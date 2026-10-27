# 代码导读 · 2a. solver-core 的 model / engine / OrbitSolver

> **本册范围**：`orbit-solver-core` 的 `model/`（3）+ `engine/`（7）+ 根包的 `OrbitSolver`，共 **11 个类**。
>
> 上游：[代码导读.md](代码导读.md)（第 1 册）
> 兄弟册：[2b 规则内核](代码导读-2b-solver-core-规则内核.md) · [2c 内置规则（上）](代码导读-2c-solver-core-内置规则上.md) · [2d 编译装配](代码导读-2d-solver-core-编译装配.md)
>
> **这一册是"排课到底怎么算出来的"最底层的一册** —— 规则（2b/2c/2d）说的是"加什么约束"，
> 本册说的是"**变量怎么建、约束挂在哪、解怎么取回来**"。

---

## 本模块在系统里的位置

`orbit-solver-core` 是整个项目的**智力核心**，而且它**独立性最强**：

> 它的整个 `pom.xml` 只有三个依赖 —— **`ortools-java`、`jackson-databind`、`junit-jupiter`**。
> 代码里 `org.springframework.*` / `com.baomidou.*` / `jakarta.*` 的 import **出现 0 次**。

**这个边界是刻意的** ✓ 正因为它是纯库，P3 才能**原封不动**地把它搬进独立的
`orbit-solver-worker` 进程 ✓（搬运时 solver-core **一行都没改** ✓）

本册三个部分的角色：

| 部分 | 类 | 角色 |
|---|---|---|
| **`model/`** | `ScheduleInput` / `ClassData` / `TimetableEntry` | **边界类型** —— 输入是什么形状、输出是什么形状 |
| **`engine/`** | `SlotVariableIndex` / `TimetableExtractor` / `SolveOutcome` / `SolverSettings` / `SolverNatives` / `ModelFingerprints` / `ConstructionInfeasible` | **建模与求解的基础设施** —— 建变量、取解、装原生库、算指纹 |
| **根包** | `OrbitSolver` | **唯一门面** —— 外界只碰这一个类 |

**依赖方向**（`OrbitSolver` 的 javadoc 特意写明了）：

```
                 OrbitSolver（根包）
                   ├──────────┴──────────┐
                   ▼                     ▼
              compile/                engine/          ← 根包可以同时依赖两边，不成环
                   │                     │
                   └────────┬────────────┘
                            ▼
                          rule/  ──►  model/
```

> 💡 **为什么门面要放根包**：`compile` 要调 `engine`（建变量）✓ `engine` 又要调 `model` ✓
> 如果门面放在 `compile` 里 ✗ 那 `engine` 想用门面就会形成**包环** ✓
> 放在根包（最上层）✓ 谁都不依赖它 ✓ 这个安排很标准，但值得指出 ✓

## 阅读顺序建议

1. **`ScheduleInput`**（52 行）—— 先知道"输入有哪些"，后面一切都好读。
2. **`SlotVariableIndex.slotVar` / `subjectAt` / `teacherAt` / `membership`** —— 🔴 **本册的核心**，其他都是配角。
3. **`TimetableExtractor`** —— 看懂"空堂哨兵"这个约定，求解结果就通了。
4. 其余（`SolverSettings` / `SolverNatives` / `ModelFingerprints` / `ConstructionInfeasible`）扫一眼即可。

---

## 2a.1 `ScheduleInput` —— 一次排课的全部输入

```java
public record ScheduleInput(
        long organizationId,
        List<GradeInfo> grades,
        List<ClassInfo> classes,
        List<SubjectInfo> subjects,
        List<TeacherInfo> teachers,
        List<Assignment> assignments,
        List<GradeSubjectInfo> gradeSubjects,
        List<FixedSlotRule> gradeFixedSlots) { ... }
```

**8 个字段 = 1 个租户 + 7 类数据** ✓ 而类注释明说：

> 对标 Python 侧 `generate_schedule` 开头那 7 条查询。

**7 条查询 → 7 个列表** ✓ 一一对应 ✓ （这 7 条查询的搬运在 4b-2 册的 `ScheduleInputLoader` ✓）

> ⚠ **注意类注释里"spike 阶段直接从 JSON fixture 读入"** ✓
> 这说明这个类**比整个项目更早存在** —— 它是在"验证 Java + OR-Tools 能不能跑出与 Python 相同的结果"那个阶段定的形状 ✓
> 也就是说：**它是先被证明可行、然后才被正式实现采用的** ✓ 这是一个很好的工程顺序 ✓

### 七个嵌套 record

| record | 字段 | 注释里标明的对应 |
|---|---|---|
| `GradeInfo` | `id, name, periodsPerDay` | `models.Grade.periods_per_day` |
| `ClassInfo` | `id, name, gradeId` | |
| `SubjectInfo` | `id, name, category` | |
| `TeacherInfo` | `id, name, subjectId, maxWeeklyHours` | `models.Teacher.max_weekly_hours` |
| `Assignment` | **`classId, subjectId, teacherId`** | ⚠ 顺序刻意 |
| `GradeSubjectInfo` | `gradeId, subjectId, weeklyHours` | `models.GradeSubject` |
| `FixedSlotRule` | `gradeId, subjectId, day, period` | `models.GradeFixedSlot` |

**三个值得注意的点**：

**① `Assignment` 的字段顺序是被注释"钉住"的** ✓
```java
/**
 * <p>字段顺序刻意与 Python 的 {@code all_assigns} 元组一致
 * {@code (class_id, subject_id, teacher_id)}，避免移植时下标错位。
 */
public record Assignment(long classId, long subjectId, long teacherId) { }
```
⚠ 而 `AssignmentRef`（数据库映射）的字段顺序是 `teacherId, classId, subjectId` ✗ —— **不一样** ✗
转换发生在 `ScheduleInputLoader` ✓ 那里也有一句注释 ✓（4b-2 册）

> 这是**顺序敏感、无编译期保护**的典型 ✗ `(long, long, long)` 换个位置照样编译 ✓
> 唯一的防线就是这两处注释 ✓

**② `periodsPerDay` 是 `Map<String, Integer>` 而不是数组** ✓
键是 `"Mon".."Fri"` ✓ 与 MySQL 的 `json` 列形状一致 ✓（`GradeRef` 用 `JacksonTypeHandler` 直接映射 ✓）
**折算成数组发生在 `SlotVariableIndex`** ✓ 那是它该干的事 ✓（见 §2a.5）

**③ `FixedSlotRule.day/period` 用的是短名** ✓（不是 `dayOfWeek` / `periodIndex` ✓）
而 `GradeFixedSlotRef` 用的是 `dayOfWeek` / `periodIndex` ✗ → 又是转换点 ✓
在 `ScheduleInputLoader` 里做了 ✓

> 💡 **一个模式**：`ScheduleInput` 里的短名（`day` / `period`）在**建模代码里被大量使用** ✓
> 所以短名在这里是合适的 ✓ 而数据库映射层用全名（`dayOfWeek`）因为它要对齐列名 ✓
> **两层各自选自己最合适的名字，转换点显式标注** ✓ —— 这比"全局统一命名"更实用 ✓

---

## 2a.2 `ClassData` —— 每班每天的节数（12 行，但有一个坑）

```java
/**
 * @param periods 索引 0..4 对应周一..周五
 */
public record ClassData(long classId, int[] periods, int total) { }
```

**为什么 `periods` 是数组而不是一个常数** ✓ 类注释：

> 注意 `periods` 是「每天各自的节数」而不是一个常数：不同年级的 `periods_per_day` 可以不同，
> 所以「**当天最后一节**」必须按班按天算。

**这是一个容易想当然的地方** ✗：直觉上"这个班一天 6 节"✗
实际上一年级周一到周五可能都是 5 节 ✗ 而二年级是 6 节 ✓ 甚至同一年级不同天也不同 ✓（`periods_per_day` 是按星期几配的 ✓）

**`total` 是一周总节数** ✓ = `periods` 之和 ✓ 事先算好 ✓（避免每次重算 ✓）

### ⚠ 一个值得注意的设计瑕疵：record 里放可变数组

`record` 的语义是**不可变的值对象** ✓ 但 `int[]` **是可变的** ✗

```java
ClassData cd = index.classData(1);
int[] p = cd.periods();
p[0] = 999;            // ✗ 编译通过，而且真的会改掉"不可变"对象里的数据
```

**后果**：任何拿到 `periods()` 的调用方都能**悄悄改掉索引里的数据** ✗
而 `SlotVariableIndex` 会把同一个 `ClassData` 实例发给所有 handler ✓ → **一个 handler 的误改会污染其他 handler** ✗

**现在有没有问题**：我 grep 过用法 ✓ 都是**只读**（`cd.periods()[day]` ✓）✓ **所以当前没有 bug** ✓

**但这是一个隐患** ✓ 标准做法是：
- 用 `List<Integer>` 代替 `int[]`（`List.of(...)` 是不可变的 ✓），或
- 在访问器里返回 `periods.clone()` ✓（有拷贝开销 ✓ 但 5 个 int 无所谓 ✓）

> ⚠ 我没有改动代码 ✓ 记在这里 ✓ —— 这是"能跑但对未来不友好"的典型 ✓

---

## 2a.3 `TimetableEntry` —— 求解结果的一行（10 行）

```java
public record TimetableEntry(long classId, long subjectId, long teacherId,
                             int dayOfWeek, int periodIndex) { }
```

⚠ **注意它用的是全名**（`dayOfWeek` / `periodIndex` ✓）而 `ScheduleInput.FixedSlotRule` 用的是短名（`day` / `period` ✓）✗
—— **同一个包里两种命名** ✓

**为什么**：`TimetableEntry` 是要**跨进程传出去**的 ✓（P3 之后由 worker 回传给 scheduling 落库 ✓）
它对应的是 `schedule_version_entries` 表的列 ✓ 所以用列名 ✓
而 `FixedSlotRule` 只在**建模内部**用 ✓ 短名更省地方 ✓

> 💡 又一个"**按用途选命名**"的例子 ✓ 和 `Assignment` 那个是同一类判断 ✓

---

## 2a.4 `SolverSettings` / `SolveOutcome` —— 求解参数与结果

### `SolverSettings`

```java
public record SolverSettings(double maxTimeInSeconds, int numSearchWorkers, boolean logSearchProgress) {

    /** 与现有 Python 生产配置一致（routers/schedule.py 传 time_limit=60）。 */
    public static SolverSettings production() {
        return new SolverSettings(60.0, 2, false);
    }
}
```

**三个参数各自的影响**：

| 参数 | 生产值 | 含义 / 影响 |
|---|---|---|
| `maxTimeInSeconds` | **60.0** | 求解墙钟上限 ✓ 到点返回当前最好解（`FEASIBLE`）或 `UNKNOWN` ✓ 注释标明了它是从 `routers/schedule.py` 的 `time_limit=60` 对过来的 ✓ |
| `numSearchWorkers` | **2** | CP-SAT 的并行搜索线程数 ✓ ⚠ **调大不一定更快** ✓ 对小模型反而增加协调开销 ✓ 而且它直接决定 CPU 占用 ✓（`CapacityService` 的槽位就是按"一个任务吃多少 CPU"设的 ✓） |
| `logSearchProgress` | **false** | 是否打印求解日志 ✓ 生产关掉 ✓（实测会刷几百行 ✓）调试时打开 ✓ |

> ⚠ **`production()` 这个名字值得注意** ✓ —— 它表明运维配置与代码分离的现状：
> 三个参数**硬编码在代码里** ✗ 而 P3 的 `WorkerProperties` 提供了
> `time-limit-seconds` / `num-search-workers` 两个配置 ✗ → **两处都能配，且没有对齐检查** ✓
> worker 侧传的是自己读到的值 ✓（`JobRunner` 里 `new SolverSettings(properties.getTimeLimitSeconds(), ...)` ✓）
> 所以 `production()` 现在**只被测试和非-worker路径使用** ✓ 记在这里备查 ✓

### `SolveOutcome`

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

**类注释解释了为什么非要带 `status`** ✓：

> 刻意把 `status` 带出来：原 Python 实现把所有非 OPTIMAL/FEASIBLE 的情况**都压成空列表返回**，
> 导致上层无法区分「超时」与「真的无解」

**这是 P1 要修的一个具体缺陷** ✓ 修好之后的实际收益在 P3 显现了 ✓：
`JobRunner` 里正是用 `outcome.timedOut()` 决定"这次失败该不该重试" ✓
```java
boolean retryable = outcome.timedOut();      // 超时 → 可重试；证明无解 → 不可重试
```
**如果没有 `status`，这个判断根本写不出来** ✗ 只能一律重试 ✓ 或者一律不重试 ✓ 两者都错 ✓

#### `CpSolverStatus` 的取值与含义

| 状态 | 含义 | `hasSolution()` | `timedOut()` |
|---|---|---|---|
| `OPTIMAL` | 证明是最优解 ✓ | ✅ | ✗ |
| `FEASIBLE` | 找到可行解，但**没证明最优**（通常是到时间了 ✓） | ✅ | ✗ |
| `INFEASIBLE` | **证明无解** ✓ 模型本身矛盾 | ✗ | ✗ |
| `UNKNOWN` | 搜索**没跑完**（时间到 / 被停止 / 内存不足） | ✗ | **✅** |
| `MODEL_INVALID` | 模型非法（建模 bug） | ✗ | ✗ |

**这张表是理解整个失败路径的钥匙** ✓：
- `INFEASIBLE` → 用户数据有问题 ✓ **重试一万次也一样** ✗ 所以 `retryable=false` ✓
- `UNKNOWN` → 只是没算完 ✓ 换个空闲时段再算可能就出来了 ✓ 所以 `retryable=true` ✓
- `MODEL_INVALID` → **代码 bug** ✓ 重试没用 ✓ 但会被归到 `retryable=false` ✓（合理 ✓ 因为重试确实没用 ✓ 只是原因不同 ✓）

> ⚠ **`timedOut()` 这个名字比它的实际含义窄** ✗
> `UNKNOWN` 不一定是"超时" ✓ 也可能是"搜索被外部停止"或"内存不足" ✓
> 叫 `searchUnfinished()` 会更准 ✓ —— **但它现在的用途（决定可重试性）不受影响** ✓
> 因为"没算完"这几种情况**都值得重试** ✓ 所以名字不准但**判断是对的** ✓

---

## 2a.5 `SlotVariableIndex` —— 🔴 **本册的核心**（314 行）

**这个类是整个 engine 层唯一负责"建变量"的地方** ✓ 类注释：

> 这是 engine 层唯一负责变量构造的类，**不含任何业务约束** —— 约束全部在 `rule/handler` 里，
> 通过 `compile.RuleContext` 使用这里的能力。

**这条分工极其重要** ✓：`SlotVariableIndex` 只说"**世界上有哪些格子**" ✓
而"哪些格子不能同时被占"是规则的职责 ✓（2b/2c 册 ✓）
**新增一条约束永远不需要改这个类** ✓ —— 这是它最有价值的设计属性 ✓

### 先理解它建立的三种"东西"

```
① 决策变量（IntVar）  slotVar(classId, day, period)
     含义：这个班在这个时段上「第几条任教关系」
     取值：0 .. n-1  = 选了第 i 条任教关系
           n        = 空堂（这是 TimetableExtractor 的"哨兵"约定）
     固定课时段：不存在（返回 null）

② 科目成员布尔（BoolVar）  subjectAt(classId, day, period, subjectId)
     含义：这个班在这个时段上的就是这一科
     ⚠ 它不是一个新变量，而是 ① 的一个「重化」（见下）

③ 教师成员布尔（BoolVar）  teacherAt(teacherId, classId, day, period)
     含义：这个教师在这个时段就在这个班上课
     ⚠ 同理，也是 ① 的重化
```

**②③ 是"派生量"，不是独立的决策** ✓ —— 这个区别是理解全篇的关键 ✓
真正被求解器决定的是 ① ✓ ②③ 只是 ① 的**逻辑函数** ✓ 但因为 CP-SAT 不能直接表达"函数" ✗
所以要**显式建布尔变量并用约束把它和 ① 绑起来** ✓ → 这就是 `membership()` ✓

### `prepare()` —— 建立索引（不建变量）

构造器调 `prepare()` ✓ 它**只建索引、不建任何 CP-SAT 变量** ✓（变量懒创建，见下 ✓）

**逐段看**：

#### ① 教师表

```java
for (ScheduleInput.TeacherInfo t : in.teachers()) {
    teachers.put(t.id(), t);
}
```
`LinkedHashMap` ✓ 保序 ✓（为什么保序见后面的"可复现性"一节 ✓）

#### ② 班级 → `ClassData`（把 `Map<String,Integer>` 折成数组）

```java
Map<Long, ScheduleInput.GradeInfo> gradeById = new LinkedHashMap<>();
for (ScheduleInput.GradeInfo g : in.grades()) {
    gradeById.put(g.id(), g);
}
for (ScheduleInput.ClassInfo c : in.classes()) {
    ScheduleInput.GradeInfo g = gradeById.get(c.gradeId());
    if (g == null) {
        continue;                      // ⚠ 年级缺失 → 跳过这个班
    }
    int[] periods = new int[DAYS];
    int total = 0;
    for (int d = 0; d < DAYS; d++) {
        Integer v = g.periodsPerDay().get(DAY_KEYS[d]);
        periods[d] = (v == null) ? 6 : v;   // ⚠ 该天没配 → 默认 6 节
        total += periods[d];
    }
    classData.put(c.id(), new ClassData(c.id(), periods, total));
}
```

**两个"静默兜底"**：
- 班级的 `gradeId` 找不到对应年级 → **跳过** ✓ 那个班会**完全不出现在课表里** ✗ 不报错 ✓
- 某天没配节数 → **默认 6** ✓

⚠ **注意 `DAYS = 5` 是硬编码的** ✓（`static final int DAYS = 5` ✓ + `DAY_KEYS = {"Mon".."Fri"}` ✓）
**没有"周六周日"** ✓ —— 这是小学数学课表的业务前提 ✓ 但如果将来要支持六天 ✗ 这里的改动点有三处 ✓
（`DAYS` 常量 ✓ `DAY_KEYS` ✓ **以及 `ClassData.periods` 数组的长度约定** ✓）

#### ③ 任教关系 → 全局列表 + 每班的索引

```java
for (ScheduleInput.Assignment a : in.assignments()) {
    int idx = allAssigns.size();
    allAssigns.add(a);
    classAssigns.computeIfAbsent(a.classId(), k -> new ArrayList<>()).add(idx);
}
```

🔴 **这是全篇最重要的一个数据结构** ✓

- `allAssigns` = **全局**的任教关系列表 ✓ 下标是"全局下标" ✓
- `classAssigns[classId]` = 该班的任教关系在 `allAssigns` 里的**下标列表** ✓
- 而 **`slotVar` 的取值范围是 `0 .. 该班任教关系条数`** ✗ —— **是"局部下标"** ✗✓

⚠ **两者之间有一次转换** ✓ 看 `slotVar`：
```java
List<Integer> indices = assignIndicesOf(classId);       // 局部 → 全局 的映射表
IntVar v = model.newIntVar(0, indices.size(), ...);     // 上界 = 条数（多出的那个值当空堂）
```
和 `TimetableExtractor`：
```java
long localIdx = solver.value(v);
if (localIdx >= indices.size()) { continue; }           // 空堂哨兵
ScheduleInput.Assignment a = index.assignmentAt(indices.get((int) localIdx));   // 局部 → 全局
```

> 💡 **为什么要分"全局下标"和"局部下标"**：
> `newIntVar` 的**上界越小越好** ✗ —— 上界 24 的变量比上界 5 的变量更"难搜" ✓
> 用局部下标让每个班的变量上界 = 自己班的任教关系条数 ✓ **模型小得多** ✓
> 代价是多一层映射 ✓ 但映射是 `indices.get(localIdx)` 一次查表 ✓ 几乎免费 ✓
>
> **这是一个典型的"为求解性能付一次工程复杂度"的取舍** ✓ 而且做得干净 ✓

#### ④ 周课时：年级级 → 班级级

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

**`grade_subjects` 是年级级的** ✓（一个年级的语文每周 8 节 ✓）**而排课是班级级的** ✓
所以这里把年级级**展开到每个班** ✓ —— 同年级的班拿到同一份周课时 ✓

⚠ **双层循环的复杂度是 O(年级科目数 × 班级数)** ✓ 数据量小（12 × 4 ✓）无所谓 ✓
但如果 160 个班 × 100 条年级科目 = 16,000 次 ✓ 也还是可以接受 ✓
**不过它确实可以写成先按 `gradeId` 分组、再一次遍历班级** ✓ —— 记在这里作为观察 ✓

#### ⑤ 每班有哪些科目（`TreeSet`！）

```java
for (Map.Entry<Long, ClassData> e : classData.entrySet()) {
    Set<Long> sids = new TreeSet<>();                    // 👈 有序集合
    for (int gi : classAssigns.getOrDefault(e.getKey(), List.of())) {
        sids.add(allAssigns.get(gi).subjectId());
    }
    classSubjects.put(e.getKey(), sids);
}
```
用 `TreeSet` 而不是 `HashSet` ✓ 按 `subjectId` **数值排序** ✓

#### ⑥ 固定课：年级级 → 班级级，**并且要求"恰好一条任教关系"**

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

🔴 **这是整个类里唯一抛异常的地方** ✓ 也是最值得理解的一处 ✓

**为什么必须"恰好一条"**：固定课要落在一个具体时段 ✓
而"这个班这个时段上这个科目"要能**唯一确定教师** ✓
如果这个班这个科目有 2 条任教关系（两个老师都能教 ✓）✗ 那就不知道该派谁 ✓
**0 条**（没人教这个科目 ✗）自然也不行 ✓

**所以它把"配置错误"变成了一个建模期的异常** ✓ —— 而不是让它变成一个无解模型 ✓
这正是 `ConstructionInfeasible` 存在的意义（见 §2a.7）✓

⚠ **注意它用的是 `throw` 而不是"跳过这条固定课"** ✗ —— 这是**有意的** ✓
"跳过"会让排课**静默地不遵守用户的固定课要求** ✗ 用户看到课表却不知道被忽略了 ✓
**报错**虽然打断流程 ✓ 但至少是**诚实的** ✓

> 💡 这与 `ClassRef`/`GradeRef` 那两个"静默兜底"（找不到年级就跳过 ✗）形成对比 ✓
> 判断标准是：**这个数据缺失会导致"用户看到的结果与他要求的不符"吗** ✓
> - 固定课被忽略 → **是** ✗ 所以报错 ✓
> - 某天节数没配 → 用默认值 6 ✓ **用户从没说过要 6** ✓ 所以兜底可以接受 ✓（虽然也不理想 ✓）

#### ⑦ 教师 → 他教哪些班

```java
for (ScheduleInput.Assignment a : in.assignments()) {
    teacherClasses.computeIfAbsent(a.teacherId(), k -> new LinkedHashSet<>()).add(a.classId());
}
```
`LinkedHashSet` ✓ 保序 ✓ 用于 `teacherTeachesClass(teacherId, classId)` 的 O(1) 判断 ✓

### 🔴 变量构造：懒创建 + 缓存

**这是本类最重要的设计** ✓ 类注释：

> **关键设计：成员布尔懒创建 + 缓存**。
> `subjectAt(...)` 与 `teacherAt(...)` 第一次被请求时才建变量，之后复用同一个 `BoolVar`。
> 原 Python 实现为「教师冲突」和「教师周上限」**各建了一套完全相同的教师成员布尔**（约占 28% 的变量），
> 这里靠缓存自然消除重复。

#### `slotVar(classId, day, period)` —— 决策变量

```java
public IntVar slotVar(long classId, int day, int period) {
    SlotKey key = new SlotKey(classId, day, period);
    if (fixed.containsKey(key)) {
        return null;                     // ① 固定课时段不参与决策
    }
    IntVar cached = slotVars.get(key);
    if (cached != null) {
        return cached;                   // ② 已建过，复用
    }
    List<Integer> indices = assignIndicesOf(classId);
    if (indices.isEmpty()) {
        return null;                     // ③ 该班没有任何任教关系
    }
    IntVar v = model.newIntVar(0, indices.size(),
            String.format("c%d_d%d_p%d", classId, day, period));
    slotVars.put(key, v);
    return v;
}
```

**三条提前返回，各有理由**：

| # | 条件 | 返回 | 含义 |
|---|---|---|---|
| ① | 是固定课时段 | `null` | 那个时段**已经被占死** ✓ 不需要决策 ✓（`TimetableExtractor` 会直接从 `fixed` 里取 ✓） |
| ② | 缓存命中 | 缓存的 `IntVar` | 同一个格子**在 CP-SAT 里只能有一个变量** ✓ 重复建会让约束挂在不同变量上 ✗ 语义就断了 ✓ |
| ③ | 该班没有任教关系 | `null` | 没有任何可排的课 ✓ 变量无从谈起 ✓ |

⚠ **`null` 是三件事的共同返回值** ✗ —— 调用方（所有 handler ✓）**必须逐一判空** ✓
类注释没有集中说明这一点 ✓ 每个方法各自写了（"固定课时段返回 null" ✓）✓

> ⚠ 这是"**用 null 表达三种不同的'没有'**" ✗ —— 一个可以改进的点 ✓
> 比如 `Optional<IntVar>` ✓ 或引入一个 `SlotState` 枚举 ✓
> **但代价是每个 handler 的代码都变长** ✓ —— 这是一个真实的取舍 ✓ 现在这样也能用 ✓
> 关键在于**文档说清了三种情况** ✓ 读的人不会漏 ✓

#### `subjectAt(classId, day, period, subjectId)` —— 科目成员布尔

```java
public BoolVar subjectAt(long classId, int day, int period, long subjectId) {
    SubjectSlotKey key = new SubjectSlotKey(classId, day, period, subjectId);
    BoolVar cached = subjectSlot.get(key);
    if (cached != null) { return cached; }

    IntVar v = slotVar(classId, day, period);
    if (v == null) { return null; }                   // 固定课 / 无任教关系

    List<Integer> indices = assignIndicesOf(classId);
    List<Integer> match = new ArrayList<>();
    for (int li = 0; li < indices.size(); li++) {
        if (assignmentAt(indices.get(li)).subjectId() == subjectId) {
            match.add(li);                            // 👈 收集"局部下标"
        }
    }
    if (match.isEmpty()) { return null; }             // 这个班根本没有这一科

    BoolVar b = membership(v, match, String.format("ss_%d_%d_%d_%d", classId, day, period, subjectId));
    subjectSlot.put(key, b);
    return b;
}
```

**四步**：查缓存 → 拿到决策变量 → **找出这个科目对应的所有任教关系（局部下标）** → 重化 ✓

**`match` 可能是多条** ✓ 比如语文有 2 条任教关系（两个语文老师 ✓）
→ 那么"这个时段是语文" = "选了这 2 条中的任意一条" ✓ —— 正是 `membership` 的"属于集合"语义 ✓

#### `teacherAt(teacherId, classId, day, period)` —— 教师成员布尔

**结构与 `subjectAt` 完全同构** ✓ 只是 `match` 的筛选条件换成 `teacherId` ✓

⚠ **注意缓存的 key 里带了 `teacherId`** ✓
`TeacherSlotKey(teacherId, classId, day, period)` ✓ —— 所以"张三在 1 班周一第 1 节"和"李四在 1 班周一第 1 节"是**两个不同的布尔** ✓
而它们**互斥**（一个格子只能上一个老师的课 ✓）—— 这个互斥**不需要显式约束** ✗ 因为从 `membership` 的定义自然成立 ✓（都来自同一个 `v` ✓）

> 💡 **这就是"重化"的威力**：不需要为"同一时段只能有一个老师"写约束 ✓
> 因为两个 `teacherAt` 都是同一个 `slotVar` 的函数 ✓ **函数的值唯一** ✓ 自然互斥 ✓
> 这比"建两套独立布尔再补一条互斥约束"**既省变量又不可能出错** ✓

#### 🔴 `membership(v, matchIndices, prefix)` —— 重化（16 行，但这是全类的数学核心）

```java
private BoolVar membership(IntVar v, List<Integer> matchIndices, String prefix) {
    BoolVar b = model.newBoolVar(prefix);
    List<Literal> conditions = new ArrayList<>(matchIndices.size());
    for (int li : matchIndices) {
        BoolVar eq = model.newBoolVar(prefix + "_eq" + li);
        model.addEquality(v, li).onlyEnforceIf(eq);          // eq ⇒ v == li
        model.addDifferent(v, li).onlyEnforceIf(eq.not());   // ¬eq ⇒ v != li
        conditions.add(eq);
    }
    model.addBoolOr(conditions).onlyEnforceIf(b);            // b ⇒ 至少一个 eq
    List<Literal> negated = new ArrayList<>(conditions.size());
    for (Literal c : conditions) {
        negated.add(c.not());
    }
    model.addBoolAnd(negated).onlyEnforceIf(b.not());        // ¬b ⇒ 所有 eq 都为假
    return b;
}
```

**目标**：让 `b` **恰好等价于**「`v` 的值落在 `matchIndices` 集合里」✓
即 `b ↔ (v ∈ matchIndices)` ✓

**🔑 关键概念：`onlyEnforceIf` 是"单向蕴含"，不是"等价"**

CP-SAT 的 `constraint.onlyEnforceIf(lit)` 的语义是：
> **当 `lit` 为真时，这个约束必须成立；当 `lit` 为假时，这个约束不被强制。**

也就是 `lit ⇒ constraint` ✓ —— **只有一半** ✗

**要得到"等价"，必须两个方向都写** ✓ 这正是这段代码在做的 ✓：

| 方向 | 代码 | 语义 |
|---|---|---|
| `eq ⇒ (v == li)` | `addEquality(v, li).onlyEnforceIf(eq)` | eq 为真 ⇒ v 必须等于 li |
| `¬eq ⇒ (v != li)` | `addDifferent(v, li).onlyEnforceIf(eq.not())` | eq 为假 ⇒ v 必须**不**等于 li |
| **合起来** | | **`eq ↔ (v == li)`** ✓ |

**然后 `b` 与这些 `eq` 的关系也是两个方向**：

| 方向 | 代码 | 语义 |
|---|---|---|
| `b ⇒ 至少一个 eq` | `addBoolOr(conditions).onlyEnforceIf(b)` | b 为真 ⇒ 至少有一条匹配 |
| `¬b ⇒ 所有 eq 都为假` | `addBoolAnd(negated).onlyEnforceIf(b.not())` | b 为假 ⇒ 一条都不匹配 |
| **合起来** | | **`b ↔ (v ∈ matchIndices)`** ✓ |

> 🔴 **这是全项目最容易写错、也最难发现的一处** ✗
> 如果只写第一个方向（`b ⇒ 至少一个 eq`）✗ 那么：
> - **`b = false` 时不要求任何事** ✓ 于是求解器可以**随便把 `b` 设成 false** ✗
> - 后果：软约束的惩罚项**永远不会被计入** ✓ **求解器可以靠"把 b 都设成假"来零惩罚** ✗
> - 症状：模型"能解"、目标值是 0、但**软约束完全没生效** ✓ 而且**不报错** ✓
>
> 这类错误在**反例注入测试**里才会暴露 ✓（solver-core 有 28 个此类测试 ✓ 见架构说明 §8）
> 这正是"每条规则要有反例注入 + 灵敏度反证"那条决策门的意义 ✓

**为什么需要中间的 `eq` 变量** ✓
直觉上想写 `model.addEquality(v, li).onlyEnforceIf(?)` ✗ —— 但 `v == li` 是**两个整数之间的关系** ✓
`onlyEnforceIf` 需要一个**布尔** ✓ 所以必须先为每个候选值建一个"是不是这个值"的布尔 ✓
这是 CP-SAT 里表达"集合成员关系"的**标准套路** ✓

**变量数量**：`1`（`b`）+ `|matchIndices|`（每个 `eq`）✓
⚠ 所以"科目成员布尔"实际上不只是一个变量 ✓ 而是一组 ✓
**但因为缓存，同一组只会被建一次** ✓ 这就是 28% 变量节省的来源 ✓

### ⚠ 可复现性：为什么全用 `LinkedHashMap` / `TreeSet`

类注释：
> 为保持模型可复现，所有需要遍历的容器都保序（`LinkedHashMap` / `TreeSet` / `ArrayList`），
> 不使用 `HashMap` / `HashSet`。

**为什么这件事很重要** ✓：CP-SAT 的 `modelStats()` 里的 `model_fingerprint` 是对
**整个 `CpModelProto` 序列化后**算的摘要 ✓
而**变量的创建顺序**会影响序列化结果 ✓

所以如果容器是无序的 ✗：
- 每次运行 `HashMap` 的遍历顺序可能不同 ✓
- → 变量创建顺序不同 ✓
- → **同一个输入算出的指纹不同** ✗
- → 指纹就**不能当回归基线** ✗ 而那正是它唯一的用途 ✓

> 💡 **这是"用 `HashMap` 还是 `LinkedHashMap` 不影响正确性"这个常见认知的一个反例** ✗
> 在这个项目里，它影响的是**可验证性** ✓
> 与 P1 踩过的另一个同族坑呼应：**"报第一个冲突"依赖遍历顺序**（`HashMap` vs 插入序 ✓ 见踩坑总结）
>
> ⚠ **但"保序"只解决了一半** ✗ —— 变量的**懒创建**意味着：
> 创建顺序还取决于**规则请求变量的顺序** ✓ 也就是 `RuleCompiler` 里规则的编译顺序 ✓
> 那个顺序由**规则列表的顺序**决定 ✓ 是确定的（来自数据库查询/规则集定义 ✓）
> **所以仍然可复现 ✓ —— 但"调整规则顺序会改变指纹"** ✓ 这是一个隐含约束 ✓
> 读代码时不会想到 ✓ **值得记在这里** ✓

### 数据访问器一览

| 方法 | 返回 | 备注 |
|---|---|---|
| `model()` | `CpModel` | 给 handler 加约束用 ✓ |
| `classDataInOrder()` | `List<ClassData>` | **保序**（`LinkedHashMap.values()` 的拷贝 ✓） |
| `classData(classId)` | `ClassData` | 找不到**抛 `IllegalArgumentException`** ✓（不是返回 null ✓ 这是好的 ✓） |
| `allClassIds()` | `Set<Long>` | ⚠ `Collections.unmodifiableSet(classData.keySet())` —— 是**视图**不是拷贝 ✓ 内容会跟着变 ✓ 但这里索引建完就固定了 ✓ 无害 ✓ |
| `assignIndicesOf(classId)` | `List<Integer>` | **局部 → 全局** 的映射表 ✓ |
| `assignmentAt(globalIndex)` | `Assignment` | **全局下标 → 对象** ✓ |
| `weeklyHoursOf(classId)` | `Map<Long,Integer>` | 保序 ✓ |
| `subjectsOf(classId)` | `Set<Long>` | `TreeSet` ✓ 按 id 排序 ✓ |
| `teachersInOrder()` | `Collection<TeacherInfo>` | 保序 ✓ |
| `teacher(teacherId)` | `TeacherInfo` | 找不到抛异常 ✓ |
| `teacherTeachesClass(t, c)` | `boolean` | O(1) ✓ |
| `lastPeriod(classId, day)` | `int` | ⚠ **名字有歧义**，见下 |
| `isFixed` / `fixedAt` | `boolean` / `FixedEntry` | 固定课查询 ✓ |
| `slotVar` / `subjectAt` / `teacherAt` | `IntVar` / `BoolVar` / `BoolVar` | 变量（可能 null ✓） |

⚠ **`lastPeriod(classId, day)` 的实现是 `classData(classId).periods()[day]`** ✗ ——
它返回的是**当天的节数**（比如 6 ✓）**不是"最后一节的序号"**（那是 5 ✓）

**如果调用方写**：
```java
for (int p = 0; p <= index.lastPeriod(classId, day); p++)     // ✗ p == 6 越界
```
就错了 ✗ 正确写法是 `< lastPeriod(...)` ✓（`TimetableExtractor` 用的就是 `<` ✓）

**这就是名字带来的陷阱** ✓ —— `periodCount(classId, day)` 会准确得多 ✓
（只差一个词，但能避免一类 off-by-one ✗ 我记在这里，没有改动代码 ✓）

---

## 2a.6 `TimetableExtractor` —— 把解还原成课表（61 行）

```java
public static List<TimetableEntry> extract(CpSolver solver, SlotVariableIndex index) {
    List<TimetableEntry> out = new ArrayList<>();
    for (ClassData cd : index.classDataInOrder()) {
        long classId = cd.classId();
        List<Integer> indices = index.assignIndicesOf(classId);
        if (indices.isEmpty()) { continue; }
        for (int day = 0; day < DAYS; day++) {
            for (int period = 0; period < cd.periods()[day]; period++) {
                long subjectId;
                long teacherId;
                SlotVariableIndex.FixedEntry fixedEntry = index.fixedAt(classId, day, period);
                if (fixedEntry != null) {
                    subjectId = fixedEntry.subjectId();
                    teacherId = fixedEntry.teacherId();
                } else {
                    IntVar v = index.slotVar(classId, day, period);
                    if (v == null) { continue; }
                    long localIdx = solver.value(v);
                    if (localIdx >= indices.size()) { continue; }   // 空堂哨兵
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

### 🔴 空堂哨兵："没有课"用一个越界的下标表示

类注释：
> 关键点是**空堂哨兵**：时段变量的取值范围是 `0..n`，其中 `0..n-1` 表示第几条任教关系，
> 而 `n` 表示这个时段没有课。

| `solver.value(v)` 的值 | 含义 |
|---|---|
| `0 .. n-1` | 选第 i 条任教关系 ✓ |
| **`n`** | **这个时段空着** ✓ |

**为什么需要一个"空堂"取值** ✗ —— 因为**课表不可能被排满** ✓
一个班一周有 30 个格子（5 天 × 6 节 ✓）而周课时总和可能只有 26 节 ✓
**剩下 4 个格子必须有东西可表达** ✗ 否则模型就是 INFEASIBLE ✓

**为什么用"多一个取值"而不是"允许变量不存在"** ✓
- 如果"空堂"= 变量缺失 ✗ 那"这个格子必须空着"就无法表达 ✓ 也无法对空堂加约束 ✓（比如"避免最后一节空着"将来可能要做 ✓）
- 用哨兵 ✓ **每个格子都有一个变量** ✓ 约束可以作用在任意格子上 ✓ 代价只是值的范围大 1 ✓

> 💡 **这个哨兵约定散落在三个地方** ✗：
> ① `slotVar` 的 `newIntVar(0, indices.size(), ...)`（上界 ✓）
> ② `TimetableExtractor` 的 `localIdx >= indices.size()`（判断 ✓）
> ③ 所有 handler 用 `subjectAt` / `teacherAt` 时**不需要知道它** ✓（因为它们用的是重化后的布尔 ✓）
>
> **所以只有两处代码依赖这个约定** ✓ 关系是紧的 ✓ 但**没有集中的文档** ✗
> 本册记在这里 ✓ —— 改动 `slotVar` 的上界必须同时改 `TimetableExtractor` ✓

### 三段循环的顺序 = 输出的顺序

`classDataInOrder()` → `day` 0..4 → `period` 0..n ✓
所以输出的 `TimetableEntry` 列表是**按班级、再按星期、再按节次**排好的 ✓
⚠ 这个顺序**没有在注释里说明** ✓ 但 P3 的 `JobRunner` 把它当"明细列表"打包发送 ✓
落库时顺序无所谓（有主键 ✓）✓ 但如果将来做"按顺序比对" ✗ 就会依赖它 ✓

### 固定课的两条路径

```
if (fixedEntry != null)   →  从索引里直接取（不查求解器）
else                      →  solver.value(v) 查决策变量的取值
```

⚠ **固定课从不经过决策变量** ✓ —— 这是"固定课不参与决策"这个设计的兑现 ✓
也就是说：**固定课的教师是建模期就定死的** ✓ 求解器**没有选择权** ✓
（如果某个班某科目有 2 条任教关系 ✗ 建模期就会抛 `ConstructionInfeasible` ✓ 见 §2a.5 ⑥ ✓ —— 设计是自洽的 ✓）

---

## 2a.7 `ConstructionInfeasible` / `SolverNatives` / `ModelFingerprints`

### `ConstructionInfeasible` —— 把"配置错误"与"真的无解"分开

```java
public class ConstructionInfeasible extends RuntimeException {
    public ConstructionInfeasible(String message) { super(message); }
}
```

类注释：
> 原 Python 实现把这些情况与「超时」「真无解」一起 `return []`，上层只能笼统报
> 「排课无解，请检查任教关系、周课时、固定时段和教师课时上限」。
> 这个异常让调用方至少能拿到**具体原因**。

**它和 `SolveOutcome.status` 是配套的两种"失败"** ✓：

| 失败类型 | 什么时候知道 | 怎么表达 | 上层该做什么 |
|---|---|---|---|
| **建模期就矛盾** | 还没求解 | `throw ConstructionInfeasible` ✓ | 告诉用户**具体哪条配置**错了 ✓ 重试无用 ✓ |
| **模型合法但无解** | 求解后 | `status == INFEASIBLE` ✓ | 报"请检查……" ✓ 重试无用 ✓ |
| **没算完** | 求解后 | `status == UNKNOWN` ✓ | **重试** ✓ |

> ⚠ **但 `ConstructionInfeasible` 现在被怎么处理，值得核对** ✗
> 它是一个 `RuntimeException` ✓ 会冒到 `RuleCompiler` → `OrbitSolver.compile` → 调用方 ✓
> 调用方（`JobRunner` / `TimetableGenerationService`）**如果不专门 catch 它** ✗
> 就会走"通用异常 → 可重试的失败"那条路 ✗ —— 而它**重试一万次也一样** ✓
> **我在 5b 册读到 `JobRunner` 的 catch 是 `catch (RuntimeException e)` → `retryable=true`** ✓
> 所以这里**很可能有一个"配置错误被当成可重试"的问题** ✓
> ⚠ 我**没有验证**这条链路（需要读 `TimetableGenerationService` / `DiagnosisHook` 才能确认 ✓）
> **标记为待核对** ✓ 不改代码 ✓

### `SolverNatives` —— 装原生库（25 行，含一条血泪注释）

```java
private static boolean loaded;

public static synchronized void load() {
    if (!loaded) {
        Loader.loadNativeLibraries();
        loaded = true;
    }
}
```

**幂等 + `synchronized`** ✓ 两个细节都对：

| 细节 | 为什么 |
|---|---|
| `if (!loaded)` 检查 | OR-Tools 的 `Loader.loadNativeLibraries()` 重复调用虽无害 ✓ 但没必要 ✓ |
| **`synchronized`** | ⚠ 多个线程同时第一次求解时 ✓ 不加锁可能让两个线程同时进入 `loadNativeLibraries()` ✗ 而 JNI 库的加载**不是线程安全的** ✓ 加锁还顺带保证了 `loaded` 的可见性 ✓（所以 `loaded` 不需要 `volatile` ✓ —— 它只在同步块内被访问 ✓） |

#### 🔴 类注释里那条最值钱的实测记录

> 注意 JDK 版本：实测 **JDK 23 上求解会在 `msvcp140.dll` 里崩溃**
> （`EXCEPTION_ACCESS_VIOLATION`，建模正常但 `CpSolver.solve()` 段错误），而 **JDK 21 LTS 正常**。项目统一使用 JDK 21。

**这条记录的价值极高** ✗ —— 因为它的症状**极难归因** ✓：

| 现象 | 一般会怎么想 | 实际 |
|---|---|---|
| 建模通过（`compile` 正常 ✓） | "模型没问题" | 对 ✓ |
| `solve()` 段错误 ✓ **进程直接死，没有异常栈** ✗ | "OR-Tools 有 bug" / "内存不够" | 都不是 ✓ |
| 崩溃点在 `msvcp140.dll`（MSVC 运行库 ✓） | "环境缺 VC++ 运行库" ✗ | 是 **JNI 与 JDK 版本的兼容问题** ✓ |

**写在 `SolverNatives` 这个类里是最合适的位置** ✓ ——
因为每个读"原生库怎么加载"的人都会看到它 ✓ 而这正是最容易怀疑的地方 ✓

> 💡 **这是"把踩坑记录写在最相关的那一行代码旁边"的典范** ✓
> 比写在 `踩坑总结.md` 里更有用 ✓ —— 因为**崩溃时人会来看代码，不会去翻文档** ✓
> （`踩坑总结.md` 里应该也有一份 ✓ 但这里的这一份才是会被真正读到的 ✓）

### `ModelFingerprints` —— 从 `modelStats()` 文本里正则取指纹

```java
private static final Pattern PATTERN =
        Pattern.compile("model_fingerprint:\\s*(0x[0-9a-fA-F]+)");

public static String of(CpModel model) { return ofStats(model.modelStats()); }

public static String ofStats(String modelStats) {
    Matcher m = PATTERN.matcher(modelStats == null ? "" : modelStats);
    return m.find() ? m.group(1) : "(未找到)";
}
```

**用正则从人类可读的文本里抠一个值** ✗ —— 这看起来有点脏 ✓ 但它有理由：
**CP-SAT 只通过 `modelStats()` 暴露指纹** ✓ 没有结构化的 API ✓（实测确认 ✓）

#### 🔴 类注释里的第二个"跨语言陷阱"

> **使用注意**：指纹是对整个 `CpModelProto` 序列化后算的摘要，因此它对「绑定特有的记账字段」敏感。
> 已实测的坑：Python 的 `model.Minimize()` 会往目标里写 `scaling_factor = 1.0`，
> 而 Java 的 `minimize()` 留空（0.0），**语义相同但指纹不同**。
>
> 结论：指纹适合做**同一实现内**的回归基线，不适合直接当跨语言等价判据；跨语言要比对 proto 的逐字段内容。

**这条关于"指纹适不适合当判据"的判断，是本册最值得带走的一课** ✓

| 用途 | 指纹合适吗 |
|---|---|
| **同一实现内的回归**（"我重构之后模型没变" ✓） | ✅ **非常合适** ✓ 一个字符串就够 ✓ |
| **跨语言等价判据**（"Java 和 Python 建的模型一样" ✓） | ❌ **不合适** ✗ 会被记账字段污染 ✓ |

**为什么**：指纹是**整个 proto 序列化后的摘要** ✓ 包含所有字段 ✓
包括**与语义无关的默认值差异** ✗（`scaling_factor` 就是 ✓）
**一个字节不同，整个指纹就完全不同** ✗ —— 摘要函数没有"部分相似"的概念 ✓

**正确做法**：**逐字段比对 proto** ✓ —— 这样遇到差异时能**指出是哪个字段** ✓
而不是"两个 16 进制串不一样" ✗ 然后无从下手 ✓

> 💡 这与 P1 的另一个判断是同一族：**"同一个实例两边最优目标值相同"作为等价判据** ✓
> 而**"解相同"不是判据** ✗（因为 CP-SAT 在多个最优解间选择不确定 ✓）
> **两个例子都在说同一件事：判据要选"语义上必须相同"的量，而不是"实现上恰好相同"的量** ✓

#### ⚠ `"(未找到)"` 这个返回值是个隐患

```java
return m.find() ? m.group(1) : "(未找到)";
```

**当正则没匹配上时，它返回一个看起来像指纹的普通字符串** ✗
如果这个值被存进 `schedule_versions.model_fingerprint` ✗ 数据库里就会出现 `(未找到)` ✓
**而它是一个合法的字符串** ✓ 不会报错 ✓ 之后拿它做比对时又永远不相等 ✗

**更安全的做法**：返回 `Optional<String>` ✓ 或抛异常 ✓ 或至少用 `null` ✓
**但现在的用途是"调试/回归基线"** ✓ 调用方（`OrbitSolver.fingerprintOf` ✓）主要是打印 ✓ 所以危害有限 ✓

⚠ 记在这里 ✓ 没有改动代码 ✓

---

## 2a.8 `OrbitSolver` —— 唯一门面

```java
public final class OrbitSolver {

    public static RuleRegistry builtinRules() { return BuiltinRules.registry(); }

    public static CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules) {
        return compile(input, rules, BuiltinRules.registry());
    }

    public static CompiledModel compile(ScheduleInput input, List<ConstraintRule> rules,
                                        RuleRegistry registry) {
        SolverNatives.load();
        return new RuleCompiler(registry).compile(input, rules);
    }

    public static SolveOutcome solve(CompiledModel compiled, SolverSettings settings) { ... }

    public static SolveOutcome solve(ScheduleInput input, List<ConstraintRule> rules,
                                     SolverSettings settings) {
        return solve(compile(input, rules), settings);
    }

    public static String fingerprintOf(CpModel model) { return ModelFingerprints.of(model); }

    private OrbitSolver() { }
}
```

**五个公开方法 + 一个私有构造器** ✓ `final` + 私有构造 = 纯静态工具类 ✓

### `compile` 与 `solve` 为什么分开

javadoc 给了理由：
> `solve` 与 `compile` 刻意分开：**指比对、规则「试算影响」、不可行性诊断都只需要编译结果，不需要真的求解**。

**这不是"接口整洁"的考虑，是性能考虑** ✓：

| 场景 | 需要什么 | 为什么不要 `solve` |
|---|---|---|
| **指纹比对**（`GoldenModelTest`） | 只要 `CompiledModel.modelFingerprint()` ✓ | 求解要花几十秒 ✓ 完全不需要 ✓ |
| **规则试算**（`RuleTrialService`） | 只要"编译得过去吗 / 变量数变了吗" ✓ | 同上 ✓ 而且试算是同步接口 ✓ 不能让用户等 60 秒 ✓ |
| **不可行性诊断**（`ConstructionInfeasible`） | 只要 `compile` ✓ 因为**建模期的矛盾在编译时就抛了** ✓ | 同上 ✓ |
| **静态诊断**（`StaticDiagnostics`） | 连 `compile` 都不用 ✓ 纯数据检查 ✓ | |

> ⚠ 这也解释了 P3 里那处设计：**深诊断（最小冲突集）要解好几次** ✓
> 而它跑在**失败路径**上 ✓ 所以只在任务已经失败时才做 ✓（见 5c/5d 册 ✓）

### `SolverNatives.load()` 在 `compile` 和 `solve` 里**都调了**

```java
public static CompiledModel compile(...) { SolverNatives.load(); ... }
public static SolveOutcome solve(...)    { SolverNatives.load(); ... }
```

⚠ 看起来重复 ✗ 但**必须这样** ✓：
- `compile` 会 `new CpModel()` 并建变量 ✓ **这一步就需要原生库** ✓
- `solve` 单独被调用时（`solve(compiled, settings)` ✓）也必须保证库已加载 ✓
- 如果只在 `compile` 里调 ✗ 那么"先 compile 再单独调 solve"没问题 ✓ 但如果有人**直接构造 `CompiledModel` 再 solve** ✗ 就会崩 ✓

**而 `load()` 是幂等的** ✓ 所以重复调用零成本 ✓ —— **幂等性让"防御性重复调用"成为好选择** ✓

### ⚠ 一处过时的 javadoc

```java
/** 系统内置的规则注册表（3 个不变量/数据驱动 + 2 个可配置）。 */
public static RuleRegistry builtinRules() {
    return BuiltinRules.registry();
}
```

**实际是 10 条规则** ✓（3 条 `mandatory` 不变量 + **7 条**可配置 ✓ 见架构说明 §3.4 ✓）

**"3 + 2" 是规则系统早期只有 5 条时的数字** ✗ 后来加到 10 条 ✓ 但这句话没跟着改 ✓

⚠ **这类"数字写在注释里"的过时** 是最常见的文档腐坏形式 ✓ —— 它**曾经是对的** ✓ 所以不会有人怀疑 ✓

> 💡 **可推广的做法**：注释里尽量**不写会变的数字** ✓
> 写成"系统内置的规则注册表（见 `BuiltinRules`）" ✓ 让读者去唯一的事实来源看 ✓
> —— 本册 §2a.5 讲 `Assignment` 顺序时提到的"注释钉住契约"是反例 ✓
> **区别在于：顺序是"必须固定的契约"（写下来是对的 ✓），而数量是"会变的实现细节"（写下来就会过期 ✗）** ✓

---

## 本册小结

**把这一册串成一句话**：

> `ScheduleInput` 描述"输入是什么" ✓ → `SlotVariableIndex` **把每个格子变成一个整数变量**，
> 并把"这个格子是不是某科目/某老师"**重化成布尔** ✓ → 规则（2b/2c/2d）在这些变量上加约束 ✓
> → `OrbitSolver.solve` 交给 CP-SAT ✓ → `TimetableExtractor` **按哨兵约定把值翻译回课表** ✓
> → 全过程可以用 `ModelFingerprints` 盯着"模型有没有被悄悄改掉" ✓

**最该带走的三件事**：

1. **`onlyEnforceIf` 是单向蕴含，等价要写两个方向**（§2a.5 `membership`）✓
   只写一个方向的后果是"软约束静默失效、目标值恒为 0" ✗ 而且不报错 ✓
2. **空堂哨兵**（§2a.6）—— 变量的上界 `0..n` 里，`n` 是"没课" ✓ 只有两处代码依赖它 ✓
3. **指纹只能当同一实现的回归基线，不能当跨语言判据**（§2a.7）✓
   `scaling_factor` 那个坑就是证据 ✓ 跨语言要比对 proto 逐字段 ✓

### 本册发现的问题（6 处，都没动代码）

| # | 问题 | 性质 |
|---|---|---|
| **1** | `OrbitSolver.builtinRules()` 的 javadoc 写"3 + 2"，**实际是 3 + 7 = 10** | ⚠ **过时注释** ✓ 早期 5 条规则时代的数字 ✓ |
| **2** | `ClassData` 是 record 但含**可变数组** `int[] periods` | ⚠ **隐患** ✓ 调用方可以悄悄改掉索引里的数据 ✓ 当前用法都是只读 ✓ 所以无 bug ✓ |
| **3** | `lastPeriod(classId, day)` **返回当天的节数**（6），不是"最后一节的序号"（5） | ⚠ **命名陷阱** ✓ 会导致 off-by-one ✓ 建议改名 `periodCount` ✓（`TimetableExtractor` 用的是 `<` ✓ 当前正确 ✓） |
| **4** | `ModelFingerprints.ofStats` 匹配失败时返回 **`"(未找到)"`** | ⚠ 一个**看起来像指纹的字符串** ✓ 若被存库会变成脏数据 ✓ 且永不相等 ✓ |
| **5** | `SolveOutcome.timedOut()` 把 `UNKNOWN` 一律当"超时" | ⚠ **名字比含义窄** ✓（也可能被停止/内存不足 ✓）但用于"可重试性"判断**是对的** ✓ |
| **6** | 🔴 **`ConstructionInfeasible`（建模期配置错误）被当成"可重试的失败"** | ✅ **已核对确认** ✓ `TimetableGenerationService.solve` 直接调 `OrbitSolver.solve`、**没有任何 catch** ✗ → 异常冒到 `JobRunner` 的 `catch (RuntimeException e)` → `reportFailure(..., retryable=true)` ✗<br>**后果**：一条配置错误（例如"固定课的任教关系不是恰好一条"）会被**重试 3 次** ✓ 每次都在同一处失败 ✓ 白白烧 3 次求解配额 ✓ 而且任务最终标成"失败"时**看不出是配置问题** ✗<br>**修法**：`JobRunner` 单独 catch `ConstructionInfeasible` → `retryable=false` ✓（一行的事 ✓）|
| **7** | `TimetableGenerationService` 的 javadoc 里 `{@link ScheduleRunner}` 指向 **P3 已删除的类** | ⚠ **悬空 javadoc 链接** ✓ 严格 javadoc 构建会报错 ✓ 同类问题在 5b 册也出现过（`SchedulingClient.workerId` 的错位注释 ✓）✓ 说明"删类时没清引用"是一个成规模的小毛病 ✓ |

**另外记下两处"隐含约束"**（不是 bug，但读代码的人不会想到）：
- **调整规则顺序会改变模型指纹**（因为变量懒创建，创建顺序依赖规则编译顺序）✓
- **`DAYS = 5` 硬编码在三处**（常量 / `DAY_KEYS` / `ClassData.periods` 的长度约定）✓ 支持六天要一起改 ✓
