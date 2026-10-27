package com.orbit.academic.user;

/**
 * 修改账号 —— 对应 Python 的 {@code UserUpdate}，四个字段都可选。
 *
 * <p>{@code isActive} 用 {@code Boolean}：要区分「没传」与「传了 false」，
 * 原始类型 {@code boolean} 做不到（H1 那个坑的同源问题）。
 */
public record UserUpdateRequest(String displayName, String password, String role, Boolean isActive) {
}
