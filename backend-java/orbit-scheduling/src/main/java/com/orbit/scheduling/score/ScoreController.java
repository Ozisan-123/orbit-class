package com.orbit.scheduling.score;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedule/versions")
public class ScoreController {

    private final ScoreService scoreService;

    public ScoreController(ScoreService scoreService) {
        this.scoreService = scoreService;
    }

    @GetMapping("/{versionId}/score")
    public ScoreResponse score(@PathVariable long versionId,
                               @AuthenticationPrincipal SchedulePrincipal principal) {
        return scoreService.score(requirePrincipal(principal).organizationId(), versionId);
    }

    @GetMapping("/{versionId}/score/diff")
    public ScoreDiffResponse diff(@PathVariable long versionId,
                                  @RequestParam long against,
                                  @AuthenticationPrincipal SchedulePrincipal principal) {
        return scoreService.diff(requirePrincipal(principal).organizationId(), versionId, against);
    }

    private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
