package com.orbit.scheduling.availability;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** P5 空闲查询接口。version_id 必填，因为“空闲”必须相对于某一版课表。 */
@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/teacher/{teacherId}")
    public AvailabilityResponse teacher(
            @PathVariable Long teacherId,
            @RequestParam Integer day,
            @RequestParam Integer period,
            @RequestParam(name = "version_id") Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requirePrincipal(principal);
        return availabilityService.teacher(user.organizationId(), teacherId,
                versionId, day, period);
    }

    @GetMapping("/class/{classId}")
    public AvailabilityResponse schoolClass(
            @PathVariable Long classId,
            @RequestParam Integer day,
            @RequestParam Integer period,
            @RequestParam(name = "version_id") Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requirePrincipal(principal);
        return availabilityService.schoolClass(user.organizationId(), classId,
                versionId, day, period);
    }

    @GetMapping("/teacher/{teacherId}/free-slots")
    public FreeSlotsResponse teacherFreeSlots(
            @PathVariable Long teacherId,
            @RequestParam Integer day,
            @RequestParam(name = "version_id") Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requirePrincipal(principal);
        return availabilityService.teacherFreeSlots(user.organizationId(), teacherId,
                versionId, day);
    }

    private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
