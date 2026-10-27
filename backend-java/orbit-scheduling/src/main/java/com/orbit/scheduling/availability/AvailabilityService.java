package com.orbit.scheduling.availability;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.GradeRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.snapshot.SubjectRef;
import com.orbit.scheduling.snapshot.TeacherRef;
import com.orbit.scheduling.validate.VersionValidator;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import com.orbit.scheduling.version.VersionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** P5 资源空闲查询。所有结论都相对于调用方显式传入的课表版本。 */
@Service
public class AvailabilityService {

    private final VersionService versionService;
    private final ScheduleVersionEntryMapper entryMapper;
    private final SnapshotMappers.TeacherRefMapper teacherMapper;
    private final SnapshotMappers.ClassRefMapper classMapper;
    private final SnapshotMappers.GradeRefMapper gradeMapper;
    private final SnapshotMappers.SubjectRefMapper subjectMapper;

    public AvailabilityService(VersionService versionService,
                               ScheduleVersionEntryMapper entryMapper,
                               SnapshotMappers.TeacherRefMapper teacherMapper,
                               SnapshotMappers.ClassRefMapper classMapper,
                               SnapshotMappers.GradeRefMapper gradeMapper,
                               SnapshotMappers.SubjectRefMapper subjectMapper) {
        this.versionService = versionService;
        this.entryMapper = entryMapper;
        this.teacherMapper = teacherMapper;
        this.classMapper = classMapper;
        this.gradeMapper = gradeMapper;
        this.subjectMapper = subjectMapper;
    }

    public AvailabilityResponse teacher(long organizationId, long teacherId,
                                        long versionId, Integer day, Integer period) {
        versionService.requireOwned(organizationId, versionId);
        requireTeacher(organizationId, teacherId);
        int dayValue = requireDay(day);
        int periodValue = requirePeriod(period, maxPeriods(organizationId, dayValue));
        ScheduleVersionEntry entry = firstEntry(versionId, organizationId,
                teacherId, null, dayValue, periodValue);
        return response("teacher", teacherId, versionId, dayValue, periodValue,
                entry, organizationId);
    }

    public AvailabilityResponse schoolClass(long organizationId, long classId,
                                            long versionId, Integer day, Integer period) {
        versionService.requireOwned(organizationId, versionId);
        ClassRef schoolClass = requireClass(organizationId, classId);
        int dayValue = requireDay(day);
        GradeRef grade = gradeMapper.selectOne(Wrappers.<GradeRef>lambdaQuery()
                .eq(GradeRef::getId, schoolClass.getGradeId())
                .eq(GradeRef::getOrganizationId, organizationId));
        int periodValue = requirePeriod(period, periods(grade, dayValue));
        ScheduleVersionEntry entry = firstEntry(versionId, organizationId,
                null, classId, dayValue, periodValue);
        return response("class", classId, versionId, dayValue, periodValue,
                entry, organizationId);
    }

    public FreeSlotsResponse teacherFreeSlots(long organizationId, long teacherId,
                                              long versionId, Integer day) {
        versionService.requireOwned(organizationId, versionId);
        requireTeacher(organizationId, teacherId);
        int dayValue = requireDay(day);
        int periodsInDay = maxPeriods(organizationId, dayValue);
        if (periodsInDay < 1) {
            throw BusinessException.badRequest("该天没有可用课时");
        }

        List<ScheduleVersionEntry> entries = entryMapper.selectList(
                Wrappers.<ScheduleVersionEntry>lambdaQuery()
                        .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                        .eq(ScheduleVersionEntry::getVersionId, versionId)
                        .eq(ScheduleVersionEntry::getTeacherId, teacherId)
                        .eq(ScheduleVersionEntry::getDayOfWeek, dayValue));
        entries.sort(Comparator.comparing(ScheduleVersionEntry::getPeriodIndex));

        Map<Integer, AvailabilityResponse.OccupiedBy> occupied = new HashMap<>();
        for (ScheduleVersionEntry entry : entries) {
            occupied.putIfAbsent(entry.getPeriodIndex(), occupiedBy(entry, organizationId));
        }
        List<Integer> free = new ArrayList<>();
        for (int period = 0; period < periodsInDay; period++) {
            if (!occupied.containsKey(period)) {
                free.add(period);
            }
        }
        List<AvailabilityResponse.OccupiedBy> occupiedList = occupied.values().stream()
                .sorted(Comparator.comparing(AvailabilityResponse.OccupiedBy::periodIndex))
                .toList();
        return new FreeSlotsResponse(versionId, teacherId, dayValue, periodsInDay,
                List.copyOf(free), occupiedList);
    }

    private AvailabilityResponse response(String resourceType, long resourceId,
                                          long versionId, int day, int period,
                                          ScheduleVersionEntry entry, long organizationId) {
        return new AvailabilityResponse(entry == null, versionId, resourceType, resourceId,
                day, period, entry == null ? null : occupiedBy(entry, organizationId));
    }

    private AvailabilityResponse.OccupiedBy occupiedBy(ScheduleVersionEntry entry,
                                                       long organizationId) {
        ClassRef schoolClass = classMapper.selectOne(Wrappers.<ClassRef>lambdaQuery()
                .eq(ClassRef::getId, entry.getClassId())
                .eq(ClassRef::getOrganizationId, organizationId));
        SubjectRef subject = subjectMapper.selectOne(Wrappers.<SubjectRef>lambdaQuery()
                .eq(SubjectRef::getId, entry.getSubjectId())
                .eq(SubjectRef::getOrganizationId, organizationId));
        TeacherRef teacher = teacherMapper.selectOne(Wrappers.<TeacherRef>lambdaQuery()
                .eq(TeacherRef::getId, entry.getTeacherId())
                .eq(TeacherRef::getOrganizationId, organizationId));
        return new AvailabilityResponse.OccupiedBy(
                entry.getId(), entry.getDayOfWeek(), entry.getPeriodIndex(),
                entry.getClassId(), schoolClass == null ? "" : schoolClass.getName(),
                entry.getSubjectId(), subject == null ? "" : subject.getName(),
                entry.getTeacherId(), teacher == null ? "" : teacher.getName());
    }

    private ScheduleVersionEntry firstEntry(long versionId, long organizationId,
                                            Long teacherId, Long classId,
                                            int day, int period) {
        var query = Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                .eq(ScheduleVersionEntry::getVersionId, versionId)
                .eq(ScheduleVersionEntry::getDayOfWeek, day)
                .eq(ScheduleVersionEntry::getPeriodIndex, period);
        if (teacherId != null) {
            query.eq(ScheduleVersionEntry::getTeacherId, teacherId);
        }
        if (classId != null) {
            query.eq(ScheduleVersionEntry::getClassId, classId);
        }
        return entryMapper.selectList(query).stream().findFirst().orElse(null);
    }

    private TeacherRef requireTeacher(long organizationId, long teacherId) {
        TeacherRef teacher = teacherMapper.selectOne(Wrappers.<TeacherRef>lambdaQuery()
                .eq(TeacherRef::getId, teacherId)
                .eq(TeacherRef::getOrganizationId, organizationId));
        if (teacher == null) {
            throw BusinessException.notFound("老师不存在");
        }
        return teacher;
    }

    private ClassRef requireClass(long organizationId, long classId) {
        ClassRef schoolClass = classMapper.selectOne(Wrappers.<ClassRef>lambdaQuery()
                .eq(ClassRef::getId, classId)
                .eq(ClassRef::getOrganizationId, organizationId));
        if (schoolClass == null) {
            throw BusinessException.notFound("班级不存在");
        }
        return schoolClass;
    }

    private int maxPeriods(long organizationId, int day) {
        return gradeMapper.selectList(Wrappers.<GradeRef>lambdaQuery()
                        .eq(GradeRef::getOrganizationId, organizationId))
                .stream().mapToInt(grade -> periods(grade, day)).max().orElse(0);
    }

    private static int requireDay(Integer day) {
        if (day == null || day < 0 || day >= VersionValidator.DAY_KEYS.size()) {
            throw BusinessException.badRequest("day 必须在 0 到 4 之间");
        }
        return day;
    }

    private static int requirePeriod(Integer period, int periodsInDay) {
        if (period == null || period < 0 || period >= periodsInDay) {
            throw BusinessException.badRequest("period 不在当天的上课时间内");
        }
        return period;
    }

    private static int periods(GradeRef grade, int day) {
        if (grade == null || grade.getPeriodsPerDay() == null) {
            return 0;
        }
        return grade.getPeriodsPerDay()
                .getOrDefault(VersionValidator.DAY_KEYS.get(day), 0);
    }
}
