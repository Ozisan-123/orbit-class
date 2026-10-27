package com.orbit.scheduling.version;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orbit.common.dto.PageQuery;
import com.orbit.common.dto.PageResponse;
import com.orbit.common.error.BusinessException;
import com.orbit.common.util.UtcTimes;
import com.orbit.scheduling.audit.AuditResponse;
import com.orbit.scheduling.audit.ScheduleAuditLog;
import com.orbit.scheduling.audit.ScheduleAuditLogMapper;
import com.orbit.scheduling.schedule.ScheduleEntity;
import com.orbit.scheduling.schedule.ScheduleEntityMapper;
import com.orbit.scheduling.security.SchedulePrincipal;
import com.orbit.scheduling.validate.VersionValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课表版本业务逻辑 —— 对应 Python {@code routers/schedule.py} 的 versions 部分（5 个接口）。
 *
 * <p>版本的生命周期：{@code draft} →（发布）→ {@code published} →（下一版发布时）→ {@code archived}。
 * 只有草稿可改；发布时会把当前已发布的那一版置为 archived，并把明细**拷贝一份**
 * 到 {@code schedules} 表作为"当前课表"的投影。
 */
@Service
public class VersionService {

    /** 版本名长度上限（Python: {@code version.name = name[:120]}）。 */
    private static final int MAX_NAME_LENGTH = 120;

    private static final Map<String, SFunction<ScheduleVersion, ?>> SORTABLE = Map.of(
            "id", ScheduleVersion::getId,
            "version_no", ScheduleVersion::getVersionNo,
            "name", ScheduleVersion::getName,
            "status", ScheduleVersion::getStatus,
            "created_at", ScheduleVersion::getCreatedAt);

    private final ScheduleVersionMapper versionMapper;
    private final ScheduleVersionEntryMapper entryMapper;
    private final ScheduleEntityMapper scheduleEntityMapper;
    private final ScheduleAuditLogMapper auditMapper;
    private final VersionValidator validator;

    public VersionService(ScheduleVersionMapper versionMapper,
                          ScheduleVersionEntryMapper entryMapper,
                          ScheduleEntityMapper scheduleEntityMapper,
                          ScheduleAuditLogMapper auditMapper,
                          VersionValidator validator) {
        this.versionMapper = versionMapper;
        this.entryMapper = entryMapper;
        this.scheduleEntityMapper = scheduleEntityMapper;
        this.auditMapper = auditMapper;
        this.validator = validator;
    }

    /**
     * 版本列表。
     *
     * <p>⚠ 默认顺序是 {@code version_no DESC}（Python 的 {@code order_by(version_no.desc())}）——
     * 最新的一版在最前面。这与 {@code PageQuery} 默认的 asc 不同，所以这里**只在用户没指定
     * order 时**用 desc：显式传 {@code order=asc} 时按升序，属于 Java 新增的能力。
     */
    public PageResponse<VersionResponse> list(long organizationId, PageQuery query) {
        LambdaQueryWrapper<ScheduleVersion> wrapper = Wrappers.<ScheduleVersion>lambdaQuery()
                .eq(ScheduleVersion::getOrganizationId, organizationId);
        if (query.hasKeyword()) {
            wrapper.like(ScheduleVersion::getName, query.keyword());
        }
        String column = query.orderColumn(SORTABLE.keySet(), "version_no");
        boolean ascending = query.orderBy() == null ? false : query.ascending();
        wrapper.orderBy(true, ascending, SORTABLE.get(column));

        Page<ScheduleVersion> page = versionMapper.selectPage(
                new Page<>(query.pageNumber(), query.pageSize()), wrapper);
        Map<Long, Long> counts = entryCounts(organizationId);
        return PageResponse.of(
                page.getRecords().stream()
                        .map(v -> VersionResponse.of(v, counts.getOrDefault(v.getId(), 0L)))
                        .toList(),
                page.getTotal());
    }

    /** 修改版本（只改草稿，且必须带对的 revision）。 */
    @Transactional
    public VersionResponse update(long organizationId, SchedulePrincipal principal,
                                  long versionId, VersionUpdateRequest request) {
        ScheduleVersion version = requireOwned(organizationId, versionId);
        if (!"draft".equals(version.getStatus())) {
            throw BusinessException.conflict("只有草稿可以修改");
        }
        if (!java.util.Objects.equals(version.getRevision(), request.expectedRevision())) {
            throw BusinessException.conflict("版本已被其他用户修改，请刷新后重试");
        }
        if (request.name() != null) {
            String name = request.name().strip();
            if (name.isEmpty()) {
                throw BusinessException.badRequest("方案名称不能为空");
            }
            version.setName(name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name);
        }
        if (request.description() != null) {
            version.setDescription(request.description().strip());
        }
        version.setRevision(version.getRevision() + 1);
        version.setUpdatedAt(UtcTimes.now());
        versionMapper.updateById(version);

        auditMapper.insert(audit(organizationId, version.getId(), principal,
                "updated", Map.of("name", version.getName())));
        return VersionResponse.of(version, countEntries(version.getId()));
    }

    /** 复制为新草稿（版本号递增，明细整份拷贝）。 */
    @Transactional
    public VersionResponse copy(long organizationId, SchedulePrincipal principal, long versionId) {
        ScheduleVersion source = requireOwned(organizationId, versionId);
        int nextNo = nextVersionNo(organizationId);
        LocalDateTime now = UtcTimes.now();

        ScheduleVersion copied = new ScheduleVersion();
        copied.setOrganizationId(organizationId);
        copied.setVersionNo(nextNo);
        // Python: name=f"{source.name} - 副本"
        copied.setName(source.getName() + " - 副本");
        copied.setDescription(source.getDescription() == null ? "" : source.getDescription());
        copied.setStatus("draft");
        copied.setRevision(0);
        // 复制的是同一份课表语义，评分与复现链也必须继承；否则新草稿无法按原规则集评分。
        copied.setRuleSetHash(source.getRuleSetHash());
        copied.setSnapshotFingerprint(source.getSnapshotFingerprint());
        copied.setModelFingerprint(source.getModelFingerprint());
        copied.setCreatedBy(principal.userId());
        copied.setCreatedAt(now);
        copied.setUpdatedAt(now);
        versionMapper.insert(copied);

        List<ScheduleVersionEntry> entries = entryMapper.selectList(
                Wrappers.<ScheduleVersionEntry>lambdaQuery()
                        .eq(ScheduleVersionEntry::getVersionId, source.getId()));
        for (ScheduleVersionEntry item : entries) {
            ScheduleVersionEntry copy = new ScheduleVersionEntry();
            copy.setOrganizationId(organizationId);
            copy.setVersionId(copied.getId());
            copy.setClassId(item.getClassId());
            copy.setSubjectId(item.getSubjectId());
            copy.setTeacherId(item.getTeacherId());
            copy.setDayOfWeek(item.getDayOfWeek());
            copy.setPeriodIndex(item.getPeriodIndex());
            entryMapper.insert(copy);
        }
        auditMapper.insert(audit(organizationId, copied.getId(), principal,
                "copied", Map.of("source_version_id", source.getId())));
        return VersionResponse.of(copied, entries.size());
    }

    /**
     * 发布。
     *
     * <p>做四件事（一个事务）：校验明细合法 → 把上一版已发布的归档 →
     * 用本版明细**整体重写** {@code schedules}（当前课表投影）→ 记录审计。
     *
     * <p>{@code schedules} 是"最新发布的那一版"的拷贝，所以是**先清空再写**。
     */
    @Transactional
    public VersionResponse publish(long organizationId, SchedulePrincipal principal, long versionId) {
        if (!principal.isAdmin()) {
            throw BusinessException.forbidden("只有管理员可以发布课表");
        }
        ScheduleVersion version = requireOwned(organizationId, versionId);
        if (!"draft".equals(version.getStatus())) {
            throw BusinessException.conflict("只有草稿可以发布");
        }
        List<ScheduleVersionEntry> entries = validator.validate(version);
        LocalDateTime now = UtcTimes.now();

        ScheduleVersion archivePatch = new ScheduleVersion();
        archivePatch.setStatus("archived");
        archivePatch.setUpdatedAt(now);
        versionMapper.update(archivePatch, Wrappers.<ScheduleVersion>lambdaUpdate()
                .eq(ScheduleVersion::getOrganizationId, organizationId)
                .eq(ScheduleVersion::getStatus, "published"));

        scheduleEntityMapper.delete(Wrappers.<ScheduleEntity>lambdaQuery()
                .eq(ScheduleEntity::getOrganizationId, organizationId));
        for (ScheduleVersionEntry item : entries) {
            ScheduleEntity row = new ScheduleEntity();
            row.setOrganizationId(organizationId);
            row.setClassId(item.getClassId());
            row.setSubjectId(item.getSubjectId());
            row.setTeacherId(item.getTeacherId());
            row.setDayOfWeek(item.getDayOfWeek());
            row.setPeriodIndex(item.getPeriodIndex());
            scheduleEntityMapper.insert(row);
        }

        version.setStatus("published");
        version.setPublishedBy(principal.userId());
        version.setPublishedAt(now);
        version.setUpdatedAt(now);
        version.setRevision(version.getRevision() + 1);
        versionMapper.updateById(version);

        auditMapper.insert(audit(organizationId, version.getId(), principal,
                "published", Map.of("entry_count", entries.size())));
        return VersionResponse.of(version, entries.size());
    }

    /** 某版本的审计记录（按时间倒序）。 */
    public List<AuditResponse> audit(long organizationId, long versionId) {
        requireOwned(organizationId, versionId);
        return auditMapper.selectList(Wrappers.<ScheduleAuditLog>lambdaQuery()
                        .eq(ScheduleAuditLog::getVersionId, versionId)
                        .eq(ScheduleAuditLog::getOrganizationId, organizationId)
                        .orderByDesc(ScheduleAuditLog::getCreatedAt))
                .stream().map(AuditResponse::from).toList();
    }

    /** 查本组织的版本，查不到 404（Python 文案：{@code 课表版本不存在}）。 */
    public ScheduleVersion requireOwned(long organizationId, long versionId) {
        ScheduleVersion version = versionMapper.selectOne(Wrappers.<ScheduleVersion>lambdaQuery()
                .eq(ScheduleVersion::getId, versionId)
                .eq(ScheduleVersion::getOrganizationId, organizationId));
        if (version == null) {
            throw BusinessException.notFound("课表版本不存在");
        }
        return version;
    }

    /**
     * 下一个版本号 = 当前最大值 + 1。
     *
     * <p>⚠ 这里有一个**已知的竞态**（Python 也有）：两个管理员同时复制会算出同一个号，
     * 后到的那个会撞 {@code uq_schedule_version_org_no} 唯一约束 → 500。
     * P1 保持行为等价，不额外加锁；真要修应该用"唯一约束 + 重试"而不是先查后插。
     */
    private int nextVersionNo(long organizationId) {
        ScheduleVersion latest = versionMapper.selectOne(Wrappers.<ScheduleVersion>lambdaQuery()
                .select(ScheduleVersion::getVersionNo)
                .eq(ScheduleVersion::getOrganizationId, organizationId)
                .orderByDesc(ScheduleVersion::getVersionNo)
                .last("LIMIT 1"));
        return (latest == null || latest.getVersionNo() == null ? 0 : latest.getVersionNo()) + 1;
    }

    private long countEntries(long versionId) {
        return entryMapper.selectCount(Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getVersionId, versionId));
    }

    /** 一次 GROUP BY 拿到所有版本的条目数（Python 用 outerjoin + group_by）。 */
    private Map<Long, Long> entryCounts(long organizationId) {
        List<Map<String, Object>> rows = entryMapper.selectMaps(
                Wrappers.<ScheduleVersionEntry>query()
                        .select("version_id", "COUNT(*) AS entry_count")
                        .eq("organization_id", organizationId)
                        .groupBy("version_id"));
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object versionId = row.get("version_id");
            Object count = row.get("entry_count");
            if (versionId instanceof Number id && count instanceof Number n) {
                counts.put(id.longValue(), n.longValue());
            }
        }
        return counts;
    }

    private static ScheduleAuditLog audit(long organizationId, long versionId,
                                          SchedulePrincipal principal, String action,
                                          Map<String, Object> details) {
        ScheduleAuditLog log = new ScheduleAuditLog();
        log.setOrganizationId(organizationId);
        log.setVersionId(versionId);
        log.setUserId(principal.userId());
        log.setUserName(principal.displayName());
        log.setAction(action);
        log.setDetails(details);
        log.setCreatedAt(UtcTimes.now());
        return log;
    }
}
