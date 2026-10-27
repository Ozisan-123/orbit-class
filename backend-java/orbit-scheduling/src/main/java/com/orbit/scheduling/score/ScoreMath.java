package com.orbit.scheduling.score;

/** 评分取整口径集中在这里，避免各维度各写一套边界处理。 */
final class ScoreMath {

    private ScoreMath() {
    }

    static int satisfactionPercent(long violations, long opportunities) {
        if (opportunities <= 0) return 100;
        return (int) Math.round(100.0 * Math.max(0, opportunities - violations) / opportunities);
    }

    static int dimensionPoints(int maxPoints, long violations, long opportunities) {
        return (int) Math.round(maxPoints
                * satisfactionPercent(violations, opportunities) / 100.0);
    }

    static int weightedSoftPoints(long weightedPercentSum, long totalWeight) {
        return totalWeight == 0 ? 50
                : (int) Math.round(50.0 * weightedPercentSum / (100.0 * totalWeight));
    }
}
