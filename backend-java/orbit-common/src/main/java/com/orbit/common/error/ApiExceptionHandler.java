package com.orbit.common.error;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局异常 → 统一错误体。
 *
 * <h2>为什么必须有这个类</h2>
 * Spring Boot 的默认错误响应是 {@code ProblemDetail}：
 * <pre>{@code
 * {"type":"about:blank","title":"Not Found","status":404,"detail":"...","instance":"/api/x"}
 * }</pre>
 * 而 Python 侧一律是 {@code {"detail": "人话"}}。前端读的是 {@code error.response.data.detail} ——
 * 虽然 Spring 的默认体里也有 {@code detail}，但**多出的字段、不同的 {@code type} 语义、
 * 以及 401/403 的默认行为**都会让契约测试产生大量噪音。§9 明确要求不用 {@code ProblemDetail}。
 *
 * <h2>状态码映射（都对着 Python 实测过）</h2>
 * <table>
 *   <tr><th>异常</th><th>返回</th><th>依据</th></tr>
 *   <tr><td>{@link BusinessException}</td><td>它自己的状态码 + {@code {"detail": "..."}}</td><td>对应 Python 的 {@code HTTPException}</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td><b>422</b> + {@code {"detail": [...]}}</td><td>FastAPI/Pydantic 的 422</td></tr>
 *   <tr><td>{@link HttpMessageNotReadableException}</td><td><b>422</b> + {@code [{"type":"json_invalid"}]}</td><td>实测：body 非法 JSON 时 FastAPI 返回 422</td></tr>
 *   <tr><td>{@link HttpRequestMethodNotSupportedException}</td><td>405 + {@code {"detail":"Method Not Allowed"}}</td><td>实测：`GET /api/grades/999999` → 405</td></tr>
 *   <tr><td>其他</td><td>500 + {@code {"detail":"服务器内部错误"}}</td><td>Python 未捕获异常时 uvicorn 返回纯文本；这里给 JSON 更好用，是有意的偏离</td></tr>
 * </table>
 *
 * <p>⚠ 注意 422 的 {@code detail} 是**数组**，而其他错误的 {@code detail} 是**字符串** ——
 * 这是 Python 侧的既有形状，不是笔误。见 {@link ValidationErrorItem}。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 422。
     *
     * <p>刻意用 {@code HttpStatusCode.valueOf(422)} 而不是
     * {@code ResponseEntity.unprocessableEntity()}：后者在 Spring Framework 7 里**已过时** ——
     * 因为 RFC 9110 把 422 的含义名从 "Unprocessable Entity" 改成了 "Unprocessable Content"，
     * Spring 跟着弃用了旧名字的便捷方法。
     *
     * <p>用数字不丢可读性（常量名解释了它是什么），而且不会随 Spring 改名再被弃用一次。
     */
    private static final HttpStatusCode UNPROCESSABLE_CONTENT = HttpStatusCode.valueOf(422);

    /**
     * 与 {@code spring.jackson.property-naming-strategy: SNAKE_CASE} 保持一致。
     *
     * <p>用在 422 的 {@code loc} 上：报错位置必须写**客户端实际使用的字段名**。
     */
    private static final PropertyNamingStrategy PROPERTY_NAMING =
            PropertyNamingStrategies.SNAKE_CASE;

    // ------------------------------------------------------------------ 业务异常

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterSeconds() != null) {
            // Python: headers={"Retry-After": str(retry_after)}
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return new ResponseEntity<>(ErrorResponse.of(ex.detail()),
                headers, HttpStatusCode.valueOf(ex.status()));
    }

    // ------------------------------------------------------------------ 422：体校验失败

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException ex) {
        // 出错位置可能是请求体（@RequestBody DTO）也可能是查询参数（@ModelAttribute / record 绑定，
        // 例如分页的 PageQuery）。FastAPI 的 loc 首段会区分 body / query，
        // 全部写成 "body" 会让排查者找错地方。
        String location = ex.getParameter().hasParameterAnnotation(RequestBody.class)
                ? "body" : "query";
        List<ValidationErrorItem> items = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            items.add(toItem(location, error.getField(), error.getCode(), error.getDefaultMessage()));
        }
        if (items.isEmpty()) {
            items.add(ValidationErrorItem.valueError(location, null, "请求参数不合法"));
        }
        return ResponseEntity.status(UNPROCESSABLE_CONTENT)
                .body(new ValidationErrorResponse(items));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ValidationErrorResponse> handleParamValidation(
            ConstraintViolationException ex) {
        List<ValidationErrorItem> items = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath() == null ? null : violation.getPropertyPath().toString();
            // 取最后一个节点作为字段名（如 "list.page" → "page"），再转成对外的 JSON 名
            String field = path == null ? null : path.substring(path.lastIndexOf('.') + 1);
            items.add(ValidationErrorItem.valueError("query", jsonName(field), violation.getMessage()));
        }
        return ResponseEntity.status(UNPROCESSABLE_CONTENT)
                .body(new ValidationErrorResponse(items));
    }

    /** body 不是合法 JSON / 类型对不上 —— 实测 FastAPI 这里也是 422。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ValidationErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        log.debug("请求体解析失败: {}", ex.getMessage());
        return ResponseEntity.status(UNPROCESSABLE_CONTENT).body(new ValidationErrorResponse(
                List.of(ValidationErrorItem.jsonInvalid("JSON decode error"))));
    }

    /**
     * **必填的查询参数没传** → 422。
     *
     * <p>FastAPI 里 {@code def f(confirm: str)} （没有默认值）会把这个查询参数当成必填，
     * 缺失时返回 422 {@code {"type":"missing","loc":["query","confirm"],"msg":"Field required"}}。
     *
     * <p>⚠ 它与"传了但值不对"是**两条不同的路径**：
     * <ul>
     *   <li>没传 → 422（本条）</li>
     *   <li>传了但值非法 → 由业务代码给 400</li>
     * </ul>
     * 最初把两者都写成 400，被影子对比工具抓出来。所以这里单独处理，
     * 别把 {@code @RequestParam} 写成 {@code required = false} 再自己判空。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ValidationErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex) {
        return ResponseEntity.status(UNPROCESSABLE_CONTENT).body(new ValidationErrorResponse(
                List.of(ValidationErrorItem.missing("query", ex.getParameterName(), "Field required"))));
    }

    // ------------------------------------------------------------------ 405 / 500

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        // 实测 FastAPI 的 405 体是 {"detail":"Method Not Allowed"}
        return new ResponseEntity<>(ErrorResponse.of("Method Not Allowed"),
                HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * 兜底。
     *
     * <p><b>这里一定要打日志</b>：否则 500 会变成「前端只看到一句服务器内部错误，
     * 后端什么都没有」，排查时会非常痛苦。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期的异常", ex);
        return new ResponseEntity<>(ErrorResponse.of("服务器内部错误"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ------------------------------------------------------------------ 工具

    /** 把 Jakarta Validation 的约束映射成 FastAPI 的 type（缺字段 → missing，其余 → value_error）。 */
    private static ValidationErrorItem toItem(String location, String field, String code, String message) {
        return ValidationErrorItem.of(typeOf(code), fieldPath(location, field), message);
    }

    /**
     * Jakarta Validation 的约束码 → Pydantic 的 {@code type} 字符串。
     *
     * <p>Pydantic 对数值边界用的是 {@code less_than_equal} / {@code greater_than_equal}，
     * 而 Spring 只给 {@code Max} / {@code Min}。既然 {@code type} 是响应体的一部分
     * （前端/契约测试会看），就得按对端的取值输出。
     * 认不出来的约束统一落到 {@code value_error} —— 与 Pydantic 的兜底类型同名。
     */
    private static String typeOf(String code) {
        if (code == null) {
            return "value_error";
        }
        if (code.startsWith(NotNull.class.getSimpleName())
                || code.startsWith(NotBlank.class.getSimpleName())
                || code.startsWith(NotEmpty.class.getSimpleName())) {
            return "missing";
        }
        if (code.startsWith("Max")) {
            return "less_than_equal";
        }
        if (code.startsWith("Min")) {
            return "greater_than_equal";
        }
        return "value_error";
    }

    /**
     * Spring 的字段路径 → Pydantic 的 {@code loc} 数组。
     *
     * <p>Spring 给的是 {@code "items[0].weeklyHours"}（一串），
     * Pydantic 要的是 {@code ["body","items",0,"weekly_hours"]}（下标单独一段、字段名 snake_case）。
     * 不转换的话，嵌套结构的报错位置两边对不上 —— 实测就是这么发现的。
     */
    private static List<Object> fieldPath(String location, String field) {
        List<Object> loc = new ArrayList<>();
        loc.add(location);
        if (field == null || field.isEmpty()) {
            return loc;
        }
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '.') {
                flushName(loc, name);
            } else if (c == '[') {
                flushName(loc, name);
                int close = field.indexOf(']', i);
                if (close < 0) {
                    name.append(c);
                    continue;
                }
                String inside = field.substring(i + 1, close);
                try {
                    loc.add(Integer.parseInt(inside));
                } catch (NumberFormatException e) {
                    loc.add(inside);
                }
                i = close;
            } else {
                name.append(c);
            }
        }
        flushName(loc, name);
        return loc;
    }

    private static void flushName(List<Object> loc, StringBuilder name) {
        if (name.length() > 0) {
            loc.add(jsonName(name.toString()));
            name.setLength(0);
        }
    }

    /**
     * Java 属性名 → 对外 JSON 字段名。
     *
     * <p>⚠ 这个转换不能省：Jakarta Validation 的 {@code FieldError.getField()} 返回的是
     * **Java 属性名**（{@code adminDisplayName}），而客户端实际发的是
     * **JSON 字段名**（{@code admin_display_name}，由全局 SNAKE_CASE 策略决定）。
     * 直接把属性名放进 {@code loc}，前端拿着它去表单里找字段会找不到。
     *
     * <p>实测（影子对比工具抓到的）：Java 报 {@code ["body","adminDisplayName"]}，
     * Python 报 {@code ["body","admin_display_name"]} —— 契约不一致。
     *
     * <p>这里**刻意复用 Jackson 的命名策略**而不是手写一个 camel→snake 转换函数：
     * 序列化用的是什么策略，报错位置就用什么策略，两者不可能漂移。
     */
    private static String jsonName(String field) {
        if (field == null) {
            return null;
        }
        return PROPERTY_NAMING.nameForField(null, null, field);
    }
}
