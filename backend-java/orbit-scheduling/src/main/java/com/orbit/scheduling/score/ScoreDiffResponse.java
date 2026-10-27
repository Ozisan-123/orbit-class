package com.orbit.scheduling.score;

import java.util.List;

/** 两版课表评分对比；正数表示当前版本更好。 */
public record ScoreDiffResponse(
        ScoreResponse current,
        ScoreResponse against,
        int totalDelta,
        List<DimensionDelta> dimensions) {

    public record DimensionDelta(String key, String label, int currentPoints,
                                 int againstPoints, int delta) {
    }
}
