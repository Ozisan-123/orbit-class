package com.orbit.scheduling.rule;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.snapshot.ScheduleInputLoader;
import com.orbit.solver.core.OrbitSolver;
import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.engine.ConstructionInfeasible;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则试算 —— {@code POST /api/rules/trial}。
 *
 * <p><b>只编译、不求解</b>（选这个语义的理由写在 {@link RuleTrialResponse} 的类注释里）。
 *
 * <p>做法是把"当前规则集"和"当前规则集 + 这条草稿"各编译一次，然后比：
 * <ul>
 *   <li>能否编译（{@link ConstructionInfeasible} 会被抓住并转成用户能读的文案）</li>
 *   <li>模型指纹是否变化（不变 = 这条规则对当前数据没有影响）</li>
 *   <li>变量数增量（证明它确实进了模型）</li>
 * </ul>
 *
 * <p>⚠ 编译两次是有代价的（要建整个模型），但比求解便宜一个数量级，
 * 而且**不需要占容量槽位、不需要抢分布式锁** —— 这正是它能同步做的原因。
 */
@Service
public class RuleTrialService {

    private static final Logger log = LoggerFactory.getLogger(RuleTrialService.class);

    private final RuleSetService ruleSetService;
    private final RuleTypeCatalog catalog;
    private final ScheduleInputLoader inputLoader;

    public RuleTrialService(RuleSetService ruleSetService,
                            RuleTypeCatalog catalog,
                            ScheduleInputLoader inputLoader) {
        this.ruleSetService = ruleSetService;
        this.catalog = catalog;
        this.inputLoader = inputLoader;
    }

    public RuleTrialResponse trial(long organizationId, RuleCreateRequest request) {
        // ① 先做与保存时**完全相同**的校验 —— 试算通过但保存失败会很困惑
        catalog.handlerOf(request.ruleType());
        ruleSetService.rejectMandatory(request.ruleType());
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        ruleSetService.validateParams(request.ruleType(), params);

        String hardness = request.hardness() == null || request.hardness().isBlank()
                ? "hard" : request.hardness().trim().toLowerCase();
        if (!"hard".equals(hardness) && !"soft".equals(hardness)) {
            throw BusinessException.badRequest("hardness 只能是 hard 或 soft，实际为 " + request.hardness());
        }
        int weight = request.weight() == null || request.weight() < 1 ? 1 : request.weight();

        RuleParams ruleParams = RuleParams.of(params);
        String description = catalog.handlerOf(request.ruleType()).describe(ruleParams);

        // ② 人话描述先拿到 —— 即使编译失败也要能告诉用户"你想加的是这条"
        List<ConstraintRule> current = ruleSetService.loadForSolve(organizationId);
        List<ConstraintRule> withDraft = new ArrayList<>(current);
        withDraft.add(new ConstraintRule(request.ruleType(), ruleParams,
                "hard".equals(hardness) ? ConstraintRule.Hardness.HARD : ConstraintRule.Hardness.SOFT,
                weight, true, description));

        ScheduleInput input = inputLoader.load(organizationId);

        CompiledModel before;
        try {
            before = OrbitSolver.compile(input, current);
        } catch (ConstructionInfeasible e) {
            // ⚠ 当前规则集本身就编译不过 —— 那不是这条草稿的问题，如实说清楚
            log.warn("现有规则集无法编译 org={}: {}", organizationId, e.getMessage());
            throw BusinessException.badRequest(
                    "当前规则集本身就无法编译，请先修复现有规则：" + e.getMessage());
        }

        long variablesBefore = variablesOf(before.modelStats());
        try {
            CompiledModel after = OrbitSolver.compile(input, withDraft);
            long variablesAfter = variablesOf(after.modelStats());
            return new RuleTrialResponse(true, description, hardness, weight,
                    true, null,
                    !before.modelFingerprint().equals(after.modelFingerprint()),
                    new RuleTrialResponse.ModelDelta(variablesBefore, variablesAfter,
                            variablesAfter - variablesBefore),
                    RuleTrialResponse.LIMITATION_NOTE);
        } catch (ConstructionInfeasible e) {
            // 这是**最有价值的输出**：这条规则与现有数据/固定课存在确定性矛盾，
            // 现在就知道，不用等到真的发起排课才失败。
            return new RuleTrialResponse(true, description, hardness, weight,
                    false, e.getMessage(),
                    false,
                    new RuleTrialResponse.ModelDelta(variablesBefore, variablesBefore, 0),
                    RuleTrialResponse.LIMITATION_NOTE);
        }
    }

    /**
     * 从 {@code modelStats()} 里取变量数。
     *
     * <p>⚠ 这里**解析字符串**是因为 solver-core 只暴露了 {@code modelStats()} 文本
     * （那是 OR-Tools 的 {@code CpModel.toString()}）。{@code GoldenModelTest} 也是这么取的，
     * 所以解析方式与既有测试一致 —— 但要知道它绑定了 OR-Tools 的输出格式，
     * 升级 OR-Tools 时这里会一起红（那样正好被看见）。
     */
    static long variablesOf(String modelStats) {
        if (modelStats == null) {
            return 0;
        }
        int at = modelStats.indexOf("#Variables:");
        if (at < 0) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : modelStats.substring(at + "#Variables:".length()).toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c == '\'') {
                continue;               // 千分位分隔符，如 9'676
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
    }
}
