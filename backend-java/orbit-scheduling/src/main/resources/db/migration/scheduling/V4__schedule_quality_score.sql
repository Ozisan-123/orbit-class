-- P5：课表质量评分快照。
-- 分数正文内同时保存 rule_set_hash 与 revision；课表被调整后再次读取评分会重新计算并覆盖。
ALTER TABLE `schedule_versions` ADD COLUMN `score` json DEFAULT NULL;
