package com.orbit.academic.fixedslot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.academic.assignment.TeacherAssignment;
import com.orbit.academic.assignment.TeacherAssignmentMapper;
import com.orbit.academic.clazz.Clazz;
import com.orbit.academic.clazz.ClassMapper;
import com.orbit.academic.grade.Grade;
import com.orbit.academic.grade.GradeMapper;
import com.orbit.academic.subject.Subject;
import com.orbit.academic.subject.SubjectMapper;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 年级固定课业务逻辑 —— 对应 Python {@code routers/fixed_slots.py}（3 个接口）。
 *
 * <p>{@link #create} 是全项目**校验最多**的接口：它要在写之前证明
 * 「把整个年级的某个科目固定在同一时间」这件事在教师层面是可行的，
 * 否则排课阶段必然无解。五道关卡（文案逐字对齐 Python）：
 * <ol>
 *   <li>年级、科目都属于本组织（404）</li>
 *   <li>所选节次在该年级当天课时范围内 → 400 {@code 所选节次不在该年级的上课时间内}</li>
 *   <li>该年级已有班级 → 400 {@code 该年级还没有班级}</li>
 *   <li>每个班都已**唯一确定**该科目的任教教师 → 400 {@code 这些班级尚未唯一确定任教教师：A、B}</li>
 *   <li>这个年级的班不是同一位老师教 → 409
 *       {@code 同一教师负责该年级多个班，不能把这些班的课程固定在同一时间}</li>
 *   <li>与其他年级同一时段的固定课教师不冲突 → 409 {@code 与其他年级的固定课程存在教师时间冲突}</li>
 * </ol>
 *
 * <p>已经存在同一 (年级, 星期, 节次) 的记录时是**改科目**而不是新建（upsert）。
 */
@Service
public class GradeFixedSlotService {

    /** 对应 Python 的 {@code DAY_KEYS}，下标就是 {@code day_of_week}。 */
    private static final List<String> DAY_KEYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri");

    private final GradeFixedSlotMapper gradeFixedSlotMapper;
    private final GradeMapper gradeMapper;
    private final SubjectMapper subjectMapper;
    private final ClassMapper classMapper;
    private final TeacherAssignmentMapper assignmentMapper;

    public GradeFixedSlotService(GradeFixedSlotMapper gradeFixedSlotMapper,
                                 GradeMapper gradeMapper,
                                 SubjectMapper subjectMapper,
                                 ClassMapper classMapper,
                                 TeacherAssignmentMapper assignmentMapper) {
        this.gradeFixedSlotMapper = gradeFixedSlotMapper;
        this.gradeMapper = gradeMapper;
        this.subjectMapper = subjectMapper;
        this.classMapper = classMapper;
        this.assignmentMapper = assignmentMapper;
    }

    public PageResponse<GradeFixedSlotResponse> list(long organizationId, Long gradeId, PageQuery query) {
        var wrapper = Wrappers.<GradeFixedSlot>lambdaQuery()
                .eq(GradeFixedSlot::getOrganizationId, organizationId);
        if (gradeId != null && gradeId != 0L) {
            wrapper.eq(GradeFixedSlot::getGradeId, gradeId);
        }
        wrapper.orderByAsc(GradeFixedSlot::getId);

        Page<GradeFixedSlot> page = gradeFixedSlotMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        return PageResponse.of(
                page.getRecords().stream().map(GradeFixedSlotResponse::from).toList(),
                page.getTotal());
    }

    @Transactional
    public GradeFixedSlotResponse create(long organizationId, GradeFixedSlotCreateRequest request) {
        Grade grade = requireOwned(gradeMapper, Grade::getId, Grade::getOrganizationId,
                request.gradeId(), organizationId, "年级不存在");
        requireOwned(subjectMapper, Subject::getId, Subject::getOrganizationId,
                request.subjectId(), organizationId, "科目不存在");

        // ② 节次必须落在该年级当天课时范围内。
        //    ⚠ Python 写的是 `day_of_week in range(5) and period_index in range(periods_per_day.get(DAY_KEYS[day_of_week], 0))`
        //    —— 靠 `and` 的短路保证 DAY_KEYS 不会被越界访问。这里必须保持同样的顺序。
        int dayOfWeek = request.dayOfWeek();
        int periodIndex = request.periodIndex();
        boolean validSlot = false;
        if (dayOfWeek >= 0 && dayOfWeek < DAY_KEYS.size()) {
            int periodsOfDay = grade.getPeriodsPerDay() == null ? 0
                    : grade.getPeriodsPerDay().getOrDefault(DAY_KEYS.get(dayOfWeek), 0);
            validSlot = periodIndex >= 0 && periodIndex < periodsOfDay;
        }
        if (!validSlot) {
            throw BusinessException.badRequest("所选节次不在该年级的上课时间内");
        }

        // ③ 该年级要有班级
        List<Clazz> gradeClasses = classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                .eq(Clazz::getOrganizationId, organizationId)
                .eq(Clazz::getGradeId, grade.getId()));
        if (gradeClasses.isEmpty()) {
            throw BusinessException.badRequest("该年级还没有班级");
        }

        // ④ 每个班都要**唯一**确定该科目的任教教师
        List<Long> classIds = gradeClasses.stream().map(Clazz::getId).toList();
        List<TeacherAssignment> assignments = assignmentMapper.selectList(
                Wrappers.<TeacherAssignment>lambdaQuery()
                        .eq(TeacherAssignment::getOrganizationId, organizationId)
                        .in(TeacherAssignment::getClassId, classIds)
                        .eq(TeacherAssignment::getSubjectId, request.subjectId()));
        Map<Long, List<TeacherAssignment>> byClass = new HashMap<>();
        for (TeacherAssignment assignment : assignments) {
            byClass.computeIfAbsent(assignment.getClassId(), k -> new ArrayList<>()).add(assignment);
        }
        List<String> incomplete = gradeClasses.stream()
                .filter(c -> byClass.getOrDefault(c.getId(), List.of()).size() != 1)
                .map(Clazz::getName)
                .toList();
        if (!incomplete.isEmpty()) {
            throw BusinessException.badRequest(
                    "这些班级尚未唯一确定任教教师：" + String.join("、", incomplete));
        }

        // ⑤ 该年级各班不能是同一位教师（否则同一时间他要同时上多个班）
        List<Long> teacherIds = gradeClasses.stream()
                .map(c -> byClass.get(c.getId()).get(0).getTeacherId())
                .toList();
        if (new HashSet<>(teacherIds).size() != teacherIds.size()) {
            throw BusinessException.conflict("同一教师负责该年级多个班，不能把这些班的课程固定在同一时间");
        }

        // ⑥ 与其他年级同一时段的固定课比教师
        GradeFixedSlot current = gradeFixedSlotMapper.selectOne(
                Wrappers.<GradeFixedSlot>lambdaQuery()
                        .eq(GradeFixedSlot::getOrganizationId, organizationId)
                        .eq(GradeFixedSlot::getGradeId, grade.getId())
                        .eq(GradeFixedSlot::getDayOfWeek, dayOfWeek)
                        .eq(GradeFixedSlot::getPeriodIndex, periodIndex));
        Long excludeId = current == null ? -1L : current.getId();
        List<GradeFixedSlot> otherRules = gradeFixedSlotMapper.selectList(
                Wrappers.<GradeFixedSlot>lambdaQuery()
                        .eq(GradeFixedSlot::getOrganizationId, organizationId)
                        .eq(GradeFixedSlot::getDayOfWeek, dayOfWeek)
                        .eq(GradeFixedSlot::getPeriodIndex, periodIndex)
                        .ne(GradeFixedSlot::getId, excludeId));
        Set<Long> myTeacherIds = new HashSet<>(teacherIds);
        for (GradeFixedSlot rule : otherRules) {
            List<Long> otherClassIds = classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                            .select(Clazz::getId)
                            .eq(Clazz::getOrganizationId, organizationId)
                            .eq(Clazz::getGradeId, rule.getGradeId()))
                    .stream().map(Clazz::getId).toList();
            if (otherClassIds.isEmpty()) {
                continue;
            }
            Set<Long> otherTeacherIds = assignmentMapper.selectList(
                            Wrappers.<TeacherAssignment>lambdaQuery()
                                    .select(TeacherAssignment::getTeacherId)
                                    .eq(TeacherAssignment::getOrganizationId, organizationId)
                                    .in(TeacherAssignment::getClassId, otherClassIds)
                                    .eq(TeacherAssignment::getSubjectId, rule.getSubjectId()))
                    .stream().map(TeacherAssignment::getTeacherId)
                    .collect(java.util.stream.Collectors.toSet());
            if (!java.util.Collections.disjoint(myTeacherIds, otherTeacherIds)) {
                throw BusinessException.conflict("与其他年级的固定课程存在教师时间冲突");
            }
        }

        if (current != null) {
            current.setSubjectId(request.subjectId());
            gradeFixedSlotMapper.updateById(current);
            return GradeFixedSlotResponse.from(current);
        }
        GradeFixedSlot created = new GradeFixedSlot();
        created.setOrganizationId(organizationId);
        created.setGradeId(grade.getId());
        created.setSubjectId(request.subjectId());
        created.setDayOfWeek(dayOfWeek);
        created.setPeriodIndex(periodIndex);
        gradeFixedSlotMapper.insert(created);
        return GradeFixedSlotResponse.from(created);
    }

    @Transactional
    public void delete(long organizationId, long slotId) {
        GradeFixedSlot slot = gradeFixedSlotMapper.selectOne(Wrappers.<GradeFixedSlot>lambdaQuery()
                .eq(GradeFixedSlot::getId, slotId)
                .eq(GradeFixedSlot::getOrganizationId, organizationId));
        if (slot == null) {
            throw BusinessException.notFound("年级固定课程不存在");
        }
        gradeFixedSlotMapper.deleteById(slot.getId());
    }

    private static <T> T requireOwned(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper,
                                      com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> idColumn,
                                      com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> orgColumn,
                                      Long id, long organizationId, String notFoundMessage) {
        if (id == null) {
            throw BusinessException.notFound(notFoundMessage);
        }
        T found = mapper.selectOne(Wrappers.<T>lambdaQuery()
                .eq(idColumn, id)
                .eq(orgColumn, organizationId));
        if (found == null) {
            throw BusinessException.notFound(notFoundMessage);
        }
        return found;
    }
}
