package com.orbit.scheduling.swap;

import jakarta.validation.constraints.NotNull;

/**
 * 拖动/换课请求 —— 对应 Python 的 {@code ScheduleSwapRequest}。
 *
 * <pre>{@code
 * source_entry_id: int      # 要移动的那节课
 * target_class_id: int      # 目标班级（同班拖动就填自己）
 * target_day_of_week: int
 * target_period_index: int
 * expected_revision: int    # 乐观锁，必填
 * }</pre>
 *
 * <p>它是"拖动一格"这个动作的完整描述：**移动哪节课 → 放到哪个班的哪一天哪一节**。
 * 同班拖动与跨班协同换时共用这一个结构，靠 {@code target_class_id} 是否等于源班级来区分。
 */
public record SwapRequest(
        @NotNull(message = "Field required") Long sourceEntryId,
        @NotNull(message = "Field required") Long targetClassId,
        @NotNull(message = "Field required") Integer targetDayOfWeek,
        @NotNull(message = "Field required") Integer targetPeriodIndex,
        @NotNull(message = "Field required") Integer expectedRevision) {
}
