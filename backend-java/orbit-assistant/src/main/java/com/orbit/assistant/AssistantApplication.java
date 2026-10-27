package com.orbit.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * orbit-assistant 启动类。
 *
 * <p>助手故障不会影响排课主链路；所有写操作只生成草案，必须由用户再次确认。
 */
@SpringBootApplication(
        scanBasePackages = {"com.orbit.assistant", "com.orbit.common"},
        exclude = UserDetailsServiceAutoConfiguration.class)
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
