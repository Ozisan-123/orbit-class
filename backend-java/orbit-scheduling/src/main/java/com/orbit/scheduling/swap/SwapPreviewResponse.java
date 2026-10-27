package com.orbit.scheduling.swap;

import java.util.List;

/** 换课干跑结果：与正式 swap 使用同一校验，但事务最终回滚。 */
public record SwapPreviewResponse(
        boolean canSwap,
        List<AffectedEntry> affectedEntries,
        List<Conflict> conflicts,
        Integer expectedRevision) {

    public record AffectedEntry(Long entryId, Position before, Position after) {
    }

    public record Position(Long classId, Long subjectId, Long teacherId,
                           Integer dayOfWeek, Integer periodIndex) {
    }

    public record Conflict(String type, String message) {
    }
}
