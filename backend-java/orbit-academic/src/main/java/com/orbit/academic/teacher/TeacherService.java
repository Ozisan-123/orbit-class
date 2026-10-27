package com.orbit.academic.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.academic.assignment.TeacherAssignment;
import com.orbit.academic.assignment.TeacherAssignmentMapper;
import com.orbit.academic.subject.Subject;
import com.orbit.academic.subject.SubjectMapper;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 教师业务逻辑。对应 Python {@code routers/teachers.py}（4 个接口）。 */
@Service
public class TeacherService {

    /** Python 的 {@code TeacherCreate.max_weekly_hours} 默认值。 */
    public static final int DEFAULT_MAX_WEEKLY_HOURS = 18;

    private static final Map<String, SFunction<Teacher, ?>> SORTABLE = Map.of(
            "id", Teacher::getId,
            "name", Teacher::getName,
            "subject_id", Teacher::getSubjectId,
            "max_weekly_hours", Teacher::getMaxWeeklyHours);

    private final TeacherMapper teacherMapper;
    private final SubjectMapper subjectMapper;
    private final TeacherAssignmentMapper assignmentMapper;

    public TeacherService(TeacherMapper teacherMapper,
                          SubjectMapper subjectMapper,
                          TeacherAssignmentMapper assignmentMapper) {
        this.teacherMapper = teacherMapper;
        this.subjectMapper = subjectMapper;
        this.assignmentMapper = assignmentMapper;
    }

    public PageResponse<TeacherResponse> list(long organizationId, PageQuery query) {
        LambdaQueryWrapper<Teacher> wrapper = Wrappers.<Teacher>lambdaQuery()
                .eq(Teacher::getOrganizationId, organizationId);
        if (query.hasKeyword()) {
            wrapper.like(Teacher::getName, query.keyword());
        }
        String column = query.orderColumn(SORTABLE.keySet(), "id");
        wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));

        Page<Teacher> page = teacherMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        List<Teacher> records = page.getRecords();
        Map<Long, String> subjectNames =
                subjectNames(records.stream().map(Teacher::getSubjectId).toList());
        return PageResponse.of(
                records.stream()
                        .map(t -> TeacherResponse.of(t, subjectNames.get(t.getSubjectId())))
                        .toList(),
                page.getTotal());
    }

    @Transactional
    public TeacherResponse create(long organizationId, TeacherCreateRequest request) {
        requireSubjectOwned(organizationId, request.subjectId());
        Teacher teacher = new Teacher();
        teacher.setOrganizationId(organizationId);
        teacher.setName(request.name());
        teacher.setSubjectId(request.subjectId());
        teacher.setMaxWeeklyHours(request.maxWeeklyHours() == null
                ? DEFAULT_MAX_WEEKLY_HOURS : request.maxWeeklyHours());
        teacherMapper.insert(teacher);
        return TeacherResponse.of(teacher, subjectName(teacher.getSubjectId()));
    }

    @Transactional
    public TeacherResponse update(long organizationId, long teacherId, TeacherUpdateRequest request) {
        Teacher teacher = requireOwned(organizationId, teacherId);
        if (request.name() != null) {
            teacher.setName(request.name());
        }
        if (request.subjectId() != null) {
            requireSubjectOwned(organizationId, request.subjectId());
            teacher.setSubjectId(request.subjectId());
        }
        if (request.maxWeeklyHours() != null) {
            teacher.setMaxWeeklyHours(request.maxWeeklyHours());
        }
        teacherMapper.updateById(teacher);
        return TeacherResponse.of(teacher, subjectName(teacher.getSubjectId()));
    }

    /**
     * 删除教师。
     *
     * <p>Python 的 {@code Teacher} 上只有 {@code assignments} 一个 cascade，
     * 所以先删任教关系；{@code fixed_slots.teacher_id} 的外键**没有** cascade，
     * 如果该教师已被固定课引用，两边都会失败（Python 会尝试把外键置 NULL 而该列 NOT NULL）。
     * 行为等价，这里不额外加工。
     */
    @Transactional
    public void delete(long organizationId, long teacherId) {
        Teacher teacher = requireOwned(organizationId, teacherId);
        assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
                .eq(TeacherAssignment::getOrganizationId, organizationId)
                .eq(TeacherAssignment::getTeacherId, teacherId));
        teacherMapper.deleteById(teacher.getId());
    }

    public Teacher requireOwned(long organizationId, long teacherId) {
        Teacher teacher = teacherMapper.selectOne(Wrappers.<Teacher>lambdaQuery()
                .eq(Teacher::getId, teacherId)
                .eq(Teacher::getOrganizationId, organizationId));
        if (teacher == null) {
            throw BusinessException.notFound("老师不存在");
        }
        return teacher;
    }

    private void requireSubjectOwned(long organizationId, Long subjectId) {
        if (subjectId == null || subjectMapper.selectCount(Wrappers.<Subject>lambdaQuery()
                .eq(Subject::getId, subjectId)
                .eq(Subject::getOrganizationId, organizationId)) == 0) {
            throw BusinessException.notFound("科目不存在");
        }
    }

    private String subjectName(Long subjectId) {
        return subjectNames(List.of(subjectId)).get(subjectId);
    }

    private Map<Long, String> subjectNames(Collection<Long> subjectIds) {
        List<Long> ids = subjectIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                        .select(Subject::getId, Subject::getName)
                        .in(Subject::getId, ids))
                .stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
    }
}
