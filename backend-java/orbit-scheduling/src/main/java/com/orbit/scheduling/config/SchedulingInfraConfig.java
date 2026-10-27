package com.orbit.scheduling.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.orbit.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * scheduling 的基础设施装配：JWT 校验 + MyBatis-Plus 分页。
 *
 * <p>{@link JwtService} 在这里**只用于校验**（scheduling 不签发 token），
 * 但它的构造器要求一个 TTL，所以照样从配置读 —— 保持两个服务的装配方式一致，
 * 将来若 scheduling 也需要签发（例如 worker 回调）就不用改结构。
 *
 * <p>分页拦截器与 academic 同理：不注册它 {@code selectPage} 就**静默不分页**
 * （见 [踩坑总结] 的 H6）。版本列表接口需要分页，所以必须有。
 */
@Configuration
public class SchedulingInfraConfig {

    @Bean
    public JwtService jwtService(
            @Value("${orbit.jwt.secret}") String secret,
            @Value("${orbit.jwt.ttl-seconds:28800}") long ttlSeconds) {
        return new JwtService(secret, ttlSeconds);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
