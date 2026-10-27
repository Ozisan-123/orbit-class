package com.orbit.scheduling.capacity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * {@code schedule_worker_slots} 的 Mapper —— 容量租约的原子操作都在这。
 *
 * <p>⚠⚠ <b>改语句的注解不能随便挑</b>：写操作（UPDATE/INSERT/DELETE）必须用
 * {@link Update}/{@link Insert}，**不能用 {@link Select}**。
 * 用 {@code @Select} 标 UPDATE 时 MyBatis 会把它当查询执行，
 * 拿不到"受影响行数"，方法返回 null → 撞上 {@code int} 返回类型就抛：
 * <pre>
 * BindingException: Mapper method 'tryAcquire attempted to return null
 *                   from a method with a primitive return type (int)'
 * </pre>
 * 这个错**构建期完全看不出来**，只有真的点到"排课"才会炸。实测踩过一次。
 */
@Mapper
public interface ScheduleWorkerSlotMapper extends BaseMapper<ScheduleWorkerSlot> {

    /**
     * 抢槽位用的条件 UPDATE —— 返回受影响行数，1 表示抢到了。
     *
     * <p>一条语句同时完成「判断是否可用」与「占用」：
     * {@code job_token IS NULL OR lease_until IS NULL OR lease_until < now}
     * 就是"这个槽位空着或已超时"。
     */
    @Update("""
            <script>
            UPDATE schedule_worker_slots
               SET organization_id = #{organizationId},
                   job_token = #{jobToken},
                   lease_until = #{leaseUntil}
             WHERE id = #{slotId}
               AND (job_token IS NULL OR lease_until IS NULL OR lease_until &lt; #{now})
            </script>
            """)
    int tryAcquire(@Param("slotId") long slotId,
                   @Param("organizationId") long organizationId,
                   @Param("jobToken") String jobToken,
                   @Param("leaseUntil") LocalDateTime leaseUntil,
                   @Param("now") LocalDateTime now);

    /** 续租：只有持有者（token 匹配）能续。返回受影响行数。 */
    @Update("""
            UPDATE schedule_worker_slots
               SET lease_until = #{leaseUntil}
             WHERE id = #{slotId} AND job_token = #{jobToken}
            """)
    int renew(@Param("slotId") long slotId,
              @Param("jobToken") String jobToken,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 释放：把槽位置空（三个字段都清掉）。返回受影响行数。 */
    @Update("""
            UPDATE schedule_worker_slots
               SET organization_id = NULL, job_token = NULL, lease_until = NULL
             WHERE id = #{slotId} AND job_token = #{jobToken}
            """)
    int release(@Param("slotId") long slotId, @Param("jobToken") String jobToken);

    /**
     * 按 token 释放 —— P3 的任务路径用它。
     *
     * <p>为什么不用 {@link #release}：任务回写结果时手里只有 {@code jobToken}，
     * 没有 slotId（那是在领单时才知道的，而任务表没存它）。
     * 而 claim 时用的 token 就是 job 的 token（每次领单都会换新的），所以
     * **token 本身就能唯一确定是哪个槽位** —— 不必为了释放再存一列、再改一次表。
     *
     * <p>它是**幂等**的：任务被接管后旧 worker 再来释放，会更新 0 行（token 已换），无害。
     */
    @Update("""
            UPDATE schedule_worker_slots
               SET organization_id = NULL, job_token = NULL, lease_until = NULL
             WHERE job_token = #{jobToken}
            """)
    int releaseByToken(@Param("jobToken") String jobToken);

    /**
     * 幂等地预置槽位行（对应 Python 的 {@code INSERT IGNORE}）。
     *
     * <p>用 {@code INSERT IGNORE} 而不是"先查再插"：多个实例同时启动时，
     * 两边都可能查到"不存在"然后都去插，其中一个会撞主键。
     * 交给数据库忽略重复更简单也更可靠。
     */
    @Insert("INSERT IGNORE INTO schedule_worker_slots (id) VALUES (#{slotId})")
    int insertIgnore(@Param("slotId") long slotId);

    /**
     * 结果落库前锁住槽位行，确认租约仍然是自己的。
     *
     * <p>与 {@code SchedulingStateMapper.lockForRunning} 配对使用 —— Python 那边
     * 也是同时 {@code with_for_update()} 两行，任一为空就丢弃结果。
     */
    @Select("""
            SELECT * FROM schedule_worker_slots
             WHERE id = #{slotId} AND job_token = #{jobToken}
             FOR UPDATE
            """)
    ScheduleWorkerSlot lockForJob(@Param("slotId") long slotId, @Param("jobToken") String jobToken);
}
