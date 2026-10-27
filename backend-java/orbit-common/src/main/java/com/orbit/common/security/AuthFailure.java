package com.orbit.common.security;

/**
 * 鉴权失败的**原因**与对应文案 —— 两个服务共用。
 *
 * <h2>为什么需要一个共享的"请求属性名"</h2>
 * 架构上，过滤器只负责「把 token 变成安全上下文」，**不负责写响应**；
 * 真正决定放行与否的是授权层，未认证时由 {@code AuthenticationEntryPoint} 统一写 401。
 *
 * <p>但 Python 的 401 文案**不止一种**，取决于失败原因：
 * <table>
 *   <tr><th>情况</th><th>Python 文案</th></tr>
 *   <tr><td>没带 token</td><td>{@code 请先登录}</td></tr>
 *   <tr><td>token 签名不对 / 已过期 / 格式非法</td><td>{@code 登录已失效，请重新登录}</td></tr>
 *   <tr><td>token 有效，但用户已删除或停用</td><td>{@code 账号不存在或停用}</td></tr>
 * </table>
 * 所以过滤器要把"为什么失败"记在**请求属性**上，入口点再据此选文案 ——
 * 这样既保持了职责分离，又能逐字对齐文案。
 *
 * <p>⚠ 最初漏了这层，结果是"token 过期"也回 {@code 请先登录}。
 * 过期是 8 小时就会遇到的常见情况，前端直接把这句话显示给用户，
 * 所以它值得做对（靠影子对比工具比对多次请求才发现）。
 */
public final class AuthFailure {

    /** 请求属性名（过滤器写、入口点读）。 */
    public static final String ATTRIBUTE = "com.orbit.auth.failure";

    /** 没带 token。 */
    public static final String NO_TOKEN = "请先登录";
    /** token 本身不合法（签名/过期/格式）。 */
    public static final String INVALID_TOKEN = "登录已失效，请重新登录";
    /** token 合法但用户不存在或已停用。 */
    public static final String UNKNOWN_USER = "账号不存在或停用";

    private AuthFailure() {
    }
}
