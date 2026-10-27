package com.orbit.scheduling.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.orbit.common.error.BusinessException;
import com.orbit.scheduling.audit.ScheduleAuditLog;
import com.orbit.scheduling.audit.ScheduleAuditLogMapper;
import com.orbit.scheduling.schedule.ScheduleEntity;
import com.orbit.scheduling.schedule.ScheduleEntityMapper;
import com.orbit.scheduling.schedule.ScheduleStateService;
import com.orbit.scheduling.schedule.SchedulingState;
import com.orbit.scheduling.security.SchedulePrincipal;
import com.orbit.scheduling.version.ScheduleVersion;
import com.orbit.scheduling.version.ScheduleVersionEntry;
import com.orbit.scheduling.version.ScheduleVersionEntryMapper;
import com.orbit.scheduling.version.ScheduleVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储运维 —— 对应 Python {@code routers/system.py}（3 个接口）。
 *
 * <p><b>为什么这组接口在 scheduling 而不是 academic</b>（§13.1 原稿把它划给 academic）：
 * <ol>
 *   <li>两个写接口操作的 4 张表（{@code schedules} / {@code schedule_audit_logs} /
 *       {@code schedule_version_entries} / {@code schedule_versions}）**全部**是
 *       scheduling 属主 —— 放 academic 就违反 §3 铁律 2</li>
 *   <li>三条路径共用 {@code /api/system/storage} 前缀，拆进程后**无法按路径分流**到两个服务</li>
 * </ol>
 * 读全部表不违反铁律 2（只禁止跨服务**写**），但为路由一致性整组一起走。
 *
 * <p>⚠ 三个接口都要求**管理员**（对应 Python 的 {@code require_admin}）。
 */
@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    /** Python 的清理确认串，逐字一致。 */
    public static final String CLEAR_CONFIRM_VALUE = "CLEAR_SCHEDULES";

    /** 统计行数的表（与 {@link SystemStatsMapper#tableCounts} 的 UNION 一一对应）。 */
    public static final List<String> COUNTED_TABLES = List.of(
            "users", "grades", "classes", "subjects", "teachers",
            "grade_subjects", "teacher_assignments", "fixed_slots", "grade_fixed_slots",
            "schedules", "schedule_versions", "schedule_version_entries", "schedule_audit_logs");

    /** 可以 {@code OPTIMIZE TABLE} 的表（Python 的固定列表，逐字一致）。 */
    public static final List<String> OPTIMIZABLE_TABLES = List.of(
            "schedule_audit_logs", "schedule_version_entries", "schedule_versions", "schedules",
            "grade_fixed_slots", "fixed_slots", "teacher_assignments", "grade_subjects",
            "teachers", "subjects", "classes", "grades", "users");

    private final SystemStatsMapper statsMapper;
    private final ScheduleStateService stateService;
    private final ScheduleEntityMapper scheduleMapper;
    private final ScheduleAuditLogMapper auditMapper;
    private final ScheduleVersionEntryMapper entryMapper;
    private final ScheduleVersionMapper versionMapper;
    private final StringRedisTemplate redis;
    private final String databaseHost;
    private final int databasePort;
    private final String databaseName;

    public SystemService(SystemStatsMapper statsMapper,
                         ScheduleStateService stateService,
                         ScheduleEntityMapper scheduleMapper,
                         ScheduleAuditLogMapper auditMapper,
                         ScheduleVersionEntryMapper entryMapper,
                         ScheduleVersionMapper versionMapper,
                         StringRedisTemplate redis,
                         @Value("${MYSQL_HOST:127.0.0.1}") String databaseHost,
                         @Value("${MYSQL_PORT:3306}") int databasePort,
                         @Value("${MYSQL_DATABASE:orbit_schedule}") String databaseName) {
        this.statsMapper = statsMapper;
        this.stateService = stateService;
        this.scheduleMapper = scheduleMapper;
        this.auditMapper = auditMapper;
        this.entryMapper = entryMapper;
        this.versionMapper = versionMapper;
        this.redis = redis;
        this.databaseHost = databaseHost;
        this.databasePort = databasePort;
        this.databaseName = databaseName;
    }

    /**
     * 存储概览。
     *
     * <p>⚠ {@code database_type} 的值是 **{@code "mysql"}（小写）** —— Python 取的是
     * {@code engine.dialect.name}。写成 {@code "MySQL"} 会让契约对不上（实测过）。
     *
     * <p>{@code database_path} 在 MySQL 下装的其实是**连接描述**
     * （{@code host:port/db}），字段名有误导性但**不能改**（前端在用）。
     *
     * <p>⚠ Java 版**不支持 SQLite**（方案 §4.2 只做 MySQL），所以这两个字段恒为 MySQL 形态。
     */
    public Map<String, Object> storage(long organizationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database_type", "mysql");
        result.put("database_path", databaseHost + ":" + databasePort + "/" + databaseName);
        result.put("database_size_bytes", statsMapper.databaseSizeBytes(databaseName));

        Map<String, Long> counts = new LinkedHashMap<>();
        // 先按固定表序铺 0，保证**没有行的表也出现在结果里**（Python 的字典也是这样）
        for (String table : COUNTED_TABLES) {
            counts.put(table, 0L);
        }
        for (Map<String, Object> row : statsMapper.tableCounts(organizationId)) {
            Object table = row.get("table_name");
            Object count = row.get("row_count");
            if (table instanceof String name && count instanceof Number n) {
                counts.put(name, n.longValue());
            }
        }
        result.put("counts", counts);
        result.put("redis", redisStatus());
        return result;
    }

    /**
     * 清空已生成的课表。
     *
     * <p>删四张表：{@code schedules}（当前课表投影）、{@code schedule_audit_logs}、
     * {@code schedule_version_entries}、{@code schedule_versions}。
     * 返回被删掉的 {@code schedules} 行数（Python 的 {@code deleted} 就是这个数）。
     *
     * <p>⚠ 删的顺序**必须**是子表在前：{@code schedules} 与 {@code audit_logs} 都引用了
     * {@code schedule_versions}... 准确说 audit/entries 有 ON DELETE CASCADE，
     * 但 {@code schedules} 没有外键指向版本。Python 的顺序是
     * schedules → audit → entries → versions，这里照做。
     */
    @Transactional
    public Map<String, Object> clearSchedules(long organizationId) {
        ensureNotRunning(organizationId);
        long deleted = scheduleMapper.delete(Wrappers.<ScheduleEntity>lambdaQuery()
                .eq(ScheduleEntity::getOrganizationId, organizationId));
        auditMapper.delete(Wrappers.<ScheduleAuditLog>lambdaQuery()
                .eq(ScheduleAuditLog::getOrganizationId, organizationId));
        entryMapper.delete(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getOrganizationId, organizationId));
        versionMapper.delete(Wrappers.<ScheduleVersion>lambdaQuery()
                .eq(ScheduleVersion::getOrganizationId, organizationId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("deleted", deleted);
        return result;
    }

    /**
     * 整理空间（MySQL 的 {@code OPTIMIZE TABLE}）。
     *
     * <p>⚠ 与 SQLite 的 {@code VACUUM} 不同，MySQL 这里是**全库级**操作，
     * 所以 Python 要求**平台管理员**（普通管理员 → 403
     * {@code MySQL 全库空间整理仅限平台管理员}）。
     */
    public Map<String, Object> compact(SchedulePrincipal principal) {
        ensureNotRunning(principal.organizationId());
        if (!"platform_admin".equals(principal.role())) {
            throw BusinessException.forbidden("MySQL 全库空间整理仅限平台管理员");
        }
        for (String table : OPTIMIZABLE_TABLES) {
            try {
                statsMapper.optimizeTable(table);
            } catch (Exception e) {
                // 单表失败不该让整个操作崩掉（权限、表被锁等都可能），但要留痕
                log.warn("OPTIMIZE TABLE {} 失败: {}", table, e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("database_size_bytes", statsMapper.databaseSizeBytes(databaseName));
        return result;
    }

    /** 排课进行中不允许做空间维护 —— Python 文案：{@code 排课进行中，不能执行空间维护}。 */
    private void ensureNotRunning(long organizationId) {
        SchedulingState state = stateService.findByOrganization(organizationId);
        if (state != null && "running".equals(state.getStatus())) {
            throw BusinessException.conflict("排课进行中，不能执行空间维护");
        }
    }

    /** Redis 状态（对应 Python 的 {@code redis_status()}）。 */
    private Map<String, Object> redisStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> info = redis.execute((RedisConnection connection) -> {
                java.util.Properties properties = connection.serverCommands().info("memory");
                Map<String, Object> map = new LinkedHashMap<>();
                if (properties != null) {
                    properties.forEach((k, v) -> map.put(String.valueOf(k), v));
                }
                return map;
            });
            Object used = info == null ? null : info.get("used_memory");
            result.put("available", true);
            result.put("used_memory", used instanceof Number n ? n.longValue() : 0L);
            result.put("used_memory_human",
                    info == null ? "0B" : String.valueOf(info.getOrDefault("used_memory_human", "0B")));
        } catch (Exception e) {
            // 与 Python 一致：Redis 不可用时只报不可用，不影响整个接口
            result.put("available", false);
        }
        return result;
    }
}
