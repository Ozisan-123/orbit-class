package com.orbit.solver.core.rule;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 软约束的惩罚项收集器，最后由它统一提交 {@code model.minimize(...)}。
 *
 * <p>与原 Python 实现一致的语义：<b>所有惩罚项权重为 1 时</b>，目标就是各项之和；
 * 带权重时按权重累加。常数项也支持（例如「两节连着的固定课都是同一科目」这种
 * 与决策变量无关的固定惩罚）。
 */
public final class PenaltyCollector {

    private final CpModel model;
    private final List<LinearArgument> unitTerms = new ArrayList<>();
    private final List<LinearArgument> weightedTerms = new ArrayList<>();
    private final List<Long> weights = new ArrayList<>();
    private long constant;
    private int currentWeight = 1;

    public PenaltyCollector(CpModel model) {
        this.model = model;
    }

    /** 设定后续 {@link #add} 使用的权重（最小为 1）。 */
    public void useWeight(int weight) {
        this.currentWeight = Math.max(1, weight);
    }

    /** 累加一个惩罚项（按当前权重）。 */
    public void add(LinearArgument term) {
        if (currentWeight == 1) {
            unitTerms.add(term);
        } else {
            weightedTerms.add(term);
            weights.add((long) currentWeight);
        }
    }

    /** 累加一个固定惩罚（按当前权重）。 */
    public void addConstant(long value) {
        constant += (long) currentWeight * value;
    }

    public boolean isEmpty() {
        return unitTerms.isEmpty() && weightedTerms.isEmpty() && constant == 0;
    }

    /** 提交目标函数；没有任何惩罚项时**不设置目标**（与 Python 的 {@code if penalty_terms:} 一致）。 */
    public void commit() {
        if (isEmpty()) {
            return;
        }
        LinearExprBuilder builder = LinearExpr.newBuilder();
        if (!unitTerms.isEmpty()) {
            builder.addSum(unitTerms.toArray(new LinearArgument[0]));
        }
        for (int i = 0; i < weightedTerms.size(); i++) {
            builder.addTerm(weightedTerms.get(i), weights.get(i));
        }
        if (constant != 0) {
            builder.add(constant);
        }
        model.minimize(builder.build());
    }
}
