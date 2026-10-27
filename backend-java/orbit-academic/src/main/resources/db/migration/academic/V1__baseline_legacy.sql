-- ============================================================================
-- orbit-academic · V1 基线（遗留 schema 导入）
--
-- 来源：对现有 Python 版数据库执行 `mysqldump --no-data orbit_schedule` 后整理。
--
-- 做了四件事（都是为了让它成为一份合格的基线）：
--   1. 按表属主拆分 —— 本文件只含 academic 拥有的 10 张表（§5.1）
--   2. 按外键依赖拓扑排序 —— dump 默认是字母序，直接执行会因外键指向
--      尚未创建的表而失败
--   3. 去掉 AUTO_INCREMENT 当前值 —— 那是开发库的数据残留，不是结构
--   4. 去掉 DROP TABLE —— Flyway 的版本化迁移只前进，不回退
--

--
-- 为什么用 CREATE TABLE IF NOT EXISTS（而不是靠 Flyway 的 baseline-on-migrate）：
--
--   最初用的是 baseline-on-migrate: true，但它在「共享库 + 按属主拆表」下是错的 ——
--   Flyway 的基线判断依据是「**整个 schema 是否为空**」，而不是「本服务的表是否存在」。
--   于是 scheduling 启动时看到 schema 里已经有 academic 的表，就误判成
--   「我的 V1 早就应用过了」，直接跳过，结果自己的 6 张表根本没建，
--   而且不报错、只多了一张空的 history 表 —— 静默失效。
--
--   改成幂等脚本后，两种库都能正确落地：
--     · 现有库（表已存在）→ 语句是 no-op，V1 记录为已应用
--     · 全新空库          → 真正建表
--
--   代价要说清楚：V1 对「已存在的表」不做结构比对，因此它<b>不具备漂移检测能力</b>。
--   这是「采纳遗留 schema」的固有性质 —— 漂移检测从 V2 开始生效。
--   基线本身的正确性由另一个手段保证：它由现有库 mysqldump 导出，逐字未改。
-- ⚠ 不重构 schema：字段类型、外键、字符集/排序规则都与现有库逐字一致。
-- ============================================================================
-- ---------------------------------------------------------------------------
-- organizations
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `organizations` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(120) NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`),
  KEY `ix_organizations_id` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `username` varchar(64) NOT NULL,
  `display_name` varchar(100) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role` varchar(20) NOT NULL,
  `is_active` tinyint(1) NOT NULL,
  `must_change_password` tinyint(1) NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `ix_users_username` (`username`),
  KEY `ix_users_id` (`id`),
  KEY `ix_users_organization_id` (`organization_id`),
  CONSTRAINT `users_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- grades
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `grades` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `name` varchar(100) NOT NULL,
  `periods_per_day` json NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_grade_org_name` (`organization_id`,`name`),
  KEY `ix_grades_id` (`id`),
  KEY `ix_grades_organization_id` (`organization_id`),
  CONSTRAINT `grades_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- subjects
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `subjects` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `name` varchar(100) NOT NULL,
  `category` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_subject_org_name` (`organization_id`,`name`),
  KEY `ix_subjects_organization_id` (`organization_id`),
  KEY `ix_subjects_id` (`id`),
  CONSTRAINT `subjects_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- classes
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `classes` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `name` varchar(100) NOT NULL,
  `grade_id` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_class_org_name` (`organization_id`,`name`),
  KEY `grade_id` (`grade_id`),
  KEY `ix_classes_id` (`id`),
  KEY `ix_classes_organization_id` (`organization_id`),
  CONSTRAINT `classes_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `classes_ibfk_2` FOREIGN KEY (`grade_id`) REFERENCES `grades` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- grade_subjects
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `grade_subjects` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `grade_id` int NOT NULL,
  `subject_id` int NOT NULL,
  `weekly_hours` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_grade_subject_org` (`organization_id`,`grade_id`,`subject_id`),
  KEY `grade_id` (`grade_id`),
  KEY `subject_id` (`subject_id`),
  KEY `ix_grade_subjects_id` (`id`),
  KEY `ix_grade_subjects_organization_id` (`organization_id`),
  CONSTRAINT `grade_subjects_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `grade_subjects_ibfk_2` FOREIGN KEY (`grade_id`) REFERENCES `grades` (`id`),
  CONSTRAINT `grade_subjects_ibfk_3` FOREIGN KEY (`subject_id`) REFERENCES `subjects` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- teachers
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `teachers` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `name` varchar(100) NOT NULL,
  `subject_id` int NOT NULL,
  `max_weekly_hours` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_teacher_org_name` (`organization_id`,`name`),
  KEY `subject_id` (`subject_id`),
  KEY `ix_teachers_organization_id` (`organization_id`),
  KEY `ix_teachers_id` (`id`),
  CONSTRAINT `teachers_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `teachers_ibfk_2` FOREIGN KEY (`subject_id`) REFERENCES `subjects` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- teacher_assignments
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `teacher_assignments` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `teacher_id` int NOT NULL,
  `class_id` int NOT NULL,
  `subject_id` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_assignment_org` (`organization_id`,`teacher_id`,`class_id`,`subject_id`),
  KEY `teacher_id` (`teacher_id`),
  KEY `class_id` (`class_id`),
  KEY `subject_id` (`subject_id`),
  KEY `ix_teacher_assignments_organization_id` (`organization_id`),
  KEY `ix_teacher_assignments_id` (`id`),
  CONSTRAINT `teacher_assignments_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `teacher_assignments_ibfk_2` FOREIGN KEY (`teacher_id`) REFERENCES `teachers` (`id`),
  CONSTRAINT `teacher_assignments_ibfk_3` FOREIGN KEY (`class_id`) REFERENCES `classes` (`id`),
  CONSTRAINT `teacher_assignments_ibfk_4` FOREIGN KEY (`subject_id`) REFERENCES `subjects` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- grade_fixed_slots
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `grade_fixed_slots` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `grade_id` int NOT NULL,
  `subject_id` int NOT NULL,
  `day_of_week` int NOT NULL,
  `period_index` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_grade_fixed_slot_org` (`organization_id`,`grade_id`,`day_of_week`,`period_index`),
  KEY `subject_id` (`subject_id`),
  KEY `ix_grade_fixed_slots_grade_id` (`grade_id`),
  KEY `ix_grade_fixed_slots_id` (`id`),
  KEY `ix_grade_fixed_slots_organization_id` (`organization_id`),
  CONSTRAINT `grade_fixed_slots_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `grade_fixed_slots_ibfk_2` FOREIGN KEY (`grade_id`) REFERENCES `grades` (`id`),
  CONSTRAINT `grade_fixed_slots_ibfk_3` FOREIGN KEY (`subject_id`) REFERENCES `subjects` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- fixed_slots
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `fixed_slots` (
  `id` int NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `class_id` int NOT NULL,
  `subject_id` int NOT NULL,
  `teacher_id` int NOT NULL,
  `day_of_week` int NOT NULL,
  `period_index` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_fixed_slot_org` (`organization_id`,`class_id`,`day_of_week`,`period_index`),
  KEY `class_id` (`class_id`),
  KEY `subject_id` (`subject_id`),
  KEY `teacher_id` (`teacher_id`),
  KEY `ix_fixed_slots_organization_id` (`organization_id`),
  KEY `ix_fixed_slots_id` (`id`),
  CONSTRAINT `fixed_slots_ibfk_1` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `fixed_slots_ibfk_2` FOREIGN KEY (`class_id`) REFERENCES `classes` (`id`),
  CONSTRAINT `fixed_slots_ibfk_3` FOREIGN KEY (`subject_id`) REFERENCES `subjects` (`id`),
  CONSTRAINT `fixed_slots_ibfk_4` FOREIGN KEY (`teacher_id`) REFERENCES `teachers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

