# 代码导读 · 2d. `orbit-solver-core` 编译装配 + 5 条可配置规则

> 本册范围：`com.orbit.solver.core.compile`（4 个类，201 行）+
> `com.orbit.solver.core.rule.handler` 里的 5 个**可配置** handler（582 行），共 **9 个类**。
> 上游：[代码导读.md](代码导读.md)（第 1 册 · 格式与详细程度基准）；
> 同模块总册：[代码导读-2-solver-core.md](代码导读-2-solver-core.md)。
>
> **本册刻意不重复**总册已经讲过的：输入模型 `ScheduleInput`、变量工厂
> `SlotVariableIndex`、规则 API（`RuleHandler` / `RuleContext` / `PenaltyCollector` /
> `RuleParams` / `RuleParamSpec`）、以及另外 5 个 handler
> （`TeacherNoConflict` / `SubjectWeeklyHours` / `TeacherWeeklyLimit` /
> `SubjectMaxPerDay` / `SubjectNoConsecutive`）。
>
> **本册补的是总册没讲透的那一层**：这些 CP-SAT 调用**在数学上是什么意思**
> （线性不等式形式、`onlyEnforceIf` 的双向重化、惩罚项怎么进目标函数），
> 以及 `RuleCompiler` 的**编译顺序为什么敏感**。

## 本模块在系统里的位置

`compile` 包是 solver-core 的「编译器」：它的输入是
「一份排课输入（`ScheduleInput`）+ 一套规则（`List<ConstraintRule>`）」，
输出是一个**建好的 CP-SAT 模型**（`CompiledModel`）。它自己**不解**这个模型 ——
求解由 [OrbitSolver](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/OrbitSolver.java)
交给 `CpSolver` 做（「编译」与「求解」分开，是为了让指比对、规则试算、不可行诊断
这些**只要模型不要解**的场景也能跑）。

`rule/handler` 包是「每条规则的具体建模代码」：一个 handler = 一种
`rule_type`。handler 之间**互不知道对方存在**，它们只通过 `RuleContext` 拿变量、
通过 `PenaltyCollector` 交惩罚项；把它们串起来的是 `RuleCompiler`。

```
scheduling.RuleSetService / RuleTypeCatalog      ← 上游：规则的存取与能力清单
        ↓ List<ConstraintRule>（来自 constraint_rules 表）
compile.BuiltinRules  ──装配──→  compile.RuleCompiler  ──编译──→  compile.CompiledModel
                                        ↓ 逐个 handler.apply(...)
                                 rule/handler/*（本册讲其中 5 个）
                                        ↓ 变量懒创建
                                 engine.SlotVariableIndex
```

## 阅读顺序建议

1. **先看 `RuleCompiler`** —— 它 77 行就把「变量 → 不变量 → 规则 → 目标」这条流水线定死了。
   不理解它，后面 5 个 handler 只是零散的建模片段；理解它，5 个 handler 全都变成
   「往这条流水线的第 2 阶段里插一段」。
2. **再看 `TeacherMinimizeGapsHandler`** —— 它是这 5 条里唯一需要**辅助变量**的
   （其余 4 条都只是「把已有变量加起来和常数比一比」）。它是本册的难度上限。
3. 最后看 `BuiltinRules` + `RuleSets` —— 它们是**装配知识**，
   决定了「有哪些规则」「P1 等价用哪套」，改动它们比改动任何单个 handler 影响都大。

---

## 0. 读这 9 个类之前必须先会的 6 件事（CP-SAT 语义速查）

这一节是**后面逐行解释的字典**，不涉及本项目业务。

### 0.1 三种东西：变量、常量、表达式

| 代码 | 数学对象 |
|---|---|
| `model.newBoolVar("x")` | 变量 x ∈ {0, 1}。**布尔变量的取值就是 0/1**，所以「x 为真」与「x = 1」是同一件事 |
| `model.newIntVar(lo, hi, "x")` | 变量 x ∈ [lo, hi]（整数） |
| `HandlerSupport.boolConst(model, "x", false)` | ⚠ **不是常量字面量，是一个被 `addEquality(x, 0)` 钉死的真变量** —— 它会占一个变量下标，会进 proto |
| `int fixed = ctx.fixedCountForTeacher(...)` | 编译期算出的 **Java 常量**。固定课没有决策变量，所以「固定课的数量」只能是常数，直接加进表达式 |

### 0.2 所有「加约束」的调用都是线性等式/不等式

CP-SAT 的约束 API 只认「线性表达式 + 界」。逐条对照：

| 代码 | 数学形式 |
|---|---|
| `model.addLessOrEqual(e, u)` | **e ≤ u** |
| `model.addGreaterOrEqual(e, l)` | **e ≥ l** |
| `model.addEquality(e, v)` | **e = v** |
| `model.addEquality(a, b)`（a、b 都是表达式/变量） | **a = b** |
| `LinearExpr.sum(HandlerSupport.args(vars))` | **Σᵢ vᵢ** |
| `HandlerSupport.sumPlus(vars, k)` | **Σᵢ vᵢ + k**（`k = 0` 时不加常数项、`vars` 为空时不加求和项 —— 语义相同，proto 不一定相同） |
| `model.minimize(e)` | **min e** |

### 0.3 `onlyEnforceIf` 是「重化」（reification），不是「条件加约束」

CP-SAT 的 `addXxx(...).onlyEnforceIf(lit)` 语义是：
**lit = 1 时约束必须成立；lit = 0 时这条约束不留任何限制**。
所以**单向的 `onlyEnforceIf` 不能表达「当且仅当」**，必须两条一起写。
[HandlerSupport](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/handler/HandlerSupport.java) 的两个编码就是干这个的：

```java
/** 令 target <=> OR(literals)。 */
static void encodeOr(CpModel model, BoolVar target, Literal[] literals) {
    model.addBoolOr(literals).onlyEnforceIf(target);            // ① target → (l₁ ∨ … ∨ l_k)
    model.addBoolAnd(negate(literals)).onlyEnforceIf(target.not()); // ② ¬target → (¬l₁ ∧ … ∧ ¬l_k)
}
```

- ① 展开是 `target = 1 ⇒ 至少一个 literal 为真`；
- ② 展开是 `target = 0 ⇒ 所有 literal 为假`，其逆否就是
  `至少一个 literal 为真 ⇒ target = 1`；
- ①+② 合起来 = **target ⟺ OR(literals)**（双向）。

`encodeAnd` 完全对称：`addBoolAnd(literals).onlyEnforceIf(target)` + `addBoolOr(negate(literals)).onlyEnforceIf(target.not())`
= **target ⟺ AND(literals)**。

> ⚠ **只写一半会怎样**：模型仍然可解、不会报错，但 `target` 变成「可以向松的方向自由取值」。
> 惩罚项用的是 `target`，于是**罚少了 → 求解器把课排得更差而代价不变**。
> 这类 bug 不崩不报，只体现在课表质量上。

### 0.4 硬 / 软两种落点

同一个 `apply(ctx, params, hard, penalties)` 有两种模式，**这是规则系统「硬软两用」的全部实现**：

| 模式 | 落点 | 数学后果 |
|---|---|---|
| `hard = true` | `model.addXxx(...)` | 加进**可行域**：违反 = 这个解不存在 |
| `hard = false` | `penalties.add(项)` | 加进**目标函数**：违反 = 目标值变大，仍可有解 |

软约束**绝不加约束**，只**交惩罚项**；硬约束**绝不交惩罚项**（接口 javadoc 明确写了
「hard 为 true 时不应使用 penalties」）。`RuleCompiler` 用 `!rule.isSoft()` 决定传哪个。

### 0.5 惩罚项怎么变成目标函数

`PenaltyCollector` 收集，最后 `commit()` 一次提交。**权重为 1 时目标就是各项之和**；
带权重时是 Σ (权重 × 项)；另有与决策无关的**常数惩罚**（固定课的违规，改不了，只能记在目标里）。

```
目标 = Σ unitTerms + Σ (weightᵢ × weightedTermᵢ) + constant
```

⚠ **`useWeight` 是有状态的前置调用**：它设的是「**接下来**这条规则的 `add` 用哪个权重」，
所以 `RuleCompiler` 必须**在 `handler.apply` 之前**调它（§1.1）。忘了调的后果不是
「这条规则权重错」，而是「**下一条软规则**权重错」—— 因为 `currentWeight` 是实例字段，会留到下一次。

### 0.6 ⚠ 固定课是「常量」，不是「值为 1 的变量」

`SlotVariableIndex` 对固定课时段**不建决策变量**：`slotVar(...)` 返回 `null`，
`subjectAt(...)` / `teacherAt(...)` 也跟着返回 `null`。所以 handler 里的统一形状是：

```java
int fixed = ctx.fixedCountForXxx(...);           // ← 编译期常数，已经算好了
List<BoolVar> vars = ctx.freeXxxSlots(...);      // ← 只含非固定时段
model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), limit);   // Σvars + fixed ≤ limit
```

**看懂「`fixed` 是常数、`vars` 是变量」这一点，这一册 5 个 handler 的硬分支就都读懂了。**

---

## 1. `compile` —— 编译装配（4 个类，201 行）

### 1.1 `RuleCompiler` —— 整个模块的心脏（77 行）

**一句话定位**：把「排课输入 + 规则集」变成 CP-SAT 模型，**是唯一会把模型建起来的地方**。

**谁调它**：[OrbitSolver.compile](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/OrbitSolver.java)（第 53 行 `new RuleCompiler(registry).compile(input, rules)`）——
`OrbitSolver` 是唯一门面，scheduling 服务只碰门面，不直接 `new RuleCompiler`。
**它调谁**：`SlotVariableIndex`（建索引/懒建变量）、`registry.mandatory()` 与
`registry.get(type)`（取 handler）、每个 `handler.apply(...)`、最后 `PenaltyCollector.commit()`。

#### 逐段逐行

```java
private final RuleRegistry registry;

public RuleCompiler(RuleRegistry registry) { this.registry = registry; }

public static RuleCompiler withBuiltinRules() {
    return new RuleCompiler(BuiltinRules.registry());
}
```

- **`registry` 是构造器注入的，不是内部 `new` 的**：这是「编译用哪套规则实现」的可替换点。
  测试可以传一个**少了某条不变量**的注册表来造反例
  （`OrbitSolver.compile(input, rules, registry)` 的三参重载就是为它留的）。
- **`withBuiltinRules()`**：便利工厂。⚠ **实测本模块内没有调用者**
  （`OrbitSolver.compile` 走的是三参重载）；是否有模块外调用者**本册未核**。

```java
public CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules) {
    CpModel model = new CpModel();                                    // ①
    SlotVariableIndex index = SlotVariableIndex.create(model, input);  // ②
    RuleContext ctx = new RuleContext(index);                          // ③
    PenaltyCollector penalties = new PenaltyCollector(model);          // ④
```

- **① `new CpModel()`**：空模型。**注意它是在编译器内部建的** ——
  `OrbitSolver` 只拿到成品 `CompiledModel`，拿不到「编译到一半的模型」。
- **② `SlotVariableIndex.create(model, input)`**：建索引。⚠ **只建索引，不建变量** ——
  决策变量在 handler **第一次请求**时才创建（§0.6）。**它也是唯一会抛
  `ConstructionInfeasible` 的地方**（固定课展开检查：某班某科目必须恰好一条任教关系）。
- **③ `new RuleContext(index)`**：把索引包成「规则作者看到的世界」。
- **④ `new PenaltyCollector(model)`**：软约束收集器。**同一个实例贯穿所有规则** ——
  这正是「权重是有状态的」这一设计的前提（§0.5）。

```java
    // 1) 不变量 + 数据驱动约束（硬）
    for (RuleHandler handler : registry.mandatory()) {
        handler.apply(ctx, RuleParams.empty(), true, penalties);
    }
```

- **遍历 `registry.mandatory()`**：只有 `mandatory() == true` 的 handler（本项目 3 个：
  `TeacherNoConflict` / `SubjectWeeklyHours` / `TeacherWeeklyLimit`）。
  **顺序 = `BuiltinRules` 的注册顺序**（`RuleRegistry` 内部是 `LinkedHashMap`）。
- **`RuleParams.empty()`**：不变量**不接受参数**。它们的参数来自**教务数据本身**
  （教师的 `maxWeeklyHours`、年级的 `weeklyHours`），handler 自己去 `ctx` 里读。
  这是「`mandatory` 意味着不可配置」的具体落点。
- **`true`**：不变量**一律硬约束**。用户不能把「同一教师同一时段上两个班」变成软约束。
- **`penalties` 也传进去**：⚠ **签名要求传，但约定是 mandatory 的 handler 不该用它**
  （在 `PenaltyCollector` 里留下惩罚项的 mandatory handler 会让「不变量」这个词失去意义）。
  这是接口设计上**一处「约定而非强制」** —— 编译器无法阻止它。

```java
    // 2) 可配置规则
    for (ConstraintRule rule : rules) {
        if (!rule.enabled()) { continue; }
        RuleHandler handler = registry.get(rule.type());
        if (handler.mandatory()) { continue; }
        List<String> errors = rule.params().validate(handler.params());
        if (!errors.isEmpty()) { throw new IllegalArgumentException(...); }
        penalties.useWeight(rule.effectiveWeight());
        handler.apply(ctx, rule.params(), !rule.isSoft(), penalties);
    }
```

逐行：

| 行 | 做什么 | 不这么写会怎样 |
|---|---|---|
| `if (!rule.enabled()) continue;` | **唯一**的 `enabled` 过滤点 | 关掉的规则照样生效；用户会以为「关掉没反应」 |
| `registry.get(rule.type())` | 未知类型 → `IllegalArgumentException("未知的规则类型: …（已注册: […]）")` | 静默跳过会让「配错 type」变成「规则不生效」，最难查 |
| `if (handler.mandatory()) continue;` | ⚠ **跳过**：不变量不接受外部配置，避免被规则表重复施加 | 数据库里若误存一行 `teacher_no_conflict`（手工插库 / 老数据迁移），不变量会被**施加两次** —— 冗余但语义相同，**模型仍可解、结果仍正确，只是约束数变了、指纹变了**。堵的就是这种「不报错的漂移」 |
| `rule.params().validate(handler.params())` | **编译时再校验一遍**参数 | 参数可能来自**旧数据**、**手工改库**、**旧版本 schema**（handler 升级后老参数变非法）。在这一层兜住，保证「进了模型的一定合法」 |
| 抛 `IllegalArgumentException` | 文案 `"规则 " + rule.type() + " 参数非法: " + String.join("; ", errors)` | ⚠ **两段信息都不能少**：哪条规则 + 具体哪里不对。测试同时断言了这两点 |
| `penalties.useWeight(rule.effectiveWeight())` | 设「接下来这条规则的 `add` 用哪个权重」 | ⚠ **必须在 `apply` 之前**。**硬规则也照样调** —— 硬规则不该 `add`，但「忘了调」的后果是**下一条软规则用错权重**。放在 `apply` 前无条件执行，是唯一不需要判断硬软的位置 |
| `handler.apply(ctx, rule.params(), !rule.isSoft(), penalties)` | ⚠ 传的是 **`!rule.isSoft()`** | 与 `hardness() == HARD` 语义相同，但复用 `isSoft()` 让「硬软判断只有一处」 |

- **`rule.effectiveWeight()`**：`weight <= 0 ? 1 : weight` —— **权重 0 会被掰成 1**，
  所以「权重 0 = 这条规则不起作用」**不是**本项目的行为。想让规则失效请用 `enabled = false`。

```java
    // 3) 目标
    penalties.commit();

    String stats = model.modelStats();
    return new CompiledModel(model, index, ctx, stats, ModelFingerprints.ofStats(stats));
}
```

- **`penalties.commit()`**：`PenaltyCollector` 内部若**一个惩罚项都没有**就直接返回、
  **不设目标**（与 Python 的 `if penalty_terms:` 一致）。所以「模型全硬约束」时是一个
  **无目标函数的可行性问题** —— CP-SAT 仍然可解，只是 `objectiveValue()` 恒为 0。
  顺序上「目标最后提交」让「项已经收齐」在代码顺序上一目了然
  （CP-SAT 本身允许约束与目标任意顺序添加）。
- **`model.modelStats()`**：⚠ **编译结束时算一次**，不是等求解完再算 ——
  统计/指纹反映的是**模型**，求解不改模型；而且只编译不求解的场景（规则试算）也要它。
- **`ModelFingerprints.ofStats(stats)`**：⚠ **复用已经拿到的 `stats` 字符串**，
  而不是 `ModelFingerprints.of(model)` 再调一次 `modelStats()` —— 后者是不小的字符串生成操作，
  而编译是热路径（每次排课、每次试算都跑）。

#### ⚠⚠ 顺序为什么敏感（本册最需要说清的一点）

先说**不成立**的直觉：**CP-SAT 对「约束添加顺序」不敏感** ——
同一组约束，无论按什么顺序加，可行域完全相同。所以顺序敏感**不是**「换个顺序解就变了」。

真正的顺序敏感有**四处**，性质完全不同：

| # | 敏感的是什么 | 改动顺序的后果 | 会不会报错 |
|---|---|---|---|
| ① | **变量创建顺序** | `SlotVariableIndex` 懒建变量，CP-SAT 按创建顺序分配下标（`slotVar` → `ss_*` / `tc_*` 成员布尔 → `membership()` 里的 `_eqN` 重化变量）。谁先问谁先建 → **变量下标变 → proto 变 → `model_fingerprint` 变** | ❌ 不报错，`GoldenModelTest` 会红（那是有意的：模型变了必须被看见） |
| ② | **`useWeight` 相对 `apply` 的位置** | 权重会**借用给下一条规则** | ❌ 不报错，只体现在求解结果上 |
| ③ | **`ConstructionInfeasible` 谁先抛** | mandatory 阶段按注册顺序执行，**第一个撞上的矛盾决定用户看到的报错文案**（`TeacherNoConflict` 排第一，所以「教师撞课」优先于「周课时不足」被报出来） | ✅ 报错，但**报的是哪一条**取决于顺序 |
| ④ | **proto 里约束/目标字段的排列顺序** | 与 ① 同源，共同决定 `model_fingerprint` | ❌ 不报错 |

所以「不变量先于可配置规则」这个顺序，**不是**为了数学正确性，而是为了两件事：
**(a) 不变量不可被规则集影响**（`mandatory` 的语义）；
**(b) 每次编译的产出结构稳定**（同样的输入+规则 ⇒ 同样的指纹 ⇒ 才能拿指纹做回归基线）。

> ⚠ **`model_fingerprint` 只适合做「同一实现内」的回归基线**，不要当跨语言等价判据：
> 已实测 Python 的 `model.Minimize()` 会往目标里写 `scaling_factor = 1.0`，
> Java 的 `minimize()` 留空 —— **语义相同、指纹不同**（见
> [Java建模等价性验证报告.md](Java建模等价性验证报告.md)）。

### 1.2 `CompiledModel` —— 编译产物（19 行）

```java
public record CompiledModel(
        CpModel model,
        SlotVariableIndex index,
        RuleContext context,
        String modelStats,
        String modelFingerprint) {
}
```

**一句话定位**：编译结果的值对象 —— 模型 + 建好的变量索引 + 规则上下文 + 指纹。
**它没有任何方法体**：`record` 自动生成 `model()` / `index()` / `context()` /
`modelStats()` / `modelFingerprint()` 五个访问器。**只装数据，没有行为。**

**谁用它**：`OrbitSolver.solve(compiled, settings)` 取 `model()` 交给 `CpSolver`、
取 `index()` 交给 `TimetableExtractor`、把 `modelStats()` / `modelFingerprint()` 塞进 `SolveOutcome`；
`RuleTrialService`（规则试算）只用编译结果，**不求解**。

| 字段 | 谁用 | 为什么必须在产物里 |
|---|---|---|
| `model` | `OrbitSolver.solve` → `new CpSolver().solve(model)` | 核心。⚠ **它是可变的** `CpModel`：测试正是靠这一点往里 `addEquality(...)` 强制变量取值来造反例 |
| `index` | `TimetableExtractor.extract(solver, compiled.index())` | **结果提取必需**：把整数变量的取值还原成「哪个班·哪节·什么课·哪位老师」 |
| `context` | ⚠ 测试（`compiled.context()`） | **生产路径不读它**。留着是为了测试能拿变量构造反例（`ctx.teacherAt(...)` / `ctx.freeTeacherSlots(...)`）。这是一处**有意的便利**，代价是「调用方可以拿到变量做任意事」 |
| `modelStats` | `SolveOutcome`、诊断落库 | 人类可读的模型规模（`modelStats()` 的原文） |
| `modelFingerprint` | `SolveOutcome`、worker 上报 | 「这版课表用的哪个模型」的凭据 |

**为什么是 `record` 而不是普通类**：编译产物是纯数据，不需要继承、不需要可变行为，
`record` 顺带给了 `equals`/`hashCode`/`toString`（试算对比时按值比较很方便）。
**注意它把 5 个字段全部暴露**（没有封装），这是刻意的：这是**模块内部的产物**，
不是跨服务契约。

> ⚠ 名字叫 `modelFingerprint`，但它的来源是**对 `modelStats` 文本做正则抠出来的**
> （`ModelFingerprints.ofStats` 匹配 `model_fingerprint:\s*(0x[0-9a-fA-F]+)`），
> 抠不到时返回字符串 **`"(未找到)"`** —— 也就是说**它是一个可能为占位符的 String，不是校验和**。
> 拿它做「模型是否变化」的比较时，`"(未找到)"` 会让两次不同的模型比出「相等」。

### 1.3 `BuiltinRules` —— 内置规则的装配点（51 行）

**一句话定位**：**「系统内置哪几种规则」的唯一装配点** ——
`rule_type` 字符串到 `RuleHandler` 实现的清单。

**为什么这份清单在这里，而不在 `RuleRegistry`**（javadoc 第 20–24 行，本册最值得记的一段）：
- `RuleRegistry` 的职责**只有**「`type` → 实现」的映射，它**刻意不知道有哪些内置规则**；
- 让注册表去 `new` 实现，会让 `rule` 包与 `rule.handler` 包**形成双向依赖**
  （注册表 new 实现，实现 implements 接口）→ **包环**；
- 放到 `compile` 里，`rule` 包就保持为**纯 API**（只定义接口与数据，不依赖任何实现）。

```java
public final class BuiltinRules {

    /** 内置的 10 种规则：3 个不变量/数据驱动 + 7 个可配置。 */
    public static RuleRegistry registry() {
        return new RuleRegistry(List.of(
                // —— 不变量 / 数据驱动（mandatory，始终生效，顺序敏感）——
                new TeacherNoConflictHandler(),
                new SubjectWeeklyHoursHandler(),
                new TeacherWeeklyLimitHandler(),
                // —— 可配置（仅当规则集引用时生效）——
                new SubjectMaxPerDayHandler(),
                new SubjectNoConsecutiveHandler(),
                new TeacherMaxDailyPeriodsHandler(),
                new TeacherUnavailableHandler(),
                new SubjectAvoidLastPeriodHandler(),
                new MainSubjectMorningHandler(),
                new TeacherMinimizeGapsHandler()));
    }

    private BuiltinRules() { }
}
```

逐行：

- **`List.of(...)` 保参数顺序** → `RuleRegistry` 用 `LinkedHashMap` 接住 →
  **注册顺序 = 变量创建顺序**（§1.1 的 ①）。
- **前三条是 mandatory**（它们的相对顺序决定变量创建次序），**后七条是可配置**
  （只在规则集显式引用时才 `apply`）。⚠ **改动这 10 行的顺序会让模型指纹变化**。
- **每次调用都 `new` 一批 handler 对象**：⚠ handler 是**无状态的**（`apply` 只用参数与 `ctx`），
  所以「新建一批」没有语义影响；但 `OrbitSolver.compile` 每次编译都调一次
  `BuiltinRules.registry()`，于是每次编译多 10 个对象 —— 在「每次排课一次」的频率下无所谓。
  **做成静态单例是不行的**：测试需要「另一个注册表」来造反例，全局单例会让「谁改了它」变得可能。
- **`private BuiltinRules() {}`**：纯静态工具类，不允许实例化。

**10 条规则的 `TYPE` 字符串**（⚠ **数据库里存的是字符串，不是类名**）：

| 档 | 类 | `TYPE` |
|---|---|---|
| mandatory | `TeacherNoConflictHandler` | `teacher_no_conflict` |
| mandatory | `SubjectWeeklyHoursHandler` | `subject_weekly_hours` |
| mandatory | `TeacherWeeklyLimitHandler` | `teacher_max_weekly_hours` ⚠ |
| 可配置 | `SubjectMaxPerDayHandler` | `subject_max_per_day` |
| 可配置 | `SubjectNoConsecutiveHandler` | `subject_no_consecutive` |
| 可配置 | `TeacherMaxDailyPeriodsHandler` | `teacher_max_daily_periods` |
| 可配置 | `TeacherUnavailableHandler` | `teacher_unavailable` |
| 可配置 | `SubjectAvoidLastPeriodHandler` | `subject_avoid_last_period` |
| 可配置 | `MainSubjectMorningHandler` | `main_subject_morning` |
| 可配置 | `TeacherMinimizeGapsHandler` | `teacher_minimize_gaps` |

> ⚠ **`TeacherWeeklyLimitHandler.TYPE` 是 `"teacher_max_weekly_hours"`，而类名叫
> `TeacherWeeklyLimit`**（`RuleSets` 的 javadoc 与 [架构说明.md](架构说明.md) 的表里
> 都写类名）。**字符串常量才是数据库里的真相**：改类名不影响数据，
> **改 `TYPE` 常量等于要求迁移数据库**。

**「新增一种规则只有两处要改」**（javadoc 第 22–24 行）：

1. **写一个 `RuleHandler` 实现类**（放进 `rule/handler/`，`type()` 返回新的 `rule_type`）；
2. **在这里登记一行**（`new XxxHandler()`，放在「可配置」那一段）。

**为什么只有两处**：规则系统的**能力清单**是从 `handler.params()` 反推出来的，
没有任何地方再维护一份类型白名单。
[RuleTypeCatalog](../../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/rule/RuleTypeCatalog.java)
（scheduling 侧）**直接持有 `BuiltinRules.registry()`**，只把 `params()` 投影成 HTTP 响应；
前端表单、助手工具定义都消费同一份 `RuleParamSpec` 列表（「一份 schema，三处消费」）。
**所以新增 handler 之后：`GET` 规则类型清单下次请求就自动多一条，前端表单自动能渲染，不用改前端。**

> ⚠ **实测一处 javadoc 与代码不一致**：`OrbitSolver.builtinRules()` 的注释写
> 「3 个不变量/数据驱动 + **2 个**可配置」，而 `BuiltinRules` 注册的是 **7 个**可配置。
> 本册以代码为准。**本册只记录，未修改源码**（规范红线 ④）。

### 1.4 `RuleSets` —— 预设规则集（54 行）

**一句话定位**：直接可用的**成套规则**。本册唯一的成品是 `legacyEquivalent()` ——
一份**语义上等价于 Python 原先 5 条硬编码约束**的规则集。

**谁调它**：
- [RuleSetService.ensureInitialized](../../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/rule/RuleSetService.java)（第 337 行）——
  **新组织第一次访问时写入的默认规则**就是它。注释写明意图：
  「**这样 P2 上线不会改变任何一所学校的排课结果**：新组织第一次排课用的还是那两条软约束，
  只是它们现在**看得见、改得动**了」。
- `TestFixtures.legacyEquivalentRules()`（测试）—— **只做转发**，注释写明「只有一份定义，
  测试与生产不可能漂移」；`PositiveFeasibilityTest` / `GoldenModelTest` 都用它。

```java
public static List<ConstraintRule> legacyEquivalent() {
    return List.of(
            ConstraintRule.soft(SubjectMaxPerDayHandler.TYPE,
                    RuleParams.of(Map.of("max", 1)), 1, "任何科目一天最多 1 节"),
            ConstraintRule.soft(SubjectNoConsecutiveHandler.TYPE,
                    RuleParams.empty(), 1, "任何科目不连排两节"));
}

private RuleSets() { }
```

#### 逐条对照 Python 的 5 条硬编码约束

Python 侧约束**硬编码在 `scheduler/engine.py`** 里（硬 1/2/3 + 软 1/2）。
Java 侧改造成规则系统后，**5 条里只有 2 条出现在这个方法里**：

| Python | 这里 | 参数 | 权重 | 怎么生效 |
|---|---|---|---|---|
| **硬 1** 教师不撞课 | `TeacherNoConflictHandler` | — | — | **不在此列出**：`mandatory`，`RuleCompiler` 第 1 阶段自动施加 |
| **硬 2** 周课时必须排满 | `SubjectWeeklyHoursHandler` | — | — | 同上（参数从快照的 `weeklyHours` 读） |
| **硬 3** 教师周课时上限 | `TeacherWeeklyLimitHandler` | — | — | 同上（参数从教师的 `maxWeeklyHours` 读） |
| **软 1** 同一科目一天最多 1 节 | `SubjectMaxPerDayHandler` | `{"max": 1}` | **1** | **本方法显式列出** |
| **软 2** 同一科目不连排 | `SubjectNoConsecutiveHandler` | `{}` | **1** | **本方法显式列出** |

逐条读：

- **`ConstraintRule.soft(type, params, weight, description)`**：工厂方法，落成
  `new ConstraintRule(type, params, Hardness.SOFT, 1, true, description)` ——
  ⚠ **注意第 5 个参数 `enabled` 被工厂固定为 `true`**，
  所以这两条默认规则**生来就是启用的**（`RuleSetService` 落库时也 `setEnabled(true)`）。
- **权重都是 1**：**与 Python 一致**。⚠ 权重不是「调参旋钮」而是**语义对齐物**：
  Python 的目标函数就是「软惩罚项之和」，权重改成 2 就不再等价了。
- **`RuleParams.of(Map.of("max", 1))`**：⚠ `max` 的值是 **`Integer` 1**（Java 自动装箱），
  而 `RuleParams.requireLong` 用 `instanceof Number` 接住 —— 所以 `Integer` 没问题。
  **但别把它换成 `Map.of("max", "1")`**：那会走 `Long.parseLong` 分支（也能过），
  可一旦写成 `"abc"` 就会在**编译时**抛 `IllegalArgumentException`。
- **不指定 `subject_id`**：`SubjectMaxPerDayHandler` 的 `subject_id` 是 `optionalInt`，
  留空 = **适用于该班的每一个科目** —— 这正是 Python「软约束 1」的语义
  （Python 是对所有科目统一处理）。
- **`RuleParams.empty()`** for `SubjectNoConsecutive`：同理，不指定科目 = 所有科目不连排。
- **`private RuleSets() {}`**：纯静态工具类。

> ⚠ **改动它会立刻让模型指纹变化 → `GoldenModelTest` 会红。**
> 那是**有意的**（javadoc 第 43 行明说）：模型变了必须被看见并重新解释，而不是悄悄变。
> **所以「顺手把权重调成 2」「顺手加一条规则进来」都是破坏 P1/P2 行为等价的动作。**

---

## 2. `rule/handler` —— 5 条可配置规则（582 行）

**这 5 条全部是 `mandatory() == false`**（都没覆写 `mandatory()`，走接口默认的 `false`）：
**只有规则集显式引用它们时才生效**，`legacyEquivalent()` 一个都没引用。
所以下面每一条，**在默认配置下都不在模型里**。

### 2.1 `TeacherMaxDailyPeriodsHandler` —— 教师单日节数上限（98 行，硬软两用）

**对应的真实教务约束**：「同一个老师一天不要排超过 4 节」。
**与 `teacher_max_weekly_hours` 的区别是粒度**：那条是**整周**上限（来自教师档案、
不可配置），这条是**日**上限（可配置）。

**参数**（`params()`）：

| 参数 | spec | 含义 |
|---|---|---|
| `teacher_id` | `optionalInt(..., 1, Integer.MAX_VALUE)` | **可省略** → 省略 = 适用于所有教师 |
| `max` | `intParam(..., 1, 20)` | **必填**，一天最多几节；⚠ 上界 20 是**防呆**，不是「一天有 20 节」 |

**`describe(params)`**：只用于**显示**（规则列表里那一行字），
`teacher_id` 用 `longOr(P_TEACHER, -1)` 取（不填时 `who = "所有教师"`），
`max` 用 `intOr(P_MAX, 1)` —— ⚠ **这里用的宽松取值是为了「显示不崩」**，
真正建模时用的是下面的 `requireInt`（缺失就抛）。

#### `apply` 逐行与数学含义

```java
CpModel model = ctx.model();
Long only = params.has(P_TEACHER) ? params.requireLong(P_TEACHER) : null;
int max = params.requireInt(P_MAX);
```

- `params.has(P_TEACHER)` 判的是 **`values.get(name) != null`**：`{"teacher_id": null}`
  与不传等价。
- **`max` 用 `requireInt`**：参数校验已经在 `RuleCompiler` 做过一遍（§1.1），
  这里的 `require` 是「这份参数一定合法」的**断言式写法**。

```java
for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
    long teacherId = teacher.id();
    if (only != null && only != teacherId) { continue; }
    for (int day = 0; day < ctx.days(); day++) {
        int fixed = ctx.fixedCountForTeacherOnDay(teacherId, day);
        List<BoolVar> vars = ctx.freeTeacherSlotsOnDay(teacherId, day);
```

- **`ctx.teachers()`** 保序（`LinkedHashMap`），所以**外层循环顺序是确定的** → 变量创建顺序确定。
- **`only != null && only != teacherId` → `continue`**：⚠ 与「科目版」的写法**不同**
  （科目版把 `only` 包成单元素集合来遍历）。**两者等价，但代码形状不一致** ——
  新增 handler 时选一种就好。
- **口径是「该教师 × 该天 × 跨所有班」**：
  `fixedCountForTeacherOnDay` 累加各时段的 `fixedCountForTeacher`；
  `freeTeacherSlotsOnDay` 遍历 `period < ctx.maxPeriodsOn(day)` 再按班过滤
  （`period >= periodsOn(classId, day)` 的班跳过）。**所以节数不同的班混在一起也不会串。**

```java
        if (hard) {
            if (fixed > max) {
                throw new ConstructionInfeasible(
                        "教师 " + teacherId + "（" + teacher.name() + "）在第 "
                                + (day + 1) + " 天的固定课已有 " + fixed
                                + " 节，超过单日上限 " + max);
            }
            if (!vars.isEmpty()) {
                model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), max);
            }
        }
```

- **硬分支 = 加一条线性不等式**：

  ```
  addLessOrEqual( Σ vars + fixed , max )   ⟺   Σ_{p ∈ 非固定时段} teacherAt(t, ·, day, p) ≤ max − fixed
  ```

  **含义**：这位老师这天在**所有自由时段**里被选中的次数，不超过「上限减去固定课已占的节数」。
  `vars` 里的每个元素是「该教师在那个时段、某个班上这一节」的成员布尔（0/1），
  求和 = **这天他实际要上几节自由课**。
- **`if (fixed > max) throw ConstructionInfeasible`**：固定课已经超了，
  **无论怎么排都违反** —— 这是**配置矛盾**，不是排课难度。
  交给求解器只会得到「无解」，用户看不懂原因；抛异常能直接指出是哪位老师第几天。
  ⚠ **文案里 `(day + 1)` 又变回 1 基**（内部 `day` 是 0 基）—— 因为那是给人看的。
- **`if (!vars.isEmpty())`**：⚠ **这个守卫不能省**。`vars` 为空时
  `sumPlus(∅, fixed)` = 常量 `fixed`，加进去就是 `fixed ≤ max` —— 前面已经保证
  `fixed ≤ max`，所以它是**恒真的冗余约束**：不影响正确性，但**会让 proto 多一条约束、指纹变化**。

```java
        } else {
            int totalPossible = fixed + vars.size();
            if (totalPossible <= max) { continue; }
            IntVar excess = model.newIntVar(0, totalPossible,
                    String.format("tday_exc_%d_%d", teacherId, day));
            model.addGreaterOrEqual(excess, HandlerSupport.sumPlus(vars, fixed - max));
            penalties.add(excess);
        }
```

- **软分支 = 一个「超出量」变量 + 一条下界不等式**：
  ```
  excess ∈ [0, totalPossible]
  excess ≥ Σ vars + fixed − max      ⟺   excess ≥ 实际节数 − 上限
  penalties.add(excess)              →   目标函数里加 excess（权重 = 规则权重）
  ```
  **数学上**：因为 `excess` 只被**下界**约束、且最小化目标里是**正系数**，
  最优解里它必然被压到下界，即
  **`excess = max(0, 实际节数 − max)`** —— 正好是「超了几节」。
  「用 `≥` 而不是 `=`」是刻意的：**`≥` 少一条等式**，而最小化会自动让它紧。
- **`if (totalPossible <= max) continue;`**：⚠ **短路**。当天最多也就 `totalPossible` 节，
  连上限都够不到 → **不可能超** → 不用建变量、不用加约束。
  不加这个短路的后果：给**每一位老师×每一天**都建一个 `excess` 变量，
  而其中绝大多数恒为 0 —— 目标函数里全是没用的零项。
- **`newIntVar(0, totalPossible, ...)` 的上界为什么是 `totalPossible`**：
  下界表达式最大能取到 `totalPossible − max`（`Σvars = vars.size()` 时），
  所以 `totalPossible` 是一个**安全（偏松）的上界** —— 它只需**装得下**下界，
  装不下模型就不可行（`excess` 无法满足 `≥`）。
- **变量名 `tday_exc_{teacherId}_{day}`**：⚠ 名字会进 proto，**改名 = 指纹变化**。

> ⚠ **本 handler 不检查 `teacher_id` 是否存在**（对比 `TeacherUnavailableHandler` 会检查）。
> 若参数引用了一个不存在的教师，`only != teacherId` 对所有真实教师都成立 →
> **整条规则静默失效**：不报错、不生效。这是从代码读出的**行为**；
> 上层（scheduling 的规则保存）是否有存在性校验，**本册未核**。

### 2.2 `TeacherUnavailableHandler` —— 教师不可用时段（92 行，硬软两用）

**对应的真实教务约束**：「张三周三第 5 节不可用（开会/外出）」。
**参数是 1 基的自然语言编号**（「周 3 第 5 节」），与前端表单一致；内部减 1。

**参数**：`teacher_id`（必填）、`day`（必填，`1..5`）、`period`（必填，`1..12`）。

> ⚠ **schema 的上界与内部下标差 1**：`intParam(P_DAY, "周几（1=周一）", 1, 5)`
> 上界是 **5**（不是 4），`P_PERIOD` 上界 **12**（不是 11）。
> **这是本模块最容易搞混的一对数字**，改 `intParam` 的界时要连 `- 1` 一起想。

> ⚠ **一条规则只封一个「天×节」**。要封「周三第 5 节 + 周四第 2 节」得建**两条规则**，
> 参数列表里没有「多时段」的形状。这是从 `params()` 读出的**能力边界**。

#### `apply` 逐行与数学含义

```java
CpModel model = ctx.model();
long teacherId = params.requireLong(P_TEACHER);
int day = params.requireInt(P_DAY) - 1;         // ← 1 基 → 0 基
int period = params.requireInt(P_PERIOD) - 1;   // ← 1 基 → 0 基

if (!ctx.teacherExists(teacherId)) {
    throw new ConstructionInfeasible("规则引用了不存在的教师 " + teacherId);
}

int fixed = ctx.fixedCountForTeacher(teacherId, day, period);
List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period);
```

- **`if (!ctx.teacherExists(...)) throw ConstructionInfeasible`**：⚠ **这一行是设计决策，不只是防呆**。
  不做它：`fixedCountForTeacher` 返回 0、`freeTeacherSlots` 返回空表，
  于是这条规则**完全不起作用** —— 「用户配了规则、没报错、也没效果」。
  **静默失效比抛异常糟得多。**
- **`ctx.freeTeacherSlots(teacherId, day, period)`**：该教师**这一个时段、跨所有班**的成员布尔
  （`period >= periodsOn(classId, day)` 的班跳过、固定课时段返回 `null` 被过滤掉）。
- **`day` / `period` 是「绝对节号」**：⚠ **第 8 节在只有 6 节课的低年级班上不存在** ——
  上面的过滤自然跳过。所以「给低年级老师封第 8 节」不会报错，也没必要报错。

```java
if (hard) {
    if (fixed > 0) {
        throw new ConstructionInfeasible(
                "教师 " + teacherId + " 在第 " + (day + 1) + " 天第 " + (period + 1)
                        + " 节既有固定课、又被设置为不可用，配置自相矛盾");
    }
    if (!vars.isEmpty()) {
        model.addEquality(LinearExpr.sum(HandlerSupport.args(vars)), 0);
    }
}
```

- **`if (fixed > 0) throw`**：**「固定课」与「不可用」不可能同时满足** ——
  固定课一定会出现在课表里（`TimetableExtractor` 直接取它，不经过求解器决策），
  而「不可用」要求这个时段不能有他的课。**这是配置矛盾，直接抛比「无解」有用。**
  ⚠ 文案里把 `day`/`period` **又转回 1 基**。
- **硬约束的数学形式**：
  ```
  addEquality( Σ vars , 0 )    且每个 var 的域是 {0,1}
  ⟺  该时段该教师在所有班的成员布尔全为 0
  ⟺  这个时段他一节课都不上
  ```
  ⚠ **「`Σ = 0` ⟺ 全为 0」这一步依赖「变量域非负」** ——
  因为 `BoolVar ∈ {0,1}`，任何一项为正都会让和大于 0。**如果这些变量是自由整数，`Σ = 0` 就有别的解。**
- ⚠ **没有 `sumPlus`（不加常数）**：这里要的就是「全 0」，而 `fixed > 0` 的情形已经抛了，
  所以不需要常数项。（对比 `SubjectWeeklyHoursHandler` 会加 `remaining` 常数。）
- **`if (!vars.isEmpty())`**：⚠ 同 §2.1 —— 空的 `Σ` 加 `= 0` 是恒真约束，省掉它免得改指纹。

```java
} else {
    if (fixed > 0) { penalties.addConstant(1); }
    for (BoolVar v : vars) { penalties.add(v); }
}
```

- **软分支 = 纯惩罚，不加任何约束**（解空间完全不变）：
  ```
  目标 +=  1·[该时段已有固定课]  +  Σ_{各班} teacherAt(t, c, day, period)
  ```
  即「用一次这个时段就罚 1 分」。
- **`penalties.addConstant(1)`**：固定课已经钉在那儿了，**改变不了，只能记在目标里**。
  ⚠ **注意它是 `1`（一条规则算一次），不是「固定课的节数」** ——
  与 `SubjectAvoidLastPeriodHandler` 的 `addConstant(fixedViolations)`（**天数**）
  形成对比。`addConstant` 内部是 `constant += currentWeight * value`，
  **所以它同样受当前权重影响**。
- **`for (BoolVar v : vars) penalties.add(v)`**：**逐个加**，不是 `add(sum(vars))`。
  ⚠ 权重为 1 时**目标值完全相同**（`PenaltyCollector` 把每个 `v` 都收进 `unitTerms`，
  最后 `addSum` 一起加），但**表达式的组织方式不同 → proto 不同 → 指纹不同**。
  这种「语义等价、proto 不等价」的差异是本项目里最容易在等价性比对时踩到的。

### 2.3 `SubjectAvoidLastPeriodHandler` —— 单科不排最后一节（114 行，硬软两用）

**对应的真实教务约束**：「语文/数学不排在当天最后一节」（最后一节学生状态差）。

**参数**：`subject_id`（`optionalInt`，**可省略** → 省略 = 所有科目）。

#### `apply` 逐行与数学含义

```java
CpModel model = ctx.model();
Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;

for (ClassData cd : ctx.classes()) {
    long classId = cd.classId();
    Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
    for (long subjectId : subjects) {
        if (!ctx.subjectsOf(classId).contains(subjectId)) { continue; }
```

- **`ctx.classes()` / `ctx.subjectsOf(classId)` 都保序** → 变量创建顺序确定。
  `subjectsOf` 内部是 `TreeSet<Long>`（**按科目 ID 升序**），所以「所有科目」这一档
  的遍历顺序也是确定的。
- **`only != null ? List.of(only) : ctx.subjectsOf(classId)`**：指定的科目 vs 该班全部科目。
- **`if (!ctx.subjectsOf(classId).contains(subjectId)) continue;`**：
  ⚠ **只在「指定了 `subject_id`」时起作用**（不指定时集合就来自 `subjectsOf`，条件恒真）。
  它的作用是：**指定了一个该班没有的科目时，安静地跳过这个班**。
  所以「`subject_id` 填了一个全年级都不存在的科目」= **整条规则静默失效**（不报错）。

```java
        List<BoolVar> lastPeriodVars = new ArrayList<>();
        int fixedViolations = 0;

        for (int day = 0; day < ctx.days(); day++) {
            int periods = ctx.periodsOn(classId, day);
            if (periods <= 0) { continue; }
            int last = periods - 1;
            long fixedSubject = ctx.fixedSubjectAt(classId, day, last);
            if (fixedSubject >= 0) {
                if (fixedSubject == subjectId) { fixedViolations++; }
                continue;
            }
            BoolVar b = ctx.subjectAt(classId, day, last, subjectId);
            if (b != null) { lastPeriodVars.add(b); }
        }
```

- **`int last = periods - 1`**：⚠ **本规则的正确性全在这一行。**
  javadoc 专门警告：「把「最后一节」写成常数（例如永远取第 8 节）会在低年级上
  **完全失效** —— 而且失效得很安静：**约束照加，只是加错了位置**。」
  因为各年级的 `periods_per_day` 可以不同（低年级 6 节、高年级 8 节）。
- **`if (periods <= 0) continue;`**：⚠ 守卫。该天 0 节时 `last = -1` 会去取一个不存在的时段。
  （数据上来自 `periodsPerDay` 的 Map：**缺键默认 6**，但显式填 `0` 会中招。）
- **`fixedSubjectAt(classId, day, last)` 返回 `-1` 表示「该时段不是固定课」**（约定：非固定课返回 -1）。
  - **是固定课** → **没有决策变量，与本次决策无关**：如果正好是目标科目 →
    `fixedViolations++`（一张**编译期常数**）；无论如何都 `continue`（不收集变量）。
  - **不是固定课** → `ctx.subjectAt(classId, day, last, subjectId)` 拿
    「该班该天最后一节是这门课」的成员布尔。
- **`fixedViolations` 的语义是「几天」**（每天最后一节最多算一次）。
  ⚠ 与 `MainSubjectMorningHandler` 的 `fixedViolations`（**节数**）语义不同 —— 两条的文案也相应不同。
- **`if (b != null)`**：⚠ 在正常数据下**不可达**
  （固定课已被上面 `continue` 掉；科目不属于该班已被 `contains` 挡掉；
  `subjectAt` 对非固定且属于该班的科目必返回非 null）。它是**防御性写法**，
  真实作用是保证 `lastPeriodVars` 里绝不会有 `null`。

```java
        if (hard) {
            if (fixedViolations > 0) {
                throw new ConstructionInfeasible(
                        "班级 " + classId + " 有 " + fixedViolations + " 天把科目 " + subjectId
                                + " 固定在最后一节，与「不排最后一节」冲突");
            }
            if (!lastPeriodVars.isEmpty()) {
                model.addEquality(LinearExpr.sum(HandlerSupport.args(lastPeriodVars)), 0);
            }
        } else {
            if (fixedViolations > 0) { penalties.addConstant(fixedViolations); }
            for (BoolVar b : lastPeriodVars) { penalties.add(b); }
        }
```

- **硬约束的数学形式**（注意是「按班×按科」各一条）：
  ```
  addEquality( Σ_{day} subjectAt(class, day, lastPeriodOf(day), subject) , 0 )
  ⟺  该班该科目在**任何一天**的最后一节都不出现
  ```
  求和里的变量数 = **该班这些天里「最后一节是自由时段」的天数**。
- **`if (fixedViolations > 0) throw`**：固定课已经把它摆在最后一节了 →
  与「不排最后一节」**直接矛盾**（固定课一定会出现在课表里）→ 抛异常指出是哪个班、几天。
- **软分支**：
  ```
  目标 += fixedViolations（常数，按规则权重放大）
  目标 += Σ_{day} subjectAt(class, day, last(day), subject)
  ```
  即「最后一节排一次这门课罚 1 分」+「已经被固定的违规按天罚」。
  ⚠ `addConstant(fixedViolations)` 传的**不是一个布尔而是一个计数**，
  与 §2.2 的 `addConstant(1)` 不同。

### 2.4 `MainSubjectMorningHandler` —— 主科优先上午（117 行，硬软两用）

**对应的真实教务约束**：「语文/数学/英语尽量排在上午」。

**参数**：

| 参数 | spec | 含义 |
|---|---|---|
| `subject_id` | `optionalInt` | 可省略 → 所有科目 |
| `morning_periods` | `intParam(..., 1, 12)` | **上午到第几节的「绝对节数」**（例如 4 = 前 4 节算上午） |

**为什么是绝对节数，而不是「按各班节数取一半」**（javadoc 第 25–27 行）：
「约束的语义应该**由学校说了算**，而不是由算法猜。各班节数不同时，「一半」会让
**不同年级的上午长度不一致**，反而制造出难以解释的课表。」

#### `apply` 逐行与数学含义

结构与 §2.3 **几乎完全相同**，只有「哪个时段算违规」变了：
`last`（一天一节）→ **`morningPeriods..periods-1` 的全部节**。

```java
int morningPeriods = params.requireInt(P_MORNING);

for (ClassData cd : ctx.classes()) {
    long classId = cd.classId();
    Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
    for (long subjectId : subjects) {
        if (!ctx.subjectsOf(classId).contains(subjectId)) { continue; }
        List<BoolVar> afternoonVars = new ArrayList<>();
        int fixedViolations = 0;

        for (int day = 0; day < ctx.days(); day++) {
            int periods = ctx.periodsOn(classId, day);
            for (int period = morningPeriods; period < periods; period++) {
                long fixedSubject = ctx.fixedSubjectAt(classId, day, period);
                if (fixedSubject >= 0) {
                    if (fixedSubject == subjectId) { fixedViolations++; }
                    continue;
                }
                BoolVar b = ctx.subjectAt(classId, day, period, subjectId);
                if (b != null) { afternoonVars.add(b); }
            }
        }
```

- **内层循环 `period = morningPeriods; period < periods; period++`**：
  - 下标是 **0 基**，所以第 `morningPeriods` 节（0 基）就是「上午 N 节」之后的**第一节下午课**；
  - ⚠ **「下午」不是「最后 N 节」**，而是「前 `morningPeriods` 节之后的**所有**节」；
  - ⚠ **`periods <= morningPeriods` 时内层循环一次都不执行** —— 这是**天然的守卫**
    （对比 §2.3 需要显式 `if (periods <= 0) continue;`，因为那里要算 `last = periods - 1`，
    这里根本不算下标）。**低年级一天只有 3 节、而 `morning_periods = 4` 时不会出错。**
- **没有 `periods <= 0` 的显式守卫**：因为循环条件本身就是守卫，`periods = 0` 时不会进循环。
- **`fixedViolations` 的语义是「节数」**（对比 §2.3 的「天数」）：同一科目可能一天被固定在
  下午的多个节次上，每个都算一次。文案也相应写「有 N **节**把科目…固定在下午」。
- **`if (b != null)`**：同 §2.3，防御性。

```java
        if (hard) {
            if (fixedViolations > 0) {
                throw new ConstructionInfeasible(
                        "班级 " + classId + " 有 " + fixedViolations + " 节把科目 " + subjectId
                                + " 固定在下午，与「主科不排下午」冲突");
            }
            if (!afternoonVars.isEmpty()) {
                model.addEquality(LinearExpr.sum(HandlerSupport.args(afternoonVars)), 0);
            }
        } else {
            if (fixedViolations > 0) { penalties.addConstant(fixedViolations); }
            for (BoolVar b : afternoonVars) { penalties.add(b); }
        }
```

- **硬约束的数学形式**：
  ```
  addEquality( Σ_{day, period ≥ morningPeriods} subjectAt(class, day, period, subject) , 0 )
  ⟺  该班该主科一节都不排在下午
  ```
  **这是 hard 模式的语义** —— javadoc 说它「更常见的用法」其实是 **soft**：
  硬模式很可能直接不可行（主科周课时多、上午装不下），
  于是变成「一配就无解」，用户会以为是 bug。
- **软分支**：
  ```
  目标 += fixedViolations  +  Σ_{下午的每一节} subjectAt(class, day, period, subject)
  ```
  **每排到下午一节罚 1 分** —— 求解器会**尽量**把主科塞进上午，塞不下时才付罚分。

> ⚠ **两条规则的 `fixedViolations` 含义不同（天数 vs 节数），是本册最容易读错的一处。**
> 判断方法：看它在**几层循环里自增** —— 在 `day` 循环体里（`last` 相关）是**天**；
> 在 `day × period` 双层里是**节**。

### 2.5 `TeacherMinimizeGapsHandler` —— 教师课表不留空档（161 行，硬软两用）

**对应的真实教务约束**：「老师的课尽量连着上，别上午一节、下午一节」。
**它是本册 5 条里唯一需要「辅助变量」的一条**，也是**最贵的一条**。

**参数**：`teacher_id`（`optionalInt`，可省略 → 所有教师）。

#### 2.5.1 为什么这条最麻烦（javadoc 第 22–25 行）

前 4 条都是「把某个变量加起来和常数比一比」。**空档不是「量的大小」，而是「量的形状」** ——
要知道某节课是不是**夹在**「这天第一节课」和「这天最后一节课」**之间**。
「夹在中间」不是线性表达式能直接写出的东西，**必须引入辅助变量把它「表示」出来**。

#### 2.5.2 建模：空洞计数（不用 first/last！）

**常见写法**是求 `first` / `last`（最小/最大）再算 `(last − first + 1) − 课时数`。
**本项目明确不用它**（javadoc 第 28–30 行），理由三条：

1. 要引入 `addMinEquality` / `addMaxEquality`（最小/最大等式）**和哨兵**；
2. **「当天没课」要单独处理**，否则 `last − first + 1` 会算出**负数**（典型坑：`last`、`first`
   的哨兵取值不当 → 空档数变成 −1 或一个巨大的值）；
3. 辅助变量数也不少。

**改用等价的「逐节判定」写法**（O(n) 个辅助变量、**不需要哨兵、不需要额外分支**）：

```
busy[p]   : 该教师这天第 p 节有课
before[p] : 第 p 节之前有课   = OR(busy[0..p-1])      （递推：before[p] = before[p-1] OR busy[p-1]）
after[p]  : 第 p 节之后有课   = OR(busy[p+1..n-1])    （递推，从后往前）
hole[p]   : before[p] AND after[p] AND NOT busy[p]
空档数     = Σ hole[p]
```

**`hole[p]` 就是「空档」的定义**：第 p 节**没课**，但它**前面有课、后面也有课**。
三个条件**缺一不可**：

| 少了哪一个 | 会把什么误判成空档 |
|---|---|
| 少 `before[p]` | 「当天第一节课**之前**」的空位 —— 那是**上班前**，不是空档 |
| 少 `after[p]` | 「最后一节课**之后**」的空位 —— 那是**放学后**，不是空档 |
| 少 `¬busy[p]` | **有课的节次本身** —— 这是最容易漏的一个 |

**它天然处理两个边界**（javadoc 第 40–45 行）：

| 边界 | 为什么自带答案 |
|---|---|
| **当天没课** | 所有 `busy = 0` → 递推得所有 `before = 0` → 没有 `hole` → **空档数 0** ✅（**这正是 first/last 写法容易算出负数的那个坑**） |
| **各年级节数不同** | 某节之后没课了 → `after` 递推自然为 0 → 不会被误判成空档 ✅ |

#### 2.5.3 正确性验证：拿 5 节的一天当例子

`n = 5`，列成 `busy` 模式（1 = 有课）：

| `busy` | `before` | `after` | `hole` | 空档数 | 是否符合直觉 |
|---|---|---|---|---|---|
| `0 0 0 0 0` | `0 0 0 0 0` | `0 0 0 0 0` | `0 0 0 0 0` | **0** | 当天没课 → 不是「4 个空档」✅ |
| `1 0 0 0 0` | `0 1 1 1 1` | `0 0 0 0 0` | `0 0 0 0 0` | **0** | 只有第一节有课，后面是放学 ✅ |
| `0 0 0 0 1` | `0 0 0 0 0` | `1 1 1 1 0` | `0 0 0 0 0` | **0** | 只有最后一节有课，前面是没上班 ✅ |
| `0 1 0 1 0` | `0 0 1 1 1` | `1 1 1 0 0` | `0 0 1 0 0` | **1** | 第 3 节夹在两节课中间 ✅ |
| `1 0 1 0 1` | `0 1 1 1 1` | `1 1 1 1 0` | `0 1 0 1 0` | **2** | 第 2、4 节是空档 ✅ |
| `1 1 1 1 1` | `0 1 1 1 1` | `1 1 1 1 0` | `0 0 0 0 0` | **0** | 全满，无空档 ✅ |

#### 2.5.4 `apply`：入口与短路

```java
CpModel model = ctx.model();
Long only = params.has(P_TEACHER) ? params.requireLong(P_TEACHER) : null;

for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
    long teacherId = teacher.id();
    if (only != null && only != teacherId) { continue; }
    for (int day = 0; day < ctx.days(); day++) {
        int maximumLessons = ctx.fixedCountForTeacherOnDay(teacherId, day)
                + ctx.freeTeacherSlotsOnDay(teacherId, day).size();
        if (maximumLessons <= 1) { continue; }   // 一天最多一节，不可能出现空档
        applyForTeacherDay(ctx, model, penalties, teacherId, day, hard);
    }
}
```

- **`maximumLessons = 固定课数 + 自由时段数`**：这位老师这天**最多能上几节**。
- **`if (maximumLessons <= 1) continue;`**：⚠ **性能上的必要短路**，不是可有可无的优化。
  - **逻辑上正确**：只有 0 或 1 节课，不可能形成「夹在中间」的空档
    （与测试里独立的 `countTeacherGaps` 实现一致：`if (periods.size() <= 1) continue;`）；
  - **不加会怎样**：每个「教师 × 天」都会建 4n 个辅助变量，
    **哪怕这位老师那天只有一节课** —— 而那种情形**根本不可能有空档**。
    javadoc 的成本提示说「对 36 教师 × 5 天 × 8 节大约是 5,700 个变量，是这些规则里最贵的一条」，
    这个短路是**抑制成本的主要手段**。
- **⚠ 本 handler 同样不检查 `teacher_id` 是否存在** → 不存在的教师 = **静默失效**（同 §2.1）。
- **`applyForTeacherDay` 抽成私有方法**：它自己重算 `n = ctx.maxPeriodsOn(day)`，
  不再从外面传参 —— 少一个可能传错的参数。

#### 2.5.5 `applyForTeacherDay` 的四组辅助变量

```java
int n = ctx.maxPeriodsOn(day);
if (n <= 1) { return; }
String tag = String.format("tg_%d_%d", teacherId, day);
```

- **`n = ctx.maxPeriodsOn(day)`**：⚠ **取「跨所有班的最大节数」** ——
  因为一位老师可能同时在 6 节的班和 8 节的班上课。
  **如果错取成某个班的节数**：这位老师在节数更多的班里还有课这件事会被漏掉，
  `after` 会错误地变 0 → **空档漏报**。
- **`if (n <= 1) return;`**：第二道守卫（与 `apply` 的短路呼应）。

**① `busy[p]`：该教师这天第 p 节有课**

```java
BoolVar[] busy = new BoolVar[n];
for (int p = 0; p < n; p++) {
    busy[p] = model.newBoolVar(tag + "_busy_" + p);
    int fixed = ctx.fixedCountForTeacher(teacherId, day, p);
    List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, p);
    if (fixed > 0) {
        model.addEquality(busy[p], 1);
    } else if (vars.isEmpty()) {
        model.addEquality(busy[p], 0);
    } else {
        HandlerSupport.encodeOr(model, busy[p], HandlerSupport.literals(vars));
    }
}
```

三路分支的数学含义：

| 分支 | 加的约束 | 含义 |
|---|---|---|
| `fixed > 0` | `busy[p] = 1` | **钉成真**：固定课在，这个时段一定有课 |
| `vars.isEmpty()` | `busy[p] = 0` | **钉成假**：跨所有班都没有可上的任教关系 → 必定没课 |
| 否则 | `busy[p] ⟺ OR(vars)` | **双向等价**（§0.3）：`busy[p] = 1` **当且仅当**他在这时段的某个班上课 |

⚠ **三处细节**：
- **判的是 `fixed > 0` 而不是 `== 1`**：`fixed > 1` 意味着固定课自己就撞车了，
  那应该由 `TeacherNoConflictHandler` 抛出来（它排在 `BuiltinRules` 第一位，
  所以**正常情况下走不到这里**）。对「有没有课」这个问题，上两次也还是「有课」，所以钉 1 是对的。
- **② 分支（`vars.isEmpty()` → 钉 0）非常关键**：不显式钉 0，`busy[p]` 就是一个
  **完全自由的布尔**，求解器可以随意取值 —— 而它**没有出现在任何 OR 编码里**
  （这是「空 OR」），于是**空档计数会被求解器利用**（把 `busy` 填 1 来吞掉 `hole`）。
- **`encodeOr` 必须双向**：⚠ **只写「`busy → OR(vars)`」这一半**，`busy` 就可以在
  「有课」时取 0 → `before`/`after` 递推错 → **空档漏报**（惩罚变小 →
  求解器倾向于把课排得零散**而不受罚**）。**不报错、不崩，只是结果变差。**

**② `before[p]`：第 p 节之前有课**

```java
BoolVar[] before = new BoolVar[n];
before[0] = HandlerSupport.boolConst(model, tag + "_bf_0", false);
for (int p = 1; p < n; p++) {
    before[p] = model.newBoolVar(tag + "_bf_" + p);
    HandlerSupport.encodeOr(model, before[p],
            new Literal[] {before[p - 1], busy[p - 1]});
}
```

- **`before[0] = boolConst(..., false)`**：⚠ **用「恒假布尔」而不是「跳过第 0 项」** ——
  这样递推就能**统一**写成 `before[p] ⟺ before[p-1] ∨ busy[p-1]`，不用写 `if (p == 1)` 特例。
  代价是**多一个被钉死的变量**（`boolConst` = `newBoolVar` + `addEquality(v, 0)`，§0.1）。
- **递推展开（归纳法）**：
  `before[1] = false ∨ busy[0] = busy[0]`；
  `before[2] = before[1] ∨ busy[1] = busy[0] ∨ busy[1]`；…
  ⇒ **`before[p] = OR(busy[0..p-1])`** = 「第 p 节**之前**有任何一节有课」。
- 变量名 `tg_{teacherId}_{day}_bf_{p}`。

**③ `after[p]`：第 p 节之后有课**

```java
BoolVar[] after = new BoolVar[n];
after[n - 1] = HandlerSupport.boolConst(model, tag + "_af_" + (n - 1), false);
for (int p = n - 2; p >= 0; p--) {
    after[p] = model.newBoolVar(tag + "_af_" + p);
    HandlerSupport.encodeOr(model, after[p],
            new Literal[] {after[p + 1], busy[p + 1]});
}
```

- 与 `before` **完全镜像**：**从后往前**递推，哨兵在 `n-1`（最后一节之后必然没课）。
- 展开 ⇒ **`after[p] = OR(busy[p+1..n-1])`** = 「第 p 节**之后**有任何一节有课」。

**④ `hole[p]`：空档 + 硬软落点**

```java
List<BoolVar> holes = new ArrayList<>(n);
for (int p = 0; p < n; p++) {
    BoolVar hole = model.newBoolVar(tag + "_hole_" + p);
    HandlerSupport.encodeAnd(model, hole,
            new Literal[] {before[p], after[p], busy[p].not()});
    holes.add(hole);
}

if (hard) {
    model.addEquality(LinearExpr.sum(HandlerSupport.args(holes)), 0);
} else {
    for (BoolVar hole : holes) {
        penalties.add(hole);
    }
}
```

- **`hole[p] ⟺ before[p] ∧ after[p] ∧ ¬busy[p]`**（`busy[p].not()` 就是第三个条件 `¬busy[p]`）。
  同样**必须双向**：单向会让 `hole` 可以取 0 → 惩罚漏报。
- **硬模式**：
  ```
  addEquality( Σ hole[p] , 0 )   ⟺   这天一个空档都没有（课表连续）
  ```
- **软模式**（更常见的用法）：
  ```
  目标 += Σ_p hole[p]     即「每个空档罚 1 分」
  ```

#### 2.5.6 成本核算（javadoc 第 49–51 行）

每个「教师 × 天」自建的辅助变量：

| 组 | 个数 |
|---|---|
| `busy` | n |
| `before` | n（含 1 个 `boolConst` 哨兵） |
| `after` | n（含 1 个 `boolConst` 哨兵） |
| `hole` | n |
| **合计** | **恰好 4n** |

⚠ **是「恰好 4n」而不是「略少于 4n」**：哨兵是用 `boolConst` 造的
**真变量**（`newBoolVar` + `addEquality(v, 0)`），**占变量下标、进 proto**，
不是常量字面量。javadoc 说「约 4n」是保守说法。

代入 javadoc 的例子：36 教师 × 5 天 × 8 节 = 36 × 5 × 4 × 8 = **5,760**，
与「大约 5,700 个变量」吻合。**这还没算** `ctx.freeTeacherSlots(...)` 懒建的
`ss_*` / `tc_*` 成员布尔和它们内部 `membership()` 的 `_eqN` 重化变量 ——
所以**真实增量比 4n 更大**。

> ⚠ **正因为贵，它是 `mandatory() == false`，且 `legacyEquivalent()` 里没有它** ——
> 默认配置下它**不在模型里**。想用必须在规则集里显式加一条 `teacher_minimize_gaps`。
> **并且它没有「一天最多 1 节」之外的短路：`maximumLessons = 2` 的教师同样会建满 4n 个变量。**

---

## 3. 小结与交叉对照

### 3.1 5 条 handler 一页对照

| 规则 `TYPE` | 真实约束 | 加什么 | 硬模式（数学） | 软模式（目标项） | 参数 |
|---|---|---|---|---|---|
| `teacher_max_daily_periods` | 老师一天别超 N 节 | 每(教师,天) 1 个 `excess` | `Σvars + fixed ≤ max` | `Σ excess = Σ max(0, 节数−max)` | `teacher_id?`、`max`(1..20) |
| `teacher_unavailable` | 老师某时段不可用 | 无新变量 | `Σ vars = 0`（该时段全部为 0） | `[fixed>0] + Σ vars` | `teacher_id`、`day`(1..5)、`period`(1..12) |
| `subject_avoid_last_period` | 某科不排最后一节 | 无新变量 | `Σ_days subjectAt(·, last(day), ·) = 0` | `fixedViolations(天) + Σ b` | `subject_id?` |
| `main_subject_morning` | 主科尽量排上午 | 无新变量 | `Σ_{下午各节} subjectAt = 0` | `fixedViolations(节) + Σ b` | `subject_id?`、`morning_periods`(1..12) |
| `teacher_minimize_gaps` | 老师课表别留空档 | 每(教师,天) **4n** 个 | `Σ hole[p] = 0` | `Σ hole[p]`（每个空档 1 分） | `teacher_id?` |

- **`?` = 可省略，省略即「适用于所有」**（所有教师 / 所有科目）。
- **「无新变量」**：前 4 条的辅助量全是**编译期常数**（`fixed` / `fixedViolations`），
  唯一的变量是 `SlotVariableIndex` 已经建好的成员布尔。

### 3.2 三处「语义相同、proto 不同」（等价性比对时的地雷）

| 位置 | 写法 A | 写法 B | 后果 |
|---|---|---|---|
| `TeacherUnavailableHandler` 软分支 | `penalties.add(v)` 逐个 | `penalties.add(sum(vars))` | 目标值相同、**proto 不同 → 指纹不同** |
| `TeacherMaxDailyPeriods` 软分支 | `sumPlus(vars, fixed - max)` | 手写 builder（`SubjectMaxPerDay` 那样） | 常见情形等价；`vars` 空或 `fixed == max` 时**表达式形状不同** |
| `if (!vars.isEmpty())` 守卫 | 省（不加恒真约束） | 不省（加 `0 ≤ max` 之类恒真式） | 可行域相同、**约束数不同 → 指纹不同** |

**结论**：**指纹比对只能证「不同」，不能证「错」** —— 遇到指纹不一致时，
先按上面三处逐一排除，再怀疑真实语义差异。

### 3.3 本册记录的全部 ⚠（按「会怎么坑你」排序）

| # | 陷阱 | 症状 |
|---|---|---|
| 1 | `SubjectAvoidLastPeriod` 的 `last` 若写成常数 | **约束照加、只是加错位置**，低年级完全失效 |
| 2 | `TeacherMinimizeGaps` 的 `onlyEnforceIf` 只写单向 | 惩罚漏报 → 求解器把课排得更散**而不受罚**，不报错 |
| 3 | 编译顺序（`BuiltinRules` 注册顺序 / 规则集顺序）改动 | 变量下标变 → 指纹变 → `GoldenModelTest` 红 |
| 4 | `penalties.useWeight` 与 `apply` 的顺序 | **下一条**软规则用错权重 |
| 5 | `TeacherUnavailable` / `SubjectAvoidLastPeriod` / `MainSubjectMorning` / `TeacherMinimizeGaps` 引用了不存在的 ID | **除了 `TeacherUnavailable` 会抛异常外，其余三条静默失效**（不报错、不生效） |
| 6 | `MainSubjectMorning` hard 模式 | 主科周课时多、上午装不下 → **一配就无解**，用户以为是 bug |
| 7 | `P_DAY` 上界 5 / `P_PERIOD` 上界 12（1 基）与内部 0 基 | 改 schema 边界时忘了 `- 1` |
| 8 | `TeacherMaxDailyPeriods` / `TeacherMinimizeGaps` 的 `fixedViolations` 语义（**节** vs **天**，见 §2.3 vs §2.4） | 文案与惩罚量写错 |
| 9 | `effectiveWeight()` 把 `weight <= 0` 掰成 1 | 想用「权重 0」停用规则 → 实际生效；应改 `enabled` |
| 10 | `CompiledModel.modelFingerprint` 抠不到时是 `"(未找到)"` | 两个不同模型可能比出「相等」 |

> ⚠ **本册只做记录，未改动任何源码**（规范红线 ④）。
> 另有两处**实测发现**（放在这里供后续核对，不属于本册 9 个类）：
> ① `OrbitSolver.builtinRules()` 的 javadoc 写「+ 2 个可配置」，实际是 **7 个**；
> ② `HandlerSupport.negations(...)`（第 26–32 行）在本模块内**没有调用者**（`encodeOr`/`encodeAnd` 用的是私有 `negate`）。

### 3.4 一张图看懂这一册

```
RuleSetService / RuleTypeCatalog（scheduling）
        │ 规则数据（constraint_rules 表）/ 能力清单
        ▼
BuiltinRules.registry()  ── 装配：10 个 handler，顺序 = 变量创建顺序
        │
        ▼
RuleCompiler.compile(input, rules)
  ① new CpModel()
  ② SlotVariableIndex.create(model, input)   ← 懒建变量；唯一抛 ConstructionInfeasible 处
  ③ new RuleContext(index)                   ← 规则作者的世界
  ④ new PenaltyCollector(model)              ← 软约束收集器（有状态：currentWeight）
  ⑤ registry.mandatory() 逐个 apply(hard=true, 空参数)      ← 不变量/数据驱动
  ⑥ for each enabled rule: registry.get → 跳过 mandatory → validate → useWeight → apply
  ⑦ penalties.commit() → model.minimize(Σ 项) 或「不设目标」
  ⑧ modelStats() + 抠指纹 → CompiledModel
        │
        ▼
CompiledModel(model, index, context, modelStats, modelFingerprint)
        │
        ▼
OrbitSolver.solve → CpSolver → TimetableExtractor（用 index 还原课表）
```
