package com.orbit.academic.subject;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量更新某科目在各年级的周课时 —— 对应 Python {@code GradeSubjectBulkUpdate}。
 *
 * <pre>{@code
 * class GradeSubjectBulkItem(BaseModel):
 *     grade_id: int
 *     weekly_hours: int = Field(ge=0, le=40)     # ← 范围校验，超了是 422
 *
 * class GradeSubjectBulkUpdate(BaseModel):
 *     subject_id: int
 *     items: list[GradeSubjectBulkItem]
 * }</pre>
 *
 * <p>⚠ {@code weeklyHours} 的 <b>0..40</b> 范围校验必须复刻：
 * 它落在 Pydantic 里，所以超范围返回的是 **422**（不是 400）。
 * 用 {@code @Min/@Max} 正好落在同一条路径上（Spring 的校验失败 → 422）。
 *
 * <p>语义：{@code weekly_hours == 0} 表示**删除**该年级的这条课时记录
 * （Python 里是 {@code if item.weekly_hours == 0: db.delete(current)}），
 * 不是"设为 0 小时"。
 */
public record GradeSubjectBulkRequest(
        @NotNull(message = "Field required") Long subjectId,
        @NotNull(message = "Field required") @Valid List<Item> items) {

    /** 一条年级课时。 */
    public record Item(
            @NotNull(message = "Field required") Long gradeId,
            @NotNull(message = "Field required")
            @Min(value = 0, message = "Input should be greater than or equal to 0")
            @Max(value = 40, message = "Input should be less than or equal to 40")
            Integer weeklyHours) {
    }
}
