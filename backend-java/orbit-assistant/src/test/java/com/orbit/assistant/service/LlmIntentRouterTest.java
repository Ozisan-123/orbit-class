package com.orbit.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmIntentRouterTest {

    @Test
    void parsesResponsesApiOutput() throws Exception {
        LlmIntentRouter router = router();

        var routed = router.parseRoute("""
                {"output":[{"content":[{"type":"output_text","text":"{\\"tool\\":\\"score_timetable\\",\\"arguments\\":{\\"version_id\\":12}}"}]}]}
                """);

        assertThat(routed.tool()).isEqualTo("score_timetable");
        assertThat(routed.arguments()).containsEntry("version_id", 12);
    }

    @Test
    void acceptsCompatibilityOutputTextAndSurfacesClarification() {
        assertThatThrownBy(() -> router().parseRoute("""
                {"output_text":"{\\"tool\\":null,\\"arguments\\":{},\\"question\\":\\"请提供版本 ID\\"}"}
                """))
                .isInstanceOf(LlmIntentRouter.ClarificationRequiredException.class)
                .hasMessage("请提供版本 ID");
    }

    @Test
    void rejectsToolsOutsideAllowList() {
        assertThatThrownBy(() -> router().parseRoute("""
                {"output_text":"{\\"tool\\":\\"delete_all_data\\",\\"arguments\\":{}}"}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未授权工具");
    }

    private static LlmIntentRouter router() {
        return new LlmIntentRouter(new ObjectMapper(), "secret",
                "https://example.invalid", "test-model");
    }
}
