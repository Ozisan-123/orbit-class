package com.orbit.academic.assignment;

import com.orbit.academic.auth.OrbitUserPrincipal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任教分配接口 —— {@code /api/assignments/*}（4 个），对应 Python {@code routers/assignments.py} 的前半。
 *
 * <p>⚠ {@code GET} 是**分页列表**（返回 {@code {items,total}}），
 * 而 {@code PUT /teacher/{id}} 返回的是**裸数组** ——
 * 因为它是写操作，返回的是"操作后的状态"，不属于 §9 的分页契约范围。
 * 两者形状不同是刻意的。
 */
@RestController
@RequestMapping("/api/assignments")
public class AssignmentController {

    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @GetMapping
    public com.orbit.common.dto.PageResponse<AssignmentResponse> list(
            @RequestParam(name = "teacher_id", required = false) Long teacherId,
            @RequestParam(name = "class_id", required = false) Long classId,
            com.orbit.common.dto.PageQuery query,
            @AuthenticationPrincipal OrbitUserPrincipal principal) {
        long organizationId = requirePrincipal(principal).organizationId();
        List<AssignmentResponse> all = assignmentService.list(organizationId, teacherId, classId);
        // 内存分页：这一层的过滤在 SQL 里已经做完，条数由业务规模决定
        // （一个学校最多几千条分配），不值得为它单独写一套分页 SQL。
        long total = all.size();
        int from = Math.min((int) ((long) (query.pageNumber() - 1) * query.pageSize()), all.size());
        int to = Math.min(from + query.pageSize(), all.size());
        return com.orbit.common.dto.PageResponse.of(all.subList(from, to), total);
    }

    @PostMapping
    public AssignmentResponse create(@Valid @RequestBody AssignmentCreateRequest request,
                                     @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return assignmentService.create(requirePrincipal(principal).organizationId(), request);
    }

    @DeleteMapping("/{assignmentId}")
    public Map<String, Object> delete(@PathVariable Long assignmentId,
                                      @AuthenticationPrincipal OrbitUserPrincipal principal) {
        assignmentService.delete(requirePrincipal(principal).organizationId(), assignmentId);
        return Map.of("ok", true);
    }

    /** 自动分配教师。请求体可省（两个字段都有默认值）—— 所以不加 {@code @Valid}。 */
    @PostMapping("/auto")
    public AssignmentAutoResponse autoAssign(
            @RequestBody(required = false) AssignmentAutoRequest request,
            @AuthenticationPrincipal OrbitUserPrincipal principal) {
        AssignmentAutoRequest effective = request == null
                ? new AssignmentAutoRequest(null, null) : request;
        return assignmentService.autoAssign(requirePrincipal(principal).organizationId(), effective);
    }

    /** 设置某教师负责的班级（覆盖式）。返回该教师操作后的分配列表（裸数组）。 */
    @PutMapping("/teacher/{teacherId}")
    public List<AssignmentResponse> setTeacherClasses(
            @PathVariable Long teacherId,
            @Valid @RequestBody TeacherClassAssignmentsRequest request,
            @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return assignmentService.setTeacherClasses(
                requirePrincipal(principal).organizationId(), teacherId, request);
    }

    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
