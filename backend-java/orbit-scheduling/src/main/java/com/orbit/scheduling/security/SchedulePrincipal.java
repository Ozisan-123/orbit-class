package com.orbit.scheduling.security;

/**
 * 当前用户 —— scheduling 侧的安全主体。
 *
 * <p>与 academic 的 {@code OrbitUserPrincipal} 是**两份相似的类**，这是刻意的：
 * <ul>
 *   <li>scheduling **不依赖** academic（Maven 层面），因为拆进程后它们要独立部署</li>
 *   <li>scheduling 也不需要 {@code UserDetails}/{@code PasswordEncoder}
 *       —— 它只**校验** token，从不校验口令，所以没有登录流程</li>
 * </ul>
 * 所以这里只是一个朴素的 record，而不是 Spring Security 的 {@code UserDetails}。
 * {@code UsernamePasswordAuthenticationToken} 的 principal 可以是任意对象 ✓
 *
 * <p>⚠ P3 拆分时应把鉴权上移到网关（网关校验 token 后把
 * {@code X-User-Id} / {@code X-Organization-Id} / {@code X-User-Role} 注入请求头），
 * 那时这个类会退化为"读请求头"。已记入方案 §15。
 */
public record SchedulePrincipal(Long userId, Long organizationId, String username,
                                String displayName, String role) {

    /** 对应 Python 的 {@code require_admin} 判定（{@code admin} 与 {@code platform_admin}）。 */
    public boolean isAdmin() {
        return "admin".equals(role) || "platform_admin".equals(role);
    }
}
