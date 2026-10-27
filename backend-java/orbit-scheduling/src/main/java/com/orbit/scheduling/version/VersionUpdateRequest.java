package com.orbit.scheduling.version;

import jakarta.validation.constraints.NotNull;

/**
 * 修改课表版本 —— 对应 Python 的 {@code ScheduleVersionUpdate}。
 *
 * <pre>{@code
 * name: Optional[str] = None
 * description: Optional[str] = None
 * expected_revision: int          # ← 必填！乐观锁
 * }</pre>
 *
 * <p>⚠ {@code expected_revision} 是**必填**（Python 没有默认值，缺了是 422）。
 * 客户端的流程是：先拿到版本（含 revision）→ 用户编辑 → 提交时带上当时的 revision；
 * 不匹配就 409，避免两个管理员同时改名把对方的改动覆盖掉。
 */
public record VersionUpdateRequest(
        String name,
        String description,
        @NotNull(message = "Field required") Integer expectedRevision) {
}
