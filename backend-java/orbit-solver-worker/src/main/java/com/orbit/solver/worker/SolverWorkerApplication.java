package com.orbit.solver.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * orbit-solver-worker —— 独立求解进程（P3）。
 *
 * <h2>它是什么</h2>
 * 一个**没有数据库连接**的进程：从 scheduling 的 {@code /internal/jobs/*} 领任务、
 * 求解、把结果交回去。方案 §2.2 的服务清单写明它「拥有的表：**无**」。
 *
 * <h2>为什么值得单独一个进程（§2.3 说这是"拆分理由最强"的一条）</h2>
 * 求解是 CPU 密集的长任务（160 班实测吃满 CPU 12-60 秒、峰值 1.8 GB）。
 * 与 Web 请求放在一起时，一次排课就能把接口响应拖慢 —— 而且进程重启会
 * **凭空丢掉正在跑的任务**。拆开之后：
 * <ul>
 *   <li>Web 服务不受求解影响（可以用很小的堆）</li>
 *   <li>worker 崩了，租约到期后任务被接管，**不会有任务凭空消失**</li>
 *   <li>worker 可以按 CPU 核数独立扩容（它不持有任何状态）</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} 是必需的：轮询循环靠 {@code @Scheduled} 驱动。
 * {@code @EnableConfigurationProperties} 把 {@link WorkerProperties} 绑上 ——
 * 不写它的话 {@code orbit.worker.*} 一个字段都不会生效（而且不报错）。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
public class SolverWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolverWorkerApplication.class, args);
    }
}
