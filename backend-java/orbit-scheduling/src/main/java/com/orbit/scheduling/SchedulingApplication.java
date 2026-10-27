package com.orbit.scheduling;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * orbit-scheduling 启动类。
 *
 * <h2>scanBasePackages / MapperScan 的理由与 academic 完全相同</h2>
 * <ul>
 *   <li>{@code com.orbit.common} 不显式加进来的话，
 *       {@code ApiExceptionHandler}（统一错误体）**不会被注册** ——
 *       错误响应会退回 Spring 默认的 {@code ProblemDetail}，而且不报错</li>
 *   <li>{@code @MapperScan} 必须配 {@code annotationClass = Mapper.class}：
 *       本项目的约定是 Mapper 与实体同放一个领域包（不是 {@code *.mapper} 子包），
 *       所以只按包名扫会漏；而不加过滤又会把将来的服务接口误注册成 Mapper</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"com.orbit.scheduling", "com.orbit.common"})
@MapperScan(basePackages = "com.orbit.scheduling", annotationClass = Mapper.class)
public class SchedulingApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulingApplication.class, args);
    }
}
