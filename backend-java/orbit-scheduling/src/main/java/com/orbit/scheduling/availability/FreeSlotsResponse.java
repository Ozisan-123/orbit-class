package com.orbit.scheduling.availability;

import java.util.List;

/** 教师某一天的全部空闲节次，以及已有占用的可核对明细。 */
public record FreeSlotsResponse(
        Long versionId,
        Long teacherId,
        int dayOfWeek,
        int periodsInDay,
        List<Integer> freePeriods,
        List<AvailabilityResponse.OccupiedBy> occupiedBy) {
}
