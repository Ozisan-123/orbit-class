package com.orbit.academic.clazz;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 班级接口 —— {@code /api/classes/*}（4 个），对应 Python {@code routers/classes.py}。
 *
 * <p>{@code GET} 的 {@code grade_id} 是可选的过滤参数。参数名是 snake_case，
 * 所以显式写 {@code @RequestParam(name = "grade_id")} —— 靠字段名猜会**静默不绑定**（H2 那个坑）。
 */
@RestController
@RequestMapping("/api/classes")
public class ClassController {

    private final ClassService classService;

    public ClassController(ClassService classService) {
        this.classService = classService;
    }

    @GetMapping
    public PageResponse<ClassResponse> list(
            @RequestParam(name = "grade_id", required = false) Long gradeId,
            PageQuery query,
            @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return classService.list(requirePrincipal(principal).organizationId(), gradeId, query);
    }

    @PostMapping
    public ClassResponse create(@Valid @RequestBody ClassCreateRequest request,
                                @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return classService.create(requirePrincipal(principal).organizationId(), request);
    }

    @PutMapping("/{classId}")
    public ClassResponse update(@PathVariable Long classId,
                                @Valid @RequestBody ClassUpdateRequest request,
                                @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return classService.update(requirePrincipal(principal).organizationId(), classId, request);
    }

    @DeleteMapping("/{classId}")
    public Map<String, Object> delete(@PathVariable Long classId,
                                      @AuthenticationPrincipal OrbitUserPrincipal principal) {
        classService.delete(requirePrincipal(principal).organizationId(), classId);
        return Map.of("ok", true);
    }

    private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
