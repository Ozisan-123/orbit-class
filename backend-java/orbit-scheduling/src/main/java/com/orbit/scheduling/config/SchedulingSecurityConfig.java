package com.orbit.scheduling.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.common.security.AuthFailure;
import com.orbit.common.security.JwtService;
import com.orbit.scheduling.security.AuthUserMapper;
import com.orbit.scheduling.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * scheduling 的安全配置 —— **只校验 token，没有登录流程**。
 *
 * <p>与 academic 的差别：
 * <ul>
 *   <li>没有 {@code PasswordEncoder} / {@code AuthenticationManager}
 *       / {@code /api/auth/login}（登录是 academic 的事）</li>
 *   <li>token 用的是**同一个密钥**（{@code APP_SECRET_KEY}）—— 一个服务签发，两个服务都认</li>
 * </ul>
 *
 * <p>401 的文案仍然逐字对齐 Python，而且**按失败原因分三种**
 * （见 {@link AuthFailure}）：过滤器把原因写在请求属性上，这里读出来选文案。
 */
@Configuration
@EnableWebSecurity
public class SchedulingSecurityConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 已认证但权限不够时的安全网（真实检查写在代码里，见各 Controller）。 */
    private static final String FORBIDDEN_DETAIL = "没有执行此操作的权限";

    @Bean
    public SecurityFilterChain schedulingSecurityFilterChain(HttpSecurity http,
                                                             JwtService jwtService,
                                                             AuthUserMapper authUserMapper)
            throws Exception {
        // 直接 new，不注册成 Bean：Filter Bean 会被 Spring Boot 自动注册到 Servlet 容器，
        // 导致它在安全链之外也跑一遍
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, authUserMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health",
                                "/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/error").permitAll()
                        // P3：worker 的内部接口。它们**不能**要求用户 JWT（worker 没有用户身份），
                        // 所以在这里放行、由 InternalJobController 自己校验共享密钥
                        // X-Worker-Secret。生产环境还应该在网络层限制（只有 worker 能访问），
                        // 那是 P3 网关的职责。
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(SchedulingSecurityConfig::writeUnauthorized)
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_DETAIL)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeUnauthorized(HttpServletRequest request,
                                          HttpServletResponse response,
                                          AuthenticationException exception) throws IOException {
        // 过滤器把失败原因放在请求属性里；没有就按"没带 token"处理
        Object reason = request.getAttribute(AuthFailure.ATTRIBUTE);
        String detail = reason instanceof String text ? text : AuthFailure.NO_TOKEN;
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, detail);
    }

    private static void writeJson(HttpServletResponse response, int status, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // 用 Jackson 拼（而不是字符串相加），文案里有引号也不会破坏 JSON
        response.getWriter().write(JSON.writeValueAsString(java.util.Map.of("detail", detail)));
    }
}
