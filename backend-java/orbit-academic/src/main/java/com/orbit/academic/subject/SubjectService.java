package com.orbit.academic.subject;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.academic.grade.Grade;
import com.orbit.academic.grade.GradeMapper;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 科目业务逻辑。
 *
 * <h2>租户隔离是显式写的，不靠拦截器</h2>
 * 每个方法都接收 {@code organizationId} 并**显式**加到查询条件里。
 * Python 侧也是这么写的（{@code filter(Subject.organization_id == user.organization_id)}）。
 *
 * <p>为什么不用 MyBatis-Plus 的租户插件自动拼条件：拦截器一旦被绕过
 * （写原生 SQL、或用了没走拦截器的 API）就**静默失去隔离**，
 * 而一个学校看到另一个学校的数据是严重事故。显式写在每个查询里，代码评审时看得见。
 */
@Service
public class SubjectService {

    /**
     * 允许的排序字段白名单。
     *
     * <p>用它把「用户传来的字符串」映射成**类型安全的列引用**，
     * 而不是把字符串拼进 SQL —— 后者是 SQL 注入的经典入口。
     * {@link PageQuery#orderColumn} 会校验白名单，不在其中直接 400。
     */
    private static final Map<String, SFunction<Subject, ?>> SORTABLE = Map.of(
            "id", Subject::getId,
            "name", Subject::getName,
            "category", Subject::getCategory);

    /** 未指定排序时的默认列。Python 侧没有 ORDER BY，实际返回的就是主键序。 */
    private static final String DEFAULT_SORT = "id";

    private final SubjectMapper subjectMapper;
    private final GradeSubjectMapper gradeSubjectMapper;
    private final GradeMapper gradeMapper;

    public SubjectService(SubjectMapper subjectMapper,
                          GradeSubjectMapper gradeSubjectMapper,
                          GradeMapper gradeMapper) {
        this.subjectMapper = subjectMapper;
        this.gradeSubjectMapper = gradeSubjectMapper;
        this.gradeMapper = gradeMapper;
    }

    /** 列表（带分页）。对应 Python {@code GET /api/subjects}，但**新增了分页**（§9.2）。 */
    public PageResponse<SubjectResponse> list(long organizationId, PageQuery query) {
        LambdaQueryWrapper<Subject> wrapper = Wrappers.<Subject>lambdaQuery()
                .eq(Subject::getOrganizationId, organizationId);
        if (query.hasKeyword()) {
            wrapper.like(Subject::getName, query.keyword());
        }
        String column = query.orderColumn(SORTABLE.keySet(), DEFAULT_SORT);
        // ⚠ 第二参是 isAsc，所以传 ascending() 而不是 desc() —— 传反了不会报错，只会排序方向相反
        wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));

        Page<Subject> page = subjectMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        return PageResponse.of(
                page.getRecords().stream().map(SubjectResponse::from).toList(),
                page.getTotal());
    }

    /**
     * 新增科目。对应 Python {@code POST /api/subjects}。
     *
     * <p>三件事在**一个事务**里：插入科目 → 校验年级归属 → 插入年级课时。
     * Python 侧失败时显式 {@code db.rollback()}；这里靠 {@code @Transactional} +
     * 异常传播自动回滚（{@link BusinessException} 是 RuntimeException，默认回滚）。
     */
    @Transactional
    public SubjectResponse create(long organizationId, SubjectCreateRequest request) {
        String name = request.name() == null ? "" : request.name().strip();

        Subject subject = new Subject();
        subject.setOrganizationId(organizationId);
        subject.setName(name);
        // Python: category: str = "minor"
        subject.setCategory(request.category() == null || request.category().isBlank()
                ? "minor" : request.category());
        subjectMapper.insert(subject);

        Map<Long, Integer> hoursByGrade = request.weeklyHoursByGrade();
        if (hoursByGrade != null && !hoursByGrade.isEmpty()) {
            ensureGradesOwnedBy(organizationId, hoursByGrade.keySet());
            for (Map.Entry<Long, Integer> entry : hoursByGrade.entrySet()) {
                Integer hours = entry.getValue();
                // Python: if weekly_hours > 0 —— 0 与负数不建关联行
                if (hours != null && hours > 0) {
                    GradeSubject link = new GradeSubject();
                    link.setOrganizationId(organizationId);
                    link.setGradeId(entry.getKey());
                    link.setSubjectId(subject.getId());
                    link.setWeeklyHours(hours);
                    gradeSubjectMapper.insert(link);
                }
            }
        }
        return SubjectResponse.from(subject);
    }

    /**
     * 删除科目。对应 Python {@code DELETE /api/subjects/{id}}。
     *
     * <p><b>必须先删 {@code grade_subjects} 的关联行</b>：
     * Python 侧 {@code Subject.grade_subjects} 上是
     * {@code cascade="all, delete-orphan"}，SQLAlchemy 会替它删掉子行；
     * 而数据库的 {@code grade_subjects_ibfk_3} 外键**没有 ON DELETE CASCADE**，
     * 直接删科目会被外键拦住、报一个难以理解的完整性错误。
     *
     * <p>⚠ 已知的、与 Python 一致的行为：如果该科目**已被教师引用**
     * （{@code teachers.subject_id}），删除会因外键失败。Python 侧同样会失败
     * （{@code Teacher.subject} 上没有 cascade，SQLAlchemy 会尝试把 {@code subject_id}
     * 置 NULL，而该列 NOT NULL）。两边都是 500，行为等价。
     * 更友好的做法（返回 409 + 人话提示）属于 P2 的前端改造范围，已记入 TODO。
     */
    @Transactional
    public void delete(long organizationId, long subjectId) {
        Subject subject = subjectMapper.selectOne(Wrappers.<Subject>lambdaQuery()
                .eq(Subject::getId, subjectId)
                .eq(Subject::getOrganizationId, organizationId));
        if (subject == null) {
            throw BusinessException.notFound("科目不存在");
        }
        gradeSubjectMapper.delete(Wrappers.<GradeSubject>lambdaQuery()
                .eq(GradeSubject::getSubjectId, subjectId));
        subjectMapper.deleteById(subjectId);
    }

    /**
     * 删除某年级下的全部「年级课时」记录（供年级删除时的级联调用）。
     *
     * <p>{@code grade_subjects} 由本科目领域属主，所以清理动作放在这里，
     * 而不是让 {@code GradeService} 直接操作这张表。
     */
    @Transactional
    public void deleteGradeSubjectsByGrade(long organizationId, long gradeId) {
        gradeSubjectMapper.delete(Wrappers.<GradeSubject>lambdaQuery()
                .eq(GradeSubject::getOrganizationId, organizationId)
                .eq(GradeSubject::getGradeId, gradeId));
    }

    /**
     * 校验这些年级 id 都属于当前学校。
     *
     * <p>对应 Python 的：
     * <pre>{@code
     * if grade_ids != owned_grade_ids:
     *     raise HTTPException(400, "包含不存在或不属于当前学校的年级")
     * }</pre>
     * 因为查询本身带 {@code IN (requested)}，找到的必然是请求集合的子集，
     * 所以「子集相等」等价于「请求的每一个都被找到」。
     */
    private void ensureGradesOwnedBy(long organizationId, Collection<Long> gradeIds) {
        Set<Long> requested = new HashSet<>(gradeIds);
        List<Grade> found = gradeMapper.selectList(Wrappers.<Grade>lambdaQuery()
                .select(Grade::getId)
                .eq(Grade::getOrganizationId, organizationId)
                .in(Grade::getId, requested));
        Set<Long> owned = found.stream().map(Grade::getId).collect(Collectors.toSet());
        if (!owned.containsAll(requested)) {
            throw BusinessException.badRequest("包含不存在或不属于当前学校的年级");
        }
    }
}
