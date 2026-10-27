package com.orbit.scheduling.rule;

import jakarta.validation.constraints.NotNull;

/**
 * 启停规则 —— {@code PATCH /api/rules/{id}}。
 *
 * <p>它对应列表上的一个开关（不弹表单）。但**仍然要带 {@code expectedRevision}**：
 * 开关看起来无害，但"我看到的这一行"与"数据库里现在那一行"可能已经不是同一件事了 ——
 * 别人可能刚改过这条规则的参数，此时我按开关不该把他的编辑一起覆盖掉
 * （所以 Mapper 里的 {@code updateEnabledWithRevision} **只更新 enabled**）。
 */
public record RulePatchRequest(
        @NotNull(message = "Field required")
        Boolean enabled,

        @NotNull(message = "Field required")
        Integer expectedRevision) {
}
