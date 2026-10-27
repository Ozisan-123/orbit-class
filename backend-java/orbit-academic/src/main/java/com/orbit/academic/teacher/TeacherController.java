package com.orbit.academic.teacher;

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

/** 教师接口 —— {@code /api/teachers/*}（4 个），对应 Python {@code routers/teachers.py}。 */
@RestController
@RequestMapping("/api/teachers")
public class TeacherController {

    private final TeacherService teacherService;

    public TeacherController(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @GetMapping
    public PageResponse<TeacherResponse> list(PageQuery query,
                                              @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return teacherService.list(requirePrincipal(principal).organizationId(), query);
    }

    @PostMapping
    public TeacherResponse create(@Valid @RequestBody TeacherCreateRequest request,
                                  @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return teacherService.create(requirePrincipal(principal).organizationId(), request);
    }

    @PutMapping("/{teacherId}")
    public TeacherResponse update(@PathVariable Long teacherId,
                                  @Valid @RequestBody TeacherUpdateRequest request,
                                  @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return teacherService.update(requirePrincipal(principal).organizationId(), teacherId, request);
    }

    @DeleteMapping("/{teacherId}")
    public Map<String, Object> delete(@PathVariable Long teacherId,
                                      @AuthenticationPrincipal OrbitUserPrincipal principal) {
        teacherService.delete(requirePrincipal(principal).organizationId(), teacherId);
        return Map.of("ok", true);
    }

    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
