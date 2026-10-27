package com.orbit.scheduling.query;

import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.security.SchedulePrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课表查看接口 —— {@code /api/schedule/class|teacher|grade/{id}}（3 个）。
 *
 * <p>⚠ 三个都返回**裸数组**，**不分页** —— 这是 §9 明确标注的例外：
 * 一张课表天然有上限（一个班一周最多几十行），页面上的表格需要完整数据，
 * 而且前端 {@code Timetable} 组件直接吃数组。分页在这里只有坏处。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleQueryController {

    private final ScheduleQueryService queryService;
    private final ExportService exportService;

    public ScheduleQueryController(ScheduleQueryService queryService, ExportService exportService) {
        this.queryService = queryService;
        this.exportService = exportService;
    }

    @GetMapping("/class/{classId}")
    public List<ScheduleResponse> byClass(
            @PathVariable Long classId,
            @RequestParam(name = "version_id", required = false) Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        return queryService.byClass(requirePrincipal(principal).organizationId(), classId, versionId);
    }

    @GetMapping("/teacher/{teacherId}")
    public List<ScheduleResponse> byTeacher(
            @PathVariable Long teacherId,
            @RequestParam(name = "version_id", required = false) Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        return queryService.byTeacher(requirePrincipal(principal).organizationId(), teacherId, versionId);
    }

    @GetMapping("/grade/{gradeId}")
    public List<ScheduleResponse> byGrade(
            @PathVariable Long gradeId,
            @RequestParam(name = "version_id", required = false) Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        return queryService.byGrade(requirePrincipal(principal).organizationId(), gradeId, versionId);
    }

    /**
     * 导出课表为 xlsx。
     *
     * <p>⚠ 它**不是 JSON 接口** —— 返回的是二进制文件，
     * 所以不能用 {@code ApiExceptionHandler} 那套 JSON 错误体来类比，
     * 但出错时（版本不存在）仍然返回 JSON 的 404。
     *
     * <p>响应头逐字对齐 Python：
     * {@code Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet}
     * 与 {@code Content-Disposition: attachment; filename=schedule.xlsx}。
     * 前端靠前者识别文件类型、靠后者拿到下载文件名，两个都不能少。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "version_id", required = false) Long versionId,
            @AuthenticationPrincipal SchedulePrincipal principal) {
        ExportService.ExportFile file =
                exportService.export(requirePrincipal(principal).organizationId(), versionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, file.contentDisposition())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return principal;
    }
}
