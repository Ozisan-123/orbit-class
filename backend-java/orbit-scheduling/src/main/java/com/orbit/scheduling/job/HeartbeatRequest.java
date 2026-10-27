package com.orbit.scheduling.job;

import jakarta.validation.constraints.NotBlank;

/**
 * worker 心跳 —— {@code POST /internal/jobs/{id}/heartbeat}。
 *
 * <p>响应里会带上 {@code cancel_requested}：**这是取消请求到达 worker 的唯一通道**
 * （worker 不碰数据库，所以它只能从心跳的响应里知道"用户点了取消"）。
 */
public record HeartbeatRequest(
        @NotBlank(message = "Field required")
        String jobToken,

        /** 0-100。OR-Tools 不直接给百分比，worker 会用"已耗时 / 时限"估算。 */
        Integer progress) {
}
