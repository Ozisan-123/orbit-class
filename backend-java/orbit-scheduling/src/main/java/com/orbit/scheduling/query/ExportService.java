package com.orbit.scheduling.query;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.scheduling.schedule.ScheduleEntity;
import com.orbit.scheduling.schedule.ScheduleEntityMapper;
import com.orbit.scheduling.snapshot.ClassRef;
import com.orbit.scheduling.snapshot.SnapshotMappers;
import com.orbit.scheduling.snapshot.SubjectRef;
import com.orbit.scheduling.snapshot.TeacherRef;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import com.orbit.scheduling.version.VersionService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 课表导出为 xlsx —— 对应 Python {@code routers/export.py}（openpyxl）。
 *
 * <h2>版式（逐项对齐 Python）</h2>
 * <ul>
 *   <li>**每个班一个工作表**，表名 = 班级名（截断到 31 字符 —— Excel 的硬限制）</li>
 *   <li>第一行表头：{@code 节次 | 周一..周五}</li>
 *   <li>之后每天一行（行首 {@code 第N节}），单元格内容是 {@code 科目名\n教师名}，空位是空串</li>
 *   <li>行数 = 该班**最大节次 + 1**（各班可以不同 —— 不同年级每天节数不同）</li>
 * </ul>
 *
 * <h2>两处与 openpyxl 的实现差异（结果一致，做法不同）</h2>
 * <ol>
 *   <li>openpyxl 新建工作簿会自带一个空的 {@code Sheet}，所以要显式删掉；
 *       **POI 的 {@code XSSFWorkbook} 新建时一个工作表都没有**，不用删。</li>
 *   <li>openpyxl 的 {@code create_sheet} 允许先建后改；POI 的 {@code createSheet(name)}
 *       在工作表名重复时会抛异常。这里依赖"班级名在组织内唯一"这个既有约束
 *       （{@code uq_class_org_name}），所以不会撞上。</li>
 * </ol>
 */
@Service
public class ExportService {

    /** 与 Python 的 {@code day_names} 逐字一致。 */
    private static final List<String> DAY_NAMES = List.of("周一", "周二", "周三", "周四", "周五");

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String FILENAME = "schedule.xlsx";

    private final ScheduleEntityMapper scheduleMapper;
    private final ScheduleVersionEntryMapper entryMapper;
    private final VersionService versionService;
    private final SnapshotMappers.ClassRefMapper classMapper;
    private final SnapshotMappers.SubjectRefMapper subjectMapper;
    private final SnapshotMappers.TeacherRefMapper teacherMapper;

    public ExportService(ScheduleEntityMapper scheduleMapper,
                         ScheduleVersionEntryMapper entryMapper,
                         VersionService versionService,
                         SnapshotMappers.ClassRefMapper classMapper,
                         SnapshotMappers.SubjectRefMapper subjectMapper,
                         SnapshotMappers.TeacherRefMapper teacherMapper) {
        this.scheduleMapper = scheduleMapper;
        this.entryMapper = entryMapper;
        this.versionService = versionService;
        this.classMapper = classMapper;
        this.subjectMapper = subjectMapper;
        this.teacherMapper = teacherMapper;
    }

    /** 导出的二进制内容 + 响应头。 */
    public record ExportFile(byte[] content, String contentType, String contentDisposition) {
    }

    /**
     * 一行课表数据 —— 统一 {@code schedules} 与版本明细两种来源的形状。
     *
     * <p>⚠ 名字**不能**叫 {@code Row}：那样会和 POI 的 {@code org.apache.poi.ss.usermodel.Row}
     * 撞名（同一文件里两个 Row，编译器会挑本类的那个，
     * 于是 {@code sheet.createRow()} 的返回值无法赋给 local 变量）。实测踩过一次。
     */
    private record ScheduleRow(long classId, long subjectId, long teacherId,
                               int dayOfWeek, int periodIndex) {
    }

    /**
     * 生成 xlsx。
     *
     * @param versionId 为空 → 导出**当前已发布**的课表（{@code schedules} 表）；
     *                  否则导出该版本的明细（会先校验版本属于本组织，否则 404）
     */
    public ExportFile export(long organizationId, Long versionId) {
        List<ScheduleRow> rows;
        if (versionId == null) {
            rows = scheduleMapper.selectList(Wrappers.<ScheduleEntity>lambdaQuery()
                            .eq(ScheduleEntity::getOrganizationId, organizationId)
                            .orderByAsc(ScheduleEntity::getClassId)
                            .orderByAsc(ScheduleEntity::getDayOfWeek)
                            .orderByAsc(ScheduleEntity::getPeriodIndex))
                    .stream()
                    .map(e -> new ScheduleRow(e.getClassId(), e.getSubjectId(), e.getTeacherId(),
                            e.getDayOfWeek(), e.getPeriodIndex()))
                    .toList();
        } else {
            // ⚠ 不能省：不加这一步就能用别人的 version_id 导出别人的课表（租户隔离）
            versionService.requireOwned(organizationId, versionId);
            rows = entryMapper.selectList(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                            .eq(ScheduleVersionEntry::getVersionId, versionId)
                            .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
                            .orderByAsc(ScheduleVersionEntry::getClassId)
                            .orderByAsc(ScheduleVersionEntry::getDayOfWeek)
                            .orderByAsc(ScheduleVersionEntry::getPeriodIndex))
                    .stream()
                    .map(e -> new ScheduleRow(e.getClassId(), e.getSubjectId(), e.getTeacherId(),
                            e.getDayOfWeek(), e.getPeriodIndex()))
                    .toList();
        }

        Map<Long, String> subjects = names(subjectMapper.selectList(
                        Wrappers.<SubjectRef>lambdaQuery().eq(SubjectRef::getOrganizationId, organizationId)),
                SubjectRef::getId, SubjectRef::getName);
        Map<Long, String> teachers = names(teacherMapper.selectList(
                        Wrappers.<TeacherRef>lambdaQuery().eq(TeacherRef::getOrganizationId, organizationId)),
                TeacherRef::getId, TeacherRef::getName);
        Map<Long, String> classes = names(classMapper.selectList(
                        Wrappers.<ClassRef>lambdaQuery().eq(ClassRef::getOrganizationId, organizationId)),
                ClassRef::getId, ClassRef::getName);

        // 按班分组；TreeMap 保证**按 class_id 升序**建表（Python: sorted(by_class)）
        Map<Long, List<ScheduleRow>> byClass = new TreeMap<>();
        for (ScheduleRow row : rows) {
            byClass.computeIfAbsent(row.classId(), k -> new ArrayList<>()).add(row);
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Map.Entry<Long, List<ScheduleRow>> entry : byClass.entrySet()) {
                long classId = entry.getKey();
                List<ScheduleRow> classRows = entry.getValue();
                String sheetName = classes.getOrDefault(classId, "班级" + classId);
                Sheet sheet = workbook.createSheet(truncate(sheetName));

                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("节次");
                for (int day = 0; day < DAY_NAMES.size(); day++) {
                    header.createCell(day + 1).setCellValue(DAY_NAMES.get(day));
                }

                int maxPeriod = classRows.stream().mapToInt(ScheduleRow::periodIndex).max().orElse(-1) + 1;
                for (int period = 0; period < maxPeriod; period++) {
                    Row excelRow = sheet.createRow(period + 1);
                    excelRow.createCell(0).setCellValue("第" + (period + 1) + "节");
                    for (int day = 0; day < DAY_NAMES.size(); day++) {
                        ScheduleRow found = find(classRows, day, period);
                        if (found == null) {
                            // ⚠ 空位**不创建单元格**，而不是写空字符串。
                            //    openpyxl 写 "" 时不会真正生成单元格，读回来是 None；
                            //    POI 写 "" 会生成一个空字符串单元格，读回来是 ""。
                            //    两者在 Excel 界面里长得一样，但**程序读出来 None ≠ ""** ——
                            //    实测就是这么发现差异的。不创建单元格两边才一致。
                            continue;
                        }
                        Cell cell = excelRow.createCell(day + 1);
                        // Python: f"{subject}\n{teacher}"（找不到名字时是空串，不是 null）
                        cell.setCellValue(subjects.getOrDefault(found.subjectId(), "") + "\n"
                                + teachers.getOrDefault(found.teacherId(), ""));
                    }
                }
            }
            workbook.write(out);
            return new ExportFile(out.toByteArray(), CONTENT_TYPE,
                    "attachment; filename=" + FILENAME);
        } catch (IOException e) {
            throw new IllegalStateException("导出课表失败", e);
        }
    }

    /** Excel 工作表名上限 31 字符（openpyxl 侧也是这么截的）。 */
    private static String truncate(String name) {
        return name.length() <= 31 ? name : name.substring(0, 31);
    }

    private static ScheduleRow find(List<ScheduleRow> rows, int day, int period) {
        for (ScheduleRow row : rows) {
            if (row.dayOfWeek() == day && row.periodIndex() == period) {
                return row;
            }
        }
        return null;
    }

    private static <T> Map<Long, String> names(List<T> rows,
                                               Function<T, Long> idOf,
                                               Function<T, String> nameOf) {
        Map<Long, String> result = new HashMap<>();
        for (T row : rows) {
            result.put(idOf.apply(row), nameOf.apply(row));
        }
        return result;
    }
}
