package com.orbit.solver.core.engine;

/**
 * 建模阶段发现「无论怎么排都不可能」时抛出 —— 用于把配置错误与求解无解区分开。
 *
 * <p>原 Python 实现把这些情况与「超时」「真无解」一起 {@code return []}，
 * 上层只能笼统报「排课无解，请检查任教关系、周课时、固定时段和教师课时上限」。
 * 这个异常让调用方至少能拿到**具体原因**。
 */
public class ConstructionInfeasible extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConstructionInfeasible(String message) {
        super(message);
    }
}
