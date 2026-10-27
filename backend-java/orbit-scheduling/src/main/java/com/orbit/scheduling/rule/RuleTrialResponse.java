package com.orbit.scheduling.rule;

/**
 * 试算影响的结果 —— {@code POST /api/rules/trial}。
 *
 * <h2>⚠ 方案里这一条的语义标着"待定"，这里选定并说明理由</h2>
 * 原文：<i>「只算约束数变化（可同步）vs 重排后的差异（很贵，应走任务队列）」</i>。
 *
 * <p><b>选定：同步版，只做"编译影响"评估，不重排。</b> 理由：
 * <ol>
 *   <li>重排要和真实排课一样占用容量槽位与分布式锁，用户在表单上改一个数字就触发一次
 *       全校重排，会把排课能力吃掉 —— 而它给出的信息（"新方案好多少"）需要人再判断一次，
 *       并不能阻止一次错误配置。</li>
 *   <li>真正能**阻止错误配置**的检查（参数是否成立、与固定课是否冲突）**编译期就能做完**，
 *       不需要求解。{@code OrbitSolver.compile} 的注释里也是这么写的：
 *       "规则「试算影响」…只需要编译结果，不需要真的求解"。</li>
 *   <li>重排的差异属于「换一套规则重排」，那本来就有正式入口（改规则 → 发起排课），
 *       会留下课表版本与审计记录 —— 那比一个不落库的试算结果更有价值。</li>
 * </ol>
 *
 * <p>所以三个字段回答三个问题：
 * <ul>
 *   <li>{@link #description} —— <b>"我配的这条规则，是我想的那个意思吗？"</b>
 *       由 handler 用当前参数生成人话（这是最有价值的一项，它防的是"参数配错"）</li>
 *   <li>{@link #compiles} / {@link #problem} —— <b>"这条规则能成立吗？"</b>
 *       编译期就会暴露确定性矛盾（例如"教师不可用时段"正好压在固定课上，
 *       构造模型时直接抛 {@code ConstructionInfeasible}）</li>
 *   <li>{@link #modelChanged} / {@link #model} —— <b>"这条规则真的起作用了吗？"</b>
 *       指纹不变 = 它对这个模型没有任何影响（比空口猜测强）</li>
 * </ul>
 *
 * @param note 明确写出本版本的局限，避免前端把它当成"完整影响评估"
 */
public record RuleTrialResponse(
        boolean valid,
        String description,
        String hardness,
        Integer weight,
        boolean compiles,
        /** 不能编译时的原因（面向用户）。能编译时为 null。 */
        String problem,
        /** 加上这条规则后，模型指纹是否变化（false = 这条规则对当前模型没有影响）。 */
        boolean modelChanged,
        ModelDelta model,
        String note) {

    /** 模型规模的增量 —— 证明"规则确实进了模型"，而不是给人看的性能指标。 */
    public record ModelDelta(long variablesBefore, long variablesAfter, long variableDelta) {
    }

    public static final String LIMITATION_NOTE =
            "本结果只评估「编译影响」（参数语义、与固定课/现有数据的确定性冲突、模型规模变化）；"
                    + "「重新排课后的差异」需要走正式排课流程，会生成新的课表版本。";
}
