package com.orbit.academic.subject;

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

import java.util.List;
import java.util.Map;

/**
 * 年级课时接口 —— {@code /api/grade-subjects/*}（4 个）。
 *
 * <p>形状上有意不一致（都对齐 Python）：
 * <ul>
 *   <li>{@code GET} 是分页列表 → {@code {items,total}}（§9 的分页契约）</li>
 *   <li>{@code POST} 返回单条对象</li>
 *   <li>{@code POST /bulk} 返回**裸数组**（写操作返回"操作后的状态"，不分页）</li>
 *   <li>{@code DELETE} 返回 {@code {"ok":true}}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/grade-subjects")
public class GradeSubjectController {

    private final GradeSubjectService gradeSubjectService;

    public GradeSubjectController(GradeSubjectService gradeSubjectService) {
        this.gradeSubjectService = gradeSubjectService;
    }

    @GetMapping
    public PageResponse<GradeSubjectResponse> list(
            @RequestParam(name = "grade_id", required = false) Long gradeId,
            PageQuery query,
            @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeSubjectService.list(requirePrincipal(principal).organizationId(), gradeId, query);
    }

    @PostMapping
    public GradeSubjectResponse upsert(@Valid @RequestBody GradeSubjectCreateRequest request,
                                       @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeSubjectService.upsert(requirePrincipal(principal).organizationId(), request);
    }

    @PostMapping("/bulk")
    public List<GradeSubjectResponse> bulk(@Valid @RequestBody GradeSubjectBulkRequest request,
                                           @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return gradeSubjectService.bulkUpdate(requirePrincipal(principal).organizationId(), request);
    }

    @DeleteMapping("/{gradeSubjectId}")
    public Map<String, Object> delete(@PathVariable Long gradeSubjectId,
                                      @AuthenticationPrincipal OrbitUserPrincipal principal) {
        gradeSubjectService.delete(requirePrincipal(principal).organizationId(), gradeSubjectId);
        return Map.of("ok", true);
    }

    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
