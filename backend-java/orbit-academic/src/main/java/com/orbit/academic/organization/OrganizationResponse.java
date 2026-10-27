package com.orbit.academic.organization;

import java.time.LocalDateTime;

/**
 * 组织视图 —— 对应 Python 的 {@code OrganizationOut}。
 *
 * <pre>{@code {"id": 2, "name": "第二学校", "created_at": "2026-09-27T06:21:25"}}</pre>
 *
 * <p>注意它**不含**任何管理员信息 —— 创建组织时会同时建一个管理员用户，
 * 但响应只返回组织本身（与 Python 一致）。
 */
public record OrganizationResponse(Long id, String name, LocalDateTime createdAt) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(
                organization.getId(), organization.getName(), organization.getCreatedAt());
    }
}
