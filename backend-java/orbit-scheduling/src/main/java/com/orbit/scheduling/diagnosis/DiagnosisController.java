package com.orbit.scheduling.diagnosis;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 诊断接口 —— {@code GET /api/schedule/diagnosis}。
 *
 * <h2>⚠ 路径与方案 §13.2 有一处**有意的差异**</h2>
 * 方案写的是 {@code GET /api/schedule/jobs/{id}/diagnosis}，并在同一行注明
 * 「原稿是无参 {@code GET /api/schedule/diagnosis}；诊断必然是「某一次排课」的，
 * 必须带 job/version」。
 *
 * <p>但 {@code schedule_jobs} 是 **P3 的表**，现在还不存在。诊断要"必须带 job"就得先有 job ——
 * 所以 P2 采用组织级路径（方案自己也说这是原稿形态），并在**落库时记下 {@code job_token}**
 * （那是当前唯一的"尝试身份"）。P3 引入 jobs 后：
 * <ul>
 *   <li>加一列 {@code job_id}（表已预留语义），接口演进为 {@code jobs/{id}/diagnosis}</li>
 *   <li>组织级路径**保留**作为"给我最近一次诊断"的便捷入口（前端首页要它）</li>
 * </ul>
 * 这样演进只**新增**路径，不破坏已有调用方。
 *
 * <h2>两个参数</h2>
 * <ul>
 *   <li>{@code deep=true} —— 做最小冲突集分析（要解几次，几十秒）。不传则只做静态检查。</li>
 *   <li>不带参数 —— 返回**最近一次落库的深诊断**；如果从没做过，就现算一次静态检查。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/schedule")
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    public DiagnosisController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    @GetMapping("/diagnosis")
    public DiagnosisResponse diagnosis(
            @RequestParam(name = "deep", required = false) Boolean deep,
            @RequestParam(name = "version_id", required = false) Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        long organizationId = principal.organizationId();

        if (Boolean.TRUE.equals(deep)) {
            // 显式要求重算 —— 不传 lastOutcome，因为"上一次求解的结果"已经从状态表丢失了；
            // 深诊断的判据是"去掉哪条规则能解出来"，不依赖上次的状态。
            return diagnosisService.diagnose(organizationId, null, versionId, true, null);
        }
        DiagnosisResponse latest = diagnosisService.latest(organizationId);
        return latest != null
                ? latest
                : diagnosisService.diagnose(organizationId, null, versionId, false, null);
    }
}
