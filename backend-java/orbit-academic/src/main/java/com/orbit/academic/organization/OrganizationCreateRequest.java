package com.orbit.academic.organization;

import jakarta.validation.constraints.NotNull;

/**
 * 新建工作空间（组织）请求体 —— 对应 Python 的 {@code OrganizationCreate}。
 *
 * <pre>{@code
 * class OrganizationCreate(BaseModel):
 *     name: str
 *     admin_username: str
 *     admin_display_name: str
 *     admin_password: str
 * }</pre>
 *
 * <p>四个字段都必填且无长度约束，所以一律用 {@link NotNull}
 * （与 {@code LoginRequest} 同理：空字符串能通过校验，由 Service 层再判空并返回 400）。
 *
 * <p><b>注意这与 Service 层的「不能为空」检查是两回事</b>：
 * <ul>
 *   <li>字段**缺失** → 422（Pydantic / Jakarta 校验）</li>
 *   <li>字段是**空字符串** → 通过校验，走到 Service → 400
 *       {@code 工作空间名称和管理员用户名不能为空}（Python 的行为）</li>
 * </ul>
 * 两者状态码不同，不能合并。
 */
public record OrganizationCreateRequest(
        @NotNull(message = "Field required") String name,
        @NotNull(message = "Field required") String adminUsername,
        @NotNull(message = "Field required") String adminDisplayName,
        @NotNull(message = "Field required") String adminPassword) {
}
