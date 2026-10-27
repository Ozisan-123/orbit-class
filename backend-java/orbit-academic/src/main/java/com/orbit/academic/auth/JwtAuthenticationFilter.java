package com.orbit.academic.auth;

import com.orbit.common.security.AuthFailure;
import com.orbit.common.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 从 {@code Authorization: Bearer <token>} 恢复认证上下文。
 *
 * <p>这是 Spring Security 里的标准做法：过滤器只负责「把 token 变成
 * {@code SecurityContext} 里的认证信息」，**不负责拒绝请求** ——
 * 放行与否交给授权层（{@code authorizeHttpRequests}）决定，
 * 未认证时由 {@code AuthenticationEntryPoint} 统一写 401。
 *
 * <p>为什么要这样分工：有些接口必须无认证可达（健康检查、登录）。
 * 如果过滤器自己看到没 token 就写 401，那些接口全都会被挡掉。
 *
 * <p><b>但它要把"为什么失败"记下来</b>：Python 的 401 文案分三种
 * （没带 token / token 失效 / 账号不存在或停用），见 {@link AuthFailure}。
 * 过滤器把原因写成请求属性，入口点据此选文案 —— 既保持职责分离，又能逐字对齐。
 *
 * <p><b>刻意不注册成 {@code @Bean}</b>：Spring Boot 会把容器里所有的
 * {@code Filter} Bean 自动注册到 Servlet 容器上，于是它会**同时**
 * 作为普通 Filter 和作为安全链的一环被执行。虽然 {@code OncePerRequestFilter}
 * 能防止同一请求内重复执行，但它在安全链**之外**也会跑一遍，
 * 时序会变得难以推理。所以这里由 {@code SecurityConfig} 直接 new，
 * 只挂在安全链上。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final OrbitUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   OrbitUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 已经有认证就不再覆盖（例如测试里手工设置的上下文）
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = bearerToken(request);
            if (token == null) {
                request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.NO_TOKEN);
            } else {
                Optional<Long> userId = jwtService.userIdOf(token);
                if (userId.isEmpty()) {
                    // 签名不对 / 已过期 / 格式非法 —— Python 文案：登录已失效，请重新登录
                    request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.INVALID_TOKEN);
                } else {
                    authenticate(request, userId.get());
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, long userId) {
        try {
            // 每个请求都查一次库：这样「用户被停用/删除后 token 立即失效」，
            // 与 Python 的 get_current_user 行为一致
            OrbitUserPrincipal principal = userDetailsService.loadById(userId);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (UsernameNotFoundException e) {
            // token 签名有效，但账号已不存在或已停用 → 保持未认证，
            // 并记下原因（Python 文案：账号不存在或停用）
            request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.UNKNOWN_USER);
            SecurityContextHolder.clearContext();
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
