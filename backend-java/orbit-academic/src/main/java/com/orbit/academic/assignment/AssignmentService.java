package com.orbit.academic.assignment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.academic.clazz.Clazz;
import com.orbit.academic.clazz.ClassMapper;
import com.orbit.academic.organization.OrganizationMapper;
import com.orbit.academic.subject.GradeSubject;
import com.orbit.academic.subject.GradeSubjectMapper;
import com.orbit.academic.subject.Subject;
import com.orbit.academic.subject.SubjectMapper;
import com.orbit.academic.teacher.Teacher;
import com.orbit.academic.teacher.TeacherMapper;
import com.orbit.common.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任教分配业务逻辑 —— 对应 Python {@code routers/assignments.py} 的前半部分。
 *
 * <p>含两个"重"接口：自动分配（{@link #autoAssign}）与设置教师班级
 * （{@link #setTeacherClasses}）。两者都先对租户行加写锁再操作。
 */
@Service
public class AssignmentService {

    private final TeacherAssignmentMapper assignmentMapper;
    private final TeacherMapper teacherMapper;
    private final ClassMapper classMapper;
    private final SubjectMapper subjectMapper;
    private final GradeSubjectMapper gradeSubjectMapper;
    private final OrganizationMapper organizationMapper;

    public AssignmentService(TeacherAssignmentMapper assignmentMapper,
                             TeacherMapper teacherMapper,
                             ClassMapper classMapper,
                             SubjectMapper subjectMapper,
                             GradeSubjectMapper gradeSubjectMapper,
                             OrganizationMapper organizationMapper) {
        this.assignmentMapper = assignmentMapper;
        this.teacherMapper = teacherMapper;
        this.classMapper = classMapper;
        this.subjectMapper = subjectMapper;
        this.gradeSubjectMapper = gradeSubjectMapper;
        this.organizationMapper = organizationMapper;
    }

    /** 列表（可按教师/班级过滤）。 */
    public List<AssignmentResponse> list(long organizationId, Long teacherId, Long classId) {
        var wrapper = Wrappers.<TeacherAssignment>lambdaQuery()
                .eq(TeacherAssignment::getOrganizationId, organizationId);
        if (teacherId != null && teacherId != 0L) {
            wrapper.eq(TeacherAssignment::getTeacherId, teacherId);
        }
        if (classId != null && classId != 0L) {
            wrapper.eq(TeacherAssignment::getClassId, classId);
        }
        wrapper.orderByAsc(TeacherAssignment::getId);
        return toResponses(wrapper);
    }

    @Transactional
    public AssignmentResponse create(long organizationId, AssignmentCreateRequest request) {
        Teacher teacher = requireOwned(teacherMapper, Teacher::getId, Teacher::getOrganizationId,
                request.teacherId(), organizationId, "老师不存在");
        Clazz clazz = requireOwned(classMapper, Clazz::getId, Clazz::getOrganizationId,
                request.classId(), organizationId, "班级不存在");
        Subject subject = requireOwned(subjectMapper, Subject::getId, Subject::getOrganizationId,
                request.subjectId(), organizationId, "科目不存在");
        if (!Objects.equals(teacher.getSubjectId(), subject.getId())) {
            throw BusinessException.badRequest("老师与科目不匹配");
        }
        TeacherAssignment assignment = new TeacherAssignment();
        assignment.setOrganizationId(organizationId);
        assignment.setTeacherId(request.teacherId());
        assignment.setClassId(request.classId());
        assignment.setSubjectId(request.subjectId());
        assignmentMapper.insert(assignment);
        return new AssignmentResponse(assignment.getId(), assignment.getTeacherId(),
                assignment.getClassId(), assignment.getSubjectId(),
                teacher.getName(), clazz.getName(), subject.getName());
    }

    @Transactional
    public void delete(long organizationId, long assignmentId) {
        TeacherAssignment assignment = assignmentMapper.selectOne(
                Wrappers.<TeacherAssignment>lambdaQuery()
                        .eq(TeacherAssignment::getId, assignmentId)
                        .eq(TeacherAssignment::getOrganizationId, organizationId));
        if (assignment == null) {
            throw BusinessException.notFound("分配不存在");
        }
        assignmentMapper.deleteById(assignment.getId());
    }

    /**
     * 自动分配教师。
     *
     * <p><b>算法逐句照搬 Python</b>（{@code routers/assignments.py:104-194}），
     * 包括排序键与选人规则 —— 因为它的输出（哪些班分到哪位老师）是可观察的行为，
     * 改了任何一处都会与 Python 产生不同结果。要点：
     * <ol>
     *   <li>先对租户行加写锁，避免两个管理员并发决策</li>
     *   <li>把「缺教师的（班, 科目）」组成任务，按**周课时降序 → 班级 id → 科目 id** 排序
     *       （先满足课时多的，避免大科目被挤掉）</li>
     *   <li>候选人 = 该科目的教师中「已有负载 + 本次课时 ≤ 教师上限」的</li>
     *   <li>选负载率（{@code 负载 / max(上限,1)}）最低的；并列时取负载低的，再并列取 id 小的</li>
     *   <li>已存在分配所对应的课时会**先计入负载**（{@code preserved}），所以不是从零开始排</li>
     * </ol>
     */
    @Transactional
    public AssignmentAutoResponse autoAssign(long organizationId, AssignmentAutoRequest request) {
        boolean replaceExisting = Boolean.TRUE.equals(request.replaceExisting());
        List<Long> subjectIds = request.subjectIds() == null ? List.of() : request.subjectIds();

        // ① 租户级写锁
        organizationMapper.lockById(organizationId);

        List<Clazz> classes = classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                .eq(Clazz::getOrganizationId, organizationId));
        List<Teacher> teachers = teacherMapper.selectList(Wrappers.<Teacher>lambdaQuery()
                .eq(Teacher::getOrganizationId, organizationId));
        Map<Long, Subject> subjects = subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                        .eq(Subject::getOrganizationId, organizationId))
                .stream().collect(Collectors.toMap(Subject::getId, Function.identity()));

        Set<Long> subjectScope = new HashSet<>(subjectIds);
        if (!subjectScope.isEmpty() && !subjects.keySet().containsAll(subjectScope)) {
            throw BusinessException.badRequest("包含不存在或不属于当前学校的科目");
        }

        var requirementQuery = Wrappers.<GradeSubject>lambdaQuery()
                .eq(GradeSubject::getOrganizationId, organizationId);
        if (!subjectScope.isEmpty()) {
            requirementQuery.in(GradeSubject::getSubjectId, subjectScope);
        }
        List<GradeSubject> requirements = gradeSubjectMapper.selectList(requirementQuery);

        if (replaceExisting) {
            var deleteQuery = Wrappers.<TeacherAssignment>lambdaQuery()
                    .eq(TeacherAssignment::getOrganizationId, organizationId);
            if (!subjectScope.isEmpty()) {
                deleteQuery.in(TeacherAssignment::getSubjectId, subjectScope);
            }
            assignmentMapper.delete(deleteQuery);
        }

        List<TeacherAssignment> existing = assignmentMapper.selectList(
                Wrappers.<TeacherAssignment>lambdaQuery()
                        .eq(TeacherAssignment::getOrganizationId, organizationId));

        Map<Long, Clazz> classesById = classes.stream()
                .collect(Collectors.toMap(Clazz::getId, Function.identity()));
        // ⚠ 用复合键 (grade_id, subject_id)：同一对只会有一条（DB 有唯一约束）
        Map<String, Integer> hoursByGradeSubject = requirements.stream()
                .collect(Collectors.toMap(
                        r -> key(r.getGradeId(), r.getSubjectId()),
                        GradeSubject::getWeeklyHours,
                        (a, b) -> a));
        Set<String> assignedKeys = existing.stream()
                .map(a -> key(a.getClassId(), a.getSubjectId()))
                .collect(Collectors.toSet());

        Map<Long, Integer> loads = new HashMap<>();
        teachers.forEach(t -> loads.put(t.getId(), 0));
        for (TeacherAssignment assignment : existing) {
            Clazz clazz = classesById.get(assignment.getClassId());
            if (clazz != null) {
                int hours = hoursByGradeSubject.getOrDefault(
                        key(clazz.getGradeId(), assignment.getSubjectId()), 0);
                loads.merge(assignment.getTeacherId(), hours, Integer::sum);
            }
        }

        Map<Long, List<Teacher>> teachersBySubject = new HashMap<>();
        for (Teacher teacher : teachers) {
            teachersBySubject.computeIfAbsent(teacher.getSubjectId(), k -> new ArrayList<>()).add(teacher);
        }

        record Job(int weeklyHours, Clazz clazz, Long subjectId) {
        }
        List<Job> jobs = new ArrayList<>();
        for (Clazz clazz : classes) {
            for (GradeSubject requirement : requirements) {
                if (Objects.equals(requirement.getGradeId(), clazz.getGradeId())
                        && !assignedKeys.contains(key(clazz.getId(), requirement.getSubjectId()))) {
                    jobs.add(new Job(requirement.getWeeklyHours(), clazz, requirement.getSubjectId()));
                }
            }
        }
        // Python: jobs.sort(key=lambda item: (-item[0], item[1].id, item[2]))
        jobs.sort(Comparator.comparingInt(Job::weeklyHours).reversed()
                .thenComparing(job -> job.clazz().getId())
                .thenComparing(Job::subjectId));

        int assignedCount = 0;
        List<String> unassigned = new ArrayList<>();
        for (Job job : jobs) {
            int weeklyHours = job.weeklyHours();
            List<Teacher> candidates = teachersBySubject.getOrDefault(job.subjectId(), List.of())
                    .stream()
                    .filter(t -> loads.getOrDefault(t.getId(), 0) + weeklyHours
                            <= t.getMaxWeeklyHours())
                    .toList();
            if (candidates.isEmpty()) {
                Subject subject = subjects.get(job.subjectId());
                String subjectName = subject != null ? subject.getName() : "科目" + job.subjectId();
                unassigned.add(job.clazz().getName() + "－" + subjectName
                        + "（需要 " + weeklyHours + " 节/周）");
                continue;
            }
            Teacher chosen = candidates.stream()
                    .min(Comparator
                            .comparingDouble((Teacher t) -> loads.getOrDefault(t.getId(), 0)
                                    / (double) Math.max(t.getMaxWeeklyHours(), 1))
                            .thenComparingInt(t -> loads.getOrDefault(t.getId(), 0))
                            .thenComparing(Teacher::getId))
                    .orElseThrow();

            TeacherAssignment assignment = new TeacherAssignment();
            assignment.setOrganizationId(organizationId);
            assignment.setTeacherId(chosen.getId());
            assignment.setClassId(job.clazz().getId());
            assignment.setSubjectId(job.subjectId());
            assignmentMapper.insert(assignment);

            loads.merge(chosen.getId(), weeklyHours, Integer::sum);
            assignedCount++;
        }

        Map<String, Integer> teacherLoads = new LinkedHashMap<>();
        for (Teacher teacher : teachers) {
            teacherLoads.put(teacher.getName(), loads.getOrDefault(teacher.getId(), 0));
        }
        return new AssignmentAutoResponse(assignedCount, existing.size(), unassigned, teacherLoads);
    }

    /**
     * 设置某教师负责的班级（覆盖式）。
     *
     * <p>三条校验，文案逐字对齐 Python：
     * <ul>
     *   <li>400 {@code 包含不存在或不属于当前学校的班级}</li>
     *   <li>400 {@code 这些班级所属年级没有开设该科目：A、B}</li>
     *   <li>400 {@code 所选班级合计 N 节/周，超过教师上限 M 节/周}</li>
     * </ul>
     *
     * <p>写操作会先把该教师的旧分配全删，再把选中班级**从别的教师手里抢过来**
     * （保证一个班的某科目只有一位负责教师），最后按 class_id 升序重建。
     *
     * <p>返回该教师的新分配列表。注意这是**裸数组**而不是 {@code {items,total}} ——
     * §9 的分页只适用于 GET 列表接口；写操作返回的是"操作后的状态"，不分页。
     */
    @Transactional
    public List<AssignmentResponse> setTeacherClasses(long organizationId, long teacherId,
                                                      TeacherClassAssignmentsRequest request) {
        organizationMapper.lockById(organizationId);

        Teacher teacher = teacherMapper.selectOne(Wrappers.<Teacher>lambdaQuery()
                .eq(Teacher::getId, teacherId)
                .eq(Teacher::getOrganizationId, organizationId));
        if (teacher == null) {
            throw BusinessException.notFound("教师不存在");
        }

        Set<Long> requestedIds = new HashSet<>(request.classIds());
        List<Clazz> ownedClasses = requestedIds.isEmpty() ? List.of()
                : classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                        .eq(Clazz::getOrganizationId, organizationId)
                        .in(Clazz::getId, requestedIds));
        if (!ownedClasses.stream().map(Clazz::getId).collect(Collectors.toSet()).equals(requestedIds)) {
            throw BusinessException.badRequest("包含不存在或不属于当前学校的班级");
        }

        Map<Long, Integer> offeredHours = gradeSubjectMapper.selectList(
                        Wrappers.<GradeSubject>lambdaQuery()
                                .eq(GradeSubject::getOrganizationId, organizationId)
                                .eq(GradeSubject::getSubjectId, teacher.getSubjectId())
                                .gt(GradeSubject::getWeeklyHours, 0))
                .stream()
                .collect(Collectors.toMap(GradeSubject::getGradeId, GradeSubject::getWeeklyHours,
                        (a, b) -> a));

        List<String> invalidClasses = ownedClasses.stream()
                .filter(c -> !offeredHours.containsKey(c.getGradeId()))
                .map(Clazz::getName)
                .toList();
        if (!invalidClasses.isEmpty()) {
            throw BusinessException.badRequest(
                    "这些班级所属年级没有开设该科目：" + String.join("、", invalidClasses));
        }
        int totalHours = ownedClasses.stream()
                .mapToInt(c -> offeredHours.getOrDefault(c.getGradeId(), 0))
                .sum();
        if (totalHours > teacher.getMaxWeeklyHours()) {
            throw BusinessException.badRequest("所选班级合计 " + totalHours + " 节/周，超过教师上限 "
                    + teacher.getMaxWeeklyHours() + " 节/周");
        }

        assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
                .eq(TeacherAssignment::getOrganizationId, organizationId)
                .eq(TeacherAssignment::getTeacherId, teacher.getId()));
        if (!requestedIds.isEmpty()) {
            assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
                    .eq(TeacherAssignment::getOrganizationId, organizationId)
                    .eq(TeacherAssignment::getSubjectId, teacher.getSubjectId())
                    .in(TeacherAssignment::getClassId, requestedIds));
            for (Long classId : requestedIds.stream().sorted().toList()) {
                TeacherAssignment assignment = new TeacherAssignment();
                assignment.setOrganizationId(organizationId);
                assignment.setTeacherId(teacher.getId());
                assignment.setClassId(classId);
                assignment.setSubjectId(teacher.getSubjectId());
                assignmentMapper.insert(assignment);
            }
        }
        return list(organizationId, teacher.getId(), null);
    }

    // ------------------------------------------------------------------ 工具

    private static String key(Long gradeOrClassId, Long subjectId) {
        return gradeOrClassId + ":" + subjectId;
    }

    /** 批量补上 teacher_name / class_name / subject_name（Python 用 joinedload）。 */
    private List<AssignmentResponse> toResponses(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TeacherAssignment> wrapper) {
        List<TeacherAssignment> rows = assignmentMapper.selectList(wrapper);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> teacherNames = names(rows.stream().map(TeacherAssignment::getTeacherId).toList(),
                ids -> teacherMapper.selectList(Wrappers.<Teacher>lambdaQuery()
                        .select(Teacher::getId, Teacher::getName).in(Teacher::getId, ids)),
                Teacher::getId, Teacher::getName);
        Map<Long, String> classNames = names(rows.stream().map(TeacherAssignment::getClassId).toList(),
                ids -> classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                        .select(Clazz::getId, Clazz::getName).in(Clazz::getId, ids)),
                Clazz::getId, Clazz::getName);
        Map<Long, String> subjectNames = names(rows.stream().map(TeacherAssignment::getSubjectId).toList(),
                ids -> subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                        .select(Subject::getId, Subject::getName).in(Subject::getId, ids)),
                Subject::getId, Subject::getName);

        return rows.stream()
                .map(a -> new AssignmentResponse(a.getId(), a.getTeacherId(), a.getClassId(),
                        a.getSubjectId(), teacherNames.get(a.getTeacherId()),
                        classNames.get(a.getClassId()), subjectNames.get(a.getSubjectId())))
                .toList();
    }

    private static <T> Map<Long, String> names(Collection<Long> rawIds,
                                               Function<List<Long>, List<T>> loader,
                                               Function<T, Long> idOf,
                                               Function<T, String> nameOf) {
        List<Long> ids = rawIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids).stream()
                .collect(Collectors.toMap(idOf, nameOf, (a, b) -> a));
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
