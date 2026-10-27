package com.orbit.academic.clazz;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.academic.assignment.TeacherAssignment;
import com.orbit.academic.assignment.TeacherAssignmentMapper;
import com.orbit.academic.fixedslot.FixedSlot;
import com.orbit.academic.fixedslot.FixedSlotMapper;
import com.orbit.academic.grade.Grade;
import com.orbit.academic.grade.GradeMapper;
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

/** 班级业务逻辑。对应 Python {@code routers/classes.py}（4 个接口）。 */
@Service
public class ClassService {

    private static final Map<String, SFunction<Clazz, ?>> SORTABLE = Map.of(
            "id", Clazz::getId,
            "name", Clazz::getName,
            "grade_id", Clazz::getGradeId);

    private final ClassMapper classMapper;
    private final GradeMapper gradeMapper;
    private final TeacherAssignmentMapper assignmentMapper;
    private final FixedSlotMapper fixedSlotMapper;

    public ClassService(ClassMapper classMapper,
                        GradeMapper gradeMapper,
                        TeacherAssignmentMapper assignmentMapper,
                        FixedSlotMapper fixedSlotMapper) {
        this.classMapper = classMapper;
        this.gradeMapper = gradeMapper;
        this.assignmentMapper = assignmentMapper;
        this.fixedSlotMapper = fixedSlotMapper;
    }

    /**
     * 列表，可按年级过滤。
     *
     * <p>⚠ Python 的过滤条件写的是 {@code if grade_id:} —— **0 被当成"不过滤"**。
     * 这里逐字保持一致（`gradeId != 0`），而不是写成 `gradeId != null`：
     * 虽然 `grade_id=0` 现实中不会查出东西，但"行为等价"是 P1 的目标，
     * 这种一处一行的差异不值得为它开例外。
     */
    public PageResponse<ClassResponse> list(long organizationId, Long gradeId, PageQuery query) {
        LambdaQueryWrapper<Clazz> wrapper = Wrappers.<Clazz>lambdaQuery()
                .eq(Clazz::getOrganizationId, organizationId);
        if (gradeId != null && gradeId != 0L) {
            wrapper.eq(Clazz::getGradeId, gradeId);
        }
        if (query.hasKeyword()) {
            wrapper.like(Clazz::getName, query.keyword());
        }
        String column = query.orderColumn(SORTABLE.keySet(), "id");
        wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));

        Page<Clazz> page = classMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        List<Clazz> records = page.getRecords();
        Map<Long, String> gradeNames = gradeNames(records.stream().map(Clazz::getGradeId).toList());
        return PageResponse.of(
                records.stream()
                        .map(c -> ClassResponse.of(c, gradeNames.get(c.getGradeId())))
                        .toList(),
                page.getTotal());
    }

    @Transactional
    public ClassResponse create(long organizationId, ClassCreateRequest request) {
        requireGradeOwned(organizationId, request.gradeId());
        Clazz clazz = new Clazz();
        clazz.setOrganizationId(organizationId);
        clazz.setName(request.name());
        clazz.setGradeId(request.gradeId());
        classMapper.insert(clazz);
        return ClassResponse.of(clazz, gradeName(clazz.getGradeId()));
    }

    @Transactional
    public ClassResponse update(long organizationId, long classId, ClassUpdateRequest request) {
        Clazz clazz = requireOwned(organizationId, classId);
        if (request.name() != null) {
            clazz.setName(request.name());
        }
        if (request.gradeId() != null) {
            requireGradeOwned(organizationId, request.gradeId());
            clazz.setGradeId(request.gradeId());
        }
        classMapper.updateById(clazz);
        return ClassResponse.of(clazz, gradeName(clazz.getGradeId()));
    }

    /**
     * 删除班级，并清理**属于 academic 的**子行。
     *
     * <p>Python 的 {@code Class} 上声明了三个 cascade：{@code assignments}（任教关系）、
     * {@code fixed_slots}（固定课）、{@code schedules}（当前课表）。
     * 前两个在 academic 属主范围内，这里照做；
     *
     * <p>⚠ <b>第三个 {@code schedules} 刻意不删</b> —— 它是 **scheduling 服务的表**
     * （见 README 包归属与方案 §3 铁律 2）。当前库里 {@code schedules} 是空表
     * （真正的课表在 {@code schedule_versions} / {@code schedule_versions_entries}），
     * 所以**今天不会有可观察的差异**；等 scheduling 做出来之后，
     * 清理这张"当前课表投影"应该是 scheduling 自己的事（组织级事件或接口调用）。
     * 这一条已记入方案 §15 的 P1 遗留。
     */
    @Transactional
    public void delete(long organizationId, long classId) {
        Clazz clazz = requireOwned(organizationId, classId);
        deleteOwnedChildren(organizationId, List.of(clazz.getId()));
        classMapper.deleteById(clazz.getId());
    }

    /**
     * 删除某年级下的所有班级（供年级删除时的级联调用）。
     *
     * <p>放在班级这一侧实现，而不是让 {@code GradeService} 直接操作班级的表 ——
     * 每张表只由它的属主领域读写，跨领域时通过对方的 Service。
     */
    @Transactional
    public void deleteAllInGrade(long organizationId, long gradeId) {
        List<Long> classIds = classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                        .select(Clazz::getId)
                        .eq(Clazz::getOrganizationId, organizationId)
                        .eq(Clazz::getGradeId, gradeId))
                .stream().map(Clazz::getId).toList();
        if (classIds.isEmpty()) {
            return;
        }
        deleteOwnedChildren(organizationId, classIds);
        classMapper.delete(Wrappers.<Clazz>lambdaQuery()
                .eq(Clazz::getOrganizationId, organizationId)
                .eq(Clazz::getGradeId, gradeId));
    }

    /** 删掉这些班级名下的任教关系与固定课（academic 属主）。 */
    private void deleteOwnedChildren(long organizationId, List<Long> classIds) {
        assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
                .eq(TeacherAssignment::getOrganizationId, organizationId)
                .in(TeacherAssignment::getClassId, classIds));
        fixedSlotMapper.delete(Wrappers.<FixedSlot>lambdaQuery()
                .eq(FixedSlot::getOrganizationId, organizationId)
                .in(FixedSlot::getClassId, classIds));
    }

    public Clazz requireOwned(long organizationId, long classId) {
        Clazz clazz = classMapper.selectOne(Wrappers.<Clazz>lambdaQuery()
                .eq(Clazz::getId, classId)
                .eq(Clazz::getOrganizationId, organizationId));
        if (clazz == null) {
            throw BusinessException.notFound("班级不存在");
        }
        return clazz;
    }

    /** 校验年级属于本组织，否则 404（Python 用 {@code owned_or_404}，文案「年级不存在」）。 */
    private void requireGradeOwned(long organizationId, Long gradeId) {
        if (gradeId == null || !gradeOwned(organizationId, gradeId)) {
            throw BusinessException.notFound("年级不存在");
        }
    }

    private boolean gradeOwned(long organizationId, Long gradeId) {
        return gradeMapper.selectCount(Wrappers.<Grade>lambdaQuery()
                .eq(Grade::getId, gradeId)
                .eq(Grade::getOrganizationId, organizationId)) > 0;
    }

    private String gradeName(Long gradeId) {
        return gradeNames(List.of(gradeId)).get(gradeId);
    }

    /** 批量取年级名，避免 N+1（Python 用 joinedload）。 */
    private Map<Long, String> gradeNames(Collection<Long> gradeIds) {
        List<Long> ids = gradeIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return gradeMapper.selectList(Wrappers.<Grade>lambdaQuery()
                        .select(Grade::getId, Grade::getName)
                        .in(Grade::getId, ids))
                .stream()
                .collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
    }
}
