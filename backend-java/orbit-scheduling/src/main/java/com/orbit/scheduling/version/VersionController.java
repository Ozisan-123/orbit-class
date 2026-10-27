package com.orbit.scheduling.version;

import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.audit.AuditResponse;
import com.orbit.scheduling.security.SchedulePrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课表版本接口 —— {@code /api/schedule/versions/*}（5 个）。
 *
 * <p>⚠ 路径前缀是 {@code /api/schedule}（与 generate/status 同属一个 Python 路由），
 * 但 Java 里拆成了两个 Controller：本类负责 versions，{@code ScheduleController}
 * 负责 generate/status。**路径本身仍是契约**，拆分只是代码组织。
 */
@RestController
@RequestMapping("/api/schedule/versions")
public class VersionController {

    private final VersionService versionService;

    public VersionController(VersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping
    public PageResponse<VersionResponse> list(PageQuery query,
                                              @AuthenticationPrincipal SchedulePrincipal principal) {
        return versionService.list(requirePrincipal(principal).organizationId(), query);
    }

    @PatchMapping("/{versionId}")
    public VersionResponse update(@PathVariable Long versionId,
                                  @Valid @RequestBody VersionUpdateRequest request,
                                  @AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requirePrincipal(principal);
        return versionService.update(user.organizationId(), user, versionId, request);
    }

    @PostMapping("/{versionId}/copy")
    public VersionResponse copy(@PathVariable Long versionId,
                                @AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requirePrincipal(principal);
        return versionService.copy(user.organizationId(), user, versionId);
    }

    @PostMapping("/{versionId}/publish")
    public VersionResponse publish(@PathVariable Long versionId,
                                   @AuthenticationPrincipal SchedulePrincipal principal) {
        SchedulePrincipal user = requirePrincipal(principal);
        return versionService.publish(user.organizationId(), user, versionId);
    }

    @GetMapping("/{versionId}/audit")
    public List<AuditResponse> audit(@PathVariable Long versionId,
                                     @AuthenticationPrincipal SchedulePrincipal principal) {
        return versionService.audit(requirePrincipal(principal).organizationId(), versionId);
    }

    private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
