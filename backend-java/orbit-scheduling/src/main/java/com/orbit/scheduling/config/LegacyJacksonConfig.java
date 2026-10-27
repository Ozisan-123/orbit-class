package com.orbit.scheduling.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis JSON type handler 与部分持久化服务仍使用 Jackson 2。
 * Spring Boot 4 的 Web 层默认使用 Jackson 3，因此需要显式提供这一个兼容 Bean。
 */
@Configuration
public class LegacyJacksonConfig {

    @Bean
    ObjectMapper legacyObjectMapper() {
        return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
