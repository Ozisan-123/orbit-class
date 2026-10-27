package com.orbit.scheduling.rule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.GradeRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.snapshot.TeacherRef;
import com.orbit.solver.core.rule.ConstraintRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则 CRUD —— {@code /api/rules/*}（P2 的对外主体）。
 *
 * <h2>四道校验，顺序有意义</h2>
 * <ol>
 *   <li><b>类型存在</b> → 400 {@code 未知的规则类型: x}（防止写进一条永远不生效的行）</li>
 *   <li><b>不是不变量</b> → 400（不变量始终生效，写成数据行只会让人以为能关掉）</li>
 *   <li><b>参数合法</b> → 400（由 solver-core 的 {@code RuleParamSpec} 判定，不在这里另写一套）</li>
 *   <li><b>乐观锁</b> → 409（编辑/启停/删除都要求 {@code expected_revision}）</li>
 * </ol>
 *
 * <h2>每次变更都记一个规则集版本</h2>
 * 因为审计链要求「这版课表用的哪套规则」可追溯。记版本是**哈希去重**的：
 * 把规则改回原样不会产生新版本（{@link RuleSetService#recordVersion}）。
 */
@Service
public class RuleService {

    private final ConstraintRuleMapper ruleMapper;
    private final RuleSetService ruleSetService;
    private final RuleTypeCatalog catalog;
    private final SnapshotMappers.GradeRefMapper gradeMapper;
    private final SnapshotMappers.ClassRefMapper classMapper;
    private final SnapshotMappers.TeacherRefMapper teacherMapper;
    private final SnapshotMappers.SubjectRefMapper subjectMapper;

    public RuleService(ConstraintRuleMapper ruleMapper,
                       RuleSetService ruleSetService,
                       RuleTypeCatalog catalog,
                       SnapshotMappers.GradeRefMapper gradeMapper,
                       SnapshotMappers.ClassRefMapper classMapper,
                       SnapshotMappers.TeacherRefMapper teacherMapper,
                       SnapshotMappers.SubjectRefMapper subjectMapper) {
        this.ruleMapper = ruleMapper;
        this.ruleSetService = ruleSetService;
        this.catalog = catalog;
        this.gradeMapper = gradeMapper;
        this.classMapper = classMapper;
        this.teacherMapper = teacherMapper;
        this.subjectMapper = subjectMapper;
    }

    // ------------------------------------------------------------------ 读

    /** 规则列表。**会顺带完成首次初始化**，所以新组织第一次打开就能看到默认规则。 */
    public List<RuleResponse> list(long organizationId) {
        ruleSetService.ensureInitialized(organizationId);
        return ruleSetService.listAll(organizationId).stream().map(this::toResponse).toList();
    }

    public RuleResponse get(long organizationId, long id) {
        return toResponse(requireOwned(organizationId, id));
    }

    // ------------------------------------------------------------------ 写

    @Transactional
    public RuleResponse create(long organizationId, RuleCreateRequest request) {
        catalog.handlerOf(request.ruleType());          // ① 未知类型 → 400
        ruleSetService.rejectMandatory(request.ruleType());   // ② 不变量 → 400
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        ruleSetService.validateParams(request.ruleType(), params);   // ③ 参数 → 400

        String hardness = hardnessOf(request.hardness(), "hard");
        String scope = scopeOf(request.scope());
        requireOrgScope(scope);

        ConstraintRuleEntity entity = new ConstraintRuleEntity();
        entity.setOrganizationId(organizationId);
        entity.setRuleType(request.ruleType());
        entity.setParams(new LinkedHashMap<>(params));
        entity.setHardness(hardness);
        entity.setWeight("soft".equals(hardness)
                ? positive(request.weight()) : 1);
        entity.setScope(scope);
        entity.setScopeId(request.scopeId());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setDescription(descriptionOf(entity, request.description()));
        entity.setSource("manual");
        entity.setVersion(1);
        entity.setCreatedAt(UtcTimes.now());
        entity.setUpdatedAt(UtcTimes.now());
        ruleMapper.insert(entity);

        ruleSetService.recordVersion(organizationId,
                "新建规则：" + entity.getDescription());
        return toResponse(entity);
    }

    @Transactional
    public RuleResponse update(long organizationId, long id, RuleUpdateRequest request) {
        ConstraintRuleEntity entity = requireOwned(organizationId, id);
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        ruleSetService.validateParams(entity.getRuleType(), params);

        String hardness = request.hardness() == null
                ? entity.getHardness() : hardnessOf(request.hardness(), entity.getHardness());

        entity.setParams(new LinkedHashMap<>(params));
        entity.setHardness(hardness);
        entity.setWeight("soft".equals(hardness)
                ? positive(request.weight() == null ? entity.getWeight() : request.weight())
                : 1);
        entity.setDescription(request.description() == null
                ? entity.getDescription() : request.description());
        entity.setUpdatedAt(UtcTimes.now());

        int affected = ruleMapper.updateWithRevision(entity, request.expectedRevision());
        if (affected != 1) {
            throw BusinessException.conflict("该规则刚被其他用户修改，请刷新后重试");
        }
        // ⚠ 必须**重新读一遍**：version 是 SQL 里 `version + 1` 出来的，
        //   内存里这个 entity 还停在旧值 —— 直接拿它拼响应会返回**过期的 revision**，
        //   前端再拿这个值提交就会 409（实测踩到过）。
        ConstraintRuleEntity updated = requireOwned(organizationId, id);
        ruleSetService.recordVersion(organizationId, "编辑规则：" + updated.getDescription());
        return toResponse(updated);
    }

    @Transactional
    public RuleResponse setEnabled(long organizationId, long id, RulePatchRequest request) {
        requireOwned(organizationId, id);
        int affected = ruleMapper.updateEnabledWithRevision(
                id, organizationId, request.enabled(), UtcTimes.now(), request.expectedRevision());
        if (affected != 1) {
            throw BusinessException.conflict("该规则刚被其他用户修改，请刷新后重试");
        }
        ConstraintRuleEntity updated = requireOwned(organizationId, id);
        ruleSetService.recordVersion(organizationId,
                (request.enabled() ? "启用规则：" : "停用规则：") + updated.getDescription());
        return toResponse(updated);
    }

    @Transactional
    public void delete(long organizationId, long id, int expectedRevision) {
        ConstraintRuleEntity entity = requireOwned(organizationId, id);
        int affected = ruleMapper.deleteWithRevision(id, organizationId, expectedRevision);
        if (affected != 1) {
            throw BusinessException.conflict("该规则刚被其他用户修改，请刷新后重试");
        }
        ruleSetService.recordVersion(organizationId, "删除规则：" + entity.getDescription());
    }

    // ------------------------------------------------------------------ 辅助

    private ConstraintRuleEntity requireOwned(long organizationId, long id) {
        ConstraintRuleEntity found = ruleMapper.selectOne(Wrappers.<ConstraintRuleEntity>lambdaQuery()
                .eq(ConstraintRuleEntity::getId, id)
                .eq(ConstraintRuleEntity::getOrganizationId, organizationId));
        if (found == null) {
            throw BusinessException.notFound("规则不存在");
        }
        return found;
    }

    private RuleResponse toResponse(ConstraintRuleEntity entity) {
        String handlerName = catalog.isKnown(entity.getRuleType())
                ? catalog.handlerOf(entity.getRuleType()).displayName()
                : entity.getRuleType();
        return RuleResponse.of(entity, handlerName,
                scopeName(entity.getScope(), entity.getScopeId()),
                descriptionOf(entity, entity.getDescription()));
    }

    /**
     * 人话描述：优先用行上存的，没有就现场问 handler。
     *
     * <p>{@code describe()} 会用**当前参数**生成说明，所以即使没人填 description，
     * 列表里也不会出现一行只有类型名的规则。
     */
    private String descriptionOf(ConstraintRuleEntity entity, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (!catalog.isKnown(entity.getRuleType())) {
            return entity.getRuleType();
        }
        return catalog.handlerOf(entity.getRuleType())
                .describe(com.orbit.solver.core.rule.RuleParams.of(entity.getParams()));
    }

    /** {@code scope_name}："三年级"而不是 {@code 3}（§13.2 的硬要求）。 */
    private String scopeName(String scope, Long scopeId) {
        if (scope == null || "org".equals(scope)) {
            return "全校";
        }
        if (scopeId == null) {
            return scope;
        }
        return switch (scope) {
            case "grade" -> {
                GradeRef g = gradeMapper.selectById(scopeId);
                yield g == null ? ("年级" + scopeId) : g.getName();
            }
            case "class" -> {
                ClassRef c = classMapper.selectById(scopeId);
                yield c == null ? ("班级" + scopeId) : c.getName();
            }
            case "teacher" -> {
                TeacherRef t = teacherMapper.selectById(scopeId);
                yield t == null ? ("教师" + scopeId) : t.getName();
            }
            case "subject" -> {
                var s = subjectMapper.selectById(scopeId);
                yield s == null ? ("科目" + scopeId) : s.getName();
            }
            default -> scope + scopeId;
        };
    }

    private static String hardnessOf(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase();
        if (!"hard".equals(value) && !"soft".equals(value)) {
            throw BusinessException.badRequest("hardness 只能是 hard 或 soft，实际为 " + raw);
        }
        return value;
    }

    private static String scopeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "org";
        }
        return raw.trim().toLowerCase();
    }

    /**
     * P2 只放开 {@code org} 作用域。
     *
     * <p>结构上表已经支持 grade/class/teacher/subject，但求解只用 org 级规则 ——
     * 细粒度作用域的语义（同一条规则在多个 scope 上如何叠加、冲突时谁赢）还没定，
     * **宁可明确拒绝，也不要接受一种自己都解释不清的输入**。
     */
    private static void requireOrgScope(String scope) {
        if (!"org".equals(scope)) {
            throw BusinessException.badRequest(
                    "本版本仅支持全校（org）作用域的规则，暂不支持 " + scope);
        }
    }

    private static int positive(Integer weight) {
        return weight == null || weight < 1 ? 1 : weight;
    }
}
