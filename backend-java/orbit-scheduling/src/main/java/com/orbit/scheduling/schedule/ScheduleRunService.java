package com.orbit.scheduling.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.job.JobQueueService;
import com.orbit.scheduling.job.ScheduleJobEntity;
import com.orbit.scheduling.job.SchedulingSnapshotEntity;
import com.orbit.scheduling.job.SnapshotService;
import com.orbit.scheduling.lock.ScheduleLockService;
import com.orbit.scheduling.rule.RuleSetService;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 发起排课 —— 对应 Python {@code trigger_schedule}，P1 收官的那个接口。
 *
 * <h2>P3 起：从"起线程"改成"入队"</h2>
 * 四道关卡，顺序不能变：
 * <ol>
 *   <li><b>Redis 锁</b>：抢不到 → 409 {@code 本校已有排课任务正在运行，请稍后再试}
 *       （挡住跨实例的重复提交，在碰数据库之前就拦掉）</li>
 *   <li><b>状态检查</b>：有未过租约的 running → 409 {@code <谁>正在排课，请稍后再试}</li>
 *   <li><b>CAS 抢状态</b>：条件 UPDATE（{@code status<>'running' OR started_at IS NULL
 *       OR started_at < 租约线}）受影响行数必须为 1 —— 它同时完成"接管过期任务"</li>
 *   <li><b>冻结快照 + 入队</b>：失败则标记 failed 并释放锁</li>
 * </ol>
 *
 * <p>⚠ <b>容量槽位不再在这里抢</b>（P1/P2 的第三关）。有了队列之后任务可能在队列里
 * 躺几分钟，那时占着槽位等于**空占产能**。槽位改到"领单"时抢 ——
 * 领到才算真的在跑（见 {@code JobQueueService.claim}）。
 *
 * <p>⚠ 对外文案与状态码**没有变**（P1 的契约由影子对比工具守着），
 * 只是 {@code message} 从"正在排课..."改成了"已入队，等待求解…"。
 * 前端只把它当一句话显示，不影响解析。
 *
 * <p>⚠ <b>刻意不加 {@code @Transactional}</b>：这几步是"每步提交"的
 * （CAS 后提交、入队后提交）。包成一个大事务会让"抢到状态"到"任务可被领走"
 * 之间的时间窗被拉长，也让失败回滚的语义变得和 P1 不同。
 */
@Service
public class ScheduleRunService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunService.class);

    private final SchedulingStateMapper stateMapper;
    private final ScheduleStateService stateService;
    private final ScheduleLockService lockService;
    private final JobQueueService queueService;
    private final SnapshotService snapshotService;
    private final RuleSetService ruleSetService;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final SecureRandom random = new SecureRandom();

    public ScheduleRunService(SchedulingStateMapper stateMapper,
                              ScheduleStateService stateService,
                              ScheduleLockService lockService,
                              JobQueueService queueService,
                              SnapshotService snapshotService,
                              RuleSetService ruleSetService,
                              @Value("${orbit.scheduling.lease-seconds:300}") int leaseSeconds,
                              @Value("${orbit.scheduling.max-attempts:3}") int maxAttempts) {
        this.stateMapper = stateMapper;
        this.stateService = stateService;
        this.lockService = lockService;
        this.queueService = queueService;
        this.snapshotService = snapshotService;
        this.ruleSetService = ruleSetService;
        // 与 CapacityService 一致：租约不允许短于 120 秒
        this.leaseSeconds = Math.max(120, leaseSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** 发起排课；返回**刚写入的状态**（等价于 Python 返回 {@code _state_out(state)}）。 */
    public ScheduleStatusResponse trigger(SchedulePrincipal principal) {
        long organizationId = principal.organizationId();
        LocalDateTime now = UtcTimes.now();
        LocalDateTime staleBefore = now.minusSeconds(leaseSeconds);
        String jobToken = newJobToken();

        // ① Redis 锁
        ScheduleLockService.LockAttempt lock = lockService.acquire(organizationId);
        if (!lock.acquired()) {
            throw BusinessException.conflict("本校已有排课任务正在运行，请稍后再试");
        }

        // ② 有未过租约的 running → 别人在跑
        SchedulingState current = stateService.findByOrganization(organizationId);
        if (current != null && "running".equals(current.getStatus())
                && current.getStartedAt() != null
                && !current.getStartedAt().isBefore(staleBefore)) {
            lockService.release(organizationId, lock.token());
            throw BusinessException.conflict(ownerOf(current) + "正在排课，请稍后再试");
        }

        // ③ CAS 抢状态（条件里带租约线 → 同时完成"接管过期任务"）
        int acquired;
        try {
            acquired = stateMapper.update(null, Wrappers.<SchedulingState>lambdaUpdate()
                    .eq(SchedulingState::getOrganizationId, organizationId)
                    .and(where -> where
                            .ne(SchedulingState::getStatus, "running")
                            .or().isNull(SchedulingState::getStartedAt)
                            .or().lt(SchedulingState::getStartedAt, staleBefore))
                    .set(SchedulingState::getStatus, "running")
                    .set(SchedulingState::getMessage, "已入队，等待求解…")
                    .set(SchedulingState::getOwnerUserId, principal.userId())
                    .set(SchedulingState::getOwnerName, principal.displayName())
                    .set(SchedulingState::getStartedAt, now)
                    .set(SchedulingState::getFinishedAt, null)
                    .set(SchedulingState::getJobToken, jobToken));
        } catch (RuntimeException e) {
            lockService.release(organizationId, lock.token());
            throw e;
        }
        if (acquired != 1) {
            lockService.release(organizationId, lock.token());
            SchedulingState state = stateService.findByOrganization(organizationId);
            throw BusinessException.conflict(ownerOf(state) + "正在排课，请稍后再试");
        }

        // ④ 冻结输入快照 + 锁定规则集，然后入队
        //
        // ⚠ 容量槽位**不在这里抢**。P1/P2 是"抢到槽位 → 起线程立即求解"，
        //   所以槽位与任务同生共死；P3 有了队列之后，任务可能在队列里躺几分钟，
        //   此时占着槽位等于**空占产能**、把别人挡在门外。
        //   所以槽位改到"领单"时抢（见 JobQueueService.claim），领到才算真的在跑。
        try {
            SchedulingSnapshotEntity snapshot = snapshotService.freeze(organizationId);
            String ruleSetHash = ruleSetService.hashOf(organizationId);
            ScheduleJobEntity job = queueService.enqueue(organizationId, principal.userId(),
                    ruleSetHash, snapshot.getId(), maxAttempts);
            log.info("排课任务已入队: org={} job={} snapshot={} 输入指纹={}",
                    organizationId, job.getId(), snapshot.getId(),
                    snapshot.getFingerprint().substring(0, 12));
        } catch (RuntimeException e) {
            log.error("入队失败 org={}", organizationId, e);
            stateService.finish(organizationId, jobToken, "failed", "排课任务入队失败");
            lockService.release(organizationId, lock.token());
            throw e;
        }

        // ⚠⚠ 入队成功后**立刻释放 Redis 锁** —— P3 改变了它的职责。
        //
        // P1/P2 时锁要一直握到求解结束（由 runner 的 finally 释放），因为那时
        // "谁在跑"只有锁知道。P3 有了任务队列之后：
        //   * 跨实例的互斥由 `schedule_jobs` 的 FOR UPDATE SKIP LOCKED 保证
        //   * 同校互斥由 `scheduling_state` 的 CAS（上面那步）+ 任务 status 保证
        //   * 而锁**如果继续握着，第一次排课之后这所学校就被永久锁死了** ——
        //     因为 worker 根本不知道锁的存在，没人会去释放它。
        //     （这个 bug 是实测跑第二遍 generate 时才暴露的：直接 409。）
        //
        // 所以锁缩回它真正的职责：**保护"检查 + CAS + 入队"这个短临界区**，
        // 防止两个并发请求同时通过状态检查。
        lockService.release(organizationId, lock.token());

        return ScheduleStatusResponse.from(stateService.findByOrganization(organizationId));
    }

    /** 对应 Python 的 {@code f"{state.owner_name or '其他用户'}正在排课，请稍后再试"}。 */
    private static String ownerOf(SchedulingState state) {
        return state == null || state.getOwnerName() == null ? "其他用户" : state.getOwnerName();
    }

    /** 对应 Python 的 {@code secrets.token_hex(24)}（48 个十六进制字符，列宽 64 够放）。 */
    private String newJobToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
