package com.orbit.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageResponse} 的序列化形状测试。
 *
 * <p>契约是 {@code {"items": [...], "total": N}} —— **一个字段都不能多、一个都不能少**。
 *
 * <p>为什么专门测「字段个数」：{@link PageResponse#isEmpty()} 这种便利方法
 * 会被 Jackson 当成属性 {@code empty} 的 getter，于是响应悄悄多出一个
 * {@code "empty": false}。它不是崩溃型错误，只会让契约比对出现噪音 ——
 * 而这类"多一个字段"的问题，靠肉眼看响应很容易漏掉。
 */
class PageResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("序列化只有 items 与 total 两个字段（isEmpty 不能泄漏成 empty 属性）")
    void serializesExactlyTwoFields() throws Exception {
        String json = objectMapper.writeValueAsString(
                PageResponse.of(List.of(Map.of("id", 1)), 42L));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        assertEquals(2, parsed.size(), "字段集合必须恰好是 {items,total}，实际: " + parsed.keySet());
        assertTrue(parsed.containsKey("items"));
        assertTrue(parsed.containsKey("total"));
        assertFalse(parsed.containsKey("empty"), "isEmpty() 被当成了 getter，响应会多出 empty 字段");
        assertEquals(42, parsed.get("total"));
    }

    @Test
    @DisplayName("空结果是 items:[], total:0（而不是 null）")
    void emptyResultShape() throws Exception {
        String json = objectMapper.writeValueAsString(PageResponse.empty());
        assertEquals("{\"items\":[],\"total\":0}", json);
    }

    @Test
    @DisplayName("构造时 items 为 null 会归一成空列表，不会序列化成 null")
    void nullItemsBecomesEmptyList() throws Exception {
        String json = objectMapper.writeValueAsString(new PageResponse<>(null, 0L));
        assertEquals("{\"items\":[],\"total\":0}", json);
    }

    @Test
    @DisplayName("items 是不可变副本（外部 List 改动不会影响已构造的响应）")
    void itemsAreCopied() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        PageResponse<String> response = PageResponse.of(mutable, 1L);
        mutable.add("b");
        assertEquals(1, response.items().size(), "构造时应做防御性拷贝");
    }

    @Test
    @DisplayName("Number 重载能吃下 MyBatis-Plus 的 Long total，也能吃下 null")
    void numberOverload() {
        assertEquals(7L, PageResponse.of(List.of(), (Number) 7L).total());
        assertEquals(0L, PageResponse.of(List.of(), (Number) null).total());
    }
}
