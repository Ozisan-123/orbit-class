package com.orbit.scheduling.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.rule.RuleSetService;
import com.orbit.solver.core.rule.ConstraintRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 任务队列 —— 入队、领单、心跳、完成/失败、取消、重试（P3 的核心）。
 *
 * <h2>为什么是"数据库当队列"而不是 MQ</h2>
 * 方案 §6.2 的原话：**任务状态必须与 {@code job_token} 栅栏在同一事务域**（铁律 1）。
 * 用 MQ 的话，"消息被消费"与"任务行被标记"是两件事，需要分布式事务或幂等补偿。
 * 用 `FOR UPDATE SKIP LOCKED`，领单本身就是一次数据库写 —— 天然原子。
 *
 * <h2>三个不变量</h2>
 * <ol>
 *   <li><b>一单只被一个人做</b>：{@code FOR UPDATE SKIP LOCKED} 保证并发领单不重复</li>
 *   <li><b>做完的人才能写</b>：所有回写都带 {@code AND job_token = ?}，接管会换 token</li>
 *   <li><b>租约断了就当人死了</b>：{@code running} 且 {@code lease_until < now} 可被重新领走</li>
 * </ol>
 */
@Service
public class JobQueueService {

    private static final Logger log = LoggerFactory.getLogger(JobQueueService.class);

    private final ScheduleJobMapper jobMapper;
    private final ScheduleJobEventMapper eventMapper;
    private final SnapshotService snapshotService;
    private final RuleSetService ruleSetService;
    private final com.orbit.scheduling.capacity.CapacityService capacityService;
    private final com.orbit.scheduling.capacity.ScheduleWorkerSlotMapper slotMapper;
    private final com.orbit.scheduling.schedule.ScheduleStateService stateService;
    private final int leaseSeconds;
    private final int heartbeatSeconds;
    private final SecureRandom random = new SecureRandom();

    public JobQueueService(ScheduleJobMapper jobMapper,
                           ScheduleJobEventMapper eventMapper,
                           SnapshotService snapshotService,
                           RuleSetService ruleSetService,
                           com.orbit.scheduling.capacity.CapacityService capacityService,
                           com.orbit.scheduling.capacity.ScheduleWorkerSlotMapper slotMapper,
                           com.orbit.scheduling.schedule.ScheduleStateService stateService,
                           @Value("${orbit.scheduling.lease-seconds:300}") int leaseSeconds,
                           @Value("${orbit.scheduling.heartbeat-seconds:15}") int heartbeatSeconds) {
        this.jobMapper = jobMapper;
        this.eventMapper = eventMapper;
        this.snapshotService = snapshotService;
        this.ruleSetService = ruleSetService;
        this.capacityService = capacityService;
        this.slotMapper = slotMapper;
        this.stateService = stateService;
        // 与容量槽位一致：租约不允许短于 120 秒
        this.leaseSeconds = Math.max(120, leaseSeconds);
        this.heartbeatSeconds = Math.max(1, heartbeatSeconds);
    }

    public int leaseSeconds() {
        return leaseSeconds;
    }

    public int heartbeatSeconds() {
        return heartbeatSeconds;
    }

    // ------------------------------------------------------------------ 入队

    /**
     * 入队（由 {@code POST /api/schedule/generate} 调用）。
     *
     * <p>⚠ 快照在**入队时**冻结，不是领单时 —— 因为"用户点下按钮的那一刻看到的数据"
     * 才是他要排的那份。
     */
    @Transactional
    public ScheduleJobEntity enqueue(long organizationId, Long createdByUserId,
                                     String ruleSetHash, long snapshotId, int maxAttempts) {
        ScheduleJobEntity job = new ScheduleJobEntity();
        job.setOrganizationId(organizationId);
        job.setJobToken(newJobToken());
        job.setStatus("pending");
        job.setRuleSetHash(ruleSetHash);
        job.setSnapshotId(snapshotId);
        job.setAttempt(0);
        job.setMaxAttempts(maxAttempts);
        job.setProgress(0);
        job.setCancelRequested(false);
        job.setCreatedBy(createdByUserId);
        job.setCreatedAt(UtcTimes.now());
        jobMapper.insert(job);

        eventMapper.insert(ScheduleJobEventEntity.of(organizationId, job.getId(),
                ScheduleJobEventEntity.ENQUEUED, null, 0, "任务入队"));
        log.info("任务入队: org={} job={} hash={}", organizationId, job.getId(),
                ruleSetHash == null ? "-" : ruleSetHash.substring(0, Math.min(12, ruleSetHash.length())));
        return job;
    }

    // ------------------------------------------------------------------ 领单

    /**
     * 领一个任务。没有可领的就返回 null（worker 会隔一会儿再来问）。
     *
     * <p>⚠ 整个方法必须在**一个事务**里：{@code FOR UPDATE} 的行锁在事务提交时释放。
     * 如果把它拆成"先查再改"，锁就白加了，两个 worker 会领到同一单。
     */
    @Transactional
    public JobClaimResponse claim(String workerId) {
        LocalDateTime now = UtcTimes.now();
        ScheduleJobEntity job = jobMapper.lockNextClaimable(now);
        if (job == null) {
            return null;
        }
        // 领到的是"租约已经过期"的 running → 这是接管，说明上一个 worker 没收尾
        boolean takeOver = "running".equals(job.getStatus());
        // 接管时，job 上还留着**上一个持有者的 token**（下面 markRunning 才换掉）。
        // 必须先用它把那个死掉的 worker 占着的槽位释放掉 ——
        // 否则槽位会永久泄漏：新 worker 完成时按**新** token 释放，释放不到旧的那份，
        // 排课产能就被一次次崩溃慢慢吃光。
        // （这个 bug 是"真 kill -9"故障注入跑出来才发现的，纯模拟租约过期看不到。）
        String previousToken = job.getJobToken();

        String token = newJobToken();

        // ⚠ 容量槽位在**这里**抢，不在入队时抢。
        //   顺序也是刻意的：先试槽位、抢不到就**什么都不写**地返回 null
        //   （事务提交时只有 FOR UPDATE 的行锁被释放，任务仍是 pending），
        //   这样"没产能"不会把一个任务误标成 running。
        Long slotId = capacityService.acquire(job.getOrganizationId(), token).orElse(null);
        if (slotId == null) {
            log.debug("没有空闲的求解槽位，暂不领单（任务保持 pending）: job={}", job.getId());
            return null;
        }
        if (takeOver && previousToken != null) {
            int freed = slotMapper.releaseByToken(previousToken);
            if (freed > 0) {
                log.info("接管时释放了上一个持有者占用的槽位: job={} 旧 token={}",
                        job.getId(), previousToken.substring(0, 8));
            }
        }

        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        int affected = jobMapper.markRunning(job.getId(), token, workerId, leaseUntil, now);
        if (affected != 1) {
            // 理论上不会发生（行还锁在我们手里），但真发生了就当作没领到，别硬来
            log.warn("领单失败（状态在锁定期间被改动）: job={}", job.getId());
            capacityService.release(slotId, token);
            return null;
        }
        int attempt = (job.getAttempt() == null ? 0 : job.getAttempt()) + 1;
        eventMapper.insert(ScheduleJobEventEntity.of(job.getOrganizationId(), job.getId(),
                takeOver ? ScheduleJobEventEntity.TAKEOVER : ScheduleJobEventEntity.CLAIMED,
                workerId, attempt,
                takeOver
                        ? "接管：上一个持有者的租约已过期（attempt " + attempt + "）"
                        : "领单（attempt " + attempt + "）"));
        if (takeOver) {
            log.warn("接管任务: job={} org={} 原 worker={} attempt={}", job.getId(),
                    job.getOrganizationId(), job.getWorkerId(), attempt);
        }

        // 快照读一次就够：既要它的 fingerprint（给 worker 做溯源），也要它的 payload（求解输入）
        SchedulingSnapshotEntity snapshot =
                snapshotService.require(job.getOrganizationId(), job.getSnapshotId());

        return new JobClaimResponse(
                job.getId(), token, job.getOrganizationId(), job.getSnapshotId(),
                snapshot.getFingerprint(), job.getRuleSetHash(),
                ruleSetService.rulesByHash(job.getOrganizationId(), job.getRuleSetHash()),
                snapshotService.toInput(snapshot),
                attempt, job.getMaxAttempts(), leaseSeconds, heartbeatSeconds, takeOver);
    }

    // ------------------------------------------------------------------ 回写

    /** 心跳续租。false = token 失效（被接管 / 已终结）→ worker 应当停止求解。 */
    public boolean heartbeat(long jobId, String jobToken, int progress) {
        LocalDateTime now = UtcTimes.now();
        int affected = jobMapper.heartbeat(jobId, jobToken,
                now.plusSeconds(leaseSeconds), now, Math.max(0, Math.min(100, progress)));
        return affected == 1;
    }

    /** 完成。false = 结果被丢弃（多半是被接管了）—— **不是错误**。 */
    @Transactional
    public boolean complete(long jobId, String jobToken, String solverStatus,
                            String modelFingerprint, Long objectiveValue,
                            Long versionId, Integer entryCount) {
        LocalDateTime now = UtcTimes.now();
        int affected = jobMapper.complete(jobId, jobToken, solverStatus, modelFingerprint,
                objectiveValue, versionId, entryCount, now);
        if (affected != 1) {
            log.warn("完成任务被拒（token 已失效）: job={}", jobId);
            return false;
        }
        ScheduleJobEntity job = jobMapper.selectById(jobId);
        eventMapper.insert(ScheduleJobEventEntity.of(job.getOrganizationId(), jobId,
                ScheduleJobEventEntity.COMPLETED, job.getWorkerId(), job.getAttempt(),
                "完成：" + entryCount + " 条明细，求解状态 " + solverStatus));
        // 让出求解槽位，别人可以开始跑
        slotMapper.releaseByToken(jobToken);
        return true;
    }

    /** 失败。{@code retryable=true} 且还没超过次数 → 回到 pending 让别人再试一次。 */
    @Transactional
    public boolean fail(long jobId, String jobToken, String error, String solverStatus,
                        boolean retryable) {
        LocalDateTime now = UtcTimes.now();
        int affected = jobMapper.fail(jobId, jobToken, truncate(error, 500), solverStatus,
                retryable, now);
        if (affected != 1) {
            log.warn("标记失败被拒（token 已失效）: job={}", jobId);
            return false;
        }
        ScheduleJobEntity job = jobMapper.selectById(jobId);
        boolean requeued = "pending".equals(job.getStatus());
        eventMapper.insert(ScheduleJobEventEntity.of(job.getOrganizationId(), jobId,
                ScheduleJobEventEntity.FAILED, job.getWorkerId(), job.getAttempt(),
                (requeued ? "失败（可重试，已退回队列）：" : "失败（不再重试）：") + truncate(error, 300)));
        // ⚠ 无论是否退回队列，都要让出槽位 —— 否则一次失败就永久吃掉一份产能
        slotMapper.releaseByToken(jobToken);

        // ⚠⚠ 终态失败还必须**收尾对外状态**。
        //   最初漏了这一步，症状是：任务已经 failed，而 scheduling_state 仍是 running，
        //   于是**这所学校再也发不起新的排课**（每次 generate 都被 409
        //   "某某正在排课，请稍后再试"挡住）—— 与"Redis 锁没人释放"是同一类事故。
        //
        //   退回队列的情况**不动**状态：那次任务还在进行中（只是换个人重做）。
        if (!requeued) {
            stateService.finishByOrganization(job.getOrganizationId(), "failed", NO_SOLUTION_MESSAGE);
        }
        return true;
    }

    /** 与 P1/P2 逐字一致的失败文案 —— 对外契约不能因为 P3 换了执行方式就改掉。 */
    private static final String NO_SOLUTION_MESSAGE =
            "排课无解，请检查任教关系、周课时、固定时段和教师课时上限";

    /** worker 察觉取消请求 → 主动交还。 */
    @Transactional
    public boolean markCancelled(long jobId, String jobToken) {
        LocalDateTime now = UtcTimes.now();
        int affected = jobMapper.markCancelled(jobId, jobToken, now);
        if (affected != 1) {
            return false;
        }
        ScheduleJobEntity job = jobMapper.selectById(jobId);
        eventMapper.insert(ScheduleJobEventEntity.of(job.getOrganizationId(), jobId,
                ScheduleJobEventEntity.CANCELLED, job.getWorkerId(), job.getAttempt(),
                "worker 响应取消请求"));
        slotMapper.releaseByToken(jobToken);
        return true;
    }

    /** 这个任务是否被要求取消（worker 在求解循环里轮询它）。 */
    public boolean isCancelRequested(long jobId, String jobToken) {        ScheduleJobEntity job = jobMapper.selectOne(Wrappers.<ScheduleJobEntity>lambdaQuery()
                .select(ScheduleJobEntity::getCancelRequested, ScheduleJobEntity::getJobToken)
                .eq(ScheduleJobEntity::getId, jobId));
        return job != null
                && Boolean.TRUE.equals(job.getCancelRequested())
                && jobToken.equals(job.getJobToken());
    }

    // ------------------------------------------------------------------ 查询与运维

    public List<ScheduleJobEntity> list(long organizationId, String status) {
        return jobMapper.selectList(Wrappers.<ScheduleJobEntity>lambdaQuery()
                .eq(ScheduleJobEntity::getOrganizationId, organizationId)
                .eq(status != null && !status.isBlank(), ScheduleJobEntity::getStatus, status)
                .orderByDesc(ScheduleJobEntity::getId));
    }

    /**
     * 分页列表（任务接口用）。
     *
     * <p>⚠ 用**数据库分页**而不是"全查出来再在内存里切" —— 任务表是会长的那类表
     * （每次排课一行，还带事件流），和"规则表天然有限"不是一回事。
     * 项目里的分页契约（§9.2）本来就要求服务端分页，这里照办。
     *
     * <p>排序固定 {@code id DESC}（最新的在前），不接受前端指定列 ——
     * 让客户端传排序列名进 SQL 是不必要的攻击面，而任务列表也确实只有这一种合理排序。
     */
    public PageResponse<ScheduleJobEntity> listByPage(long organizationId, String status,
                                                      PageQuery query) {
        Page<ScheduleJobEntity> page = new Page<>(query.page(), query.size());
        Page<ScheduleJobEntity> result = jobMapper.selectPage(page,
                Wrappers.<ScheduleJobEntity>lambdaQuery()
                        .eq(ScheduleJobEntity::getOrganizationId, organizationId)
                        .eq(status != null && !status.isBlank(), ScheduleJobEntity::getStatus, status)
                        .orderByDesc(ScheduleJobEntity::getId));
        return PageResponse.of(result.getRecords(), result.getTotal());
    }

    public ScheduleJobEntity requireJob(long organizationId, long jobId) {
        ScheduleJobEntity job = jobMapper.selectOne(Wrappers.<ScheduleJobEntity>lambdaQuery()
                .eq(ScheduleJobEntity::getId, jobId)
                .eq(ScheduleJobEntity::getOrganizationId, organizationId));
        if (job == null) {
            throw BusinessException.notFound("排课任务不存在");
        }
        return job;
    }

    /** 只按 id 取（内部接口用 —— worker 只有 jobId，没有租户上下文）。 */
    public ScheduleJobEntity requireJobById(long jobId) {
        ScheduleJobEntity job = jobMapper.selectById(jobId);
        if (job == null) {
            throw BusinessException.notFound("排课任务不存在");
        }
        return job;
    }

    public List<ScheduleJobEventEntity> events(long jobId) {
        return eventMapper.selectList(Wrappers.<ScheduleJobEventEntity>lambdaQuery()
                .eq(ScheduleJobEventEntity::getJobId, jobId)
                .orderByAsc(ScheduleJobEventEntity::getId));
    }

    /**
     * "我前面还有几个" —— 只对 {@code pending} 有意义。
     *
     * <p>口径与领单完全一致（都是 {@code ORDER BY id}），否则显示的排队位置会是错的。
     */
    public long queuePosition(long organizationId, long jobId) {
        Long count = jobMapper.selectCount(Wrappers.<ScheduleJobEntity>lambdaQuery()
                .eq(ScheduleJobEntity::getStatus, "pending")
                .lt(ScheduleJobEntity::getId, jobId));
        return count == null ? 0 : count;
    }

    /** 取消：还没被领走就直接终结；正在跑的只打标记，由 worker 自己收手。 */
    @Transactional
    public ScheduleJobEntity cancel(long organizationId, long jobId) {
        ScheduleJobEntity job = requireJob(organizationId, jobId);
        if ("done".equals(job.getStatus()) || "failed".equals(job.getStatus())
                || "cancelled".equals(job.getStatus())) {
            throw BusinessException.badRequest("该任务已经结束，无法取消");
        }
        if ("pending".equals(job.getStatus())) {
            jobMapper.cancelPending(jobId, organizationId, UtcTimes.now());
            eventMapper.insert(ScheduleJobEventEntity.of(organizationId, jobId,
                    ScheduleJobEventEntity.CANCELLED, null, job.getAttempt(), "取消（尚未开始）"));
            // ⚠ 取消一个还没开始的任务同样是"终态"，必须收尾对外状态 ——
            //   否则这所学校会一直显示"正在排课"，发不起新任务。
            //   （与失败路径是同一类问题，只是更容易被忽略：取消看起来"什么都没发生"。）
            stateService.finishByOrganization(organizationId, "idle", "排课任务已取消");
        } else {
            jobMapper.requestCancel(jobId, organizationId);
            eventMapper.insert(ScheduleJobEventEntity.of(organizationId, jobId,
                    ScheduleJobEventEntity.CANCELLED, job.getWorkerId(), job.getAttempt(),
                    "取消请求已发出，等待 worker 收手"));
        }
        return requireJob(organizationId, jobId);
    }

    /** 人工重试：只对失败的/已取消的任务有意义。 */
    @Transactional
    public ScheduleJobEntity retry(long organizationId, long jobId) {
        ScheduleJobEntity job = requireJob(organizationId, jobId);
        if (jobMapper.requeue(jobId, organizationId) != 1) {
            throw BusinessException.badRequest("只有失败或已取消的任务可以重试");
        }
        eventMapper.insert(ScheduleJobEventEntity.of(organizationId, jobId,
                ScheduleJobEventEntity.RETRIED, null, 0, "人工重试"));
        return requireJob(organizationId, jobId);
    }

    /** 租约是否已过期（**派生**字段，不落库 —— 它每时每刻都在变）。 */
    public boolean isLeaseExpired(ScheduleJobEntity job, LocalDateTime now) {
        return "running".equals(job.getStatus())
                && (job.getLeaseUntil() == null || job.getLeaseUntil().isBefore(now));
    }

    /** 对应 Python 的 {@code secrets.token_hex(24)}（48 个十六进制字符）。 */
    private String newJobToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 供 P2 的诊断接口复用：任务的规则集快照。 */
    public List<ConstraintRule> rulesOf(ScheduleJobEntity job) {
        return ruleSetService.rulesByHash(job.getOrganizationId(), job.getRuleSetHash());
    }
}
