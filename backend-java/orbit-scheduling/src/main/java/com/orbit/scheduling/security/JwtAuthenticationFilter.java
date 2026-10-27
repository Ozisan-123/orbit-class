package com.orbit.scheduling.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.security.AuthFailure;
import com.orbit.common.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 从 {@code Authorization: Bearer <token>} 恢复安全上下文（scheduling 版）。
 *
 * <p>与 academic 那个的差别：这里**只按 id 装载用户**（scheduling 没有登录接口），
 * 所以不需要 {@code UserDetailsService}/{@code PasswordEncoder}，
 * 直接用 {@link AuthUserMapper} 查一次库，构造朴素的 {@link SchedulePrincipal}。
 *
 * <p>失败原因记在请求属性上（{@link AuthFailure}），由入口点决定最终文案 ——
 * 见那个类的说明。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AuthUserMapper authUserMapper;

    public JwtAuthenticationFilter(JwtService jwtService, AuthUserMapper authUserMapper) {
        this.jwtService = jwtService;
        this.authUserMapper = authUserMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = bearerToken(request);
            if (token == null) {
                request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.NO_TOKEN);
            } else {
                Optional<Long> userId = jwtService.userIdOf(token);
                if (userId.isEmpty()) {
                    request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.INVALID_TOKEN);
                } else {
                    authenticate(request, userId.get());
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, long userId) {
        AuthUser user = authUserMapper.selectOne(Wrappers.<AuthUser>lambdaQuery()
                .eq(AuthUser::getId, userId));
        if (user == null || !user.active()) {
            // token 合法但账号不可用 —— 与 Python 的 filter(is_active.is_(True)) 一致
            request.setAttribute(AuthFailure.ATTRIBUTE, AuthFailure.UNKNOWN_USER);
            SecurityContextHolder.clearContext();
            return;
        }
        SchedulePrincipal principal = new SchedulePrincipal(user.getId(), user.getOrganizationId(),
                user.getUsername(), user.getDisplayName(), user.getRole());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + String.valueOf(user.getRole()).toUpperCase())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
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
