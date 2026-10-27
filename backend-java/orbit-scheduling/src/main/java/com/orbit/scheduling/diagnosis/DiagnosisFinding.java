package com.orbit.scheduling.diagnosis;

import java.util.Map;

/**
 * 一条诊断发现 —— 结构化、可验证、可翻译。
 *
 * <p>方案 §6.4 的核心主张是：**求解器输出结构化事实，助手只做翻译**。
 * 所以这里的 {@link #payload} 必须是**机器可读的字段**，而不是一句写死的中文：
 * <pre>
 *   {type:"TEACHER_OVERLOAD", teacher_id:7, actual:24, limit:18, excess:6}
 *         ↓ 助手（P4）只做翻译，零风险，不影响求解正确性
 *   「张老师这学期排了 24 节数学，上限 18 节，超了 6 节。建议减掉一个班或把上限调到 24。」
 * </pre>
 *
 * <p>{@link #message} 是**直接给用户看的那句话**（翻译的"默认实现"）；
 * 助手可以基于 payload 生成更贴切的表述，也可以在措辞上超过它。
 * 两者都给，是因为"没有助手时也不能是一片空白"。
 */
public record DiagnosisFinding(
        /** 机器可读的类别，如 {@code TEACHER_OVERLOAD}。 */
        String type,
        /** {@code error}（一定排不出来）| {@code warning}（很可能会出问题）| {@code info}。 */
        String severity,
        /** 直接可展示的中文说明。 */
        String message,
        /** 结构化字段 —— 助手与前端据此做进一步处理。 */
        Map<String, Object> payload) {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";
    public static final String INFO = "info";
}
