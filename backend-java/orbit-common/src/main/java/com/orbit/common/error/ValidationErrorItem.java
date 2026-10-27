package com.orbit.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 参数校验错误的一项 —— 刻意做成 <b>FastAPI/Pydantic 的形状</b>。
 *
 * <p>实测 Python 侧校验失败返回的是 <b>422 + detail 是数组</b>：
 * <pre>{@code
 * {"detail":[{"type":"missing","loc":["body","name"],"msg":"Field required","input":{}}]}
 * }</pre>
 *
 * <p>这跟其他错误的 {@code {"detail": "字符串"}} **完全不是一个形状**。
 * 之所以要复刻这个别扭的结构，是因为：P1 的目标是「**行为等价**」，不是「顺便改好」。
 * 一旦这里改成字符串，契约测试就会红，然后要为每个差异写例外 —— 测试也就失去意义了。
 *
 * <p>⚠ <b>文案不强求逐字</b>：Pydantic 的 {@code msg}（"Field required"）无法在 Spring 里复刻，
 * 我们用 Jakarta Validation 的注解消息（"不能为空"）。契约测试比对的是
 * <b>状态码 + 结构（detail 是数组、每项有 type/loc/msg）</b>，不是文案。
 *
 * @param type  FastAPI 的错误类型：{@code missing}（缺字段）/ {@code value_error}（值不合法）/
 *              {@code json_invalid}（body 不是合法 JSON）
 * @param loc   出错位置，格式 {@code ["body", "<字段名>"]}（FastAPI 同款）
 * @param msg   人话描述
 * @param input 触发错误的原始值；**拿不到时为 null 并省略该字段**
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationErrorItem(String type, List<Object> loc, String msg, Object input) {

    /** body 里缺字段。 */
    public static ValidationErrorItem missing(String field, String message) {
        return missing("body", field, message);
    }

    /** 指定位置（{@code body} 或 {@code query}）里缺字段。 */
    public static ValidationErrorItem missing(String location, String field, String message) {
        return new ValidationErrorItem("missing", loc(location, field), message, null);
    }

    public static ValidationErrorItem valueError(String field, String message) {
        return valueError("body", field, message);
    }

    public static ValidationErrorItem valueError(String location, String field, String message) {
        return new ValidationErrorItem("value_error", loc(location, field), message, null);
    }

    public static ValidationErrorItem jsonInvalid(String message) {
        return new ValidationErrorItem("json_invalid", List.of("body"), message, null);
    }

    /**
     * 完全自定义（用在嵌套路径等需要自己拼 {@code loc} 的场景）。
     *
     * <p>为什么要暴露它：Python(Pydantic) 对**数组元素**的报错位置是把下标当成
     * <b>独立的一段</b>，例如 {@code ["body","items",0,"weekly_hours"]}；
     * 而 Spring 给的字段路径是一整串 {@code "items[0].weeklyHours"}。
     * 两者形状不同，必须在 {@code ApiExceptionHandler} 里解析后重拼。
     */
    public static ValidationErrorItem of(String type, List<Object> loc, String message) {
        return new ValidationErrorItem(type, loc, message, null);
    }

    /**
     * 出错位置。
     *
     * <p>FastAPI 的 {@code loc} 首段是**位置来源**：{@code "body"}（请求体）或
     * {@code "query"}（查询参数）。分页参数（{@code page}/{@code size}）来自 query，
     * 所以要写成 {@code ["query","page"]}；写成 {@code ["body","page"]} 会误导排查者。
     */
    private static List<Object> loc(String location, String field) {
        return field == null ? List.of(location) : List.of(location, field);
    }
}
