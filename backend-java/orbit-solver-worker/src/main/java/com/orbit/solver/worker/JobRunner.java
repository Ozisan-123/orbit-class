package com.orbit.solver.worker;

import com.orbit.solver.core.OrbitSolver;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.model.TimetableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 求解**一个**领到的任务，并把结果交回去（P3 的执行体）。
 *
 * <h2>执行顺序</h2>
 * <ol>
 *   <li>起心跳线程（每 {@code heartbeatSeconds} 秒），它做两件事：
 *       续租 + 读回 {@code cancel_requested}</li>
 *   <li>求解（{@code timeLimitSeconds}）</li>
 *   <li>把结果交回去 —— 但交之前先看心跳有没有说"你已经不是持有者了"</li>
 * </ol>
 *
 * <h2>三种收场</h2>
 * <ul>
 *   <li><b>成功</b> → 交回 entries；若 scheduling 说 {@code accepted=false}，
 *       说明**结果被丢弃**（被接管了）。这**不是错误**，记一条日志就行</li>
 *   <li><b>无解/超时</b> → 交回 error。⚠ {@code INFEASIBLE} 要标成**不可重试** ——
 *       证明无解的问题重试多少次都一样，标成可重试只会浪费两轮求解</li>
 *   <li><b>被取消</b> → 交回 {@code cancelled=true}</li>
 * </ul>
 *
 * <h2>⚠ 一处已知的局限：求解中途无法真正中断</h2>
 * OR-Tools 的 {@code solver.solve()} 是**阻塞调用**，没有提供中断接口。
 * 所以取消请求只能做到：
 * <ul>
 *   <li>**求解前**检查 → 根本没开始就直接交回取消</li>
 *   <li>**求解后**检查 → 算出结果但发现取消了，丢弃结果并交回取消</li>
 * </ul>
 * 也就是说一次 60 秒的求解**最坏要跑完**才会响应取消。
 * 要真正做到"中途停"得靠 `CpSolver.stopSearch()`（需要在求解线程外调用，
 * 且语义是"停止搜索并返回当前最好解"）—— 那是 P5 的可观测/压测阶段一起做的事。
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final SchedulingClient client;
    private final WorkerProperties properties;

    public JobRunner(SchedulingClient client, WorkerProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public void run(ClaimedJob job) {
        long startedAt = System.nanoTime();
        AtomicBoolean tokenLost = new AtomicBoolean(false);
        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        AtomicInteger progress = new AtomicInteger(0);

        Thread heartbeat = startHeartbeat(job, tokenLost, cancelRequested, progress);
        try {
            if (cancelRequested.get()) {
                reportCancelled(job);
                return;
            }

            SolveOutcome outcome = OrbitSolver.solve(job.input(), job.rules(),
                    new SolverSettings(properties.getTimeLimitSeconds(),
                            properties.getNumSearchWorkers(), false));
            progress.set(100);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            if (tokenLost.get()) {
                // 心跳已经知道我们不是持有者了 → 结果不会被接受，不必白跑一次网络
                log.warn("求解完成但已被接管，丢弃结果: job={} 耗时={}ms", job.jobId(), elapsedMs);
                return;
            }
            if (cancelRequested.get()) {
                reportCancelled(job);
                return;
            }

            List<TimetableEntry> entries = outcome.entries();
            if (entries == null || entries.isEmpty()) {
                // ⚠ 重试策略按求解状态分：证明无解 → 不可重试；超时未证明 → 可重试
                boolean retryable = outcome.timedOut();
                reportFailure(job, "排课无解或超时（" + outcome.status() + "）",
                        String.valueOf(outcome.status()), retryable);
                log.info("求解无解: job={} status={} 耗时={}ms", job.jobId(), outcome.status(), elapsedMs);
                return;
            }

            Map<?, ?> response = client.submitResult(job.jobId(), job.jobToken(),
                    String.valueOf(outcome.status()), outcome.modelFingerprint(),
                    outcome.objectiveValue(), entries);
            boolean accepted = response != null && Boolean.TRUE.equals(response.get("accepted"));
            log.info("求解完成: job={} status={} 明细={} 耗时={}ms {}", job.jobId(), outcome.status(),
                    entries.size(), elapsedMs, accepted ? "" : "（结果被丢弃：已被接管）");
        } catch (RuntimeException e) {
            log.error("求解出错: job={}", job.jobId(), e);
            // 异常一律可重试（进程/环境问题居多），但如果是"证明无解"那类我们已经
            // 在上面的分支里标成不可重试了
            reportFailure(job, "求解出错：" + e, null, true);
        } finally {
            heartbeat.interrupt();
        }
    }

    /** 心跳线程：续租 + 读回取消请求；一旦 scheduling 说 token 失效就停手。 */
    private Thread startHeartbeat(ClaimedJob job, AtomicBoolean tokenLost,
                                  AtomicBoolean cancelRequested, AtomicInteger progress) {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(Math.max(1, job.heartbeatSeconds()) * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                SchedulingClient.HeartbeatResult result =
                        client.heartbeat(job.jobId(), job.jobToken(), progress.get());
                if (!result.ok()) {
                    // 被接管了：继续算下去也不会被接受，但**求解调用无法中断**，
                    // 所以只能打标记，外层算完后丢弃结果
                    log.warn("心跳被拒（任务已被接管）: job={}", job.jobId());
                    tokenLost.set(true);
                    return;
                }
                if (result.cancelRequested()) {
                    cancelRequested.set(true);
                    return;
                }
            }
        }, "orbit-hb-" + job.jobId());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void reportCancelled(ClaimedJob job) {
        Map<?, ?> response = client.submitCancellation(job.jobId(), job.jobToken());
        log.info("任务已响应取消: job={} {}", job.jobId(), response);
    }

    private void reportFailure(ClaimedJob job, String error, String solverStatus, boolean retryable) {
        try {
            Map<?, ?> response = client.submitFailure(job.jobId(), job.jobToken(),
                    error, solverStatus, retryable);
            log.info("已交回失败: job={} retryable={} {}", job.jobId(), retryable, response);
        } catch (RuntimeException e) {
            // 交回失败也没别的办法：租约到期后任务会被接管重做
            log.error("交回失败结果时出错: job={}", job.jobId(), e);
        }
    }
}
