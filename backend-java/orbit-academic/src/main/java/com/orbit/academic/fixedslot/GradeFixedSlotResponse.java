package com.orbit.academic.fixedslot;

/** 年级固定课视图 —— 对应 Python {@code GradeFixedSlotOut}。 */
public record GradeFixedSlotResponse(Long id, Long gradeId, Long subjectId,
                                     Integer dayOfWeek, Integer periodIndex) {

    public static GradeFixedSlotResponse from(GradeFixedSlot slot) {
        return new GradeFixedSlotResponse(slot.getId(), slot.getGradeId(), slot.getSubjectId(),
                slot.getDayOfWeek(), slot.getPeriodIndex());
    }
}
