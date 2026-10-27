package com.orbit.scheduling.job;

import com.orbit.solver.core.model.TimetableEntry;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * worker 交回结果 —— {@code POST /internal/jobs/{id}/result}。
 *
 * <p>一个请求同时表达"成功"与"失败"：{@link #error} 非空即失败。
 * 这样 worker 侧只有一条回写路径，不会出现"失败了但忘了调失败接口"。
 *
 * <p>⚠ {@link #entries} 直接复用 solver-core 的 {@link TimetableEntry} 记录，
 * 而不是在这里另建一个形状相同的 DTO —— 那会出现两个"看起来一样"的类型，
 * 改一个忘一个。
 */
public record JobResultRequest(
        @NotBlank(message = "Field required")
        String jobToken,

        /** {@code OPTIMAL} / {@code FEASIBLE} / {@code INFEASIBLE} / {@code UNKNOWN}。 */
        String solverStatus,

        String modelFingerprint,
        Long objectiveValue,

        /** 求解成功时的课表明细；失败时为 null。 */
        List<TimetableEntry> entries,

        /** 非空表示失败（求解异常、无解等）。 */
        String error,

        /**
         * worker 察觉用户取消 → 交回取消。
         *
         * <p>⚠ 与"失败"是两回事：失败是"这次没排出来"（可能该重试），
         * 取消是"用户不想排了"（**绝不该重试**）。混成一个会把用户主动取消的任务
         * 重新拉起来跑。
         */
        Boolean cancelled,

        /**
         * 失败是否值得重试。
         *
         * <p>⚠ 这个判断**由 worker 做**，因为它才知道失败的性质：
         * 进程被杀 / 网络抖动 → 该重试；{@code INFEASIBLE}（证明无解）→ 重试多少次都一样。
         */
        Boolean retryable) {
}
