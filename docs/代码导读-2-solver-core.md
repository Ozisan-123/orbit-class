# Orbit 代码导读（第二期）—— `orbit-solver-core` 规则系统 + CP-SAT

> **与《架构说明》的分工**：
> [架构说明.md](架构说明.md) §3.4 讲「这个模块为什么独立、为什么是库不是服务」；
> **本篇讲「这个类/这个方法逐行在干嘛、往 CP-SAT 模型里加了什么、谁调它、不这么写会怎样」**。
>
> **与第一篇的分工**：[代码导读.md](代码导读.md)（`orbit-common`）定的是**格式与详细程度的基准**。
> 本篇比它**再细一层**：每个方法先给签名，再按代码顺序讲每一段/每一行；
> 凡是 CP-SAT 建模的地方，都要说清**它加了什么变量/约束、数学含义是什么**。

---

## 0. 本模块在系统里的位置

`orbit-solver-core` 是**一个库，不是一个服务**：它不知道 Spring、不知道数据库、不知道 HTTP
（`org.springframework.*` / `com.baomidou.*` / `jakarta.*` 的 import 出现 **0 次**，
`pom.xml` 只有 `ortools-java` + `jackson-databind` + `junit-jupiter` 三个依赖，
见 [pom.xml](../../backend-java/orbit-solver-core/pom.xml)）。它的**输入是一个 record（`ScheduleInput`）、
输出是一个 record（`SolveOutcome`）**，中间把一个「小学排课问题」编译成 CP-SAT 模型。

它被**两个模块**依赖，这是"规则只有一份定义"的落点：
- `orbit-scheduling`：同进程调用（`TimetableGenerationService` 真求解、`RuleTrialService` 只编译做"试算影响"、
  `RuleTypeCatalog` 把 `BuiltinRules.registry()` 换个形状暴露给前端/助手）；
- `orbit-solver-worker`：P3 起在**独立进程**里调用（[JobRunner.java](../../backend-java/orbit-solver-worker/src/main/java/com/orbit/solver/worker/JobRunner.java)）——
  搬运时本模块**一行都没改**（[架构说明.md](架构说明.md) §3.4）。

一条贯穿全模块的目标仍然是「**行为等价**」：Java 侧的**模型形状**（变量数、约束数、目标项）
必须与被替换的 Python `scheduler/engine.py` 一致 —— [GoldenModelTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/GoldenModelTest.java)
把它固化成了断言。

### 0.1 类清单（32 个：main 31 + test 1）

架构说明里写的是「33 个类」，`glob` 在本模块 `src/main/java` 下数到 **31 个 .java 文件**；
另有 1 个属于本模块、但刻意放在**测试**目录的类（`legacy/FaithfulModelBuilder`，等价性基线）
与它一起构成"求解内核"这个话题，因此本篇一并覆盖，共 **32 个**。
（差额 1 个无法从代码里确认来源，可能是按 Java 类型/记录内嵌类型计数 —— **此处未完全确认**。）

| 包 | 类 | 行数 | 一句话 |
|---|---|---|---|
| （根） | `OrbitSolver` | 87 | 对外**唯一门面**：compile / solve / fingerprintOf |
| `model` | `ScheduleInput` | 52 | 一次排课的全部输入（含 6 个内嵌 record） |
| `model` | `ClassData` | 12 | 单班每天节数（是数组，不是常数） |
| `model` | `TimetableEntry` | 10 | 求解结果的一行 |
| `rule` | `ConstraintRule` | 36 | 一条生效的规则实例（type+params+硬软+权重） |
| `rule` | `RuleHandler` | 46 | 一种规则的**实现**接口 |
| `rule` | `RuleParamSpec` | 43 | 单个参数的 Schema（驱动三处消费） |
| `rule` | `RuleParams` | 130 | 参数值 + 取值（宽容）+ 校验（严格） |
| `rule` | `RuleRegistry` | 68 | `type → handler` 映射（刻意不认识内置规则） |
| `rule` | `RuleContext` | 293 | 规则作者看到的"课表世界"（固定课记账收敛到这一处） |
| `rule` | `PenaltyCollector` | 72 | 软约束惩罚项收集 + 统一 `minimize` |
| `rule/handler` | `HandlerSupport` | 78 | 线性表达式与布尔编码工具 |
| `rule/handler` | `TeacherNoConflictHandler` | 74 | 不变量：教师时段不冲突 |
| `rule/handler` | `SubjectWeeklyHoursHandler` | 73 | 数据驱动：周课时排满 |
| `rule/handler` | `TeacherWeeklyLimitHandler` | 65 | 数据驱动：教师周上限 |
| `rule/handler` | `SubjectMaxPerDayHandler` | 106 | 单日单科节数上限（硬软两用） |
| `rule/handler` | `SubjectNoConsecutiveHandler` | 113 | 单科不连排（软） |
| `rule/handler` | `TeacherMaxDailyPeriodsHandler` | 98 | 教师单日节数上限（硬软两用） |
| `rule/handler` | `TeacherUnavailableHandler` | 92 | 教师不可用时段 |
| `rule/handler` | `SubjectAvoidLastPeriodHandler` | 114 | 单科不排最后一节 |
| `rule/handler` | `MainSubjectMorningHandler` | 117 | 主科优先上午 |
| `rule/handler` | `TeacherMinimizeGapsHandler` | 161 | 教师课表不留空档（本模块最贵的一条） |
| `compile` | `RuleCompiler` | 77 | 规则集 → CP-SAT 模型 |
| `compile` | `CompiledModel` | 19 | 编译产物（模型 + 索引 + 上下文 + 统计 + 指纹） |
| `compile` | `BuiltinRules` | 51 | **装配点**：内置 10 种规则的注册顺序 |
| `compile` | `RuleSets` | 54 | 预设规则集（含迁移期关键的 `legacyEquivalent`） |
| `engine` | `SlotVariableIndex` | 314 | 变量与"成员布尔"的建立与缓存（engine 层唯一建变量的类） |
| `engine` | `TimetableExtractor` | 61 | 从解里还原课表行（含空堂哨兵） |
| `engine` | `SolveOutcome` | 31 | 求解结果（**带 status**：区分超时与真无解） |
| `engine` | `SolverSettings` | 10 | 求解参数（含 `production()`） |
| `engine` | `SolverNatives` | 25 | 原生库加载（⚠ 与 JDK 版本强相关） |
| `engine` | `ModelFingerprints` | 35 | 从 `modelStats()` 文本里抠指纹 |
| `engine` | `ConstructionInfeasible` | 17 | 建模期就发现"无论怎么排都不可能" |
| `test/legacy` | `FaithfulModelBuilder` | 568 | 忠实移植的 Python 建模（**等价性基线**，仅在测试里） |

### 0.2 阅读顺序建议

**不要从 `OrbitSolver` 往下按包读**（那样会先撞上 300 行的 `SlotVariableIndex`）。
建议按「先建立世界模型 → 再读一条最简单的规则 → 再读编译器 → 最后读变量工厂」的顺序：

| # | 先读 | 为什么从这里开始 |
|---|---|---|
| 1 | [`ScheduleInput`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/model/ScheduleInput.java) + [`ClassData`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/model/ClassData.java) | 只有 52 + 12 行，但**整个模块只认这 7 类输入**。看懂它，后面每个 handler 的循环变量就都有出处 |
| 2 | [`OrbitSolver`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/OrbitSolver.java) | 87 行的门面，给出「compile 与 solve 为什么分开」的全景 |
| 3 | [`RuleHandler`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/RuleHandler.java) + [`TeacherNoConflictHandler`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/handler/TeacherNoConflictHandler.java) | 只读这一条**最短的**规则，就能看懂"规则 = 往模型里加约束"是什么意思 |
| 4 | [`RuleContext`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/RuleContext.java) | 规则作者看到的 API。**固定课记账为什么必须收敛到一处**，看 javadoc 那段就够 |
| 5 | [`PenaltyCollector`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/PenaltyCollector.java) + [`RuleCompiler`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/compile/RuleCompiler.java) | 硬约束 vs 软约束两条路在这里合流：惩罚项收集、目标函数提交、编译顺序 |
| 6 | [`RuleParamSpec`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/RuleParamSpec.java) → [`RuleParams`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/RuleParams.java) → [`RuleRegistry`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/RuleRegistry.java) → [`BuiltinRules`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/compile/BuiltinRules.java) | 「一份 schema 三处消费」那条链，从声明读到装配 |
| 7 | **最后**读 [`SlotVariableIndex`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/engine/SlotVariableIndex.java) | 314 行、最底层、最数学。但读完前 6 步后你会发现它只回答一个问题：**"这个时段的决策变量长什么样"** |
| 8 | 十条 handler 的其余九条 + `engine` 的小类 | 有了 3 和 7 的基础，剩下的 handler 都是同一个套路的变体 |
| 9 | [`RuleSets`](../../backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/compile/RuleSets.java) + [`GoldenModelTest`](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/GoldenModelTest.java) | 收尾：看懂"为什么改了模型会立刻红，而这是好事" |

> **一条捷径**：如果你只想搞懂"某条规则到底怎么生效的"，直接读它的 `apply(...)`。
> 十条 `apply` 有**统一的骨架**：
> `for (每个班/每位教师) → for (每天) → for (每节) → 收集 BoolVar → addLessOrEqual / addEquality`。
> 差异只在"收集哪些变量"和"和什么比"。

---

## 1. `OrbitSolver` —— 对外唯一门面（87 行）

**为什么要有它**：它是全模块**唯一**被 `orbit-scheduling` 与 `orbit-solver-worker` import 的类
（实测：外部模块只 import 了 `OrbitSolver` / `CompiledModel` / `SolveOutcome` / `SolverSettings` /
`ScheduleInput` / `ConstraintRule` 这几个类型）。它位于**根包**，因此可以同时依赖 `compile` 与
`engine` 而不产生包环；反过来没有任何包依赖它（见 [solver-core-包依赖图.html](solver-core-包依赖图.html)）。

它是 `final` 类 + 私有构造器（第 85–86 行）：纯静态门面，不允许被实例化或继承。

### 方法清单

| 方法 | 干什么 | 谁调它 |
|---|---|---|
| `static RuleRegistry builtinRules()` | 返回内置规则注册表 | `orbit-scheduling` 的 `RuleTypeCatalog`；`SpikeMain` |
| `static CompiledModel compile(ScheduleInput, List<ConstraintRule>)` | 编译（用内置注册表） | `RuleTrialService`、测试（`GoldenModelTest` / `CounterexampleInjectionTest` …） |
| `static CompiledModel compile(ScheduleInput, List<ConstraintRule>, RuleRegistry)` | 编译（可换注册表） | **只有测试**：反例注入测试用"去掉某条规则"的注册表做**灵敏度反证** |
| `static SolveOutcome solve(CompiledModel, SolverSettings)` | 求解一个已编译的模型 | `SpikeMain`、测试（先编译再强制某些变量，再解） |
| `static SolveOutcome solve(ScheduleInput, List<ConstraintRule>, SolverSettings)` | 一步到位 | `TimetableGenerationService`、`JobRunner`、`PositiveFeasibilityTest` |
| `static String fingerprintOf(CpModel)` | 取模型指纹 | `SpikeMain`、`GoldenModelTest`（对基线模型取指纹） |

### 1.1 `static RuleRegistry builtinRules()`

```java
public static RuleRegistry builtinRules() {
    return BuiltinRules.registry();
}
```

**逐行**：唯一一行就是转发给 [`BuiltinRules.registry()`](#41-builtinrules--内置规则的装配点51-行)。

**为什么要有这个转发**：调用方（scheduling、worker）**只依赖根包的 `OrbitSolver`**，
不需要 import `compile` 包的类 —— 这样"注册表怎么装的"是编译包自己的事，
换装配方式不影响任何调用方。也正因为它是方法而不是字段，调用方**不可能持有并改坏**这个注册表
（每次都是新造的，见 §4.1 的构造函数）。

### 1.2 `compile(input, rules)` 与 `compile(input, rules, registry)`

```java
public static CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules) {
    return compile(input, rules, BuiltinRules.registry());      // ① 默认用内置注册表
}

public static CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules,
                                    RuleRegistry registry) {
    SolverNatives.load();                                        // ② 加载 OR-Tools 原生库
    return new RuleCompiler(registry).compile(input, rules);     // ③ 每次都新建一个编译器
}
```

**逐行**：

- **①** 二参重载只是把注册表参数补成内置的。**注意它是 `return compile(...)` 而不是复制编译逻辑** ——
  编译路径只有一条，测试和生产不会漂移。
- **② `SolverNatives.load()`**：在建模型**之前**调用。为什么必须先加载？因为 `new CpModel()`
  本身就要走 JNI。⚠ 这一行是**全模块最"环境敏感"的一行**：实测 JDK 23 上加载建模都正常，
  但 `CpSolver.solve()` 会在 `msvcp140.dll` 里 `EXCEPTION_ACCESS_VIOLATION` 段错误，
  **JDK 21 LTS 正常**（[踩坑总结.md](踩坑总结.md) E3）。
- **③ `new RuleCompiler(registry)`**：编译器是**每次编译新建的实例**，它只持有注册表。
  为什么不缓存/做成单例？`RuleCompiler` 自己没有可变状态，但"每次新建"让"注册表"
  与"模型"严格一对一 —— 反例注入测试正是靠 `compile(input, rules, withoutConflict)`
  传入一个**只有一条规则的注册表**来做灵敏度反证的。

**不这么写会怎样**：如果 `compile` 里顺手把 `rules` 也过滤一遍（比如只取 `enabled`），
那么 `RuleCompiler` 就得再实现一次同样的过滤 —— 两个地方都会漏。**过滤只在 `RuleCompiler` 里做一次**（§5.1 第 ②步）。

### 1.3 `solve(CompiledModel, SolverSettings)`

```java
public static SolveOutcome solve(CompiledModel compiled, SolverSettings settings) {
    SolverNatives.load();
    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(settings.maxTimeInSeconds());
    solver.getParameters().setNumSearchWorkers(settings.numSearchWorkers());
    solver.getParameters().setLogSearchProgress(settings.logSearchProgress());

    CpSolverStatus status = solver.solve(compiled.model());
    List<TimetableEntry> entries =
            (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE)
                    ? TimetableExtractor.extract(solver, compiled.index())
                    : List.of();

    return new SolveOutcome(status, entries, compiled.modelStats(),
            compiled.modelFingerprint(), solver.wallTime(),
            (long) solver.objectiveValue(), (long) solver.bestObjectiveBound());
}
```

**逐行**：

- **`SolverNatives.load()`**：`compile` 里已调用过，这里再调一次。为什么？因为
  `solve(CompiledModel, ...)` **可以被单独调用**（测试就是这么用的：先 compile，往里强制几个变量，再 solve），
  调用路径上不能假设前一步存在。`load()` 内部有 `loaded` 标志，重复调用无副作用（§7.3）。
- **`new CpSolver()`**：**每次都新建**。CP-SAT 的 `CpSolver` 持有一次求解的状态；
  复用它需要自己保证清空，风险大于收益（而且求解器实例不是线程安全的）。
- **三行 `setXxx`**：把 `SolverSettings` 的三个字段逐个搬进去。**为什么用三行显式赋值而不是循环/反射**：
  参数名与字段名一一对应，读的时候不用跳转就能确认"这个字段真的被用上了"——
  ⚠ 这类"配置字段写了但没生效"是最常见的静默错误（本项目在别处踩过：yml 键名对不上时 Spring 静默忽略，
  见 [踩坑总结.md](踩坑总结.md) K4）。
- **`solver.solve(compiled.model())`**：真正跑起来。这一行是**全模块唯一会长时间阻塞的地方**
  （生产 60 秒）。⚠ 它**不可中断**：`orbit-solver-worker` 的心跳线程发现任务被接管后只能打标记，
  等这次求解跑完再丢弃结果（[JobRunner.java](../../backend-java/orbit-solver-worker/src/main/java/com/orbit/solver/worker/JobRunner.java) 的注释写明了这一点）。
- **三元表达式**：**只有** `OPTIMAL` 或 `FEASIBLE` 才去提取课表，其余（`INFEASIBLE` / `UNKNOWN` / `MODEL_INVALID`）
  取 `List.of()`。数学上：`INFEASIBLE` = 已证明无解，`UNKNOWN` = 到时限还没定论。
  **两者都返回空列表** —— 与 Python 的"压成同一个空列表"在这里保持了一致，
  但**状态没有丢**（下一行的 `status` 带出去了），所以上层还能区分。这正是 [SolveOutcome](#71-solveoutcome--求解结果31-行) 存在的理由。
- **`new SolveOutcome(...)`**：把 7 个东西一起交出去。注意 `compiled.modelStats()` 与
  `compiled.modelFingerprint()` 是**编译时**就算好的（§5.1），这里只是搬运 —— 求解完再算一次
  会多花时间，而且没有意义（模型没变）。
- **`(long) solver.objectiveValue()`**：⚠ **必须强转**。CP-SAT 的 `objectiveValue()` 返回 `double`，
  而本项目的目标值恒为**整数**（惩罚项都是整数加权）。**不转**的话：
  上层（worker 上报、诊断比较）会拿到 `3.0` 这种形状，与 Python 的整数目标值**形状不一致**，
  契约比对会报差异（这类"数值形状"差异在本项目踩过多次）。`bestObjectiveBound()` 同理。

**`solve` 与 `compile` 为什么刻意分开**（javadoc 第 35–36 行）：
- **指比对**：只需要编译结果的统计/指纹，不需要求解（[GoldenModelTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/GoldenModelTest.java) 全程不求解）；
- **规则"试算影响"**：`RuleTrialService` 只 `compile` 前后两份，比较**模型差异**，60 秒的求解一次都不跑；
- **不可行性诊断**：先静态看配置，实在要解才解。

如果合成一个方法，这三个场景都会被迫付出一次 `solve()` 的代价（生产配置是 60 秒）。

### 1.4 `solve(input, rules, settings)` 与 `fingerprintOf(model)`

```java
public static SolveOutcome solve(ScheduleInput input, List<ConstraintRule> rules,
                                 SolverSettings settings) {
    return solve(compile(input, rules), settings);      // 编译 + 求解
}

public static String fingerprintOf(CpModel model) {
    return ModelFingerprints.of(model);
}
```

**逐行**：`solve` 的三参重载 = `compile` 后立刻 `solve`，**没有第三条路径**。
它是 `TimetableGenerationService` 和 `JobRunner` 用的那个入口（那两个类只关心"给输入给规则，拿结果"）。

`fingerprintOf` 转发给 [`ModelFingerprints.of`](#76-modelfingerprints--从统计文本里抠指纹35-行)，
存在的理由是**让外部模块不用 import `engine` 包**（同 §1.1 的理由）。

---

## 2. `model` —— 输入与输出（3 个类，74 行）

### 2.1 `ScheduleInput` —— 一次排课的全部输入（52 行）

**它是这个模块的"世界观"**：7 个列表 + 1 个组织 ID，对应 Python `generate_schedule` 开头那 7 条查询。

```java
public record ScheduleInput(
        long organizationId,
        List<GradeInfo> grades,
        List<ClassInfo> classes,
        List<SubjectInfo> subjects,
        List<TeacherInfo> teachers,
        List<Assignment> assignments,
        List<GradeSubjectInfo> gradeSubjects,
        List<FixedSlotRule> gradeFixedSlots) {
```

**为什么是 `record` 且不带任何持久化注解**：它被两种完全不同的来源填充 ——
spike 阶段从 JSON fixture 读（[FixtureLoader](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/FixtureLoader.java)），
正式实现里由 scheduling 服务拉 academic 快照后组装。**带上 JPA/MyBatis 注解就等于把数据库拖进这个纯库**，
P3 搬运到独立进程时就得改代码。

> ⚠ **record 的组件顺序是契约**：`TimetableExtractor`、`SlotVariableIndex`、测试里的
> `withOnePeriodPerDay` / `withTeacherLimit` 都会用 `new ScheduleInput(...)` **位置化**重建它。
> **加字段必须同时改所有 `new ScheduleInput(` 调用点**，否则编译期就会报错（这是好事，位置化构造器是强制的）。

| 组件 | 含义 | 谁在用 |
|---|---|---|
| `organizationId` | 组织（学校）ID | 求解内核**完全不用它**（纯透传；跨组织隔离是 scheduling 的事） |
| `grades` | 年级 + 各班每天节数 | `SlotVariableIndex.prepare()` 展开成 `ClassData` |
| `classes` | 班级（含 `gradeId`） | 到处都用 |
| `subjects` | 科目（含 `category`） | ⚠ **本模块只在 fixture 反序列化时保留，`category` 在建模里未被读取**（"主科"是靠规则参数里的 `subject_id` 指定的，不是靠 category 自动判定） |
| `teachers` | 教师 + 周上限 | 两个数据驱动硬规则 |
| `assignments` | 任教关系（哪班哪科哪位老师） | 变量的**取值域**就是它 |
| `gradeSubjects` | 年级科目周课时 | 「周课时排满」硬规则 |
| `gradeFixedSlots` | 年级级固定课 | 展开到该年级每个班 |

#### 6 个内嵌 record

| record | 组件 | 逐字段说明 |
|---|---|---|
| `GradeInfo` | `id, name, periodsPerDay` | `periodsPerDay` 的键是 `"Mon".."Fri"`（见 §7.1 的 `DAY_KEYS`）；⚠ **这是唯一一处"每天节数不固定"的来源** |
| `ClassInfo` | `id, name, gradeId` | |
| `SubjectInfo` | `id, name, category` | `category` 用途见上表 ⚠ |
| `TeacherInfo` | `id, name, subjectId, maxWeeklyHours` | `subjectId` 在本模块也**未被建模使用**；`maxWeeklyHours` 是硬上限 |
| `Assignment` | `classId, subjectId, teacherId` | javadoc 明说：**字段顺序刻意与 Python 的 `all_assigns` 元组一致**，避免移植时下标错位 |
| `GradeSubjectInfo` | `gradeId, subjectId, weeklyHours` | |
| `FixedSlotRule` | `gradeId, subjectId, day, period` | ⚠ 这里是**年级级**，要由 `SlotVariableIndex.prepare()` 展开到该年级的每个班 |

**为什么 `Assignment` 要强调字段顺序**：Python 侧是**元组下标**（`assign[0]` = class_id），
Java 侧是具名访问器。两者语义相同，但如果谁把 Java 的 record 组件顺序改成
`(subjectId, classId, teacherId)`，**Java 代码仍然编译通过**（`a.classId()` 还是 `a.classId()`），
而 JSON fixture / 快照的**位置化**构造（如果有）就会静默错位。这条注释是**防止未来的人凭手感重排字段**。

### 2.2 `ClassData` —— 单班每天的节数（12 行）

```java
public record ClassData(long classId, int[] periods, int total) {
}
```

**为什么 `periods` 是 `int[]` 而不是 `int`**（javadoc 第 6–9 行，这是本模块最容易写错的语义之一）：
不同年级的 `periods_per_day` 可以不同（低年级 6 节、高年级 8 节），
所以「**当天最后一节**」必须**按班按天**算：`periods[d] - 1`。

> ⚠ **把"最后一节"写成常数会安静地失效**：约束照加，只是加错了位置。
> `SubjectAvoidLastPeriodHandler` 的 javadoc 专门写了这一条；`RuleContext.periodsOn(classId, day)`
> 就是为了让每条规则都能拿到"正确的那一天的那一节"。

- `periods[d]`：索引 `0..4` 对应周一..周五（不是 1..5，**内部一律 0 基**）；
- `total`：整周节数（**唯一用途是构造后在别处读**；实测本模块建模代码没有读它 ——
  `SlotVariableIndex` 存了但 handler 不读，真正的遍历一律 `periods[d]`。它更像是给上层/日志用的汇总值。**此处未完全确认**是否有外部读者）。

### 2.3 `TimetableEntry` —— 求解结果的一行（10 行）

```java
public record TimetableEntry(
        long classId, long subjectId, long teacherId, int dayOfWeek, int periodIndex) {
}
```

**注意 `dayOfWeek` / `periodIndex` 是 0 基**（`TimetableExtractor` 里的循环从 0 开始），
而**规则参数**里的 `day` / `period` 是 **1 基**（`TeacherUnavailableHandler` 内部减 1，§5.10）。
这是本模块术语上最容易混的一对：**"参数是给人看的，内部是给机器算的"**。

---

## 3. `engine` —— 变量工厂、提取、结果与工具（7 个类，493 行）

### 3.1 `SlotVariableIndex` —— 变量与"成员布尔"（314 行，本模块最底层）

**它只回答一个问题**：*"某班某天某节"这个时段的决策变量长什么样？*
它**不含任何业务约束**（约束全在 `rule/handler`），这是刻意的分层 —— javadoc 第 21–22 行。

#### 3.1.1 常量与内部 key

```java
static final int DAYS = 5;
private static final String[] DAY_KEYS = {"Mon", "Tue", "Wed", "Thu", "Fri"};

public record FixedEntry(long subjectId, long teacherId) { }
private record SlotKey(long classId, int day, int period) { }
private record SubjectSlotKey(long classId, int day, int period, long subjectId) { }
private record TeacherSlotKey(long teacherId, long classId, int day, int period) { }
```

**逐行**：
- `DAYS = 5`：**包级可见（无修饰符）** —— `RuleContext` 有自己的 `public static final DAYS = 5`（§4.6），
  两个常量必须一致；这里用包级可见而不是 private，是给同包类用的。
- `DAY_KEYS`：从 `periodsPerDay` 这个 JSON 对象里取每天节数用的键。
  ⚠ **这里就是"一天 6 节"默认值的来源**：`prepare()` 里 `v == null ? 6 : v`（§3.1.3）。
- `FixedEntry`：固定课占用（科目 + 教师）。**它是值对象而非变量** —— 固定课不参与决策。
- 三个 `key` record：**用 record 当 Map 的键**，自动获得 `equals`/`hashCode`。
  `TeacherSlotKey` 的组件顺序是 `(teacherId, classId, day, period)`，
  ⚠ **与 `SubjectSlotKey` 的 `(classId, day, period, subjectId)` 顺序不同** ——
  这是按"最常用来遍历/查找的那一维放前面"写的，但读代码时容易看串，**改动时务必看声明**。

#### 3.1.2 字段：三组索引 + 三个变量缓存

```java
private final ScheduleInput in;
private final CpModel model;

private final Map<Long, ClassData> classData = new LinkedHashMap<>();
private final List<ScheduleInput.Assignment> allAssigns = new ArrayList<>();
private final Map<Long, List<Integer>> classAssigns = new LinkedHashMap<>();
private final Map<Long, Map<Long, Integer>> classHours = new LinkedHashMap<>();
private final Map<Long, Set<Long>> classSubjects = new LinkedHashMap<>();
private final Map<SlotKey, FixedEntry> fixed = new LinkedHashMap<>();
private final Map<Long, Set<Long>> teacherClasses = new LinkedHashMap<>();
private final Map<Long, ScheduleInput.TeacherInfo> teachers = new LinkedHashMap<>();

private final Map<SlotKey, IntVar> slotVars = new LinkedHashMap<>();
private final Map<SubjectSlotKey, BoolVar> subjectSlot = new LinkedHashMap<>();
private final Map<TeacherSlotKey, BoolVar> teacherSlot = new LinkedHashMap<>();
```

**为什么全部用 `LinkedHashMap` / `ArrayList` / `TreeSet`，一个 `HashMap`/`HashSet` 都没有**
（javadoc 第 29–30 行）：**模型必须可复现**。CP-SAT 的 `model_fingerprint` 对
**变量创建顺序**敏感（[踩坑总结.md](踩坑总结.md) E2 说明了这一点是"优点"），
而 `HashMap` 的迭代顺序在同一 JVM 里稳定、**跨 JDK/跨进程不保证**。
一旦顺序变，指纹变、GoldenModelTest 变红 —— 而那是**假红**（模型语义没变）。

> **这一条不是洁癖，是被指纹测试逼出来的纪律**：任何一处 `new HashMap<>()` 都可能让
> `0x3cbfb67312cda176` 这个基线在某些环境上对不上。

**三组缓存的地位不同**：
- `slotVars` / `subjectSlot` / `teacherSlot` 是**懒创建缓存**（第一次请求才建变量），
  这是本模块**最重要的性能设计**（§3.1.6）；
- 其余 8 个 Map 是 `prepare()` 一次性建好的**只读索引**。

#### 3.1.3 构造与 `prepare()` —— 8 段 索引构建

```java
private SlotVariableIndex(CpModel model, ScheduleInput in) {
    this.model = model;
    this.in = in;
    prepare();
}
public static SlotVariableIndex create(CpModel model, ScheduleInput in) {
    return new SlotVariableIndex(model, in);
}
```

**为什么私有构造器 + 静态工厂**：`create` 在构造过程中就会跑 `prepare()`（可能抛
`ConstructionInfeasible`），用静态工厂让"创建即校验"这件事在调用点看起来是一个动作。
调用点只有一处：[`RuleCompiler.compile`](#51-rulecompiler--规则集--cp-sat-模型77-行) 第 44 行。

`prepare()` 逐段：

**① 教师表（第 78–80 行）**
```java
for (ScheduleInput.TeacherInfo t : in.teachers()) {
    teachers.put(t.id(), t);
}
```
`id → TeacherInfo`。**保序**（`LinkedHashMap`），后面 `teachersInOrder()` 直接返回它的 `values()` ——
`RuleContext.teachers()` 的遍历顺序就是这里定义的，而它决定了
`TeacherNoConflictHandler` / `TeacherWeeklyLimitHandler` 建变量的顺序。

**② 班级节数（第 82–99 行）**
```java
Map<Long, ScheduleInput.GradeInfo> gradeById = new LinkedHashMap<>();
for (ScheduleInput.GradeInfo g : in.grades()) { gradeById.put(g.id(), g); }

for (ScheduleInput.ClassInfo c : in.classes()) {
    ScheduleInput.GradeInfo g = gradeById.get(c.gradeId());
    if (g == null) { continue; }                       // ← 年级不存在的班被静默跳过
    int[] periods = new int[DAYS];
    int total = 0;
    for (int d = 0; d < DAYS; d++) {
        Integer v = g.periodsPerDay().get(DAY_KEYS[d]);
        periods[d] = (v == null) ? 6 : v;              // ← 缺 "Mon" 这类键 → 默认 6 节
        total += periods[d];
    }
    classData.put(c.id(), new ClassData(c.id(), periods, total));
}
```
**关键行**：
- `if (g == null) continue;`：⚠ **年级不存在 → 这个班被整个跳过**（不建 `ClassData`）。
  后果：后续 `ctx.classes()` 里没有它，**它不会出现在课表里**，而且**不报错**。
  这是"数据问题"而不是"排课无解"，外部有 `MISSING_ASSIGNMENT` 一类的静态诊断兜（[架构说明.md](架构说明.md) §5.7）。
  **这里的行为就是"静默跳过"**，读代码时要知道。
- `(v == null) ? 6 : v`：⚠ **默认 6 节是硬编码的**。为什么 6？只能说是与 Python 侧的默认一致
  （**此处未完全确认** Python 的默认值，代码里没有注释说明来源）。**改这个数会改变模型**。
- `total`：把整周节数加起来，见 §2.2 的说明。

**③ 任教关系扁平化（第 101–105 行）**
```java
for (ScheduleInput.Assignment a : in.assignments()) {
    int idx = allAssigns.size();
    allAssigns.add(a);
    classAssigns.computeIfAbsent(a.classId(), k -> new ArrayList<>()).add(idx);
}
```
**这是全模块的核心数据结构**：把任教关系**扁平成一个全局列表** `allAssigns`，
同时记录"第 i 条任教关系属于哪个班"。`classAssigns.get(classId)` 得到的是
**该班任教关系在全局列表里的下标列表**（`idx` 恰好等于它在列表里的位置）。

> **这个 `idx` 就是后面"时段决策变量的取值"**：变量取 `idx` 表示"这节课上这条任教关系"。
> 所以 `allAssigns` 的**顺序即变量的语义编码**，一旦顺序变，同一个模型数字含义就变了
> —— 这也是它必须 `ArrayList`（保序）的原因。

**④ 周课时（第 107–114 行）**
```java
for (ScheduleInput.GradeSubjectInfo gs : in.gradeSubjects()) {
    for (ScheduleInput.ClassInfo c : in.classes()) {
        if (c.gradeId() == gs.gradeId()) {
            classHours.computeIfAbsent(c.id(), k -> new LinkedHashMap<>())
                    .put(gs.subjectId(), gs.weeklyHours());
        }
    }
}
```
**逐行**：年级科目周课时**按年级复制到每个班**。这是一个**嵌套循环（O(年级科目 × 班级)）**，
在 160 班规模下也只是几万次，可接受；换成"先按年级分组再展开"会更快，但会多一层结构。
**内层是 `LinkedHashMap`**：⚠ 保序在这里也有意义 —— `SubjectWeeklyHoursHandler` 遍历它的
`entrySet()` 用哪种顺序加约束，会影响指纹。

**⑤ 该班涉及哪些科目（第 116–122 行）**
```java
for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
    Set<Long> sids = new TreeSet<>();
    for (int gi : classAssigns.getOrDefault(e.getKey(), List.of())) {
        sids.add(allAssigns.get(gi).subjectId());
    }
    classSubjects.put(e.getKey(), sids);
}
```
**逐行**：对每个班，从它的任教关系里收集**去重后的科目集合**。
`TreeSet`（而不是 `LinkedHashSet`）→ **按 subjectId 升序**。
为什么用 `TreeSet`：去重 + **确定的顺序**。为什么不用 `LinkedHashSet`（也是确定的顺序）？
两者在本项目里都能保证可复现；`TreeSet` 额外给了"从小到大"这个更容易复述的性质。
⚠ **注意遍历的外层是 `classData.entrySet()`**，所以"没有课的班"（`classData` 里不存在）
连一个空集合都不会有 —— 这是 ②里"年级不存在就跳过"的直接后果。

**⑥ 固定课展开（第 124–143 行）—— 唯一会抛异常的一段**
```java
for (ScheduleInput.FixedSlotRule rule : in.gradeFixedSlots()) {
    for (ScheduleInput.ClassInfo c : in.classes()) {
        if (c.gradeId() != rule.gradeId()) { continue; }
        List<ScheduleInput.Assignment> matches = new ArrayList<>();
        for (ScheduleInput.Assignment a : in.assignments()) {
            if (a.classId() == c.id() && a.subjectId() == rule.subjectId()) {
                matches.add(a);
            }
        }
        if (matches.size() != 1) {
            throw new ConstructionInfeasible(
                    "固定课展开失败：班级 " + c.id() + " 科目 " + rule.subjectId()
                            + " 的任教关系不是恰好一条（实际 " + matches.size() + "）");
        }
        fixed.put(new SlotKey(c.id(), rule.day(), rule.period()),
                new FixedEntry(rule.subjectId(), matches.get(0).teacherId()));
    }
}
```
**逐行**：
- 外层是**年级级规则**，内层遍历所有班并筛出同年级的 → **展开到每个班**（javadoc 说的就是这个）；
- 中段为 `(该班, 该科目)` 找任教关系。**要求恰好一条**：0 条说不清"固定课由谁上"，
  多条说不清"该用哪一位老师"。**两种都是数据配置问题**，所以**抛 `ConstructionInfeasible` 而不是交给求解器**。
- 抛出的文案会带上"实际 N"，便于定位。⚠ **这是本模块唯一在 `prepare()` 阶段抛出的检查**，
  它把"固定课配置错误"从"排课无解"里分了出来（Python 侧是压成空列表的）。
- `fixed` 的键是 `(classId, day, period)`：**固定课是"时段被占住"，覆盖该班该时段的任何决策**。
  ⚠ 如果两条固定课规则落在同一 `(班, 天, 节)`，`put` 会**后者覆盖前者而不报错** ——
  触发条件是"同一班同科目两条相同的固定课规则"或"不同科目落在同一节"。
  **此处未完全确认**是否会真的发生（多年级共用同一节的检查在外部静态诊断里，
  见 [架构说明.md](架构说明.md) §5.7 的 `FIXED_SLOT_*`）。

**⑦ 教师任教班级（第 145–147 行）**
```java
for (ScheduleInput.Assignment a : in.assignments()) {
    teacherClasses.computeIfAbsent(a.teacherId(), k -> new LinkedHashSet<>()).add(a.classId());
}
```
`teacherId → 该教师教的班集合`。用 `LinkedHashSet` 而不是 `HashSet`：
这个集合后面被 `RuleContext.freeTeacherSlots(teacherId)` 遍历，**顺序会影响变量创建顺序 → 影响指纹**。
⚠ 注意它**没有用 `classData` 里存在的班来过滤** —— 所以一个"年级缺失"的班也可能出现在这里。

#### 3.1.4 数据访问方法（第 150–208 行）

| 方法 | 逐行说明 |
|---|---|
| `CpModel model()` | 直接返回；`RuleContext.model()` 转发它 |
| `List<ClassData> classDataInOrder()` | **`new ArrayList<>(classData.values())`** —— ⚠ **每次调用都新建一个列表**。`RuleContext.classes()` 直接转发它，而 `TimetableExtractor` 与**每一个 handler** 都在最外层循环调用它。这是**每规则一次的 O(班数) 分配**，班数 160 时每次 160 个引用 —— 可忽略，但"每次新建"这个事实值得知道 |
| `ClassData classData(long)` | 查不到抛 `IllegalArgumentException("未知班级: " + classId)`。**不是** `ConstructionInfeasible`：调用方传错 ID 是**编程错误**，不是数据问题 |
| `Set<Long> allClassIds()` | `Collections.unmodifiableSet(classData.keySet())` —— **只读视图**，不是拷贝 |
| `List<Integer> assignIndicesOf(long)` | `classAssigns.getOrDefault(classId, List.of())`；**空班返回不可变空列表**（调用方只需判 `isEmpty`） |
| `Assignment assignmentAt(int)` | `allAssigns.get(globalIndex)` —— 注意参数名是 **globalIndex**（全局下标），与 `assignIndicesOf` 返回的**局部下标**不是一个东西（§3.1.5 里有 `li` vs 全局的对照） |
| `Map<Long,Integer> weeklyHoursOf(long)` | 查不到返回 `Map.of()` |
| `Set<Long> subjectsOf(long)` | 查不到返回 `Set.of()` |
| `Collection<TeacherInfo> teachersInOrder()` | 返回 `teachers.values()`（**活视图**，不是拷贝） |
| `TeacherInfo teacher(long)` | 查不到抛 `IllegalArgumentException("未知教师: " + teacherId)` |
| `boolean teacherTeachesClass(long, long)` | `set != null && set.contains(classId)`。这个朴素实现是 O(1) 的 hash 查询，被 `RuleContext` 的各处循环**高频调用**（每次"教师 × 天 × 节 × 班"），所以它是热路径 |
| `int lastPeriod(long, int day)` | `classData(classId).periods()[day]`。⚠ **方法名叫 `lastPeriod`，返回的却是"节数"**（不是"最后一节的下标"）：`periods[d] = 6` 时返回 6，而"最后一节"是 5。调用方一律写 `period < lastPeriod(...)`（当上界用）。**这个名字与语义的偏差是一个陷阱**，`RuleContext.periodsOn` 转发它时用的名字（periodsOn = "这天有几节"）才准确 |

#### 3.1.5 固定课访问（第 210–218 行）

```java
public boolean isFixed(long classId, int day, int period) {
    return fixed.containsKey(new SlotKey(classId, day, period));
}
public FixedEntry fixedAt(long classId, int day, int period) {
    return fixed.get(new SlotKey(classId, day, period));
}
```
**逐行**：就是把 `fixed` 这张表的两种问法暴露出去。每次调用都 `new SlotKey(...)` ——
record 的 `equals`/`hashCode` 自动生成，所以这个"临时键"是正确的；代价是每次一个短命对象。
`isFixed` 与 `fixedAt(...) != null` 语义相同，两者并存只是可读性考虑。

#### 3.1.6 三个变量访问器（第 220–294 行）—— 本类的核心

**`IntVar slotVar(long classId, int day, int period)`**

```java
SlotKey key = new SlotKey(classId, day, period);
if (fixed.containsKey(key)) { return null; }        // ① 固定课：没有决策变量
IntVar cached = slotVars.get(key);
if (cached != null) { return cached; }              // ② 缓存命中
List<Integer> indices = assignIndicesOf(classId);
if (indices.isEmpty()) { return null; }             // ③ 空班：没有决策变量
IntVar v = model.newIntVar(0, indices.size(),
        String.format("c%d_d%d_p%d", classId, day, period));
slotVars.put(key, v);
return v;
```

**逐行与数学含义**：
- **① 固定课返回 `null`**：固定课"已经定了"，不需要决策。**这是让所有 handler 都能用同一套写法**
  的关键：handler 拿到 `null` 就跳过这个时段。⚠ **不这么写会怎样**：每个 handler 都得自己
  `isFixed(...)` 判断一次，于是 10 条规则 × 39 处判断 —— Python 原实现就是这样，`RuleContext` 的 javadoc
  说它散落 **39 处**、极易漏。
- **② 缓存命中**：同一个 `(班,天,节)` 永远返回**同一个 `IntVar`**。这一条保证了"多个规则引用同一时段"
  不会各建一个变量。
- **③ 空班返回 `null`**：没有任教关系的班没有可选的课。⚠ 与 ②一样，它也是"用 `null` 表达不可用"
  （而不是抛异常）—— 因为"这个班这个时段没有决策"是完全正常的语义。
- **`model.newIntVar(0, indices.size(), ...)`**：**变量的定义域是 `0 .. n`（共 n+1 个取值）**，
  其中 `n = indices.size()`：

  | 取值 | 含义 |
  |---|---|
  | `0 .. n-1` | 上**第 `li` 条任教关系**（`li` 是该班任教关系列表的**局部**下标） |
  | `n` | **空堂哨兵**（这个时段不上课） |

  > ⚠ **上界是 `n` 而不是 `n-1`** —— 这一处"看似 off-by-one"是**有意的**：
  > 它多出来的那个值就是空堂。`TimetableExtractor` 靠 `localIdx >= indices.size()` 识别它（§3.2）。
  > **如果改成 `n-1`，课表会被迫上满每一节**：周课时之和小于座位总数时直接无解
  > （而"空堂"本身是正常现象，见 [PositiveFeasibilityTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/PositiveFeasibilityTest.java) 第 40–41 行的注释）。

- **变量名 `c%d_d%d_p%d`**（`c12_d3_p5`）：⚠ **变量名是模型指纹的一部分**（指纹是整个
  `CpModelProto` 序列化后的摘要），所以这里的格式**不能随手改**。
  名字里带 `c/d/p` 而不是 `class/day/period`，是为了与 Python 侧的名字**逐字符一致**。

**`BoolVar subjectAt(long classId, int day, int period, long subjectId)`**

```java
SubjectSlotKey key = new SubjectSlotKey(classId, day, period, subjectId);
BoolVar cached = subjectSlot.get(key);
if (cached != null) { return cached; }                  // ① 缓存
IntVar v = slotVar(classId, day, period);
if (v == null) { return null; }                         // ② 固定课/空班 → 无此布尔
List<Integer> indices = assignIndicesOf(classId);
List<Integer> match = new ArrayList<>();
for (int li = 0; li < indices.size(); li++) {
    if (assignmentAt(indices.get(li)).subjectId() == subjectId) { match.add(li); }
}
if (match.isEmpty()) { return null; }                   // ③ 该班没这个科目
BoolVar b = membership(v, match,
        String.format("ss_%d_%d_%d_%d", classId, day, period, subjectId));
subjectSlot.put(key, b);
return b;
```

**逐行**：
- **①** 缓存键含 `subjectId`。
- **② 委托给 `slotVar`**：所以"固定课"这个判断**只写了一次**（在 `slotVar` 里）。
- **中段的双下标**：`indices` 是该班的任教关系（**全局下标**），`li` 是它在**该班列表里的位置**。
  `match` 收集的是**局部下标** `li`，因为变量 `v` 的取值域就是按局部下标排的（§3.1.3 ③）。
  ⚠ **这里是最容易写错的地方**：`assignmentAt(indices.get(li))` 里，
  `indices.get(li)` 是全局下标、`li` 是局部下标，**两者都叫"下标"**。写反了不会编译错，
  只会让约束默默指向错误的任教关系。
- **③ `match.isEmpty()` 返回 `null`**：该班没有这个科目 → "这个时段是这门课"这个命题**无意义**。
  用 `null` 而不是"恒假布尔"，是为了**让 handler 少加约束**（一个恒假布尔会让模型里多出无效变量与约束，
  进而改变指纹）。
- **`membership(...)`**：见下。

**`BoolVar teacherAt(long teacherId, long classId, int day, int period)`** ——
与 `subjectAt` **结构完全相同**，只是筛选条件是 `a.teacherId() == teacherId`，
变量名前缀是 `tc_t%d_c%d_d%d_p%d`（`tc` = teacher-class）。

**`private BoolVar membership(IntVar v, List<Integer> matchIndices, String prefix)`**

```java
BoolVar b = model.newBoolVar(prefix);
List<Literal> conditions = new ArrayList<>(matchIndices.size());
for (int li : matchIndices) {
    BoolVar eq = model.newBoolVar(prefix + "_eq" + li);
    model.addEquality(v, li).onlyEnforceIf(eq);
    model.addDifferent(v, li).onlyEnforceIf(eq.not());
    conditions.add(eq);
}
model.addBoolOr(conditions).onlyEnforceIf(b);
List<Literal> negated = new ArrayList<>(conditions.size());
for (Literal c : conditions) { negated.add(c.not()); }
model.addBoolAnd(negated).onlyEnforceIf(b.not());
return b;
```

**这是本模块唯一的"整数 → 布尔"编码原语**，数学上要表达的是
**`b ⟺ (v ∈ matchIndices)`**（双向等价，而不是单向蕴含）。逐行：

1. **`b = newBoolVar(prefix)`**：要建的布尔。
2. **对每个 `li ∈ matchIndices`，建一个 `eq_li`**，并用**两条条件约束**把它钉成
   "`eq_li` 为真 ⟺ `v == li`"：
   - `model.addEquality(v, li).onlyEnforceIf(eq)` → **`eq` ⇒ `v = li`**
   - `model.addDifferent(v, li).onlyEnforceIf(eq.not())` → **`¬eq` ⇒ `v ≠ li`**
   合起来即等价。⚠ **为什么必须两条**：只写第一条的话，`v ≠ li` 时 `eq` 可以随便取真或假，
   CP-SAT 会**为了满足约束挑一个方便的值**，于是 `b` 的含义就不确定了 —— 这正是"模型看起来对、解出来不对"的典型来源。
   > 📌 顺带一个 API 陷阱（[踩坑总结.md](踩坑总结.md) E1）：Java 绑定里**没有 `addNotEqual`**，
   > 不等是 **`addDifferent`**。凭直觉写会编译不过。
3. **`model.addBoolOr(conditions).onlyEnforceIf(b)`** → **`b` ⇒ 至少一个 `eq` 为真**，
   即 `b ⇒ v ∈ matchIndices`。
4. **`model.addBoolAnd(negated).onlyEnforceIf(b.not())`** → **`¬b` ⇒ 所有 `eq` 为假**，
   即 `¬b ⇒ v ∉ matchIndices`。

**3 + 4 合起来就是 `b ⟺ (v ∈ matchIndices)`。** 
⚠ **注意 3 与 4 不是同一件事的两次表述**：3 是"有一个命中"，4 是"一个都不命中"。
只写 3 会得到一个**单向**编码（`b` 可以在命中时仍取假），于是"软约束的惩罚可以靠把 `b` 置 0 来逃避"
—— 那会让软约束**完全失效但不报错**。

**变量命名规则汇总**（都进指纹，不能乱改）：

| 变量 | 名字模板 | 数量级 |
|---|---|---|
| 时段决策 | `c{classId}_d{day}_p{period}` | 每班每节一个 |
| 科目成员 | `ss_{classId}_{day}_{period}_{subjectId}` | 懒创建 |
| 教师成员 | `tc_t{teacherId}_c{classId}_d{day}_p{period}` | 懒创建 |
| 整数等值辅助 | 上面两个 + `_eq{li}` | 每个 `match` 一项 |
| 其它规则自己的辅助 | `day_exc_*` / `cons_*` / `tday_exc_*` / `tg_*_busy_*` / `tg_*_bf_*` / `tg_*_af_*` / `tg_*_hole_*` | 见各 handler |

#### 3.1.7 为什么"懒创建 + 缓存"值 28% 的变量

javadoc 第 24–27 行说明了理由，实测数据在 [GoldenModelTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/GoldenModelTest.java)
的第三个测试里：

```
忠实移植（Python 的做法，教师冲突与教师周上限各建一套）= 13,452 变量
规则系统（共用同一批教师成员布尔）           =  9,676 变量
消除 = 3,776 = 28.1%
```

**机制**：`TeacherNoConflictHandler` 调 `ctx.freeTeacherSlots(teacherId, day, period)`，
`TeacherWeeklyLimitHandler` 调 `ctx.freeTeacherSlots(teacherId)` —— 两个**不同签名**的方法，
最终都经过 `teacherAt(teacherId, classId, day, period)` → `teacherSlot` 缓存。
**同一批变量被两条规则共用**，而不是各建一套。

> ⚠ **这条优化能成立，前提是缓存是"真相的唯一来源"**。
> 任何规则如果自己 `model.newBoolVar(...)` 造一个"教师是否在此班"的布尔，就等于**又建了一套**，
> 变量数会涨回去、指纹会变、GoldenModelTest 会红。**新增 handler 必须用 `ctx.*` 拿变量。**

### 3.2 `TimetableExtractor` —— 从解里还原课表（61 行）

```java
private static final int DAYS = 5;

public static List<TimetableEntry> extract(CpSolver solver, SlotVariableIndex index) {
    List<TimetableEntry> out = new ArrayList<>();
    for (ClassData cd : index.classDataInOrder()) {          // ① 按班（保序）
        long classId = cd.classId();
        List<Integer> indices = index.assignIndicesOf(classId);
        if (indices.isEmpty()) { continue; }                  // ② 空班跳过
        for (int day = 0; day < DAYS; day++) {                // ③ 每天
            for (int period = 0; period < cd.periods()[day]; period++) {   // ④ 每节
                long subjectId;
                long teacherId;
                SlotVariableIndex.FixedEntry fixedEntry = index.fixedAt(classId, day, period);
                if (fixedEntry != null) {                     // ⑤ 固定课：直接取，不问求解器
                    subjectId = fixedEntry.subjectId();
                    teacherId = fixedEntry.teacherId();
                } else {
                    IntVar v = index.slotVar(classId, day, period);
                    if (v == null) { continue; }               // ⑥ 理论上不会到这（②已排除空班）
                    long localIdx = solver.value(v);
                    if (localIdx >= indices.size()) { continue; }   // ⑦ 空堂哨兵
                    ScheduleInput.Assignment a = index.assignmentAt(indices.get((int) localIdx));
                    subjectId = a.subjectId();
                    teacherId = a.teacherId();
                }
                out.add(new TimetableEntry(classId, subjectId, teacherId, day, period));
            }
        }
    }
    return out;
}
```

**逐行**：
- **①/③/④ 三层循环的顺序（班 → 天 → 节）**决定了**输出列表的顺序**。
  上层（scheduling 落库、契约比对）如果依赖"课表条目的顺序"，就依赖这里。
- **② 空班跳过**：没有任教关系的班不产生任何条目。
- **⑤ 固定课优先**：⚠ **固定课不通过变量取**。因为 `slotVar` 对固定课返回 `null`（§3.1.6）。
  这也保证了"固定课一定出现在课表里" —— 它不需要求解器去选。
- **⑦ 空堂哨兵**：`localIdx == indices.size()` 时 `continue` —— **这个时段不产出条目**。
  所以输出的条目数**可以少于"班 × 节"的总数**，空堂就是"没有行"。
  > ⚠ **不要用 `entries.size()` 判断课表是否完整**（[PositiveFeasibilityTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/PositiveFeasibilityTest.java) 第 40–41 行专门写了这条），
  > 要核对的是"各科节数是否等于周课时"（`TestFixtures.checkInvariants`）。
- **`(int) localIdx`**：`solver.value(IntVar)` 返回 `long`，转 `int` 是为了当列表下标。
  域上界是班级任教关系条数（几十），**不可能溢出**。

### 3.3 `SolveOutcome` —— 求解结果（31 行）

```java
public record SolveOutcome(
        CpSolverStatus status,
        List<TimetableEntry> entries,
        String modelStats,
        String modelFingerprint,
        double wallTimeSeconds,
        long objectiveValue,
        long bestObjectiveBound) {

    public boolean hasSolution() {
        return status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
    }
    public boolean timedOut() {
        return status == CpSolverStatus.UNKNOWN;
    }
}
```

**为什么带 `status`**（javadoc 第 11–13 行）：Python 把所有非 OPTIMAL/FEASIBLE 压成空列表，
上层只能笼统报一句"排课无解"。带上 `status` 之后，上层能区分：
- `INFEASIBLE` = **证明了**无解 → 让用户去改数据；
- `UNKNOWN` = 到时限还没证明 → **不该让用户改数据**，延长时间就行。

`orbit-solver-worker` 正是靠这个区分**重试策略**：
`boolean retryable = outcome.timedOut();` —— 超时可重试、证明无解不可重试
（[JobRunner.java](../../backend-java/orbit-solver-worker/src/main/java/com/orbit/solver/worker/JobRunner.java) 第 92 行）。

| 成员 | 逐行说明 |
|---|---|
| `modelStats` | 编译时算好的 `model.modelStats()` 多行文本，人类可读，也用于诊断落库 |
| `modelFingerprint` | 编译时算好的指纹（`ModelFingerprints.ofStats`） |
| `wallTimeSeconds` | `solver.wallTime()`，**double**；⚠ 与 `objectiveValue` 的"强制转整数"相反，墙钟时间**保留小数**（它就是给日志/监控看的） |
| `objectiveValue` | `(long) solver.objectiveValue()`，见 §1.3 的强转说明 |
| `bestObjectiveBound` | 最优目标的下界（证明最优性的进度）。⚠ Python 侧把这**两个值一起**用于"等价性判定"（[架构说明.md](架构说明.md) §6 ②）：比"解"本身更稳定 |
| `hasSolution()` | 与 `OrbitSolver.solve` 里的三元判断**同一条件**（⚠ **两处重复**：改一处必须改另一处） |
| `timedOut()` | 只有 `UNKNOWN` 算超时。⚠ `MODEL_INVALID` / `INFEASIBLE` **都不是**超时 |

> ⚠ `entries` 在本记录里**没有做防御性拷贝**（对比 `PageResponse` 在 common 里有 `List.copyOf`）。
> 求解路径上它总是 `TimetableExtractor` 新建的 `ArrayList`，但**测试/上层拿到后是可变的**。
> 此处**行为就是"直接持有"**，读代码时不要假设它不可变。

### 3.4 `SolverSettings` —— 求解参数（10 行）

```java
public record SolverSettings(double maxTimeInSeconds, int numSearchWorkers, boolean logSearchProgress) {
    /** 与现有 Python 生产配置一致（routers/schedule.py 传 time_limit=60）。 */
    public static SolverSettings production() {
        return new SolverSettings(60.0, 2, false);
    }
}
```

**逐行**：三个字段直接对应 `CpSolver` 的三个参数。
- `maxTimeInSeconds = 60.0`：对齐 Python 的 `time_limit=60`。
- `numSearchWorkers = 2`：并行搜索线程数。⚠ 注意它**不是 CPU 核数**，而是一个刻意的保守值
  （同进程还有别的服务，且 P3 起求解在独立进程里也受 `WorkerProperties` 控制）。
- `logSearchProgress = false`：生产不打搜索日志（CP-SAT 的搜索日志非常大）。
  调成 `true` 只在排查"为什么解不出来"时用。

⚠ **`production()` 与本模块的测试用值不同**：测试里常用 `new SolverSettings(20, 1, false)`
（[TestFixtures.quickSolve](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/TestFixtures.java)）
与 `new SolverSettings(0.001, 1, false)`（专门制造 `UNKNOWN`）。
**`production()` 在本模块内没有被调用** —— 真正的生产路径是
`TimetableGenerationService` / `JobRunner` **各自**从配置（`time_limit=60`、`SCHEDULE_SOLVER_WORKERS`）构造
`SolverSettings`，以便这两处能独立调参。所以 `production()` 目前是"约定值的记录"，**此处未完全确认**是否还有其他调用方。

### 3.5 `SolverNatives` —— 原生库加载（25 行）

```java
private static boolean loaded;

public static synchronized void load() {
    if (!loaded) {
        Loader.loadNativeLibraries();
        loaded = true;
    }
}
```

**逐行**：
- `synchronized` + `loaded` 标志：**双重保证只加载一次**。为什么需要同步？P3 起 worker 会用
  线程池并发跑多个任务，多个线程可能同时首次进入 `OrbitSolver.compile`。
  ⚠ **`synchronized` 加在方法上会让每次 `load()` 都取一次锁**（即使已加载）——
  代价极小（一次无竞争 monitor 进入），且 `compile` 本身是重量级操作，不值得优化掉。
- `Loader.loadNativeLibraries()`：OR-Tools 的 JNI 入口，把 `ortools-<platform>` 里的原生库
  解包到临时目录并加载。

> ⚠ **实测（[踩坑总结.md](踩坑总结.md) E3、[Java建模等价性验证报告.md](Java建模等价性验证报告.md) §5）**：
> **JDK 23 上 `CpSolver.solve()` 段错误**（`EXCEPTION_ACCESS_VIOLATION` at `msvcp140.dll`），
> 而**建模、指纹、`model.validate()` 全部正常** —— 于是很容易误判成"模型写错了"。
> 根因**不是**缺 MSVC 可再发行组件（实测本机 redist 14.51 比 OR-Tools 构建时用的还新）。
> **换 JDK 21 LTS 后完全正常**，项目因此统一 JDK 21。
> **排查建议**：遇到 JNI 段错误，**换 JDK 试是最便宜的手段之一**。

### 3.6 `ModelFingerprints` —— 从统计文本里抠指纹（35 行）

```java
private static final Pattern PATTERN =
        Pattern.compile("model_fingerprint:\\s*(0x[0-9a-fA-F]+)");

public static String of(CpModel model) {
    return ofStats(model.modelStats());
}
public static String ofStats(String modelStats) {
    Matcher m = PATTERN.matcher(modelStats == null ? "" : modelStats);
    return m.find() ? m.group(1) : "(未找到)";
}
```

**逐行**：
- **正则**：从多行统计文本里抓 `model_fingerprint: 0x...`。`\s*` 容忍冒号后的空格数变化
  （不同 OR-Tools 版本的排版可能不同）；`0x[0-9a-fA-F]+` 不做位数限制。
- **`modelStats == null ? "" : modelStats`**：null 安全。⚠ **为什么需要它**：`ofStats` 也被
  `RuleCompiler` 用（`ModelFingerprints.ofStats(stats)`），而 `stats` 来自 `model.modelStats()`，
  正常不为 null；这个判空是**防御性的**（宁可返回 `"(未找到)"` 也不要 NPE）。
- **找不到时返回字符串 `"(未找到)"` 而不是 null/抛异常**：⚠ **这是一个刻意的取舍**：
  指纹是**观测/回归**用途，不是控制流依据。返回一个显眼的字串让断言失败时能一眼看出
  "不是指纹不同，是压根没抓到"。**但它也意味着"改坏了不会崩"** —— 依赖指纹的测试
  （`GoldenModelTest`）必须比对**具体值**，只断言"非空"是测不出问题的。

> ⚠ **指纹的正确用途**（[踩坑总结.md](踩坑总结.md) E2、[Java建模等价性验证报告.md](Java建模等价性验证报告.md) §4.2）：
> **不能**当跨语言等价判据 —— 它是对整个 `CpModelProto` 序列化的摘要，
> 对**绑定特有的记账字段**敏感。已实测的唯一差异是 `objective.scaling_factor`：
> Python 的 `minimize()` 会写 `1.0`，Java 的 `minimize()` 留空（`0.0`），**语义相同、指纹不同**
> （`0x735a03bb2eff53a7` vs 对齐后的 `0x874e810d17f32a8b`）。
> 正确做法是**逐字段比对 proto**；指纹只当**同一实现内的回归护栏**。

### 3.7 `ConstructionInfeasible` —— 建模期就发现"不可能"（17 行）

```java
public class ConstructionInfeasible extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ConstructionInfeasible(String message) { super(message); }
}
```

**逐行**：就是一个带 `serialVersionUID` 的 `RuntimeException`。继承 `RuntimeException`
而不是受检异常：它在 handler 深处被抛出，强制 `throws` 会污染整条调用链。

**它在哪被抛**（实测，共 6 处）：

| 抛出点 | 触发条件 |
|---|---|
| `SlotVariableIndex.prepare()` | 固定课展开时 `(班,科目)` 的任教关系不是恰好 1 条 |
| `TeacherNoConflictHandler` | 同一教师同一时段被**固定课**占了 > 1 次（固定课之间就冲突） |
| `SubjectMaxPerDayHandler`（硬） | 该班该天该科的**固定课数**已超过 `max` |
| `TeacherMaxDailyPeriodsHandler`（硬） | 该教师当天的**固定课数**已超过 `max` |
| `TeacherUnavailableHandler` | ① 规则引用了**不存在的教师**；② 该时段**既有固定课又被设为不可用** |
| `SubjectAvoidLastPeriodHandler`（硬） | 某天最后一节被**固定**成了该科目 |
| `MainSubjectMorningHandler`（硬） | 某节被**固定**成了该科目且落在下午 |

**共性**：**全部是"配置自相矛盾"，不是"排课难度"**。交给求解器只会得到一个
`INFEASIBLE`，用户看不懂原因；抛异常能把**具体哪条配置打架**说清楚。
⚠ **注意：这些异常会从 `OrbitSolver.compile` 冒出去**，调用方（scheduling 的
`TimetableGenerationService` / worker 的 `JobRunner`）需要把它当成"配置错误"而不是"求解失败"来处理
—— `JobRunner` 的 catch 会把异常标成**可重试**（第 105–109 行），
**这一点与"配置错误不该重试"的直觉相反**，属于已记录的行为。

---

## 4. `rule` —— 规则系统的 API（7 个类，688 行）

### 4.1 `ConstraintRule` —— 一条生效的规则（36 行）

```java
public record ConstraintRule(
        String type, RuleParams params, Hardness hardness, int weight,
        boolean enabled, String description) {

    public enum Hardness { HARD, SOFT }

    public static ConstraintRule hard(String type, RuleParams params, String description) {
        return new ConstraintRule(type, params, Hardness.HARD, 1, true, description);
    }
    public static ConstraintRule soft(String type, RuleParams params, int weight, String description) {
        return new ConstraintRule(type, params, Hardness.SOFT, weight, true, description);
    }
    public boolean isSoft() { return hardness == Hardness.SOFT; }
    public int effectiveWeight() { return weight <= 0 ? 1 : weight; }
}
```

**它对应 `constraint_rules` 表里的一行**（javadoc）。逐行：
- **`type`**：稳定字符串，与 `RuleHandler.type()` 对应（如 `"subject_max_per_day"`）。
- **`params`**：来自数据库的 `params` JSON，形状是 `Map<String,Object>`。
- **`hardness`**：枚举而不是 `boolean`，**给数据库列和 JSON 用**（`"HARD"`/`"SOFT"` 比 `true/false` 自解释）。
- **`weight`**：软约束权重。硬约束的构造器**固定写 1** ——因为硬约束不参与目标函数，权重无意义。
- **`enabled`**：两个工厂都写 `true`。**为什么还要有 `enabled` 字段**：它对应表里的 `enabled` 列，
  由 scheduling 映射进来；`RuleCompiler` 会跳过 `false` 的（§5.1 ②）。
  ⚠ **但两个工厂方法都无法造出 `enabled=false` 的实例** —— 需要它的调用方得用主构造器。
  **此处未完全确认** scheduling 侧是否真的会传 `false` 进来（也可能是先过滤再映射）。
- **`isSoft()`**：唯一一处判断硬软的地方（`RuleCompiler` 用它算 `!rule.isSoft()` 得到 `hard` 参数）。
- **`effectiveWeight()`**：⚠ **`weight <= 0 → 1`**。为什么要有这个兜底：数据库里可能是 `0` 或负数
  （人工改库、老数据）。**权重为 0 会让软约束"免费"**（惩罚项系数 0 ⇒ 目标函数里等于没有），
  即"规则配了但完全不起作用、还不报错"。兜底成 1 是**刻意的保守选择**：
  宁可让它按 1 生效，也不要静默失效。

### 4.2 `RuleHandler` —— 规则的实现接口（46 行）

```java
public interface RuleHandler {
    String type();
    String displayName();
    List<RuleParamSpec> params();
    default boolean mandatory() { return false; }
    default String describe(RuleParams params) { return displayName(); }
    void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties);
}
```

**逐行**：

| 成员 | 谁调它 | 说明 |
|---|---|---|
| `type()` | `RuleRegistry` 构造/查找、`RuleCompiler` | **会写进数据库的 `rule_type` 列**，所以是**长期契约**，改名等于迁移数据 |
| `displayName()` | 前端、`RuleTypeCatalog` | 面向用户的中文名 |
| `params()` | `RuleCompiler`（校验）、前端表单、助手工具定义 | **一份 schema 三处消费** |
| `mandatory()` | `RuleRegistry.mandatory()/configurable()`、`RuleCompiler` | **默认 `false`**：只有明确表达"这是不变量"的才返回 `true` |
| `describe(params)` | 前端规则列表 | **默认返回 `displayName()`**，需要展示参数值的规则才覆盖它（如 `SubjectMaxPerDayHandler`） |
| `apply(...)` | `RuleCompiler` | **唯一的抽象方法**：把这条规则编译进模型 |

**`apply` 的参数语义**（javadoc 第 40–44 行，这是最容易误用的地方）：
- `hard == true` → 加**约束**（`addLessOrEqual` / `addEquality`），**且不应该碰 `penalties`**；
- `hard == false` → 加**惩罚项**（`penalties.add(...)`），**不应该加硬约束**。
- 同一个 handler 必须两种都能干（`subject_max_per_day` 就是），
  这样"同一条规则既能当硬约束也能当软约束"才成立（[架构说明.md](架构说明.md) §5.2 好处 2）。

⚠ **`mandatory()` 的含义**（javadoc 第 14–17 行）：它表达的是**不变量**——
"课表违反了就是错的"，**不可被规则集关闭**，也**不接受参数**。
所以 `RuleCompiler` 对 mandatory 的 handler 一律传 `RuleParams.empty()`（§5.1 ①）。
数据驱动的那两条（周课时、教师周上限）也走这一档，**参数直接从快照读**。

### 4.3 `RuleParamSpec` —— 单个参数的 Schema（43 行）

```java
public record RuleParamSpec(
        String name, ParamType type, boolean required,
        Long min, Long max, List<String> enumValues, String label) {

    public enum ParamType { INT, LONG, BOOL, STRING, ENUM }

    public static RuleParamSpec intParam(String name, String label, long min, long max) {
        return new RuleParamSpec(name, ParamType.INT, true, min, max, List.of(), label);
    }
    public static RuleParamSpec optionalInt(String name, String label, long min, long max) {
        return new RuleParamSpec(name, ParamType.INT, false, min, max, List.of(), label);
    }
    public static RuleParamSpec enumParam(String name, String label, List<String> values) {
        return new RuleParamSpec(name, ParamType.ENUM, true, null, null, List.copyOf(values), label);
    }
    public static RuleParamSpec boolParam(String name, String label) {
        return new RuleParamSpec(name, ParamType.BOOL, false, null, null, List.of(), label);
    }
}
```

**逐行**：
- **`min`/`max` 是包装类型 `Long`**：只有数值型参数才有边界，枚举/布尔/字符串用 `null`。
  ⚠ **用 `null` 而不是"哨兵值"**（如 `Long.MIN_VALUE`）是刻意的：`RuleParams.validate` 里
  写的是 `if (spec.min() != null && n < spec.min())` ——**判空即"没有这个边界"**，
  不存在"边界恰好是 MIN_VALUE"的歧义。
- **`required` 的默认值不统一**，这是**刻意的**：
  - `intParam` → `required = true`（必填数值）
  - `optionalInt` → `false`（**这就是"留空表示所有科目/所有教师"的实现方式**）
  - `enumParam` → `true`
  - `boolParam` → **`false`**（⚠ 布尔参数"缺失"与"显式 false"语义不同，
    但工厂把它们做成"缺失时由 handler 用 `boolOr(name, fallback)` 给默认值"）
  - ⚠ **没有 `stringParam` 工厂** —— `ParamType.STRING` 在 `validate` 里有分支，
    但**没有任何内置规则声明字符串参数**。`此处未完全确认` 是否有计划中的规则要用。
- **`enumParam` 用 `List.copyOf(values)`**：防御性拷贝（不可变 + 拒绝 null 元素）。
  ⚠ **其他三个工厂用 `List.of()`（也是不可变的）**，一致性没问题。
- **`label` 是中文**：直接给前端表单当字段标签用（"一天最多几节"）。

### 4.4 `RuleParams` —— 参数值与校验（130 行）

```java
public record RuleParams(Map<String, Object> values) {

    public RuleParams {
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
    }
    public static RuleParams empty() { return new RuleParams(Map.of()); }
    public static RuleParams of(Map<String, Object> values) { return new RuleParams(values); }
```

**逐行（紧凑构造器）**：`null` → `Map.of()`；否则 **`new LinkedHashMap<>(values)` 拷贝一份**。
- **为什么要拷贝**：调用方可能传进一个之后还会被改的 Map（如从 Jackson 解析后又被复用的对象）。
  拷贝让 `RuleParams` 真正不可变（record 组件本身是 final 的，但**它指向的 Map 不一定**）。
  ⚠ **没有做 `Map.copyOf`**：因为 `Map.copyOf` 会**拒绝 null 值**，而 JSON 里 `{"max": null}`
  是可能出现的，这里选择容忍（`has(name)` 判的就是"值是否为 null"）。
- **为什么保序（LinkedHashMap）**：`RuleParams` 会参与 `rule_set_hash` 的计算
  （[架构说明.md](架构说明.md) §5.6："`<params 的键排序 JSON>`"）—— 那边会排序，
  但保序仍然让调试输出稳定。

#### 取值方法（"宽容"）

| 方法 | 逐行说明 |
|---|---|
| `boolean has(String name)` | `values.get(name) != null`。⚠ **"存在但值为 null" 视为不存在** —— 这正是上面不拷贝成 `Map.copyOf` 的原因 |
| `long requireLong(String name)` | ① 缺失 → `IllegalArgumentException("规则参数缺失: " + name)`；② `instanceof Number` → `n.longValue()`（⚠ 一条分支同时接住 `Integer`/`Long`/`Double`）；③ 否则当字符串 `Long.parseLong(trim())`，失败抛 `IllegalArgumentException("规则参数 X 不是整数: v")` |
| `long longOr(String, long)` | `has ? requireLong : fallback` |
| `int requireInt(String)` | `Math.toIntExact(requireLong(...))` —— ⚠ **用 `toIntExact` 而不是强转**：超 `int` 范围会抛 `ArithmeticException` 而不是**静默截断**（截断会让"max=4294967297"变成"max=1"，一个**看似合理的错值**，极难发现） |
| `int intOr(String, int)` | 同上带默认值 |
| `boolean boolOr(String, boolean)` | null → fallback；`Boolean` → 原值；否则 `Boolean.parseBoolean(trim())`。⚠ **`Boolean.parseBoolean` 对任何非 `"true"` 的字符串都返回 `false`** —— 所以 `"yes"`、`"1"`、`"是"` 全部变成 `false`，**不报错**。这种"静默取假"由 `validate` 拦住（见下 BOOL 分支） |
| `String stringOr(String, String)` | null → fallback；否则 `String.valueOf(v)` |

**"取值宽容、校验严格"的分工**（javadoc 第 11–12 行）：

- **取值**阶段要容忍 JSON 反序列化的类型漂移：`1` 可能是 `Integer`、`1L` 可能是 `Long`、
  `"1"` 可能是 `String`（前端表单经常把数字当字符串传）。所以 `requireLong` 三条分支都接。
- **校验**阶段必须严格 —— 因为**这是写进数据库之前的最后一道闸**。

#### `List<String> validate(List<RuleParamSpec> specs)`

```java
public List<String> validate(List<RuleParamSpec> specs) {
    List<String> errors = new ArrayList<>();
    for (RuleParamSpec spec : specs) {
        if (spec.required() && !has(spec.name())) {
            errors.add("缺少必填参数 " + spec.name() + "（" + spec.label() + "）");
            continue;                                     // ← 缺必填就跳到下一个参数
        }
        if (!has(spec.name())) { continue; }              // 可选参数缺失 = 通过
        Object raw = values.get(spec.name());
        switch (spec.type()) {
            case INT, LONG -> {
                if (!(raw instanceof Number)) {
                    try { Long.parseLong(String.valueOf(raw).trim()); }
                    catch (NumberFormatException e) {
                        errors.add("参数 " + spec.name() + " 必须是整数，实际为 " + raw);
                        continue;
                    }
                }
                long n = requireLong(spec.name());
                if (spec.min() != null && n < spec.min()) {
                    errors.add("参数 " + spec.name() + " 不能小于 " + spec.min() + "，实际为 " + n);
                }
                if (spec.max() != null && n > spec.max()) {
                    errors.add("参数 " + spec.name() + " 不能大于 " + spec.max() + "，实际为 " + n);
                }
            }
            case ENUM -> {
                String s = String.valueOf(raw);
                if (!spec.enumValues().contains(s)) {
                    errors.add("参数 " + spec.name() + " 取值必须是 " + spec.enumValues()
                            + " 之一，实际为 " + s);
                }
            }
            case BOOL -> {
                if (!(raw instanceof Boolean)
                        && !"true".equalsIgnoreCase(String.valueOf(raw))
                        && !"false".equalsIgnoreCase(String.valueOf(raw))) {
                    errors.add("参数 " + spec.name() + " 必须是布尔值，实际为 " + raw);
                }
            }
            case STRING -> { /* 任意字符串均可 */ }
        }
    }
    return errors;
}
```

**逐行与"为什么这么写"**：
- **返回 `List<String>` 而不是抛异常**：一次把**所有**问题报给用户。
  工程上很重要：用户改一次表单，不该只被告知"少了 max"，改完再被告知"max 越界"。
- **`continue` 而不是 `break`**：跳过的只是**当前参数**，继续检查后面的。
- **`INT` 与 `LONG` 走同一条分支**：⚠ 两者的**校验逻辑相同**（范围检查用 `long`），
  真正的区别只在"上层怎么用"（`requireInt` vs `requireLong`）。
- **数字型先判类型再解析**：
  `raw instanceof Number` 为真 → 跳过解析（**注意：`Double` 也是 `Number`**，
  所以 `{"max": 1.9}` 会通过类型检查，然后 `requireLong` 的 `n.longValue()` **静默截断成 1**。
  ⚠ **这是一个已知的宽松点**：JSON 里传浮点会被悄悄取整，**不报错**。
  此处**行为就是截断**，未做额外防御）。
  非 `Number` → 试 `Long.parseLong`（接受 `"3"`，拒绝 `"abc"`）。
- **越界文案**："不能小于"/"不能大于" —— ⚠ **这两句被测试断言精确匹配**
  （[RuleParamsValidationTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/RuleParamsValidationTest.java) 第 49、52 行），
  **改文案会让测试红**。而且这两句会通过 `RuleCompiler` 的异常**暴露给用户**（§5.1），
  所以它们是**面向用户的文案**，属于行为契约。
- **`ENUM` 分支不做类型判断**：一律 `String.valueOf(raw)` 再比。
  ⚠ 这意味着 JSON 里的**数字** `3` 会被变成 `"3"` 去比 —— 如果枚举值是字符串 `"3"` 就会通过。
- **`BOOL` 的字符串判定用 `equalsIgnoreCase`**：接受 `"TRUE"` / `"false"`；
  **拒绝** `"yes"`/`"1"`（测试第 86 行断言了 `"yes"` 被拒）。
- **`STRING` 分支空实现**：任意字符串。⚠ 有 `ParamType.STRING` 但没有 `stringParam` 工厂（§4.3），
  所以这个分支**在实际内置规则里走不到**。

### 4.5 `RuleRegistry` —— `type → handler` 映射（68 行）

```java
private final Map<String, RuleHandler> byType = new LinkedHashMap<>();

public RuleRegistry(List<RuleHandler> handlers) {
    for (RuleHandler h : handlers) {
        RuleHandler previous = byType.put(h.type(), h);
        if (previous != null) {
            throw new IllegalStateException("规则类型重复注册: " + h.type());
        }
    }
}
```

**逐行**：
- **`LinkedHashMap`**：**注册顺序即变量创建顺序**（javadoc 第 16 行）。
  `RuleCompiler` 遍历 `registry.mandatory()`（按注册顺序），
  所以 `BuiltinRules` 里那 10 行的**顺序会影响模型指纹**。
- **检测重复注册并抛 `IllegalStateException`**：⚠ **用 `put` 的返回值检测**，
  而不是"先 `containsKey` 再 `put`" —— 一次操作同时完成"插入"和"发现旧值"，
  没有 check-then-act 的竞态，也不会写两遍。
  **为什么必须抛**：`put` 会**静默覆盖**。两个 handler 用同一个 `type` 时，
  前者会被悄悄丢掉，表现为"这条规则没生效" —— 而**注册表是静态装配**，
  这种错误应该在**启动/首次使用时**就炸掉，而不是等到某个学校的课表排错。
- **`previous != null` 的语义**：⚠ 注意它**不能区分"没插过"与"插过但值是 null"**。
  这里不可能有 null 值（handler 非 null），所以安全。

| 方法 | 逐行说明 |
|---|---|
| `get(String type)` | 找不到抛 `IllegalArgumentException`，**文案里带上「已注册: [所有 type]」** —— 排查时直接能看到可选值，不用去翻代码 |
| `contains(String)` | `byType.containsKey` |
| `all()` | `byType.values()`（**活视图**，注册表构造后不再改，所以安全） |
| `mandatory()` | 遍历筛 `h.mandatory()`，**新建 `ArrayList`** |
| `configurable()` | 筛 `!h.mandatory()` |

**为什么"有哪些内置规则"不在这里**（javadoc 第 12–14 行）：
如果 `RuleRegistry` 自己 `new` 那些 handler，就会和 `rule.handler` 包形成**双向依赖**
（注册表 new 实现、实现 implements 接口）。所以装配被推到
[`compile.BuiltinRules`](#41-builtinrules--内置规则的装配点51-行)。

> 这也解释了 [架构说明.md](架构说明.md) §6⑦ 那条包归属规则：`RuleSets` 放 `compile` 而不是 `rule`，
> 因为它是**装配知识**（有哪些预设），不是注册表（type→实现）。

### 4.6 `RuleContext` —— 规则作者看到的"课表世界"（293 行）

**这个类的存在理由**（javadoc 第 18–22 行，本模块最值钱的一段说明）：

> 原 Python 实现里 `fixed` 相关处理**散落 39 处**：每条约束都要自己判断"这个时段是不是固定课"、
> 自己把固定课的数量数出来、再手工加进线性表达式。任何新规则（人写或 AI 写）都必须重复这套记账，
> 因此**极易漏**。

于是 `RuleContext` 把所有"固定课记账"收敛成一组方法。规则作者只需要问业务问题：

```java
int fixed = ctx.fixedCountForTeacher(teacherId, day, period);   // 固定课已算在内
List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period); // 只含非固定时段
model.addLessOrEqual(sum(vars) + fixed, 1);
```

#### 逐方法

| 方法 | 逐行说明 | 谁调它 |
|---|---|---|
| `CpModel model()` | `index.model()` 转发 | 每个 handler 的第一行 |
| `int days()` | 返回常量 `5` | 每个 handler 的天循环上界 |
| `List<ClassData> classes()` | `index.classDataInOrder()`（**每次新建列表**，§3.1.4） | 每个班级 handler 的最外层循环 |
| `Collection<Long> classIds()` | `index.allClassIds()`（只读视图） | `fixedCountForTeacher*`、`freeTeacherSlots*` |
| `ClassData classData(long)` | 转发 | 少见（多数用 `classes()`） |
| `Set<Long> subjectsOf(long)` | 转发 | `SubjectMaxPerDayHandler` 等 |
| `Map<Long,Integer> weeklyHoursOf(long)` | 转发 | `SubjectWeeklyHoursHandler` |
| `Collection<TeacherInfo> teachers()` | 转发 | 教师级 handler |
| `boolean teacherExists(long)` | **线性扫描** `teachers()` 对比 id | 只有 `TeacherUnavailableHandler`（第 67 行）。⚠ **O(教师数) 的线性扫描**：本意是"把引用了不存在的教师变成配置错误而非无解"，而这条规则里只调用一次，代价可忽略。**但写成 `index.teacher(id) != null` 的 try/catch 会更别扭**，现在的写法更直白 |
| `int teacherWeeklyLimit(long)` | `index.teacher(teacherId).maxWeeklyHours()` —— ⚠ **教师不存在会抛 `IllegalArgumentException`**（`SlotVariableIndex.teacher` 的行为） | `TeacherWeeklyLimitHandler` 直接用 `teacher.maxWeeklyHours()` 而**不用**这个方法；**实测**本模块内没有调用者。**此处未完全确认**是否有外部（scheduling）调用 |
| `boolean teacherTeachesClass(long,long)` | 转发 | 热路径，见 §3.1.4 |
| `int periodsOn(long, int day)` | `index.lastPeriod(...)` —— ⚠ **名字叫"节数"，不是"最后一节下标"**（§3.1.4 的同名陷阱），转发时名字改对了 | 所有需要"当天最后一节"的 handler |

**固定课计数组**：

| 方法 | 逐行说明 |
|---|---|
| `boolean isFixed(long,int,int)` | 转发 |
| `long fixedSubjectAt(long,int,int)` | `var fe = index.fixedAt(...); return fe == null ? -1 : fe.subjectId();` —— ⚠ **用 `-1` 表示"不是固定课"**（而不是 null 或 Optional）。这样调用方可以写 `fixedSubject >= 0` 与 `== subjectId` 两个判断，**不需要判空** |
| `long fixedTeacherAt(long,int,int)` | 同上，返回 `teacherId` 或 `-1` |
| `int fixedCountForSubject(long classId, long subjectId)` | 把每天的数量加起来。⚠ **第 122 行有一个死变量**：`int periods = periodsOn(classId, 0);` **声明后从未使用**（循环里用的是 `fixedCountForSubjectOnDay`）。无害，但读的时候别以为它在参与计算 |
| `int fixedCountForSubjectOnDay(long,int,int day)` | 对 `period = 0 .. periodsOn(classId, day)-1` 逐节比 `fixedSubjectAt == subjectId` 计数 |
| `int fixedCountForTeacher(long teacherId)` | **跨该教师教的所有班 × 5 天 × 节**统计。⚠ 先 `teacherTeachesClass` 过滤（省掉大部分班） |
| `int fixedCountForTeacher(long,int day,int period)` | **该教师在某天某节被固定课占了几次**。⚠ **返回 `>1` 意味着"固定课本身就撞车"** —— `TeacherNoConflictHandler` 用它抛 `ConstructionInfeasible`。⚠ 有两个过滤：`teacherTeachesClass` 与 `period >= periodsOn(classId, day)`（该班这天没这么多节） |
| `int fixedCountForTeacherOnDay(long,int day)` | 对 `period < maxPeriodsOn(day)` 累加上一个方法。⚠ **用 `maxPeriodsOn`（跨班最大）而不是某个班的节数**，因为要跨班；每个班的越界由上一个方法的 `period >= periodsOn` 挡掉 |

**变量访问组**（三个转发 + 四个批量收集）：

| 方法 | 逐行说明 |
|---|---|
| `IntVar slotVar(long,int,int)` | 转发。**固定课或空班返回 `null`** |
| `BoolVar subjectAt(long,int,int,long)` | 转发。**固定课或该班无此科目返回 `null`** |
| `BoolVar teacherAt(long,int,long,int,int)` | 转发。**固定课或无任教关系返回 `null`** |
| `List<BoolVar> freeSubjectSlots(long classId, long subjectId)` | 5 天 × `periodsOn(classId, day)` 收集 `subjectAt(...)`，**跳过 `null`**。名字里的 "free" = **非固定课** |
| `List<BoolVar> freeSubjectSlotsOnDay(long,long,int day)` | 单天版本 |
| `List<BoolVar> freeTeacherSlots(long teacherId)` | 该教师教的所有班 × 天 × 节。⚠ **"free" = 非固定课**（不是"没课"！）。这个方法返回的是**"该教师可能在这里上课"的候选布尔集合** |
| `List<BoolVar> freeTeacherSlots(long,int day,int period)` | 单时段的跨班版本。**两个班节数不同时用 `period >= periodsOn(classId, day)` 挡住** |
| `List<BoolVar> freeTeacherSlotsOnDay(long,int day)` | ⚠ **用 `maxPeriodsOn(day)` 遍历**（跨班最大节数），再对每节调用上一个方法。**如果这里错用某个班的节数，节数少的班会被整段漏掉** |
| `int maxPeriodsOn(int day)` | 遍历所有 `ClassData` 取 `Math.max`。⚠ **各班节数可以不同**，所以"天 × 节"的二层遍历必须用这个上界，不能取 `classes().get(0)` |

> ⚠ **`freeSubjectSlots` / `freeTeacherSlots` 与 `freeTeacherSlots(teacherId, day, period)` 的关系**（javadoc 第 226–229 行）：
> 它们最终都走同一个 `teacherAt`/`subjectAt` 缓存，**返回的是同一批 `BoolVar` 对象**。
> 这就是"变量数从 13,452 降到 9,676"的实现层面原因：`TeacherNoConflictHandler`（用三参版）
> 与 `TeacherWeeklyLimitHandler`（用一参版）**共用同一批教师成员布尔**，而不是各建一套。

**最后两个方法**（第 284–292 行）`assignIndicesOf` / `assignmentAt`：
**只为"提取/测试"准备的**（在真正的建模里用不到），注释也写了"提取用"。
`CounterexampleInjectionTest` 等测试通过 `compiled.context()` 拿变量、构造反例，
就是靠这些访问器。⚠ 它们的存在意味着**规则作者也能看到任教关系下标** ——
理论上一不小心就会绕过 `subjectAt` 自己造变量（从而破坏 28% 的去重）。
**纪律要求：handler 里只用 `ctx.*At / free*` 拿变量。**

### 4.7 `PenaltyCollector` —— 软约束的惩罚项收集（72 行）

```java
private final CpModel model;
private final List<LinearArgument> unitTerms = new ArrayList<>();
private final List<LinearArgument> weightedTerms = new ArrayList<>();
private final List<Long> weights = new ArrayList<>();
private long constant;
private int currentWeight = 1;

public PenaltyCollector(CpModel model) { this.model = model; }

public void useWeight(int weight) { this.currentWeight = Math.max(1, weight); }

public void add(LinearArgument term) {
    if (currentWeight == 1) { unitTerms.add(term); }
    else { weightedTerms.add(term); weights.add((long) currentWeight); }
}

public void addConstant(long value) { constant += (long) currentWeight * value; }

public boolean isEmpty() {
    return unitTerms.isEmpty() && weightedTerms.isEmpty() && constant == 0;
}

public void commit() {
    if (isEmpty()) { return; }                       // ← 没有惩罚项 → 不设目标
    LinearExprBuilder builder = LinearExpr.newBuilder();
    if (!unitTerms.isEmpty()) { builder.addSum(unitTerms.toArray(new LinearArgument[0])); }
    for (int i = 0; i < weightedTerms.size(); i++) {
        builder.addTerm(weightedTerms.get(i), weights.get(i));
    }
    if (constant != 0) { builder.add(constant); }
    model.minimize(builder.build());
}
```

**它解决的问题**：CP-SAT 的 `model.minimize(...)` **只能调用一次**，而要表达的是
"所有软约束的惩罚项加起来"。所以必须有一个收集器：每个 handler 只管 `add(...)`，
最后由它统一提交。

**逐行**：
- **三组数据分开存**（`unitTerms` / `weightedTerms` + `weights` / `constant`）：
  ⚠ **为什么要分**：`LinearExprBuilder.addSum(...)` 可以一次性把一批**系数为 1** 的项加进去，
  而带权重的项要逐项 `addTerm(term, weight)`。把权重为 1 的项单独攒起来，
  能让"最常见的情况（权重全 1）"走批量路径，**产出的 `LinearExpr` 形状更简单**。
  这一点是**为模型指纹服务的** —— 目标里项的组织方式会改变 proto。
  与 Python 的语义对齐（javadoc 第 14–16 行）："所有惩罚项权重为 1 时，目标就是各项之和"。
- **`currentWeight` 是可变状态**：⚠ **收集器是"有状态、按顺序消费"的**。
  `RuleCompiler` 在处理**每条规则之前**调 `penalties.useWeight(rule.effectiveWeight())`（§5.1 ②），
  之后这条规则的所有 `add` 都用这个权重。**顺序错乱（比如在别的规则处理中途改权重）会静默算错目标**。
- **`useWeight` 里 `Math.max(1, weight)`**：与 `ConstraintRule.effectiveWeight()` 的兜底**重复了一次**。
  ⚠ **两处兜底都在**：`effectiveWeight()` 保证 `≥1`，`useWeight` 再保一次。
  这是"纵深防御"而不是冗余错误 —— `PenaltyCollector` 是 `rule` 包的公开 API，
  不能假设调用方一定先过了 `effectiveWeight()`。
- **`addConstant(long value)`**：**常数惩罚**（与决策变量无关）。
  用法见 `SubjectNoConsecutiveHandler`（两节都是固定课且都是该科目 → 罚 1）。
  ⚠ **注意 `constant += currentWeight * value`**：常数的累加是**在 `addConstant` 时乘权重**的，
  而变量项是在 `commit` 时才乘。两条路径的乘法时机不同，**结果一致**（都是按当时的权重），
  但读代码时要意识到"权重是快照"。
- **`isEmpty()` 的三项判定**：⚠ **`constant == 0` 也是条件之一**。
  这意味着"只有常数惩罚、且常数为 0"等价于没有惩罚 —— 正确。
  但反过来：**只有常数惩罚（非 0）时 `isEmpty()` 为 false**，会去调 `model.minimize(constant)`。
- **`commit()` 里 `if (isEmpty()) return;`**：⚠ **没有惩罚项时完全不设置目标**。
  javadoc 明说这是对齐 Python 的 `if penalty_terms:`。
  **不这么写会怎样**：如果总是调 `model.minimize(0)`，模型里会多出一个空目标 ——
  **语义相同（最小化 0）但 proto 变了、指纹变了、GoldenModelTest 会红**。
- **`builder.addSum(...)` 用 `toArray(new LinearArgument[0])`**：
  ⚠ **Java 绑定里 `LinearExpr.sum` 收的是 `LinearArgument[]`**（[踩坑总结.md](踩坑总结.md) E1）。
  用 `new LinearArgument[0]` 而不是预分配大小的数组，是现代 JVM 上更快的写法（避免反射式数组分配）。
- **`builder.add(constant)`**：加常数项（`LinearExprBuilder.add(long)`）。
- **`model.minimize(builder.build())`**：⚠ **整个模型里唯一一次 `minimize`**。
  它对应 proto 的 `objective`，也就是 [Java建模等价性验证报告.md](Java建模等价性验证报告.md) 里
  `scaling_factor` 那个差异点：**Python 的 `Minimize` 写 1.0，Java 的 `minimize` 留空 0.0**。

---

## 5. `compile` —— 把规则集编译成模型（4 个类，201 行）

### 5.1 `RuleCompiler` —— 规则集 → CP-SAT 模型（77 行）

```java
private final RuleRegistry registry;

public RuleCompiler(RuleRegistry registry) { this.registry = registry; }

public static RuleCompiler withBuiltinRules() {
    return new RuleCompiler(BuiltinRules.registry());
}

public CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules) {
    CpModel model = new CpModel();                                   // ① 空模型
    SlotVariableIndex index = SlotVariableIndex.create(model, input); // ② 建索引（顺带校验固定课）
    RuleContext ctx = new RuleContext(index);                         // ③ 规则作者的世界
    PenaltyCollector penalties = new PenaltyCollector(model);         // ④ 软约束收集器

    // 1) 不变量 + 数据驱动约束（硬）
    for (RuleHandler handler : registry.mandatory()) {
        handler.apply(ctx, RuleParams.empty(), true, penalties);
    }

    // 2) 可配置规则
    for (ConstraintRule rule : rules) {
        if (!rule.enabled()) { continue; }
        RuleHandler handler = registry.get(rule.type());
        if (handler.mandatory()) { continue; }
        List<String> errors = rule.params().validate(handler.params());
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "规则 " + rule.type() + " 参数非法: " + String.join("; ", errors));
        }
        penalties.useWeight(rule.effectiveWeight());
        handler.apply(ctx, rule.params(), !rule.isSoft(), penalties);
    }

    // 3) 目标
    penalties.commit();

    String stats = model.modelStats();
    return new CompiledModel(model, index, ctx, stats, ModelFingerprints.ofStats(stats));
}
```

**逐行**：

**① `new CpModel()`**：一个空的 CP-SAT 模型对象。**注意它不是在 `OrbitSolver` 里建的**
—— 这个 `cpModel` 是"编译器内部的东西"，`OrbitSolver` 只拿到 `CompiledModel`。

**② `SlotVariableIndex.create(model, input)`**：建索引，**这也是唯一会抛 `ConstructionInfeasible` 的地方**
（固定课展开检查，§3.1.3 ⑥）。⚠ **注意它只"建索引"，不建变量** ——
变量在 handler 第一次要的时候才建（§3.1.6）。

**③ `new RuleContext(index)`**：把索引包成"规则作者的世界"。

**④ `new PenaltyCollector(model)`**：软约束的收集器，**同一个实例贯穿所有规则**。

**`for (RuleHandler handler : registry.mandatory())`（第 49–51 行）—— 第 1 阶段**：
- **遍历顺序 = `BuiltinRules` 的注册顺序**（`LinkedHashMap` 保序）。⚠ **这决定了不变量之间的变量创建顺序 → 决定指纹**。
- **`RuleParams.empty()`**：不变量**不接受参数**（它们的参数来自快照/教务数据，
  由 handler 自己去 `ctx` 里读）。这是"`mandatory` 意味着不可配置"的具体落点。
- **`true`（hard）**：不变量**一律是硬约束**。
- **`penalties` 也传进去**：⚠ **mandatory 的 handler 不应该用它**（接口 javadoc 写了），
  但签名要求传。这是接口设计上的一处"约定而非强制"。

**`for (ConstraintRule rule : rules)`（第 54–69 行）—— 第 2 阶段**：
- **`if (!rule.enabled()) continue;`**：⚠ 这是**唯一**的 enabled 过滤点（§1.2）。
- **`registry.get(rule.type())`**：未知类型 → `IllegalArgumentException("未知的规则类型: …已注册: […]")`。
  **为什么在这里抛而不是在数据库层**：规则类型是**代码里的概念**（`rule_type` 列只是字符串），
  只有注册表知道哪些合法。[RuleParamsValidationTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/RuleParamsValidationTest.java) 断言了这条错误文案。
- **`if (handler.mandatory()) continue;`**：⚠ **跳过**。理由（第 60 行注释）：
  "不变量不接受外部配置，避免被规则表重复施加"。
  **不这么写会怎样**：如果数据库里误存了一行 `teacher_no_conflict`（比如手工插入、
  或规则集从老数据迁移时带过来），不变量会被**施加两次** ——
  两次 `addLessOrEqual(sum(vars)+fixed, 1)` 是**冗余但语义相同**的约束，
  **模型仍可解、结果仍正确，只是模型的约束数变了、指纹变了**。
  这类"重复但不报错"的漂移正是这里要堵的。
- **`rule.params().validate(handler.params())`**：⚠ **校验在编译时也做一遍**，
  不只依赖 scheduling 保存时的那次校验。原因是：参数可能来自**旧数据**、
  **手工改库**、或者**旧版本 handler 的 schema**（handler 升级后老参数变得非法）。
  在编译入口再校验一次，保证"进了模型的一定是合法参数"。
- **校验失败抛 `IllegalArgumentException`，文案是
  `"规则 " + rule.type() + " 参数非法: " + String.join("; ", errors)`**：
  ⚠ **两条信息都不能少**：① 是哪条规则（`rule.type()`）② 具体哪里不对（`validate` 的文案）。
  测试第 110、112 行**同时断言了这两点**。
- **`penalties.useWeight(rule.effectiveWeight())`**：⚠ **必须在 `handler.apply` 之前调用**。
  它设置的是"接下来这条规则的所有 `add` 用哪个权重"。**硬规则也照样调** ——
  虽然硬规则不该 `add` 任何东西，但"忘了调"的后果是**下一条软规则用错权重**。
  所以放在 apply 之前无条件执行，是**唯一不需要判断硬软的位置**。
- **`handler.apply(ctx, rule.params(), !rule.isSoft(), penalties)`**：
  ⚠ 注意是 **`!rule.isSoft()`**（而不是 `rule.hardness() == HARD`）——语义相同，
  但这里复用 `isSoft()` 是为了"硬软判断只有一处"。

**`penalties.commit()`（第 72 行）—— 第 3 阶段**：把目标函数提交给模型（§4.7）。
⚠ **顺序含义**：目标必须**在所有约束都加完之后**提交。虽然 CP-SAT 允许约束与目标任意顺序添加，
但"最后提交目标"让"目标里的项已经全部收集齐"这件事**在代码顺序上一目了然**。

**最后三行**：
```java
String stats = model.modelStats();
return new CompiledModel(model, index, ctx, stats, ModelFingerprints.ofStats(stats));
```
- `model.modelStats()`：⚠ **在编译结束时算一次**，而不是等到求解完。理由：统计/指纹反映的是**模型**，
  求解不会改模型；而且 `SolveOutcome` 要用它，`RuleTrialService`（只编译不求解）也要用它。
- `ModelFingerprints.ofStats(stats)`：⚠ **直接把已经拿到的 `stats` 传进去复用**，
  而不是 `ModelFingerprints.of(model)` 再算一遍 `modelStats()` ——
  `modelStats()` 是个不小的字符串生成操作，编译是热路径（每次排课、每次试算都跑）。

`withBuiltinRules()`：⚠ **实测本模块内没有调用者**（`OrbitSolver.compile` 走的是
`new RuleCompiler(registry).compile(...)`）。它是一个便利工厂，**此处未完全确认**是否有外部调用。

### 5.2 `CompiledModel` —— 编译产物（19 行）

```java
public record CompiledModel(
        CpModel model,
        SlotVariableIndex index,
        RuleContext context,
        String modelStats,
        String modelFingerprint) {
}
```

**逐字段**：

| 字段 | 谁用 | 为什么必须在产物里 |
|---|---|---|
| `model` | `OrbitSolver.solve` 传给 `CpSolver` | 核心 |
| `index` | `TimetableExtractor.extract(solver, compiled.index())` | **结果提取需要它**：把整数变量的取值还原成"哪个班哪节上什么课哪位老师"（§3.2） |
| `context` | 测试（`compiled.context()`） | ⚠ **生产路径不用它**。它存在是为了**测试能拿变量构造反例**（[CounterexampleInjectionTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/CounterexampleInjectionTest.java) 反复用 `ctx.teacherAt(...)` / `ctx.freeTeacherSlots(...)`）。**把 `RuleContext` 留在产物里是一个有意的便利**，代价是"调用方可以拿到变量做任意事" |
| `modelStats` | `SolveOutcome`、诊断落库 | 人类可读的模型规模；诊断要留痕时用它 |
| `modelFingerprint` | `SolveOutcome`、worker 上报 | "这版课表用的哪个模型"的凭据（[架构说明.md](架构说明.md) §6⑥） |

**它是一个 `record` 而不是一个"带 getter 的类"**：只装数据，没有行为。
⚠ **`model()` 返回的是可变的 `CpModel`** —— 测试正是靠这一点往里 `addEquality(...)`
强制变量的（构造反例）。生产路径不会改它。

### 5.3 `BuiltinRules` —— 内置规则的装配点（51 行）

```java
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
```

**逐行**：
- **`List.of(...)` 保持参数顺序**，`RuleRegistry` 用 `LinkedHashMap` 接住 →
  **注册顺序 = 变量创建顺序**（javadoc 第 26 行的警告）。
- **前三条是 mandatory**：它们的相对顺序决定了模型里变量的创建次序。
  ⚠ **改动这 10 行的顺序会让模型指纹变化 → `GoldenModelTest` 会红**。
  而那是**有意的**："模型变了必须被看见并重新解释"。
- **每次调用都 `new` 一批 handler 对象**：⚠ **handler 是无状态的**（每个 `apply` 只用参数与 ctx），
  所以"新建一批"没有语义影响；但**每次调用 `builtinRules()` 都分配 10 个对象**，
  而 `OrbitSolver.compile`（二参版）每次都调 `BuiltinRules.registry()`。
  在"每次排课调一次"的频率下无所谓；**如果将来放进高频路径要注意**。
  做成静态单例是不行的 —— `RuleRegistry` 是注册表的唯一实例，
  而反例测试需要"另一个注册表"，把内置的做成全局单例会让"谁改了它"变得可能。
- **10 条规则的分工**（[架构说明.md](架构说明.md) §3.4 的表）：

  | 类型 | 规则 | `TYPE` 常量 |
  |---|---|---|
  | 不变量/数据驱动（3） | `TeacherNoConflictHandler` | `teacher_no_conflict` |
  | | `SubjectWeeklyHoursHandler` | `subject_weekly_hours` |
  | | `TeacherWeeklyLimitHandler` | `teacher_max_weekly_hours` ⚠ 见下 |
  | 可配置（7） | `SubjectMaxPerDayHandler` | `subject_max_per_day` |
  | | `SubjectNoConsecutiveHandler` | `subject_no_consecutive` |
  | | `TeacherMaxDailyPeriodsHandler` | `teacher_max_daily_periods` |
  | | `TeacherUnavailableHandler` | `teacher_unavailable` |
  | | `SubjectAvoidLastPeriodHandler` | `subject_avoid_last_period` |
  | | `MainSubjectMorningHandler` | `main_subject_morning` |
  | | `TeacherMinimizeGapsHandler` | `teacher_minimize_gaps` |

  > ⚠ **`TeacherWeeklyLimitHandler.TYPE` 是 `"teacher_max_weekly_hours"`，
  > 而类名叫 `TeacherWeeklyLimit`** —— [架构说明.md](架构说明.md) §5.2 的表里写的是
  > `TeacherWeeklyLimit`，`RuleSets` 的 javadoc 也写 `{@code TeacherWeeklyLimit}`。
  > **字符串常量才是数据库里的真相**，类名只是 Java 侧的叫法。改类名不影响数据，
  > **改 `TYPE` 常量等于要求迁移数据库**。

**"新增一种规则只有一处要改"**（javadoc 第 22–24 行）：写一个 handler + 在这里登记一行，
**前端表单与助手工具会自动跟上** —— 因为它们的输入是 `handler.params()`
（[架构说明.md](架构说明.md) §5.6 的"一份 schema，三处消费"）。
[RuleTypeCatalog](../../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/rule/RuleTypeCatalog.java)
只是把 `BuiltinRules.registry()` 换个形状暴露，**没有任何类型白名单或 if/else**。

### 5.4 `RuleSets` —— 预设规则集（54 行）

```java
public static List<ConstraintRule> legacyEquivalent() {
    return List.of(
            ConstraintRule.soft(SubjectMaxPerDayHandler.TYPE,
                    RuleParams.of(Map.of("max", 1)), 1, "任何科目一天最多 1 节"),
            ConstraintRule.soft(SubjectNoConsecutiveHandler.TYPE,
                    RuleParams.empty(), 1, "任何科目不连排两节"));
}
```

**逐行**：
- **只有两条软规则**，因为另外三条（教师不冲突、周课时、教师周上限）是 `mandatory`，
  由 `RuleCompiler` **自动施加**，不需要在规则集里列（列了也会被 `RuleCompiler` 跳过，§5.1）。
- **权重都是 1**：与 Python 一致。
- **`SubjectMaxPerDay` 的参数是 `Map.of("max", 1)`**：⚠ 注意 **`max` 的值是 `Integer` 1**，
  而 `RuleParams.requireLong` 用 `instanceof Number` 接住 —— 所以 `Integer` 没问题。
  但同时 `SubjectMaxPerDayHandler.params()` 里 `P_SUBJECT` 是 **optional** 的，
  所以"不指定 subject_id"= **适用于所有科目**，这正是 Python 的"软约束 1"语义。
- **`SubjectNoConsecutive` 用 `RuleParams.empty()`**：不指定科目 = 所有科目都不连排。

**为什么它必须在 `main` 而不是 `test` 里**（javadoc 第 31–34 行，这是本模块最"方法论"的一段）：

> 迁移期的 scheduling 服务要用它来求解，等价性测试也要用它来对基线 ——
> 如果各写一份，两边迟早漂移，而"漂移"正是这个预设要防的东西。
> 所以：**只有一份定义，测试引用生产的**。

实测落地：`TestFixtures.legacyEquivalentRules()`（测试代码）**只是转发**到
`RuleSets.legacyEquivalent()`，注释写明"这里只做转发：**只有一份定义**，测试与生产不可能漂移"。

> ⚠ **改动它会立刻让模型指纹变化 → `GoldenModelTest` 红**。javadoc 明说"那是**有意的**"。

---

## 6. `rule/handler` —— 十条内置规则（11 个类，1015 行）

### 6.0 先看骨架：十条 `apply` 的统一形状

**读任何一条 handler 之前先记住这个骨架**（十条里有八条是这个形状）：

```
for (每个班 / 每位教师)                       ← ctx.classes() / ctx.teachers()
    for (每天 day = 0 .. ctx.days()-1)
        for (每节 period = 0 .. 当天节数-1)     ← ⚠ 节数按班按天算
            fixed = ctx.fixedCountForXxx(...)  ← 固定课已经算在内
            vars  = ctx.freeXxxSlots(...)      ← 只含非固定时段，固定课返回 null 被跳过
            ├─ hard: model.addLessOrEqual(sum(vars) + fixed, limit)   ← 加约束
            └─ soft: 造一个"超出量"变量 ≥ (sum(vars)+fixed-limit)     ← 加惩罚
                     penalties.add(超出量)
```

**三个必须记住的常量/约定**：

| 约定 | 说明 |
|---|---|
| **`hard` 是 `apply` 的参数** | 同一个 handler 两种模式都要实现（`subject_max_per_day` 就是硬软两用） |
| **`fixed` 是常数，不是变量** | 固定课没有决策变量（`slotVar` 返回 null），所以"固定课的数量"只能是**编译期算出的常数**，加在表达式里 |
| **`vars` 可能是空列表** | 此时**不要加约束**：`addLessOrEqual(0 + fixed, 1)` 如果 `fixed=0` 就变成 `0 ≤ 1`（恒真，无害但会让指纹变化）。所以十条里到处是 `if (!vars.isEmpty())` |

### 6.1 `HandlerSupport` —— 共用的线性表达式与布尔编码（78 行）

```java
final class HandlerSupport {              // ⚠ 包级私有：只有 rule.handler 包能用
    private HandlerSupport() { }

    static LinearArgument[] args(List<BoolVar> vars) { return vars.toArray(new LinearArgument[0]); }

    static Literal[] literals(List<BoolVar> vars) { return vars.toArray(new Literal[0]); }

    static Literal[] negations(List<BoolVar> vars) {
        Literal[] out = new Literal[vars.size()];
        for (int i = 0; i < vars.size(); i++) { out[i] = vars.get(i).not(); }
        return out;
    }

    static LinearExpr sumPlus(List<BoolVar> vars, long extra) {
        LinearExprBuilder builder = LinearExpr.newBuilder();
        if (!vars.isEmpty()) { builder.addSum(args(vars)); }
        if (extra != 0) { builder.add(extra); }
        return builder.build();
    }

    static int maxPeriodsOnDay(RuleContext ctx, int day) { return ctx.maxPeriodsOn(day); }

    static BoolVar boolConst(CpModel model, String name, boolean value) {
        BoolVar v = model.newBoolVar(name);
        model.addEquality(v, value ? 1 : 0);
        return v;
    }

    static void encodeOr(CpModel model, BoolVar target, Literal[] literals) {
        model.addBoolOr(literals).onlyEnforceIf(target);
        model.addBoolAnd(negate(literals)).onlyEnforceIf(target.not());
    }

    static void encodeAnd(CpModel model, BoolVar target, Literal[] literals) {
        model.addBoolAnd(literals).onlyEnforceIf(target);
        model.addBoolOr(negate(literals)).onlyEnforceIf(target.not());
    }

    private static Literal[] negate(Literal[] literals) { ... }
}
```

**逐行**：
- **类本身包级私有（无 `public`）**：它是 `rule.handler` 的内部工具，**不暴露给外部**。
  `OrbitSolver` / scheduling 都用不到它。
- **`args(vars)` → `LinearArgument[]`**：`BoolVar` **同时实现 `IntVar` 与 `Literal`**
  （[踩坑总结.md](踩坑总结.md) E1 记录的 API 事实），所以它既能当线性项又能当字面量；
  但 `LinearExpr.sum` 要求的是 `LinearArgument[]`，得显式转换类型（Java 的数组协变不够用）。
- **`literals(vars)` → `Literal[]`**：给 `addBoolOr` / `addBoolAnd` 用。
- **`negations(vars)`**：批量取反。⚠ **实测本模块内没有调用者**
  （`membership` 里是手工循环取反的，`SubjectNoConsecutiveHandler` 用 `new Literal[]{...}`）。
  它是为"批量 OR 取反"准备的，**目前是死代码**。**此处未完全确认**是否有测试在用（`HandlerSupport` 包级私有，测试在别的包，**访问不到**）。
- **`sumPlus(vars, extra)`**：构造 **`sum(vars) + extra`**。两个 `if` 都是**为了产出更简单的表达式**：
  - `vars` 为空 → 不加 `addSum`（否则会加一个空和）；
  - `extra == 0` → 不加常数（⚠ **这一条很重要**：加 `+0` 会改变 `LinearExpr` 的形状 → **改变指纹**）。
  - ⚠ **注意 `extra` 是 `long` 且可以为负**：`TeacherMaxDailyPeriodsHandler`（软）用
    `sumPlus(vars, fixed - max)` —— `fixed - max` 可能是负数，这里**必须支持负数**
    （`LinearExprBuilder.add(long)` 接受负常数）。
- **`maxPeriodsOnDay(ctx, day)`**：⚠ **纯转发**（一行），只是为了"在 handler 里写得更短"。
  它的存在让"跨班遍历用最大节数"这个意图更显眼。
- **`boolConst(model, name, value)`**：造一个**被钉死的布尔常量**。
  ⚠ 注释解释了用途："用于统一 OR/AND 编码，省去分支"。
  具体见 `TeacherMinimizeGapsHandler`：`before[0]` 与 `after[n-1]` 天然是"假"，
  用它造一个恒假布尔，就能让递推循环**不用写边界特例**。
  代价是模型里多几个变量/约束（**这也进指纹**）。
- **`encodeOr` / `encodeAnd`**：**双向等价**编码（`target ⟺ OR/AND(literals)`）。
  ⚠ **每一条编码都是"正向 + 反向"两条约束**，理由与 `membership` 完全相同（§3.1.6）：
  只写正向会得到**单向蕴含**，反向的取值就自由了。
  - `encodeOr`：`target ⇒ OR`（`addBoolOr(...).onlyEnforceIf(target)`）
    + `¬target ⇒ 全部为假`（`addBoolAnd(negate).onlyEnforceIf(target.not())`）
  - `encodeAnd`：`target ⇒ AND` + `¬target ⇒ 至少一个为假`
  ⚠ **`negate` 是 `private` 而 `negations` 是包级** —— 两个几乎一样的方法并存，
  命名只差一个 `s`，**读代码时容易看混**（`negate(Literal[])` vs `negations(List<BoolVar>)`）。

### 6.2 `TeacherNoConflictHandler` —— 不变量：教师时段不冲突（74 行）

对应 Python 的「硬 1」（javadoc 写"硬约束 2"，[架构说明.md](架构说明.md) §5.2 的表写"硬 1"；
**代码注释与架构表在这条上编号不一致**，以代码为准：`TYPE = "teacher_no_conflict"`）。

```java
public static final String TYPE = "teacher_no_conflict";
@Override public boolean mandatory() { return true; }
@Override public List<RuleParamSpec> params() { return List.of(); }

@Override
public String describe(RuleParams params) {
    return "同一教师同一时段最多一节课（系统不变量，始终生效）";
}

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
        long teacherId = teacher.id();
        for (int day = 0; day < ctx.days(); day++) {
            int maxPeriods = HandlerSupport.maxPeriodsOnDay(ctx, day);
            for (int period = 0; period < maxPeriods; period++) {
                List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period);
                int fixed = ctx.fixedCountForTeacher(teacherId, day, period);
                if (fixed > 1) {
                    throw new ConstructionInfeasible(
                            "教师 " + teacherId + "（" + teacher.name() + "）在第 "
                                    + (day + 1) + " 天第 " + (period + 1)
                                    + " 节被固定课占了 " + fixed + " 次，固定课之间就冲突");
                }
                if (!vars.isEmpty()) {
                    model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), 1);
                }
            }
        }
    }
}
```

**逐行与数学含义**：
- **三层循环**：教师 → 天 → 节。⚠ **第二层用 `ctx.days()`（常量 5）**，
  但**第三层的上界是 `maxPeriodsOnDay`（跨班最大节数）** —— 因为要覆盖"该教师教的每个班"，
  各班节数不同，必须用最大上界；**个别班节数不够的情况由 `freeTeacherSlots` 内部挡掉**
  （§4.6 里 `period >= periodsOn(classId, day)` 那一行）。
- **`vars = ctx.freeTeacherSlots(teacherId, day, period)`**：
  "该教师在这个时段、**跨他教的所有班**、'可能在这里上课'的布尔集合"，**不含固定课时段**。
- **`fixed = ctx.fixedCountForTeacher(teacherId, day, period)`**：
  该时段被**固定课**占了**几次**。
- **`if (fixed > 1) throw ConstructionInfeasible`**：⚠ **这是本 handler 最值钱的一段**。
  `fixed > 1` = 同一个老师同一时段被**两个不同的班**用固定课占住 = **固定课之间就打架了**，
  与决策无关，**求解器永远解不出来**。抛异常能直接指出"是哪个老师、哪一天、第几节、占了几次"。
  - **不这么写会怎样**：约束会变成 `sum(vars) + 2 ≤ 1`，即 `sum(vars) ≤ -1` ——
    对非负变量**不可满足**，于是整个模型 `INFEASIBLE`，
    用户只看到"排课无解，请检查任教关系、周课时、固定时段和教师课时上限"（Python 的原话），
    **根本不知道是固定课冲突**。
  - 这个场景在 [Java建模等价性验证报告.md](Java建模等价性验证报告.md) §2 的"期间发现"里被明确记录过：
    夹具里"如果固定课覆盖的班级共享同一位教师，会导致该教师在同一时段被固定多次"，
    Python 侧只表现为"排课无解"。
- **`model.addLessOrEqual(sumPlus(vars, fixed), 1)`**：**数学含义**：
  $$fixed + \sum_{v \in vars} v \le 1$$
  即"该教师在这个时段最多上一节课"。`fixed` 是常数（固定课已经占掉的次数，这里必为 0 或 1），
  `vars` 里的每个布尔表示"该教师在这个班的这一节上课"。
- **`if (!vars.isEmpty())`**：⚠ **当 `fixed == 0` 且 `vars` 为空时，表达式是 `0 ≤ 1`**，
  加进去是**冗余约束**（模型语义不变，但约束数变、指纹变）。所以必须跳过。
  ⚠ **注意：如果 `fixed == 1` 且 `vars` 为空，不加约束也是对的**（`1 ≤ 1` 恒真）——
  现在的写法**两种情况都跳过**，结果一致。

### 6.3 `SubjectWeeklyHoursHandler` —— 数据驱动：周课时排满（73 行）

对应 Python 的「硬 2」（代码 javadoc 写"硬约束 1"）。⚠ **注意 javadoc 第 20 行的编号（硬约束 1）
与 `TeacherNoConflictHandler` javadoc 里的"硬约束 2"以及架构表都不完全对齐** ——
本模块的两处 javadoc 编号系统与架构说明的表不一致，**以 TYPE 常量为准**。

```java
public static final String TYPE = "subject_weekly_hours";
@Override public boolean mandatory() { return true; }
@Override public List<RuleParamSpec> params() { return List.of(); }

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    for (ClassData cd : ctx.classes()) {
        long classId = cd.classId();
        for (Map.Entry<Long, Integer> entry : ctx.weeklyHoursOf(classId).entrySet()) {
            long subjectId = entry.getKey();
            int needed = entry.getValue();
            int fixed = ctx.fixedCountForSubject(classId, subjectId);
            int remaining = needed - fixed;
            if (remaining <= 0) { continue; }        // 固定课已经满足甚至超过要求
            List<BoolVar> vars = ctx.freeSubjectSlots(classId, subjectId);
            if (!vars.isEmpty()) {
                model.addEquality(LinearExpr.sum(HandlerSupport.args(vars)), remaining);
            }
        }
    }
}
```

**逐行与数学含义**：
- **外层是班，内层是"该班的 科目→周课时"**（`weeklyHoursOf`，保序的 `LinkedHashMap`）。
  ⚠ **内层顺序会影响变量创建顺序 → 影响指纹**。
- **`fixed = ctx.fixedCountForSubject(classId, subjectId)`**：该班该科目**整周**被固定课占掉的节数。
- **`remaining = needed - fixed`**：**还需要排几节**。
  ⚠ **这就是"固定课统一记账"的关键一步**：Python 侧是在每条约束内部手工算这个差值的（39 处之一）。
- **`if (remaining <= 0) continue;`**：⚠ **两种含义合用一个分支**：
  - `remaining == 0`：固定课正好排满 → 自由时段必须**一节都不排**。
    **不加约束**的话，自由时段仍可排该科目 → **该科目会超课时**！
    这看起来像 bug，但**实际上不是**：因为 `addEquality(sum, 0)` 与 **不加约束** 在
    "自由时段能否排该科目"上确实不同 —— ⚠ **这里需要仔细**：
    本分支**跳过了加约束**，所以"固定课已满足要求"时，**自由时段理论上还能再排这门课**。
    **但**：`remaining <= 0` 时 `addEquality(sum(vars), remaining)` 会因为 `remaining` 为负而不可满足，
    所以只能跳过。而"不许再排"这个约束**在本 handler 里确实没有加**。
    这不是本 handler 的职责（它只管"必须排满"），"不许超"要靠 —— 严格的等式在
    `remaining > 0` 时才加。
    ⚠ **所以当固定课恰好等于周课时要求时，模型允许该科目被多排**，这是一个真实的宽松点。
    实际数据里"固定课覆盖整个周课时"极为罕见（[PositiveFeasibilityTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/PositiveFeasibilityTest.java)
    用独立校验器核对节数，所以真出现会测出来）。**此处如实记录：代码行为就是"跳过不加约束"。**
  - `remaining < 0`：固定课**已经超标**（配置矛盾）→ 同样只能跳过。
    ⚠ **这里没有像其他 handler 那样抛 `ConstructionInfeasible`** —— 与
    `SubjectMaxPerDayHandler`（固定课超 `max` 会抛）的处理**不一致**。
    **代码行为就是"静默继续"**。
- **`List<BoolVar> vars = ctx.freeSubjectSlots(classId, subjectId)`**：该班该科目**整周**非固定时段的布尔。
- **`model.addEquality(LinearExpr.sum(args(vars)), remaining)`**：**数学含义**：
  $$\sum_{v \in vars} v = needed - fixed$$
  即"**自由时段里恰好排 `remaining` 节该科目**"，加上已固定的 `fixed` 节，
  正好等于年级要求的 `needed`。
- **⚠ 注意这里用的是 `LinearExpr.sum(...)` 而不是 `HandlerSupport.sumPlus(vars, remaining)`**
  （对比 `addLessOrEqual` 那些）。**为什么**：等式右边是常数 `remaining`，
  `addEquality(expr, value)` 的 `value` 是 `long`，所以不需要把常数并进表达式。
  **如果写成 `sumPlus(vars, -remaining)` 再 `addEquality(expr, 0)`，语义相同但 proto 不同**（指纹会变）。
- **`if (!vars.isEmpty())`**：⚠ 如果该班该科目**一个自由时段都没有**（全被固定课占了），
  就加不了约束 —— 此时 `remaining > 0` 意味着**永远排不满**，模型会 `INFEASIBLE`。
  **这里没有抛 `ConstructionInfeasible`**（对比 `SubjectMaxPerDayHandler` 会抛）。
  外部静态诊断的 `MISSING_ASSIGNMENT` / `CLASS_CAPACITY_SHORTFALL` 就是抓这类情况的
  （[架构说明.md](架构说明.md) §5.7）。

### 6.4 `TeacherWeeklyLimitHandler` —— 数据驱动：教师周上限（65 行）

对应 Python 的「硬 3」（javadoc 写"硬约束 3"，与架构表一致）。

```java
public static final String TYPE = "teacher_max_weekly_hours";   // ⚠ 注意与类名不同
@Override public boolean mandatory() { return true; }

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
        long teacherId = teacher.id();
        List<BoolVar> vars = ctx.freeTeacherSlots(teacherId);        // ← 整周
        int fixed = ctx.fixedCountForTeacher(teacherId);             // ← 整周
        if (!vars.isEmpty()) {
            model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), teacher.maxWeeklyHours());
        }
    }
}
```

**逐行与数学含义**：
- **`ctx.freeTeacherSlots(teacherId)`（一参版）**：该教师**整周、跨所有班**的成员布尔。
  ⚠ **这是 28% 去重的另一半**：`TeacherNoConflictHandler` 用的是**三参版**，
  两者**返回同一批缓存的 `BoolVar`**（javadoc 第 20–21 行专门写了这一点）。
- **`model.addLessOrEqual(sumPlus(vars, fixed), teacher.maxWeeklyHours())`**：
  $$fixed + \sum_{v \in vars} v \le maxWeeklyHours$$
  即"该教师整周课时（含固定课）+ 上限"。
- **⚠ `teacher.maxWeeklyHours()` 是直接用 record 访问器读的，而不是 `ctx.teacherWeeklyLimit(id)`**
  —— `RuleContext.teacherWeeklyLimit` 因此在本模块内**没有调用者**（§4.6）。
- **`if (!vars.isEmpty())`**：同上，避免加恒真约束。
  ⚠ **注意：这里没有检查 `fixed > maxWeeklyHours`**（"固定课本身已超上限"）——
  那种情况下表达式是 `sum(vars) + fixed ≤ max`，即 `sum(vars) ≤ 负数` → **不可满足** →
  模型 `INFEASIBLE`，**不抛 `ConstructionInfeasible`**。
  与 `TeacherMaxDailyPeriodsHandler`（日上限，会抛）的处理**不一致**。
  **代码行为就是交给求解器。**

### 6.5 `SubjectMaxPerDayHandler` —— 单日单科节数上限（106 行，硬软两用）

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

@Override
public String describe(RuleParams params) {
    String scope = params.has(P_SUBJECT) ? "科目 " + params.longOr(P_SUBJECT, -1) : "所有科目";
    return scope + " 一天最多 " + params.intOr(P_MAX, 1) + " 节";
}

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;
    int max = params.requireInt(P_MAX);

    for (ClassData cd : ctx.classes()) {
        long classId = cd.classId();
        Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
        for (long subjectId : subjects) {
            if (!ctx.subjectsOf(classId).contains(subjectId)) { continue; }
            for (int day = 0; day < ctx.days(); day++) {
                int fixed = ctx.fixedCountForSubjectOnDay(classId, subjectId, day);
                List<BoolVar> vars = ctx.freeSubjectSlotsOnDay(classId, subjectId, day);

                if (hard) {
                    if (fixed > max) {
                        throw new ConstructionInfeasible(
                                "班级 " + classId + " 在第 " + (day + 1) + " 天，科目 "
                                        + subjectId + " 的固定课已有 " + fixed + " 节，超过上限 " + max);
                    }
                    if (!vars.isEmpty()) {
                        model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), max);
                    }
                } else {
                    int totalPossible = fixed + vars.size();
                    if (totalPossible <= max) { continue; }   // 不可能超，无需惩罚
                    IntVar excess = model.newIntVar(0, totalPossible,
                            String.format("day_exc_%d_%d_%d", classId, subjectId, day));
                    model.addGreaterOrEqual(excess, LinearExpr.newBuilder()
                            .addSum(HandlerSupport.args(vars))
                            .add(fixed - max)
                            .build());
                    penalties.add(excess);
                }
            }
        }
    }
}
```

**逐行与数学含义**：

**参数与 schema**：
- `P_SUBJECT` 用 **`optionalInt`** → 留空 = **适用于所有科目**。
  这让同一条规则能表达"语文一天最多两节"和"任何科目一天都别超过一节"（javadoc 第 21–22 行）。
- `P_MAX` 用 **`intParam`**（必填），范围 **`1..20`**。
  ⚠ **上界 20**：目前最大班级节数在 8 附近，20 是留了余量。**`max=21` 会被 `validate` 拒**，
  [RuleParamsValidationTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/RuleParamsValidationTest.java) 第 51–52 行断言了这一点。
- `describe` 用 `longOr(P_SUBJECT, -1)` / `intOr(P_MAX, 1)` 取默认值**只为了显示**。

**`apply` 前半段**：
- **`Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null`**：
  ⚠ **用包装类型 `Long` 的 `null` 表达"不指定"**，而不是哨兵值。
- **`Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId)`**：
  统一成"要处理的科目集合" —— 指定了就是单元素集合，否则是该班的全部科目。
  ⚠ **这一步让后面的循环只有一份**，不用写 if/else 两套。
- **`if (!ctx.subjectsOf(classId).contains(subjectId)) continue;`**：
  ⚠ **只在"指定了科目"时有意义**（此时 `subjects` 是 `List.of(only)`）：
  如果这个班根本没教这门课，就跳过。**不跳过会怎样**：
  `ctx.freeSubjectSlotsOnDay(...)` 会返回空列表（因为 `subjectAt` 对该科目返回 `null`），
  于是 `sumPlus([], 0) ≤ max` → 又是那条"恒真冗余约束"。
  **这个 `continue` 是为保模型干净/指纹稳定而存在的。**

**硬约束分支**：
- **`if (fixed > max) throw ConstructionInfeasible`**：配置矛盾（固定课本身就超上限）。
  ⚠ **`fixed` 是"该班该天该科目的固定课节数"** —— 一天里同一科目被固定两次就已经违规了。
  文案带上班级、天（+1 变成人话）、科目、实际值、上限。
- **`model.addLessOrEqual(sumPlus(vars, fixed), max)`**：
  $$fixed + \sum_{v} v \le max$$
  即"这一天这个科目最多 `max` 节"。

**软约束分支**（⚠ **本模块唯一的"单向下界线性化"模式**）：
- **`int totalPossible = fixed + vars.size()`**：**该科目这一天理论最多能上几节**
  （固定课 + 所有可选的自由时段）。
- **`if (totalPossible <= max) continue;`**：⚠ **"不可能超，无需惩罚"**。
  这是**纯粹的性能优化，而且是必要的**：不加这个短路，
  每个班 × 每科 × 每天都会造一个 `excess` 变量**即使它恒为 0**，
  变量数会暴涨、指纹变化、求解变慢。**短路之后，`excess` 只在真有超课可能时才存在。**
- **`IntVar excess = model.newIntVar(0, totalPossible, "day_exc_%d_%d_%d")`**：
  "**超出量**"变量，域是 `0 .. totalPossible`。
  ⚠ 上界取 `totalPossible` 而不是 `max`：`excess` 最大是"全部时段都排该科目"时的
  `fixed + vars.size() - max`，取 `totalPossible` 是**宽松上界**（够用）。
  ⚠ **变量名 `day_exc_{classId}_{subjectId}_{day}`** 进指纹。
- **`model.addGreaterOrEqual(excess, sum(vars) + (fixed - max))`**：**这是"单向下界"技巧**：
  $$excess \ge \sum v + fixed - max$$
  - 当实际节数 ≤ max 时，右边 ≤ 0，而 `excess ≥ 0` 已由定义域保证 → **`excess` 自然取 0**
    （因为目标在最小化它）；
  - 当实际节数 > max 时，右边 = 超出的节数 → `excess ≥ 超出量`，最小化时**恰好等于超出量**。
  
  ⚠ **为什么只用一条"≥"而不是等式**：等式需要引入 `max(0, ...)` 的非线性表达；
  "≥ + 目标最小化 + 定义域非负"三件事合起来**自动得到 `excess = max(0, 实际 - max)`**（这正是 javadoc 说的"单向下界的线性化"）。
  **代价是它依赖"`excess` 出现在目标里"** —— 如果哪条规则造了 `excess` 却忘了 `penalties.add(excess)`，
  这个变量就**完全自由**（可以取任意值），约束形同虚设，**且不报错**。
- **`penalties.add(excess)`**：按当前权重（`RuleCompiler` 已设好的 `rule.effectiveWeight()`）累加进目标。

**这条规则为什么能同时是硬的和软的**（javadoc 第 24–28 行）：
- 硬：`sum + fixed ≤ max`
- 软：`excess = max(0, sum + fixed - max)` 进目标
两者是同一语义的两种强度表达。`RuleSets.legacyEquivalent()` 用的是**软 + `max=1`** ——
**这正是 Python 的「软 1 一科一天最多 1 节」**。

### 6.6 `SubjectNoConsecutiveHandler` —— 单科不连排（113 行，仅软）

对应 Python 的「软 2」。javadoc 第 21–23 行说明了原实现的痛苦：
"原实现要在规则内部手工处理「前一节是固定课 / 后一节是固定课 / 两节都不是」三种分支并自查 fixed 表；
现在这些判断通过 `ctx.fixedSubjectAt` 完成，规则本体只剩业务逻辑。"

```java
public static final String TYPE = "subject_no_consecutive";
public static final String P_SUBJECT = "subject_id";
@Override public List<RuleParamSpec> params() {
    return List.of(RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）", 1, Integer.MAX_VALUE));
}

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;

    for (ClassData cd : ctx.classes()) {
        long classId = cd.classId();
        Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
        for (long subjectId : subjects) {
            if (!ctx.subjectsOf(classId).contains(subjectId)) { continue; }
            for (int day = 0; day < ctx.days(); day++) {
                int periods = ctx.periodsOn(classId, day);
                for (int period = 0; period + 1 < periods; period++) {
                    long fixedFirst = ctx.fixedSubjectAt(classId, day, period);
                    long fixedSecond = ctx.fixedSubjectAt(classId, day, period + 1);

                    if (fixedFirst >= 0 && fixedSecond >= 0) {           // 两节都是固定课
                        if (fixedFirst == subjectId && fixedSecond == subjectId) {
                            penalties.addConstant(1);
                        }
                        continue;
                    }
                    if (fixedFirst >= 0) {                               // 只有前一节固定
                        if (fixedFirst != subjectId) { continue; }
                        BoolVar second = ctx.subjectAt(classId, day, period + 1, subjectId);
                        if (second != null) { penalties.add(second); }
                    } else if (fixedSecond >= 0) {                        // 只有后一节固定
                        if (fixedSecond != subjectId) { continue; }
                        BoolVar first = ctx.subjectAt(classId, day, period, subjectId);
                        if (first != null) { penalties.add(first); }
                    } else {                                             // 两节都不固定
                        BoolVar first = ctx.subjectAt(classId, day, period, subjectId);
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
                }
            }
        }
    }
}
```

**逐行与数学含义**：

**循环结构**：
- **`for (int period = 0; period + 1 < periods; period++)`**：⚠ **上界是 `periods - 1`**，
  因为每次要看 `(period, period+1)` **一对**。写成 `period < periods` 会越界到"不存在的下一节"。
- **`fixedFirst` / `fixedSecond`**：⚠ 用 **`>= 0` 判断"是不是固定课"**，
  因为 `fixedSubjectAt` 用 `-1` 当哨兵（§4.6）。**这个约定必须记住**，
  写 `fixedFirst != -1` 与 `>= 0` 等价，但**写 `fixedFirst != 0` 就错了**（科目 ID 可以是 0？—— 从 `optionalInt(P_SUBJECT, min=1)` 看 ID 从 1 开始，所以 0 不会出现；
  **但 `>= 0` 是更安全的写法**）。

**四种情形**（这是本规则的核心，也是 Python 侧 39 处手工记账的典型现场）：

| 情形 | 处理 | 数学含义 |
|---|---|---|
| **两节都固定，且都是该科目** | `penalties.addConstant(1)` | 违规与决策**无关**（已经定了）→ **常数惩罚 1**。⚠ 这是 `PenaltyCollector.addConstant` 的典型用法 |
| 两节都固定，但不是"都是该科目" | 什么都不做 | 不可能违规 |
| **前一节固定 = 该科目，后一节自由** | 罚 `second`（"后一节也是该科目"的布尔） | 违规 ⟺ 后一节排了该科目 |
| **后一节固定 = 该科目，前一节自由** | 罚 `first` | 违规 ⟺ 前一节排了该科目 |
| **两节都自由** | 造 `both`，罚它 | 违规 ⟺ 两节都排该科目 |
| 前一节固定（但是别的科目） | `continue` | 必定不违规 |
| 后一节固定（但是别的科目） | `continue` | 必定不违规 |

- **两节都自由时的 `both` 编码**：
  ```java
  model.addBoolAnd(new Literal[] {first, second}).onlyEnforceIf(both);          // both ⇒ first ∧ second
  model.addBoolOr(new Literal[] {first.not(), second.not()}).onlyEnforceIf(both.not());  // ¬both ⇒ ¬first ∨ ¬second
  ```
  **合起来即 `both ⟺ first ∧ second`**。⚠ **两条都要**（与 `HandlerSupport.encodeAnd` 相同的道理）：
  只写第一条会得到单向蕴含，`both` 可以在两节都排了该科目时仍取 0，**惩罚被逃掉**。
  ⚠ **这里没有调用 `HandlerSupport.encodeAnd`**，而是**手写了同样的编码** ——
  行为一致（都是 `both ⟺ AND`），但**这是一处可以统一的地方**（`HandlerSupport.encodeAnd` 就是为它准备的）。
  ⚠ **注意 `addBoolAnd` 的参数是 `Literal[]`**，所以 `first` / `second` 这种 `BoolVar`
  直接被当作 `Literal` 用（`BoolVar implements Literal`）。
- **`penalties.add(second)` / `add(first)` / `add(both)`**：都是**加一个布尔**。
  因为 `BoolVar` 是 0/1 变量，所以"加上这个布尔"= **"每次违规罚 1"**。
- **变量名 `cons_{classId}_{day}_{period}_{subjectId}`**（`cons` = consecutive）。

**⚠ 与"硬"的关系**：javadoc 与 `RuleHandler.apply` 都允许硬软两用，
但**本 handler 完全没有 `hard` 分支** —— 无论 `hard` 传什么，它都只加惩罚项（软）。
**代码行为就是：这条规则实际只能是软的**，尽管 `params()`/`schedule` 允许把它配成硬约束
（此时 `RuleCompiler` 会传 `hard=true`，但本 handler **忽略该参数**，
于是"硬约束"被静默地当软约束执行 —— **罚分而不是禁止**）。
**这是一个真实的、静默的行为偏差**，此处如实记录（[RuleSets.legacyEquivalent](#54-rulesets--预设规则集54-行) 只用软，
所以迁移路径上不会触发）。

### 6.7 `TeacherMaxDailyPeriodsHandler` —— 教师单日节数上限（98 行，硬软两用）

结构与 `SubjectMaxPerDayHandler` 完全平行（一个按"班×科×天"，一个按"教师×天"）。

```java
public static final String TYPE = "teacher_max_daily_periods";
public static final String P_TEACHER = "teacher_id";
public static final String P_MAX = "max";

@Override public List<RuleParamSpec> params() {
    return List.of(
            RuleParamSpec.optionalInt(P_TEACHER, "教师 ID（留空表示所有教师）", 1, Integer.MAX_VALUE),
            RuleParamSpec.intParam(P_MAX, "一天最多几节", 1, 20));
}

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    Long only = params.has(P_TEACHER) ? params.requireLong(P_TEACHER) : null;
    int max = params.requireInt(P_MAX);

    for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
        long teacherId = teacher.id();
        if (only != null && only != teacherId) { continue; }
        for (int day = 0; day < ctx.days(); day++) {
            int fixed = ctx.fixedCountForTeacherOnDay(teacherId, day);
            List<BoolVar> vars = ctx.freeTeacherSlotsOnDay(teacherId, day);

            if (hard) {
                if (fixed > max) {
                    throw new ConstructionInfeasible(
                            "教师 " + teacherId + "（" + teacher.name() + "）在第 "
                                    + (day + 1) + " 天的固定课已有 " + fixed + " 节，超过单日上限 " + max);
                }
                if (!vars.isEmpty()) {
                    model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), max);
                }
            } else {
                int totalPossible = fixed + vars.size();
                if (totalPossible <= max) { continue; }
                IntVar excess = model.newIntVar(0, totalPossible,
                        String.format("tday_exc_%d_%d", teacherId, day));
                model.addGreaterOrEqual(excess, HandlerSupport.sumPlus(vars, fixed - max));
                penalties.add(excess);
            }
        }
    }
}
```

**逐行（只讲与 `SubjectMaxPerDayHandler` 不同的地方）**：
- **`if (only != null && only != teacherId) continue;`**：⚠ 与科目版本的写法**不同**：
  科目版是"把 `only` 包成单元素集合"，教师版是"过滤当前教师"。
  **两者等价，但代码形状不一致**（一处是 `Collection<Long> subjects = ...`，
  一处是 `continue`）。**新增 handler 时选一种就好**。
- **`ctx.fixedCountForTeacherOnDay(teacherId, day)`** / **`ctx.freeTeacherSlotsOnDay(teacherId, day)`**：
  都是"该教师这天（跨所有班）"的口径。
- **`HandlerSupport.sumPlus(vars, fixed - max)`**：⚠ **这里用了 `sumPlus` 而不是
  `LinearExpr.newBuilder()...`**（科目版本是手写 builder），**两者产出的表达式相同**
  （`sum(vars) + (fixed-max)`）—— 又是一处形状不一致但语义相同的地方。
  ⚠ **表达式相同不代表 proto 相同**：`sumPlus` 在 `vars` 为空时不 `addSum`、
  在 `extra == 0` 时不 `add`；手写 builder 则总是 `addSum` + `add`。**在 `vars` 非空且
  `fixed != max` 的常见情形下两者等价**。
- **`if (fixed > max) throw`**：与科目版相同（配置矛盾 → 直接报错）。
- 变量名 `tday_exc_{teacherId}_{day}`。

**为什么这条与 `teacher_max_weekly_hours` 并存**（javadoc 第 21–25 行）：
后者是**整周**上限、**数据驱动、不可配置**（来自教师档案）；
这条是**日**上限、**可配置**。日上限是学校常见的诉求："同一个老师一天不要排超过 4 节"。

### 6.8 `TeacherUnavailableHandler` —— 教师不可用时段（92 行）

```java
public static final String TYPE = "teacher_unavailable";
public static final String P_TEACHER = "teacher_id";
public static final String P_DAY = "day";
public static final String P_PERIOD = "period";

@Override public List<RuleParamSpec> params() {
    return List.of(
            RuleParamSpec.intParam(P_TEACHER, "教师 ID", 1, Integer.MAX_VALUE),
            RuleParamSpec.intParam(P_DAY, "周几（1=周一）", 1, 5),
            RuleParamSpec.intParam(P_PERIOD, "第几节（1 开始）", 1, 12));
}

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    long teacherId = params.requireLong(P_TEACHER);
    int day = params.requireInt(P_DAY) - 1;            // ← 1 基 → 0 基
    int period = params.requireInt(P_PERIOD) - 1;      // ← 1 基 → 0 基

    if (!ctx.teacherExists(teacherId)) {
        throw new ConstructionInfeasible("规则引用了不存在的教师 " + teacherId);
    }

    int fixed = ctx.fixedCountForTeacher(teacherId, day, period);
    List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period);

    if (hard) {
        if (fixed > 0) {
            throw new ConstructionInfeasible(
                    "教师 " + teacherId + " 在第 " + (day + 1) + " 天第 " + (period + 1)
                            + " 节既有固定课、又被设置为不可用，配置自相矛盾");
        }
        if (!vars.isEmpty()) {
            model.addEquality(LinearExpr.sum(HandlerSupport.args(vars)), 0);
        }
    } else {
        if (fixed > 0) { penalties.addConstant(1); }
        for (BoolVar v : vars) { penalties.add(v); }
    }
}
```

**逐行与数学含义**：
- **`day = requireInt(P_DAY) - 1`**：⚠ **参数是 1 基（给人看的"周 3"），内部是 0 基**。
  这条边界在**参数契约里**：`intParam(P_DAY, "周几（1=周一）", 1, 5)` 上界是 **5**（不是 4），
  `P_PERIOD` 上界 **12**（不是 11）。**schema 的边界与内部下标差 1**，这是本模块最容易搞混的一对。
- **`if (!ctx.teacherExists(teacherId)) throw ConstructionInfeasible`**：
  ⚠ **把"规则引用了不存在的教师"变成配置错误，而不是无解**（javadoc 第 21–24 行）。
  不做这个检查会怎样：`ctx.fixedCountForTeacher` / `freeTeacherSlots` 对不存在的教师
  只会返回空集合/0，于是"这条规则**完全不起作用**" —— 用户的规则配了、没报错、也没效果。
  **静默失效比抛异常糟得多。**
- **`fixed = ctx.fixedCountForTeacher(teacherId, day, period)`**：该教师这个时段**被固定课占了几次**。
- **硬分支**：
  - **`if (fixed > 0) throw`**：⚠ **"既有固定课又被设为不可用"是配置自相矛盾**：
    固定课一定会出现在课表里（`TimetableExtractor` 直接取它，§3.2），
    而"不可用"要求这个时段不能排他的课 —— 二者不可能同时满足。
    **交给求解器只会得到"无解"**，所以这里直接抛。
    ⚠ **注意文案里 `(day + 1)` / `(period + 1)` 又变回 1 基** —— 因为那是给人看的。
  - **`model.addEquality(sum(vars), 0)`**：**数学含义**：这个时段该教师在**所有班**的成员布尔**全为 0**
    —— 即"这个时段他一节课都不能上"。⚠ **没有 `sumPlus`（不加常数）**：
    这里要的就是"全 0"，而 `fixed > 0` 的情形已经抛异常了，所以不需要常数项。
- **软分支**：
  - **`if (fixed > 0) penalties.addConstant(1)`**：固定课已经在那儿了，**罚 1**（当作"一次违规"）。
  - **`for (BoolVar v : vars) penalties.add(v)`**：**逐个加**（不是 `add(sum(vars))`）。
    ⚠ **两种写法目标值相同**（权重为 1 时，`sum` 的展开与逐项相加一致），
    但**表达式的组织方式不同 → proto 不同 → 指纹不同**。
    逐项加的写法还与 `PenaltyCollector` 的 `unitTerms` 批量 `addSum` 路径有关
    （这里每个 `v` 都进 `unitTerms`，最后被 `addSum` 一起加 —— **所以结果确实一样**）。

### 6.9 `SubjectAvoidLastPeriodHandler` —— 单科不排最后一节（114 行）

```java
public static final String TYPE = "subject_avoid_last_period";
public static final String P_SUBJECT = "subject_id";

@Override public List<RuleParamSpec> params() {
    return List.of(RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）",
            1, Integer.MAX_VALUE));
}

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;

    for (ClassData cd : ctx.classes()) {
        long classId = cd.classId();
        Collection<Long> subjects = only != null ? List.of(only) : ctx.subjectsOf(classId);
        for (long subjectId : subjects) {
            if (!ctx.subjectsOf(classId).contains(subjectId)) { continue; }
            List<BoolVar> lastPeriodVars = new ArrayList<>();
            int fixedViolations = 0;

            for (int day = 0; day < ctx.days(); day++) {
                int periods = ctx.periodsOn(classId, day);
                if (periods <= 0) { continue; }                 // ← 该天没有节
                int last = periods - 1;                          // ← ⚠ 按班按天算
                long fixedSubject = ctx.fixedSubjectAt(classId, day, last);
                if (fixedSubject >= 0) {
                    if (fixedSubject == subjectId) { fixedViolations++; }
                    continue;
                }
                BoolVar b = ctx.subjectAt(classId, day, last, subjectId);
                if (b != null) { lastPeriodVars.add(b); }
            }

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
        }
    }
}
```

**逐行与数学含义**：
- **`int last = periods - 1`**：⚠ **本规则的正确性全在这一行**。
  javadoc 第 23–26 行专门警告："把「最后一节」写成常数（例如永远取第 8 节）
  会在低年级上**完全失效** —— 而且失效得很安静：约束照加，只是加错了位置。"
  因为不同年级 `periods_per_day` 可以不同（低年级 6 节、高年级 8 节）。
- **`if (periods <= 0) continue;`**：⚠ 该天 0 节时 `last = -1` 会越界/取到错误时段。这个守卫是必要的
  （虽然实际数据里"某天 0 节"很罕见，但**从 `periodsPerDay` 的 Map 里缺键会走默认 6**，
  而如果显式填了 `0` 就会中招）。
- **`fixedSubject = ctx.fixedSubjectAt(classId, day, last)`**：
  ⚠ 分两种情况处理：固定课（与决策无关）与自由时段（与决策相关）。
- **`fixedViolations++`**：该天**已经被固定**在最后一节了 → 与决策无关的**违规**。
- **`BoolVar b = ctx.subjectAt(...)`，`if (b != null) lastPeriodVars.add(b)`**：
  收集"该班该天最后一节是这门课"的布尔。⚠ **`b == null` 的可能性**：
  该班没有这个科目（`subjectAt` 第三条返回 null）→ 跳过。
- **硬分支**：
  - **`if (fixedViolations > 0) throw`**：固定课已经把该科目摆在最后一节，
    与"不排最后一节"**直接矛盾** → 抛异常（配置矛盾）。
  - **`model.addEquality(sum(lastPeriodVars), 0)`**：**数学含义**：
    "所有（该班、每天）最后一节的'是该科目'布尔全为 0" —— 即**该科目不出现在任何一天的最后一节**。
- **软分支**：
  - **`penalties.addConstant(fixedViolations)`**：⚠ **常数是"几天"**（不是 1）。
    因为它统计了多天的固定违规，每天算一次违规。
  - **`for (BoolVar b : lastPeriodVars) penalties.add(b)`**：每个"排到最后一节"的布尔罚 1。

### 6.10 `MainSubjectMorningHandler` —— 主科优先上午（117 行）

与 `SubjectAvoidLastPeriodHandler` **结构几乎完全相同**，只是"最后一节"换成了"下午的每一节"。

```java
public static final String TYPE = "main_subject_morning";
public static final String P_SUBJECT = "subject_id";
public static final String P_MORNING = "morning_periods";

@Override public List<RuleParamSpec> params() {
    return List.of(
            RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）", 1, Integer.MAX_VALUE),
            RuleParamSpec.intParam(P_MORNING, "上午到第几节（前 N 节算上午）", 1, 12));
}

@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    Long only = params.has(P_SUBJECT) ? params.requireLong(P_SUBJECT) : null;
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
        }
    }
}
```

**逐行（讲与上一条的差异）**：
- **`morningPeriods` 是"上午到第几节"的绝对节数**（例如 4 表示前 4 节算上午）。
  javadoc 第 26–27 行解释了**为什么用绝对节数而不是"按各班节数取一半"**：
  "约束的语义应该由学校说了算，而不是由算法猜。各班节数不同时，'一半'会让不同年级的上午长度不一致，
  反而制造出难以解释的课表。"
- **`for (int period = morningPeriods; period < periods; period++)`**：
  ⚠ **从 `morningPeriods` 开始**（0 基下标 = 第 `morningPeriods + 1` 节）。
  所以 `morningPeriods = 4` 时，`period ∈ {4,5,6,7}` 被判为"下午" ——
  **前 4 节是上午（下标 0..3），第 5 节起算下午**。这是"绝对节数"的直接含义。
  ⚠ **与 `SubjectAvoidLastPeriod` 的差异**：那条每天只看**一节**，这条每天看**多节**，
  而且**不是**"当天最后 N 节"，而是"第 `morningPeriods` 节之后的所有节"。
  所以 `periods < morningPeriods` 时**内层循环一次都不执行**（自然的守卫）。
- **`fixedViolations++` 的语义是"被固定在下午的**节数**"**（对比上一条是"天数"）——
  文案也相应写"有 N **节**把科目…固定在下午"。
- **硬分支**：`addEquality(sum(afternoonVars), 0)` = "**主科一律不许排在下午**"。
- **软分支**：每节罚 1（更常见的用法，javadoc 第 29 行）。

### 6.11 `TeacherMinimizeGapsHandler` —— 教师课表不留空档（161 行，本模块最复杂的一条）

**javadoc 第 22–51 行把设计完整写出来了**，这是本模块最值得细读的一段。

#### 6.11.1 为什么这条最麻烦

前面的规则都是"把某个变量加起来，和常数比一比"。空档不一样：它是一个**「量的形状」**上的性质
—— 要知道某节课是不是**夹在**"这天第一节课"和"这天最后一节课"之间。

#### 6.11.2 为什么不用常见的 first/last 写法

常见写法是先求 `first` / `last` 再算 `(last-first+1) - 课时数`。但那要引入**最小/最大等式和哨兵**，
还得单独处理"当天没课"（**否则会算出负数**）。这里改用**等价的逐节判定**，O(n) 个辅助变量且不需要哨兵：

```
busy[p]   : 该教师这天第 p 节有课
before[p] : 第 p 节之前有课     = OR(busy[0..p-1])      （递推：before[p] = before[p-1] OR busy[p-1]）
after[p]  : 第 p 节之后有课     = OR(busy[p+1..n-1])    （递推，从后往前）
hole[p]   : before[p] AND after[p] AND NOT busy[p]
空档数     = Σ hole[p]
```

#### 6.11.3 `apply` 与短路

```java
@Override
public void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties) {
    CpModel model = ctx.model();
    Long only = params.has(P_TEACHER) ? params.requireLong(P_TEACHER) : null;

    for (ScheduleInput.TeacherInfo teacher : ctx.teachers()) {
        long teacherId = teacher.id();
        if (only != null && only != teacherId) { continue; }
        for (int day = 0; day < ctx.days(); day++) {
            int maximumLessons = ctx.fixedCountForTeacherOnDay(teacherId, day)
                    + ctx.freeTeacherSlotsOnDay(teacherId, day).size();
            if (maximumLessons <= 1) { continue; }     // 一天最多一节，不可能出现空档
            applyForTeacherDay(ctx, model, penalties, teacherId, day, hard);
        }
    }
}
```

**逐行**：
- **`maximumLessons`**：该教师这天**最多能上几节**（固定课 + 所有可用自由时段）。
- **`if (maximumLessons <= 1) continue;`**：⚠ **短路，而且这是性能上的必要短路**
  （javadoc 第 51 行明说："另外用了「当天最多只能上 1 节就不可能产生空档」做短路，
  能省掉大部分教师"）。
  **逻辑上也完全正确**：只有 0 或 1 节课，不可能形成"夹在中间"的空档
  （与 `TestFixtures.countTeacherGaps` 里第 165–167 行的独立实现一致：`if (periods.size() <= 1) continue;`）。
  ⚠ **不加这个短路会怎样**：每个"教师 × 天"都会建约 4n 个辅助变量，
  即使那位老师那天只有一节课 —— 而那种情况**根本不可能有空档**。
  javadoc 的成本提示说"对 36 教师 × 5 天 × 8 节大约是 5,700 个变量，是这些规则里最贵的一条"，
  短路是抑制这个成本的主要手段。
- **`applyForTeacherDay(...)` 被抽成私有方法**：⚠ **签名里没有 `hard` 之外的上下文**，
  它自己重新算 `n = ctx.maxPeriodsOn(day)`。

#### 6.11.4 `applyForTeacherDay` —— 四组辅助变量

**① `busy[p]`：该教师这天第 p 节有课**

```java
int n = ctx.maxPeriodsOn(day);
if (n <= 1) { return; }
String tag = String.format("tg_%d_%d", teacherId, day);

BoolVar[] busy = new BoolVar[n];
for (int p = 0; p < n; p++) {
    busy[p] = model.newBoolVar(tag + "_busy_" + p);
    int fixed = ctx.fixedCountForTeacher(teacherId, day, p);
    List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, p);
    if (fixed > 0) {
        model.addEquality(busy[p], 1);                                  // ① 固定课 → 恒真
    } else if (vars.isEmpty()) {
        model.addEquality(busy[p], 0);                                  // ② 无处可上 → 恒假
    } else {
        HandlerSupport.encodeOr(model, busy[p], HandlerSupport.literals(vars));  // ③ busy ⟺ OR(vars)
    }
}
```

**逐行与数学含义**：
- **`n = ctx.maxPeriodsOn(day)`**：⚠ **用跨班最大节数**（因为一个教师可能在不同节数的班里上课）。
- **`if (n <= 1) return;`**：n = 0/1 时不可能有空档（第二道守卫，与 `apply` 的短路呼应）。
- **三路分支**：
  - **① `fixed > 0` → `busy[p] = 1`**：⚠ **注意判的是 `> 0` 而不是 `== 1`**。
    `fixed > 1` 意味着固定课冲突，那应该由 `TeacherNoConflictHandler` 抛 `ConstructionInfeasible`
    （而它在 `BuiltinRules` 里排在前面，所以**正常情况下到不了这里**）。
    **这里把 `busy[p]` 钉成 1**（不管几次）—— 对"有没有课"这个问题来说，多几次也还是"有课"。
  - **② `vars.isEmpty()` → `busy[p] = 0`**：这个时段**跨所有班都没有可上的任教关系** → 必定没课。
    ⚠ **这个分支非常关键**：如果不显式钉 0，`busy[p]` 就是一个**完全自由**的布尔，
    求解器可以随意取值（虽然 OR 编码的 ③ 分支不需要它，但这里是"空 OR"）。
  - **③ `encodeOr`**：**`busy[p] ⟺ OR(vars)`**。⚠ **双向等价**（§6.1）：
    `busy[p] = 1` 当且仅当该教师在这个时段的某个班上上课。
    **只写单向会怎样**：`busy` 可以在"有课"时取 0，于是 `before/after` 递推错、
    **空档计数会漏报**（惩罚变小 → 求解器倾向于把课排得零散而不受罚），
    **且不报错、不崩、只是结果变差**。

**② `before[p]`：第 p 节之前有课**

```java
BoolVar[] before = new BoolVar[n];
before[0] = HandlerSupport.boolConst(model, tag + "_bf_0", false);   // ← 第 0 节之前必然没课
for (int p = 1; p < n; p++) {
    before[p] = model.newBoolVar(tag + "_bf_" + p);
    HandlerSupport.encodeOr(model, before[p], new Literal[] {before[p - 1], busy[p - 1]});
}
```

**逐行与数学含义**：
- **`before[0] = boolConst(..., false)`**：⚠ **用"恒假布尔"而不是"跳过第 0 项"** ——
  这样递推就能**统一写成 `before[p] = before[p-1] OR busy[p-1]`**，不用写 `if (p == 1)` 的特例。
  javadoc 第 34 行明确写了这个递推式。
- **`before[p] ⟺ before[p-1] ∨ busy[p-1]`**：展开就是 **`OR(busy[0..p-1])`**
  （归纳法：`before[1] = false ∨ busy[0] = busy[0]`，`before[2] = before[1] ∨ busy[1] = busy[0]∨busy[1]`…）。
  所以"第 p 节之前有课"= **第 0 到 p-1 节里有任何一节有课**。
- **变量名** `tg_{teacherId}_{day}_bf_{p}`。
- **只用 `n-1` 个新变量**（第 0 个是常量）。

**③ `after[p]`：第 p 节之后有课**

```java
BoolVar[] after = new BoolVar[n];
after[n - 1] = HandlerSupport.boolConst(model, tag + "_af_" + (n - 1), false);
for (int p = n - 2; p >= 0; p--) {
    after[p] = model.newBoolVar(tag + "_af_" + p);
    HandlerSupport.encodeOr(model, after[p], new Literal[] {after[p + 1], busy[p + 1]});
}
```

**逐行**：与 `before` **完全镜像**，只是**从后往前**递推，且哨兵在 `n-1`（最后一节之后必然没课）。
展开得到 **`after[p] = OR(busy[p+1..n-1])`**。

**④ `hole[p]`：空档**

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

**逐行与数学含义**：
- **`hole[p] ⟺ before[p] ∧ after[p] ∧ ¬busy[p]`**：
  **"第 p 节没课，但它前面有课、后面也有课"** —— 这正是**空档的定义**。
  ⚠ **三个条件缺一不可**：
  - 少了 `before[p]` → 把"当天第一节课之前"的空位也算成空档（**这不是空档，是上班前**）；
  - 少了 `after[p]` → 把"最后一节课之后"算成空档（**放学后不是空档**）；
  - 少了 `¬busy[p]` → 有课的节次会被算成空档。**这是最容易漏的一个**：
    `Literal[] {before[p], after[p], busy[p].not()}` 里那个 `.not()` 就是它。
- **`encodeAnd`**：双向等价（`hole ⟺ AND`）。同样地，**单向会漏报**。
- **硬**：`sum(holes) = 0` → **一天里一个空档都没有**（课表连续）。
- **软**：每个 `hole` 加 1 分惩罚（更常见的用法）。
- **变量名** `tg_{teacherId}_{day}_hole_{p}`、`tg_{teacherId}_{day}_af_{p}`。

**它天然处理两个边界，不需要额外分支**（javadoc 第 40–45 行）：
- **当天没课**：所有 `busy` 为 0 → `before` 全为 0（递推：`false ∨ 0`）→ 没有 `hole` → **空档数 0** ✅
  （**这正是 first/last 写法容易算出负数的那个坑**）；
- **各年级节数不同**：某节之后没课了，`after` 自然为 0（哨兵在 `n-1`），不会被误判成空档 ✅
  ——⚠ **前提是 `n` 取了 `maxPeriodsOn(day)`**：如果错误地取某个班的节数，
  "该教师在这个节数更多的班里还有课"就会被漏掉，`after` 会错误地变 0。

**成本核算**（javadoc 第 49–51 行）：每个"教师 × 天"约 **`4n` 个辅助变量**
（`busy` n 个 + `before` n 个 + `after` n 个 + `hole` n 个，各有一个哨兵是常量，所以略少于 4n）。
对 36 教师 × 5 天 × 8 节大约 **5,700 个变量**，是这些规则里最贵的一条，
**因此只在规则集里显式配置时才生效**（它是 `mandatory() == false`，不在 `legacyEquivalent` 里）。

---

## 7. 本篇小结

### 7.1 一张图看懂编译过程

```
ScheduleInput + List<ConstraintRule>
        │
        ├─ SlotVariableIndex.create(model, input)        ← engine：建索引（固定课展开校验）
        │      └─ 变量「懒创建 + 缓存」：slotVar / subjectAt / teacherAt
        │
        ├─ new RuleContext(index)                        ← rule：规则作者的世界（固定课记账收敛处）
        ├─ new PenaltyCollector(model)                   ← rule：软约束收集器
        │
        ├─ registry.mandatory()  → apply(ctx, empty, hard=true)     ← 3 条不变量/数据驱动
        │
        ├─ rules（enabled、非 mandatory）
        │      ├─ rule.params().validate(handler.params())          ← 编译期再校验一次
        │      ├─ penalties.useWeight(rule.effectiveWeight())
        │      └─ handler.apply(ctx, params, !rule.isSoft(), penalties)  ← 7 条可配置
        │
        ├─ penalties.commit()                            ← 唯一一次 model.minimize(...)
        │
        └─ CompiledModel(model, index, ctx, modelStats, fingerprint)
```

### 7.2 这个模块守的六件事

| 守什么 | 靠什么 |
|---|---|
| **模型可复现** | 全程 `LinkedHashMap` / `TreeSet` / `ArrayList`，不用 `HashMap`/`HashSet`；变量名固定模板 |
| **模型不漂移** | 变量创立顺序 = 注册顺序；`GoldenModelTest` 断言变量数、指纹、各类约束计数 |
| **固定课记账只写一次** | `RuleContext` 的 `fixed*` / `free*` 两组方法（原 Python 散落 39 处） |
| **变量不重复** | 成员布尔懒创建 + 缓存 ⇒ 两条教师规则共用一批（13,452 → 9,676，−28.1%） |
| **配置错误 ≠ 排课无解** | 7 处 `throw new ConstructionInfeasible(...)`，文案指出具体哪条配置打架 |
| **超时 ≠ 无解** | `SolveOutcome.status` 带出 `CpSolverStatus`（`timedOut()` 只认 `UNKNOWN`） |

### 7.3 本篇记录的全部 ⚠ 陷阱（按"会怎么坑你"排序）

| # | 陷阱 | 后果 | 在哪 |
|---|---|---|---|
| 1 | **`model_fingerprint` 不能当跨语言等价判据** | 两边 `model_stats()` 一字不差、指纹却不同（`objective.scaling_factor` 1.0 vs 0.0） | §3.6、[踩坑总结.md](踩坑总结.md) E2 |
| 2 | **JDK 23 上 `solve()` 段错误**（建模/指纹全正常） | 误判成"模型写错了"；**JDK 21 LTS 正常** | §3.5、[踩坑总结.md](踩坑总结.md) E3 |
| 3 | **`slotVar` 定义域上界是 `n`（不是 `n-1`）** | 那个多出来的值就是**空堂哨兵**；改成 `n-1` 会强制上满每一节 | §3.1.6 |
| 4 | **`membership` / `encodeOr` / `encodeAnd` 必须双向** | 只写正向 ⇒ 布尔取值自由 ⇒ **软约束被静默逃避**（罚分算不对） | §3.1.6、§6.1、§6.6、§6.11 |
| 5 | **`-1` 是"不是固定课"的哨兵** | `fixedSubjectAt` / `fixedTeacherAt` 用 `>= 0` 判断，判错就整条规则失效 | §4.6 |
| 6 | **"最后一节"必须按班按天算**（`periodsOn(classId, day) - 1`） | 写成常数会**安静地失效**：约束照加，只是加错位置 | §6.9、§3.1.4 |
| 7 | **`ctx.classes()` 每次调用都新建列表** | 高频调用会反复分配（当前规模可忽略） | §3.1.4 |
| 8 | **`SlotVariableIndex.lastPeriod` 名字叫 last 其实返回"节数"** | 当作"最后一节下标"用会多算一节 | §3.1.4 |
| 9 | **教师成员布尔必须从 `ctx` 拿，不能自己 `newBoolVar`** | 自己造就等于又建一套 ⇒ 变量数涨回去、指纹变、测试红 | §3.1.7 |
| 10 | **`RuleParams` 对 `Double` 静默取整**（`n.longValue()`） | `{"max": 1.9}` → `1`，不报错 | §4.4 |
| 11 | **`Boolean.parseBoolean` 对 `"yes"` 返回 false** | 取值阶段静默取假；靠 `validate` 的 BOOL 分支拦住 | §4.4 |
| 12 | **`weight <= 0` 会被兜底成 1**（两处：`effectiveWeight()` 与 `useWeight()`） | 兜底是**有意的**：权重 0 会让软约束"免费"⇒ 配了却不起作用 | §4.1、§4.7 |
| 13 | **`SubjectNoConsecutiveHandler` 忽略 `hard` 参数** | 配成硬约束时**实际按软约束执行**（罚分而非禁止），**静默偏差** | §6.6 |
| 14 | **`SubjectWeeklyHoursHandler` 在 `remaining <= 0` 时直接跳过** | 固定课恰好排满时**允许自由时段再排该科目**（真实宽松点，无异常） | §6.3 |
| 15 | **`fixed > 1` 意味着"固定课之间就打架了"** | 不抛异常会得到一个看不懂的 `INFEASIBLE` | §6.2、§3.7 |
| 16 | **实验用 `SolverSettings` 与 `production()` 不是一个值** | 测试 20s/1 worker、生产 60s/2 workers；`production()` 实测**本模块内无调用者** | §3.4 |
| 17 | **`ConstructionInfeasible` 会从 `OrbitSolver.compile` 冒出去** | `JobRunner` 的兜底 catch 把它标成**可重试**，与"配置错误不该重试"的直觉相反 | §3.7 |
| 18 | **`RuleContext.teacherWeeklyLimit` / `HandlerSupport.negations` / `OrbitSolver` 的 `withBuiltinRules` 实测无调用者** | 死代码（改写代码时不要以为它们在生效） | §4.6、§6.1、§5.1 |
| 19 | **`RuleContext.fixedCountForSubject` 第 122 行有未使用变量** | 无害，但读的时候别以为它在参与计算 | §4.6 |
| 20 | **`TeacherWeeklyLimitHandler.TYPE = "teacher_max_weekly_hours"` 与类名不同** | 改类名不影响数据，**改 `TYPE` 常量等于要求迁移数据库** | §5.3 |
| 21 | **`ScheduleInput` 的 record 组件顺序是位置化构造的契约** | 重排字段后 Java 仍可能编译通过，但 fixture/快照会静默错位 | §2.1 |

> **"实测"标记的来源**：本篇的实测数字与结论都来自本仓库里已有的基线，不是推测 ——
> [GoldenModelTest](../../backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/GoldenModelTest.java)
> （13,452 / 9,676 / −3,776 / −28.1% / 两个指纹 / 各类约束计数）、
> [Java建模等价性验证报告.md](Java建模等价性验证报告.md)（proto 逐字段比对、`scaling_factor`、JNI 崩溃）、
> [踩坑总结.md](踩坑总结.md) E1–E3、以及各 `*Test` 里的断言。
> 凡是**没有**这些依据的地方，本篇一律写「此处未完全确认 / 此处未完全理解」，**没有编造**。

### 7.4 想改这个模块时，先看哪张清单

| 你想做的事 | 必须先看 |
|---|---|
| 新增一种规则 | `RuleHandler` 接口 → 一个现成 handler（推荐抄 `SubjectAvoidLastPeriodHandler`，它最短且带固定课处理）→ `BuiltinRules` 登记一行 → 检查是否需要进 `RuleSets` |
| 改一条规则的建模方式 | 改完**立刻跑 `GoldenModelTest`**：红了是**正常的**，但必须**解释清楚**为什么模型该变 |
| 改 `RuleContext` / `SlotVariableIndex` | 同上。这两处任何改动都会改模型 ⇒ 指纹必变 |
| 改规则参数 | 只需改 `handler.params()` —— 前端表单与助手工具**自动跟上**（一份 schema 三处消费）。⚠ 但**老数据可能因此变非法**，`RuleCompiler` 的编译期校验会拦住它 |
| 把规则从软改硬（或反之） | 检查该 handler 的 `apply` **是否真的实现了两条路** —— 已知 `SubjectNoConsecutiveHandler` 只实现软（§6.6 陷阱 13） |
| 排查"排不出来" | ① 先看异常文案（`ConstructionInfeasible` 会说是哪条配置）；② 再看 `SolveOutcome.status` 是 `INFEASIBLE` 还是 `UNKNOWN`；③ 最后才看 `modelStats` |
