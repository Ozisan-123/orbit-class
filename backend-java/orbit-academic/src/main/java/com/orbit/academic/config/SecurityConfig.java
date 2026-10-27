package com.orbit.academic.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.academic.auth.JwtAuthenticationFilter;
import com.orbit.academic.auth.OrbitUserDetailsService;
import com.orbit.academic.auth.PythonPbkdf2PasswordEncoder;
import com.orbit.common.security.AuthFailure;
import com.orbit.common.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 认证与授权的装配 —— 走 Spring Security 的标准流程。
 *
 * <pre>
 *   登录：  AuthenticationManager(DaoAuthenticationProvider)
 *             → UserDetailsService 查库 → PasswordEncoder 比对 → 签发 JWT
 *   请求：  JwtAuthenticationFilter → SecurityContextHolder → 授权层判断
 *   失败：  AuthenticationEntryPoint → 401 {"detail":"<按失败原因>"}
 * </pre>
 *
 * <p>没有照搬 Python 的「手写 verify_password + 每个接口 Depends(get_current_user)」，
 * 因为在 Spring 里那等于把框架已经做好的事重做一遍。
 *
 * <h2>但两件事必须与 Python 保持一致（它们是契约，不是实现）</h2>
 * <ol>
 *   <li><b>口令格式</b>：库里已有 Python 生成的 {@code pbkdf2_sha256$310000$...} 哈希，
 *       所以 {@link PasswordEncoder} 用 {@link PythonPbkdf2PasswordEncoder}。
 *       换成 BCrypt 的话，迁移后所有老用户登录不了。</li>
 *   <li><b>未认证返回 401（不是 403）</b>，且文案**按失败原因分三种**
 *       （见 {@link AuthFailure}）。前端 axios 拦截器只认 401 触发登出；
 *       Spring Security 默认给 403，用户会卡在「没登录却退不出去」。</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 授权失败（已认证但权限不够）的安全网。
     *
     * <p>正常路径上**不应该触发**：本项目的角色判断是显式写在代码里的
     * （{@code principal.isAdmin()} / {@code isPlatformAdmin()}），
     * 这样才能给出 Python 那句具体的 {@code 仅管理员可以执行此操作}。
     * 这里只是防止有人用 {@code hasRole(...)} 写了规则却忘了配 handler
     * 而拿到 Spring 默认的 HTML 错误页。
     */
    private static final String FORBIDDEN_DETAIL = "没有执行此操作的权限";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new PythonPbkdf2PasswordEncoder();
    }

    /**
     * 登录用的 {@link AuthenticationManager}。
     *
     * <p>{@code DaoAuthenticationProvider} 是标准实现：它调
     * {@link OrbitUserDetailsService} 取用户，再用 {@link PasswordEncoder} 比对口令。
     * 账号停用时 {@code UserDetails.isEnabled()} 返回 false，它会抛
     * {@code DisabledException}（也是 {@code AuthenticationException} 的一种）。
     */
    @Bean
    public AuthenticationManager authenticationManager(OrbitUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // 隐藏「用户名不存在」与「口令错误」的区别（默认就是隐藏的，这里显式写出意图）
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain orbitSecurityFilterChain(HttpSecurity http,
                                                        JwtService jwtService,
                                                        OrbitUserDetailsService userDetailsService)
            throws Exception {
        // 直接 new，不注册成 Bean：Filter Bean 会被 Spring Boot 自动注册到 Servlet 容器，
        // 导致它在安全链之外也跑一遍（见 JwtAuthenticationFilter 的类注释）
        JwtAuthenticationFilter jwtFilter =
                new JwtAuthenticationFilter(jwtService, userDetailsService);

        http
                // 纯 JWT API：无 session、无表单、无 CSRF token
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查/探针必须无认证可达，否则负载均衡与监控探不到
                        .requestMatchers("/api/health",
                                "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/error").permitAll()
                        // 登录本身当然要能匿名访问
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // 其余一律需要认证（包括 /api/auth/me 与 /change-password）
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(SecurityConfig::writeUnauthorized)
                        .accessDeniedHandler(SecurityConfig::writeForbidden))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeUnauthorized(HttpServletRequest request,
                                          HttpServletResponse response,
                                          AuthenticationException exception) throws IOException {
        // 过滤器把失败原因放在请求属性里；没有就按"没带 token"处理。
        // 三种文案（请先登录 / 登录已失效，请重新登录 / 账号不存在或停用）都来自 Python。
        Object reason = request.getAttribute(AuthFailure.ATTRIBUTE);
        String detail = reason instanceof String text ? text : AuthFailure.NO_TOKEN;
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, detail);
    }

    private static void writeForbidden(HttpServletRequest request,
                                       HttpServletResponse response,
                                       org.springframework.security.access.AccessDeniedException exception)
            throws IOException {
        writeJson(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_DETAIL);
    }

    private static void writeJson(HttpServletResponse response, int status, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // 用 Jackson 拼（而不是字符串相加），文案里有引号也不会破坏 JSON
        response.getWriter().write(JSON.writeValueAsString(Map.of("detail", detail)));
    }
}
