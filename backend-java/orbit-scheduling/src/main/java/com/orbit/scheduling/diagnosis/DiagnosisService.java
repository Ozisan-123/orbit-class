package com.orbit.scheduling.diagnosis;

import com.google.ortools.sat.CpSolverStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.rule.ConstraintRuleEntity;
import com.orbit.scheduling.rule.RuleSetService;
import com.orbit.scheduling.schedule.TimetableGenerationService;
import com.orbit.scheduling.snapshot.ScheduleInputLoader;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.model.ScheduleInput;
import com.orbit.solver.core.rule.ConstraintRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可行性诊断 —— 方案 §6.4 的落地。
 *
 * <h2>两段式：先静态（毫秒），后深诊断（几十秒）</h2>
 * <ol>
 *   <li><b>静态检查</b>（{@link StaticDiagnostics}）：不求解就能判定"一定排不出来"的四类数据问题。
 *       这是**默认**行为 —— 因为它快、而且结论是确定的。</li>
 *   <li><b>深诊断</b>（{@code ?deep=true}）：把每条可配置的硬规则**逐条去掉**再求解，
 *       去掉哪条之后能排出来，那条就是冲突的来源。这才是"最小冲突集"。</li>
 * </ol>
 *
 * <h2>为什么"逐条去掉"而不是方案原稿的 {@code onlyEnforceIf} 松弛</h2>
 * 方案 §6.4 写的是「硬约束改为 {@code onlyEnforceIf(flag)} 可开关约束，最小化违反加权和」。
 * 那需要改动 solver-core 里**每一条 handler 的建模方式**（都要接受一个 flag），
 * 而这里用了一个**不改内核**的等价做法：
 * <pre>
 *   对每条硬规则 R：把 R 从规则集里去掉，重新求解一次
 *     → 能解出 → R 属于最小冲突集
 *     → 仍无解 → R 不是（唯一/主要）原因
 * </pre>
 * 代价是要解 K 次（K = 硬规则条数），但：
 * <ul>
 *   <li>K 通常很小（真实场景下用户配的硬规则一般 ≤ 5 条），而且**上限被 {@code MAX_TRIES} 卡住**</li>
 *   <li>每次求解**只用 {@code deep-time-limit} 秒**（默认 8 秒），不是排课时的 60 秒 ——
 *       诊断只需要知道"有没有解"，不需要找到最优解</li>
 *   <li>**不变量去不掉**（{@code teacher_no_conflict} / 周课时 / 教师上限始终生效），
 *       所以如果冲突来自不变量，这一轮会一无所获 —— 那时如实报告，而不是编一个原因</li>
 * </ul>
 *
 * <h2>落库策略</h2>
 * **只在深诊断时落库**。浅诊断（静态）是毫秒级的，每次刷新重算完全没问题；
 * 深诊断要几十秒，那才是 §5.2 说的"每次前端刷新都重算不可接受"。
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    /** 深诊断最多尝试几条硬规则 —— 卡住总耗时上界（× 每次的 deep-time-limit 秒）。 */
    private static final int MAX_TRIES = 5;

    private final StaticDiagnostics staticDiagnostics;
    private final ScheduleDiagnosisMapper diagnosisMapper;
    private final RuleSetService ruleSetService;
    private final ScheduleInputLoader inputLoader;
    private final TimetableGenerationService generationService;
    private final int deepTimeLimitSeconds;

    public DiagnosisService(StaticDiagnostics staticDiagnostics,
                            ScheduleDiagnosisMapper diagnosisMapper,
                            RuleSetService ruleSetService,
                            ScheduleInputLoader inputLoader,
                            TimetableGenerationService generationService,
                            @Value("${orbit.scheduling.diagnosis-time-limit-seconds:8}") int deepTimeLimitSeconds) {
        this.staticDiagnostics = staticDiagnostics;
        this.diagnosisMapper = diagnosisMapper;
        this.ruleSetService = ruleSetService;
        this.inputLoader = inputLoader;
        this.generationService = generationService;
        this.deepTimeLimitSeconds = Math.max(1, deepTimeLimitSeconds);
    }

    /**
     * 跑一次诊断。
     *
     * @param lastOutcome 最近一次求解的结果；为 null 表示"还没排过课，只想先体检一下"
     * @param deep        是否做最小冲突集分析
     */
    public DiagnosisResponse diagnose(long organizationId, String jobToken, Long versionId,
                                      boolean deep, SolveOutcome lastOutcome) {
        return diagnoseWithStatus(organizationId, jobToken, versionId, deep,
                lastOutcome == null ? null : lastOutcome.status().name());
    }

    /**
     * 用**求解状态字符串**跑诊断 —— P3 的任务路径用它。
     *
     * <p>为什么需要它：{@code schedule_jobs.solver_status} 存的就是字符串
     * （{@code OPTIMAL}/{@code FEASIBLE}/{@code INFEASIBLE}/{@code UNKNOWN}），
     * 而任务失败时手里已经没有 {@code SolveOutcome} 对象了。
     *
     * <p>⚠ 不这么做的后果很具体：排课因为**规则太紧**而失败、静态检查又没发现问题时，
     * 诊断会退回 "kind=OK" —— 一个明明失败了的任务，诊断却说一切正常。
     *
     * <p>⚠ 刻意**不做成 {@link #diagnose} 的重载**：那个方法的第四个参数可以是 null，
     * 而 `diagnose(org, token, null, false, null)` 在有两个重载时**无法确定调用哪个**
     * （编译期就报"对 diagnose 的引用不明确"，实测踩到过）。
     * 参数里允许 null 的时候，名字比重载清楚。
     *
     * @param solverStatus 见上；null 表示"没有求解信息，只看静态检查"
     */
    public DiagnosisResponse diagnoseWithStatus(long organizationId, String jobToken, Long versionId,
                                                boolean deep, String solverStatus) {
        List<DiagnosisFinding> staticFindings = staticDiagnostics.run(organizationId);
        String kind = determineKind(staticFindings, solverStatus);
        String ruleSetHash = ruleSetService.hashOf(organizationId);

        List<DiagnosisResponse.ConflictRule> conflictRules = List.of();
        if (deep) {
            conflictRules = findConflictRules(organizationId, solverStatus);
            if (!conflictRules.isEmpty()) {
                kind = DiagnosisResponse.KIND_INFEASIBLE;
            }
        }

        // ⚠ severity 必须在 kind **定稿之后**才算。
        //   最初把它写在深诊断之前，于是"深诊断才发现不可行"的那次调用会返回
        //   kind=INFEASIBLE 却 severity=info（两个字段自相矛盾，实测踩到过）。
        String severity = highestSeverity(staticFindings, kind);
        String note = note(staticFindings, conflictRules, kind, deep);
        DiagnosisResponse response = new DiagnosisResponse(kind, severity, versionId, jobToken,
                ruleSetHash, staticFindings, conflictRules, deep, note, UtcTimes.now());

        // ⚠ 落库条件：深诊断（贵）**或**确实有问题（需要留痕）。
        //    "一切正常"的浅诊断不落库 —— 它毫秒级就能重算，落库只会把表撑大。
        if (deep || !DiagnosisResponse.KIND_OK.equals(kind)) {
            persist(organizationId, jobToken, versionId, response);
        }
        return response;
    }

    /** 要不要落库 —— 见 {@link #diagnose} 里的条件说明。 */
    private void persist(long organizationId, String jobToken, Long versionId,
                         DiagnosisResponse response) {        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findings", response.staticFindings());
        payload.put("conflict_rules", response.conflictRules());
        payload.put("rule_set_hash", response.ruleSetHash());
        payload.put("note", response.note());
        // ⚠ deep 必须落库：回读时要如实告诉调用方"这条记录是浅诊断还是深诊断"。
        //   最初没存它，latest() 只好硬编码 true —— 于是排课失败留下的那条**浅**诊断
        //   会谎称自己是深诊断（实测踩到过）。
        payload.put("deep", response.deep());

        ScheduleDiagnosisEntity entity = new ScheduleDiagnosisEntity();
        entity.setOrganizationId(organizationId);
        entity.setJobToken(jobToken);
        entity.setVersionId(versionId);
        entity.setKind(response.kind());
        entity.setSeverity(response.severity());
        entity.setPayload(payload);
        entity.setCreatedAt(response.createdAt());
        diagnosisMapper.insert(entity);
        log.info("诊断已落库: org={} kind={} findings={} conflictRules={}",
                organizationId, response.kind(), response.staticFindings().size(),
                response.conflictRules().size());
    }

    /**
     * 最近一次**落库**的诊断。
     *
     * <p>⚠ 一个刻意的混合：**冲突集用落库的**（那才是贵的那部分，也是它必须落库的原因），
     * **静态部分现场重算**（毫秒级，而且比落库那一刻的数据更"现在" ——
     * 用户可能刚改完某个班的周课时）。
     *
     * <p>返回 null 表示还没做过深诊断。
     */
    public DiagnosisResponse latest(long organizationId) {
        ScheduleDiagnosisEntity row = diagnosisMapper.selectOne(
                Wrappers.<ScheduleDiagnosisEntity>lambdaQuery()
                        .eq(ScheduleDiagnosisEntity::getOrganizationId, organizationId)
                        .orderByDesc(ScheduleDiagnosisEntity::getId)
                        .last("LIMIT 1"));
        if (row == null) {
            return null;
        }
        List<DiagnosisFinding> fresh = staticDiagnostics.run(organizationId);
        return new DiagnosisResponse(
                row.getKind(),
                highestSeverity(fresh, row.getKind()),
                row.getVersionId(),
                row.getJobToken(),
                stringOf(row.getPayload(), "rule_set_hash"),
                fresh,
                parseConflictRules(row.getPayload()),
                // 如实回读落库时记下的值，而不是假定它是深诊断
                booleanOf(row.getPayload(), "deep"),
                stringOf(row.getPayload(), "note"),
                row.getCreatedAt());
    }

    /** 从落库的 payload 里还原冲突集（它是一串 {@code {rule_id, rule_type, description}}）。 */
    private static List<DiagnosisResponse.ConflictRule> parseConflictRules(Map<String, Object> payload) {
        Object raw = payload == null ? null : payload.get("conflict_rules");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<DiagnosisResponse.ConflictRule> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object id = map.get("rule_id");
                out.add(new DiagnosisResponse.ConflictRule(
                        id instanceof Number n ? n.longValue() : null,
                        map.get("rule_type") == null ? null : String.valueOf(map.get("rule_type")),
                        map.get("description") == null ? null : String.valueOf(map.get("description"))));
            }
        }
        return out;
    }

    private static String stringOf(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanOf(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    /**
     * 最小冲突集：逐条去掉硬规则再求解，去掉后能排出来的就是冲突来源。
     *
     * <p>⚠ 只处理**可配置的硬规则**。不变量（教师不撞课 / 周课时 / 教师上限）是
     * {@code mandatory}，去不掉 —— 如果冲突来自它们，这里返回空列表，
     * 由 {@link #note} 如实说明"冲突不在可配置规则里"。
     */
    private List<DiagnosisResponse.ConflictRule> findConflictRules(long organizationId,
                                                                   String solverStatus) {
        // 只有"确实排不出来"才值得做这一步
        if ("OPTIMAL".equals(solverStatus) || "FEASIBLE".equals(solverStatus)) {
            return List.of();
        }
        List<ConstraintRuleEntity> hardRules = ruleSetService.listAll(organizationId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                .filter(r -> "hard".equalsIgnoreCase(r.getHardness()))
                .toList();
        if (hardRules.isEmpty()) {
            return List.of();
        }

        List<ConstraintRule> allRules = ruleSetService.loadForSolve(organizationId);
        ScheduleInput input = inputLoader.load(organizationId);

        List<DiagnosisResponse.ConflictRule> conflicts = new ArrayList<>();
        int tried = 0;
        for (ConstraintRuleEntity hard : hardRules) {
            if (tried >= MAX_TRIES) {
                break;
            }
            tried++;
            List<ConstraintRule> without = allRules.stream()
                    .filter(r -> !(r.type().equals(hard.getRuleType())
                            && r.params().values().equals(hard.getParams())))
                    .toList();
            if (without.size() == allRules.size()) {
                continue;   // 没匹配上（理论上不会），跳过而不是误报
            }
            try {
                SolveOutcome outcome = generationService.solve(
                        input, without, deepTimeLimitSeconds, 1);
                if (outcome.hasSolution()) {
                    conflicts.add(new DiagnosisResponse.ConflictRule(
                            hard.getId(), hard.getRuleType(), hard.getDescription()));
                }
            } catch (RuntimeException e) {
                // 单条尝试失败不该让整个诊断崩掉
                log.warn("深诊断尝试失败 rule={}: {}", hard.getId(), e.getMessage());
            }
        }
        return conflicts;
    }

    /** {@code INFEASIBLE}（证明无解）与 {@code UNKNOWN}（超时未证明）必须分开报。 */
    private static String determineKind(List<DiagnosisFinding> findings, String solverStatus) {
        boolean certain = findings.stream().anyMatch(f -> DiagnosisFinding.ERROR.equals(f.severity()));
        if (certain) {
            return DiagnosisResponse.KIND_INFEASIBLE;
        }
        if (solverStatus == null) {
            return DiagnosisResponse.KIND_OK;
        }
        return switch (solverStatus) {
            case "INFEASIBLE" -> DiagnosisResponse.KIND_INFEASIBLE;
            case "UNKNOWN" -> DiagnosisResponse.KIND_UNKNOWN;
            // OPTIMAL / FEASIBLE / MODEL_INVALID 之外的都当"不知道"，
            // 而不是乐观地当成功
            case "OPTIMAL", "FEASIBLE" -> DiagnosisResponse.KIND_OK;
            default -> DiagnosisResponse.KIND_UNKNOWN;
        };
    }

    private static String highestSeverity(List<DiagnosisFinding> findings, String kind) {
        if (findings.stream().anyMatch(f -> DiagnosisFinding.ERROR.equals(f.severity()))
                || DiagnosisResponse.KIND_INFEASIBLE.equals(kind)) {
            return DiagnosisFinding.ERROR;
        }
        if (findings.stream().anyMatch(f -> DiagnosisFinding.WARNING.equals(f.severity()))
                || DiagnosisResponse.KIND_UNKNOWN.equals(kind)) {
            return DiagnosisFinding.WARNING;
        }
        return DiagnosisFinding.INFO;
    }

    /**
     * 结论的可信度说明。
     *
     * <p>⚠ 这一段是刻意写的：诊断**最危险的失败方式**不是没结论，
     * 而是给一个听起来确定、其实是猜的结论（"应该是教师超载吧"）。
     * 所以这里必须把"没找到问题"与"排不出来"这两件事的关系说清楚。
     */
    private static String note(List<DiagnosisFinding> findings,
                               List<DiagnosisResponse.ConflictRule> conflictRules,
                               String kind, boolean deep) {
        if (DiagnosisResponse.KIND_OK.equals(kind)) {
            return findings.isEmpty()
                    ? "静态检查未发现问题，最近一次求解也是成功的。"
                    : "静态检查有提示项，但都不影响求解。";
        }
        if (!findings.isEmpty()) {
            return "静态检查已定位到确定的问题（见 static_findings）—— 这些必须修，否则一定排不出来。";
        }
        if (!conflictRules.isEmpty()) {
            return "静态检查未发现问题；深诊断显示：去掉 conflict_rules 里的规则后就能排出课表，"
                    + "冲突出在这些硬规则上。";
        }
        if (!deep) {
            return "静态检查未发现问题。加上 ?deep=true 可以做最小冲突集分析，"
                    + "判断冲突是否来自某条硬规则。";
        }
        return DiagnosisResponse.KIND_UNKNOWN.equals(kind)
                ? "静态检查未发现问题，而求解是**超时未证明**（不是证明无解）——"
                  + "建议延长时间限制或减少约束后重试，**不必急着改数据**。"
                : "静态检查与逐条放宽都没能找到原因：冲突来自**不变量**"
                  + "（教师不撞课 / 周课时必须排满 / 教师周上限）的组合，"
                  + "或来自多条硬规则的共同作用。这类情况需要最小冲突集算法（P3 计划）。";
    }
}
