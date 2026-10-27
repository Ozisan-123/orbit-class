package com.orbit.scheduling.availability;

/** 某个资源在指定课表版本、星期与节次上的占用状态。 */
public record AvailabilityResponse(
        boolean available,
        Long versionId,
        String resourceType,
        Long resourceId,
        int dayOfWeek,
        int periodIndex,
        OccupiedBy occupiedBy) {

    /** 占用该时段的课；空闲时外层的 occupiedBy 为 null。 */
    public record OccupiedBy(
            Long entryId,
            int dayOfWeek,
            int periodIndex,
            Long classId,
            String className,
            Long subjectId,
            String subjectName,
            Long teacherId,
            String teacherName) {
    }
}
