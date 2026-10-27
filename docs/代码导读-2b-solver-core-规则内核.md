# 代码导读 · 2b. `solver-core` 规则内核

> 本册范围：`backend-java/orbit-solver-core/src/main/java/com/orbit/solver/core/rule/`（**不含** `handler/` 子目录），共 **7 个类**：
> `ConstraintRule`、`RuleHandler`、`RuleParamSpec`、`RuleParams`、`RuleRegistry`、`RuleContext`、`PenaltyCollector`。
> 上游：[代码导读.md](代码导读.md)（第 1 册，格式基准）
> 下游（**不在本册范围**，由别人负责）：`rule/handler/**` 十个实现类、`compile/BuiltinRules`、`compile/RuleCompiler`、`engine/SlotVariableIndex`。

## 本模块在系统里的位置

这 7 个类是**规则系统的"接口层"**，不含任何一条具体规则。它们回答四个问题：

1. **一条规则长什么样**（`ConstraintRule` = 数据、`RuleHandler` = 实现）；
2. **参数怎么声明、怎么校验**（`RuleParamSpec` + `RuleParams` —— 全项目最关键的复用点）；
3. **编译器怎么找到实现**（`RuleRegistry`）；
4. **实现写规则时手里有什么**（`RuleContext` 看课表、`PenaltyCollector` 交惩罚项）。

调用关系（全部已在源码里核实）：

```
scheduling/RuleSetService  ──读 constraint_rules 表──▶ List<ConstraintRule>
scheduling/RuleTypeCatalog ──BuiltinRules.registry()──▶ RuleRegistry ──▶ RuleTypeResponse（HTTP 投影）
                                                             │
OrbitSolver.compile(input, rules)                             │
   └─▶ compile/RuleCompiler ──new RuleContext(SlotVariableIndex)
                            ──new PenaltyCollector(model)
                            ──registry.mandatory() → handler.apply(..., hard=true, ...)
                            ──registry.get(type)   → RuleParams.validate(handler.params())
                                                   → handler.apply(ctx, params, !isSoft(), penalties)
                            ──penalties.commit()   → model.minimize(...)
```

**一句话**：本包是 `rule/handler/**` 与 `compile/**` 之间的**契约**。`rule` 包刻意不认识 `rule.handler`（注册表不 `new` 实现），装配知识全部收在 `compile/BuiltinRules` 一处。

## 阅读顺序建议

1. **`ConstraintRule`（36 行）→ `RuleHandler`（46 行）** —— 先建立「声明 / 实现」两级结构，这是整套设计的骨架；
2. **`RuleParamSpec` + `RuleParams`** —— 再看参数（一份 schema 驱动三处消费），这是全项目复用度最高的地方；
3. 有了上面三块，`RuleRegistry`（68 行）几乎不用读；
4. **最后读 `RuleContext` + `PenaltyCollector`** —— 它们只有真正写 handler 时才需要细节，但 `RuleContext` 里"固定课收敛到一处"的设计动机值得先看类注释。

---

### 2b.1 `ConstraintRule` —— 一条**生效的规则实例**（36 行）

**一句话定位**：`constraint_rules` 表里一行的内存形态；`scheduling` 服务负责把实体映射成它，**求解进程只认这个**，两边不共享实体类。

**谁调它**：

| 上游 | 做什么 |
|---|---|
| `scheduling/rule/RuleSetService`（`toConstraintRules`） | 从 `ConstraintRuleEntity` 逐字段搬过来，得到 `List<ConstraintRule>` |
| `scheduling/rule/RuleTrialService` | 「试算影响」时用 `new ConstraintRule(...)` 拼一条草稿规则塞进规则集 |
| `compile/RuleSets.legacyEquivalent()` | 用工厂方法写死"与 Python 等价的那两条软规则" |
| `solver-core` 全部测试类 | 用 `ConstraintRule.hard(...)` / `.soft(...)` 造输入 |

**下游调谁**：它自己不调任何东西 —— 是纯数据。被 `RuleCompiler.compile()` 读过四个成员（`enabled` / `type` / `params` / `isSoft` / `effectiveWeight`）。

#### 声明

```java
public record ConstraintRule(
        String type,
        RuleParams params,
        Hardness hardness,
        int weight,
        boolean enabled,
        String description) {

    public enum Hardness { HARD, SOFT }
```

| 字段 | 含义 | ⚠ 注意 |
|---|---|---|
| `type` | 规则的稳定标识，**必须能命中 `RuleRegistry`** | 命中不了 → `registry.get()` 抛 `未知的规则类型`（见 2b.4） |
| `params` | 参数值（来自 `constraint_rules.params` JSON） | `hard()` / `soft()` 都**不做校验**，校验发生在 `RuleCompiler`（见 2b.3） |
| `hardness` | `HARD` = 加约束；`SOFT` = 加惩罚项 | 枚举只有两个值，没有 `null` 的语义位 |
| `weight` | 软约束的权重 | **只对软约束有意义**；`hard()` 固定写 1 |
| `enabled` | 该行是否参与本次求解 | `RuleCompiler` 第一步就 `if (!rule.enabled()) continue;` |
| `description` | 规则集里显示的人话 | 允许为 null；`RuleService` 在为空时会回退到 `handler.describe(params)` |

**为什么是 `record`**：它跨进程/跨服务传递（`scheduling` 组装 → `solver-core` 求解），必须是**不可变值对象**，否则"求解过程中规则集被改"会变成极难查的问题。`record` 自带 `equals`/`hashCode`，也让测试里的断言和规则集哈希（`canonicalHash`）好写。

#### `static ConstraintRule hard(...)` 与 `static ConstraintRule soft(...)`

| 工厂 | 行为 |
|---|---|
| `hard(type, params, description)` | `new ConstraintRule(..., Hardness.HARD, 1, true, ...)` —— 硬约束**不用权重**，但字段没有 nullable 语义位，就写常量 **1** 占位 |
| `soft(type, params, weight, description)` | `new ConstraintRule(..., Hardness.SOFT, weight, true, ...)` —— 带上真权重 |

两个工厂的 `enabled` 都写 **true**：

> ⚠ **没有 `enabled=false` 的入口**。「关掉某条规则」在 Java 侧不是靠构造一个 `enabled=false` 的对象，而是**规则集里根本没有这一行** —— `RuleSetService.enabledRows()` 的 SQL 里已经 `eq(enabled, true)`。所以 `enabled` 在求解进程里**几乎总是 true**，`RuleCompiler` 那句 `if (!rule.enabled()) continue;` 是**防御性**的（给"手工拼规则集 / 试算"这类内部调用兜底）。
>
> ⚠ **工厂方法是"不用构造器"的纪律所在**：你可以 `new ConstraintRule("x", p, Hardness.HARD, 5, true, "d")`，那个 `5` 会被**静默忽略**（硬约束的 `weight` 从头到尾没人读）。`hard()` 把「硬约束权重是 1」这个约定**编码进代码**，而不是写在注释里。

#### `boolean isSoft()`

`return hardness == Hardness.SOFT;` —— 一句话。**唯一调用点**是 `RuleCompiler` 的 `handler.apply(ctx, rule.params(), !rule.isSoft(), penalties)`，即 **`hard` 参数就是 `!isSoft()`**。`apply` 只收 `boolean hard`、不收 `Hardness` 枚举，是为了让 handler 作者少认识一个类型（见 2b.2 的取舍）。

#### `int effectiveWeight()`

```java
return weight <= 0 ? 1 : weight;
```

> ⚠ **为什么需要它而不直接用 `weight`**：`weight` 来自数据库（`constraint_rules.weight`），可能是 `NULL`（`RuleSetService` 已把 null 兜成 1）也可能是 0 或负数（历史数据 / 手工 SQL）。**权重 0 的后果是这条软规则"完全不起作用"，但界面上它明明存在** —— 用户会以为规则生效了、只是求解器没做到。所以这里把 `<= 0` 一律抬到 **1**：**宁可权重比预期大，也不让它静默失效**。

**谁调它**：`RuleCompiler`（`penalties.useWeight(rule.effectiveWeight())`）与 `RuleSetService`（首次初始化写库 / 算规则集哈希时落权重）。

---

### 2b.2 `RuleHandler` —— 一种规则的**实现**（46 行）

**一句话定位**：`rule_type` 指向的那份"怎么做"的代码，由开发者写一次、系统重复用。

**谁调它**：

| 上游 | 调它的什么 |
|---|---|
| `compile/RuleCompiler.compile()` | `mandatory()`、`apply()`、`params()`（校验用） |
| `compile/BuiltinRules.registry()` | `new` 出十个实现并交给 `RuleRegistry` |
| `scheduling/rule/RuleTypeResponse.of()` | `displayName()`、`describe()`、`mandatory()`、`params()` |
| `scheduling/rule/RuleTypeCatalog.configurable()` | `mandatory()`（筛掉不变量） |
| `scheduling/rule/RuleService` / `RuleTrialService` | `displayName()`、`describe(params)`（补默认描述文案） |

**下游调谁**：实现类在 `apply` 里通过 `RuleContext` 拿变量、往 `PenaltyCollector` 交惩罚项。**接口本身不认识这两者的内部**。

#### 为什么「一个规则 = 声明 + 实现」要分成两个类

这是本包最重要的设计决策，理由有三层：

1. **变更频率与作者不同**：`ConstraintRule` 的实例**每天在变**（用户在页面上配规则、改参数、调权重）；`RuleHandler` 的实现**几个月才写成一次**。混成一个类，等于每配一条规则都要 new 一份代码逻辑。
2. **数据要能落库、能跨进程传**：规则集存的是 `rule_type = "subject_max_per_day"` + `params = {"max":1}` —— **字符串和 JSON**。实现既不能序列化也不需要序列化，它只需要一个 `type → 实现` 的查表（`RuleRegistry`）。
3. **AI 助手只能生成数据，不能生成代码**：助手能产出的是「`subject_max_per_day`，`max=1`」这种**数据**；能不能安全落库，取决于实现侧**早就写好的** `params()` schema（见 2b.3）。"助手只填数据"这条边界，正因为实现与数据分离才成立。

所以：**用户编辑数据，开发者写实现，`type` 是两者之间的唯一接缝。**

#### `String type()`

稳定标识，会写进数据库的 `rule_type` 列。实现类都写成 `public static final String TYPE = "teacher_no_conflict";` + `public String type() { return TYPE; }`。

> ⚠ **`TYPE` 是数据库里的字面量，改它等于改数据契约**：库里已有的 `constraint_rules.rule_type` 会全部变成"未知的规则类型"。所以它必须是**不能随手重构的常量** —— 这也正是每个 handler 都把它提成 `public static final String TYPE` 的原因：让测试和默认规则集（`RuleSets`）引用**同一个常量**，而不是各自再抄一遍字符串。

#### `String displayName()` / `List<RuleParamSpec> params()`

`displayName()` 是面向用户的中文名（例：`"教师时段不冲突"`），给规则类型下拉列表用。

`params()` 是参数声明 —— **同时供后端校验、前端表单、助手工具定义使用**。不变量的实现返回 `List.of()`（例：`TeacherNoConflictHandler`）。展开见 2b.3。

#### `default boolean mandatory() { return false; }`

不变量：**始终生效，不可被规则集关闭**。

> ⚠ **这一档收了两类其实不同的东西**（阅读源码时最容易混）：
>
> | 类别 | 例子 | 参数从哪来 |
> |---|---|---|
> | 真·不变量 | `teacher_no_conflict`（同一教师同一时段不能上两个班） | 无参数 |
> | **数据驱动**约束 | `subject_weekly_hours`（周课时数）、`teacher_weekly_limit`（教师周上限） | 参数**直接从快照读**（`ScheduleInput`），不经用户配置 |
>
> 共同点是：**课表违反了就是错的**，用户不该有机会关掉 —— 所以合并成同一档。
>
> 机制在 `RuleCompiler`：`mandatory()` 为真的 handler 被编译器**自动施加**，收到的是 `RuleParams.empty()` + `hard=true`（`for (handler : registry.mandatory()) handler.apply(ctx, RuleParams.empty(), true, penalties)`）；并且**当规则表里也出现同一个 type 时会被跳过**（`if (handler.mandatory()) continue;   // 不变量不接受外部配置，避免被规则表重复施加`）。第三道保险在写入侧：`RuleSetService.rejectMandatory()` 直接 400 拒绝把不变量写进规则表。

**为什么是 `default` 而不是抽象方法**：十个实现里只有 3 个是 `mandatory`。写成抽象方法，另外 7 个都要写一遍 `return false;`。`default` 让"普通规则"的实现体干净。

#### `default String describe(RuleParams params) { return displayName(); }`

面向用户的一句话说明（规则列表里显示）。**默认就是 `displayName()`**；需要把参数念出来的实现才覆写 —— `TeacherUnavailableHandler.describe` 把"周几 / 第几节"翻成人话，`TeacherWeeklyLimitHandler.describe` 把上限数字念出来。

**谁调它**：`RuleService`（`description` 为空时的兜底）、`RuleTrialService`（试算时生成说明）。`scheduling` 侧的注释点明了用意：**"`describe()` 会用当前参数生成说明，所以即使没人填 `description`，界面也不会只显示一个干巴巴的规则名"**。

> ⚠ 它是**纯展示**的：返回值不参与求解、也不参与规则集哈希（哈希取的是实体字段）。想改文案就放心改，不会让模型指纹变化。

#### `void apply(RuleContext ctx, RuleParams params, boolean hard, PenaltyCollector penalties)`

把这条规则编译进模型。**这是接口里唯一有副作用的方法**（往 `CpModel` 加约束、往 `PenaltyCollector` 加惩罚项）。

| 参数 | 含义（照抄 javadoc） |
|---|---|
| `ctx` | 规则作者看到的"课表世界"（变量索引、班级/教师数据、固定课计数） |
| `params` | 本条的参数值 |
| `hard` | **true = 硬约束（加约束），false = 软约束（加惩罚项）** |
| `penalties` | 软约束的惩罚项收集器；**`hard` 为 true 时不应使用** |

> ⚠ **`hard` 为 true 时用 `penalties` 是"未定义行为"**，而接口**没有任何机制能阻止它**（无断言、无异常）。后果有两层：`penalties.commit()` 在 `RuleCompiler` 里是**无条件**调用的，所以"硬约束偷偷加了惩罚项"会**静默改变目标函数**；更坏的是见 2b.6 —— **只要最终有惩罚项，`commit()` 就会调 `model.minimize(...)`**，一份"本该只求可行解"的模型被迫去优化。
>
> 这条纪律靠 handler 自己守：`if (hard) { model.addLessOrEqual(...); } else { penalties.add(...); }` 是每个 handler 的固定骨架（十个实现无一例外）。**写新规则时请照抄这个骨架。**
>
> ⚠ **为什么是 `boolean hard` 而不是 `Hardness` 枚举**：接口刻意只暴露一个布尔，让 handler 作者不用 import `ConstraintRule.Hardness`。代价是**丢掉了"既不是硬也不是软"的扩展位** —— 目前不需要，就先不加。

> ⚠ **一处冗余**（不是坑，但读源码时会疑惑）：文件开头的 `import com.orbit.solver.core.rule.PenaltyCollector;` 与 `import com.orbit.solver.core.rule.RuleContext;` 是**多余的** —— 这两个类与本接口**同包**，删掉不影响编译。看到它们不必联想"是不是另一个包里的同名类"。

---

### 2b.3 `RuleParamSpec` + `RuleParams` —— 参数 Schema 与参数值（43 + 130 行）

**这两节是本册的重点**：同一份 Schema 驱动**前端规则配置表单 / 后端参数校验 / 将来的 AI 助手工具定义**。这也是本项目"复用"做得最彻底的一处。

#### 2b.3.1 `RuleParamSpec` —— 单个参数的声明

```java
public record RuleParamSpec(
        String name,
        ParamType type,
        boolean required,
        Long min,
        Long max,
        List<String> enumValues,
        String label) {

    public enum ParamType { INT, LONG, BOOL, STRING, ENUM }
```

**谁调它**：每个 `RuleHandler.params()` 构造它；`RuleParams.validate(specs)` 消费它；`scheduling/rule/RuleParamResponse.of()` 把它**逐字段投影**成 HTTP DTO。

**类注释写明了它为什么值得单独存在**：`一份 schema 同时驱动三处用途，这是规则系统的关键设计：① 后端保存规则时做参数校验；② 前端按它动态渲染表单（不需要为每个规则手写页面）；③ 助手的工具定义（新增规则类型时助手自动会用，无需改 prompt）`。

这三处在下游都真实存在（**已核实**）：前端表单的出口是 `RuleTypeResponse`/`RuleParamResponse`（`RuleTypeCatalog` 里那张 ASCII 图 —— `RuleHandler.params() → RuleParamSpec → {前端表单, 后端校验, 助手工具}`）；后端校验的入口是 `RuleSetService.validateParams` 与 `RuleCompiler`；助手侧对应 P4/P5 的规划。

#### 逐字段：Schema 支持哪些类型与约束

| 字段 | 类型 | 含义 | 哪些 `ParamType` 会用它 |
|---|---|---|---|
| `name` | `String` | 参数名，**就是 `params` JSON 里的 key**（如 `"max"`、`"subject_id"`） | 全部 |
| `type` | `ParamType` | `INT` / `LONG` / `BOOL` / `STRING` / `ENUM` | 全部 |
| `required` | `boolean` | 必填与否 | 全部 |
| `min` | `Long`（可 null） | 下界（**闭区间**，`n < min` 才报错） | 只有数值类型 |
| `max` | `Long`（可 null） | 上界（闭区间，`n > max` 才报错） | 只有数值类型 |
| `enumValues` | `List<String>` | 允许的取值集合 | 只有 `ENUM` |
| `label` | `String` | 中文名，**直接给前端当表单标签**，也进错误文案 | 全部 |

> ⚠ **`min` / `max` 用包装类型 `Long` 而不是 `long`**：因为 `BOOL` / `STRING` / `ENUM` 的参数**根本没有范围**，必须有"不适用"的表达位。同理 `enumValues` 用一个**空列表**而不是 null 表示"不是枚举"（工厂方法里统一写 `List.of()`），这样 `validate` 里可以无脑 `contains` 而不用先判空。

> ⚠ **`INT` 与 `LONG` 在语义上没有任何区别**：`RuleParams.validate` 里它们是同一个 `case INT, LONG ->` 分支，都走 `requireLong` + `min`/`max`。区分它们**纯粹是为了给前端/助手一个提示**（"这个数小，用 int；这个数可能是 ID，用 long"）。目前内置的十个 handler **没有一个用到 `LONG`**（都是 `intParam` / `optionalInt`）。看到 `LONG` 不用去找"它在哪被特殊处理"——没有。

#### 四个工厂方法（**Schema 的唯一构造入口**）

| 工厂 | 产出 | 说明 |
|---|---|---|
| `intParam(name, label, min, max)` | `(INT, required=true, min, max)` | 必填整数，**签名强制**必须给 `[min, max]` |
| `optionalInt(name, label, min, max)` | `(INT, required=false, min, max)` | 可省略的整数。**"留空表示所有科目/所有教师"就是靠它**："留空"在存储层等于"这个 key 不存在"，于是 `required=false` 恰好表达了"不限" |
| `enumParam(name, label, values)` | `(ENUM, required=true, null, null, List.copyOf(values))` | 必填枚举 |
| `boolParam(name, label)` | `(BOOL, required=false, null, null)` | 布尔，**没有必填版本** —— 布尔有天然默认值，`RuleParams.boolOr(name, fallback)` 提供兜底 |

> ⚠ **`enumParam` 里的 `List.copyOf(values)` 不是多余的**：`RuleParamSpec` 是 `record`，**默认不做防御性拷贝** —— 构造器里存的就是调用方那个 `List` 的**引用**。调用方传一个可变的 `ArrayList` 并在之后改它，这份 Schema 会**悄悄变形**（前端拿到的枚举项跟着变、校验结果跟着变）。`List.copyOf` 同时保证了不可变性与 null 检查。
>
> ⚠ **但 `intParam` / `optionalInt` / `boolParam` 里的 `List.of()` 是编译期常量集合，没有这个问题** —— 这三处**不需要**拷贝。看着"风格不一致"，其实只有 `enumParam` 必须拷。
>
> ⚠ **`BOOL` 与 `STRING` 没有专属工厂**：想声明必填字符串只能 `new RuleParamSpec(name, ParamType.STRING, true, null, null, List.of(), label)`。这不是遗漏 —— 当前没有任何规则需要"用户填一个字符串"，所以没为它造工厂；**要用的时候直接 new**（public record 的规范构造器）。

#### 一个真实的用法（三处消费的源头）

`SubjectMaxPerDayHandler.params()` 就两行：

```java
RuleParamSpec.optionalInt(P_SUBJECT, "科目 ID（留空表示所有科目）", 1, Integer.MAX_VALUE),
RuleParamSpec.intParam(P_MAX, "一天最多几节", 1, 20)
```

**同一份声明同时产生**：前端渲染两个输入框（`科目 ID` 可留空、`一天最多几节` 必填 + 范围 1~20）；后端 `validate` 校验 `max ∈ [1,20]`、`subject_id` 若给了则 `∈ [1, INT_MAX]`；助手生成工具定义（一个可选 integer + 一个必填 integer）。

**所以"加一条新规则"= 写一个 handler（含 `params()`）+ 在 `BuiltinRules` 登记一行**，前端与助手都不用改。这正是 `RuleTypeCatalog` 类注释里"如果前端自己维护一份表单定义，那么'开发者加了一条新规则'和'前端能配这条规则'就会变成两件事 —— 而它们本该是同一件事"的兑现。

#### 2b.3.2 `RuleParams` —— 参数值（取值 + 校验）

```java
public record RuleParams(Map<String, Object> values) {
```

**类注释里的两句设计原则**：`取值时对类型宽容（JSON 反序列化后数字可能是 Integer 也可能是 Long），校验时对范围严格`。展开就是：**取值宽容** —— `requireLong` 对 `Number` 直接 `longValue()`，对别的类型先 `String.valueOf(...).trim()` 再 `Long.parseLong`，所以 `1`、`1L`、`"1"`、`" 1 "` 都能取出 `1`；**校验严格** —— `required`/`min`/`max`/`enumValues` 一条不放过（见下面的 `validate`）。

#### 紧凑构造器

```java
public RuleParams {
    values = values == null ? Map.of() : new LinkedHashMap<>(values);
}
```

| 分支 | 为什么 |
|---|---|
| `values == null → Map.of()` | 库里 `params` 列可能是 NULL（或 JSON 里没有参数）。**让"没参数"和"空参数"是同一个东西**，调用方永远不必判空 |
| 否则 `new LinkedHashMap<>(values)` | **防御性拷贝 + 保序**。拷贝：调用方（`RuleSetService` 里那个实体对象上的 Map）后续被改，不影响已经建好的 `RuleParams`；保序：`LinkedHashMap` 保持 JSON 里的顺序，让错误文案、未来的"参数快照比较"稳定可复现（与 `SlotVariableIndex` 明确"不使用 `HashMap`"是同一条纪律） |

> ⚠ 拷贝是**浅拷贝**：如果某个参数值是**可变的 `List`/`Map`**（内置规则目前都是标量，但 Schema 理论上允许），改它仍会穿透。当前无此用法，记录在此以免将来误判"`RuleParams` 一定是深不可变的"。

#### 两个工厂

| 方法 | 用途 |
|---|---|
| `static RuleParams empty()` | `RuleParams(Map.of())`。**不变量规则被自动施加时用的就是它**（`RuleCompiler` 里 `handler.apply(ctx, RuleParams.empty(), true, penalties)`） |
| `static RuleParams of(Map<String, Object> values)` | 从库里的 `params` JSON / 测试造的 Map 构造。**允许传 null**（紧凑构造器兜住） |

#### 取值方法组（"宽容"的一半）

| 方法 | 缺失参数时 | 非数字值 | 备注 |
|---|---|---|---|
| `boolean has(String name)` | 返回 false | —— | 判定标准是 `values.get(name) != null`，**空字符串算"有"** |
| `long requireLong(String name)` | **抛 `IllegalArgumentException("规则参数缺失: " + name)`** | 抛 `IllegalArgumentException("规则参数 " + name + " 不是整数: " + v)` | 先 `Number` 分支，再字符串解析 |
| `long longOr(String name, long fallback)` | 返回 fallback | 抛异常 | `has(name) ? requireLong(name) : fallback` |
| `int requireInt(String name)` | 抛异常 | 抛异常 | `Math.toIntExact(requireLong(name))` —— **超 int 范围抛 `ArithmeticException`**，不是静默截断 |
| `int intOr(String name, int fallback)` | 返回 fallback | 抛异常 | |
| `boolean boolOr(String name, boolean fallback)` | 返回 fallback | **不抛**：`Boolean.parseBoolean` 对 `"yes"` 之类的非 `"true"` 串返回 **false** | 见下面的 ⚠ |
| `String stringOr(String name, String fallback)` | 返回 fallback | —— | `String.valueOf(v)`，数字也能当字符串取 |

> ⚠ **`boolOr` 是最"宽容"的一个：它不会报错，只会给出 `false`。** `Boolean.parseBoolean("yes")` / `("1")` / `("是")` **全部返回 false**（只有忽略大小写的 `"true"` 才返回 true）。后果是**取值这条路会把一个"写错的真值"静默当成 false** —— 所以"用户填的 `true/false` 是否合法"**必须靠 `validate` 拦**（见下），不能指望 `boolOr`。`RuleParamsValidationTest` 里专门有一条 `布尔校验` 用例（`"TRUE"` 通过、`"yes"` 被拒）就是这个原因。

> ⚠ **缺失参数时"抛异常"还是"给默认值"由调用方选**：`require*` 抛、`*Or` 给默认值。**没有"取不到就返回 0"的版本** —— 这是刻意的：测试里明确断言 `RuleParams.empty().requireInt("max")` **抛异常**而不是返回 `0`，因为返回 0 会让"漏配参数"表现为"规则看起来生效了但行为为零"。

#### `List<String> validate(List<RuleParamSpec> specs)` —— 校验（"严格"的一半）

返回**错误说明列表，空列表表示通过**。逐段讲：

```java
for (RuleParamSpec spec : specs) {
    if (spec.required() && !has(spec.name())) {
        errors.add("缺少必填参数 " + spec.name() + "（" + spec.label() + "）");
        continue;
    }
    if (!has(spec.name())) {
        continue;                       // 可选项没填 → 放过
    }
    Object raw = values.get(spec.name());
    switch (spec.type()) { ... }
}
```

| 步骤 | 行为 | 设计要点 |
|---|---|---|
| ① 必填缺失 | 加一条错误，**`continue`** | `continue` 很关键：不再对"不存在的值"做类型/范围判断，避免同一参数报两条错 |
| ② 可选项缺失 | `continue` | **"留空表示所有"就靠这里放行**。注意：可选项缺失**不算错**（测试 `必填与可选的区分` 明确断言 `subject_id` 缺失不报错） |
| ③ 取原始值 | `Object raw = values.get(...)` | 注意用的是**原始 `Object`** 而不是 `requireLong` 的返回值 —— 因为要先看类型对不对 |

**数值分支** `case INT, LONG ->`：

```java
if (!(raw instanceof Number)) {
    try {
        Long.parseLong(String.valueOf(raw).trim());
    } catch (NumberFormatException e) {
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
```

- 不是 `Number` 就**试一次字符串解析**："`"3"` 应被当作整数接受"（JSON 反序列化后数字可能是 Integer / Long / String，测试里明确覆盖）；
- 解析失败 → 加错误 + `continue`（不再比范围）；
- **范围是闭区间**：`min=1` 时 `1` 合法、`0` 报"不能小于"；`max=20` 时 `20` 合法、`21` 报"不能大于"（测试 `越界被拒` 用例正是这两个边界值）。
- ⚠ 两个下上界判断**各自独立**，不是 `else if` —— 参数范围**可以同时报两条吗**？不能：`min > max` 的 Schema 才会，而内置规则没有这种 Schema。当前行为是"可能报两条"，不必为此写测试。

**枚举分支** `case ENUM ->`：

```java
String s = String.valueOf(raw);
if (!spec.enumValues().contains(s)) {
    errors.add("参数 " + spec.name() + " 取值必须是 " + spec.enumValues() + " 之一，实际为 " + s);
}
```

⚠ **枚举比较是"字符串化后精确匹配"、区分大小写**：`"Grade"` 不等于 `"grade"`。而且**不做 trim**（数值分支 trim 了，这里没有）—— 所以 `" grade "` 会被拒。内置的十个 handler **目前没有任何 ENUM 参数**（`enumParam` 只在测试里被用到），所以这个差异现阶段不会咬人；写第一条带枚举的规则时要知道。

**布尔分支** `case BOOL ->`：

```java
if (!(raw instanceof Boolean)
        && !"true".equalsIgnoreCase(String.valueOf(raw))
        && !"false".equalsIgnoreCase(String.valueOf(raw))) {
    errors.add("参数 " + spec.name() + " 必须是布尔值，实际为 " + raw);
}
```

- 接受真 `Boolean`，以及**忽略大小写的 `"true"` / `"false"`**（测试里 `"TRUE"` 通过）；
- `"yes"`、`"1"`、`"是"` 被拒 —— **这正是给 `boolOr` 的宽容兜底**：取值时 `"yes"` 会静默变 false，但校验时它先被拦下来了。**两道关配合才安全**；
- ⚠ 注意判定用的是 `"true".equalsIgnoreCase(String.valueOf(raw))` 而不是 `Boolean.parseBoolean(...)` —— 后者对任意非法串都返回 false，**没法区分"合法 false"和"非法值"**。这是这两行必须写这么长的原因。

**`case STRING -> { // 任意字符串均可 }`**：空分支，什么都不查。**连"必填"之外没有任何约束** —— 没有长度上限、没有正则。需要更严的约束就得扩 `ParamType`（当前没有需求）。

#### 校验结果怎么变成用户可见的错误

`RuleCompiler` 把错误列表拼成一个异常：

```java
List<String> errors = rule.params().validate(handler.params());
if (!errors.isEmpty()) {
    throw new IllegalArgumentException(
            "规则 " + rule.type() + " 参数非法: " + String.join("; ", errors));
}
```

`RuleSetService.validateParams`（**写入侧，更早的一道关**）则翻成 HTTP 400：

```java
List<String> errors = RuleParams.of(params).validate(catalog.handlerOf(ruleType).params());
if (!errors.isEmpty()) {
    throw BusinessException.badRequest(String.join("；", errors));
}
```

> ⚠ **两处的连接符不同**：`RuleCompiler` 用 `"; "`（ASCII 分号），`RuleSetService` 用 `"；"`（全角分号）。这不是笔误，是两层各自的文案 —— 但如果你在写契约测试比对错误文案，**别忘了这两条路径的文案形状不一样**。
>
> ⚠ **`validate` 的一个已知局限**：它**只校验 Schema 里声明过的参数**，`params` 里那些**Schema 没声明的多余 key 会被完全忽略**（`for (spec : specs)` 是单向遍历）。所以拼错参数名（`"maxx"` 而不是 `"max"`）的后果不是"报错"，而是"这个参数被当作不存在 + `max` 缺失报错"（若 `max` 必填）。对**必填**参数能兜住，对**可选**参数则**静默丢弃**。这条没有在源码注释里写明，是从代码行为读出来的。

#### 这层设计的意义（一句话总括）

`RuleParamSpec` 是**声明**、`RuleParams` 是**值**；校验逻辑**只存在于 `RuleParams.validate` 一处**。`RuleSetService` 的注释把理由写得很直白：**"不在这里实现第二套校验逻辑：`RuleParamSpec` 是前端表单、后端校验、助手工具定义三处共用的同一份 schema，校验规则也必须只有一份。"**

---

### 2b.4 `RuleRegistry` —— `type → handler` 的映射（68 行）

**一句话定位**：一个纯粹的数据结构 —— **刻意不知道有哪些内置规则**。

**谁调它**：

| 上游 | 用法 |
|---|---|
| `compile/BuiltinRules.registry()` | `new RuleRegistry(List.of(十个 handler))`（唯一的生产构造点） |
| `compile/RuleCompiler` | `mandatory()`（自动施加）、`get(type)`（查实现） |
| `scheduling/rule/RuleTypeCatalog` | `all()` / `configurable()`（能力清单接口）、`contains` / `get`（翻译成 400） |
| `OrbitSolver.builtinRules()` | 原样转发给调用方 |
| `solver-core` 测试 | `new RuleRegistry(List.of(new SubjectWeeklyHoursHandler()))` —— **只注册一条**，用来隔离被测规则（这是它可构造、不可继承的实用价值） |

**下游调谁**：只调 `RuleHandler` 的 `type()` 与 `mandatory()`。**不认识 `rule.handler` 包的任何具体类**。

#### 为什么"不知道有哪些规则"是刻意的

类注释：

```java
/**
 * <p>它是一个纯粹的数据结构 —— <b>刻意不知道有哪些内置规则</b>。
 * 「系统内置哪几种规则」属于装配知识，由 {@code compile.BuiltinRules} 负责构造，
 * 否则本包与 {@code rule.handler} 之间会形成双向依赖（注册表 new 实现、实现 implements 接口）。
 *
 * <p>注册顺序即变量创建顺序，因此内部保序（LinkedHashMap）。
 */
```

拆开看两层：

1. **避免包环**：如果 `RuleRegistry` 自己 `new TeacherNoConflictHandler()`，那么 `rule` 包依赖 `rule.handler`，而 `rule.handler` 又 `implements` `rule.RuleHandler` —— **双向依赖**。把 new 的动作挪到 `compile/BuiltinRules`，`rule` 包就退回成**纯 API**（只被依赖，不依赖别人）。
2. **新增规则只改一处**：写一个 handler + 在 `BuiltinRules` 登记一行。

#### `private final Map<String, RuleHandler> byType = new LinkedHashMap<>();`

**`LinkedHashMap` 是必须的，不是随手选的**：

> ⚠ **"注册顺序即变量创建顺序"** —— `BuiltinRules` 的注释也重申了一遍："前三条是不变量/数据驱动，它们按 `mandatory()` 被自动施加，其相对顺序决定了模型里变量的创建次序 —— **改动顺序会让模型指纹变化**"。
>
> 换成 `HashMap` 的后果：`mandatory()` / `all()` 的遍历顺序随 key 的哈希分布变化 → `handler.apply` 的调用顺序变化 → **CP-SAT 模型里变量与约束的创建次序变化** → `ModelFingerprints` 变化。**求解结果本身（最优值）可能不变，但模型指纹会红**，而指纹正是这个项目用来证明"Java 与 Python 模型等价"的工具。所以这是一条**不能重构掉**的实现细节。

#### 构造器：`RuleRegistry(List<RuleHandler> handlers)`

```java
for (RuleHandler h : handlers) {
    RuleHandler previous = byType.put(h.type(), h);
    if (previous != null) {
        throw new IllegalStateException("规则类型重复注册: " + h.type());
    }
}
```

| 行 | 做什么 |
|---|---|
| `byType.put(...)` | 用 `put` 的**返回值**同时完成"写入"和"重复检测" —— 一行搞定，不用先 `containsKey` 再 `put`（两次哈希） |
| `previous != null` → 抛 `IllegalStateException` | **启动期就炸，而不是运行期二选一** |

> ⚠ **异常类型是 `IllegalStateException` 而不是 `IllegalArgumentException`**：`IllegalArgumentException` 在这个项目里已经承载了"**用户输入有问题**"的语义（`RuleTypeCatalog` 的注释明确说"不把 `IllegalArgumentException` 全局映射成 400，否则真正的编程错误会被报成'你的请求有问题'"）。重复注册是**开发者错误**，用 `IllegalStateException` 把它和用户错误分开。
>
> ⚠ 抛之前**已经污染了 map**（`put` 先执行了）—— 但那无所谓：这是构造器，异常一抛对象就不可达了。

> ⚠ **它不校验"两个不同实现声明了同一个 `type()` 但类不同"之外的任何东西**，也不校验 `type()` 是否为空串 / 是否有空格。`type()` 返回 `null` 会直接 NPE（`byType.put(null, h)` 在 `LinkedHashMap` 上是允许的，但紧接着第二次 `null` 就会撞上"重复注册"）。当前十个实现的 `TYPE` 全是硬编码常量，风险为零。

#### `RuleHandler get(String type)`

```java
RuleHandler h = byType.get(type);
if (h == null) {
    throw new IllegalArgumentException("未知的规则类型: " + type
            + "（已注册: " + byType.keySet() + "）");
}
return h;
```

**"查不到时怎样"是本册重点之一**：

- **抛 `IllegalArgumentException`，不返回 null、不返回空 Optional**。
- **文案里带上"已注册的全部类型"**（`byType.keySet()`）。这是很实用的一手：用户/开发者看到 `未知的规则类型: subject_max_per_dayy（已注册: [teacher_no_conflict, ...]）`，一眼就能发现是**拼写错误**，而不是去翻源码找合法值。
- **异常由上游翻译**：`scheduling/rule/RuleTypeCatalog.handlerOf` 先 `contains` 再 `get`，把"未知类型"翻成 **HTTP 400**：

```java
if (!registry.contains(ruleType)) {
    throw BusinessException.badRequest("未知的规则类型: " + ruleType);
}
```

> ⚠ 注意这里**用的是另一个文案**：`"未知的规则类型: " + ruleType`（**不带** `（已注册: ...）`）。所以：
> - 从 **HTTP 接口**传错类型 → 400，文案里**没有**已注册清单；
> - 从 **`RuleCompiler`** 直接传错类型 → `IllegalArgumentException`，文案里**有**已注册清单。
>
> 两条路径的文案不同是**刻意的**（前者是给终端用户看的，列出内部类型表属于泄露实现细节；后者是给开发者看的）。别在契约测试里去"对齐"它们。

#### `boolean contains(String type)`

`byType.containsKey(type)`。存在的意义是让**上游能在不触发异常的前提下判断** —— `RuleTypeCatalog` 用它把异常翻译成 400，`isKnown` / `isMandatory` 都建在它上面。

#### `Collection<RuleHandler> all()`

返回 `byType.values()`。**注意这里没有防御性拷贝**（返回的是 map 的视图）。

> ⚠ **这个视图是不可改的"半个谎言"**：`values()` 视图本身**不能 `add`**（`LinkedHashMap.values()` 不支持 add，会抛 `UnsupportedOperationException`），但它的 `remove` / `clear`（以及迭代器的 `remove`）**会真的改到注册表**。所以调用方拿到 `all()` 后如果调 `removeIf(...)`，会**静默破坏全局注册表**。
>
> 目前两个调用方（`RuleTypeCatalog.all()`、`SpikeMain`）都只做 `.stream().map(...)` 和读循环，没有改。**如果要给返回值加防御性拷贝，`List.copyOf(byType.values())` 就够了** —— 现在没加是权衡（每次请求都拷一份十个元素的列表），不过 `RuleTypeCatalog` 是 `@Component` 单例、每次 HTTP 请求都会调 `all()`/`configurable()`，真要改建议连缓存一起考虑。

#### `List<RuleHandler> mandatory()` 与 `List<RuleHandler> configurable()`

```java
public List<RuleHandler> mandatory() {
    List<RuleHandler> out = new ArrayList<>();
    for (RuleHandler h : byType.values()) {
        if (h.mandatory()) { out.add(h); }
    }
    return out;
}

/** 可配置的规则（能出现在规则列表里让用户编辑的）。 */
public List<RuleHandler> configurable() {
    List<RuleHandler> out = new ArrayList<>();
    for (RuleHandler h : byType.values()) {
        if (!h.mandatory()) { out.add(h); }
    }
    return out;
}
```

| 观察 | 说明 |
|---|---|
| 两者是**互补的**（`h.mandatory()` 与其取反），合起来恰好等于 `all()` | 没有第三条分支 |
| 都用 for 循环而不是 stream | 与全项目风格一致（可读、无装箱开销）；顺序**保证是注册顺序**（因为遍历的是保序 map） |
| 都返回**新 `ArrayList`** | 这两个方法**有**防御性拷贝（与 `all()` 不同），调用方随便改不影响注册表 |
| `mandatory()` 的**顺序敏感** | `RuleCompiler` 直接 `for (RuleHandler handler : registry.mandatory())` 施加约束 —— 列表顺序 = 变量创建顺序 = 模型指纹的一部分 |

**`configurable()` 的用途**（在 `RuleTypeCatalog` 里写明了）：

> 不变量与数据驱动规则**不该出现在"新建规则"下拉里**：它们始终生效、不接受参数，让用户"选它"只会产生一条什么也不做的规则（甚至误导用户以为关掉它就能改变行为）。

**`OrbitSolver.builtinRules()` 的 javadoc 已经过时了**（唯一一处发现的文档漂移）：

```java
/** 系统内置的规则注册表（3 个不变量/数据驱动 + 2 个可配置）。 */
```

实际的 `BuiltinRules.registry()` 是 **3 个不变量/数据驱动 + 7 个可配置 = 10 个**（`BuiltinRules` 自己的注释写的是"内置的 10 种规则：3 个不变量/数据驱动 + 7 个可配置"）。**以 `BuiltinRules` 为准**。按红线③，本册不改源码，仅记录。

---

### 2b.5 `RuleContext` —— 规则作者看到的「课表世界」（293 行）

**一句话定位**：handler 在编译期唯一的数据入口 —— 把 `SlotVariableIndex`（变量与索引）包一层，**并把固定课记账收敛到一处**。

**谁调它**：`RuleCompiler` 里 `new RuleContext(index)` 建一次，然后**传给每一个 handler 的 `apply`**（同一个实例、同一次编译）。`handler/**` 的十个实现全部只通过它读数据。它也出现在 `CompiledModel` 里（`compile` 之后仍可查 `ctx.assignIndicesOf` / `assignmentAt`，供时间表提取使用）。

**下游调谁**：几乎全是 `index` 的**转发**；只有"固定课计数"那一段是**真正的计算**（在 `RuleContext` 里做，而不是 `SlotVariableIndex` 里）。

#### 类注释：为什么这个类必须存在

```java
/**
 * <p><b>这个类的存在理由，是把固定课处理收敛到一处。</b>
 * 原 Python 实现里 {@code fixed} 相关处理散落 39 处：每一条约束都要自己判断
 * 「这个时段是不是固定课」、自己把固定课的数量数出来、再手工加进线性表达式。
 * 任何新规则（人写或 AI 写）都必须重复这套记账，因此**极易漏**。
 *
 * <p>规则作者现在只需要问业务问题：
 * <pre>{@code
 * int fixed = ctx.fixedCountForTeacher(teacherId, day, period);   // 固定课已算在内
 * List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period); // 只含非固定时段
 * model.addLessOrEqual(sum(vars) + fixed, 1);
 * }</pre>
 *
 * <p>于是 {@code fsid} / {@code ftid} / {@code fixed_count} 这些记账细节不再出现在任何 handler 里。
 */
```

**怎么读这段**：Python 里"固定课"（学校已经排死、不参与求解的时段）不产生决策变量。所以**每条约束都得自己把固定部分补上**：`sum(决策变量) + 固定数量 ≤ 上限`。一旦某条约束忘了加 `fixed`，**它的约束力会被悄悄削弱**（固定课"免费"占额度）—— 这是最难查的一类错误，因为一切都能跑、只是结果不对。

**两个工具的契约**（都必须记住）：

| 工具 | 契约 |
|---|---|
| `freeXxxSlots(...)` | 返回的列表**只含非固定时段**（固定课不会出现在里面） |
| `fixedCountForXxx(...)` | 返回值**就是补偿项**，可以直接加到 `sum(vars)` 上 |

**两者必须成对使用**：只调前者 → 约束对固定课失效；只调后者 → 重复计数。这就是 `TeacherNoConflictHandler` 里的标准骨架：

```java
List<BoolVar> vars = ctx.freeTeacherSlots(teacherId, day, period);
int fixed = ctx.fixedCountForTeacher(teacherId, day, period);
if (fixed > 1) { throw new ConstructionInfeasible(...); }   // 固定课之间自己就撞了
if (!vars.isEmpty()) {
    model.addLessOrEqual(HandlerSupport.sumPlus(vars, fixed), 1);
}
```

（`vars.isEmpty()` 也一并处理了 —— 传空列表给 CP-SAT 会构造一个恒真约束，浪费模型规模。）

#### 基本成员

| 成员 | 说明 |
|---|---|
| `public static final int DAYS = 5` | ⚠ **每周 5 天，硬编码**，且**与 `SlotVariableIndex.DAYS` 重复**（那里同样是 `static final int DAYS = 5`，另有 `DAY_KEYS = {"Mon",...,"Fri"}`）。两处没有互相引用 —— **改一处不会同步另一处**。将来支持"周六上课"时，这两处（含 `DAY_KEYS`）必须一起改。记在此处以便将来检索 |
| `private final SlotVariableIndex index` | **唯一的依赖**；`RuleContext` 自身**没有任何可变状态**（无缓存、无计数），是"只读门面"。真正有状态的是 `index` 里的懒创建变量缓存与 `PenaltyCollector` |

`RuleContext` 是 `final` 类、构造器 `public`（`RuleCompiler` 要 `new`），**不暴露 `index` 本身** —— 只能通过下面列出的方法取数据。

#### `CpModel model()` / `int days()`

| 方法 | 转发到 | 备注 |
|---|---|---|
| `model()` | `index.model()` | handler 拿它加约束（`model.addLessOrEqual(...)` 等） |
| `days()` | 返回常量 `DAYS`（= 5） | **不查 `index`**。十个 handler 的循环都用 `for (int day = 0; day < ctx.days(); day++)` |

#### 基本数据区（`public List<ClassData> classes()` 到 `int periodsOn(...)`）

这一段**全是 `index` 的转发**（除了 `teacherExists`）：

| 方法 | 转发到 | 用途 / ⚠ |
|---|---|---|
| `List<ClassData> classes()` | `index.classDataInOrder()` | 遍历班级。**保序** |
| `Collection<Long> classIds()` | `index.allClassIds()` | 遍历班级 ID |
| `ClassData classData(long classId)` | `index.classData(classId)` | 单个班级（节数数组 `periods()`、总节数） |
| `Set<Long> subjectsOf(long classId)` | `index.subjectsOf(classId)` | 该班的科目集合（`TreeSet`，保序） |
| `Map<Long,Integer> weeklyHoursOf(long classId)` | `index.weeklyHoursOf(classId)` | **保序**的"科目 → 周课时"（`LinkedHashMap`） |
| `Collection<ScheduleInput.TeacherInfo> teachers()` | `index.teachersInOrder()` | 遍历教师（`id()` 是 `long`） |
| `boolean teacherExists(long teacherId)` | **自己实现**（见下） | 把"引用了不存在的教师"变成配置错误 |
| `int teacherWeeklyLimit(long teacherId)` | `index.teacher(teacherId).maxWeeklyHours()` | ⚠ 教师不存在时 `index.teacher` 抛 `IllegalArgumentException("未知教师: " + teacherId)` |
| `boolean teacherTeachesClass(long teacherId, long classId)` | `index.teacherTeachesClass(...)` | 教师不存在的班级返回 `false`（不抛） |
| `int periodsOn(long classId, int day)` | `index.lastPeriod(classId, day)` | **该班该天的节数**；方法名是 `periodsOn` 但**返回的是"节数"而不是"最后一节的编号"**（`lastPeriod` 内部就是 `classData(classId).periods()[day]`）。循环写法是 `period < ctx.periodsOn(classId, day)` |

**`teacherExists` 是这一段唯一有逻辑的实现**：

```java
/** 该教师 ID 是否存在（用于把「引用了不存在的教师」变成配置错误而非无解）。 */
public boolean teacherExists(long teacherId) {
    for (ScheduleInput.TeacherInfo t : teachers()) {
        if (t.id() == teacherId) {
            return true;
        }
    }
    return false;
}
```

> ⚠ **它是 O(教师数) 的线性扫描**（`index` 其实有 `Map<Long, TeacherInfo> teachers`，但**没有暴露"是否存在"**的方法，所以 `RuleContext` 只能自己扫）。对小学规模（几十个教师）无所谓；**别在"每个时段 × 每个班级"的内层循环里调它**。

> ⚠ **为什么要它**：规则参数里可以写 `teacher_id = 999`（不存在的教师）。如果不查，这条规则会**什么也不做**（没有任何变量匹配），表现为"规则加了等于没加"；或者更糟 —— 生成一个无法满足的模型，报"无解"，用户去查课表却查不出原因。**"配置错误"和"无解"必须区分开**，这是诊断能力的前提。`TeacherUnavailableHandler` 就靠它做这个区分。

> ⚠ **`teacherWeeklyLimit` 对不存在的教师会抛异常，`teacherTeachesClass` 不会**。两个相邻方法对同一个坏输入反应不同 —— 用错一个就从"配置错误"变成"求解进程崩"。写新 handler 时**先 `teacherExists` 再取上限**。

#### 固定课计数区（`isFixed` 到 `fixedCountForTeacherOnDay`）

**这是本类存在的理由**，逐方法：

| 方法 | 行数 | 做什么 |
|---|---|---|
| `boolean isFixed(classId, day, period)` | 3 | 转 `index.isFixed`（就是查 `fixed` map 里有没有这个 key） |
| `long fixedSubjectAt(classId, day, period)` | 4 | 取 `index.fixedAt(...)`，**null → 返回 `-1`** |
| `long fixedTeacherAt(classId, day, period)` | 4 | 同上，返回教师 ID，非固定 → `-1` |
| `int fixedCountForSubject(classId, subjectId)` | 8 | 该班该科目**整周**被固定课占掉的节数 |
| `int fixedCountForSubjectOnDay(classId, subjectId, day)` | 9 | 同上，限某一天 |
| `int fixedCountForTeacher(teacherId)` | 16 | 该教师**整周**被固定课占掉的节数 |
| `int fixedCountForTeacher(teacherId, day, period)` | 15 | 该教师**在某天某节**被固定课占掉几次（**>1 表示固定课自己就撞车**） |
| `int fixedCountForTeacherOnDay(teacherId, day)` | 8 | 该教师**某天**被固定课占掉的节数 |

> ⚠ **用 `-1` 而不是 null / `OptionalLong` 表示"这个时段不是固定课"**：`subjectId` 和 `teacherId` 在业务上都是**正数**（`RuleParamSpec` 里 ID 类参数的 `min` 全是 1），所以 `-1` 是安全的哨兵值。好处是调用方可以直接 `if (ctx.fixedTeacherAt(...) == teacherId)` 而不用先判空 —— `fixedCountForTeacher` 系列里全是这个写法。

**`fixedCountForTeacher(teacherId, day, period)` 的三个守卫**（这是最值得逐行读的一段）：

```java
for (long classId : classIds()) {
    if (!teacherTeachesClass(teacherId, classId)) {
        continue;                       // ① 这教师根本不教这个班
    }
    if (period >= periodsOn(classId, day)) {
        continue;                       // ② 这个班今天没这么多节
    }
    if (fixedTeacherAt(classId, day, period) == teacherId) {
        count++;
    }
}
```

| 守卫 | 为什么必须有 |
|---|---|
| ① 任教关系 | 否则会把"同一位教师 ID 出现在别的班的固定课里"也算进来（数据异常时会虚高） |
| ② 节数上限 | ⚠ **必须按班检查**：不同年级的作息可以不同（`ClassData.periods()` 是**按天**的数组）。外层循环可能用 `maxPeriodsOn(day)`（全年级**最大**节数），所以对"节数少的班"会问到**不存在的时段** —— `fixedAt` 那时查不到东西返回 `null`，`-1 != teacherId`，**结果碰巧正确**；但这个 `continue` 让它**不依赖"查不到就是 null"这个巧合**，是必要的显式守卫 |

**`> 1` 的语义**（javadoc 原话）：`该教师在某天某节被固定课占掉几次（>1 表示固定课本身就撞车）`。

这是**求解前就能发现的问题**：两条固定课把同一位教师排在同一时段 —— 这不是求解器能"优化"的，是**输入数据自相矛盾**。所以 `TeacherNoConflictHandler` 直接：

```java
if (fixed > 1) {
    throw new ConstructionInfeasible("教师 ... 在第 N 天第 M 节被固定课占了 " + fixed + " 次，固定课之间就冲突");
}
```

> ⚠ **`ConstructionInfeasible` 与"模型无解"是两件不同的事**：前者是**建模阶段**就判定"这输入不可能有解"（可以立刻给用户一句人话），后者是求解器跑完说 `INFEASIBLE`（用户只能拿到"排不出来"）。这个区分是诊断能力的关键，而**它是靠 `fixedCount... > 1` 这个计数器实现的** —— 所以 `RuleContext` 把"数固定课"这件事包下来，收益不只是"少写代码"。

**`fixedCountForTeacher(teacherId)`（整周版）** 与 `fixedCountForSubject` 的循环写法：

```java
public int fixedCountForSubject(long classId, long subjectId) {
    int count = 0;
    int periods = periodsOn(classId, 0);        // ⚠ 这里取了第 0 天
    for (int day = 0; day < DAYS; day++) {
        count += fixedCountForSubjectOnDay(classId, subjectId, day);
    }
    return count;
}
```

> ⚠ **`int periods = periodsOn(classId, 0);` 这一行算出来的值在方法体内从未被使用** —— 内层实际调用的是 `fixedCountForSubjectOnDay(...)`，而那个方法自己按 `periodsOn(classId, day)` 逐天算。这看起来是**早期版本的遗留**（曾经想在这里自己写循环）。**行为上无害**（多算了一次查表），但读的时候别以为"整周计数用的是第 0 天的节数" —— **不是**，只有这一行是死代码。按红线①，我不臆测作者的意图，仅记录"该局部变量未被使用，逐日计数由被调方法完成"。

**`fixedCountForSubjectOnDay`**：

```java
for (int period = 0; period < periodsOn(classId, day); period++) {
    if (fixedSubjectAt(classId, day, period) == subjectId) {
        count++;
    }
}
```

**`fixedCountForTeacherOnDay`**：

```java
for (int period = 0; period < maxPeriodsOn(day); period++) {
    count += fixedCountForTeacher(teacherId, day, period);
}
```

> ⚠ 这里用的是 `maxPeriodsOn(day)`（跨班最大值）而不是某个班的节数 —— 因为教师跨班，只能按"所有班里最长的一天"扫。这正是上面守卫②存在的直接原因（**这两个方法是配套的**）。
>
> ⚠ `fixedCountForTeacherOnDay` 是 **O(班级数 × 节数)**，而 `fixedCountForTeacherOnDay` 又被用作"教师周上限"那类约束的输入 —— 在内层循环里反复调用会变成 O(n²)。handler 里（如 `TeacherWeeklyLimitHandler`）应当**每教师调一次并缓存**，不要每个时段都调。写新规则时注意。

#### 变量访问区（`slotVar` 到 `freeTeacherSlotsOnDay`）

| 方法 | 返回 | ⚠ null 的语义（**必须记住**） |
|---|---|---|
| `IntVar slotVar(classId, day, period)` | 时段决策变量（取值 `0..assignments.size()`） | **固定课或空班（无任课安排）时返回 `null`** |
| `BoolVar subjectAt(classId, day, period, subjectId)` | "该时段是某科目" | **固定课 或 该班无此科目 时返回 `null`** |
| `BoolVar teacherAt(teacherId, classId, day, period)` | "该教师在该班上这一节" | **固定课 或 无任教关系 时返回 `null`** |

> ⚠ **`null` 是这三个方法的常规返回值，不是异常**。所以**任何遍历都必须 `if (b != null)`** —— `freeXxxSlots` 系列已经帮你过滤了，**但如果你直接调 `subjectAt` / `teacherAt`，忘了判空就是 NPE**（而且是"某些班级/某些时段才崩"的那种间歇性 NPE）。

> ⚠ **`null` 的三重含义被压成了一个值**："固定课"（已经排好了，不需要决策）、"没有这个科目/任教关系"（结构上不存在）、"空班"（没有任何任课安排）。**调用方无法区分它们** —— 想区分只能另外调 `isFixed(...)` / `subjectsOf(...)` / `teacherTeachesClass(...)`。
>
> 这对"固定课补偿"是**有影响的**：`freeXxxSlots` 过滤掉的既有固定时段、也有结构上不存在的时段。**对约束来说两者都该被排除**（都"不参与决策"），所以这个压缩是**有意的**、也是安全的。但如果你要写"统计某科目一共有多少可选时段"，得知道返回的列表里**不含固定课** —— 那正是 `fixedCountForSubject` 要补回来的东西。

**`freeSubjectSlots(classId, subjectId)`**：

```java
/** 该班该科目整周所有「非固定课」时段的布尔（懒创建，重复调用返回同一批变量）。 */
for (int day = 0; day < DAYS; day++) {
    for (int period = 0; period < periodsOn(classId, day); period++) {
        BoolVar b = subjectAt(classId, day, period, subjectId);
        if (b != null) { out.add(b); }
    }
}
```

**"懒创建，重复调用返回同一批变量"** 是重点：`subjectAt` 背后是 `SlotVariableIndex.subjectSlot` 这个 `Map<SubjectSlotKey, BoolVar>` 缓存（`SlotVariableIndex` 类注释："第一次被请求时才建变量，之后复用同一个 `BoolVar`"）。所以 `freeSubjectSlots` **每次调用都重新组装列表、但变量是同一批** —— 反复调用的代价只是**建 ArrayList**，不是建变量。

> ⚠ **这也是"教师冲突"与"教师周上限"两条规则不会各建一套变量的原因**：`SlotVariableIndex` 注释说原 Python 实现重复了约 **28%** 的变量。这条收益**完全依赖缓存是共享的** —— 换句话说，`RuleContext` 的各个 `freeXxxSlots` 之间必须返回**同一批对象**，任何"为了安全而拷贝一份变量"的改动都会把这个收益打回原形。

**`freeSubjectSlotsOnDay`**：同上，只扫一天。

**`freeTeacherSlots(teacherId)`（整周）**：

```java
/**
 * 该教师整周所有「非固定课」时段的布尔。
 * 与 {@link #freeTeacherSlots(long, int, int)} 返回的是**同一批缓存变量**，
 * 因此「教师不冲突」与「教师周上限」两条规则不会各建一套（原实现重复了约 28% 的变量）。
 */
for (long classId : classIds()) {
    if (!teacherTeachesClass(teacherId, classId)) { continue; }
    for (int day = 0; day < DAYS; day++) {
        for (int period = 0; period < periodsOn(classId, day); period++) {
            BoolVar b = teacherAt(teacherId, classId, day, period);
            if (b != null) { out.add(b); }
        }
    }
}
```

**`freeTeacherSlots(teacherId, day, period)`（某天某节，跨班）**：三重循环换成"班级 → 该时段"，多一个守卫：

```java
if (period >= periodsOn(classId, day)) { continue; }   // 这个班今天没这么多节
```

**`freeTeacherSlotsOnDay(teacherId, day)`**：

```java
for (int period = 0; period < maxPeriodsOn(day); period++) {
    out.addAll(freeTeacherSlots(teacherId, day, period));
}
```

> ⚠ **`freeTeacherSlotsOnDay` 会多次建中间 `ArrayList`**（每个时段一次 `freeTeacherSlots`）—— 变量是缓存的，但列表不是。可读性换来的小开销，当前规模无影响。

#### `int maxPeriodsOn(int day)`

```java
/** 某一天里所有班级中最大的节数（各班节数可以不同，遍历「天 × 节」时用它）。 */
int max = 0;
for (ClassData cd : classes()) {
    max = Math.max(max, cd.periods()[day]);
}
return max;
```

**为什么需要它**："不同年级可以不同节数"（`periodsOn` 的 javadoc 也强调了这点：`该班该天的节数（不同年级可以不同，所以「最后一节」必须按班按天算）`）。所以任何"天 × 节"的双层循环**不能用一个固定的节数上限**，只能用最大值，并在内层用 `periodsOn(classId, day)` 过滤。

**`HandlerSupport.maxPeriodsOnDay(ctx, day)` 就是它的转发**（`handler` 包里的工具方法），所以 handler 里两种写法等价：`ctx.maxPeriodsOn(day)` 或 `HandlerSupport.maxPeriodsOnDay(ctx, day)`。

> ⚠ **它是 O(班级数) 的**，且 `freeTeacherSlotsOnDay` / `fixedCountForTeacherOnDay` 每调用一次就查一次。放在"天"这一层（最多 5 次）没问题；**不要放进"节"层的内层循环**。

#### 提取区（`assignIndicesOf` / `assignmentAt`）

```java
public List<Integer> assignIndicesOf(long classId)      { return index.assignIndicesOf(classId); }
public ScheduleInput.Assignment assignmentAt(int globalIndex) { return index.assignmentAt(globalIndex); }
```

**归在"提取用"一节**，因为它们的真正用途是**求解之后**把决策变量的取值翻译回"这节课上什么科目、哪位老师"（`engine/TimetableExtractor` 与 `CompiledModel` 用）。编译期 handler 几乎不需要它们 —— `SlotVariableIndex.slotVar` 的取值域 `0..indices.size()` 正是这个全局索引列表的下标（`subjectAt` / `teacherAt` 内部就是用 `assignIndicesOf` + `assignmentAt` 匹配出科目/教师的）。

**它们被放在 `RuleContext` 里的意义**：`CompiledModel` 只持有 `ctx`（而不是同时持有 `index`），所以提取阶段也能从同一个入口拿数据 —— **`RuleContext` 因此成了"编译期与提取期共用的课表视图"**。

---

### 2b.6 `PenaltyCollector` —— 软约束惩罚项的收集与提交（72 行）

**一句话定位**：软约束的"收银台" —— handler 把惩罚项交进来，最后由它**统一** `model.minimize(...)`。

**谁调它**：

| 上游 | 调什么 |
|---|---|
| `compile/RuleCompiler.compile()` | `new PenaltyCollector(model)`、`useWeight(rule.effectiveWeight())`、`commit()` |
| `handler/**` 的软约束分支 | `add(term)`、`addConstant(n)` |

**下游调谁**：只调 CP-SAT 的 `LinearExprBuilder` 与 `CpModel.minimize`。

#### 为什么需要"收集"而不是让每条规则自己 minimize

CP-SAT 的 `model.minimize()` **只能调一次**（第二次调用会覆盖/冲突）。而软约束可能有很多条，每条都要贡献自己的惩罚项。所以必须先**汇总成一个线性表达式**再提交。

#### 状态

```java
private final CpModel model;
private final List<LinearArgument> unitTerms = new ArrayList<>();
private final List<LinearArgument> weightedTerms = new ArrayList<>();
private final List<Long> weights = new ArrayList<>();
private long constant;
private int currentWeight = 1;
```

> ⚠ **为什么要分成 `unitTerms` 和 `weightedTerms` 两条列表**（而不是"一条列表 + 一条权重表，权重全是 1"）：
> `LinearExprBuilder.addSum(...)` 是**批量**接口（一次加一堆系数为 1 的项），而 `addTerm(expr, coefficient)` 是**逐项**接口。**权重为 1 的项走 `addSum`，是为了让目标函数的构造尽可能紧凑**（CP-SAT 的线性表达式里"系数 1"是最常见的情况，批量加比逐项加省内存与时间）。
>
> 这不是玄学优化：**内置的软规则全都用权重 1**（`RuleSets.legacyEquivalent()` 里两条都是 `1`），所以 `unitTerms` 是**常态路径**，`weightedTerms` 只在用户把权重调到 2 以上时才用。

| 字段 | 含义 |
|---|---|
| `unitTerms` | 权重为 1 的惩罚项（提交时走 `addSum`） |
| `weightedTerms` + `weights` | 权重 ≠ 1 的惩罚项（**两个列表下标一一对应**，提交时逐个 `addTerm`） |
| `constant` | 常数惩罚（与决策变量无关的固定代价），**已经乘过当时的权重** |
| `currentWeight` | "当前权重"，初值 **1**；`useWeight` 设定，`add` / `addConstant` 读取 |

#### `void useWeight(int weight)`

```java
/** 设定后续 {@link #add} 使用的权重（最小为 1）。 */
public void useWeight(int weight) {
    this.currentWeight = Math.max(1, weight);
}
```

- **注意"后续"二字**：权重是**有状态的游标**，不是逐项参数。`add(term)` 用的是**调用那一刻**的 `currentWeight`。
- `Math.max(1, weight)`：与 `ConstraintRule.effectiveWeight()` 的 `weight <= 0 ? 1 : weight` 是**同一条纪律的第二道保险**（见 2b.1 的 ⚠）。**两道都有意义**：`effectiveWeight` 保护"直接用 `ConstraintRule` 的场景"，这里保护"任何调用方"。

**谁设定它**（`RuleCompiler` 的唯一一处）：

```java
penalties.useWeight(rule.effectiveWeight());
handler.apply(ctx, rule.params(), !rule.isSoft(), penalties);
```

> ⚠ **`useWeight` 对硬约束也会被调用**（因为这几行对每条可配置规则都执行，不分硬软）。硬约束的 handler 按要求**不碰 `penalties`**，所以没有副作用 —— 但**游标被改掉了**：下一条软规则调 `useWeight` 时会重新设，所以也没问题。
>
> ⚠ **真正的风险是"漏调 `useWeight`"**：如果将来有别的路径直接 `new PenaltyCollector(model)` 然后调 `add`，它会**默默使用上一条规则留下的权重**（或初值 1）。`currentWeight` **没有随 `add` 重置** —— 这个设计是刻意的（一条规则可以连续加多个同权重的项，例如 `SubjectNoConsecutiveHandler` 里连加 4 个），但代价就是**"权重作用域"靠调用顺序隐式维持**。

#### `void add(LinearArgument term)`

```java
/** 累加一个惩罚项（按当前权重）。 */
public void add(LinearArgument term) {
    if (currentWeight == 1) {
        unitTerms.add(term);
    } else {
        weightedTerms.add(term);
        weights.add((long) currentWeight);
    }
}
```

| 分支 | 行为 |
|---|---|
| `currentWeight == 1` | 进 `unitTerms`（提交时批量加，系数 1） |
| 否则 | `weightedTerms` 与 `weights` **同时**追加（**两个列表必须同长同序**，这是本类唯一的隐含不变量） |

> ⚠ **`add(null)` 不会被拦**：它会进列表，然后在 `commit()` 里 `addSum` / `addTerm` 抛 NPE。**NPE 的位置离出错处很远**（在编译末尾而不是 handler 里）—— 写 handler 时请自己保证判空（`freeXxxSlots` 已过滤掉 null，直接调 `subjectAt` 的要自己判）。

#### `void addConstant(long value)`

```java
/** 累加一个固定惩罚（按当前权重）。 */
public void addConstant(long value) {
    constant += (long) currentWeight * value;
}
```

- **立刻乘权重**（而不是"先存原值、提交时再乘"），所以 `constant` 里存的是**已加权**的值。
- **用途**（类注释）：`常数项也支持（例如「两节连着的固定课都是同一科目」这种与决策变量无关的固定惩罚）`。

**真实调用点**（都是"固定课本身就违反了这条软规则，无论如何都要记一笔"）：

| 调用点 | 语义 |
|---|---|
| `SubjectNoConsecutiveHandler:74` `penalties.addConstant(1)` | 两节固定课连着且同科目 → 固定罚 1 |
| `SubjectAvoidLastPeriodHandler:105` `penalties.addConstant(fixedViolations)` | 固定课落在最后一节 → 每处罚 1 |
| `MainSubjectMorningHandler:108` `penalties.addConstant(fixedViolations)` | 固定课没排在上午 → 每处罚 1 |
| `TeacherUnavailableHandler:85` `penalties.addConstant(1)` | 固定课落在教师不可用时段 → 罚 1 |

> ⚠ **常数项对"找可行解"没有影响，但对"最优解"有影响**：`minimize` 的常数部分**不改变哪组解最优**（所有解都加同一个常数），但会**改变目标值的数值**（`solver.objectiveValue()` 会把它算进去，`OrbitSolver.solve` 里 `(long) solver.objectiveValue()` 就是返回值）。所以：
> - 与 Python 对基线时，**目标值必须含常数项**才可能相等；
> - 反过来，如果常数项对不上，**最优排课结果可能完全一样**，只是目标值差一个数 —— 排查时先看目标值差多少、是不是正好等于某个常数。

#### `boolean isEmpty()`

```java
return unitTerms.isEmpty() && weightedTerms.isEmpty() && constant == 0;
```

**三项全空才算空**（注意常数项是 `== 0` 而不是"没调过 `addConstant`"）。

#### `void commit()` —— 提交目标函数

```java
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
```

逐段：

| 段 | 做什么 | ⚠ |
|---|---|---|
| `if (isEmpty()) return;` | **一个惩罚项都没有 → 完全不设目标** | **这是与 Python 对齐的关键**：Python 侧写的是 `if penalty_terms: model.Minimize(...)`。差别是**实质性的** —— 一旦设了 `minimize`，求解器就进入"优化"模式（默认会一直跑到证明最优或超时）；不设目标则只求**任一可行解**，通常**快得多**。所以"没有软规则"和"有软规则但权重为 0"是两种完全不同的执行路径 |
| `LinearExpr.newBuilder()` | 建表达式构造器 | |
| `addSum(unitTerms.toArray(new LinearArgument[0]))` | 批量加权重 1 的项 | 传 `new LinearArgument[0]` 是 Java 集合转数组的**正确写法**（传预分配大小的数组在并发/复杂泛型下反而有坑）；这里 `unitTerms` 非空（已判），所以不会传空数组给 `addSum` |
| `for (i ...) addTerm(weightedTerms.get(i), weights.get(i))` | 逐项按权重加 | ⚠ **靠"两个列表下标对齐"**：任何"往 `weightedTerms` 加而不往 `weights` 加"的改动都会在这里 **IndexOutOfBounds**。而 `add()` 是唯一入口，所以风险被关在 `add` 里 |
| `if (constant != 0) builder.add(constant)` | 加常数项 | 常数**已经乘过权重**（见 `addConstant`），这里**不能再乘一次** |
| `model.minimize(builder.build())` | 唯一一次 `minimize` | ⚠ **不可重入**：`commit()` 没有"已提交"标志，调两次会 `minimize` 两次。当前只有 `RuleCompiler` 在编译末尾调一次；**写新代码时不要重复调**（第二次会覆盖目标函数，而且不会有任何报错） |

> ⚠ **只在 `commit()` 里 `minimize`，意味着"惩罚项全部收集完才决定目标"** —— 所以 handler 之间**不能互相看到对方加了什么**，也不需要关心顺序（除了模型指纹的变量创建顺序）。这是软约束"可插拔"的基础。

#### 与 `RuleCompiler` 的完整时序（把 2b.1~2b.6 串起来）

`RuleCompiler.compile()` 的十二行，就是本册所有类被驱动的方式：

| 步骤（源码顺序） | 本册涉及的类 | 要点 |
|---|---|---|
| ① `new CpModel()` → `SlotVariableIndex.create` → `new RuleContext(index)` → `new PenaltyCollector(model)` | `RuleContext`、`PenaltyCollector` | 一次编译只有一个实例，全程共享 |
| ② `for (handler : registry.mandatory()) handler.apply(ctx, RuleParams.empty(), true, penalties)` | `RuleHandler.mandatory()` | 不变量**自动施加**；用空参数 + `hard=true`；**顺序 = 注册顺序** |
| ③ `if (!rule.enabled()) continue;` | `ConstraintRule.enabled()` | 库侧 SQL 已过滤，这里是防御 |
| ④ `registry.get(rule.type())` | `RuleRegistry.get()` | 查不到 → `未知的规则类型: ...（已注册: [...]）` |
| ⑤ `if (handler.mandatory()) continue;` | `RuleHandler.mandatory()` | 不变量**不接受外部配置**，避免重复施加 |
| ⑥ `rule.params().validate(handler.params())` → 非空则抛 `规则 X 参数非法: ...` | `RuleParamSpec` + `RuleParams.validate` | **在建模前就抛**，不会产出半个模型 |
| ⑦ `penalties.useWeight(rule.effectiveWeight())` | `ConstraintRule.effectiveWeight()`、`PenaltyCollector.useWeight` | 硬约束也会走到这行（无副作用） |
| ⑧ `handler.apply(ctx, rule.params(), !rule.isSoft(), penalties)` | `RuleHandler.apply` + `RuleContext` | **`hard = !isSoft()`** |
| ⑨ `penalties.commit()` | `PenaltyCollector.commit()` | 空 → **不设目标**（与 Python 的 `if penalty_terms:` 一致） |
| ⑩ `model.modelStats()` → `CompiledModel` | `ModelFingerprints` | 对 ②⑦⑧ 的**顺序与规模**都敏感 —— 所以 `RuleRegistry` 必须保序、handler 内部变量创建顺序也不能随手改 |

---

## 附：本册未覆盖但与阅读强相关的类

| 类 | 位置 | 与本册的关系 |
|---|---|---|
| `BuiltinRules` | `compile/BuiltinRules.java` | **唯一的生产装配点**：`new RuleRegistry(List.of(10 个 handler))`，注册顺序敏感 |
| `RuleCompiler` | `compile/RuleCompiler.java` | 本册所有类的**唯一驱动者**（上面那张时序表就是它） |
| `RuleSets` | `compile/RuleSets.java` | 用 `ConstraintRule.soft(...)` 写死"与 Python 等价的两条软规则" |
| `SlotVariableIndex` | `engine/SlotVariableIndex.java` | `RuleContext` 的全部转发目标；变量的懒创建 + 缓存在这里 |
| `RuleTypeCatalog` / `RuleParamResponse` / `RuleTypeResponse` | `orbit-scheduling` 的 `rule/` 包 | Schema 三处消费的 **HTTP 出口**（投影，不加工） |
| `RuleSetService.validateParams` / `rejectMandatory` | `orbit-scheduling` 的 `rule/` 包 | 写入侧的**第二道校验关**（400） |
| `RuleParamsValidationTest`、`TeacherRuleTest`、`SubjectPlacementRuleTest`、`CounterexampleInjectionTest` | `solver-core/src/test` | 本册行为断言的**实测依据**（本册引用的用例均出自这些真实文件） |
