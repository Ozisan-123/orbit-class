package com.orbit.academic.user;

import com.orbit.academic.auth.OrbitUserPrincipal;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口 —— {@code /api/users/*}（3 个），对应 Python {@code routers/users.py}。
 *
 * <p>三个接口都要求**管理员**（Python: {@code Depends(require_admin)}）：
 * 非管理员 → 403 {@code 仅管理员可以执行此操作}。
 * 注意与平台管理员的区别（{@code 仅平台管理员可以管理工作空间}）—— 两种文案不能混。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** Python 的 require_admin 文案，逐字一致。 */
    private static final String ADMIN_ONLY = "仅管理员可以执行此操作";

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<UserResponse> list(PageQuery query,
                                           @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return userService.list(requireAdmin(principal).organizationId(), query);
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody UserCreateRequest request,
                               @AuthenticationPrincipal OrbitUserPrincipal principal) {
        return userService.create(requireAdmin(principal).organizationId(), request);
    }

    @PutMapping("/{userId}")
    public UserResponse update(@PathVariable Long userId,
                               @Valid @RequestBody UserUpdateRequest request,
                               @AuthenticationPrincipal OrbitUserPrincipal principal) {
        OrbitUserPrincipal admin = requireAdmin(principal);
        return userService.update(admin.organizationId(), userId, admin.userId(), request);
    }

    private static OrbitUserPrincipal requireAdmin(OrbitUserPrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        if (!principal.isAdmin()) {
            throw BusinessException.forbidden(ADMIN_ONLY);
        }
        return principal;
    }
}
