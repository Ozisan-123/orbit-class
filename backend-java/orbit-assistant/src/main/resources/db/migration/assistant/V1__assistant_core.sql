CREATE TABLE IF NOT EXISTS `assistant_conversations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `user_id` int NOT NULL,
  `title` varchar(120) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_assistant_conversations_user` (`organization_id`,`user_id`,`updated_at`),
  CONSTRAINT `assistant_conversations_org_fk` FOREIGN KEY (`organization_id`) REFERENCES `organizations` (`id`),
  CONSTRAINT `assistant_conversations_user_fk` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `assistant_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint NOT NULL,
  `organization_id` int NOT NULL,
  `user_id` int NOT NULL,
  `turn_index` int NOT NULL,
  `role` varchar(16) NOT NULL,
  `content` text NOT NULL,
  `token_count` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_assistant_messages_conversation` (`conversation_id`,`turn_index`,`id`),
  KEY `ix_assistant_messages_usage` (`organization_id`,`created_at`),
  CONSTRAINT `assistant_messages_conversation_fk` FOREIGN KEY (`conversation_id`) REFERENCES `assistant_conversations` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `assistant_actions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `user_id` int NOT NULL,
  `conversation_id` bigint NOT NULL,
  `turn_index` int NOT NULL,
  `tool_name` varchar(64) NOT NULL,
  `arguments_json` json NOT NULL,
  `result_json` json DEFAULT NULL,
  `proposal_json` json DEFAULT NULL,
  `status` varchar(24) NOT NULL,
  `expected_revision` int DEFAULT NULL,
  `final_revision` int DEFAULT NULL,
  `error_detail` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `decided_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_assistant_actions_conversation` (`conversation_id`,`turn_index`,`id`),
  KEY `ix_assistant_actions_status` (`organization_id`,`status`,`created_at`),
  CONSTRAINT `assistant_actions_conversation_fk` FOREIGN KEY (`conversation_id`) REFERENCES `assistant_conversations` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `rule_generation_attempts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `organization_id` int NOT NULL,
  `user_id` int NOT NULL,
  `action_id` bigint NOT NULL,
  `original_text` text NOT NULL,
  `generated_rule` json NOT NULL,
  `accepted` tinyint(1) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `decided_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_rule_generation_attempts_org` (`organization_id`,`created_at`),
  CONSTRAINT `rule_generation_attempts_action_fk` FOREIGN KEY (`action_id`) REFERENCES `assistant_actions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
