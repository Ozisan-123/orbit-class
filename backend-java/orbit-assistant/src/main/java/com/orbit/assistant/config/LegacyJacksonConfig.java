package com.orbit.assistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 助手的审计 JSON 与 OpenAI 兼容协议使用 Jackson 2，显式注册避免 Boot 4 启动时缺 Bean。 */
@Configuration
public class LegacyJacksonConfig {

    @Bean
    ObjectMapper legacyObjectMapper() {
        return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
