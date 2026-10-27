package com.orbit.scheduling.job;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * {@code schedule_jobs} 的 Mapper —— **整个队列机制的落点**。
 *
 * <h2>三个必须一起看的机制</h2>
 * <ol>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED}</b>（{@link #lockNextClaimable}）：
 *       多个 worker 同时领单时，各自锁住不同的行、跳过已被锁的，于是**不需要任何应用层锁**。
 *       这正是用数据库当队列的价值所在。</li>
 *   <li><b>{@code job_token} 栅栏</b>：每次领单都**换一个新 token**，
 *       所有回写语句都带 {@code AND job_token = ?}。
 *       于是"被接管后，原来那个 worker 又醒过来提交结果"这种情况**写不进去** ——
 *       它的 token 已经过期了。这是幂等的关键，不能省。</li>
 *   <li><b>{@code lease_until}</b>：worker 每 15 秒续一次。没续上就说明它可能死了，
 *       {@link #lockNextClaimable} 会把这种 {@code running} 行也当作可领的（这就是"接管"）。</li>
 * </ol>
 *
 * <p>⚠ 写操作一律用 {@link Update}（用 {@code @Select} 标 UPDATE 拿不到 rowcount，
 * 见踩坑总结 B7）——而本类**全靠 rowcount 判断成败**。
 */
@Mapper
public interface ScheduleJobMapper extends BaseMapper<ScheduleJobEntity> {

    /**
     * 领下一个可做的任务，**行锁 + 跳过已锁**。
     *
     * <p>"可做"包含两种：从未跑过的 {@code pending}，以及**租约已过期的 {@code running}**
     * （原 worker 可能已经崩了 —— 这就是"接管"）。
     *
     * <p>⚠ 必须在事务里调用，否则 {@code FOR UPDATE} 的锁立刻就释放了，
     * 两个 worker 会领到同一单。
     *
     * <p>⚠ {@code ORDER BY id} 是 FIFO（先入先出）—— 它同时决定了"排队位置"的口径，
     * 所以 {@code GET /api/schedule/jobs} 里算排队名次用的也是 id 顺序。
     */
    @Select("""
            SELECT * FROM schedule_jobs
             WHERE status = 'pending'
                OR (status = 'running' AND lease_until < #{now})
             ORDER BY id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """)
    ScheduleJobEntity lockNextClaimable(@Param("now") LocalDateTime now);

    /**
     * 结果落库前锁住任务行并确认**它仍然是自己的**。
     *
     * <p>返回 null 表示：已被接管 / 已终结 → 调用方应**丢弃结果**（不是报错）。
     *
     * <p>⚠ 这一步与 P1 的 {@code SchedulingStateMapper.lockForRunning} 是同一个套路，
     * 只是判据从"组织级状态行"换成了"任务行 + token" —— 粒度更细，而且
     * 多个任务可以并存（不同组织）。
     */
    @Select("""
            SELECT * FROM schedule_jobs
             WHERE id = #{id} AND job_token = #{jobToken} AND status = 'running'
             FOR UPDATE
            """)
    ScheduleJobEntity lockForRunning(@Param("id") long id, @Param("jobToken") String jobToken);

    /**
     * 占住这一单：置 running、换新 token、续租、attempt +1。
     *
     * <p>⚠ 换 token 是**故意的**：接管发生时，旧 worker 手里的 token 立刻失效，
     * 它之后提交的任何结果都会被拒绝。
     *
     * <p>条件里带 {@code status IN ('pending','running')} 而不是只按 id 更新：
     * 防止把一个已经被取消/完成的任务重新拉起来。
     */
    @Update("""
            UPDATE schedule_jobs
               SET status = 'running',
                   job_token = #{jobToken},
                   worker_id = #{workerId},
                   attempt = attempt + 1,
                   lease_until = #{leaseUntil},
                   heartbeat_at = #{now},
                   started_at = COALESCE(started_at, #{now}),
                   finished_at = NULL,
                   last_error = NULL
             WHERE id = #{id}
               AND status IN ('pending', 'running')
            """)
    int markRunning(@Param("id") long id,
                    @Param("jobToken") String jobToken,
                    @Param("workerId") String workerId,
                    @Param("leaseUntil") LocalDateTime leaseUntil,
                    @Param("now") LocalDateTime now);

    /** 心跳续租（可带进度）。token 不匹配 → rowcount 0 → 上层停止心跳。 */
    @Update("""
            UPDATE schedule_jobs
               SET lease_until = #{leaseUntil},
                   heartbeat_at = #{now},
                   progress = #{progress}
             WHERE id = #{id} AND job_token = #{jobToken} AND status = 'running'
            """)
    int heartbeat(@Param("id") long id,
                  @Param("jobToken") String jobToken,
                  @Param("leaseUntil") LocalDateTime leaseUntil,
                  @Param("now") LocalDateTime now,
                  @Param("progress") int progress);

    /** 完成：写结果摘要并置 done。token 不匹配 → 结果被丢弃（不报错，那是正确的）。 */
    @Update("""
            UPDATE schedule_jobs
               SET status = 'done',
                   progress = 100,
                   solver_status = #{solverStatus},
                   model_fingerprint = #{modelFingerprint},
                   objective_value = #{objectiveValue},
                   version_id = #{versionId},
                   entry_count = #{entryCount},
                   finished_at = #{now},
                   lease_until = NULL
             WHERE id = #{id} AND job_token = #{jobToken} AND status = 'running'
            """)
    int complete(@Param("id") long id,
                 @Param("jobToken") String jobToken,
                 @Param("solverStatus") String solverStatus,
                 @Param("modelFingerprint") String modelFingerprint,
                 @Param("objectiveValue") Long objectiveValue,
                 @Param("versionId") Long versionId,
                 @Param("entryCount") Integer entryCount,
                 @Param("now") LocalDateTime now);

    /**
     * 失败。
     *
     * <p>分两种收场，由 {@code retryable} 决定：
     * <ul>
     *   <li>可重试且还有次数 → 回到 {@code pending}（**并且清空 lease_until**），
     *       别的 worker 立刻能领走；token 在下次领单时会被换掉</li>
     *   <li>否则 → {@code failed}，等人工 retry</li>
     * </ul>
     */
    @Update("""
            <script>
            UPDATE schedule_jobs
               SET status = CASE WHEN #{retryable} = TRUE AND attempt &lt; max_attempts
                                 THEN 'pending' ELSE 'failed' END,
                   last_error = #{error},
                   solver_status = #{solverStatus},
                   finished_at = CASE WHEN #{retryable} = TRUE AND attempt &lt; max_attempts
                                      THEN NULL ELSE #{now} END,
                   lease_until = NULL
             WHERE id = #{id} AND job_token = #{jobToken} AND status = 'running'
            </script>
            """)
    int fail(@Param("id") long id,
             @Param("jobToken") String jobToken,
             @Param("error") String error,
             @Param("solverStatus") String solverStatus,
             @Param("retryable") boolean retryable,
             @Param("now") LocalDateTime now);

    /** 请求取消（幂等；运行中的任务由 worker 自己看到这个标记后收手）。 */
    @Update("""
            UPDATE schedule_jobs
               SET cancel_requested = TRUE
             WHERE id = #{id} AND organization_id = #{organizationId}
               AND status IN ('pending', 'running')
            """)
    int requestCancel(@Param("id") long id, @Param("organizationId") long organizationId);

    /** 立即取消一个**还没被领走**的任务（pending 才允许直接终结）。 */
    @Update("""
            UPDATE schedule_jobs
               SET status = 'cancelled', finished_at = #{now}, lease_until = NULL
             WHERE id = #{id} AND organization_id = #{organizationId} AND status = 'pending'
            """)
    int cancelPending(@Param("id") long id,
                      @Param("organizationId") long organizationId,
                      @Param("now") LocalDateTime now);

    /** worker 领到任务后察觉取消请求 → 主动交还（回到 pending 由上层置 cancelled）。 */
    @Update("""
            UPDATE schedule_jobs
               SET status = 'cancelled', finished_at = #{now}, lease_until = NULL
             WHERE id = #{id} AND job_token = #{jobToken} AND status = 'running'
            """)
    int markCancelled(@Param("id") long id,
                      @Param("jobToken") String jobToken,
                      @Param("now") LocalDateTime now);

    /** 人工重试：只允许失败的（或已取消的）任务，并且重置 attempt。 */
    @Update("""
            UPDATE schedule_jobs
               SET status = 'pending',
                   attempt = 0,
                   progress = 0,
                   cancel_requested = FALSE,
                   last_error = NULL,
                   worker_id = NULL,
                   lease_until = NULL,
                   started_at = NULL,
                   finished_at = NULL
             WHERE id = #{id} AND organization_id = #{organizationId}
               AND status IN ('failed', 'cancelled')
            """)
    int requeue(@Param("id") long id, @Param("organizationId") long organizationId);
}
