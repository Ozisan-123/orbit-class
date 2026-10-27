-- ============================================================================
-- orbit-scheduling · V2：规则系统（P2）
--
-- 本文件引入 P2 的四样东西：
--   1. constraint_rules           规则表（约束数据化 —— 从硬编码变成用户可编辑的数据）
--   2. constraint_rule_versions   规则集版本（冻结快照 + rule_set_hash）
--   3. schedule_diagnoses         不可行性诊断结果
--   4. schedule_versions 加一列    rule_set_hash（这版课表是哪套规则排出来的）
--
-- ⚠ 前置依赖同 V1：organization_id 外键指向 academic 的 organizations 表，
--    所以 **academic 的迁移必须先执行完**。
--
-- ⚠ 与 V1 的一处不同：V1 是"遗留 schema 导入"，所以要靠 CREATE TABLE IF NOT EXISTS
--    兜住 baseline-on-migrate 的误判；V2 是**真正的新增变更**，由 Flyway 保证只执行一次，
--    因此最后的 ALTER TABLE 不加 IF NOT EXISTS（MySQL 8 也不支持这个语法）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- constraint_rules —— 规则定义
--
-- 「规则 = 数据（用户可编辑）+ 实现（开发者写一次的 handler）」里的那一半数据。
-- rule_type 是派发键，对应 solver-core 里某个 RuleHandler.type()。
--
-- 三个刻意的设计（与方案 §6.1 的「三个约定」一一对应）：
--   * hardness / weight 是**列**而不是 params 里的字段 —— 同一条规则既可能被当硬约束
--     也可能被当软偏好，那是"规则实例"的属性，不是"规则类型"的属性。
--     放进 params 会和表结构重复，产生两个真相。
--   * scope / scope_id 表达"这条规则管谁"：org 级 scope_id 为 NULL，
--     grade/class/teacher/subject 级指向对应表的 id。
--     ⚠ 但 P2 求解只用 org 级规则 —— 细粒度作用域要先解决"同一条规则在多个 scope 上叠加"
--     的语义（见 §15 记录），本期只落结构、不放开写入。
--   * version 是乐观锁（对应接口里的 expected_revision）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `constraint_rules` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `rule_type` varchar(64) NOT NULL,
  `params` json NOT NULL,
  `hardness` varchar(8) NOT NULL,
  `weight` int NOT NULL DEFAULT 1,
  `scope` varchar(16) NOT NULL,
  `scope_id` int DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `description` varchar(255) DEFAULT NULL,
  `source` varchar(16) NOT NULL DEFAULT 'manual',
  `version` int NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_constraint_rules_org_enabled` (`organization_id`, `enabled`),
  CONSTRAINT `constraint_rules_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- constraint_rule_versions —— 规则集版本
--
-- 每次规则表发生变更（新建/编辑/启停/删除）就重算一次 `rule_set_hash`：
--   * 哈希已存在 → 不新建版本（改回去等于没改）
--   * 哈希是新的 → version_no = max+1，并把**当时的全部规则冻结进 rules 列**
--
-- ⚠ 为什么要冻结整份快照，而不是只存 hash？
--    因为审计链要求「这版课表用的哪套规则」**任何时候都能点开**。
--    只存 hash 的话，规则后来被删掉/改掉，历史就无法还原了。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `constraint_rule_versions` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `version_no` int NOT NULL,
  `rule_set_hash` char(64) NOT NULL,
  `rules` json NOT NULL,
  `rule_count` int NOT NULL,
  `note` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_rule_version_org_no` (`organization_id`, `version_no`),
  UNIQUE KEY `uq_rule_version_org_hash` (`organization_id`, `rule_set_hash`),
  CONSTRAINT `constraint_rule_versions_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_diagnoses —— 不可行性诊断结果
--
-- ⚠ **必须落库**（方案 §5.2）：最小冲突集要"软化硬约束重求解"，很贵；
--    每次前端刷新都重算不可接受。
--
-- kind 区分三件事，这个区分是本期新增的能力：
--   INFEASIBLE  求解器**证明了**无解（CP-SAT 返回 INFEASIBLE）
--   UNKNOWN     到了时限还没证明 —— 与"真无解"是**两回事**，不能混为一谈
--   OK          有解（诊断里可能仍有 warning，比如教师刚好顶到上限）
--
-- ⚠ job_token 可为 NULL：按需诊断（还没发起排课时先问"为什么排不出来"）没有任务身份。
--    P3 引入 schedule_jobs 后会再加 job_id 列，届时接口从
--    GET /api/schedule/diagnosis 演进为 GET /api/schedule/jobs/{id}/diagnosis。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedule_diagnoses` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `job_token` varchar(64) DEFAULT NULL,
  `version_id` int DEFAULT NULL,
  `kind` varchar(32) NOT NULL,
  `severity` varchar(16) NOT NULL,
  `payload` json NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_diagnosis_org_token` (`organization_id`, `job_token`),
  CONSTRAINT `schedule_diagnoses_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_versions 加 rule_set_hash
--
-- 方案 §5.2 的原话：「评分必须与 rule_set_hash 绑定 —— 同一张课表在不同规则集下
-- 分数不同，**不记 hash 的分数没有意义**」。
-- 所以 P2 先把 hash 列落地（本期就要用：generate 时记录用了哪套规则）；
-- `score JSON` 那一列等 P4/P5 真正有评分时再加，不提前占位。
-- ---------------------------------------------------------------------------
ALTER TABLE `schedule_versions` ADD COLUMN `rule_set_hash` char(64) DEFAULT NULL;
