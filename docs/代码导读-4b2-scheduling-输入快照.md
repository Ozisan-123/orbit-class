# 代码导读 · 4b-2. 输入快照

> **本册范围**：`orbit-scheduling` 的 `snapshot/` 包，共 **9 个类**：
> `SnapshotMappers`（内含 7 个嵌套 Mapper 接口）+ 7 个 `*Ref` + `ScheduleInputLoader`。
>
> 上游：[代码导读.md](代码导读.md)（契约层）· [4a](代码导读-4a-scheduling-排课编排.md)（排课编排）· [4b](代码导读-4b-scheduling-容量槽位与分布式锁.md)（容量与锁）
>
> ⚠ 与 4b 一样，本册也是**补写**的 —— 这一块原来在旧版 `4a`（2115 行 / 37 个类）里，被覆盖时丢了。

---

## 本模块在系统里的位置

**一句话**：这个包回答一个问题 —— **「排一课，到底需要哪些数据？」**

排课要用到 7 类数据，它们**全部属于 academic 服务的表**（年级 / 班级 / 科目 / 教师 / 任教 / 周课时 / 固定课时）。
`scheduling` 服务**只读**它们，从**不写** ✓ 然后把它们装配成 solver-core 的 `ScheduleInput` ✓

```
academic 属主的 7 张表
        │  只读（铁律 2 禁止的是"跨服务写"，读是允许的 —— 但这是一处已知耦合）
        ▼
  7 个 *Ref（只读映射）
        │  ScheduleInputLoader.load(orgId)  ← 唯一的装配点
        ▼
  ScheduleInput（solver-core 的领域记录）
        │
        ├─► SnapshotService.freeze()      → 冻结进 scheduling_snapshots（P3，入队时）
        ├─► DiagnosisService              → 诊断时现查
        └─► RuleTrialService              → 规则试算时现查
```

**这个包是方案 §3 铁律 3「排课输入用快照」的落点** ✓ —— 但它的实现方式值得说清楚，
因为它**不是一个"快照"**，而是**一个"将来会变成快照"的接缝**（见 §4b2.3）✓

## 阅读顺序建议

1. **先看 §4b2.0** —— 不理解"为什么要 7 个 `*Ref` 而不直接用 academic 的实体"，后面会一直别扭。
2. 再看 **`ScheduleInputLoader.load`** —— 它是唯一有逻辑的地方，7 个 `*Ref` 只是它的输入。
3. `SnapshotMappers` 和 7 个 `*Ref` 扫一眼即可 —— 它们几乎没有逻辑，但**有几个字段级的坑**（见 §4b2.2）。

---

## 4b2.0 为什么要 7 个 `*Ref`，而不是直接用 academic 的实体

`scheduling` 和 `academic` 是**两个服务** ✓ 但此刻它们**共用同一个数据库** ✓
所以技术上 `scheduling` 完全可以直接 `import` academic 的 `Grade` 实体来查 ✓ —— **但没有** ✓

**三个理由**：

| 理由 | 说明 |
|---|---|
| **① 表属主必须清晰** | 铁律 2：每张表只有一个属主 ✓ 如果 scheduling 直接依赖 academic 的 `Grade` 实体 ✗ 那这个实体就成了**事实上的共享模型** ✗ 改它会影响两个服务 ✓ 而 `GradeRef` 是**"我眼里的 grades 表"** ✓ 我可以只映我需要的那几列 ✓ |
| **② 类名要能一眼看出关系** | `*Ref` 后缀是刻意的 ✓ 看到 `ClassRef` 就知道"这是**对别人数据的引用**，不是我的实体" ✓ 而 `ClassEntity` / `Clazz` 看起来就像自己的表 ✓ |
| **③ 将来拆库时，改的是这些类** | 拆独立库之后这些 `Ref` 会变成"一次性拉取并冻结的快照对象" ✓ 而不是每次现查 ✓ **改动被关在这 9 个类里** ✓ |

**`Ref` 只映射排课真正需要的列** ✓ 不多不少 ✓
比如 `ClassRef` 只映射 `id / organizationId / name / gradeId` ✓ —— academic 的 `classes` 表还有别的列 ✓ 但排课用不到 ✓ 所以**不映** ✓

> ⚠ **这一处耦合是"已知"的，不是疏忽** ✓ 架构说明 §3.3 明确记着：
> 「这是一处已知耦合：读不违反铁律 2，但 P3 拆独立库时会断」✓
> 届时的两条路：① 鉴权上移到网关；② 各服务维护只读投影 ✓ 已记入 TODO ✓

---

## 4b2.1 `SnapshotMappers` —— 一个容器类里装 7 个 Mapper

```java
public final class SnapshotMappers {

    private SnapshotMappers() {
    }

    @Mapper
    public interface ClassRefMapper extends BaseMapper<ClassRef> { }
    @Mapper
    public interface GradeRefMapper extends BaseMapper<GradeRef> { }
    // ... 共 7 个
}
```

**设计要点**：

| 点 | 说明 |
|---|---|
| **7 个 Mapper 放在一个文件里** | 类注释直接写了理由：「它们只是同一件事的多个门面，**每个都是空的 `BaseMapper`**」✓ 7 个空接口各占一个文件，只会让目录变长 ✓ |
| **它们是 `public interface`（不是独立文件）** | 因为是 `SnapshotMappers` 的**嵌套接口** ✓ 引用时写 `SnapshotMappers.GradeRefMapper` ✓ —— 这个全限定名本身就是文档：「这是快照用的只读门面」✓ |
| **外层类是 `final` + 私有构造器** | ⚠ 它**永远不会被实例化** ✗ 它只是个命名空间 ✓ 私有构造器是为了让"这个类不能被 new"成为编译期事实 ✓ 而不是靠约定 ✓ |
| **每个接口都是空的 `BaseMapper<T>`** | 因为只需要 MyBatis-Plus 自带的 `selectList` / `selectBatchIds` ✓ —— **一个自定义 SQL 都没有** ✓ 这和 `ScheduleJobMapper`（塞满了自定义原子 SQL）形成对比 ✓ 为什么？**因为只读** ✓ 只读不需要原子性技巧 ✓ |

> 💡 **一个可以推广的判断法**：看一个 `*Mapper` 有没有自定义 SQL，
> 大致能猜出它服务的场景是「**读**」还是「**并发写**」✓
> 全是空接口 → 只是查询门面 ✓ 塞满 `@Update` → 那里有并发控制 ✓

---

## 4b2.2 七个 `*Ref` 逐个讲

先看一张总表（**它们和 academic 的表一一对应**）：

| Ref | academic 的表 | 排课为什么需要它 | 特殊之处 |
|---|---|---|---|
| `GradeRef` | `grades` | 每个年级**每天上几节**（`periods_per_day` 是按星期几配的） | ⚠ **JSON 列**，见下 |
| `ClassRef` | `classes` | 排课的基本单位；`grade_id` 决定它继承哪套周课时与固定课 | 类注释解释了 `Ref` 命名约定 |
| `SubjectRef` | `subjects` | 把课表条目补上科目名与分类 | |
| `TeacherRef` | `teachers` | **教师周课时上限**（硬约束输入） | `maxWeeklyHours` 用**包装类型** |
| `AssignmentRef` | `teacher_assignments` | 「某教师教某班某科」→ **候选教师集合** | ⚠ 字段顺序是刻意的 |
| `GradeSubjectRef` | `grade_subjects` | 「某年级某科每周几课时」→ **需求输入** | `weeklyHours` 用包装类型 |
| `GradeFixedSlotRef` | `grade_fixed_slots` | 「某年级某天某节固定上某科」→ 展开到该年级每个班 | ⚠ **刻意不含班级级的 `fixed_slots`** |

### ⚠ `GradeRef` —— JSON 列必须 `autoResultMap = true`

```java
@TableName(value = "grades", autoResultMap = true)      // ← 这个属性不能省
public class GradeRef {

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Integer> periodsPerDay;         // 形如 {"Mon":5,"Tue":5,...}
}
```

`periods_per_day` 在 MySQL 里是 **`json` 列** ✓ 要映射成 `Map<String,Integer>` 必须给 `@TableField` 配 `JacksonTypeHandler` ✓

**⚠ 但只配 handler 是不够的** ✓ 还要在 `@TableName` 上写 **`autoResultMap = true`** ✗
**不写的话查询出来是 `null`** ✗ —— 不报错、不抛异常 ✓ 只是那个字段永远是空 ✓

**为什么**：MyBatis-Plus 默认用**自动生成的 `resultMap`** ✓ 而 `typeHandler` 只在**显式声明的 `resultMap`** 里生效 ✓
`autoResultMap = true` 就是让它"按注解生成那个 resultMap" ✓

> ⚠ 类注释写着「这个坑在 academic 的 `Grade` 上踩过一次，这里直接带上」✓
> —— 即：**同一个坑踩了两次，第二次是因为第一次的经验被带过来了** ✓
> 这类坑的特点：**静默**（不报错、字段为 null）✓ 而且**只在真读那一列时才暴露** ✓

### `ClassRef` —— 类注释里有这个包的完整设计说明

除了字段，这个类的注释承担了三件事：

1. **解释包归属**：「放在 `scheduling.snapshot` 包下 —— 按 README 的包归属，这个包的职责是『**冻结并消费**排课输入快照』」
2. **解释为什么读不违反铁律 2**：「⚠ 与 `security.AuthUser` 同理：**读** academic 的表不违反铁律 2（铁律 2 禁止的是跨服务**写**）」
3. **解释 `Ref` 后缀**：「类名带 `Ref` 后缀是刻意的：一眼能看出『这是对别人数据的引用，不是我的实体』」

> 这是**好的注释该有的样子**：它解释的是**读者的疑问**（"为什么不直接用实体"、"这算不算违反铁律"）
> 而不是重复代码（"这是一个班级引用类"）✓

### `TeacherRef` —— 为什么 `maxWeeklyHours` 是 `Integer` 而不是 `int`

```java
private Integer maxWeeklyHours;    // 不是 int
```

理由和 `PageQuery` 的 `Integer page` 是同一类（见第 1 册）✓：
**数据库的列可能给回 `null`** ✓ 用 `int` 接会在 MyBatis 赋值时 NPE 或抛类型错误 ✓

⚠ 注释说这列在库里是 `NOT NULL` ✓ 但 `ScheduleInputLoader` 里**还是兜了底**：

```java
.map(t -> new ScheduleInput.TeacherInfo(t.getId(), t.getName(), t.getSubjectId(),
        t.getMaxWeeklyHours() == null ? 18 : t.getMaxWeeklyHours()))
```

**18 是 Python 那边的默认值** ✓ —— 即"即使库说不为空，我也按对端的语义处理" ✓
这是**行为等价迁移里的典型心态**：不要因为"我的库约束更强"就省掉对端的兜底 ✓
因为一旦有历史数据（或将来换库）破坏这个约束，两边行为就会分叉 ✓

### `AssignmentRef` —— 字段顺序是刻意的

类注释解释了它的**语义**：「排课的**第二个核心输入**：它决定了一节课能由谁上。引擎会把它当作『候选教师集合』」
而 `ScheduleInputLoader` 里有一句：

```java
// ⚠ 字段顺序刻意与 Python 的 all_assigns 元组 (class_id, subject_id, teacher_id) 一致
.map(a -> new ScheduleInput.Assignment(a.getClassId(), a.getSubjectId(), a.getTeacherId()))
```

对照 `AssignmentRef` 的声明顺序：`teacherId / classId / subjectId` ✗ —— **不一样** ✗

也就是说：**装配时把顺序"扭"了一次** ✓ 让它与 Python 的元组一致 ✓
这是一个**很值得注意的做法**：实体字段按表结构排（`teacher_id` 在表里可能就是第二列 ✓），
而传给求解的元组按**对端的顺序**排 ✓ —— **两种顺序都各自有自己的理由，转换点被显式标注了** ✓

> ⚠ 这种"顺序敏感"的地方，**没有编译期保护** ✗
> `(Long, Long, Long)` 三个参数换个位置照样编译通过 ✓ 但结果全错 ✓
> 唯一的防线就是这句注释 ✓ 如果你要重构它，**先读注释再动手** ✓

### `GradeFixedSlotRef` —— 刻意"少读一样"

类注释里有一句很关键的话：

> 注意它是**年级级**的（`grade_fixed_slots`），不是班级级（`fixed_slots`）。
> 后者当前没有参与建模（Python 的引擎只读了年级级的那张），
> 这里保持一致 —— **不"顺手"多加输入**，否则模型会与 Python 不同。

**这是"行为等价"这条约束在细节上的体现** ✓
`fixed_slots`（班级级固定课）**是存在的一张表** ✓ 而且从业务上看"班级级固定课"显然也该参与排课 ✓
但 Python 的引擎**只读了年级级那张** ✓ 所以 Java 侧**也不读** ✓

**为什么这么严**：因为读进去就会改变模型 ✓ 改变模型就会改变目标值 ✓
而 P1 的判据是「**两边最优目标值必须相同**」✓（实测都是 12 ✓）
多加一样输入 → 目标值可能变 → 判据失效 ✓ 而且**没人能说清是"修好了"还是"改坏了"** ✓

> 💡 **可推广的原则**：等价迁移期间，**"看起来该做但 Python 没做"的事一律不做** ✓
> 把它写进 TODO ✓ 等迁移完成、判据不再依赖两边对比之后，再作为**独立的新需求**改 ✓
> 否则你会在同一个提交里同时引入"迁移"和"行为变更" ✗ 出了问题无法二分定位 ✓

### `GradeSubjectRef` / `SubjectRef`

- `GradeSubjectRef` 是**需求输入** ✓ 注释：「引擎要靠它知道每个班的每门课要排几节」✓
  `weeklyHours` 又是包装类型 + 兜底 0 ✓
- `SubjectRef` 只有一个 `category` 字段值得注意 ✓ —— 它是**软约束**的输入（例如"主科尽量排上午"要按分类判断 ✓）

---

## 4b2.3 `ScheduleInputLoader` —— 7 次查询 → 8 个列表

**整个包唯一有逻辑的类** ✓ 只有一个公开方法 ✓

### 构造器：7 个 Mapper 全部注入

```java
public ScheduleInputLoader(SnapshotMappers.GradeRefMapper gradeMapper,
                           SnapshotMappers.ClassRefMapper classMapper,
                           SnapshotMappers.SubjectRefMapper subjectMapper,
                           SnapshotMappers.TeacherRefMapper teacherMapper,
                           SnapshotMappers.AssignmentRefMapper assignmentMapper,
                           SnapshotMappers.GradeSubjectRefMapper gradeSubjectMapper,
                           SnapshotMappers.GradeFixedSlotRefMapper gradeFixedSlotMapper) { ... }
```

**7 个参数、7 个字段、7 行赋值** ✓ 啰嗦但是**刻意的**：Spring 的构造器注入让依赖**显式可见** ✓
而这个类的职责就是"把 7 张表装配成一份输入" ✓ 所以"它依赖 7 张表"这件事**应该**写在签名里 ✓

（对比：如果用 `@Autowired` 字段注入 ✗ 你从类外面**看不出**它依赖多少东西 ✓
这里 7 个参数一眼就知道"这个类的成本是 7 次查询" ✓ —— 而这恰好是**性能相关的信息** ✓）

### `load(organizationId)` 的结构

**七段，结构完全一致** ✓ 每段都是：**查 → 过滤 → 映射 → 收集** ✓

```java
List<ScheduleInput.GradeInfo> grades = gradeMapper.selectList(
                Wrappers.<GradeRef>lambdaQuery().eq(GradeRef::getOrganizationId, organizationId))
        .stream()
        .map(g -> new ScheduleInput.GradeInfo(g.getId(), g.getName(), g.getPeriodsPerDay()))
        .toList();
```

逐段看四个共同点：

| 点 | 说明 |
|---|---|
| **每条查询都带 `organizationId`** | 多租户隔离 ✓ 7 条查询**无一例外** ✓ 第 1 个查询漏了就是一个**跨租户数据泄漏** ✓ |
| **用 `lambdaQuery()` 而不是字符串条件** | `GradeRef::getOrganizationId` 是**方法引用** ✓ 改字段名时编译器会报错 ✓ 字符串 `"organization_id"` 不会 ✓ |
| **`.stream().map(...).toList()`** | 实体 → solver-core 的**领域记录** ✓ —— 这一步是"翻译"：把 MyBatis 的实体变成纯数据 ✓ 之后 solver-core 完全不知道数据库的存在 ✓ |
| **`.toList()` 返回不可变列表** | Java 16+ 的 `Stream.toList()` ✓ 返回**不可修改**的列表 ✓ 正好匹配 solver-core 里 `ScheduleInput` 的不可变语义 ✓ |

### ⚠ 四处 null 兜底，每一处都有理由

```java
t.getMaxWeeklyHours() == null ? 18 : ...        // 18 = Python 的默认值
gs.getWeeklyHours()   == null ? 0  : ...        // 0 = 当作"这门课不排"
f.getDayOfWeek()      == null ? 0  : ...        // 0 = 星期一
f.getPeriodIndex()    == null ? 0  : ...        // 0 = 第一节
```

**注意这四处兜底的方向不一样**：
- `maxWeeklyHours` → **18**（一个"合理上限" ✓ 宁可少排也不让它无上限 ✓）
- `weeklyHours` → **0**（"不排" ✓ 而不是"排 1 节" ✓）
- 时段 → **0**（第 0 天第 0 节 ✓ 一个确定的、可预测的位置 ✓）

**为什么方向不同**：因为**每个字段的"安全默认值"不同** ✓
兜底值不是随便挑的 ✓ 挑错了会**静默改变排课结果** ✗ 而且是在"数据异常"这条罕见路径上 ✗ 更难发现 ✓

> ⚠ 这四处**都没有注释说明 Python 那边对应的默认值是什么**（只有 `maxWeeklyHours` 写了"Python 的默认值"）✗
> 如果将来有人要改这些兜底值，**必须先去 Python 源码里核对** ✓ 不能凭感觉 ✓

### `return new ScheduleInput(organizationId, grades, classes, subjects, teachers, assignments, gradeSubjects, gradeFixedSlots)`

**8 个参数的位置构造器** ✓ ⚠ 又是"顺序敏感、无编译期保护"的地方 ✓
`grades` 和 `classes` 换个位置照样编译通过 ✓ 但语义全错 ✓

**这也解释了为什么 solver-core 的 `ScheduleInput` 值得单独写一册**（2a 册 ✓）——
那个记录是**整个求解的边界** ✓ 它的字段顺序就是这份契约 ✓

---

## 4b2.4 三个调用方 —— 这个"接缝"设计兑现了

类注释里有一句承诺：

> 现在实现是"现查一遍"（与 Python 一致，同一个库），但**接口已经收在这里**：
> P3 拆分时只要把 `load` 换成"调 academic 的接口一次性拉取并冻结"，
> **调用方（求解链路）一行都不用改**。

**我 grep 验证了这句话** ✓ `inputLoader.load(...)` 一共有 **3 个调用方**：

| 调用方 | 位置 | 它要的是"现查"还是"冻结"？ |
|---|---|---|
| `DiagnosisService` | `:254` | **现查** ✓ 诊断是"现在这一刻数据能不能排" ✓ 对时效性敏感 ✓ 冻结反而错 ✗ |
| `RuleTrialService` | `:76` | **现查** ✓ 试算规则是"用当前数据评估" ✓ |
| **`SnapshotService`** | `:54` | **冻结** ✓ ← P3 新增 ✓ 入队时拉一次、存进 `scheduling_snapshots` ✓ |

**所以那句话是**基本**成立的**：P3 加了一个**新的**调用方（`SnapshotService`）✓
而**原来那两个调用方一行没改** ✓ 接缝确实收在 `load` 这个方法上 ✓

**但它有一处不准确** ✗：注释说"P3 拆分时只要把 `load` 换成……" ✓
实际上 P3 **没有改 `load`** ✗ —— 而是**新增了一条"冻结"路径**（`SnapshotService` 调它、然后把结果存库 ✓）✓
"现查"这条路仍然存在 ✓ 并且**仍然是对的** ✓（诊断/试算本来就该用当前数据 ✓）

> 💡 **这是"接缝设计"的一个真实好处**：当初把"取数据"这件事收进一个方法，
> 并不意味着"以后一定要改它" ✓ 而是"要加新用法时，**只需要加调用方，不需要改被调方**" ✓
> 结果 P3 既保留了现查（给诊断/试算）✓ 又加上了冻结（给排队求解）✓ **同一个方法服务了两种语义** ✓

---

## 本册小结

`snapshot/` 这 9 个类几乎没有"代码逻辑" ✓ 它们表达的是**边界**：

| 类 | 它守的边界 |
|---|---|
| 7 个 `*Ref` | **只读**别人的表；只映自己需要的列；名字带 `Ref` 提醒你这不是我的实体 |
| `SnapshotMappers` | 只读门面**不需要自定义 SQL**（和并发写场景形成鲜明对比） |
| `ScheduleInputLoader` | **"排课需要哪些数据"这件事只有一个答案**，写在一个方法里 |

**读这一册最该带走的三件事**：

1. **`autoResultMap = true`**（`GradeRef`）—— JSON 列不写它，读出来静默是 `null` ✓
2. **"不顺手多加输入"**（`GradeFixedSlotRef`）—— 等价迁移期间，Python 没做的事一律不做 ✓
3. **顺序敏感的地方一定有注释**（`Assignment` 的字段顺序、`ScheduleInput` 的构造器）✓
   —— 这些地方**没有编译期保护** ✓ 重构前先读注释 ✓

### 本册发现的问题

| # | 问题 | 性质 |
|---|---|---|
| **1** | 四处 null 兜底里**只有一处**注明了"这是 Python 的默认值" ✗ | 文档缺失 ✓ 无功能影响 ✓ 但将来改兜底值的人会不知道去哪核对 ✓ |
| **2** | `load` 的类注释承诺"P3 拆分时把 `load` 换成拉接口"，而 P3 实际是**新增调用方、没改 `load`** ✗ | 注释**略微过时** ✓ 结论仍成立 ✓ 但读的人会以为 `load` 已经被改过 ✓ |

**两处都没有实测行为影响** ✓ —— 我**没有改动任何代码** ✓
