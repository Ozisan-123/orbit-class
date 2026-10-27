package com.orbit.solver.core.engine;

/** 求解参数 —— 对标 Python 里对 {@code solver.parameters} 的设置。 */
public record SolverSettings(double maxTimeInSeconds, int numSearchWorkers, boolean logSearchProgress) {

    /** 与现有 Python 生产配置一致（routers/schedule.py 传 time_limit=60）。 */
    public static SolverSettings production() {
        return new SolverSettings(60.0, 2, false);
    }
}
