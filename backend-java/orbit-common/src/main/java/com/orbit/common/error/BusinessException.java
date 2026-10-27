package com.orbit.common.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常 —— 对应 Python 侧的 {@code HTTPException(status_code=..., detail="人话")}。
 *
 * <p>Python 原版全文有 20 多处 {@code HTTPException}，文案都是**面向用户的中文**。
 * 这些文案本身就是契约的一部分（前端直接显示 {@code error.response.data.detail}），
 * 所以这里刻意要求传完整文案，而不是传错误码让上层去翻译。
 *
 * <p>实测到的原文（迁移时要逐字保留）：
 * <ul>
 *   <li>401 {@code 请先登录} / {@code 用户名或密码错误} / {@code 登录已失效，请重新登录} / {@code 账号不存在或已停用}</li>
 *   <li>403 {@code 仅管理员可以执行此操作} / {@code 仅平台管理员可以管理工作空间}</li>
 *   <li>404 {@code 年级不存在} / {@code 班级不存在} / {@code 老师不存在} / {@code 课表版本不存在}</li>
 *   <li>409 {@code 只有草稿可以修改} / {@code 本校已有排课任务正在运行，请稍后再试}</li>
 *   <li>429 {@code 登录失败次数过多，请在 N 秒后重试} / {@code 服务器排课任务已满，请稍后再试}</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private final int status;
    /** 仅 429 用：对应 Python 的 {@code headers={"Retry-After": ...}}。 */
    private final Integer retryAfterSeconds;

    public BusinessException(int status, String detail) {
        this(status, detail, null);
    }

    public BusinessException(int status, String detail, Integer retryAfterSeconds) {
        super(detail);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public HttpStatus httpStatus() {
        return HttpStatus.valueOf(status);
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 异常消息即响应体里的 {@code detail}。 */
    public String detail() {
        return getMessage();
    }

    // ------------------------------------------------------------------ 工厂

    public static BusinessException badRequest(String detail) {
        return new BusinessException(400, detail);
    }

    public static BusinessException unauthorized(String detail) {
        return new BusinessException(401, detail);
    }

    public static BusinessException forbidden(String detail) {
        return new BusinessException(403, detail);
    }

    public static BusinessException notFound(String detail) {
        return new BusinessException(404, detail);
    }

    public static BusinessException conflict(String detail) {
        return new BusinessException(409, detail);
    }

    public static BusinessException tooManyRequests(String detail, int retryAfterSeconds) {
        return new BusinessException(429, detail, retryAfterSeconds);
    }

    /**
     * 429，**不带** {@code Retry-After} 头。
     *
     * <p>对应 Python 里那些没给 headers 的 429，例如排课容量满时的
     * {@code 服务器排课任务已满，请稍后再试} —— 那时确实给不出"还要等多久"，
     * 因为取决于别的任务什么时候跑完。登录限流则相反（它知道窗口剩余时间），
     * 所以那一条用带 {@code Retry-After} 的重载。
     */
    public static BusinessException tooManyRequests(String detail) {
        return new BusinessException(429, detail);
    }
}
