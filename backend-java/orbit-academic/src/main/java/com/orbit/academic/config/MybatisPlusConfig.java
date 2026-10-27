package com.orbit.academic.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配。
 *
 * <h2>为什么必须有这个分页拦截器</h2>
 * MyBatis-Plus 的 {@code selectPage(Page, Wrapper)} **不会自己分页** ——
 * 它把分页交给 {@link PaginationInnerInterceptor} 去改写 SQL（加 {@code LIMIT}）。
 * 不注册这个拦截器时：
 * <ul>
 *   <li>**不报错**，构建通过、启动成功</li>
 *   <li>{@code selectPage} 返回**全部**记录（真的没有 LIMIT），
 *       而 {@code page.getTotal()} 也拿不到正确的总数</li>
 *   <li>表现是「分页参数好像没生效」—— 而接口看起来是好的</li>
 * </ul>
 * 这与踩坑总结里的 B2（Flyway 静默失效）、B5（MyBatis-Plus starter 静默失效）
 * 是同一类问题：**引入了依赖 ≠ 功能真的在运行**。
 *
 * <p>所以 P1 的验证里有一条是**实测** {@code ?size=1} 只返回 1 条、
 * 而 {@code total} 仍然是总数 —— 光看代码看不出这个拦截器有没有生效。
 *
 * <p>⚠ 在 MyBatis-Plus 3.5.9+ 里，{@code PaginationInnerInterceptor} 依赖 jsqlparser，
 * 被拆到了独立构件中（不再随 extension 一起来）。如果这个类编译不过或运行时
 * 抛 {@code NoClassDefFoundError}，就要补 {@code mybatis-plus-jsqlparser} 依赖。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 明确指定 MySQL：DbType 也用来做分页方言与 count 语句生成
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
