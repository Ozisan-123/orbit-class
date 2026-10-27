package com.orbit.academic.grade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.academic.clazz.ClassService;
import com.orbit.academic.subject.SubjectService;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 年级业务逻辑。对应 Python {@code routers/grades.py}（4 个接口）。
 *
 * <p>删除年级时要连带清理班级与年级课时（Python 侧靠
 * {@code Grade.classes} / {@code Grade.grade_subjects} 两个
 * {@code cascade="all, delete-orphan"}）。这里**通过对方领域的 Service 清理**，
 * 而不是自己拿 ClassMapper / GradeSubjectMapper 直接删 ——
 * 每张表只由属主领域读写，跨领域走对方的接口。
 */
@Service
public class GradeService {

    /** Python 的 {@code GradeCreate.periods_per_day} 默认值。 */
    public static final Map<String, Integer> DEFAULT_PERIODS_PER_DAY = Map.of(
            "Mon", 6, "Tue", 6, "Wed", 6, "Thu", 6, "Fri", 6);

    private static final Map<String, SFunction<Grade, ?>> SORTABLE = Map.of(
            "id", Grade::getId,
            "name", Grade::getName);

    private final GradeMapper gradeMapper;
    private final ClassService classService;
    private final SubjectService subjectService;

    public GradeService(GradeMapper gradeMapper,
                        ClassService classService,
                        SubjectService subjectService) {
        this.gradeMapper = gradeMapper;
        this.classService = classService;
        this.subjectService = subjectService;
    }

    public PageResponse<GradeResponse> list(long organizationId, PageQuery query) {
        LambdaQueryWrapper<Grade> wrapper = Wrappers.<Grade>lambdaQuery()
                .eq(Grade::getOrganizationId, organizationId);
        if (query.hasKeyword()) {
            wrapper.like(Grade::getName, query.keyword());
        }
        String column = query.orderColumn(SORTABLE.keySet(), "id");
        wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));

        Page<Grade> page = gradeMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        return PageResponse.of(page.getRecords().stream().map(GradeResponse::from).toList(),
                page.getTotal());
    }

    @Transactional
    public GradeResponse create(long organizationId, GradeCreateRequest request) {
        Grade grade = new Grade();
        grade.setOrganizationId(organizationId);
        grade.setName(request.name());
        grade.setPeriodsPerDay(request.periodsPerDay() == null
                ? new LinkedHashMap<>(DEFAULT_PERIODS_PER_DAY)
                : request.periodsPerDay());
        gradeMapper.insert(grade);
        return GradeResponse.from(grade);
    }

    /**
     * 修改年级：**只改传了的字段**（PUT 路径、PATCH 语义）。
     *
     * <p>所以先查再改，而不是构造部分实体走 {@code updateById}：
     * 后者无法区分「把 periods_per_day 改成 null」与「不改它」，
     * 而 Python 的 {@code if data.periods_per_day is not None} 是能区分的。
     */
    @Transactional
    public GradeResponse update(long organizationId, long gradeId, GradeUpdateRequest request) {
        Grade grade = requireOwned(organizationId, gradeId);
        if (request.name() != null) {
            grade.setName(request.name());
        }
        if (request.periodsPerDay() != null) {
            grade.setPeriodsPerDay(request.periodsPerDay());
        }
        gradeMapper.updateById(grade);
        return GradeResponse.from(grade);
    }

    @Transactional
    public void delete(long organizationId, long gradeId) {
        Grade grade = requireOwned(organizationId, gradeId);
        // 对应 Python 的两个 cascade：班级（及其任教关系/固定课）、年级课时
        classService.deleteAllInGrade(organizationId, gradeId);
        subjectService.deleteGradeSubjectsByGrade(organizationId, gradeId);
        gradeMapper.deleteById(grade.getId());
    }

    /** 查本组织的年级，查不到 404（Python 文案：{@code 年级不存在}）。 */
    public Grade requireOwned(long organizationId, long gradeId) {
        Grade grade = gradeMapper.selectOne(Wrappers.<Grade>lambdaQuery()
                .eq(Grade::getId, gradeId)
                .eq(Grade::getOrganizationId, organizationId));
        if (grade == null) {
            throw BusinessException.notFound("年级不存在");
        }
        return grade;
    }
}
