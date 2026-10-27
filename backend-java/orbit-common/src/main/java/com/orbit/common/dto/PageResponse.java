package com.orbit.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 列表接口的统一响应结构（§9.2）。
 *
 * <pre>{@code
 * GET /api/teachers?page=1&size=20  →  { "items": [ ... ], "total": 123 }
 * }</pre>
 *
 * <h2>为什么必须替换掉裸数组</h2>
 * Python 原版所有列表接口都是 `response_model=list[XxxOut]` —— 返回**裸数组**、
 * 且**完全忽略分页参数**（实测：`GET /api/grades?page=1&size=1` 仍然返回全部 5 条）。
 * 前端因此只能靠 antd 的**前端分页**：一次拉全量、在浏览器内存里切片。
 * 现在数据规模是 4 个班，但目标是 160 班规模的多租户 SaaS ——
 * 一个年级的课表条目就接近 4,800 行，必须真分页。
 *
 * <p><b>刻意不做</b>「有分页参数时包一层、无参数时返回裸数组」的混合形态：
 * 同一接口两种返回结构会让前端必须写两个分支，契约测试也无法覆盖。
 *
 * <p>代价（已在方案 §7.1 记录）：这是一次**响应结构变更**，前端
 * {@code api/index.ts} 里约 12 个列表函数都要加一层解包 —— 所以
 * 「迁移期前端零改动」这个承诺作废，改成「迁移期前端只适配分页」。
 *
 * @param items 当前页数据
 * @param total 满足条件的总条数（<b>不是</b>当前页条数）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PageResponse<T>(List<T> items, long total) {

    public PageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResponse<T> of(List<T> items, long total) {
        return new PageResponse<>(items, total);
    }

    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), 0L);
    }

    /**
     * 从 MyBatis-Plus 的分页对象构造。
     *
     * <p>刻意不在这里 import {@code com.baomidou.mybatisplus...}：{@code orbit-common}
     * 是个薄共享模块（见它的 pom 注释——只引 {@code spring-web}，不给 WebFlux 网关带 Servlet 容器）。
     * 让 common 依赖 MyBatis-Plus 会把这个约束破坏掉。
     * 调用方写 {@code PageResponse.of(page.getRecords(), page.getTotal())} 即可。
     */
    public static <T> PageResponse<T> of(List<T> items, Number total) {
        return new PageResponse<>(items, total == null ? 0L : total.longValue());
    }

    /**
     * 当前页是否为空。
     *
     * <p>⚠ {@code @JsonIgnore} 不能省：Jackson 会把任何 {@code isEmpty()} 当成
     * 属性 {@code empty} 的 getter，于是响应变成
     * {@code {"items":[...],"total":6,"empty":false}} —— 多出一个 **Python 侧没有的字段**。
     * 不是崩溃型错误，但会让契约测试产生噪音，也可能让前端的类型校验失败。
     * 实测踩过一次（它是 getter 形态，很容易忽略）。
     */
    @JsonIgnore
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
