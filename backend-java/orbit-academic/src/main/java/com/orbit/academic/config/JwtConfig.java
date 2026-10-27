package com.orbit.academic.config;

import com.orbit.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link JwtService} 装成 Bean。
 *
 * <p><b>为什么装配放在服务侧而不是 {@code orbit-common} 里</b>：
 * {@code orbit-common} 刻意保持为一个**薄共享模块**（只引 spring-web，
 * 不给 WebFlux 网关带进 Servlet 容器）。让它带 Spring 容器注解（{@code @Component}/{@code @Configuration}）
 * 会把「共享逻辑」和「容器装配」混在一起 —— 而且各服务对策略的需求可能不同
 * （比如将来 scheduling 只校验不签发）。
 *
 * <p>同样的做法在 solver-core 里也用过：{@code RuleRegistry} 是纯数据结构，
 * 「有哪些内置规则」的装配交给 {@code compile.BuiltinRules}。
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtService jwtService(
            @Value("${orbit.jwt.secret}") String secret,
            @Value("${orbit.jwt.ttl-seconds:28800}") long ttlSeconds) {
        return new JwtService(secret, ttlSeconds);
    }
}
