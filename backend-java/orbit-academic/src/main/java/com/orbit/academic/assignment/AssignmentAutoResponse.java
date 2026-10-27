package com.orbit.academic.assignment;

import java.util.List;
import java.util.Map;

/**
 * 自动分配结果 —— 对应 Python {@code AssignmentAutoOut}。
 *
 * <p>{@code teacherLoads} 是「教师姓名 → 已分配周课时」。
 * 之所以用姓名当 key 而不是 id，是 Python 原样如此（人看的报表），
 * **同名教师会互相覆盖** —— 这是既有行为，不在 P1 修正。
 */
public record AssignmentAutoResponse(int assignedCount, int preservedCount,
                                     List<String> unassigned, Map<String, Integer> teacherLoads) {
}
