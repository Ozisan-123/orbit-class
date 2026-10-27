-- ============================================================================
-- orbit-scheduling · V1 基线（遗留 schema 导入）
--
-- ⚠⚠ 前置依赖：本文件的 6 张表引用了 academic 的 5 张表 ——
--     organizations / users / classes / subjects / teachers，共 16 个外键。
--     **必须先执行完 academic 的 V1**，否则本文件会因外键指向不存在的表而失败。
--
--     这是「共享库 + 按属主拆表」的固有代价：DDL 的属主分开了，
--     但数据库层面的引用关系仍然把两个服务连在一起。
--     这里选择「让它响亮地失败」，而不是关掉 FOREIGN_KEY_CHECKS 静默建表 ——
--     后者会得到一个没有外键的库，问题要推迟到运行期才暴露。
--
-- 其余处理同 academic 的 V1：拓扑排序、去掉自增值、去掉 DROP TABLE，
-- 以及用 CREATE TABLE IF NOT EXISTS 保证幂等（原因见 academic 那个文件的表头：
-- Flyway 的 baseline-on-migrate 在共享库下会误判「V1 已应用」而静默跳过）。
-- ============================================================================
-- ---------------------------------------------------------------------------
-- scheduling_state
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `scheduling_state` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `status` varchar(20) NOT NULL,
  `message` varchar(500) NOT NULL,
  `owner_user_id` int DEFAULT NULL,
  `owner_name` varchar(100) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `job_token` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_scheduling_state_org` (`organization_id`),
  KEY `owner_user_id` (`owner_user_id`),
  KEY `ix_scheduling_state_organization_id` (`organization_id`),
  CONSTRAINT `scheduling_state_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `scheduling_state_ibfk_2` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_worker_slots
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedule_worker_slots` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int DEFAULT NULL,
  `job_token` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `organization_id` (`organization_id`),
  CONSTRAINT `schedule_worker_slots_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedules
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedules` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `class_id` int NOT NULL,
  `subject_id` int NOT NULL,
  `teacher_id` int NOT NULL,
  `day_of_week` int NOT NULL,
  `period_index` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_schedule_org` (`organization_id`,`class_id`,`day_of_week`,`period_index`),
  KEY `class_id` (`class_id`),
  KEY `subject_id` (`subject_id`),
  KEY `teacher_id` (`teacher_id`),
  KEY `ix_schedules_organization_id` (`organization_id`),
  KEY `ix_schedules_id` (`id`),
  CONSTRAINT `schedules_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `schedules_ibfk_2` FOREIGN KEY (`class_id`) REFERENCES `classes` (`id`),
  CONSTRAINT `schedules_ibfk_3` FOREIGN KEY (`subject_id`) REFERENCES `subjects` (`id`),
  CONSTRAINT `schedules_ibfk_4` FOREIGN KEY (`teacher_id`) REFERENCES `teachers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_versions
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedule_versions` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `version_no` int NOT NULL,
  `name` varchar(120) NOT NULL,
  `description` text NOT NULL,
  `status` varchar(20) NOT NULL,
  `revision` int NOT NULL,
  `created_by` int DEFAULT NULL,
  `published_by` int DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `published_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_schedule_version_org_no` (`organization_id`,`version_no`),
  KEY `created_by` (`created_by`),
  KEY `published_by` (`published_by`),
  KEY `ix_schedule_versions_status` (`status`),
  KEY `ix_schedule_versions_organization_id` (`organization_id`),
  KEY `ix_schedule_versions_id` (`id`),
  CONSTRAINT `schedule_versions_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `schedule_versions_ibfk_2` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`),
  CONSTRAINT `schedule_versions_ibfk_3` FOREIGN KEY (`published_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_version_entries
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedule_version_entries` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `version_id` int NOT NULL,
  `class_id` int NOT NULL,
  `subject_id` int NOT NULL,
  `teacher_id` int NOT NULL,
  `day_of_week` int NOT NULL,
  `period_index` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_version_class_slot` (`version_id`,`class_id`,`day_of_week`,`period_index`),
  KEY `subject_id` (`subject_id`),
  KEY `ix_schedule_version_entries_id` (`id`),
  KEY `ix_schedule_version_entries_version_id` (`version_id`),
  KEY `ix_schedule_version_entries_class_id` (`class_id`),
  KEY `ix_version_teacher_slot` (`version_id`,`teacher_id`,`day_of_week`,`period_index`),
  KEY `ix_schedule_version_entries_teacher_id` (`teacher_id`),
  KEY `ix_schedule_version_entries_organization_id` (`organization_id`),
  CONSTRAINT `schedule_version_entries_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `schedule_version_entries_ibfk_2` FOREIGN KEY (`version_id`) REFERENCES `schedule_versions` (`id`) ON DELETE CASCADE,
  CONSTRAINT `schedule_version_entries_ibfk_3` FOREIGN KEY (`class_id`) REFERENCES `classes` (`id`),
  CONSTRAINT `schedule_version_entries_ibfk_4` FOREIGN KEY (`subject_id`) REFERENCES `subjects` (`id`),
  CONSTRAINT `schedule_version_entries_ibfk_5` FOREIGN KEY (`teacher_id`) REFERENCES `teachers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- schedule_audit_logs
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `schedule_audit_logs` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `version_id` int NOT NULL,
  `user_id` int DEFAULT NULL,
  `user_name` varchar(100) NOT NULL,
  `action` varchar(30) NOT NULL,
  `details` json NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`),
  KEY `ix_schedule_audit_logs_id` (`id`),
  KEY `ix_schedule_audit_logs_version_id` (`version_id`),
  KEY `ix_schedule_audit_logs_organization_id` (`organization_id`),
  CONSTRAINT `schedule_audit_logs_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `schedule_audit_logs_ibfk_2` FOREIGN KEY (`version_id`) REFERENCES `schedule_versions` (`id`) ON DELETE CASCADE,
  CONSTRAINT `schedule_audit_logs_ibfk_3` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

