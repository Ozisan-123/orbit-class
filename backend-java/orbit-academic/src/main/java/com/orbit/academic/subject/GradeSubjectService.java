package com.orbit.academic.subject;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 年级课时（{@code grade_subjects}）业务逻辑 ——
 * 对应 Python {@code routers/assignments.py} 里的 {@code /grade-subjects} 四个接口。
 *
 * <p>为什么和科目放一个包：{@code grade_subjects} 表达的是「**某科目**在某年级的周课时」，
 * 属主是科目领域；实体与 Mapper 也在这里。
 */
@Service
public class GradeSubjectService {

    private final GradeSubjectMapper gradeSubjectMapper;
    private final SubjectMapper subjectMapper;
    private final GradeMapper gradeMapper;

    public GradeSubjectService(GradeSubjectMapper gradeSubjectMapper,
                               SubjectMapper subjectMapper,
                               GradeMapper gradeMapper) {
        this.gradeSubjectMapper = gradeSubjectMapper;
        this.subjectMapper = subjectMapper;
        this.gradeMapper = gradeMapper;
    }

    public PageResponse<GradeSubjectResponse> list(long organizationId, Long gradeId, PageQuery query) {
        var wrapper = Wrappers.<GradeSubject>lambdaQuery()
                .eq(GradeSubject::getOrganizationId, organizationId);
        if (gradeId != null && gradeId != 0L) {
            wrapper.eq(GradeSubject::getGradeId, gradeId);
        }
        wrapper.orderByAsc(GradeSubject::getId);

        Page<GradeSubject> page = gradeSubjectMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        return PageResponse.of(toResponses(page.getRecords()), page.getTotal());
    }

    /**
     * 新建或更新一条年级课时（**upsert**）。
     *
     * <p>Python 的行为是：已存在 (grade, subject) 就改 {@code weekly_hours}，否则新建 ——
     * 所以同一个请求重复提交是幂等的。
     */
    @Transactional
    public GradeSubjectResponse upsert(long organizationId, GradeSubjectCreateRequest request) {
        requireGradeOwned(organizationId, request.gradeId());
        requireSubjectOwned(organizationId, request.subjectId());

        GradeSubject existing = gradeSubjectMapper.selectOne(Wrappers.<GradeSubject>lambdaQuery()
                .eq(GradeSubject::getOrganizationId, organizationId)
                .eq(GradeSubject::getGradeId, request.gradeId())
                .eq(GradeSubject::getSubjectId, request.subjectId()));
        GradeSubject entity;
        if (existing != null) {
            existing.setWeeklyHours(request.weeklyHours());
            gradeSubjectMapper.updateById(existing);
            entity = existing;
        } else {
            entity = new GradeSubject();
            entity.setOrganizationId(organizationId);
            entity.setGradeId(request.gradeId());
            entity.setSubjectId(request.subjectId());
            entity.setWeeklyHours(request.weeklyHours());
            gradeSubjectMapper.insert(entity);
        }
        return toResponses(List.of(entity)).get(0);
    }

    /**
     * 批量更新某科目在各年级的周课时。
     *
     * <p>{@code weeklyHours == 0} 表示**删除**该年级的记录（不是设为 0）。
     * 返回更新后该科目的全部记录（**裸数组** —— 写操作返回状态，不分页）。
     */
    @Transactional
    public List<GradeSubjectResponse> bulkUpdate(long organizationId, GradeSubjectBulkRequest request) {
        requireSubjectOwned(organizationId, request.subjectId());

        Set<Long> gradeIds = request.items().stream()
                .map(GradeSubjectBulkRequest.Item::gradeId)
                .collect(Collectors.toSet());
        Set<Long> ownedGradeIds = gradeIds.isEmpty() ? Set.of()
                : gradeMapper.selectList(Wrappers.<Grade>lambdaQuery()
                        .select(Grade::getId)
                        .eq(Grade::getOrganizationId, organizationId)
                        .in(Grade::getId, gradeIds))
                .stream().map(Grade::getId).collect(Collectors.toSet());
        if (!gradeIds.equals(ownedGradeIds)) {
            throw BusinessException.badRequest("包含不存在或不属于当前学校的年级");
        }

        Map<Long, GradeSubject> existing = gradeSubjectMapper.selectList(
                        Wrappers.<GradeSubject>lambdaQuery()
                                .eq(GradeSubject::getOrganizationId, organizationId)
                                .eq(GradeSubject::getSubjectId, request.subjectId()))
                .stream().collect(Collectors.toMap(GradeSubject::getGradeId, gs -> gs, (a, b) -> a));

        for (GradeSubjectBulkRequest.Item item : request.items()) {
            GradeSubject current = existing.get(item.gradeId());
            if (item.weeklyHours() == 0) {
                if (current != null) {
                    gradeSubjectMapper.deleteById(current.getId());
                }
            } else if (current != null) {
                current.setWeeklyHours(item.weeklyHours());
                gradeSubjectMapper.updateById(current);
            } else {
                GradeSubject created = new GradeSubject();
                created.setOrganizationId(organizationId);
                created.setGradeId(item.gradeId());
                created.setSubjectId(request.subjectId());
                created.setWeeklyHours(item.weeklyHours());
                gradeSubjectMapper.insert(created);
            }
        }
        // Python 返回的是「该科目全部年级课时」，不按 grade_id 过滤
        return allForSubject(organizationId, request.subjectId());
    }

    @Transactional
    public void delete(long organizationId, long gradeSubjectId) {
        GradeSubject entity = gradeSubjectMapper.selectOne(Wrappers.<GradeSubject>lambdaQuery()
                .eq(GradeSubject::getId, gradeSubjectId)
                .eq(GradeSubject::getOrganizationId, organizationId));
        if (entity == null) {
            throw BusinessException.notFound("不存在");
        }
        gradeSubjectMapper.deleteById(entity.getId());
    }

    /** 某科目的全部年级课时（裸数组），供批量更新后返回。 */
    public List<GradeSubjectResponse> allForSubject(long organizationId, Long subjectId) {
        return toResponses(gradeSubjectMapper.selectList(Wrappers.<GradeSubject>lambdaQuery()
                .eq(GradeSubject::getOrganizationId, organizationId)
                .eq(GradeSubject::getSubjectId, subjectId)
                .orderByAsc(GradeSubject::getId)));
    }

    // ------------------------------------------------------------------ 工具

    private List<GradeSubjectResponse> toResponses(Collection<GradeSubject> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Long> subjectIds = rows.stream().map(GradeSubject::getSubjectId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> subjectNames = subjectIds.isEmpty() ? Map.of()
                : subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                        .select(Subject::getId, Subject::getName)
                        .in(Subject::getId, subjectIds))
                .stream().collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
        return rows.stream()
                .map(gs -> new GradeSubjectResponse(gs.getId(), gs.getGradeId(), gs.getSubjectId(),
                        gs.getWeeklyHours(), subjectNames.get(gs.getSubjectId())))
                .toList();
    }

    private void requireGradeOwned(long organizationId, Long gradeId) {
        if (gradeId == null || gradeMapper.selectCount(Wrappers.<Grade>lambdaQuery()
                .eq(Grade::getId, gradeId)
                .eq(Grade::getOrganizationId, organizationId)) == 0) {
            throw BusinessException.notFound("年级不存在");
        }
    }

    private void requireSubjectOwned(long organizationId, Long subjectId) {
        if (subjectId == null || subjectMapper.selectCount(Wrappers.<Subject>lambdaQuery()
                .eq(Subject::getId, subjectId)
                .eq(Subject::getOrganizationId, organizationId)) == 0) {
            throw BusinessException.notFound("科目不存在");
        }
    }
}
