package com.orbit.scheduling.schedule;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code scheduling_state} 的 Mapper。 */
@Mapper
public interface SchedulingStateMapper extends BaseMapper<SchedulingState> {

    /**
     * 结果落库前**锁住状态行**并确认任务仍然是自己的。
     *
     * <p>对应 Python {@code _run_scheduling} 里的：
     * <pre>{@code
     * state = db.query(SchedulingState).filter(
     *     org == ..., job_token == job_token, status == "running",
     * ).with_for_update().first()
     * if state is None or slot is None:
     *     db.rollback(); return        # 被接管了 —— 结果直接丢弃
     * }</pre>
     *
     * <p>这一步是**求解与落库之间的闸门**：求解可能跑了几十秒，期间租约可能超时、
     * 任务可能已被别人接管。如果不用 {@code FOR UPDATE} 重新确认，
     * 一个"已经过期"的求解结果会覆盖掉新任务的成果。
     *
     * <p>返回 null 表示"已经不是我的任务了" → 调用方应**丢弃结果**（不是报错）。
     */
    @Select("""
            SELECT * FROM scheduling_state
             WHERE organization_id = #{organizationId}
               AND job_token = #{jobToken}
               AND status = 'running'
             FOR UPDATE
            """)
    SchedulingState lockForRunning(@Param("organizationId") long organizationId,
                                   @Param("jobToken") String jobToken);
}
