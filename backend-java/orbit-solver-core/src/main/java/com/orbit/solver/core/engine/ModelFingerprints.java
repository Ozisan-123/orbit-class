package com.orbit.solver.core.engine;

import com.google.ortools.sat.CpModel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 {@code modelStats()} 文本里取模型指纹。
 *
 * <p><b>使用注意</b>：指纹是对整个 {@code CpModelProto} 序列化后算的摘要，
 * 因此它对「绑定特有的记账字段」敏感。已实测的坑：Python 的 {@code model.Minimize()}
 * 会往目标里写 {@code scaling_factor = 1.0}，而 Java 的 {@code minimize()} 留空（0.0），
 * 语义相同但指纹不同（见 docs/Java建模等价性验证报告.md）。
 *
 * <p>结论：指纹适合做**同一实现内**的回归基线，不适合直接当跨语言等价判据；
 * 跨语言要比对 proto 的逐字段内容。
 */
public final class ModelFingerprints {

    private static final Pattern PATTERN =
            Pattern.compile("model_fingerprint:\\s*(0x[0-9a-fA-F]+)");

    public static String of(CpModel model) {
        return ofStats(model.modelStats());
    }

    public static String ofStats(String modelStats) {
        Matcher m = PATTERN.matcher(modelStats == null ? "" : modelStats);
        return m.find() ? m.group(1) : "(未找到)";
    }

    private ModelFingerprints() {
    }
}
