package com.orbit.scheduling.system;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存储运维接口 —— {@code /api/system/*}（3 个）。
 *
 * <p>三个都要求**管理员**（Python: {@code Depends(require_admin)}）：
 * 非管理员 → 403 {@code 仅管理员可以执行此操作}。
 * 另有一处更强的限制在 {@code compact} 里（要求平台管理员），文案不同，别混。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /** Python 的 require_admin 文案，逐字一致。 */
    private static final String ADMIN_ONLY = "仅管理员可以执行此操作";

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @GetMapping("/storage")
    public Map<String, Object> storage(@AuthenticationPrincipal SchedulePrincipal principal) {
        return systemService.storage(requireAdmin(principal).organizationId());
    }

    /**
     * 清空已生成的课表。
     *
     * <p>{@code confirm} 是**必填的查询参数**，且必须等于 {@code CLEAR_SCHEDULES} ——
     * 一个防误触的门槛。两种情况的状态码**不同**，别合并：
     * <ul>
     *   <li>参数**没传** → 422（FastAPI 对必填查询参数的行为，见 {@code ApiExceptionHandler}）</li>
     *   <li>传了但**值不对** → 400 {@code 缺少安全确认参数}</li>
     * </ul>
     * 所以这里**不能**写 {@code required = false} 再自己判空 —— 那样两种情况都会变成 400。
     */
    @DeleteMapping("/storage/schedules")
    public Map<String, Object> clearSchedules(
            @RequestParam(name = "confirm") String confirm,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requireAdmin(principal);
        if (!SystemService.CLEAR_CONFIRM_VALUE.equals(confirm)) {
            throw BusinessException.badRequest("缺少安全确认参数");
        }
        return systemService.clearSchedules(user.organizationId());
    }

    @PostMapping("/storage/compact")
    public Map<String, Object> compact(@AuthenticationPrincipal SchedulePrincipal principal) {
        return systemService.compact(requireAdmin(principal));
    }

    private static SchedulePrincipal requireAdmin(SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        if (!principal.isAdmin()) {
            throw BusinessException.forbidden(ADMIN_ONLY);
        }
        return principal;
    }
}
