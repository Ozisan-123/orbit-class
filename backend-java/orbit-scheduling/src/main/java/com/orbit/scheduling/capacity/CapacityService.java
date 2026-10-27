package com.orbit.scheduling.capacity;

import com.orbit.common.util.UtcTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 容量租约 —— 对应 Python {@code scheduler/capacity.py}。
 *
 * <h2>为什么用「条件 UPDATE + 看 rowcount」而不是「先查再改」</h2>
 * 抢槽位必须是**原子的**：
 * <pre>{@code
 * UPDATE schedule_worker_slots
 *    SET organization_id=?, job_token=?, lease_until=?
 *  WHERE id=? AND (job_token IS NULL OR lease_until IS NULL OR lease_until < now)
 * }</pre>
 * 受影响行数 = 1 → 我抢到了；= 0 → 被别人占着。
 * 两个请求同时打过来，InnoDB 的行锁会让它们串行，只有一个能拿到 1 ✓
 *
 * <p>这正是方案 §4.2 选 MyBatis 而不是 JPA 的原因 —— 条件 UPDATE 的 rowcount
 * 就是"抢到了吗"的答案，一条语句完成，不需要额外的查询或乐观锁重试。
 *
 * <h2>⚠ 故意不加 {@code @Transactional}</h2>
 * Python 的实现是**每个槽位尝试后立刻 commit**：
 * <pre>{@code
 * for slot_id in range(1, MAX + 1):
 *     changed = ...update(...)
 *     db.commit()          # ← 逐个提交
 *     if changed == 1: return slot_id
 * }</pre>
 * 如果整个循环包在一个事务里，失败的尝试虽然不留数据，但会让事务持有一批行锁、
 * 且"抢到后到外层提交之间"的时间窗被拉长，别的实例更难抢到。
 * 这里的 mapper 调用**不开事务**，每条语句自动提交，与 Python 的行为一致。
 *
 * <p>续租 {@code lease_until} 是为了让**持有者崩溃后槽位能自动回收** ——
 * 不需要人去清库。
 */
@Service
public class CapacityService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CapacityService.class);

    private final ScheduleWorkerSlotMapper slotMapper;
    private final int maxConcurrent;
    private final int leaseSeconds;

    public CapacityService(ScheduleWorkerSlotMapper slotMapper,
                           @Value("${orbit.scheduling.max-concurrent-schedules:2}") int maxConcurrent,
                           @Value("${orbit.scheduling.lease-seconds:300}") int leaseSeconds) {
        this.slotMapper = slotMapper;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        // Python: max(120, ...) —— 租约不允许短于 120 秒，否则求解还没跑完就被判定超时
        this.leaseSeconds = Math.max(120, leaseSeconds);
    }

    /** 启动时预置槽位行（对应 Python 的 {@code initialize_capacity()}）。 */
    @Override
    public void run(ApplicationArguments args) {
        for (long slotId = 1; slotId <= maxConcurrent; slotId++) {
            slotMapper.insertIgnore(slotId);
        }
        log.info("容量槽位已就绪：{} 个（租约 {} 秒）", maxConcurrent, leaseSeconds);
    }

    /**
     * 抢一个槽位。
     *
     * @return 槽位号；**空表示容量已满**（接口要回 429）
     */
    public Optional<Long> acquire(long organizationId, String jobToken) {
        LocalDateTime now = UtcTimes.now();
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        for (long slotId = 1; slotId <= maxConcurrent; slotId++) {
            if (slotMapper.tryAcquire(slotId, organizationId, jobToken, leaseUntil, now) == 1) {
                return Optional.of(slotId);
            }
        }
        return Optional.empty();
    }

    /** 续租。token 不匹配（被别人接管了）→ false。 */
    public boolean renew(long slotId, String jobToken) {
        return slotMapper.renew(slotId, jobToken, UtcTimes.now().plusSeconds(leaseSeconds)) == 1;
    }

    /** 释放。token 不匹配则什么都不做（幂等，不会误释放别人的槽位）。 */
    public void release(long slotId, String jobToken) {
        slotMapper.release(slotId, jobToken);
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int leaseSeconds() {
        return leaseSeconds;
    }
}
