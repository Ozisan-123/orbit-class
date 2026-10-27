# 代码导读 · 2c. `solver-core` 内置规则（上）：3 条不变量 + 2 条可配置 + 公共工具

> 本册范围：`backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/handler/`
> 中的 **6 个文件**：
> `HandlerSupport`、`TeacherNoConflictHandler`、`SubjectWeeklyHoursHandler`、
> `TeacherWeeklyLimitHandler`、`SubjectMaxPerDayHandler`、`SubjectNoConsecutiveHandler`。
>
> 上游：[代码导读.md](代码导读.md)（第 1 册，格式基准）、
> [代码导读-2-solver-core.md](代码导读-2-solver-core.md)（第 2 册，§6 也对这 10 条 handler 做了概览）。
>
> **本册的立场**：第 2 册的 §6 已经把这 10 条 handler 逐条走了一遍；
> **本册不重复它的内容，专门讲"数学含义"** —— 每一行 CP-SAT 调用在
> $\text{0/1}$ 变量上到底约束了什么、为什么写成这个形状、写成别的形状会怎样。
> 剩下的 5 条 handler（`TeacherMaxDailyPeriodsHandler`、`TeacherUnavailableHandler`、
> `SubjectAvoidLastPeriodHandler`、`MainSubjectMorningHandler`、`TeacherMinimizeGapsHandler`）
> 属于第 2d 册，**不在本文范围内**。

## 本模块在系统里的位置

`rule/handler/` 里装的**不是业务数据、也不是引擎**，而是「**教务约束的 CP-SAT 表达**」：

- **上游**：`compile.RuleCompiler` 在 `compile(input, rules)` 里依次调用它们的 `apply(...)`。
  三条 `mandatory()` 的**无条件施加**，其余七条**只在规则集显式引用时才施加**。
- **下游**：它们只通过 `rule.RuleContext` 拿变量（**永远不直接 new 变量**，唯一的例外见 §2 的
  `boolConst` 与 §5/§6 里为软约束新建的辅助变量），把约束加进 `CpModel`，
  把软约束的惩罚项交给 `rule.PenaltyCollector`（最后由它统一 `minimize`）。
- **它守的东西**：`GoldenModelTest` 把整个模型的变量数与指纹固化成断言，
  所以下面每一行改动的**实际后果都是"指纹变红"**，而不是"悄悄换个解"。

## 阅读顺序建议

1. **`HandlerSupport`**（§2）—— 6 个文件里唯一的工具类，读懂它，后面 5 个 handler 里的
   `args` / `sumPlus` / `maxPeriodsOnDay` 就不再是黑盒。
2. **`TeacherNoConflictHandler`**（§3）—— **最短、最典型**的一条：三层循环 + 一条不等式，
   把"教师不撞课"这件事讲清楚了，另外两条 mandatory 只是换个维度（科目、教师整周）重复同一个套路。
3. **`SubjectMaxPerDayHandler`**（§5）—— **本册唯一藏着线性化技巧的一条**（软约束里的
   "单向下界"），也是唯一在硬/软两条路径上给出**不同后果**（抛异常 vs 交给求解器）的一条。
4. 其余两条（`SubjectWeeklyHoursHandler` §4、`TeacherWeeklyLimitHandler` §4、
   `SubjectNoConsecutiveHandler` §6）按兴趣挑。

---

## 0. 先补一堂 5 分钟的 CP-SAT 数学课（后面每一行都要用）

本文反复用到 4 个原语，先把它们的**数学含义**钉死。这些结论都来自 OR-Tools 的建模语义
（`ortools-java 9.15.6755`，用 `javap` 核对过签名，见 §7 的 API 速查表）：

| 代码 | 数学含义 | 本册哪里出现 |
|---|---|---|
| `model.addLessOrEqual(expr, k)` | $expr \le k$ | §3 §4 §5 |
| `model.addEquality(expr, k)` | $expr = k$ | §4 §5 §6（`boolConst`） |
| `model.addGreaterOrEqual(expr, other)` | $expr \ge other$ | §5 软分支 |
| `c.onlyEnforceIf(t)` | $t \Rightarrow c$（**前提为真时约束才成立**） | §6、`HandlerSupport` 的 `encodeOr/And` |
| `model.minimize(expr)` | 求 $expr$ 的**最小值**（CP-SAT 的求解阶段会优先满足它） | 软约束的落点 |

**三个关于"布尔即 0/1 整数"的事实**（`javap` 已确认，不是推测）：

1. `BoolVar extends IntVar implements Literal`
   —— 所以同一个 `BoolVar` **既能当线性项（`LinearArgument`），又能当字面量（`Literal`）**。
   这就是为什么 `HandlerSupport.args(...)` 与 `literals(...)` 两个转换方法都存在。
2. `IntVar implements LinearArgument` —— 所以 `newIntVar` 造的整数变量
   （例如 §5 的 `excess`）**可以直接扔进 `addGreaterOrEqual` / 线性表达式**。
3. `Literal.not()` 返回 `NotBoolVar`（`Literal` 的另一种实现）。
   所以 `v.not()` **既是字面量也是线性项**：`sum(v.not())` 在数学上等于
   $\sum (1 - v)$ —— **但这个恒等式在本册里没有被用到**，写出来只是因为
   `HandlerSupport` 的 `negations` 返回的正是这种"取反后的字面量"。

> ⚠ **本册的一个反直觉结论，先说在前面**：
> **代码里没有任何一处调用 `addAtMostOne` / `addExactlyOne`。**
> 项目里"最多一个 / 恰好 k 个"全部是用
> `addLessOrEqual(...)` / `addEquality(...)` **手写线性不等式**表达的。
> 两者在 CP-SAT 里语义等价，但**写线性不等式会改变 `CpModelProto`**，
> 因此**会改变模型指纹** —— 所以这是刻意的选择，不能"顺手换成更漂亮的 API"。

### 0.2 「顺序敏感」到底指什么

`mandatory()` 为真的 handler **不接受配置、也无法被规则集关闭**，
但它们**有一个顺序**。这个顺序定义在
[BuiltinRules](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/compile/BuiltinRules.java)
的注册列表里：

```java
return new RuleRegistry(List.of(
        // —— 不变量 / 数据驱动（mandatory，始终生效，顺序敏感）——
        new TeacherNoConflictHandler(),
        new SubjectWeeklyHoursHandler(),
        new TeacherWeeklyLimitHandler(),
        // —— 可配置（仅当规则集引用时生效）——
        new SubjectMaxPerDayHandler(),
        ...
```

`RuleRegistry` 内部是 `LinkedHashMap`（保序），`RuleRegistry.mandatory()` 按注册顺序过滤，
`RuleCompiler` 再按这个顺序 `for` 一遍：

```java
// RuleCompiler.compile(...)
for (RuleHandler handler : registry.mandatory()) {
    handler.apply(ctx, RuleParams.empty(), true, penalties);
}
```

所以 **"顺序敏感" = 这三次 `apply` 的先后决定了模型里变量的创建次序**，
因为变量是**懒创建 + 缓存**的（`SlotVariableIndex`）：

- 三条在**同一个 `CpModel` 上**顺序执行；
- 变量**第一次被请求时**才 `newBoolVar`/`newIntVar`，此后缓存复用；
- 于是"谁先请求"决定了变量在 `CpModelProto.variables` 数组里的**下标**，
  也决定了约束在 `constraints` 数组里的先后。

⚠ **后果是具体的**：`GoldenModelTest` 断言的
**规则系统指纹 `0x3cbfb67312cda176`** 是对整个 `CpModelProto` 序列化后算的摘要，
**只要调整上面三行 `new` 的顺序，指纹就会变**（模型在数学上等价，但 proto 不同）。
所以改动这个列表**不是"整理代码"，是改模型**。

**为什么偏偏是这三条排在前面**（它们之间的语义关系）：

| 次序 | handler | 它在模型里第一次创建的东西 |
|---|---|---|
| 1 | `TeacherNoConflictHandler` | 教师成员布尔（`tc_t*_c*_d*_p*`，三参版 `freeTeacherSlots`）—— **量最大的一批** |
| 2 | `SubjectWeeklyHoursHandler` | 科目成员布尔（`ss_*_d*_p*_*`） |
| 3 | `TeacherWeeklyLimitHandler` | **不新建教师成员布尔**，复用第 1 条已经建好的同一批（见 §7.1） |

> 换言之：**顺序敏感不是因为数学，而是因为"变量下标进指纹"**。
> 如果哪天改了 `SlotVariableIndex` 让变量预建（而不是懒创建），
> 这个顺序敏感性就会消失 —— 但**指纹会变**，那是另一回事。

### 0.3 「可配置」到底指什么

`SubjectMaxPerDayHandler` 与 `SubjectNoConsecutiveHandler` 的 `mandatory()` **没有被覆写**，
用的是接口默认实现：

```java
// RuleHandler
default boolean mandatory() {
    return false;
}
```

于是它们在两个地方被区别对待：

**① 它们不会在 `RuleCompiler` 的第 1 步被自动施加**（只 `registry.mandatory()` 那一档跑）。

**② 它们必须由"规则集"显式引用** —— 规则集是 `List<ConstraintRule>`（一行 = `constraint_rules`
表里的一行），`RuleCompiler` 第 2 步才遍历它：

```java
for (ConstraintRule rule : rules) {
    if (!rule.enabled()) { continue; }               // 行被禁用 → 不生效
    RuleHandler handler = registry.get(rule.type()); // type 字符串 → 实现
    if (handler.mandatory()) { continue; }           // 不变量不接受外部配置（防重复施加）
    List<String> errors = rule.params().validate(handler.params());  // 参数校验
    if (!errors.isEmpty()) {
        throw new IllegalArgumentException(
                "规则 " + rule.type() + " 参数非法: " + String.join("; ", errors));
    }
    penalties.useWeight(rule.effectiveWeight());     // 设"后续 add 的权重"
    handler.apply(ctx, rule.params(), !rule.isSoft(), penalties);   // hard = !soft
}
```

**这一段就是"只有规则集显式引用时才生效"的全部机制**，逐条值得记住：

- `registry.get(rule.type())` 用的是 **`type()` 返回的字符串**
  （`"subject_max_per_day"`），**不是类名**。拼错一个字 → `IllegalArgumentException: 未知的规则类型`。
- `params().validate(...)` 是**唯一**的参数校验入口，校验失败是**异常**而不是"忽略这条规则"。
  ⇒ 一条参数写错的规则会让**整次编译失败**，而不是静默跳过。
- `penalties.useWeight(rule.effectiveWeight())` 是一个**有状态的副作用**：
  它设置 `PenaltyCollector` 里"后续 `add` 用哪个权重"（§7.3）。
  ⚠ **注意它设了之后就不再复位** —— 但下一轮循环一定会重新设，所以只要每次 `apply` 前都调它就没问题
  （`RuleCompiler` 确实是这么写的）。
- `handler.apply(ctx, rule.params(), !rule.isSoft(), penalties)` —— **硬软的判定是 `!isSoft()`**。
  所以 `ConstraintRule.hard(...)` → `hard = true`（加约束），
  `ConstraintRule.soft(...)` → `hard = false`（加惩罚项）。
- ⚠ `if (handler.mandatory()) continue;` 是**防重复施加**：规则表里如果有人写了一条
  `teacher_no_conflict`，它**被静默忽略**（不报错、不重复加约束）。

**它们从哪来**：`compile.RuleSets.legacyEquivalent()` 是**唯一的内置预设**，
它显式列出的正是这两条：

```java
return List.of(
        ConstraintRule.soft(SubjectMaxPerDayHandler.TYPE,
                RuleParams.of(Map.of("max", 1)), 1, "任何科目一天最多 1 节"),
        ConstraintRule.soft(SubjectNoConsecutiveHandler.TYPE,
                RuleParams.empty(), 1, "任何科目不连排两节"));
```

这两行就是「Python 的软 1 / 软 2」在 Java 侧的等价物（`RuleSets` 的 javadoc 有完整映射表）。
**没有这两行，就没有这两条约束** —— 这正是"可配置"的含义。

---

## 2. `HandlerSupport` —— 6 个文件共用的 8 个工具方法（78 行）

**一句话定位**：`rule.handler` 包的**私有工具箱**，把"`List<BoolVar>` ↔ CP-SAT 的各种数组/表达式"
这层样板代码收在一处。

**谁调它**：**只有 `rule.handler` 包内的 handler**。类声明是**包级私有**：

```java
final class HandlerSupport {          // 没有 public
    private HandlerSupport() {        // 纯静态工具类，禁止实例化
    }
```

⚠ **`public final class` 与 `final class` 的区别在这里是有意的**：
`OrbitSolver` / scheduling / 其它包**看不到这个类**，所以它**不可能成为对外 API**，
将来改名/删方法不会破坏任何外部调用者。

**逐方法**：

### 2.1 `static LinearArgument[] args(List<BoolVar> vars)`

```java
return vars.toArray(new LinearArgument[0]);
```

把布尔列表转成 **`LinearArgument[]`**（线性表达式接受的最小单位）。

⚠ **为什么不能直接 `vars.toArray(new BoolVar[0])` 再传**：Java 的数组协变只对
"子类型数组 → 父类型数组"有效，而 `BoolVar` 与 `LinearArgument` 之间是
**接口实现关系**（`BoolVar extends IntVar implements Literal`，`IntVar implements LinearArgument`）。
`BoolVar[]` **不是** `LinearArgument[]`，必须显式转换（`toArray` 里传目标类型即可）。

⚠ 参数用 `List<BoolVar>` 而**不是** `Collection<BoolVar>`：
`RuleContext.freeXxxSlots` 返回的都是 `List`，所以够用；**改成 `Collection` 会更通用**，
但当前没有这个需求。

### 2.2 `static Literal[] literals(List<BoolVar> vars)`

```java
return vars.toArray(new Literal[0]);
```

同上，但目标是 **`Literal[]`** —— 给 `addBoolOr` / `addBoolAnd` 用。

⚠ **`args` 与 `literals` 做的是"同一个列表的两种视图"**，因为 `BoolVar` 同时是
`LinearArgument` 与 `Literal`（§0 的事实 1）。**调用者必须自己选对**：
- 想构造 `sum(vars) ≤ k` → 用 `args`
- 想构造 `OR(literals)` / `AND(literals)` → 用 `literals`

⚠ **本册 6 个文件里，`literals` 一次都没被调用**（唯一调用者在
`TeacherMinimizeGapsHandler` 第 122 行）。

### 2.3 `static Literal[] negations(List<BoolVar> vars)`

```java
Literal[] out = new Literal[vars.size()];
for (int i = 0; i < vars.size(); i++) {
    out[i] = vars.get(i).not();
}
return out;
```

**批量取反**：返回 $[\lnot v_1, \lnot v_2, \dots]$。

⚠ **`out` 的类型是 `Literal[]` 而不是 `BoolVar[]`**，因为 `Literal.not()` 的返回类型是
`Literal`（运行时是 `NotBoolVar` —— §0 的事实 3）。
`NotBoolVar` 同样 `implements Literal` 且 `implements LinearArgument`，
所以这个数组**既能喂给 `addBoolAnd`，也能参与线性表达式**（`sum(v.not()) = n - sum(v)`）。

> ⚠ **实测：`negations` 在整个 `backend-java` 里没有任何调用者**
> （用 `grep -r "negations" backend-java` 只有它自己的定义那一行）。
> `RoleCompiler`/所有 handler 都是**手工写 `new Literal[]{ a.not(), b.not() }`** 或调用私有的
> `negate(Literal[])`。所以它是**死代码**。
> 它与 §2.8 的私有 `negate` 功能几乎相同，**只差参数类型**（`List<BoolVar>` vs `Literal[]`），
> 命名只差一个字母 `s` —— **读代码时极容易看混**。

### 2.4 `static LinearExpr sumPlus(List<BoolVar> vars, long extra)`

```java
LinearExprBuilder builder = LinearExpr.newBuilder();
if (!vars.isEmpty()) {
    builder.addSum(args(vars));
}
if (extra != 0) {
    builder.add(extra);
}
return builder.build();
```

构造 **$\sum_{v \in vars} v + extra$** 这个线性表达式。

**两个 `if` 的作用**：

| `if` | 不加会怎样 |
|---|---|
| `!vars.isEmpty()` | 会往表达式里塞一个**空 `addSum`**。语义上是 $+0$，但 proto 形状不同 → **指纹变**。当前调用点大多在 `if (!vars.isEmpty())` 之内（见 §3 的"为什么它外面还有一道判空"），所以这条更像是**防御** |
| `extra != 0` | 会往表达式里塞一个 **`+0` 常数项**。⚠ **这是本项目反复强调的坑**：`sum(vars) + 0` 与 `sum(vars)` 在数学上相同，**但 `CpModelProto` 不同，指纹就不同**（第 2 册 §6.1 也记了这条） |

⚠ **`extra` 必须是 `long` 且允许为负**：`SubjectMaxPerDayHandler` 软分支用
`LinearExpr.newBuilder().addSum(...).add(fixed - max)`，而 `fixed - max` 在
"固定课少于上限"时是**负数**（`fixed` 这里是"该班该天该科目的固定课节数"，
在软分支里**不保证** `fixed ≤ max` —— 见 §5 的数学推导）。
`LinearExprBuilder.add(long)` 确实接受负常数（`javap` 确认签名是 `add(long)`）。

⚠ **本方法在本册里的 3 个调用点**（都在 `addLessOrEqual` 的左边）：
`TeacherNoConflictHandler:68`、`TeacherWeeklyLimitHandler:60`、`SubjectMaxPerDayHandler:87`。
§4 的 `SubjectWeeklyHoursHandler` **故意不用它**（等式右边是常数，直接走 `addEquality`），
理由见 §4 的最后一条。

### 2.5 `static int maxPeriodsOnDay(RuleContext ctx, int day)`

```java
return ctx.maxPeriodsOn(day);
```

**纯转发，一行**。数学上它是 $\max_{c \in \text{classes}} \text{periods}_c(day)$
（跨班最大节数，见 `RuleContext:276`）。

**为什么存在**：不是功能需要，是**可读性** —— handler 里写
`HandlerSupport.maxPeriodsOnDay(ctx, day)` 比 `ctx.maxPeriodsOn(day)` 长，
但它把"**这里是在跨班遍历，所以只能用最大上界**"这个意图摆在明面上。
⚠ 换一种写法（直接用 `ctx.maxPeriodsOn(day)`）**完全等价**，不改变模型。

### 2.6 `static BoolVar boolConst(CpModel model, String name, boolean value)`

```java
BoolVar v = model.newBoolVar(name);
model.addEquality(v, value ? 1 : 0);
return v;
```

造一个**被钉死的布尔常量**：$v = 1$（或 $0$），是**变量**而不是 Java 常量。

**为什么需要它**：让"边界情况"和"一般情况"**共用同一段编码**。
`TeacherMinimizeGapsHandler` 里的递推需要 `before[0] = 假`、`after[n-1] = 假`，
用它造出恒假布尔，就能让循环**不写边界特例**（第 2 册 §6.1 记了这一点）。

**数学上的代价（要说清楚）**：它**不是免费的**：
- 模型里多 **1 个布尔变量** + **1 条 `addEquality` 约束**；
- 求解器要花力气证明它恒定（presolve 一般会干掉，但**建模统计与指纹已经变了**）；
- ⚠ **`name` 会进指纹**，所以同一个 handler 用不同的 `name` 前缀，指纹就不同。

**行为细节**：`model.addEquality(v, 1)` 用的是 `addEquality(LinearArgument, long)` 重载
（§7 已用 `javap` 确认存在），而 `BoolVar` 就是 `IntVar`，所以这里**不需要任何转换**。

⚠ **它仍然返回 `BoolVar` 而不是 Java 的 `boolean`** —— 这正是目的：
调用点把它当普通变量用，**不必写两套代码**。

### 2.7 `encodeOr` —— 令 `target ⟺ OR(literals)`

```java
static void encodeOr(CpModel model, BoolVar target, Literal[] literals) {
    model.addBoolOr(literals).onlyEnforceIf(target);
    model.addBoolAnd(negate(literals)).onlyEnforceIf(target.not());
}
```

**逐句的数学含义**（`onlyEnforceIf(t)` 读作"$t$ 为真时这条约束才必须成立"）：

| 行 | 加进去的约束 | 逻辑式 |
|---|---|---|
| `addBoolOr(literals).onlyEnforceIf(target)` | $target \Rightarrow (l_1 \lor \dots \lor l_n)$ | 正向 |
| `addBoolAnd(negate).onlyEnforceIf(target.not())` | $\lnot target \Rightarrow (\lnot l_1 \land \dots \land \lnot l_n)$，即 $\lnot target \Rightarrow \lnot(l_1 \lor \dots \lor l_n)$ | 反向 |
| 两条合起来 | $target \Leftrightarrow (l_1 \lor \dots \lor l_n)$ | **双向等价** |

⚠ **为什么必须两条**（本项目最常犯的建模错误）：
**只写第一条会得到单向蕴含** —— $target$ 被强制为真时 OR 成立，
但 $target = 0$ 时**什么都不限制**，于是"OR 为真"的情况下
`target` 仍可以取 0，**惩罚项就白加了**（`target` 只出现在目标里时会被最小化到 0）。
`SlotVariableIndex.membership`（重化）用的是**完全相同的双向结构**，
这是本项目的统一约定。

⚠ **`target.not()` 返回的是 `Literal`**，`onlyEnforceIf(Literal)` 有对应重载（§7 已确认）。
⚠ **`literals` 为空数组时**：$OR() = \bot$（假），于是 `target` 被钉成假。
本册里没有这种调用点。

### 2.8 `encodeAnd` 与私有 `negate`

```java
static void encodeAnd(CpModel model, BoolVar target, Literal[] literals) {
    model.addBoolAnd(literals).onlyEnforceIf(target);
    model.addBoolOr(negate(literals)).onlyEnforceIf(target.not());
}

private static Literal[] negate(Literal[] literals) { /* 逐元素 .not() */ }
```

| 行 | 加进去的约束 | 逻辑式 |
|---|---|---|
| `addBoolAnd(literals).onlyEnforceIf(target)` | $target \Rightarrow (l_1 \land \dots \land l_n)$ | 正向 |
| `addBoolOr(negate).onlyEnforceIf(target.not())` | $\lnot target \Rightarrow (\lnot l_1 \lor \dots \lor \lnot l_n)$，即 $\lnot target \Rightarrow \lnot(l_1 \land \dots \land l_n)$ | 反向 |
| 合起来 | $target \Leftrightarrow (l_1 \land \dots \land l_n)$ | **双向等价** |

⚠ **`negate` 是 `private`，`negations`（§2.3）是包级**，两者命名只差一个 `s`、
功能重叠、参数类型不同。**读的时候务必看参数类型**：
`negate(Literal[])` 是内部用的，`negations(List<BoolVar>)` 是死代码。

⚠ **`encodeOr` / `encodeAnd` 在本册 6 个文件里都没有调用者**
（调用点在 `TeacherMinimizeGapsHandler`，属于 2d 册）。
本册的 `SubjectNoConsecutiveHandler` **手工写了 `encodeAnd` 的等价编码**
（§6.4），这是一处**已知的可以统一的地方**。

---

## 3. `TeacherNoConflictHandler` —— 不变量：同一教师同一时段最多一节课（74 行）

**一句话定位**：本项目**最短也最典型**的一条约束 —— "一个老师不能同时出现在两个班"。

**谁调它**：
- **上游**：`RuleCompiler` 第 1 步（`registry.mandatory()`），**每个模型无条件施加一次**，
  入参是 `RuleParams.empty()` 与 `hard = true`。
- **下游**：只读 `RuleContext`（`teachers()` / `freeTeacherSlots(...)` /
  `fixedCountForTeacher(...)`）与 `HandlerSupport`；写 `CpModel`。
  ⚠ **它不用 `PenaltyCollector`**，所以 `hard` 参数被完全忽略。

**类头部的三行元数据**（都是给"规则系统"用的，不影响数学）：

| 成员 | 值 | 含义 |
|---|---|---|
| `TYPE` | `"teacher_no_conflict"` | 写进 `constraint_rules.rule_type` 的稳定标识。⚠ **`RuleCompiler` 用它查 `RuleRegistry`，不是类名** |
| `displayName()` | `"教师时段不冲突"` | 面向用户的中文名 |
| `params()` | `List.of()` | **空 = 不接受任何参数**。⚠ 与 `mandatory()` 是**两个正交的声明**：一个说"不可配置"，一个说"没有参数"。前者是语义（不许关），后者是结构（没得配）。**两者都写才完整** |
| `mandatory()` | `true` | 见 §0.2 |
| `describe(...)` | `"同一教师同一时段最多一节课（系统不变量，始终生效）"` | ⚠ **它忽略入参 `params`**（因为本来就没有参数） |

### 3.1 `apply` 逐行 + 数学含义

```java
CpModel model = ctx.model();
for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {          // ① 每位教师
    long teacherId = teacher.id();
    for (int day = 0; day < ctx.days(); day++) {                    // ② 每天（常量 5）
        int maxPeriods = HandlerSupport.maxPeriodsOnDay(ctx, day);  // ③ 跨班最大节数
        for (int period = 0; period < maxPeriods; period++) {       // ④ 每节
            List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period);
            int fixed = ctx.fixedCountForTeacher(teacherId, day, period);
            if (fixed > 1) {
                throw new ConstructionInfeasible(...);
            }
            if (!vars.isEmpty()) {
                model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), 1);
            }
        }
    }
}
```

**① `ctx.teachers()` 是"输入里出现的所有教师"**（`SlotVariableIndex` 里由
`in.teachers()` 建立的 `LinkedHashMap` 的 `values()`），**保序**。
⚠ 它**不是**"这个学校的所有教师"，也不是"有课的教师" —— 一个没任教关系的教师也会被遍历到，
只是循环体里 `vars` 与 `fixed` 都是 0（见下面 ④ 的说明）。

**② 每天：`day = 0 .. ctx.days() - 1`**，而 `RuleContext.DAYS` 是**硬编码常量 5**
（`public static final int DAYS = 5;`）。⚠ **这个 5 不在本 handler 里**，
`ScheduleInput` 的 `GradeInfo.periodsPerDay()` 用的 key 是 `"Mon".."Fri"`
（`SlotVariableIndex.DAY_KEYS`）。**要改成 6 天工作制，必须三处一起改**
（`RuleContext.DAYS`、`SlotVariableIndex.DAYS`、`DAY_KEYS`），本 handler 不用改。

**③ `maxPeriodsOnDay(ctx, day)` = 当天**所有班里最大的节数**。
⚠ 这是本 handler 的**关键上界选择**：因为 `vars` 要跨"该教师教的所有班"收集，
而**各班节数可以不同**（`ClassData.periods()` 是 `int[5]`，按班按天）。
- 用**最大**节数当上界 → 保证不漏掉任何节；
- 用**某个班**的节数（例如 `classes().get(0)}`）→ **节数更多的班会被整段漏掉**，
  那门课就**再也受不到这条约束**（静默错解，不是崩溃）；
- 上界"多出来"的那几节不会有副作用：`freeTeacherSlots(t, d, p)` 内部有
  `if (period >= periodsOn(classId, day)) continue;`，**节数不够的班会被挡掉**。

**④ `vars = ctx.freeTeacherSlots(teacherId, day, period)`** —— **本条的语义核心**：

> $\text{vars} = \{\, v_{t,c,d,p} \mid c \in \text{该教师教的班},\ \text{时段 }(c,d,p)\text{ 不是固定课}\,\}$
>
> 其中 $v_{t,c,d,p} = 1 \iff$ **"教师在班 $c$ 的第 $d$ 天第 $p$ 节上课"**（"教师成员布尔"）。

三个必须记住的细节：

1. **"free" 指的是"非固定课"，不是"该教师有空"**。
   固定课**没有决策变量**（`slotVar` 对固定课返回 `null`），所以它不出现在 `vars` 里。
2. **它只包含"任教关系"上的可能**：`freeTeacherSlots` 先过滤
   `teacherTeachesClass(teacherId, classId)`，再对每个班取 `teacherAt(...)`，
   `teacherAt` 内部还会检查"该班里有没有这位教师的任教记录"（`match.isEmpty()` → 返回 `null`）。
   所以 `vars` 里的每个变量都对应**一条真实存在的任教关系**。
3. **本方法的调用同时"创建"了这些布尔变量**（懒创建 + 缓存）。
   因为是**第一条 mandatory**，它决定了整个模型里
   **教师成员布尔的创建顺序**（§0.2）。

**⑤ `fixed = ctx.fixedCountForTeacher(teacherId, day, period)`**：
"该教师在该时段被**固定课**占了**几次**"。

⚠ **`fixed` 的取值有 4 种可能，而它们对应完全不同的语义**：

| `fixed` | 含义 | 说明 |
|---|---|---|
| `0` | 该时段该教师没被固定课占 | 正常情况 |
| `1` | 该教师在该时段有一个班的固定课 | **正常**（这位老师这节就是上那个班），此时 $\sum vars$ 必须为 0，即**自由时段不许再给他排课** |
| $\ge 2$ | **该教师在同一时段被 $\ge 2$ 个班的固定课占了** | **固定课之间就撞车了**，与决策无关 → 抛异常（下面 ⑥） |
| `0` 且 `vars` 也空 | 该教师在该时段没有任何可能 | 见 ⑧ |

⚠ `fixedCountForTeacher` **不是**"随便数数"：它遍历**所有班 × 该天 × 该班这天的节数**，
用 `fixedTeacherAt(...) == teacherId` 判定（`RuleContext:158`）。
它内部同样有 `teacherTeachesClass` 与 `period >= periodsOn(classId, day)` 两道过滤，
**所以它的边界口径与 `freeTeacherSlots` 是一致的** —— 这一点很重要，
否则 `fixed` 与 `vars` 会各算各的、出现"同一时段既算固定又被当成自由"的错。

**⑥ `if (fixed > 1) throw new ConstructionInfeasible(...)`** —— **本 handler 最值钱的一段**。

数学上：这条约束要加的是
$$fixed + \sum_{v \in vars} v \le 1$$
当 $fixed = 2$ 时，右边是 $\sum v \le -1$。而 $v \ge 0$（都是布尔），
**这个约束不可满足** ⇒ 整个模型 `INFEASIBLE`。

于是有两条路：

| 做法 | 后果 |
|---|---|
| **让约束加进去**（原来的写法） | 求解器最后报 `INFEASIBLE`，上层只能给出笼统的"排课无解，请检查任教关系、周课时、固定时段和教师课时上限"（Python 原话）。**用户根本不知道是固定课自己撞了** |
| **建模期就抛 `ConstructionInfeasible`**（现在的写法） | 异常消息**直接点名**：`教师 12（张老师）在第 3 天第 2 节被固定课占了 2 次，固定课之间就冲突`。调用方可以把它与"真无解/超时"区分开，报出**具体原因** |

这正是 `ConstructionInfeasible` 这个类存在的理由（它的 javadoc 第一句就是
"建模阶段发现「无论怎么排都不可能」时抛出 —— 用于把配置错误与求解无解区分开"）。

⚠ **文案里的 `(day + 1)` / `(period + 1)` 是刻意的**：循环变量是 0-based，
但**消息是给人看的**，所以 +1 变成 1-based。**改这一段时别把 +1 顺手删了**。

⚠ **这个场景不是假想**：`Java建模等价性验证报告.md` 记录过 —— 夹具里
"如果固定课覆盖的班级共享同一位教师，会导致该教师在同一时段被固定多次"，
**Python 侧只表现为"排课无解"**，Java 侧因此多暴露了一条诊断信息。

**⑦ `if (!vars.isEmpty()) { model.addLessOrEqual(sumPlus(vars, fixed), 1); }`**

**数学含义**：

$$fixed + \sum_{c \in \text{该教师教的班}} v_{t,c,d,p} \;\le\; 1$$

即"**该教师在 $(d,p)$ 这个时段，跨所有班加起来最多上一次课**"。

用 CP-SAT 的预定义 API 说，这就是 `addAtMostOne`（**但项目里没有调用它**，§0 的反直觉结论）。
⚠ **注意 `fixed` 是常数**（建模期算出来的 Java `int`），不是变量：
固定课没有决策变量，所以"固定课占了几次"只能以常数形式进入不等式。

**⑧ 为什么判空是必须的 —— 这一段有两个不同的问题，别混在一起**：

- **数学上的必要性**：`vars` 为空时表达式是 `fixed ≤ 1`。
  由于 `⑥` 已经保证了 `fixed ≤ 1`，这条**恒真**。
  所以判空**不改变可行解集合** —— 它**不是**为了正确性。
- **模型干净度上的必要性**：加进去会**多一条恒真约束** ∈ `CpModelProto.constraints`
  ⇒ 约束计数变、**指纹变**、`modelStats()` 变。
  `GoldenModelTest` 断言的 `#kLinear1: 8'201` 这类计数就是被这种"恒真约束"影响的。
- ⚠ **`fixed == 0 且 vars 为空` 时**，不等式是 `0 ≤ 1`，**最典型的冗余**；
  **`fixed == 1 且 vars 为空`** 时是 `1 ≤ 1`，同样恒真。
  两种都跳过 —— **结果一致**，不会漏掉任何真实约束。

> ⚠ **一个容易忽略的后果**：`if (!vars.isEmpty())` 让**同一个数学约束在不同数据下**
> 会不会被加进去变得**依赖数据**。所以"变量数/约束数"是数据的函数，
> 换夹具（`fixtures/school-8.json`）数字就变 —— 这是正常的，不是漂移。

### 3.2 为什么这条是"硬"的、且没有软版本

- `apply` **完全忽略 `hard`**，永远只走 `addLessOrEqual`。这没问题，因为
  `RuleCompiler` 只会在 `mandatory()` 那一档调它（`hard = true` 写死），
  而第 2 步的 `if (handler.mandatory()) continue;` 保证规则表**不可能**把它配成软的。
- **语义上它也不该有软版本**：`PenaltyCollector` 的机制是"每次违规罚权重分"
  （§7.3），而"一个老师同时上两个班"不是"尽量少发生"，是**根本不允许发生**。
  能加罚分的约束都有"违反程度"这个量，这条没有。

### 3.3 这条约束在"真实教务"里对应什么

| 教务说法 | 模型里的对应 |
|---|---|
| "同一个老师同一时间只能在一个班上课" | $fixed + \sum v \le 1$ |
| "老师的课表上不能有两节课时间重叠" | 同上（时间 = $(day, period)$ 这个二元组） |
| "老师被安排到了两个班" | 求解器永远不会给出这种解 |

> ⚠ **一个刻意的模型简化**：**没有"连堂"概念**。两个相邻的 `period` 是**两个独立的时段**，
> 同一个老师在第 1、2 节连上两个班是**允许的**（那正是"连堂"）。
> 这条约束只管"**同一时刻不能分身**"。要禁止连堂是**另一条规则**的事。

---

## 4. 两条"数据驱动"的 mandatory（`SubjectWeeklyHoursHandler` + `TeacherWeeklyLimitHandler`）

这两条与 §3 并列在 `BuiltinRules` 里，**都是 `mandatory()`**，
但要理解它们**为什么也进这一档**：它们的参数**不来自规则表**，
而来自**教务数据**（`grade_subjects.weekly_hours` / `teachers.max_weekly_hours`）。
所以"用户配置"这个维度对它们不存在 —— **它们不是"用户加的一条规则"，是"数据自然带来的约束"**。
`RuleHandler` 的 javadoc 把这两者都归入 `mandatory()`：

> 数据驱动的约束（周课时数、教师周上限）也走这一档，参数直接从快照读。

### 4.1 `SubjectWeeklyHoursHandler` —— 每科周课时必须排满（73 行）

**一句话定位**：把"年级课时设置"（例如语文 5 节/周）**变成等式约束**。

**谁调它**：`RuleCompiler` 第 1 步（它是 `BuiltinRules` 里的**第 2 条**），
入参 `RuleParams.empty()` + `hard = true`。**它也不用 `PenaltyCollector`**。

**元数据**：`TYPE = "subject_weekly_hours"`、`params() = List.of()`、
`mandatory() = true`、`describe = "每个班每科的周课时数必须排满（来自年级课时设置）"`。

#### `apply` 逐行 + 数学含义

```java
for (ClassData cd : ctx.classes()) {                                 // ① 每个班
    long classId = cd.classId();
    for (Map.Entry<Long, Integer> entry : ctx.weeklyHoursOf(classId).entrySet()) {  // ② 该班 科目→周课时
        long subjectId = entry.getKey();
        int needed = entry.getValue();
        int fixed = ctx.fixedCountForSubject(classId, subjectId);     // ③ 整周固定课数
        int remaining = needed - fixed;                               // ④ 还差几节
        if (remaining <= 0) {
            continue;   // 固定课已经满足甚至超过要求
        }
        List<BoolVar> vars = ctx.freeSubjectSlots(classId, subjectId); // ⑤ 整周非固定时段
        if (!vars.isEmpty()) {
            model.addEquality(LinearExpr.sum(HandlerSupport.args(vars)), remaining);  // ⑥
        }
    }
}
```

**① `ctx.classes()`** 是 `SlotVariableIndex.classDataInOrder()`，即 `LinkedHashMap` 的 `values()`
—— **保序**，且**只包含"年级存在"的班**（`prepare()` 里 `gradeById.get(c.gradeId()) == null`
的班会被 `continue` 掉，**连 `ClassData` 都不建**）。⚠ **这是"输入数据决定遍历集合"的第一处**。

**② `ctx.weeklyHoursOf(classId)`** 是 $班 \to (科目 \to 周课时)$，**保序的 `LinkedHashMap`**
（`SlotVariableIndex.prepare()` 里按 `in.gradeSubjects()` 的顺序 `put`）。

⚠ **它的内容完全由"输入数据"决定**：
- 它由 `grade_subjects` 里**该班所属年级**的记录生成（`c.gradeId() == gs.gradeId()`）；
- **该班没有的科目也可能出现在这里**（只要年级课时表里有）。

⚠ **而这正是下面 ⑤ 的 `vars` 可能为空的原因之一** —— 见 ⑥ 的第三个坑。

⚠ **内层顺序影响变量创建顺序**（⇒ 指纹）。`LinkedHashMap` 保序是**刻意的**
（`SlotVariableIndex` 的 javadoc：为保持模型可复现，所有遍历容器都不用 `HashMap`/`HashSet`）。

**③ `fixed = ctx.fixedCountForSubject(classId, subjectId)`**：
该班该科目**整周**（5 天 × 该班每天节数）被固定课占掉的节数。

**④ `remaining = needed - fixed`**：**"自由时段里还需要排几节"**。

⚠ **这就是"固定课统一记账"的关键一步**：Python 原实现里这套"数固定课、算差值、手工加进
线性表达式"的记账**散落在 39 处**，每一处都可能漏；现在**只在这一行**。

**⑤ `vars = ctx.freeSubjectSlots(classId, subjectId)`**：该班该科目**整周所有非固定时段**
的"科目成员布尔" $s_{c,\text{subj},d,p}$，含义是 **"第 $d$ 天第 $p$ 节这个班上的是这门课"**。

**⑥ `model.addEquality(LinearExpr.sum(args(vars)), remaining)`**

**数学含义**：

$$\sum_{d,p} s_{c,\text{subj},d,p} \;=\; needed - fixed
\quad\Longleftrightarrow\quad
fixed + \sum_{d,p} s_{c,\text{subj},d,p} \;=\; needed$$

即"**固定课 + 自由时段里排的该科目 = 年级要求的周课时**"。

⚠ **它不是 `addExactlyOne`**：`addExactlyOne(vars)` 是 $\sum v = 1$，
这里是 $= remaining$，而 `remaining` 可以是 1..5 甚至更大。
用预定义 API 说，它更接近"**恰好 $k$ 个为真**"，而**项目里同样没有调用 `addExactlyOne`**（§0）。

#### ⚠ ⑤⑥ 相关的三个坑（**都是真实的行为，不是猜测**）

**坑 1：`remaining == 0` 时"什么都不做"**。
`remaining == 0` 表示"固定课恰好把周课时排满了"。
按数学，自由时段应当**一节都不能排**，但**本 handler 在这个分支里直接 `continue`，没有加任何约束**。
⇒ **模型层面允许该班该科目在自由时段被多排**。
- 为什么不能加 `addEquality(sum(vars), 0)` 而在同一个分支里合并 —— 因为 `remaining < 0` 时
  那个等式**不可满足**（右边是负数），所以负数必须跳过；
  而 `remaining == 0` 本可以加，代码**选择了跳过**。
- **这是真实的宽松点**（第 2 册 §6.3 也记录了这一点）。
  实际数据里"固定课覆盖整个周课时"极少见，而且
  `PositiveFeasibilityTest` 用独立校验器核对节数，真出现会被测出来。
- **本册如实记录：代码行为就是"跳过不加约束"。**

**坑 2：`remaining < 0`（固定课已经超过周课时要求）时静默跳过**。
这属于**配置矛盾**（固定课 6 节，年级只要求 5 节）。按 §5/§3 的风格本该抛
`ConstructionInfeasible`，**但这里没有** —— 它与 `SubjectMaxPerDayHandler`（§5，同类型矛盾会抛）
**处理不一致**。**代码行为就是静默继续。**（本条也不在第 2 册的原文里，是本册核对代码后的结论。）

**坑 3：`vars` 为空且 `remaining > 0` ⇒ 模型 `INFEASIBLE`，且没有具体原因**。
`vars.isEmpty()` 有两种成因：
- **该班该科目一个非固定时段都没有**（整周都被固定课占了）；
- **该班根本没有这门课的任教关系** —— 但 `weeklyHoursOf` 是**按年级**生成的，
  所以"年级课时表里有、这个班却没有任教关系"会走到这里。

此时 `remaining > 0` 意味着"**要排 $k$ 节却没有位置可排**"，而代码**跳过了约束**
⇒ 模型里**没有这条要求** ⇒ **不会 INFEASIBLE**，而是**静默地少排课**。
⚠ **谁在兜这个底**：外部静态诊断（`MISSING_ASSIGNMENT` / `CLASS_CAPACITY_SHORTFALL`，
见 `架构说明.md` §5.7）就是抓这类情况的。**但这说明"模型本身并不保证排满"**。

**⚠ 为什么 ⑥ 用 `LinearExpr.sum(...)` 而不是 `HandlerSupport.sumPlus(vars, -remaining)`**：
两者数学等价（$\sum v + (-remaining) = 0$），但 `addEquality(expr, long)` 的重载
**直接接受常数右边**（§7 确认），所以**没有必要把常数并进表达式**。
**写成 `sumPlus` 版本语义相同、proto 不同 ⇒ 指纹会变。**

### 4.2 `TeacherWeeklyLimitHandler` —— 教师周课时不超过上限（65 行）

**一句话定位**：把"教师档案里的 `max_weekly_hours`"变成**整周**的上限不等式。

**谁调它**：`RuleCompiler` 第 1 步（`BuiltinRules` 里的**第 3 条**），`hard = true`，不用 `PenaltyCollector`。

**元数据**：⚠ **`TYPE = "teacher_max_weekly_hours"`，与类名 `TeacherWeeklyLimitHandler` 不同**
（类名说 Limit，type 说 max_weekly_hours）。这是**刻意的稳定标识**，
`RuleRegistry` / 数据库 `rule_type` / 前端都认这个字符串。

#### `apply` 逐行 + 数学含义

```java
CpModel model = ctx.model();
for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {        // ① 每位教师
    long teacherId = teacher.id();
    List<BoolVar> vars = ctx.freeTeacherSlots(teacherId);         // ② 整周、跨所有班
    int fixed = ctx.fixedCountForTeacher(teacherId);              // ③ 整周固定课数
    if (!vars.isEmpty()) {
        model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed),
                teacher.maxWeeklyHours());                        // ④ 上限来自档案
    }
}
```

**① 遍历顺序与 §3 完全一致**（同一个 `ctx.teachers()`），**这是 28% 去重能成立的前提**：
两条规则用**同样的顺序**请求**同一批**变量（见 §7.1）。

**② `ctx.freeTeacherSlots(teacherId)`（一参版）** = 该教师**整周 × 跨他教的所有班**
的"教师成员布尔"的**扁平列表**（`RuleContext:230`）。
⚠ 与 §3 用的**三参版**（`freeTeacherSlots(teacherId, day, period)`）**返回的是同一批
缓存的 `BoolVar` 对象**（同一个 `teacherAt` 缓存）—— 这就是去重的实现层面原因。

**③ `fixed = ctx.fixedCountForTeacher(teacherId)`**（一参版）= 该教师**整周**被固定课占掉的节数。

**④ `addLessOrEqual(sumPlus(vars, fixed), teacher.maxWeeklyHours())`**

**数学含义**：

$$fixed + \sum_{c,d,p} v_{t,c,d,p} \;\le\; \texttt{teacher.maxWeeklyHours()}$$

即"**这位老师这一周（含固定课）最多上这么多节**"。

> **真实教务含义**：教师是**有编制工时**的。`max_weekly_hours` 就是"这位老师一周最多几节"
> （兼职/行政岗的老师会显著低于满课时）。**超了就是超工作量**，所以它是**硬约束**。

#### ⚠ 本条的两个坑

**坑 1：上限是直接从 record 读的，不走 `RuleContext`**。
代码写的是 `teacher.maxWeeklyHours()`，而**不是** `ctx.teacherWeeklyLimit(teacherId)`。
两者等价（`RuleContext.teacherWeeklyLimit` 就是
`index.teacher(teacherId).maxWeeklyHours()`，`RuleContext:88`），
但 ⇒ **`RuleContext.teacherWeeklyLimit` 在本模块内没有调用者**。
本册如实记录：**它是为规则作者准备的便利方法，目前闲置。**

**坑 2：`fixed > maxWeeklyHours` 时不会抛 `ConstructionInfeasible`**。
"固定课本身已经超上限"是**配置矛盾**。此时不等式变成
$\sum v \le max - fixed < 0$ ⇒ **不可满足** ⇒ 模型 `INFEASIBLE`，
**上层只能给笼统的"排课无解"**。
⚠ 对比 `SubjectMaxPerDayHandler`（§5）**同类型矛盾会抛异常并点名** ——
**这两条的处理不一致**。本册如实记录：**这里选择"交给求解器"**。

⚠ **还有一处不对称**：`if (!vars.isEmpty())` 的判空在这里**可能掩盖**坑 2 的一部分 ——
如果某教师**一个自由时段都没有**（全是固定课）而 `fixed > max`，
判空会让这条约束**完全不被加进去** ⇒ 这个超限**根本不会被发现**。
（判空本身是对的：`vars` 空时表达式是 `fixed ≤ max`，加不加都不影响别的变量；
但如果它恒假，加进去才会让模型 INFEASIBLE。）**本册如实描述这个行为差异。**

### 4.3 这三条为什么是"一组"

| handler | 遍历维度 | 约束形状 | 数学 |
|---|---|---|---|
| `TeacherNoConflict` | 教师 × **时段** | $\le 1$ | 同一时刻不能分身 |
| `SubjectWeeklyHours` | 班 × **科目** | $= needed - fixed$ | 课时必须排满 |
| `TeacherWeeklyLimit` | 教师 × **整周** | $\le max$ | 不能超工作量 |

三者合起来正是 Python `engine.py` 的**硬 1 / 硬 2 / 硬 3**
（⚠ **编号在源码 javadoc 与 `架构说明.md` 的表之间不一致**，
以 `BuiltinRules` 的注册顺序与 `TYPE` 常量为准，见 §0.2）。
`RuleSets.legacyEquivalent()` 里**只列了软 1/软 2，没有列这三条** ——
因为它们是 `mandatory`，**由 `RuleCompiler` 按 `mandatory()` 自动施加**，
写进规则集反而会被 `if (handler.mandatory()) continue;` 静默忽略。

---

## 5. `SubjectMaxPerDayHandler` —— 可配置、硬软两用：单日单科节数上限（106 行）

**一句话定位**：限制"**某个班、某一门课、在某一天**最多上几节"，是本册里**唯一带线性化技巧**
的一条。对应 Python 的**软 1**（`max = 1` 时）。

**谁调它**：
- **上游**：`RuleCompiler` 第 2 步 —— **只有当规则集显式引用 `"subject_max_per_day"` 时才生效**
  （§0.3）。`RuleSets.legacyEquivalent()` 里那行
  `ConstraintRule.soft(SubjectMaxPerDayHandler.TYPE, RuleParams.of(Map.of("max", 1)), 1, ...)`
  就是它唯一的**内置**来源。
- **`hard` 参数来自 `!rule.isSoft()`**：软规则传 `false`，硬规则传 `true`。
- **下游**：`PenaltyCollector`（软分支）、`CpModel`、以及 `ConstructionInfeasible`（硬分支）。
- ⚠ **它是本册唯一两条路径都完整实现、且两路径后果不同的 handler**。

### 5.1 `params()` 与 `describe()`

```java
public static final String TYPE = "subject_max_per_day";
public static final String P_SUBJECT = "subject_id";
public static final String P_MAX = "max";

@Override
public List<RuleParamSpec> params() {
    return List.of(
            RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）", 1, Integer.MAX_VALUE),
            RuleParamSpec.intParam(P_MAX, "一天最多几节", 1, 20));
}
```

| 参数 | 声明 | 必填 | 范围 | 数学含义 |
|---|---|---|---|---|
| `subject_id` | `optionalInt` | **否** | $[1, 2^{31}-1]$ | 省略 = **该班的每一门科目都适用** |
| `max` | `intParam` | **是** | $[1, 20]$ | 上限 $M$ |

⚠ **"可省略"是这条规则能表达两种教务说法的关键**（javadoc 第 21–22 行）：

| 教务说法 | 参数 |
|---|---|
| "语文一天最多两节" | `{"subject_id": 3, "max": 2}` |
| "任何科目一天都别超过一节" | `{"max": 1}`（**不写 `subject_id`**） |

⚠ **`max` 的上界 20 是硬编码的**。目前最大班级节数在 8 附近，20 是余量。
`RuleParamsValidationTest` 断言了 `max = 21` 会被拒。
**换成"每天 12 节"的大学场景时会撞到这个上界** —— 那是改
`RuleParamSpec` 的地方，不是改 handler。

**参数校验发生在哪**（§0.3）：`RuleCompiler` 里的
`rule.params().validate(handler.params())`。
⚠ **handler 自己不做范围校验** —— `apply` 里的 `params.requireInt(P_MAX)` 只保证
"存在且是整数"，**不保证 $\le 20$**（那是 `validate` 的活）。见 §5.5 的坑 3。

`describe(...)` 只用于**显示**：

```java
String scope = params.has(P_SUBJECT) ? "科目 " + params.longOr(P_SUBJECT, -1) : "所有科目";
return scope + " 一天最多 " + params.intOr(P_MAX, 1) + " 节";
```

- `params.has(P_SUBJECT)` 的语义是 **"`values.get(name) != null`"**（`RuleParams:30`），
  所以 `{"subject_id": null}` 与"不写 `subject_id`"**等价**。
- `longOr(P_SUBJECT, -1)` 里的 `-1` **只是"取不到时的显示兜底"**，
  ⚠ **不会出现在校验路径上**（因为已经 `has` 过了）。`intOr(P_MAX, 1)` 同理。
- ⚠ **`describe` 不做校验、也不抛异常** —— 即使 `max` 非法，它也会照样拼出一句话。

### 5.2 `apply` 的参数读取与循环骨架

```java
CpModel model = ctx.model();
Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;   // ①
int max = params.requireInt(P_MAX);                                        // ②

for (ClassData cd : ctx.classes()) {                                       // ③
    long classId = cd.classId();
    Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);  // ④
    for (long subjectId : subjects) {                                      // ⑤
        if (!ctx.subjectsOf(classId).contains(subjectId)) {                // ⑥
            continue;
        }
        for (int day = 0; day < ctx.days(); day++) {                       // ⑦
            int fixed = ctx.fixedCountForSubjectOnDay(classId, subjectId, day);     // ⑧
            List<BoolVar> vars = ctx.freeSubjectSlotsOnDay(classId, subjectId, day); // ⑨
            ... 硬分支 / 软分支 ...
        }
    }
}
```

**① `Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null`**
—— 用**包装类型 `Long` 的 `null`** 表达"不指定"，而不是哨兵值（`-1` / `0`）。
⚠ `requireLong` 在参数缺失时抛 `IllegalArgumentException`（`RuleParams:37`），
但这里已经 `has` 过，所以不会走到。

**② `int max = params.requireInt(P_MAX)`** —— `requireInt` 是
`Math.toIntExact(requireLong(name))`。⚠ **`Math.toIntExact` 在超出 int 范围时会抛
`ArithmeticException`**（不是 `IllegalArgumentException`）。因为 `validate` 卡了上界 20，
正常路径走不到；**但直接调 `apply` 的测试可以绕过 `validate`**。

**④ `Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId)`**
—— **把"指定了科目"和"没指定"统一成一个集合**，于是后面的循环**只有一份**，
不用写 `if/else` 两套。⚠ **`List.of(only)` 是 `Collection<Long>` 的合法实现**，
所以三元的两个分支类型相容。

⚠ **`List.of(only)` 里 `only` 是 `Long` 对象**，`for (long subjectId : subjects)` 会自动拆箱。

**⑥ `if (!ctx.subjectsOf(classId).contains(subjectId)) continue;`**
—— ⚠ **这个判断只在"指定了科目"时有实际作用**（那时 `subjects` 是单元素 `List.of(only)`）；
不指定的分支里 `subjects` 本来就是 `ctx.subjectsOf(classId)`，判断恒真。

它挡的是：**参数指定了一门这个班根本没有的科目**。
不跳过会怎样：`ctx.freeSubjectSlotsOnDay(...)` 返回**空列表**（因为 `subjectAt` 对
"该班无此科目"返回 `null`），于是硬分支里 $\sum \emptyset + 0 \le M$ 变成 `0 ≤ M` —— **恒真冗余约束**。
⇒ **这个 `continue` 是为"模型干净 / 指纹稳定"存在的**，不是为正确性。

**⑦ 内层天数**：`day = 0 .. ctx.days()-1`（常量 5）。

**⑧ `fixed = ctx.fixedCountForSubjectOnDay(classId, subjectId, day)`**
= 该班该科目**这一天**被固定课占掉的节数（`RuleContext:129`，逐节比 `fixedSubjectAt == subjectId`）。

**⑨ `vars = ctx.freeSubjectSlotsOnDay(classId, subjectId, day)`**
= 该班该科目**这一天**所有非固定时段的科目成员布尔。

⚠ **`fixed` 与 `vars` 的"天"口径必须一致**：两者都按 `periodsOn(classId, day)`
（该班**当天**的节数）截断 —— 各班每天节数可以不同，所以**不能**用跨班的 `maxPeriodsOn`。

### 5.3 硬分支 —— 数学含义

```java
if (hard) {
    if (fixed > max) {
        throw new ConstructionInfeasible(
                "班级 " + classId + " 在第 " + (day + 1) + " 天，科目 "
                        + subjectId + " 的固定课已有 " + fixed + " 节，超过上限 " + max);
    }
    if (!vars.isEmpty()) {
        model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), max);
    }
}
```

**约束**：

$$fixed + \sum_{p} s_{c,\text{subj},d,p} \;\le\; M$$

**真实教务含义**："这个班上这门课，一天最多 $M$ 节"。
$M = 1$ 时就是"**同一门课一天不许排两节**"（这是 Python 软 1 的硬版本）。

**两个细节**：
- `(day + 1)` —— ⚠ **异常文案用 1-based**（与 §3 一致）。但变量名 `day_exc_...` 用 0-based
  （见 5.4 的 `String.format`）。**两处不一致是刻意的：一个是给人看，一个是给指纹看。**
- `if (!vars.isEmpty())` —— 同 §3：`vars` 空时是恒真约束，跳过。
  ⚠ 但这里**在抛异常之后**才判空，所以"$fixed > M$"**一定会被抛出来**，
  不会像 §4.2 坑 2 那样被判空掩盖。**这两条 handler 在这一点上是不一致的。**

### 5.4 软分支 —— **本册唯一的线性化技巧**（逐句讲）

```java
} else {
    int totalPossible = fixed + vars.size();          // ① 理论上最多能上几节
    if (totalPossible <= max) {
        continue;   // 不可能超，无需惩罚
    }
    IntVar excess = model.newIntVar(0, totalPossible,   // ② 造"超出量"变量
            String.format("day_exc_%d_%d_%d", classId, subjectId, day));
    model.addGreaterOrEqual(excess, LinearExpr.newBuilder()   // ③ 单向下界
            .addSum(HandlerSupport.args(vars))
            .add(fixed - max)
            .build());
    penalties.add(excess);                            // ④ 进目标
}
```

**目标语义**：惩罚 = **$\max(0,\ \text{实际节数} - M)$**，即"超出多少就罚多少"。

**① `totalPossible = fixed + vars.size()`**：该科目这一天**在物理上最多能排几节**
（固定课 + 所有可选自由时段）。

**`if (totalPossible <= M) continue;`** —— ⚠ **这是性能优化，而且是必要的**：
不短路的话，每个（班 × 科 × 天）都会造一个 `excess` 变量**即使它恒为 0**（见下面 ③ 的推导），
⇒ 变量数暴涨、`GoldenModelTest` 的 `#ints` 变、求解变慢。
**短路之后 `excess` 只在真的可能超课时才存在。**

**② `IntVar excess = model.newIntVar(0, totalPossible, "day_exc_{classId}_{subjectId}_{day}")`**

- **域是 $[0, totalPossible]$**（`newIntVar(下界, 上界, 名字)`，两端**闭区间**）。
- ⚠ **上界取 `totalPossible` 而不是 `max`**：实际超出量的最大可能值是
  "$fixed + 全部自由时段都排该科目 - M$" $= totalPossible - M \le totalPossible$，
  所以 `totalPossible` 是**安全（偏松）的上界**。
  取更紧的上界（例如 `totalPossible - max`）语义也够，但**当前代码没有这么做**。
- ⚠ **变量名进指纹**（`String.format` 的结果就是 `CpModelProto` 里变量的 `name`）。
  注意这里 `day` **是 0-based**（与 ③ 异常文案的 `day + 1` **相反**）。

**③ `model.addGreaterOrEqual(excess, sum(vars) + (fixed - max))`** —— **这就是"单向下界线性化"**：

$$excess \;\ge\; \sum_p s_{c,\text{subj},d,p} + fixed - M$$

**为什么只写"$\ge$"就够了**（三件事合起来推出等式）：

| 情形 | 不等式右边 | `excess` 会取什么 |
|---|---|---|
| 实际节数 $\le M$（没超） | $\le 0$ | 由 ② 的域 $excess \ge 0$ ⇒ $excess$ 只能 $\ge 0$；而**目标在最小化它** ⇒ **恰好取 0** ⇒ 不罚 |
| 实际节数 $> M$（超了 $k$ 节） | $= k > 0$ | 不等式强制 $excess \ge k$；最小化 ⇒ **恰好取 $k$** ⇒ 罚 $k \times$ 权重 |

⇒ 目标里 $excess$ 的有效值就是 $\max(0,\ \sum v + fixed - M)$。

**为什么不能写等式**：$\max(0,\cdot)$ 是**非线性的**，写成等式需要额外引入
"是否超出"的指示布尔并做 big-M 编码。**"$\ge$ + 定义域非负 + 目标最小化"三件事
正好免费地得到 $\max(0,\cdot)$** —— 这就是 javadoc 说的"单向下界线性化"。

> ⚠ **这个技巧有一个必须记住的依赖**：
> **它依赖 `excess` 出现在目标里**（`penalties.add(excess)`）。
> 如果忘了那一行，`excess` 就是一个**几乎完全自由**的变量
> （只有那条下界约束），约束形同虚设、**且不报错、不影响可行性**。
> 更糟的是：漏掉 `penalties.add` 时，如果 `PenaltyCollector` 里**还有别的惩罚项**，
> 模型照样有目标、照样求解、照样给出"最优解" —— **只是这条规则被静默忽略了**。
> ⇒ **改 handler 时的硬性纪律：造了惩罚变量就必须 `penalties.add` 它。**

⚠ **一个真实的边界行为**（本册核对代码后的结论，第 2 册没有提）：
如果 `fixed > M` 而 `vars` 为空（即"该班这一天该科目全是固定课，且已经超上限"），
则不等式右边 $= fixed - M > 0$ 是个**常数**，② 的域上界 `totalPossible = fixed` 也允许它。
于是 `excess` 会被取到 $fixed - M$（或 0，取决于哪个更小 ——
准确的边界是 $excess = \min(fixed,\ \max(0, fixed - M))$，
⚠ **这一步的精确取值取决于目标对 `excess` 的系数与域上界的交互，
本册不做断言**）。**要点是：软分支下"固定课本身超限"不会抛异常，
而是被计成一个（部分或全部）与决策无关的惩罚项。**
⇒ 与硬分支（**抛 `ConstructionInfeasible` 并点名**）**行为不同**，
也与 §4.2 坑 2（**静默交给求解器**）不同。**三种处理，三种行为。**

**④ `penalties.add(excess)`** —— 把 `excess` 加进当前权重下的惩罚列表。
`RuleCompiler` 在这一轮循环开头已经调过
`penalties.useWeight(rule.effectiveWeight())`，所以权重是对的（§7.3）。

### 5.5 本条的三个坑（容易踩的）

**坑 1：`hard` 是"配置出来的"，不是"handler 决定"的。**
同一条规则，用户把它设为"硬"或"软"，走的是**两个完全不同的分支**：
- 硬 ⇒ **加约束**（可能让模型 `INFEASIBLE`）；
- 软 ⇒ **加惩罚**（永远可行，只是目标值变差）。

⚠ **所以"这条规则报 422/500"和"这条规则让排课变慢/变差"是完全不同的用户现象**，
排查前先看 `constraint_rules.hardness`（`ConstraintRule.Hardness`）。

**坑 2：`subject_id` 忽略时，循环规模会很大。**
不指定科目 ⇒ `subjects = ctx.subjectsOf(classId)`（该班所有科目）⇒
循环体是 **班 × 科 × 天** 次。4 个班 × 10 科 × 5 天 = 200 次；
目标是 160 班时这就是 **8,000 次**，其中大部分会走 `totalPossible <= max` 短路。
**这就是短路的实际价值所在。**

**坑 3：`validate` 才是范围校验，`apply` 不是。**
`validate` 会拒 `max = 0` / `max = 21` / `subject_id = 0`；
而 `apply` 里的 `requireInt` **只管"存在且是整数"**。
⚠ **两层校验的分工是刻意的**（`RuleParamSpec` 的 javadoc：一份 schema 同时驱动
"后端保存时校验 / 前端表单 / 助手工具定义"），
但⇒ **直接调 `apply` 的单元测试可以传入非法参数而不报错**。
写测试时要注意这一点。

### 5.6 这条约束在"真实教务"里对应什么

| 教务说法 | 参数 |
|---|---|
| "语文一天不要连着排两节"（弱） | 软 + `{"subject_id":3,"max":1}` |
| "任何科目一天最多两节"（强） | 硬 + `{"max":2}` |
| "主科每天都要有，但一天别超过一节" | 这条管上限；"每天都要有"是**另一条**约束（本册没有） |
| Python 的**软 1** | 软 + `{"max":1}`（**不指定科目**） |

⚠ **与 §6 的分工**：`max = 1` 的软版本罚的是
"同一科目一天出现 $k$ 次"的**次数**（$k-1$ 分），
而 §6 罚的是"**相邻两节**同科目"的**对数**。
**两条规则惩罚的是不同形状的违规**，可以**同时存在**（`RuleSets.legacyEquivalent()` 就同时启用）。
⇒ 一天 3 节同科目，但**只有 1 对相邻**时，软 1 罚 2 分、软 2 罚 1 分。**两笔都进同一个目标**。

---

## 6. `SubjectNoConsecutiveHandler` —— 可配置软约束：某科目不要连排两节（113 行）

**一句话定位**：对每一个"**相邻两节**"的时段对，如果两节**都上同一门课**，就罚 1 分。

对应 Python 的**软 2**。javadoc 第 21–23 行说明了原实现的痛苦：

> 原实现要在规则内部手工处理「前一节是固定课 / 后一节是固定课 / 两节都不是」三种分支
> 并自查 fixed 表；现在这些判断通过 `ctx.fixedSubjectAt` 完成，规则本体只剩业务逻辑。

**谁调它**：`RuleCompiler` 第 2 步，且**只有规则集显式引用 `"subject_no_consecutive"` 时**才生效。
`RuleSets.legacyEquivalent()` 里用 `RuleParams.empty()`（**不指定科目** = 所有科目都不连排）。

### 6.1 `params()` / `describe()`

```java
public static final String TYPE = "subject_no_consecutive";
public static final String P_SUBJECT = "subject_id";

@Override
public List<RuleParamSpec> params() {
    return List.of(
            RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）", 1, Integer.MAX_VALUE));
}
```

**只有** `subject_id`（可选），**没有 `max`** —— 因为"连排"的判定是**天然二元的**
（相邻两节要么都是这门课、要么不是），没有"连排几节算违规"这个参数量。
⚠ **要表达"最多连排两节"，本 handler 做不到** —— 它只有"连排 = 违规"这一档。

`describe(...)`：

```java
return params.has(P_SUBJECT)
        ? "科目 " + params.longOr(P_SUBJECT, -1) + " 不连排两节"
        : "所有科目都不连排两节";
```

同样的 `has` + `longOr(..., -1)` 显示兜底模式（§5.1）。

### 6.2 `apply` 的循环结构

```java
CpModel model = ctx.model();
Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;

for (ClassData cd : ctx.classes()) {                                    // 班
    long classId = cd.classId();
    Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
    for (long subjectId : subjects) {
        if (!ctx.subjectsOf(classId).contains(subjectId)) { continue; }  // 同 §5.2 ⑥
        for (int day = 0; day < ctx.days(); day++) {
            int periods = ctx.periodsOn(classId, day);                   // ① 该班这天的节数
            for (int period = 0; period + 1 < periods; period++) {       // ② 相邻对
                ...
            }
        }
    }
}
```

**① `int periods = ctx.periodsOn(classId, day)`** = **该班这一天**的节数
（`SlotVariableIndex.lastPeriod` → `classData(classId).periods()[day]`）。

⚠ **这里用的是"该班的节数"，而不是 §3 那种跨班的 `maxPeriodsOn`** ——
因为相邻对**必须在同一个班内**比较，所以用该班自己的节数才是对的。
**这是本册里两条 handler 用不同上界的唯一地方，值得记住。**

**② `for (int period = 0; period + 1 < periods; period++)`**
—— 上界是 `periods - 1`，因为每次要看 $(period, period+1)$ **一对**。
⚠ 写成 `period < periods` 会让 `period + 1 == periods` 时**访问不存在的下一节**，
`ctx.fixedSubjectAt(classId, day, periods)` 会返回 `-1`（因为该时段没进 `fixed` 表），
而 `ctx.subjectAt(classId, day, periods, subjectId)` 会返回 `null`（`slotVar` 对
`period >= 该班节数` ⚠ —— **实际上 `slotVar` 并不检查节数上界**，
它只查 `fixed` 与 `assignIndices`；**越界时行为不确定，本册不做断言**）。
**结论：`period + 1 < periods` 是必须的，不能想当然。**

### 6.3 四种情形 —— 本规则的核心

先记住 `ctx.fixedSubjectAt(classId, day, period)` 的约定：
**返回 $-1$ 表示"这个时段不是固定课"**，否则返回"固定课是哪门科目"。

```java
long fixedFirst  = ctx.fixedSubjectAt(classId, day, period);
long fixedSecond = ctx.fixedSubjectAt(classId, day, period + 1);
```

⚠ **判断必须写 `>= 0`**（不能写 `!= -1` 也可以，但**绝不能写 `!= 0`**）。
科目 ID 从 1 起（`optionalInt(P_SUBJECT, min = 1)`），所以 0 不会出现 ——
但 `>= 0` 与 `-1` 哨兵约定**成对存在**，改哨兵值时必须一起改。

**情形 A：两节都是固定课**

```java
if (fixedFirst >= 0 && fixedSecond >= 0) {
    if (fixedFirst == subjectId && fixedSecond == subjectId) {
        penalties.addConstant(1);
    }
    continue;
}
```

- **违规与否与决策无关**：两节都已经被固定课定死了，求解器改不了。
- 如果**两节恰好都是这门课** ⇒ 这是一次**必然发生的违规** ⇒
  `penalties.addConstant(1)` 加 **1 分的常数惩罚**。
- ⚠ **常数惩罚的数学含义**：它在**每一个可行解**里取值都相同，
  所以它**不影响哪个解最优**，只让**报告的目标值 +1**。
  （`PenaltyCollector` 的 javadoc 说得更直接：
  "常数项也支持（例如「两节连着的固定课都是同一科目」这种与决策变量无关的固定惩罚）"。）
- ⚠ **这里 `continue` 掉了**，所以下面三个分支不会重复计这道违规。**每次违规只罚一次。**

**情形 B：只有前一节是固定课**

```java
if (fixedFirst >= 0) {
    if (fixedFirst != subjectId) { continue; }        // 固定课不是这门课 ⇒ 不可能违规
    BoolVar second = ctx.subjectAt(classId, day, period + 1, subjectId);
    if (second != null) { penalties.add(second); }
}
```

- `fixedFirst != subjectId` ⇒ 这一对里第一节不是这门课 ⇒ **不可能构成"连排"** ⇒ `continue`。
- `fixedFirst == subjectId` ⇒ **违规 ⟺ 后一节也是这门课**，
  而"后一节是这门课"的布尔正是 `ctx.subjectAt(classId, day, period+1, subjectId)`。
  ⇒ **直接罚那个布尔**（$=1$ 时罚 1 分，$=0$ 时罚 0 分）。

  **这就是"布尔即 0/1 ⇒ 罚一个布尔 = 每次违规罚 1"的全部含义**，不需要任何额外编码。

- ⚠ `if (second != null)`：这是**必要的防御**，不是冗余。
  `subjectAt` 在两种情况下返回 `null`：① 该时段是固定课；② 该班**没有**该科目的任教关系。
  情形 B 里前一节固定，所以 ① 不会发生；**但 ② 完全可能**
  （`subject_id` 参数指定的科目在这个班没有任教关系 ⇒ `subjects` 是单元素列表
  且 `⑥` 的判断会挡住它 ⇒ 实际上走不到这里；**但"该班有此科目、这个时段却不是自由课"的其它组合
  本册未穷举**）。
  ⇒ **`null` 检查是"不确定就跳过"，宁可少罚也不报 `NullPointerException`。**

**情形 C：只有后一节是固定课**

```java
} else if (fixedSecond >= 0) {
    if (fixedSecond != subjectId) { continue; }
    BoolVar first = ctx.subjectAt(classId, day, period, subjectId);
    if (first != null) { penalties.add(first); }
}
```

**情形 B 的镜像**：违规 ⟺ 前一节也是这门课 ⇒ 罚 `first`。

**情形 D：两节都不是固定课**（一般情况）

```java
} else {
    BoolVar first  = ctx.subjectAt(classId, day, period, subjectId);
    BoolVar second = ctx.subjectAt(classId, day, period + 1, subjectId);
    if (first != null && second != null) {
        BoolVar both = model.newBoolVar(
                String.format("cons_%d_%d_%d_%d", classId, day, period, subjectId));
        model.addBoolAnd(new Literal[] {first, second}).onlyEnforceIf(both);
        model.addBoolOr(new Literal[] {first.not(), second.not()})
                .onlyEnforceIf(both.not());
        penalties.add(both);
    }
}
```

**违规 ⟺ `first ∧ second`**，所以需要一个"$both \Leftrightarrow first \land second$"的变量：

| 行 | 约束 | 逻辑式 |
|---|---|---|
| `addBoolAnd({first, second}).onlyEnforceIf(both)` | $both \Rightarrow (first \land second)$ | 正向 |
| `addBoolOr({first.not(), second.not()}).onlyEnforceIf(both.not())` | $\lnot both \Rightarrow (\lnot first \lor \lnot second)$，即 $\lnot both \Rightarrow \lnot(first \land second)$ | 反向 |
| 合起来 | $both \Leftrightarrow first \land second$ | **双向等价** |

⚠ **两条缺一不可**（与 `HandlerSupport.encodeAnd` 的推导完全相同，§2.8）：
**只写第一条**，`both` 在"两节都排了该科目"时**仍可取 0** ⇒ **惩罚被逃掉**，
求解器会立刻学会这么干（罚分为 0 的解更优），**这条软约束等于没加，而且不报错**。

⚠ **这两行就是 `HandlerSupport.encodeAnd(model, both, new Literal[]{first, second})` 的
手写展开** —— **行为完全一致，但没有复用那个工具方法**。
这是本册发现的一处**可以统一的地方**（`encodeAnd` 的唯一调用者在
`TeacherMinimizeGapsHandler`，属于 2d 册）。
**现在不改**：改了 `CpModelProto` 顺序可能变化 ⇒ 指纹变 ⇒ `GoldenModelTest` 红。

⚠ **`addBoolAnd(new Literal[]{first, second})` 里 `first`/`second` 是 `BoolVar`** ——
直接当 `Literal` 用（`BoolVar implements Literal`，§0 的事实 1）。
`.not()` 返回 `NotBoolVar`，同样 `implements Literal`。

⚠ **变量名 `cons_{classId}_{day}_{period}_{subjectId}`**（`cons` = consecutive）。
全是 **0-based**（与 §5 异常文案的 1-based **相反**）。
名字里**带 `subjectId`**，所以"不指定科目"时会有大量 `cons_*` 变量；
指定单一科目时只有该科目那一批。

### 6.4 ⚠ **本条最重要的一条事实：它忽略 `hard`**

`RuleHandler.apply` 的签名带 `boolean hard`，`RuleCompiler` 也老老实实传了
`!rule.isSoft()`，但**本 handler 从第一行到最后一行的代码里没有出现过 `hard`**：

⇒ **无论配置成硬还是软，它都只加惩罚项。**
**因此这条规则"实际只能是软的"**，尽管
`params()` / `ConstraintRule.hard(...)` / 前端表单**都允许把它配成"硬"**。
配置成硬时，`RuleCompiler` 会传 `hard = true`，**handler 忽略它**，
于是"硬约束"被**静默地当软约束执行 —— 罚分而不是禁止**。

⚠ **这是一个真实的、静默的行为偏差**，本册如实记录。
（`RuleSets.legacyEquivalent()` 只用软，所以**迁移路径上不会触发**；
但用户可以在前端把它改成硬 —— 那时**界面说"硬"，行为是"软"**。）

> 对照：`SubjectMaxPerDayHandler`（§5）**两条路径都实现了**，
> 所以"硬软两用"在它身上是真的。**同样是"可配置"，两条 handler 的完成度不同。**

### 6.5 惩罚进目标函数的完整链路（与 §5 相同）

1. 本 handler 调 `penalties.addConstant(1)` / `penalties.add(bool)`；
2. `PenaltyCollector.add` 按 **`currentWeight`**（`RuleCompiler` 在这一轮开头用
   `useWeight(rule.effectiveWeight())` 设好的）把项放进 `unitTerms` 或 `weightedTerms`；
3. 所有规则跑完后，`RuleCompiler` 第 3 步调 `penalties.commit()`；
4. `commit()` 里：`isEmpty()` 就**不设置目标**（与 Python 的 `if penalty_terms:` 一致），
   否则 `model.minimize(unitTerms 之和 + Σ 权重×项 + 常数)`（§7.3）。

⚠ **`isEmpty()` 的三元判断**（`unitTerms` / `weightedTerms` / `constant` **都空**才算空）
意味着：**只要有一条软规则产生了"常数惩罚"，目标就会被设置**（虽然那个常数不影响解的选择）。
`RuleSets.legacyEquivalent()` 在"某班有两节连着的同名固定课"时就会走到这种情况。

### 6.6 这条约束在"真实教务"里对应什么

| 教务说法 | 说明 |
|---|---|
| "语文不要连着上两节" | `{"subject_id": 3}` |
| "任何科目都别连排" | 不写 `subject_id`（Python 软 2 就是这一档） |
| "数学连堂" | ⚠ **本规则恰恰是要禁止的东西**；要允许连堂就不加这条规则 |

⚠ **它不是"禁止同一天出现两次"**（那是 §5 的 `max = 1`）。
它是"**禁止相邻**"。一天 3 节同科目但只有 1 对相邻时，只罚 1 分。

<!--PART5-->


