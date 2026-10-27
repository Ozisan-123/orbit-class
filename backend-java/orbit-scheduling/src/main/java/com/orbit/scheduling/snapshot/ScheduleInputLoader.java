package com.orbit.scheduling.snapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.solver.core.model.ScheduleInput;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 academic 的数据装配成 {@link ScheduleInput} —— 求解的**输入快照**。
 *
 * <p>对应 Python {@code scheduler/engine.py:generate_schedule} 开头那几条查询。
 * 放在 {@code scheduling.snapshot} 包下：按 README 的包归属，这个包的职责就是
 * 「**冻结并消费**排课输入快照」。
 *
 * <h2>⚠ 它是方案 §3 铁律 3「排课输入用快照」的落点</h2>
 * 现在实现是"现查一遍"（与 Python 一致，同一个库），但**接口已经收在这里**：
 * P3 拆分时只要把 {@link #load} 换成"调 academic 的接口一次性拉取并冻结"，
 * 调用方（求解链路）一行都不用改。
 *
 * <p>刻意只读 Python 引擎真正用到的 7 类数据 —— 不多读一样。
 * 多读会改变模型（比如把班级级 {@code fixed_slots} 也加进去），
 * 那就不是"等价迁移"而是"顺手改行为"了。
 */
@Component
public class ScheduleInputLoader {

    private final SnapshotMappers.GradeRefMapper gradeMapper;
    private final SnapshotMappers.ClassRefMapper classMapper;
    private final SnapshotMappers.SubjectRefMapper subjectMapper;
    private final SnapshotMappers.TeacherRefMapper teacherMapper;
    private final SnapshotMappers.AssignmentRefMapper assignmentMapper;
    private final SnapshotMappers.GradeSubjectRefMapper gradeSubjectMapper;
    private final SnapshotMappers.GradeFixedSlotRefMapper gradeFixedSlotMapper;

    public ScheduleInputLoader(SnapshotMappers.GradeRefMapper gradeMapper,
                               SnapshotMappers.ClassRefMapper classMapper,
                               SnapshotMappers.SubjectRefMapper subjectMapper,
                               SnapshotMappers.TeacherRefMapper teacherMapper,
                               SnapshotMappers.AssignmentRefMapper assignmentMapper,
                               SnapshotMappers.GradeSubjectRefMapper gradeSubjectMapper,
                               SnapshotMappers.GradeFixedSlotRefMapper gradeFixedSlotMapper) {
        this.gradeMapper = gradeMapper;
        this.classMapper = classMapper;
        this.subjectMapper = subjectMapper;
        this.teacherMapper = teacherMapper;
        this.assignmentMapper = assignmentMapper;
        this.gradeSubjectMapper = gradeSubjectMapper;
        this.gradeFixedSlotMapper = gradeFixedSlotMapper;
    }

    /** 拉取某一组织的全部排课输入。 */
    public ScheduleInput load(long organizationId) {
        List<ScheduleInput.GradeInfo> grades = gradeMapper.selectList(
                        Wrappers.<GradeRef>lambdaQuery().eq(GradeRef::getOrganizationId, organizationId))
                .stream()
                .map(g -> new ScheduleInput.GradeInfo(g.getId(), g.getName(), g.getPeriodsPerDay()))
                .toList();

        List<ScheduleInput.ClassInfo> classes = classMapper.selectList(
                        Wrappers.<ClassRef>lambdaQuery().eq(ClassRef::getOrganizationId, organizationId))
                .stream()
                .map(c -> new ScheduleInput.ClassInfo(c.getId(), c.getName(), c.getGradeId()))
                .toList();

        List<ScheduleInput.SubjectInfo> subjects = subjectMapper.selectList(
                        Wrappers.<SubjectRef>lambdaQuery().eq(SubjectRef::getOrganizationId, organizationId))
                .stream()
                .map(s -> new ScheduleInput.SubjectInfo(s.getId(), s.getName(), s.getCategory()))
                .toList();

        List<ScheduleInput.TeacherInfo> teachers = teacherMapper.selectList(
                        Wrappers.<TeacherRef>lambdaQuery().eq(TeacherRef::getOrganizationId, organizationId))
                .stream()
                // max_weekly_hours 在库里是 NOT NULL，但 MyBatis 可能给出 null（老数据），兜底 18（Python 的默认值）
                .map(t -> new ScheduleInput.TeacherInfo(t.getId(), t.getName(), t.getSubjectId(),
                        t.getMaxWeeklyHours() == null ? 18 : t.getMaxWeeklyHours()))
                .toList();

        // ⚠ 字段顺序刻意与 Python 的 all_assigns 元组 (class_id, subject_id, teacher_id) 一致
        List<ScheduleInput.Assignment> assignments = assignmentMapper.selectList(
                        Wrappers.<AssignmentRef>lambdaQuery().eq(AssignmentRef::getOrganizationId, organizationId))
                .stream()
                .map(a -> new ScheduleInput.Assignment(a.getClassId(), a.getSubjectId(), a.getTeacherId()))
                .toList();

        List<ScheduleInput.GradeSubjectInfo> gradeSubjects = gradeSubjectMapper.selectList(
                        Wrappers.<GradeSubjectRef>lambdaQuery().eq(GradeSubjectRef::getOrganizationId, organizationId))
                .stream()
                .map(gs -> new ScheduleInput.GradeSubjectInfo(gs.getGradeId(), gs.getSubjectId(),
                        gs.getWeeklyHours() == null ? 0 : gs.getWeeklyHours()))
                .toList();

        List<ScheduleInput.FixedSlotRule> gradeFixedSlots = gradeFixedSlotMapper.selectList(
                        Wrappers.<GradeFixedSlotRef>lambdaQuery()
                                .eq(GradeFixedSlotRef::getOrganizationId, organizationId))
                .stream()
                .map(f -> new ScheduleInput.FixedSlotRule(f.getGradeId(), f.getSubjectId(),
                        f.getDayOfWeek() == null ? 0 : f.getDayOfWeek(),
                        f.getPeriodIndex() == null ? 0 : f.getPeriodIndex()))
                .toList();

        return new ScheduleInput(organizationId, grades, classes, subjects, teachers,
                assignments, gradeSubjects, gradeFixedSlots);
    }
}
