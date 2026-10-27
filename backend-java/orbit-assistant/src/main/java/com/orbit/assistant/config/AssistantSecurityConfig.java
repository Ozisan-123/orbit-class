package com.orbit.assistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.assistant.security.AssistantJwtFilter;
import com.orbit.common.security.AuthFailure;
import com.orbit.common.security.JwtService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

@Configuration
public class AssistantSecurityConfig {

    @Bean
    JwtService jwtService(@Value("${orbit.jwt.secret}") String secret,
                          @Value("${orbit.jwt.ttl-seconds:28800}") long ttl) {
        return new JwtService(secret, ttl);
    }

    @Bean
    SecurityFilterChain assistantSecurityFilterChain(HttpSecurity http, JwtService jwtService,
                                                     JdbcTemplate jdbc, ObjectMapper json) throws Exception {
        AssistantJwtFilter filter = new AssistantJwtFilter(jwtService, jdbc);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // SSE 完成/报错会触发同一请求的异步二次派发；首次 REQUEST 已完成 JWT 鉴权。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(h -> h.authenticationEntryPoint((request, response, error) -> {
                    Object reason = request.getAttribute(AuthFailure.ATTRIBUTE);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(json.writeValueAsString(Map.of("detail",
                            reason instanceof String text ? text : AuthFailure.NO_TOKEN)));
                }))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
