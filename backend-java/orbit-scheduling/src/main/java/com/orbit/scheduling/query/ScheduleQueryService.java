package com.orbit.scheduling.query;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.schedule.ScheduleEntity;
import com.orbit.scheduling.schedule.ScheduleEntityMapper;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.snapshot.SubjectRef;
import com.orbit.scheduling.snapshot.TeacherRef;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import com.orbit.scheduling.version.VersionService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 课表查询 —— 对应 Python {@code _schedule_query} + class/teacher/grade 三个接口。
 *
 * <h2>{@code version_id} 决定查哪张表（Python 的设计，原样保留）</h2>
 * <ul>
 *   <li><b>不带</b> {@code version_id} → 查 {@code schedules}（**当前已发布**的课表）</li>
 *   <li><b>带</b> {@code version_id} → 查 {@code schedule_version_entries}（指定版本的明细）</li>
 * </ul>
 * 所以同一个接口在"看已发布课表"和"看某版草稿"时读的是不同的表。
 * 带 {@code version_id} 时会先校验该版本属于本组织（否则 404）——
 * 这一步不能省，它是**租户隔离**的一部分：不加就能用别人的 version_id 读到别人的课表。
 */
@Service
public class ScheduleQueryService {

    private final ScheduleEntityMapper scheduleEntityMapper;
    private final ScheduleVersionEntryMapper entryMapper;
    private final VersionService versionService;
    private final SnapshotMappers.ClassRefMapper classRefMapper;
    private final SnapshotMappers.SubjectRefMapper subjectRefMapper;
    private final SnapshotMappers.TeacherRefMapper teacherRefMapper;

    public ScheduleQueryService(ScheduleEntityMapper scheduleEntityMapper,
                                ScheduleVersionEntryMapper entryMapper,
                                VersionService versionService,
                                SnapshotMappers.ClassRefMapper classRefMapper,
                                SnapshotMappers.SubjectRefMapper subjectRefMapper,
                                SnapshotMappers.TeacherRefMapper teacherRefMapper) {
        this.scheduleEntityMapper = scheduleEntityMapper;
        this.entryMapper = entryMapper;
        this.versionService = versionService;
        this.classRefMapper = classRefMapper;
        this.subjectRefMapper = subjectRefMapper;
        this.teacherRefMapper = teacherRefMapper;
    }

    /** 某个班的课表。 */
    public List<ScheduleResponse> byClass(long organizationId, long classId, Long versionId) {
        if (versionId == null) {
            return enrich(scheduleEntityMapper.selectList(Wrappers.<ScheduleEntity>lambdaQuery()
                    .eq(ScheduleEntity::getOrganizationId, organizationId)
                    .eq(ScheduleEntity::getClassId, classId)), organizationId);
        }
        versionService.requireOwned(organizationId, versionId);
        return enrich(entryMapper.selectList(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                .eq(ScheduleVersionEntry::getVersionId, versionId)
                .eq(ScheduleVersionEntry::getClassId, classId)), organizationId);
    }

    /** 某位教师的课表。 */
    public List<ScheduleResponse> byTeacher(long organizationId, long teacherId, Long versionId) {
        if (versionId == null) {
            return enrich(scheduleEntityMapper.selectList(Wrappers.<ScheduleEntity>lambdaQuery()
                    .eq(ScheduleEntity::getOrganizationId, organizationId)
                    .eq(ScheduleEntity::getTeacherId, teacherId)), organizationId);
        }
        versionService.requireOwned(organizationId, versionId);
        return enrich(entryMapper.selectList(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                .eq(ScheduleVersionEntry::getVersionId, versionId)
                .eq(ScheduleVersionEntry::getTeacherId, teacherId)), organizationId);
    }

    /**
     * 某个年级的课表（该年级下所有班）。
     *
     * <p>Python 是 {@code join(Class)} 过滤；这里先取出该年级的班级 id 再 {@code IN} ——
     * 效果相同，而且不用在 Mapper 上写联表。
     */
    public List<ScheduleResponse> byGrade(long organizationId, long gradeId, Long versionId) {
        List<Long> classIds = classRefMapper.selectList(Wrappers.<ClassRef>lambdaQuery()
                        .select(ClassRef::getId)
                        .eq(ClassRef::getOrganizationId, organizationId)
                        .eq(ClassRef::getGradeId, gradeId))
                .stream().map(ClassRef::getId).toList();
        if (classIds.isEmpty()) {
            return List.of();
        }
        if (versionId == null) {
            return enrich(scheduleEntityMapper.selectList(Wrappers.<ScheduleEntity>lambdaQuery()
                    .eq(ScheduleEntity::getOrganizationId, organizationId)
                    .in(ScheduleEntity::getClassId, classIds)), organizationId);
        }
        versionService.requireOwned(organizationId, versionId);
        return enrich(entryMapper.selectList(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                .eq(ScheduleVersionEntry::getVersionId, versionId)
                .in(ScheduleVersionEntry::getClassId, classIds)), organizationId);
    }

    /**
     * 补上名字。
     *
     * <p>两种入参（{@code ScheduleEntity} 与 {@code ScheduleVersionEntry}）字段完全一样，
     * 用一个小的适配函数统一，避免写两遍。
     */
    private <T> List<ScheduleResponse> enrich(List<T> rows, long organizationId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> subjects = nameMap(subjectRefMapper.selectList(
                        Wrappers.<SubjectRef>lambdaQuery().eq(SubjectRef::getOrganizationId, organizationId)),
                SubjectRef::getId, SubjectRef::getName);
        Map<Long, String> teachers = nameMap(teacherRefMapper.selectList(
                        Wrappers.<TeacherRef>lambdaQuery().eq(TeacherRef::getOrganizationId, organizationId)),
                TeacherRef::getId, TeacherRef::getName);
        Map<Long, String> classes = nameMap(classRefMapper.selectList(
                        Wrappers.<ClassRef>lambdaQuery().eq(ClassRef::getOrganizationId, organizationId)),
                ClassRef::getId, ClassRef::getName);

        List<ScheduleResponse> result = new java.util.ArrayList<>(rows.size());
        for (T row : rows) {
            ScheduleRow view = ScheduleRow.of(row);
            result.add(new ScheduleResponse(view.id(), view.classId(), view.subjectId(),
                    view.teacherId(), view.dayOfWeek(), view.periodIndex(),
                    // Python: .get(id, "") —— 找不到时是空串
                    subjects.getOrDefault(view.subjectId(), ""),
                    teachers.getOrDefault(view.teacherId(), ""),
                    classes.getOrDefault(view.classId(), "")));
        }
        return result;
    }

    private static <T> Map<Long, String> nameMap(List<T> rows,
                                                 Function<T, Long> idOf,
                                                 Function<T, String> nameOf) {
        return rows.stream().filter(r -> idOf.apply(r) != null)
                .collect(Collectors.toMap(idOf, nameOf, (a, b) -> a, HashMap::new));
    }

    /** 把两种"课表行"适配成同一个视图，供 {@link #enrich} 统一处理。 */
    private record ScheduleRow(Long id, Long classId, Long subjectId, Long teacherId,
                               Integer dayOfWeek, Integer periodIndex) {

        static ScheduleRow of(Object row) {
            if (row instanceof ScheduleEntity e) {
                return new ScheduleRow(e.getId(), e.getClassId(), e.getSubjectId(),
                        e.getTeacherId(), e.getDayOfWeek(), e.getPeriodIndex());
            }
            if (row instanceof ScheduleVersionEntry e) {
                return new ScheduleRow(e.getId(), e.getClassId(), e.getSubjectId(),
                        e.getTeacherId(), e.getDayOfWeek(), e.getPeriodIndex());
            }
            throw BusinessException.badRequest("未知的课表行类型: " + Objects.toString(row));
        }
    }
}
