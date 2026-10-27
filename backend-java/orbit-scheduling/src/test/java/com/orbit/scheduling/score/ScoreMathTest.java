package com.orbit.scheduling.score;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreMathTest {

    @Test
    void 无可评机会不应凭空扣分() {
        assertEquals(100, ScoreMath.satisfactionPercent(0, 0));
        assertEquals(15, ScoreMath.dimensionPoints(15, 0, 0));
    }

    @Test
    void 违规超过机会时最低归零而非负分() {
        assertEquals(0, ScoreMath.satisfactionPercent(8, 3));
        assertEquals(0, ScoreMath.dimensionPoints(20, 8, 3));
    }

    @Test
    void 半数满足按各维度分值四舍五入() {
        assertEquals(50, ScoreMath.satisfactionPercent(1, 2));
        assertEquals(8, ScoreMath.dimensionPoints(15, 1, 2));
        assertEquals(25, ScoreMath.weightedSoftPoints(50, 1));
    }

    @Test
    void 没有软规则时软规则维度记满分() {
        assertEquals(50, ScoreMath.weightedSoftPoints(0, 0));
    }
}
