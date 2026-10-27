package com.orbit.assistant.tool;

import com.orbit.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentRouterTest {
    private final IntentRouter router = new IntentRouter();

    @Test
    void routesAvailabilityWithZeroBasedDayAndPeriod() {
        IntentRouter.Routed routed = router.route(
                "教师 3 在版本 12 周三第 2 节是否空闲", null, null);

        assertThat(routed.tool()).isEqualTo("check_teacher_availability");
        assertThat(routed.arguments()).containsEntry("teacher_id", 3L)
                .containsEntry("version_id", 12L)
                .containsEntry("day", 2L)
                .containsEntry("period", 1L);
    }

    @Test
    void routesRuleAsProposalInsteadOfDirectWrite() {
        IntentRouter.Routed routed = router.route(
                "提议规则 教师 7 周三第 4 节不可用，硬规则 权重 9", null, null);

        assertThat(routed.tool()).isEqualTo("propose_rule");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) routed.arguments().get("request");
        assertThat(request).containsEntry("rule_type", "teacher_unavailable")
                .containsEntry("hardness", "hard")
                .containsEntry("weight", 9L);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        assertThat(params)
                .containsEntry("teacher_id", 7L)
                .containsEntry("day", 3L)
                .containsEntry("period", 4L);
    }

    @Test
    void neverGuessesMissingVersion() {
        assertThatThrownBy(() -> router.route("教师 3 周三第 2 节是否空闲", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本 ID");
    }
}
