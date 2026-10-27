package com.orbit.scheduling.swap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.audit.ScheduleAuditLog;
import com.orbit.scheduling.audit.ScheduleAuditLogMapper;
import com.orbit.scheduling.security.SchedulePrincipal;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.GradeFixedSlotRef;
import com.orbit.scheduling.snapshot.GradeRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.validate.VersionValidator;
import com.orbit.scheduling.version.ScheduleVersion;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import com.orbit.scheduling.version.ScheduleVersionMapper;
import com.orbit.scheduling.version.VersionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 拖动/换课 —— 对应 Python {@code schedule.py:381-510}，**全项目最"绕"的一段逻辑**。
 *
 * <h2>核心手法：用负值把记录"暂时移出"唯一键的冲突范围</h2>
 * 表上有唯一约束 {@code uq_version_class_slot (version_id, class_id, day_of_week, period_index)}。
 * 想把 A 挪到 B 的位置、B 挪到 A 的位置时，**无论先改哪一个都会撞这个约束**。
 * Python 的解法是把要移动的记录先改成"负值坐标"：
 * <pre>{@code
 * first.day_of_week, first.period_index = -1, -first.id     # 负数不会与任何真实时段冲突
 * second.day_of_week, second.period_index = old_day, old_period
 * first.day_of_week, first.period_index = destination_day, destination_period
 * }</pre>
 * 这个设计直接依赖唯一约束的存在（否则没必要绕），所以**改表结构时必须一起看这里**。
 *
 * <h2>四个业务概念</h2>
 * <ul>
 *   <li><b>同班拖动</b>：{@code target_class_id == 源班级}，与目标位置的课互换（目标为空就是移动）</li>
 *   <li><b>跨班协同换时</b>：两个班的课互换，且必须是**同一年级**（否则作息不同、换完更乱）</li>
 *   <li><b>镜像</b>：跨班时还要处理"对方班在我原来的时段那一节"和"我在对方目标时段那一节"，
 *       它们是协同换时的一部分，必须一起腾挪</li>
 *   <li><b>固定课保护</b>：涉及到的 (年级, 星期, 节次) 里若有年级固定课 → 禁止（409）</li>
 * </ul>
 *
 * <p>换完还会跑一次 {@link VersionValidator}（**拖动后整体重校验**）——
 * 不信任"这次换课本身没冲突"，而是重新验证整张表，因为一次拖动可能间接造成别处的冲突。
 */
@Service
public class SwapService {

    private final ScheduleVersionMapper versionMapper;
    private final ScheduleVersionEntryMapper entryMapper;
    private final ScheduleAuditLogMapper auditMapper;
    private final VersionValidator validator;
    private final SnapshotMappers.ClassRefMapper classMapper;
    private final SnapshotMappers.GradeRefMapper gradeMapper;
    private final SnapshotMappers.GradeFixedSlotRefMapper gradeFixedSlotMapper;

    public SwapService(ScheduleVersionMapper versionMapper,
                       ScheduleVersionEntryMapper entryMapper,
                       ScheduleAuditLogMapper auditMapper,
                       VersionValidator validator,
                       SnapshotMappers.ClassRefMapper classMapper,
                       SnapshotMappers.GradeRefMapper gradeMapper,
                       SnapshotMappers.GradeFixedSlotRefMapper gradeFixedSlotMapper) {
        this.versionMapper = versionMapper;
        this.entryMapper = entryMapper;
        this.auditMapper = auditMapper;
        this.validator = validator;
        this.classMapper = classMapper;
        this.gradeMapper = gradeMapper;
        this.gradeFixedSlotMapper = gradeFixedSlotMapper;
    }

    /**
     * 精确干跑：在真实事务里执行与正式换课完全相同的代码，比较前后条目后强制回滚。
     * 这样固定课、跨班镜像、独立校验器和未来新增校验都只有一份实现。
     */
    @Transactional
    public SwapPreviewResponse preview(long organizationId, SchedulePrincipal principal,
                                       long versionId, SwapRequest request) {
        List<ScheduleVersionEntry> beforeRows = entriesOf(organizationId, versionId);
        Map<Long, SwapPreviewResponse.Position> before = positions(beforeRows);
        try {
            swapInternal(organizationId, principal, versionId, request, false);
            Map<Long, SwapPreviewResponse.Position> after = positions(
                    entriesOf(organizationId, versionId));
            List<SwapPreviewResponse.AffectedEntry> affected = before.entrySet().stream()
                    .filter(entry -> !java.util.Objects.equals(entry.getValue(), after.get(entry.getKey())))
                    .map(entry -> new SwapPreviewResponse.AffectedEntry(
                            entry.getKey(), entry.getValue(), after.get(entry.getKey())))
                    .sorted(java.util.Comparator.comparing(SwapPreviewResponse.AffectedEntry::entryId))
                    .toList();
            rollbackPreview();
            return new SwapPreviewResponse(true, affected, List.of(), request.expectedRevision());
        } catch (BusinessException error) {
            rollbackPreview();
            String type = switch (error.status()) {
                case 404 -> "NOT_FOUND";
                case 409 -> "CONFLICT";
                default -> "INVALID_REQUEST";
            };
            return new SwapPreviewResponse(false, List.of(),
                    List.of(new SwapPreviewResponse.Conflict(type, error.detail())),
                    request.expectedRevision());
        }
    }

    @Transactional
    public VersionResponse swap(long organizationId, SchedulePrincipal principal,
                                long versionId, SwapRequest request) {
        return swapInternal(organizationId, principal, versionId, request, true);
    }

    private VersionResponse swapInternal(long organizationId, SchedulePrincipal principal,
                                         long versionId, SwapRequest request,
                                         boolean recordAudit) {
        // ① 锁住版本行（拖动是并发敏感操作：两个管理员同时拖同一版必须串行）
        ScheduleVersion version = versionMapper.selectOne(Wrappers.<ScheduleVersion>lambdaQuery()
                .eq(ScheduleVersion::getId, versionId)
                .eq(ScheduleVersion::getOrganizationId, organizationId)
                .last("FOR UPDATE"));
        if (version == null) {
            throw BusinessException.notFound("课表版本不存在");
        }
        if (!"draft".equals(version.getStatus())) {
            throw BusinessException.conflict("已发布课表不可拖动，请先复制为新草稿");
        }
        if (!java.util.Objects.equals(version.getRevision(), request.expectedRevision())) {
            throw BusinessException.conflict("该草稿刚被其他用户修改，请刷新后重试");
        }

        ScheduleVersionEntry source = entryMapper.selectOne(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getId, request.sourceEntryId())
                .eq(ScheduleVersionEntry::getVersionId, version.getId()));
        if (source == null) {
            throw BusinessException.notFound("要移动的课程不存在");
        }
        ClassRef sourceClass = classMapper.selectOne(Wrappers.<ClassRef>lambdaQuery()
                .eq(ClassRef::getId, source.getClassId())
                .eq(ClassRef::getOrganizationId, organizationId));
        ClassRef targetClass = classMapper.selectOne(Wrappers.<ClassRef>lambdaQuery()
                .eq(ClassRef::getId, request.targetClassId())
                .eq(ClassRef::getOrganizationId, organizationId));
        if (sourceClass == null || targetClass == null) {
            throw BusinessException.notFound("班级不存在");
        }
        GradeRef sourceGrade = gradeMapper.selectById(sourceClass.getGradeId());
        GradeRef targetGrade = gradeMapper.selectById(targetClass.getGradeId());

        int targetDay = request.targetDayOfWeek();
        int targetPeriod = request.targetPeriodIndex();
        // ② 目标时段必须同时落在两个班当天的上课时间内
        boolean validTarget = targetDay >= 0 && targetDay < VersionValidator.DAY_KEYS.size()
                && targetPeriod >= 0 && targetPeriod < periods(sourceGrade, targetDay)
                && targetPeriod < periods(targetGrade, targetDay);
        // ③ 源时段也必须落在**目标班**当天的时间内 —— 否则换过去对方那天根本没那么节课
        boolean validSourceForTarget = source.getDayOfWeek() >= 0
                && source.getDayOfWeek() < VersionValidator.DAY_KEYS.size()
                && source.getPeriodIndex() >= 0
                && source.getPeriodIndex() < periods(targetGrade, source.getDayOfWeek());
        if (!validTarget || !validSourceForTarget) {
            throw BusinessException.badRequest("目标节次不在两个班级的共同上课时间内");
        }
        boolean crossClass = !java.util.Objects.equals(source.getClassId(), targetClass.getId());
        if (crossClass && !java.util.Objects.equals(sourceClass.getGradeId(), targetClass.getGradeId())) {
            throw BusinessException.badRequest("跨班协同换时仅支持同一年级的班级");
        }

        int oldDay = source.getDayOfWeek();
        int oldPeriod = source.getPeriodIndex();
        ScheduleVersionEntry target = findEntry(version.getId(), targetClass.getId(), targetDay, targetPeriod);
        if (target != null && java.util.Objects.equals(target.getId(), source.getId())) {
            // 拖回原位 → 什么都不做，直接返回（Python 也是提前 return）
            return VersionResponse.of(version, countEntries(version.getId()));
        }
        if (crossClass && target == null) {
            throw BusinessException.badRequest("跨班拖动需要放到另一个班级已有课程上进行协同换时");
        }

        // ④ 固定课保护：涉及到的 (年级, 星期, 节次) 里不能有年级固定课
        Set<String> protectedGradePositions = new HashSet<>();
        Map<Long, Long> classGrade = new HashMap<>();
        classGrade.put(sourceClass.getId(), sourceClass.getGradeId());
        classGrade.put(targetClass.getId(), targetClass.getGradeId());
        addProtected(protectedGradePositions, classGrade, sourceClass.getId(), oldDay, oldPeriod);
        addProtected(protectedGradePositions, classGrade, sourceClass.getId(), targetDay, targetPeriod);
        if (crossClass) {
            addProtected(protectedGradePositions, classGrade, targetClass.getId(), oldDay, oldPeriod);
            addProtected(protectedGradePositions, classGrade, targetClass.getId(), targetDay, targetPeriod);
        }
        Set<Long> gradeIds = new HashSet<>();
        for (String key : protectedGradePositions) {
            gradeIds.add(Long.parseLong(key.split(":")[0]));
        }
        List<GradeFixedSlotRef> fixedRules = gradeFixedSlotMapper.selectList(
                Wrappers.<GradeFixedSlotRef>lambdaQuery()
                        .eq(GradeFixedSlotRef::getOrganizationId, organizationId)
                        .in(GradeFixedSlotRef::getGradeId, gradeIds));
        for (GradeFixedSlotRef rule : fixedRules) {
            if (protectedGradePositions.contains(
                    rule.getGradeId() + ":" + rule.getDayOfWeek() + ":" + rule.getPeriodIndex())) {
                throw BusinessException.conflict("协同换时涉及固定课程，不能调整");
            }
        }

        // ⑤ 腾挪
        if (crossClass) {
            ScheduleVersionEntry sourceMirror =
                    findEntry(version.getId(), sourceClass.getId(), targetDay, targetPeriod);
            ScheduleVersionEntry targetMirror =
                    findEntry(version.getId(), targetClass.getId(), oldDay, oldPeriod);
            swapInClass(source, sourceMirror, oldDay, oldPeriod, targetDay, targetPeriod);

            int targetOldDay = target.getDayOfWeek();
            int targetOldPeriod = target.getPeriodIndex();
            // 用 -2 区分于 swapInClass 里的 -1（调试时能看出是谁在腾挪）
            target.setDayOfWeek(-2);
            target.setPeriodIndex(-target.getId().intValue());
            entryMapper.updateById(target);
            if (targetMirror != null) {
                targetMirror.setDayOfWeek(targetOldDay);
                targetMirror.setPeriodIndex(targetOldPeriod);
                entryMapper.updateById(targetMirror);
            }
            target.setDayOfWeek(oldDay);
            target.setPeriodIndex(oldPeriod);
            entryMapper.updateById(target);
        } else {
            swapInClass(source, target, oldDay, oldPeriod, targetDay, targetPeriod);
        }

        // ⑥ 整体重校验（不信任"这次换课本身没冲突"）
        validator.validate(version);

        version.setRevision(version.getRevision() + 1);
        version.setUpdatedAt(UtcTimes.now());
        versionMapper.updateById(version);

        String action = crossClass ? "cross_swapped" : (target != null ? "swapped" : "moved");
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("class_id", source.getClassId());
        details.put("target_class_id", targetClass.getId());
        details.put("from", List.of(oldDay, oldPeriod));
        details.put("to", List.of(targetDay, targetPeriod));
        details.put("source_entry_id", source.getId());
        details.put("target_entry_id", target == null ? null : target.getId());

        if (recordAudit) {
            ScheduleAuditLog log = new ScheduleAuditLog();
            log.setOrganizationId(organizationId);
            log.setVersionId(version.getId());
            log.setUserId(principal.userId());
            log.setUserName(principal.displayName());
            log.setAction(action);
            log.setDetails(details);
            log.setCreatedAt(UtcTimes.now());
            auditMapper.insert(log);
        }

        return VersionResponse.of(version, countEntries(version.getId()));
    }

    /**
     * 把一个班的"移动 + 互换"做完（对应 Python 的 {@code swap_in_class}）。
     *
     * <p>关键是**先把 first 挪到负值**再让 second 占位，否则会撞唯一约束。
     * 负数用 {@code -first.id} 而不是固定 -1：两次调用（跨班时会调两次）不会互相撞。
     */
    private void swapInClass(ScheduleVersionEntry first, ScheduleVersionEntry second,
                             int oldDay, int oldPeriod, int destinationDay, int destinationPeriod) {
        if (first == null) {
            return;
        }
        first.setDayOfWeek(-1);
        first.setPeriodIndex(-first.getId().intValue());
        entryMapper.updateById(first);

        if (second != null) {
            second.setDayOfWeek(oldDay);
            second.setPeriodIndex(oldPeriod);
            entryMapper.updateById(second);
        }
        first.setDayOfWeek(destinationDay);
        first.setPeriodIndex(destinationPeriod);
        entryMapper.updateById(first);
    }

    private void addProtected(Set<String> positions, Map<Long, Long> classGrade,
                              long classId, int day, int period) {
        Long gradeId = classGrade.get(classId);
        if (gradeId != null) {
            positions.add(gradeId + ":" + day + ":" + period);
        }
    }

    private ScheduleVersionEntry findEntry(long versionId, long classId, int day, int period) {
        return entryMapper.selectOne(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getVersionId, versionId)
                .eq(ScheduleVersionEntry::getClassId, classId)
                .eq(ScheduleVersionEntry::getDayOfWeek, day)
                .eq(ScheduleVersionEntry::getPeriodIndex, period));
    }

    /** 某年级某天有几节课（{@code periods_per_day} 的 JSON 里取）。 */
    private static int periods(GradeRef grade, int day) {
        if (grade == null || grade.getPeriodsPerDay() == null
                || day < 0 || day >= VersionValidator.DAY_KEYS.size()) {
            return 0;
        }
        return grade.getPeriodsPerDay().getOrDefault(VersionValidator.DAY_KEYS.get(day), 0);
    }

    private long countEntries(long versionId) {
        return entryMapper.selectCount(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getVersionId, versionId));
    }

    private List<ScheduleVersionEntry> entriesOf(long organizationId, long versionId) {
        return entryMapper.selectList(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                .eq(ScheduleVersionEntry::getVersionId, versionId));
    }

    private static Map<Long, SwapPreviewResponse.Position> positions(
            List<ScheduleVersionEntry> entries) {
        return entries.stream().collect(Collectors.toMap(
                ScheduleVersionEntry::getId,
                entry -> new SwapPreviewResponse.Position(
                        entry.getClassId(), entry.getSubjectId(), entry.getTeacherId(),
                        entry.getDayOfWeek(), entry.getPeriodIndex()),
                (left, right) -> left,
                java.util.LinkedHashMap::new));
    }

    private static void rollbackPreview() {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }
}
