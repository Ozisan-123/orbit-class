package com.orbit.academic.grade;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 年级接口 —— {@code /api/grades/*}（4 个），对应 Python {@code routers/grades.py}。 */
@RestController
@RequestMapping("/api/grades")
public class GradeController {

    private final GradeService gradeService;

    public GradeController(GradeService gradeService) {
        this.gradeService = gradeService;
    }

    @GetMapping
    public PageResponse<GradeResponse> list(PageQuery query,
                                           @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeService.list(requirePrincipal(principal).organizationId(), query);
    }

    @PostMapping
    public GradeResponse create(@Valid @RequestBody GradeCreateRequest request,
                                @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeService.create(requirePrincipal(principal).organizationId(), request);
    }

    @PutMapping("/{gradeId}")
    public GradeResponse update(@PathVariable Long gradeId,
                                @Valid @RequestBody GradeUpdateRequest request,
                                @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeService.update(requirePrincipal(principal).organizationId(), gradeId, request);
    }

    @DeleteMapping("/{gradeId}")
    public Map<String, Object> delete(@PathVariable Long gradeId,
                                      @AuthenticationPrincipal OrbitUserPrincipal principal) {
        gradeService.delete(requirePrincipal(principal).organizationId(), gradeId);
        return Map.of("ok", true);
    }

    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
