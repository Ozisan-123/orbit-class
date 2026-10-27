package com.orbit.solver.worker;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * worker 的主循环：轮询领单 → 丢给线程池求解（P3）。
 *
 * <h2>为什么是"轮询"而不是长连接或消息推送</h2>
 * 因为**领单本身就能表达"没活干"**（scheduling 返回 204），
 * 而不需要额外的通知机制。轮询间隔 2 秒，代价是每 2 秒一次 HTTP ——
 * 对一个"求解要 60 秒"的场景完全可以忽略。
 *
 * <p>而用 MQ 反而更麻烦：任务状态必须与 {@code job_token} 栅栏在**同一事务域**
 * （铁律 1），MQ 的"消息被消费"与"任务行被标记"是两件事，需要分布式事务或幂等补偿。
 *
 * <h2>两处并发上限，各管一层</h2>
 * <ul>
 *   <li>{@code orbit.worker.concurrency} —— **本进程**最多同时算几个（线程池大小）</li>
 *   <li>scheduling 侧的容量槽位 —— **全局**最多几个（跨所有 worker 副本）。
 *       抢不到槽位时 claim 会返回 204，本循环自然就空转等下一轮</li>
 * </ul>
 * 只有本地上限时，起 4 个副本就变成 4 倍并发，可能把机器打满；
 * 所以全局上限留在 scheduling 那边，worker 这边只是"别把自己压垮"。
 */
@Component
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);

    private final SchedulingClient client;
    private final JobRunner runner;
    private final WorkerProperties properties;
    private final ExecutorService pool;
    private final AtomicInteger inFlight = new AtomicInteger();

    public WorkerLoop(SchedulingClient client, JobRunner runner, WorkerProperties properties) {
        this.client = client;
        this.runner = runner;
        this.properties = properties;
        int concurrency = Math.max(1, properties.getConcurrency());
        this.pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "orbit-solve");
            t.setDaemon(false);   // 求解线程**不能是守护线程**：它跑着的时候进程不该退出
            return t;
        });
        log.info("solver-worker 启动: id={} scheduling={} 本进程并发={} 求解时限={}s",
                properties.getId(), properties.getSchedulingBaseUrl(), concurrency,
                properties.getTimeLimitSeconds());
    }

    /**
     * 轮询：把能领的任务都领回来，直到"没活干"或"本地已满"。
     *
     * <p>⚠ 一次 poll 会**连着领多个**（while 循环），而不是每轮只领一个 ——
     * 否则并发度设成 4 却要 8 秒才能填满。
     */
    @Scheduled(fixedDelayString = "${orbit.worker.poll-interval-ms:2000}")
    public void poll() {
        int concurrency = Math.max(1, properties.getConcurrency());
        while (inFlight.get() < concurrency) {
            ClaimedJob job;
            try {
                job = client.claim();
            } catch (RuntimeException e) {
                // ⚠ 必须打**整个异常**而不是 getMessage()。
                //   这里最初只打了消息，于是日志里只有
                //   "Error while extracting response for type [ClaimedJob]"
                //   ——**根因（哪个字段反序列化失败）完全看不到**，
                //   而那种错误恰恰只存在于 cause 链里。
                log.warn("领单出错，本轮放弃", e);
                return;
            }
            if (job == null) {
                return;   // 没活干（或没有全局槽位），等下一轮
            }
            if (job.takeOver()) {
                // 接管是**值得记一笔**的事件：说明上一个 worker 没能收尾
                log.warn("接管任务: job={} attempt={}/{} 上一个持有者没能收尾",
                        job.jobId(), job.attempt(), job.maxAttempts());
            }
            inFlight.incrementAndGet();
            pool.submit(() -> {
                try {
                    runner.run(job);
                } catch (Throwable t) {
                    // 兜底：绝不能让一个任务把线程池里的线程吃掉
                    log.error("求解任务时未捕获的错误: job={}", job.jobId(), t);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /** 优雅停机：等在跑的求解收尾（最多等一个求解时限 + 10 秒）。 */
    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(properties.getTimeLimitSeconds() + 10L, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("停机超时，仍有 {} 个求解未完成 —— 它们的租约到期后会被接管", inFlight.get());
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
