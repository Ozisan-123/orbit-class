package com.orbit.common.dto;

import com.orbit.common.error.BusinessException;
import org.springframework.web.bind.annotation.BindParam;

import java.util.Set;

/**
 * 列表接口的统一查询参数（§9.2）。
 *
 * <pre>{@code
 * GET /api/teachers?page=1&size=20&keyword=张&order_by=name&order=asc
 * }</pre>
 *
 * <p>用 record + 紧凑构造器做**归一化**，而不是在 12 个 Controller 里各写一遍：
 * <ul>
 *   <li>{@code page} 缺省/&le;0 → 1</li>
 *   <li>{@code size} 缺省/&le;0 → 20；<b>上限 200</b>（防止 {@code size=999999} 打垮库与内存）</li>
 *   <li>{@code keyword} 空白 → null（避免 SQL 里拼出 {@code LIKE '%%'}）</li>
 *   <li>{@code order} 只接受 asc/desc，其余 → asc</li>
 * </ul>
 *
 * <h2>⚠ 这里必须是包装类型 Integer，不能用原始类型 int</h2>
 * Spring 对 record 做**构造器绑定**（{@code @ModelAttribute} 风格）时，
 * 请求里**没带**某个参数会把 {@code null} 传给构造器。
 * 而 {@code null} 无法转成原始类型 {@code int} —— 绑定直接失败：
 * <pre>
 * 422 {"detail":[{"type":"value_error","loc":["query","page"],
 *      "msg":"Failed to convert value of type 'null' to required type 'int'"}]}
 * </pre>
 * 后果是**所有列表接口在「不带参数」时全部 422**，而带全参数时又是好的 ——
 * 所以很容易漏测（实测确实先漏了，是「走通全链路」这一步抓出来的）。
 * 用 {@code Integer} 接住 null、再在紧凑构造器里归一化即可。
 *
 * <p>紧凑构造器保证构造完成后两个字段**一定非 null**，所以
 * {@link #pageNumber()} / {@link #pageSize()} 可以安全地当 int 用。
 *
 * <h2>⚠ 参数名要显式绑（@BindParam），不能靠字段名猜</h2>
 * record 的组件名是 camelCase（{@code orderBy}），而查询参数是 snake_case
 * （{@code order_by}）—— **两者对不上时 Spring 不会报错，只是这个参数永远是 null**。
 * 表现是「传了 {@code order_by} 但排序没变、白名单校验也没触发」。
 * 最开始就漏了这一点，是靠「传一个非法的 {@code order_by} 却没返回 400」才发现的。
 *
 * <p><b>排序字段必须走白名单</b>：{@link #orderColumn(Set, String)} 会校验，
 * 不在白名单里直接 400 —— 绝不允许把用户输入直接拼进 {@code ORDER BY}。
 */
public record PageQuery(
        @BindParam("page") Integer page,
        @BindParam("size") Integer size,
        @BindParam("keyword") String keyword,
        @BindParam("order_by") String orderBy,
        @BindParam("order") String order) {

    public static final int DEFAULT_SIZE = 20;
    /** 上限：防止 `?size=999999` 把库和内存打垮。 */
    public static final int MAX_SIZE = 200;

    public PageQuery {
        page = (page == null || page <= 0) ? 1 : page;
        size = (size == null || size <= 0) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        keyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        orderBy = (orderBy == null || orderBy.isBlank()) ? null : orderBy.trim();
        order = "desc".equalsIgnoreCase(order) ? "desc" : "asc";
    }

    /** 全量查询（只给 page/size，其余取默认）。 */
    public static PageQuery of(int page, int size) {
        return new PageQuery(page, size, null, null, null);
    }

    // ------------------------------------------------------------------ 给调用方的 int 视图

    /** 归一化后的页码（构造器保证非 null）。 */
    public int pageNumber() {
        return page;
    }

    /** 归一化后的每页条数（构造器保证非 null，且已截断到 {@link #MAX_SIZE}）。 */
    public int pageSize() {
        return size;
    }

    public boolean hasKeyword() {
        return keyword != null;
    }

    public boolean desc() {
        return "desc".equals(order);
    }

    /**
     * 是否升序。
     *
     * <p>⚠ 用它给 MyBatis-Plus 的 {@code orderBy(condition, isAsc, column)} 传参 ——
     * 第二个参数叫 {@code isAsc}，**不是** {@code isDesc}。
     * 直接把 {@link #desc()} 传进去会让语义整个反过来，
     * 而且**不会报错**（默认排序与显式排序都"有结果"，只是方向反了），
     * 只有拿数据核对时才发现。实测踩过一次。
     */
    public boolean ascending() {
        return !desc();
    }

    /** {@code LIKE %keyword%} 的匹配串；无关键字时返回 null。 */
    public String likePattern() {
        return keyword == null ? null : "%" + keyword + "%";
    }

    /**
     * 取排序字段并校验白名单。
     *
     * @param allowed  允许的字段名（**由各 Service 映射成类型安全的列引用**，不是拼进 SQL 的字符串）
     * @param fallback 未指定 orderBy 时用的默认列
     * @throws BusinessException 400 —— 指定了不在白名单里的字段
     */
    public String orderColumn(Set<String> allowed, String fallback) {
        if (orderBy == null) {
            return fallback;
        }
        if (!allowed.contains(orderBy)) {
            throw BusinessException.badRequest(
                    "不支持的排序字段: " + orderBy + "（可选: " + String.join(", ", allowed) + "）");
        }
        return orderBy;
    }
}
