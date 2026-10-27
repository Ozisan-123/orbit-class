package com.orbit.common.dto;

import com.orbit.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageQuery} 的归一化规则测试。
 *
 * <p>这些断言都有**对应的实测事故**，不是凭空写的边界用例：
 * <ul>
 *   <li>{@link #allNullUsesDefaults} —— 原始类型 {@code int} 接不住 null 时，
 *       所有列表接口在不带参数的情况下会 422。这是回归测试，防止有人"顺手"改回 int</li>
 *   <li>{@link #sizeIsCapped} —— {@code ?size=999999} 会把整表拉进内存</li>
 *   <li>{@link #ascendingIsNotDesc} —— 传给 MyBatis-Plus 的第二个参数叫 isAsc，
 *       传错方向不会报错、只会排序相反</li>
 *   <li>{@link #orderColumnRejectsUnknown} —— 排序字段必须走白名单，
 *       否则用户输入会被拼进 ORDER BY</li>
 * </ul>
 */
class PageQueryTest {

    @Test
    @DisplayName("全部参数为 null（不带任何查询参数）时用默认值，且不抛异常")
    void allNullUsesDefaults() {
        PageQuery q = new PageQuery(null, null, null, null, null);
        assertEquals(1, q.pageNumber());
        assertEquals(PageQuery.DEFAULT_SIZE, q.pageSize());
        assertNull(q.keyword());
        assertNull(q.orderBy());
        assertEquals("asc", q.order());
        assertFalse(q.desc());
        assertTrue(q.ascending());
    }

    @Test
    @DisplayName("Spring 绑定 record 时未提供的参数会传 null —— 这正是必须用包装类型的原因")
    void nullComponentMustNotThrow() {
        // 用过原始类型 int 时，这里会抛 NullPointerException；
        // 而在真实运行时它表现为 422「Failed to convert value of type 'null' to required type 'int'」
        PageQuery pageMissing = new PageQuery(null, 5, null, null, null);
        assertEquals(1, pageMissing.pageNumber(), "page 缺失 → 1");
        assertEquals(5, pageMissing.pageSize(), "给了的 size 不应被覆盖");

        PageQuery sizeMissing = new PageQuery(3, null, null, null, null);
        assertEquals(3, sizeMissing.pageNumber());
        assertEquals(PageQuery.DEFAULT_SIZE, sizeMissing.pageSize(), "size 缺失 → 默认 20");
    }

    @Test
    @DisplayName("page/size 非正数归一到默认值")
    void nonPositiveFallsBack() {
        assertEquals(1, new PageQuery(0, 10, null, null, null).pageNumber());
        assertEquals(1, new PageQuery(-3, 10, null, null, null).pageNumber());
        assertEquals(PageQuery.DEFAULT_SIZE, new PageQuery(1, 0, null, null, null).pageSize());
        assertEquals(PageQuery.DEFAULT_SIZE, new PageQuery(1, -9, null, null, null).pageSize());
    }

    @Test
    @DisplayName("size 上限 200：?size=999999 被截断，防止整表进内存")
    void sizeIsCapped() {
        assertEquals(PageQuery.MAX_SIZE, new PageQuery(1, 999_999, null, null, null).pageSize());
        assertEquals(PageQuery.MAX_SIZE, new PageQuery(1, 201, null, null, null).pageSize());
        assertEquals(200, new PageQuery(1, 200, null, null, null).pageSize(), "正好 200 不应被改");
        assertEquals(7, new PageQuery(1, 7, null, null, null).pageSize());
    }

    @Test
    @DisplayName("order 只认 asc/desc（大小写不敏感），其余一律当 asc")
    void orderNormalization() {
        assertEquals("desc", new PageQuery(1, 10, null, null, "desc").order());
        assertEquals("desc", new PageQuery(1, 10, null, null, "DESC").order());
        assertEquals("desc", new PageQuery(1, 10, null, null, "Desc").order());
        assertEquals("asc", new PageQuery(1, 10, null, null, "asc").order());
        assertEquals("asc", new PageQuery(1, 10, null, null, "ASC").order());
        // 垃圾值不能让 SQL 出问题，也不能 500
        assertEquals("asc", new PageQuery(1, 10, null, null, "'; DROP TABLE users; --").order());
        assertEquals("asc", new PageQuery(1, 10, null, null, "").order());
    }

    @Test
    @DisplayName("ascending() 与 desc() 互斥 —— 它是给 MyBatis-Plus 的 isAsc 用的")
    void ascendingIsNotDesc() {
        PageQuery asc = new PageQuery(1, 10, null, null, "asc");
        PageQuery desc = new PageQuery(1, 10, null, null, "desc");
        assertTrue(asc.ascending());
        assertFalse(asc.desc());
        assertFalse(desc.ascending());
        assertTrue(desc.desc());
        // 默认是升序（对应 Python 侧无 ORDER BY 时实际返回的主键序，
        // 也是分页所必须的稳定顺序）
        assertTrue(new PageQuery(1, 10, null, null, null).ascending());
    }

    @Test
    @DisplayName("keyword 去空白；全空白视为没有关键字（避免 LIKE '%%'）")
    void keywordNormalization() {
        assertNull(new PageQuery(1, 10, null, null, null).keyword());
        assertNull(new PageQuery(1, 10, "", null, null).keyword());
        assertNull(new PageQuery(1, 10, "   ", null, null).keyword());
        assertEquals("张", new PageQuery(1, 10, "  张  ", null, null).keyword());
        assertTrue(new PageQuery(1, 10, "张", null, null).hasKeyword());
        assertFalse(new PageQuery(1, 10, "  ", null, null).hasKeyword());
        assertEquals("%张%", new PageQuery(1, 10, "张", null, null).likePattern());
        assertNull(new PageQuery(1, 10, " ", null, null).likePattern());
    }

    @Test
    @DisplayName("order_by 走白名单：未指定取默认列，在名单内放行，不在名单内 400")
    void orderColumnRejectsUnknown() {
        Set<String> allowed = Set.of("id", "name", "category");

        assertEquals("id", new PageQuery(1, 10, null, null, null).orderColumn(allowed, "id"),
                "未指定时用 fallback");
        assertEquals("name", new PageQuery(1, 10, null, "name", null).orderColumn(allowed, "id"));
        assertEquals("category", new PageQuery(1, 10, null, "category", null).orderColumn(allowed, "id"));

        // 关键：不在白名单里必须拒绝，而不是忽略。
        // 忽略的话，用户会以为排序生效了（之前就踩过这个：order_by 因为没绑定而被静默忽略）
        BusinessException e = assertThrows(BusinessException.class,
                () -> new PageQuery(1, 10, null, "organization_id", null).orderColumn(allowed, "id"));
        assertEquals(400, e.status());
        assertTrue(e.detail().contains("organization_id"), e.detail());
        assertTrue(e.detail().contains("可选"), "提示里要列出可选字段: " + e.detail());

        // SQL 注入式输入同样被白名单挡住
        assertThrows(BusinessException.class,
                () -> new PageQuery(1, 10, null, "id; DROP TABLE users", null).orderColumn(allowed, "id"));
    }

    @Test
    @DisplayName("order_by 去空白；全空白视为未指定")
    void orderByNormalization() {
        assertNull(new PageQuery(1, 10, null, "   ", null).orderBy());
        assertEquals("name", new PageQuery(1, 10, null, "  name ", null).orderBy());
    }

    @Test
    @DisplayName("of(page, size) 便捷构造")
    void factoryMethod() {
        PageQuery q = PageQuery.of(3, 15);
        assertEquals(3, q.pageNumber());
        assertEquals(15, q.pageSize());
        assertEquals("asc", q.order());
    }
}
