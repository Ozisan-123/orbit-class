package com.orbit.scheduling.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.util.UtcTimes;
import org.springframework.stereotype.Service;

/**
 * 排课状态机的读写 —— 对应 Python {@code schedule.py} 的 {@code _finish} / 状态查询。
 *
 * <p>所有写操作都带 {@code job_token} 条件（**fencing token**）：
 * <pre>{@code
 * UPDATE scheduling_state SET status=?, message=?, finished_at=?
 *  WHERE organization_id=? AND job_token=? AND status='running'
 * }</pre>
 * 这样即使一个"被判定超时"的旧任务活过来继续写，也**影响不到新任务** ——
 * 它的 token 已经对不上了。这是"排课原子性"的核心手法（方案 §3 铁律 1）。
 */
@Service
public class ScheduleStateService {

    private final SchedulingStateMapper stateMapper;

    public ScheduleStateService(SchedulingStateMapper stateMapper) {
        this.stateMapper = stateMapper;
    }

    /** 读状态（没有行就返回 null，由调用方决定怎么呈现）。 */
    public SchedulingState findByOrganization(long organizationId) {
        return stateMapper.selectOne(Wrappers.<SchedulingState>lambdaQuery()
                .eq(SchedulingState::getOrganizationId, organizationId));
    }

    /**
     * 收尾：把状态从 running 改成终态。对应 Python 的 {@code _finish}。
     *
     * <p>⚠ 它**独立于调用方的事务**：Python 用的是新建的 {@code SessionLocal()}，
     * 所以即使求解过程的事务回滚了，状态更新依然落库。
     * 这里不开事务（每条语句自动提交），效果相同。
     *
     * @return 受影响行数；0 表示"这条状态已经被别人改过"（token 或 status 不匹配）
     */
    public int finish(long organizationId, String jobToken, String status, String message) {
        return stateMapper.update(null, Wrappers.<SchedulingState>lambdaUpdate()
                .eq(SchedulingState::getOrganizationId, organizationId)
                .eq(SchedulingState::getJobToken, jobToken)
                .eq(SchedulingState::getStatus, "running")
                .set(SchedulingState::getStatus, status)
                .set(SchedulingState::getMessage, message)
                .set(SchedulingState::getFinishedAt, UtcTimes.now()));
    }

    /**
     * 收尾（**P3 的任务路径用**）：不带 token 条件，只按"当前是 running"来改。
     *
     * <h2>为什么可以去掉 token 条件</h2>
     * P1/P2 时 {@link #finish} 带 token 是为了防"被判定超时的旧任务活过来覆盖新任务"。
     * P3 之后这个责任转移到了**任务行**上：{@code JobResultWriter} 落库前会
     * {@code SELECT ... FOR UPDATE WHERE job_token = ? AND status='running'}，
     * 拿不到就**直接丢弃结果**、根本不会走到这里。
     *
     * <p>而且 token 条件在这里**已经无法满足**了：{@code trigger} 写进
     * {@code scheduling_state.job_token} 的是**入队时**的 token，
     * 而每次领单都会换新 token（那是栅栏的设计）—— 两者必然不同。
     *
     * <p>所以判据改成"这条状态现在是不是 running"：只有真正把版本写成功的那个
     * 持有者会调它，安全性由上游的 token 校验保证。
     */
    public int finishByOrganization(long organizationId, String status, String message) {
        return stateMapper.update(null, Wrappers.<SchedulingState>lambdaUpdate()
                .eq(SchedulingState::getOrganizationId, organizationId)
                .eq(SchedulingState::getStatus, "running")
                .set(SchedulingState::getStatus, status)
                .set(SchedulingState::getMessage, message)
                .set(SchedulingState::getFinishedAt, UtcTimes.now()));
    }

    /**
     * 心跳：把 {@code started_at} 往前推，表示"我还活着"。
     *
     * <p>复用 {@code started_at} 字段作为心跳时间（Python 的做法）——
     * 因为超时判定与"正在排课"的展示都看这一个字段，
     * 另加一个 heartbeat_at 列会引入"两个时间哪个为准"的问题。
     *
     * @return 受影响行数；0 表示已被接管，心跳线程应停止
     */
    public int heartbeat(long organizationId, String jobToken) {
        return stateMapper.update(null, Wrappers.<SchedulingState>lambdaUpdate()
                .eq(SchedulingState::getOrganizationId, organizationId)
                .eq(SchedulingState::getJobToken, jobToken)
                .eq(SchedulingState::getStatus, "running")
                .set(SchedulingState::getStartedAt, UtcTimes.now()));
    }
}
