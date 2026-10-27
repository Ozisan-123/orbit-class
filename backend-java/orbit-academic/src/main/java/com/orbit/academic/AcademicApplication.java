package com.orbit.academic;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * orbit-academic 启动类。
 *
 * <h2>为什么 scanBasePackages 要显式写 com.orbit.common</h2>
 * {@code @SpringBootApplication} 默认**只扫描自己所在包及其子包**（{@code com.orbit.academic}）。
 * 而 {@code orbit-common} 里的 {@code ApiExceptionHandler}（全局错误体）在
 * {@code com.orbit.common} 下 —— 不显式加进来，它**不会被注册**，于是：
 * <ul>
 *   <li>错误响应会退回 Spring 默认的 {@code ProblemDetail}，契约对不上</li>
 *   <li>而且**不报错**：构建通过、启动成功，只有契约测试会红 —— 典型的静默失效</li>
 * </ul>
 *
 * <h2>Mapper 扫描：@MapperScan + annotationClass 过滤</h2>
 * 本项目的约定是「实体 / Mapper / Controller / Service **同放在一个领域包**里」
 * （见 README 包归属表：{@code scheduling.rule} 就同时含实体、Mapper、Controller、Service），
 * 所以 Mapper 在 {@code com.orbit.academic.user} 而**不是** {@code ...user.mapper}。
 *
 * <p>踩过两次，这里记清楚：
 * <ol>
 *   <li>{@code @MapperScan("com.orbit.academic.**.mapper")} —— 期望 {@code mapper} 子包，
 *       而 Mapper 在领域包根下，**一个都没注册**，启动失败
 *       （{@code No qualifying bean of type 'UserMapper'}）</li>
 *   <li>去掉 {@code @MapperScan}、只留接口上的 {@code @Mapper} —— **也不行**：
 *       MyBatis-Plus 3.5.9 的自动扫描没有把 {@code com.orbit.academic.user} 下的接口捞起来，
 *       同样启动失败</li>
 * </ol>
 *
 * <p>正解是 {@code @MapperScan} 指定基础包 **并用 {@code annotationClass} 过滤**：
 * <ul>
 *   <li>扫整个 {@code com.orbit.academic} → 领域包下的 Mapper 都能找到</li>
 *   <li>只注册带 {@code @Mapper} 的接口 → 将来有人在领域包里加一个服务接口
 *       （比如 {@code SnapshotProvider}）**不会被误注册成 Mapper**</li>
 * </ul>
 * 加新 Mapper 时只要记得标 {@code @Mapper} 就行，不用回来改这个启动类。
 */
@SpringBootApplication(scanBasePackages = {"com.orbit.academic", "com.orbit.common"})
@MapperScan(basePackages = "com.orbit.academic", annotationClass = Mapper.class)
public class AcademicApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcademicApplication.class, args);
    }
}
