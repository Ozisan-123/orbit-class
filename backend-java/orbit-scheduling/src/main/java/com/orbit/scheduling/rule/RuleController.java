package com.orbit.scheduling.rule;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则接口 —— {@code /api/rules/*}（P2 新增，Python 侧**没有对应物**）。
 *
 * <p>为什么 P2 之前 Python 没有这些接口：约束是**硬编码在 {@code engine.py} 里**的，
 * 用户改不了。P2 做的事就是把它们变成数据。
 *
 * <p>⚠ 因此这一组接口**无法用影子对比与 Python 比对** —— 判据换成了两条可执行的事实：
 * <ol>
 *   <li>用 DB 规则集（默认初始化出来的那两条）排课，结果与用
 *       {@code RuleSets.legacyEquivalent()} 排课**目标值相同**（P1 的判据继续用）</li>
 *   <li>规则的行为由 solver-core 的反例注入测试守（每条规则配灵敏度反证）</li>
 * </ol>
 *
 * <h2>权限</h2>
 * 全部要求**管理员**（与 {@code /api/system/*} 一致）：规则会改变全校的排课结果，
 * operator 不该动。文案复用同一句 {@code 仅管理员可以执行此操作}。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private static final String ADMIN_ONLY = "仅管理员可以执行此操作";

    private final RuleService ruleService;
    private final RuleSetService ruleSetService;
    private final RuleTypeCatalog catalog;
    private final RuleTrialService trialService;

    public RuleController(RuleService ruleService,
                          RuleSetService ruleSetService,
                          RuleTypeCatalog catalog,
                          RuleTrialService trialService) {
        this.ruleService = ruleService;
        this.ruleSetService = ruleSetService;
        this.catalog = catalog;
        this.trialService = trialService;
    }

    /**
     * 规则类型清单（能力清单）。
     *
     * <p>{@code ?configurable_only=true} 只返回可配置类型 —— 前端"新建规则"下拉用这个；
     * 不带参数则返回全部（含不变量），给"系统规则"说明区用。
     */
    @GetMapping("/types")
    public List<RuleTypeResponse> types(
            @RequestParam(name = "configurable_only", required = false) Boolean configurableOnly,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        requireAdmin(principal);
        return Boolean.TRUE.equals(configurableOnly) ? catalog.configurable() : catalog.all();
    }

    @GetMapping
    public List<RuleResponse> list(@AuthenticationPrincipal SchedulePrincipal principal) {
        return ruleService.list(requireAdmin(principal).organizationId());
    }

    /**
     * ⚠ 路由顺序有讲究：{@code /versions} 必须声明在 {@code /{ruleId}} **之前**，
     * 否则 {@code /versions} 会被当成 {@code ruleId="versions"} 匹配到详情接口上
     * （Spring 的模式匹配会挑更具体的那条，但把静态段放在前面是更稳的写法）。
     */
    @GetMapping("/versions")
    public List<RuleVersionResponse> versions(@AuthenticationPrincipal SchedulePrincipal principal) {
        long orgId = requireAdmin(principal).organizationId();
        ruleSetService.ensureInitialized(orgId);
        return ruleSetService.listVersions(orgId).stream().map(RuleVersionResponse::summary).toList();
    }

    @GetMapping("/versions/{versionId}")
    public RuleVersionResponse version(@PathVariable Long versionId,
                                       @AuthenticationPrincipal SchedulePrincipal principal) {
        long orgId = requireAdmin(principal).organizationId();
        ruleSetService.ensureInitialized(orgId);
        return RuleVersionResponse.detail(ruleSetService.requireVersion(orgId, versionId));
    }

    /** 当前生效的规则集版本号与哈希（前端在"这版课表用了哪套规则"处显示）。 */
    @GetMapping("/current")
    public Map<String, Object> current(@AuthenticationPrincipal SchedulePrincipal principal) {
        long orgId = requireAdmin(principal).organizationId();
        ruleSetService.ensureInitialized(orgId);
        ConstraintRuleVersionEntity version = ruleSetService.currentVersion(orgId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version_no", version == null ? null : version.getVersionNo());
        out.put("rule_set_hash", version == null ? null : version.getRuleSetHash());
        out.put("rule_count", version == null ? 0 : version.getRuleCount());
        return out;
    }

    @PostMapping("/trial")
    public RuleTrialResponse trial(@RequestBody RuleCreateRequest request,
                                   @AuthenticationPrincipal SchedulePrincipal principal) {
        return trialService.trial(requireAdmin(principal).organizationId(), request);
    }

    @PostMapping
    public RuleResponse create(@RequestBody @jakarta.validation.Valid RuleCreateRequest request,
                               @AuthenticationPrincipal SchedulePrincipal principal) {
        return ruleService.create(requireAdmin(principal).organizationId(), request);
    }

    @GetMapping("/{ruleId}")
    public RuleResponse get(@PathVariable Long ruleId,
                            @AuthenticationPrincipal SchedulePrincipal principal) {
        return ruleService.get(requireAdmin(principal).organizationId(), ruleId);
    }

    @PutMapping("/{ruleId}")
    public RuleResponse update(@PathVariable Long ruleId,
                               @RequestBody @jakarta.validation.Valid RuleUpdateRequest request,
                               @AuthenticationPrincipal SchedulePrincipal principal) {
        return ruleService.update(requireAdmin(principal).organizationId(), ruleId, request);
    }

    /** 启停开关（列表上直接切换，不弹表单）。 */
    @PatchMapping("/{ruleId}")
    public RuleResponse setEnabled(@PathVariable Long ruleId,
                                   @RequestBody @jakarta.validation.Valid RulePatchRequest request,
                                   @AuthenticationPrincipal SchedulePrincipal principal) {
        return ruleService.setEnabled(requireAdmin(principal).organizationId(), ruleId, request);
    }

    /**
     * 删除。
     *
     * <p>⚠ {@code expected_revision} 走**查询参数**而不是 body：DELETE 带 body 在
     * 各种客户端/代理上行为不一致。它是**必填**的（不是可选），所以不传会得到
     * 422 —— 与 {@code /api/system/storage/schedules?confirm=} 的做法一致。
     */
    @DeleteMapping("/{ruleId}")
    public Map<String, Object> delete(@PathVariable Long ruleId,
                                      @RequestParam(name = "expected_revision") Integer expectedRevision,
                                      @AuthenticationPrincipal SchedulePrincipal principal) {
        ruleService.delete(requireAdmin(principal).organizationId(), ruleId, expectedRevision);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
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
