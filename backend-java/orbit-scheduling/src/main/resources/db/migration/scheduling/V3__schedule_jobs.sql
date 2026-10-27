-- ============================================================================
-- orbit-scheduling · V3：求解隔离（P3）
--
-- 本文件把"排课"从**进程内线程**改成**持久任务队列 + 独立求解进程**：
--   1. scheduling_snapshots    排课输入快照（冻结的 ScheduleInput + 指纹）
--   2. schedule_jobs           持久任务队列（SKIP LOCKED 领单、租约、重试、取消）
--   3. schedule_job_events     任务事件流（启动/领单/心跳/完成/失败/接管/取消）
--
-- ⚠ 建表顺序有讲究：schedule_jobs 有外键指向 scheduling_snapshots，
--   **被引用的表必须先建**，否则报 "Failed to open the referenced table"。
--   （V1 里 academic/scheduling 之间的 16 个外键也是同样的道理，
--     所以 start.cmd 里 academic 必须先于 scheduling 启动。）
--
-- ⚠ 为什么要有队列，而不是继续用线程：
--   线程版有三个治不好的问题 ——
--     ① 进程重启 → 正在跑的任务凭空消失（没有任何记录说明它存在过）
--     ② 求解吃满 CPU 时会和 Web 请求抢核（§2.3 说这是"拆分理由最强"的一条）
--     ③ 无法回答"排队中吗 / 排第几 / 上次为什么失败"
--
-- ⚠⚠ **谁写这三张表**：只有 scheduling。
--   方案 §2.2 的服务清单写明 solver-worker「拥有的表：**无**」，
--   所以 worker **不做** `SELECT ... FOR UPDATE SKIP LOCKED`（那是在写 schedule_jobs），
--   而是通过 scheduling 的内部接口领单：`POST /internal/jobs/claim`。
--   这样铁律 2（禁止跨服务写库）在 P3 仍然成立，代价是每次领单多一次 HTTP。
--
-- 前置依赖同 V1/V2：organization_id 外键指向 academic 的 organizations 表。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- scheduling_snapshots —— 排课输入快照（必须最先建，被 schedule_jobs 引用）
--
-- 方案 §3 铁律 3「排课输入用快照」的**真正落地**。
--
-- P1/P2 时 `ScheduleInputLoader` 只是"现查一遍"（与 Python 一致，同一个库），
-- 那时求解就在同一个请求链路里发生，看不出问题。一旦求解挪到**独立进程**、
-- 可能排队几分钟后才开始跑，就必须冻结输入：否则"排队期间教务数据被改了"
-- 会导致排出来的课表对应的是另一份数据，且事后无法复现。
--
-- fingerprint 是对 payload 的规范化哈希：同一份输入 → 同一个指纹，
-- 于是"这次求解用的到底是哪份数据"可以和 model_fingerprint / rule_set_hash 一起进审计链。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `scheduling_snapshots` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `fingerprint` char(64) NOT NULL,
  `payload` json NOT NULL,
  `grade_count` int NOT NULL DEFAULT 0,
  `class_count` int NOT NULL DEFAULT 0,
  `subject_count` int NOT NULL DEFAULT 0,
  `teacher_count` int NOT NULL DEFAULT 0,
  `assignment_count` int NOT NULL DEFAULT 0,
  `grade_subject_count` int NOT NULL DEFAULT 0,
  `fixed_slot_count` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_snapshots_org` (`organization_id`, `id`),
  KEY `ix_snapshots_fingerprint` (`organization_id`, `fingerprint`),
  CONSTRAINT `scheduling_snapshots_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_jobs —— 持久任务队列
--
-- 状态机：pending → running → done | failed | cancelled
--                    ↑________|  （租约过期后可被重新领走，attempt +1）
--
-- 三个关键字段：
--   * job_token    —— **栅栏**。worker 每次回写都要带上它；
--                     任务被接管后旧 worker 的 token 就失效了，它的结果会被静默丢弃。
--   * lease_until  —— **租约**。worker 每 15 秒续一次；没续上说明它可能死了，
--                     别的 worker 可以领走（这就是"接管"）。
--   * attempt      —— 重试次数。超过 max_attempts 就不再自动重试，交给人工判断。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedule_jobs` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `job_token` varchar(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `rule_set_hash` char(64) NOT NULL,
  `snapshot_id` int DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `heartbeat_at` datetime DEFAULT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `attempt` int NOT NULL DEFAULT 0,
  `max_attempts` int NOT NULL DEFAULT 3,
  `progress` int NOT NULL DEFAULT 0,
  `cancel_requested` tinyint(1) NOT NULL DEFAULT 0,
  `last_error` varchar(500) DEFAULT NULL,
  `solver_status` varchar(32) DEFAULT NULL,
  `model_fingerprint` varchar(64) DEFAULT NULL,
  `objective_value` bigint DEFAULT NULL,
  `version_id` int DEFAULT NULL,
  `entry_count` int DEFAULT NULL,
  `created_by` int DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_schedule_jobs_token` (`job_token`),
  -- 领单走这个索引：`WHERE status='pending' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED`
  KEY `ix_schedule_jobs_queue` (`status`, `id`),
  KEY `ix_schedule_jobs_org` (`organization_id`, `id`),
  CONSTRAINT `schedule_jobs_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `schedule_jobs_ibfk_2` FOREIGN KEY (`snapshot_id`) REFERENCES `scheduling_snapshots` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_job_events —— 任务事件流
--
-- 它不是"日志"（日志已经有了），而是**结构化的状态迁移记录**。
-- 用途有三个，都不是"看看而已"：
--   * 「这个任务为什么跑了 3 次」→ 看 attempt 与 takeover 事件
--   * 「求解到底卡在哪」→ 看 heartbeat 事件的**时间间隔**（有没有断）
--   * 压测与故障注入时统计"接管成功率"
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedule_job_events` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `job_id` int NOT NULL,
  `event` varchar(32) NOT NULL,
  `worker_id` varchar(64) DEFAULT NULL,
  `attempt` int DEFAULT NULL,
  `detail` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_job_events_job` (`job_id`, `id`),
  CONSTRAINT `schedule_job_events_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_versions 加求解溯源两列
--
-- 有了它们，「这版课表是怎么来的」才完整：
--   snapshot_fingerprint —— 用的哪份输入
--   model_fingerprint    —— 编译出的模型（CpModelProto 指纹）
--   加上 P2 已有的 rule_set_hash —— 用的哪套规则
-- 三者合起来，任何一版课表都能被精确复现。
-- ---------------------------------------------------------------------------
ALTER TABLE `schedule_versions` ADD COLUMN `snapshot_fingerprint` char(64) DEFAULT NULL;
ALTER TABLE `schedule_versions` ADD COLUMN `model_fingerprint` char(64) DEFAULT NULL;
