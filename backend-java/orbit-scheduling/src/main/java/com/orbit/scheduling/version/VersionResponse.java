package com.orbit.scheduling.version;

import java.time.LocalDateTime;

/**
 * 课表版本视图 —— 对应 Python 的 {@code ScheduleVersionOut}。
 *
 * <p>字段名靠全局 {@code SNAKE_CASE} 输出：{@code version_no} / {@code entry_count}
 * / {@code created_by} / {@code published_at} 等 ✓
 *
 * <p>⚠ {@code description} 在 Python 侧**可能是 None 但输出成 ""**
 * （{@code description=version.description or ""}），所以这里也给空串而不是 null，
 * 两者对前端是不同的（前者能直接渲染，后者会显示 undefined）。
 */
public record VersionResponse(Long id, Integer versionNo, String name, String description,
                              String status, Integer revision, long entryCount,
                              Long createdBy, Long publishedBy,
                              LocalDateTime createdAt, LocalDateTime updatedAt,
                              LocalDateTime publishedAt) {

    public static VersionResponse of(ScheduleVersion version, long entryCount) {
        return new VersionResponse(
                version.getId(), version.getVersionNo(), version.getName(),
                version.getDescription() == null ? "" : version.getDescription(),
                version.getStatus(), version.getRevision(), entryCount,
                version.getCreatedBy(), version.getPublishedBy(),
                version.getCreatedAt(), version.getUpdatedAt(), version.getPublishedAt());
    }
}
