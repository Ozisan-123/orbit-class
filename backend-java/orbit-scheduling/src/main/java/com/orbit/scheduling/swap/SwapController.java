package com.orbit.scheduling.swap;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import com.orbit.scheduling.version.VersionResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 拖动/换课接口 —— {@code POST /api/schedule/versions/{id}/swap}。
 *
 * <p>路径前缀与 {@code VersionController} 相同（{@code /api/schedule/versions}），
 * 但拆成两个 Controller 更清楚：版本管理是 CRUD，换课是一套独立的规则引擎
 * （固定课保护 + 跨班协同 + 整体重校验）。**路径本身仍是契约**，拆分只是代码组织。
 */
@RestController
@RequestMapping("/api/schedule/versions")
public class SwapController {

    private final SwapService swapService;

    public SwapController(SwapService swapService) {
        this.swapService = swapService;
    }

    @PostMapping("/{versionId}/swap/preview")
    public SwapPreviewResponse preview(@PathVariable Long versionId,
                                       @Valid @RequestBody SwapRequest request,
                                       @AuthenticationPrincipal SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return swapService.preview(principal.organizationId(), principal, versionId, request);
    }

    @PostMapping("/{versionId}/swap")
    public VersionResponse swap(@PathVariable Long versionId,
                                @Valid @RequestBody SwapRequest request,
                                @AuthenticationPrincipal SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return swapService.swap(principal.organizationId(), principal, versionId, request);
    }
}
