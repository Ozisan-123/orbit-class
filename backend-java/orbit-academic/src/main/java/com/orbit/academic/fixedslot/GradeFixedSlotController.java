package com.orbit.academic.fixedslot;

import com.orbit.academic.auth.OrbitUserPrincipal;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 年级固定课接口 —— {@code /api/fixed-slots/by-grade/*}（3 个）。
 *
 * <p>注意路径里带 {@code by-grade} 这一段：它是 Python 路由前缀的一部分，
 * 不能用 {@code @RequestMapping("/api/fixed-slots")} 图省事 —— 路径是契约。
 */
@RestController
@RequestMapping("/api/fixed-slots/by-grade")
public class GradeFixedSlotController {

    private final GradeFixedSlotService gradeFixedSlotService;

    public GradeFixedSlotController(GradeFixedSlotService gradeFixedSlotService) {
        this.gradeFixedSlotService = gradeFixedSlotService;
    }

    @GetMapping
    public PageResponse<GradeFixedSlotResponse> list(
            @RequestParam(name = "grade_id", required = false) Long gradeId,
            PageQuery query,
            @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeFixedSlotService.list(requirePrincipal(principal).organizationId(), gradeId, query);
    }

    @PostMapping
    public GradeFixedSlotResponse create(@Valid @RequestBody GradeFixedSlotCreateRequest request,
                                         @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeFixedSlotService.create(requirePrincipal(principal).organizationId(), request);
    }

    @DeleteMapping("/{slotId}")
    public Map<String, Object> delete(@PathVariable Long slotId,
                                      @AuthenticationPrincipal OrbitUserPrincipal principal) {
        gradeFixedSlotService.delete(requirePrincipal(principal).organizationId(), slotId);
        return Map.of("ok", true);
    }

    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
