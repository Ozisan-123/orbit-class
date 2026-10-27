package com.orbit.solver.core.model;

/**
 * 单个班级的时段容量 —— 对标 Python 的 {@code class_data[cid]}。
 *
 * <p>注意 {@code periods} 是「每天各自的节数」而不是一个常数：不同年级的
 * {@code periods_per_day} 可以不同，所以「当天最后一节」必须按班按天算。
 *
 * @param periods 索引 0..4 对应周一..周五
 */
public record ClassData(long classId, int[] periods, int total) {
}
