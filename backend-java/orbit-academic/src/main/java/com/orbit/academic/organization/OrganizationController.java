package com.orbit.academic.organization;

import com.orbit.academic.auth.OrbitUserPrincipal;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作空间接口 —— {@code /api/organizations/*}（2 个），对应 Python 的
 * {@code routers/organizations.py}。
 *
 * <p><b>两个接口都要求平台管理员</b>（Python: {@code Depends(require_platform_admin)}）：
 * <ul>
 *   <li>非平台管理员 → 403 {@code 仅平台管理员可以管理工作空间}</li>
 *   <li>注意与「管理员」的区别：{@code require_admin} 的文案是
 *       {@code 仅管理员可以执行此操作}，两者不能混用</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    /** Python 的 require_platform_admin 文案，逐字一致。 */
    private static final String PLATFORM_ADMIN_ONLY = "仅平台管理员可以管理工作空间";

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    public PageResponse<OrganizationResponse> list(PageQuery query,
                                                  @AuthenticationPrincipal OrbitUserPrincipal principal) {
        requirePlatformAdmin(principal);
        return organizationService.list(query);
    }

    @PostMapping
    public OrganizationResponse create(@Valid @RequestBody OrganizationCreateRequest request,
                                       @AuthenticationPrincipal OrbitUserPrincipal principal) {
        requirePlatformAdmin(principal);
        return organizationService.create(request);
    }

    /**
     * 平台管理员校验。
     *
     * <p>为什么写在代码里而不是用 Spring 的 {@code hasRole("PLATFORM_ADMIN")}：
     * 用 URL 规则表达时，403 由 Spring 的 {@code AccessDeniedHandler} 统一生成，
     * 只能给一句通用文案；而 Python 为每种权限检查准备了**不同的**中文提示
     * （{@code 仅管理员...} / {@code 仅平台管理员...}），前端会直接显示它。
     * 写成显式检查才能逐字保住这些文案。
     */
    private static void requirePlatformAdmin(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        if (!principal.isPlatformAdmin()) {
            throw BusinessException.forbidden(PLATFORM_ADMIN_ONLY);
        }
    }
}
