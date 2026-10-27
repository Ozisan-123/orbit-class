package com.orbit.scheduling.score;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.rule.RuleSetService;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.GradeRef;
import com.orbit.scheduling.snapshot.GradeSubjectRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.snapshot.SubjectRef;
import com.orbit.scheduling.snapshot.TeacherRef;
import com.orbit.scheduling.validate.VersionValidator;
import com.orbit.scheduling.version.ScheduleVersion;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import com.orbit.scheduling.version.ScheduleVersionMapper;
import com.orbit.solver.core.rule.ConstraintRule;
import com.orbit.solver.core.rule.RuleParams;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 课表质量评分。总分 100：软规则 50、教师空档 20、课程分布 15、主科上午 15；
 * 任一硬约束失败时整版判无效并归零。
 */
@Service
public class ScoreService {

    private final ScheduleVersionMapper versionMapper;
    private final ScheduleVersionEntryMapper entryMapper;
    private final SnapshotMappers.ClassRefMapper classMapper;
    private final SnapshotMappers.GradeRefMapper gradeMapper;
    private final SnapshotMappers.GradeSubjectRefMapper gradeSubjectMapper;
    private final SnapshotMappers.TeacherRefMapper teacherMapper;
    private final SnapshotMappers.SubjectRefMapper subjectMapper;
    private final RuleSetService ruleSetService;
    private final VersionValidator validator;
    private final ObjectMapper objectMapper;

    public ScoreService(ScheduleVersionMapper versionMapper,
                        ScheduleVersionEntryMapper entryMapper,
                        SnapshotMappers.ClassRefMapper classMapper,
                        SnapshotMappers.GradeRefMapper gradeMapper,
                        SnapshotMappers.GradeSubjectRefMapper gradeSubjectMapper,
                        SnapshotMappers.TeacherRefMapper teacherMapper,
                        SnapshotMappers.SubjectRefMapper subjectMapper,
                        RuleSetService ruleSetService,
                        VersionValidator validator,
                        ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.entryMapper = entryMapper;
        this.classMapper = classMapper;
        this.gradeMapper = gradeMapper;
        this.gradeSubjectMapper = gradeSubjectMapper;
        this.teacherMapper = teacherMapper;
        this.subjectMapper = subjectMapper;
        this.ruleSetService = ruleSetService;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public ScoreResponse score(long organizationId, long versionId) {
        ScheduleVersion version = requireVersion(organizationId, versionId);
        if (version.getRuleSetHash() == null || version.getRuleSetHash().isBlank()) {
            throw BusinessException.conflict("该课表版本没有规则集指纹，无法生成可追溯评分");
        }
        Model model = loadModel(organizationId, version);
        List<ConstraintRule> rules = ruleSetService.rulesByHash(organizationId, version.getRuleSetHash());
        List<String> hardViolations = hardViolations(version, model);
        List<ScoreResponse.RuleMetric> breakdown = new ArrayList<>();

        long weightedEarned = 0;
        long totalWeight = 0;
        long softViolations = 0;
        long softOpportunities = 0;
        for (ConstraintRule rule : rules) {
            Measure measure = measure(rule, model);
            int satisfaction = percent(measure.violations(), measure.opportunities());
            breakdown.add(new ScoreResponse.RuleMetric(
                    rule.type(), rule.hardness().name().toLowerCase(), rule.effectiveWeight(),
                    measure.violations(), measure.opportunities(), satisfaction, measure.explanation()));
            if (rule.isSoft()) {
                totalWeight += rule.effectiveWeight();
                weightedEarned += (long) satisfaction * rule.effectiveWeight();
                softViolations += measure.violations();
                softOpportunities += measure.opportunities();
            } else if (measure.violations() > 0) {
                hardViolations.add(measure.explanation());
            }
        }

        int softPoints = ScoreMath.weightedSoftPoints(weightedEarned, totalWeight);
        Measure gaps = teacherGaps(model, null);
        int gapPoints = points(20, gaps);
        Measure distribution = distribution(model);
        int distributionPoints = points(15, distribution);
        Measure morning = mainSubjectMorning(model);
        int morningPoints = points(15, morning);

        List<ScoreResponse.Dimension> dimensions = List.of(
                dimension("soft_rules", "软规则满足度", 50, softPoints,
                        softViolations, softOpportunities,
                        totalWeight == 0 ? "规则集中没有软规则，记满分"
                                : "按规则权重汇总；每条规则按违规次数/可评机会扣分"),
                dimension("teacher_gaps", "教师空档", 20, gapPoints,
                        gaps.violations(), gaps.opportunities(), gaps.explanation()),
                dimension("distribution", "课程分布均衡", 15, distributionPoints,
                        distribution.violations(), distribution.opportunities(), distribution.explanation()),
                dimension("main_subject_morning", "主科上午占比", 15, morningPoints,
                        morning.violations(), morning.opportunities(), morning.explanation()));

        boolean valid = hardViolations.isEmpty();
        int total = valid ? softPoints + gapPoints + distributionPoints + morningPoints : 0;
        ScoreResponse response = new ScoreResponse(version.getId(), version.getRevision(),
                version.getRuleSetHash(), valid, total, List.copyOf(hardViolations),
                dimensions, List.copyOf(breakdown), UtcTimes.now());
        persist(organizationId, versionId, response);
        return response;
    }

    public ScoreDiffResponse diff(long organizationId, long versionId, long againstId) {
        if (versionId == againstId) {
            throw BusinessException.badRequest("对比版本不能与当前版本相同");
        }
        ScoreResponse current = score(organizationId, versionId);
        ScoreResponse against = score(organizationId, againstId);
        Map<String, ScoreResponse.Dimension> old = new HashMap<>();
        against.dimensions().forEach(item -> old.put(item.key(), item));
        List<ScoreDiffResponse.DimensionDelta> deltas = current.dimensions().stream()
                .map(item -> {
                    ScoreResponse.Dimension previous = old.get(item.key());
                    int oldPoints = previous == null ? 0 : previous.earnedPoints();
                    return new ScoreDiffResponse.DimensionDelta(item.key(), item.label(),
                            item.earnedPoints(), oldPoints, item.earnedPoints() - oldPoints);
                }).toList();
        return new ScoreDiffResponse(current, against,
                current.totalScore() - against.totalScore(), deltas);
    }

    private List<String> hardViolations(ScheduleVersion version, Model model) {
        List<String> violations = new ArrayList<>();
        try {
            validator.validate(version);
        } catch (BusinessException error) {
            violations.add(error.detail());
        }

        Map<String, Long> actual = new HashMap<>();
        for (ScheduleVersionEntry entry : model.entries()) {
            actual.merge(entry.getClassId() + ":" + entry.getSubjectId(), 1L, Long::sum);
        }
        Set<String> requiredKeys = new HashSet<>();
        for (ClassRef clazz : model.classes().values()) {
            for (GradeSubjectRef need : model.gradeSubjects()) {
                if (!java.util.Objects.equals(clazz.getGradeId(), need.getGradeId())) continue;
                String key = clazz.getId() + ":" + need.getSubjectId();
                requiredKeys.add(key);
                long count = actual.getOrDefault(key, 0L);
                int required = need.getWeeklyHours() == null ? 0 : need.getWeeklyHours();
                if (count != required) {
                    violations.add("班级 " + clazz.getName() + " 的科目 " + need.getSubjectId()
                            + " 应排 " + required + " 节，实际 " + count + " 节");
                }
            }
        }
        for (Map.Entry<String, Long> item : actual.entrySet()) {
            if (!requiredKeys.contains(item.getKey())) {
                violations.add("课表含课程方案外的班级/科目组合 " + item.getKey()
                        + "，共 " + item.getValue() + " 节");
            }
        }

        Map<Long, Long> teacherHours = new HashMap<>();
        model.entries().forEach(e -> teacherHours.merge(e.getTeacherId(), 1L, Long::sum));
        for (TeacherRef teacher : model.teachers().values()) {
            long count = teacherHours.getOrDefault(teacher.getId(), 0L);
            if (teacher.getMaxWeeklyHours() != null && count > teacher.getMaxWeeklyHours()) {
                violations.add(teacher.getName() + "周课时 " + count
                        + "，超过上限 " + teacher.getMaxWeeklyHours());
            }
        }
        return violations;
    }

    private Measure measure(ConstraintRule rule, Model model) {
        RuleParams p = rule.params();
        return switch (rule.type()) {
            case "subject_max_per_day" -> subjectMaxPerDay(model,
                    optionalLong(p, "subject_id"), p.requireInt("max"));
            case "subject_no_consecutive" -> subjectNoConsecutive(model,
                    optionalLong(p, "subject_id"));
            case "teacher_max_daily_periods" -> teacherMaxDaily(model,
                    optionalLong(p, "teacher_id"), p.requireInt("max"));
            case "teacher_unavailable" -> teacherUnavailable(model, p.requireLong("teacher_id"),
                    p.requireInt("day") - 1, p.requireInt("period") - 1);
            case "subject_avoid_last_period" -> subjectAvoidLast(model,
                    optionalLong(p, "subject_id"));
            case "main_subject_morning" -> subjectMorning(model,
                    optionalLong(p, "subject_id"), p.requireInt("morning_periods"));
            case "teacher_minimize_gaps" -> teacherGaps(model,
                    optionalLong(p, "teacher_id"));
            // 新 handler 上线时必须同步定义独立复核口径；不能让未知规则静默拿满分。
            default -> new Measure(1, 1, "评分器尚不支持规则类型 " + rule.type());
        };
    }

    private Measure subjectMaxPerDay(Model model, Long subjectId, int max) {
        Map<String, Long> counts = new HashMap<>();
        long opportunities = 0;
        for (ScheduleVersionEntry e : model.entries()) {
            if (subjectId != null && !subjectId.equals(e.getSubjectId())) continue;
            counts.merge(e.getClassId() + ":" + e.getSubjectId() + ":" + e.getDayOfWeek(), 1L, Long::sum);
            opportunities++;
        }
        long violations = counts.values().stream().mapToLong(n -> Math.max(0, n - max)).sum();
        return new Measure(violations, opportunities, "单日单科超过上限的课时共 " + violations + " 节");
    }

    private Measure subjectNoConsecutive(Model model, Long subjectId) {
        Set<String> slots = new HashSet<>();
        long opportunities = 0;
        for (ScheduleVersionEntry e : model.entries()) {
            if (subjectId != null && !subjectId.equals(e.getSubjectId())) continue;
            slots.add(e.getClassId() + ":" + e.getSubjectId() + ":" + e.getDayOfWeek() + ":" + e.getPeriodIndex());
            opportunities++;
        }
        long violations = 0;
        for (String slot : slots) {
            String[] x = slot.split(":");
            int period = Integer.parseInt(x[3]);
            if (slots.contains(x[0] + ":" + x[1] + ":" + x[2] + ":" + (period + 1))) violations++;
        }
        return new Measure(violations, opportunities, "同一科目相邻连排共 " + violations + " 处");
    }

    private Measure teacherMaxDaily(Model model, Long teacherId, int max) {
        Map<String, Long> counts = new HashMap<>();
        long opportunities = 0;
        for (ScheduleVersionEntry e : model.entries()) {
            if (teacherId != null && !teacherId.equals(e.getTeacherId())) continue;
            counts.merge(e.getTeacherId() + ":" + e.getDayOfWeek(), 1L, Long::sum);
            opportunities++;
        }
        long violations = counts.values().stream().mapToLong(n -> Math.max(0, n - max)).sum();
        return new Measure(violations, opportunities, "教师单日超限课时共 " + violations + " 节");
    }

    private Measure teacherUnavailable(Model model, long teacherId, int day, int period) {
        long hits = model.entries().stream().filter(e -> e.getTeacherId() == teacherId
                && e.getDayOfWeek() == day && e.getPeriodIndex() == period).count();
        return new Measure(hits, 1, hits == 0 ? "不可用时段未排课" : "不可用时段排了 " + hits + " 节课");
    }

    private Measure subjectAvoidLast(Model model, Long subjectId) {
        long opportunities = 0;
        long violations = 0;
        for (ScheduleVersionEntry e : model.entries()) {
            if (subjectId != null && !subjectId.equals(e.getSubjectId())) continue;
            opportunities++;
            if (e.getPeriodIndex() == periods(model, e.getClassId(), e.getDayOfWeek()) - 1) violations++;
        }
        return new Measure(violations, opportunities, "目标科目落在末节共 " + violations + " 节");
    }

    private Measure subjectMorning(Model model, Long subjectId, int morningPeriods) {
        long opportunities = 0;
        long violations = 0;
        for (ScheduleVersionEntry e : model.entries()) {
            if (subjectId != null && !subjectId.equals(e.getSubjectId())) continue;
            opportunities++;
            if (e.getPeriodIndex() >= morningPeriods) violations++;
        }
        return new Measure(violations, opportunities, "目标科目排在前 " + morningPeriods
                + " 节之后共 " + violations + " 节");
    }

    private Measure teacherGaps(Model model, Long teacherId) {
        Map<String, List<Integer>> periods = new HashMap<>();
        long lessons = 0;
        for (ScheduleVersionEntry e : model.entries()) {
            if (teacherId != null && !teacherId.equals(e.getTeacherId())) continue;
            periods.computeIfAbsent(e.getTeacherId() + ":" + e.getDayOfWeek(), k -> new ArrayList<>())
                    .add(e.getPeriodIndex());
            lessons++;
        }
        long gaps = 0;
        for (List<Integer> day : periods.values()) {
            Set<Integer> unique = new HashSet<>(day);
            if (unique.size() <= 1) continue;
            int first = unique.stream().min(Integer::compareTo).orElse(0);
            int last = unique.stream().max(Integer::compareTo).orElse(first);
            gaps += (last - first + 1L) - unique.size();
        }
        return new Measure(gaps, lessons, "教师首节课到末节课之间共有 " + gaps + " 个空档");
    }

    private Measure distribution(Model model) {
        Map<String, Map<Integer, Long>> byDay = new HashMap<>();
        for (ScheduleVersionEntry e : model.entries()) {
            byDay.computeIfAbsent(e.getClassId() + ":" + e.getSubjectId(), k -> new HashMap<>())
                    .merge(e.getDayOfWeek(), 1L, Long::sum);
        }
        long concentrated = byDay.values().stream().flatMap(m -> m.values().stream())
                .mapToLong(n -> Math.max(0, n - 1)).sum();
        return new Measure(concentrated, model.entries().size(),
                "同班同科在同一天第 2 节起计为集中，共 " + concentrated + " 节");
    }

    private Measure mainSubjectMorning(Model model) {
        Set<Long> majors = new HashSet<>();
        model.subjects().values().stream().filter(s -> "major".equalsIgnoreCase(s.getCategory()))
                .forEach(s -> majors.add(s.getId()));
        long total = 0;
        long afternoon = 0;
        for (ScheduleVersionEntry e : model.entries()) {
            if (!majors.contains(e.getSubjectId())) continue;
            total++;
            if (e.getPeriodIndex() >= 4) afternoon++;
        }
        return new Measure(afternoon, total, "主科以前 4 节为上午口径，下午共有 " + afternoon + " 节");
    }

    private Model loadModel(long organizationId, ScheduleVersion version) {
        List<ScheduleVersionEntry> entries = entryMapper.selectList(
                Wrappers.<ScheduleVersionEntry>lambdaQuery()
                        .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                        .eq(ScheduleVersionEntry::getVersionId, version.getId()));
        return new Model(entries,
                indexed(classMapper.selectList(Wrappers.<ClassRef>lambdaQuery()
                        .eq(ClassRef::getOrganizationId, organizationId)), ClassRef::getId),
                indexed(gradeMapper.selectList(Wrappers.<GradeRef>lambdaQuery()
                        .eq(GradeRef::getOrganizationId, organizationId)), GradeRef::getId),
                indexed(teacherMapper.selectList(Wrappers.<TeacherRef>lambdaQuery()
                        .eq(TeacherRef::getOrganizationId, organizationId)), TeacherRef::getId),
                indexed(subjectMapper.selectList(Wrappers.<SubjectRef>lambdaQuery()
                        .eq(SubjectRef::getOrganizationId, organizationId)), SubjectRef::getId),
                gradeSubjectMapper.selectList(Wrappers.<GradeSubjectRef>lambdaQuery()
                        .eq(GradeSubjectRef::getOrganizationId, organizationId)));
    }

    private ScheduleVersion requireVersion(long organizationId, long versionId) {
        ScheduleVersion version = versionMapper.selectOne(Wrappers.<ScheduleVersion>lambdaQuery()
                .eq(ScheduleVersion::getId, versionId)
                .eq(ScheduleVersion::getOrganizationId, organizationId));
        if (version == null) throw BusinessException.notFound("课表版本不存在");
        return version;
    }

    private void persist(long organizationId, long versionId, ScoreResponse response) {
        try {
            versionMapper.updateScore(organizationId, versionId, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("评分结果无法序列化", error);
        }
    }

    private static ScoreResponse.Dimension dimension(String key, String label, int max, int earned,
                                                       long actual, long opportunities, String explanation) {
        return new ScoreResponse.Dimension(key, label, max, earned, actual, opportunities,
                max - earned, explanation);
    }

    private static int points(int max, Measure measure) {
        return ScoreMath.dimensionPoints(max, measure.violations(), measure.opportunities());
    }

    private static int percent(long violations, long opportunities) {
        return ScoreMath.satisfactionPercent(violations, opportunities);
    }

    private static Long optionalLong(RuleParams params, String name) {
        return params.has(name) ? params.requireLong(name) : null;
    }

    private static int periods(Model model, long classId, int day) {
        ClassRef clazz = model.classes().get(classId);
        GradeRef grade = clazz == null ? null : model.grades().get(clazz.getGradeId());
        if (grade == null || grade.getPeriodsPerDay() == null || day < 0
                || day >= VersionValidator.DAY_KEYS.size()) return 0;
        return grade.getPeriodsPerDay().getOrDefault(VersionValidator.DAY_KEYS.get(day), 0);
    }

    private static <T> Map<Long, T> indexed(List<T> items, java.util.function.Function<T, Long> id) {
        Map<Long, T> out = new LinkedHashMap<>();
        items.stream().sorted(Comparator.comparing(id)).forEach(item -> out.put(id.apply(item), item));
        return out;
    }

    private record Measure(long violations, long opportunities, String explanation) {
    }

    private record Model(List<ScheduleVersionEntry> entries,
                         Map<Long, ClassRef> classes,
                         Map<Long, GradeRef> grades,
                         Map<Long, TeacherRef> teachers,
                         Map<Long, SubjectRef> subjects,
                         List<GradeSubjectRef> gradeSubjects) {
    }
}
