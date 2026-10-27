package com.orbit.scheduling.validate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.GradeRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.snapshot.TeacherRef;
import com.orbit.scheduling.version.ScheduleVersion;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课表版本的**独立校验器** —— 对应 Python {@code _validate_version}。
 *
 * <p>它在两个地方被调用：<b>发布前</b>（确保发布出去的课表是合法的）与
 * <b>每次换课/拖动后</b>（确保拖动没有制造冲突）。这是"排课结果正确性"的最后一道闸门 ——
 * 求解器可能因为 bug 或数据变化产出非法结果，这里不信任求解器，独立重算一遍。
 *
 * <p>两条规则：
 * <ol>
 *   <li>每条课都要落在**该班所在年级当天**的课时范围内 →
 *       409 {@code 存在超出班级上课时段的课程（记录 N）}</li>
 *   <li>同一位教师在同一时段不能出现在多个班 →
 *       409 {@code X老师在周N第M节存在班级冲突}</li>
 * </ol>
 *
 * <p>注意它**读的是 academic 属主的 classes / grades / teachers 表** ——
 * 与鉴权同理，读不违反铁律 2，但拆库时要么走接口、要么用快照
 * （方案 §3 铁律 3「排课输入用快照」正是为此准备的）。
 */
@Component
public class VersionValidator {

    /** 对应 Python 的 {@code DAY_KEYS}，下标即 {@code day_of_week}。 */
    public static final List<String> DAY_KEYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri");

    private final ScheduleVersionEntryMapper entryMapper;
    private final SnapshotMappers.ClassRefMapper classRefMapper;
    private final SnapshotMappers.GradeRefMapper gradeRefMapper;
    private final SnapshotMappers.TeacherRefMapper teacherRefMapper;

    public VersionValidator(ScheduleVersionEntryMapper entryMapper,
                            SnapshotMappers.ClassRefMapper classRefMapper,
                            SnapshotMappers.GradeRefMapper gradeRefMapper,
                            SnapshotMappers.TeacherRefMapper teacherRefMapper) {
        this.entryMapper = entryMapper;
        this.classRefMapper = classRefMapper;
        this.gradeRefMapper = gradeRefMapper;
        this.teacherRefMapper = teacherRefMapper;
    }

    /** 校验并返回该版本的全部条目（Python 也把条目返回给调用方复用）。 */
    public List<ScheduleVersionEntry> validate(ScheduleVersion version) {
        List<ScheduleVersionEntry> entries = entryMapper.selectList(
                Wrappers.<ScheduleVersionEntry>lambdaQuery()
                        .eq(ScheduleVersionEntry::getVersionId, version.getId()));

        Map<Long, ClassRef> classes = new java.util.LinkedHashMap<>();
        for (ClassRef item : classRefMapper.selectList(Wrappers.<ClassRef>lambdaQuery()
                .eq(ClassRef::getOrganizationId, version.getOrganizationId()))) {
            classes.put(item.getId(), item);
        }
        Map<Long, GradeRef> grades = new java.util.LinkedHashMap<>();
        for (GradeRef item : gradeRefMapper.selectList(Wrappers.<GradeRef>lambdaQuery()
                .eq(GradeRef::getOrganizationId, version.getOrganizationId()))) {
            grades.put(item.getId(), item);
        }

        // key = (teacher_id, day, period) → 该时段被排了课的班级 id 列表
        //
        // ⚠ 必须是 LinkedHashMap（保持遍历顺序 = 条目的读取顺序），不能用 HashMap：
        //    下面"报第一个冲突"的行为**依赖遍历顺序**。Python 的 dict 是插入序，
        //    HashMap 是无序的 —— 结果是两边都正确地拒绝了这次换课，
        //    却报了**不同的冲突**（实测：Python 报"王老师在周1第2节"，Java 报"钱老师在周1第1节"），
        //    响应体不一致。同一个输入必须给出同一个答案。
        Map<String, List<Long>> teacherSlots = new java.util.LinkedHashMap<>();
        for (ScheduleVersionEntry entry : entries) {
            ClassRef clazz = classes.get(entry.getClassId());
            GradeRef grade = clazz == null ? null : grades.get(clazz.getGradeId());
            Integer dayOfWeek = entry.getDayOfWeek();
            String dayKey = (dayOfWeek != null && dayOfWeek >= 0 && dayOfWeek < DAY_KEYS.size())
                    ? DAY_KEYS.get(dayOfWeek) : null;
            int periods = (grade == null || grade.getPeriodsPerDay() == null || dayKey == null)
                    ? 0 : grade.getPeriodsPerDay().getOrDefault(dayKey, 0);
            if (grade == null || dayKey == null || entry.getPeriodIndex() == null
                    || entry.getPeriodIndex() < 0 || entry.getPeriodIndex() >= periods) {
                throw BusinessException.conflict("存在超出班级上课时段的课程（记录 " + entry.getId() + "）");
            }
            teacherSlots.computeIfAbsent(
                    entry.getTeacherId() + ":" + entry.getDayOfWeek() + ":" + entry.getPeriodIndex(),
                    k -> new java.util.ArrayList<>()).add(entry.getClassId());
        }

        for (Map.Entry<String, List<Long>> slot : teacherSlots.entrySet()) {
            if (slot.getValue().size() <= 1) {
                continue;
            }
            String[] parts = slot.getKey().split(":");
            long teacherId = Long.parseLong(parts[0]);
            int day = Integer.parseInt(parts[1]);
            int period = Integer.parseInt(parts[2]);
            TeacherRef teacher = teacherRefMapper.selectById(teacherId);
            String teacherName = teacher == null ? "教师" : teacher.getName();
            // Python: f"{teacher.name}在周{day + 1}第{period + 1}节存在班级冲突"
            throw BusinessException.conflict(
                    teacherName + "在周" + (day + 1) + "第" + (period + 1) + "节存在班级冲突");
        }
        return entries;
    }
}
