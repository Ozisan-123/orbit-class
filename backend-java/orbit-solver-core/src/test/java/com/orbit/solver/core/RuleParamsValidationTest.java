package com.orbit.solver.core;

import com.orbit.solver.core.rule.RuleParamSpec;
import com.orbit.solver.core.rule.RuleParams;
import com.orbit.solver.core.rule.handler.SubjectMaxPerDayHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数校验测试。
 *
 * <p>这份 schema 同时驱动三处：后端保存规则时的校验、前端动态表单、助手的工具定义。
 * 因此「校验是否严格」直接决定用户能不能把非法规则写进数据库。
 */
class RuleParamsValidationTest {

    private static final List<RuleParamSpec> SPECS = new SubjectMaxPerDayHandler().params();

    @Test
    @DisplayName("合法参数通过")
    void 合法参数通过() {
        assertEquals(List.of(), RuleParams.of(Map.of("subject_id", 1L, "max", 2)).validate(SPECS));
        // subject_id 可省略
        assertEquals(List.of(), RuleParams.of(Map.of("max", 2)).validate(SPECS));
    }

    @Test
    @DisplayName("缺必填参数被拒，可省略参数缺失不算错")
    void 必填与可选的区分() {
        List<String> errors = RuleParams.of(Map.of("subject_id", 1L)).validate(SPECS);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("max"), "错误信息应指出缺少 max：" + errors);

        assertEquals(List.of(), RuleParams.of(Map.of("max", 1)).validate(SPECS),
                "subject_id 是可选的，缺失不应报错");
    }

    @Test
    @DisplayName("越界被拒（下界 / 上界）")
    void 越界被拒() {
        String tooSmall = RuleParams.of(Map.of("max", 0)).validate(SPECS).toString();
        assertTrue(tooSmall.contains("不能小于"), "max=0 应被拒：" + tooSmall);

        String tooBig = RuleParams.of(Map.of("max", 21)).validate(SPECS).toString();
        assertTrue(tooBig.contains("不能大于"), "max=21 应被拒：" + tooBig);
    }

    @Test
    @DisplayName("非整数被拒，但字符串形式的整数被接受")
    void 类型宽容但非法值严格() {
        assertTrue(RuleParams.of(Map.of("max", "abc")).validate(SPECS).toString()
                .contains("必须是整数"));

        // JSON 反序列化后数字可能是 Integer / Long / String，都应接受
        assertEquals(List.of(), RuleParams.of(Map.of("max", "3")).validate(SPECS),
                "字符串 \"3\" 应被当作整数接受");
    }

    @Test
    @DisplayName("枚举非法值被拒")
    void 枚举校验() {
        List<RuleParamSpec> specs = List.of(
                RuleParamSpec.enumParam("scope", "范围", List.of("all", "grade", "class")));

        assertEquals(List.of(), RuleParams.of(Map.of("scope", "grade")).validate(specs));
        List<String> errors = RuleParams.of(Map.of("scope", "school")).validate(specs);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("取值必须是"), errors.toString());
    }

    @Test
    @DisplayName("布尔非法值被拒")
    void 布尔校验() {
        List<RuleParamSpec> specs = List.of(RuleParamSpec.boolParam("allow", "是否允许"));

        assertEquals(List.of(), RuleParams.of(Map.of("allow", true)).validate(specs));
        assertEquals(List.of(), RuleParams.of(Map.of("allow", "TRUE")).validate(specs),
                "字符串形式应被接受");
        assertEquals(1, RuleParams.of(Map.of("allow", "yes")).validate(specs).size());
    }

    @Test
    @DisplayName("取值：缺失抛异常，intOr 给默认值")
    void 取值行为() {
        assertThrows(IllegalArgumentException.class,
                () -> RuleParams.empty().requireInt("max"),
                "取必填参数缺失时应抛异常而不是返回 0");

        assertEquals(7, RuleParams.empty().intOr("max", 7));
        assertEquals(5L, RuleParams.of(Map.of("n", 5)).longOr("n", -1));
        assertTrue(RuleParams.empty().boolOr("flag", true));
        assertEquals("fallback", RuleParams.empty().stringOr("s", "fallback"));
    }

    @Test
    @DisplayName("编译时非法参数会被拦下并指出规则类型")
    void 编译期拦截非法参数() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OrbitSolver.compile(TestFixtures.school8(), List.of(
                        com.orbit.solver.core.rule.ConstraintRule.hard(
                                SubjectMaxPerDayHandler.TYPE,
                                RuleParams.of(Map.of("max", 99)), "越界"))));
        assertTrue(e.getMessage().contains(SubjectMaxPerDayHandler.TYPE),
                "异常应指出是哪条规则：" + e.getMessage());
        assertTrue(e.getMessage().contains("不能大于"), e.getMessage());
    }

    @Test
    @DisplayName("未知规则类型被拒")
    void 未知规则类型被拒() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OrbitSolver.compile(TestFixtures.school8(), List.of(
                        com.orbit.solver.core.rule.ConstraintRule.soft(
                                "不存在的规则", RuleParams.empty(), 1, "x"))));
        assertTrue(e.getMessage().contains("未知的规则类型"), e.getMessage());
    }
}
