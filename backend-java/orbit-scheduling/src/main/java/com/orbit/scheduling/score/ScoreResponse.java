package com.orbit.scheduling.score;

import java.time.LocalDateTime;
import java.util.List;

/** 一版课表的可解释质量评分。 */
public record ScoreResponse(
        Long versionId,
        Integer revision,
        String ruleSetHash,
        boolean valid,
        int totalScore,
        List<String> hardViolations,
        List<Dimension> dimensions,
        List<RuleMetric> ruleBreakdown,
        LocalDateTime computedAt) {

    public record Dimension(
            String key,
            String label,
            int maxPoints,
            int earnedPoints,
            long actual,
            long opportunities,
            int deduction,
            String explanation) {
    }

    public record RuleMetric(String ruleType, String hardness, int weight,
                             long violations, long opportunities,
                             int satisfactionPercent, String explanation) {
    }
}
