# 代码导读 · 5d. 不可行性诊断

> **本册范围**：`orbit-scheduling` 的 `diagnosis/` 包，共 **7 个类**：
> `StaticDiagnostics`、`DiagnosisService`、`DiagnosisController`、`DiagnosisResponse`、
> `DiagnosisFinding`、`ScheduleDiagnosisEntity`、`ScheduleDiagnosisMapper`。
>
> 规则系统（`rule/` 16 个类）见 **5c**。
>
> 上游：[代码导读.md](代码导读.md) · [4a](代码导读-4a-scheduling-排课编排.md) · [4b-2](代码导读-4b2-scheduling-输入快照.md) · [5b](代码导读-5b-任务队列-落库与求解进程.md)

---

## 本模块在系统里的位置

**它回答一个问题：「为什么排不出来？」** ✓

```
用户点了排课 → 失败 → 界面显示：「排课无解，请检查任教关系、周课时、固定时段和教师课时上限」
                          ↑
                    这是 Python 的原文案（P1 逐字保留 ✓）
                    但它把**四种完全不同的原因**压成了一句话 ✗
```

**诊断模块的价值就是把那一句话拆开** ✓：

| 真实原因 | 用户该做什么 | 一句话文案能区分吗 |
|---|---|---|
| 某个班某门课**没配老师** | 去配老师 | ✗ |
| 某班的周课时**超过了座位数** | 减课时或加节数 | ✗ |
| 某位教师**超课时上限** | 减班或调上限 | ✗ |
| 某条**硬规则太紧** | 放宽那条规则 | ✗ |
| **超时未证明** | **延长时间，别改数据** | ✗ ✗ **最危险的一种** |

🔴 **最后一行是重点** ✗ —— 用户看到"请检查任教关系、周课时、固定时段和教师课时上限"时 ✓
**他会真的去改数据** ✗ 而其实什么都没错 ✓ 只是没算完 ✓
**这个模块存在的首要理由，就是不再让用户做这种无用功** ✓

## 阅读顺序建议

1. **`DiagnosisResponse` 的类注释** —— 它把"为什么要分三态"讲透了 ✓ 是本册的立意 ✓
2. **`StaticDiagnostics` 的四类检查** —— 每类都是一个小算法 ✓ 而且**有真实的业务含义** ✓
3. **`DiagnosisService` 的 `note()`** —— 🔴 全项目最值得单独读的一个方法（见 §5d.4 ✓）
4. 其余（Controller / DTO / 实体 / Mapper）扫一眼 ✓

---

## 5d.1 `DiagnosisFinding` —— 结构化事实（33 行）

```java
public record DiagnosisFinding(
        String type,        // 机器可读的类别，如 TEACHER_OVERLOAD
        String severity,    // error | warning | info
        String message,     // 直接可展示的中文说明
        Map<String, Object> payload) {   // 结构化字段

    public static final String ERROR = "error";
    public static final String WARNING = "warning";
    public static final String INFO = "info";
}
```

**类注释讲的是这个项目对 AI 助手的核心设计** ✓：

> 方案 §6.4 的核心主张是：**求解器输出结构化事实，助手只做翻译**。
> 所以这里的 `payload` 必须是**机器可读的字段**，而不是一句写死的中文：
> ```
>   {type:"TEACHER_OVERLOAD", teacher_id:7, actual:24, limit:18, excess:6}
>         ↓ 助手（P4）只做翻译，零风险，不影响求解正确性
>   「张老师这学期排了 24 节数学，上限 18 节，超了 6 节。建议减掉一个班或把上限调到 24。」
> ```

🔴 **这是铁律 5「禁止编造」在数据结构上的落点** ✓ —— 值得展开 ✓：

| 设计 | 效果 |
|---|---|
| **`type` + `payload` 是机器可读的** ✓ | 助手**只能**基于这些字段措辞 ✓ **编不出"张老师超载"这种不存在的事实** ✓ |
| **`message` 是"翻译的默认实现"** ✓ | 没有助手时界面也不空白 ✓ |
| 两者都给 ✓ | 注释：「"没有助手时也不能是一片空白"」✓ |

> 💡 **"事实与措辞分离"是可验证性的前提** ✗：
> 如果只有一个 `message` 字符串 ✗ 那"事实可追溯率"（P4 的决策门 ✓）**根本无法度量** ✗
> 因为没有任何东西可以拿去核对 ✓
> **有了 `payload`，就能自动检查"助手说的每个数字都能在 payload 里找到"** ✓✓
> —— **决策门能不能算，取决于数据结构长什么样** ✗ 这句话值得记住 ✓

⚠ **`severity` 用字符串常量而不是枚举** ✗ —— 与 4c-2 册的 `action` 是同一族 ✓
`DiagnosisFinding.ERROR` / `WARNING` / `INFO` ✓ 三个常量 ✓ **比裸字符串好** ✓ 但仍是 `String` 类型 ✓
（写成 `enum` 能获得编译期穷尽检查 ✓ 代价是 Jackson 序列化要配一下 ✓ 取舍合理 ✓）

### 四类发现的 `type` 取值（散在 `StaticDiagnostics` 里）

| `type` | `severity` | 含义 |
|---|---|---|
| `MISSING_ASSIGNMENT` | **error** | 某班某科有周课时但没老师 |
| `CLASS_CAPACITY_SHORTFALL` | **error** | 周课时总量 > 一周座位数 |
| `TEACHER_OVERLOAD` | **error** | 教师被分配课时 > 他的上限 |
| `FIXED_SLOT_CONFLICT` | **error** | 同年级同一节固定了两门不同的课 |
| `FIXED_SLOT_OUT_OF_RANGE` | **error** | 固定到了当天不存在的节次 |
| **`FIXED_SLOT_SHARED_SLOT`** | **warning** ⚠ | 同一节被多个年级固定 —— **只是"有可能"撞课** ✓ |

🔴 **注意最后一条的严重度是 `warning` 而不是 `error`** ✗ —— 这是一个**刻意的克制** ✓ 见 §5d.2 ③ ✓

---

## 5d.2 `StaticDiagnostics` —— 四类检查（352 行）

### 类注释：为什么静态检查排在深诊断前面

> 真实世界里"排不出来"绝大多数是**数据问题**，不是约束组合太紧：
> - 某个班的周课时加起来超过了它一周的座位数
> - 某位教师被分配的课时超过了他自己的上限
> - 某个班某门课**根本没配老师**（于是"周课时必须排满"这条不变量永远无法满足）
> - 固定课自相矛盾
>
> 这四类都能在**毫秒级**算出来，而且给出的结论是**确定的**（不是"可能"）。
> 相比之下最小冲突集要对模型做松弛再求解，贵得多，应该**只在静态检查一无所获时才用**。
>
> ⚠ 这也是为什么诊断要**先静态、后求解**：先花 1 毫秒排除 90% 的可能，再花几十秒去分析剩下 10%。

🔴 **"确定的"（不是"可能"）这三个字是关键** ✓ —— 它区分了两类诊断的本质 ✓：

| | 静态检查 | 深诊断（最小冲突集） |
|---|---|---|
| 方法 | **集合与算术** ✓ | **反复求解** ✓ |
| 耗时 | **毫秒** ✓ | **几十秒** ✓ |
| 结论 | **确定的**（"一定排不出来" ✓） | **充分的**（"去掉它能排出来"→ 它至少是原因之一 ✓） |
| 失败时会怎样 | 不漏报，但**覆盖不全** ✓ | 覆盖更广，但**可能一无所获** ✓ |

**所以顺序不能反** ✓ —— 先做便宜且确定的 ✓ 再做贵且不完整的 ✓
⚠ 反过来（先深诊断 ✗）会让 90% 的常见问题都要等几十秒 ✓ **体验灾难** ✓

### ⚠ 类注释里的一个措辞不准

> 相比之下最小冲突集要对模型做**松弛**再求解

⚠ 而 `DiagnosisService` 的注释明确说了**没有做松弛** ✗ —— 用的是"逐条去掉规则重解" ✓
**两处注释对同一个算法的描述不一致** ✗（一处说"松弛"，一处说"去掉"✓）
**松驰是方案原稿的写法**（`onlyEnforceIf` ✓）✓ 而实际实现**没改内核** ✓
→ **这处注释是从方案原稿抄下来忘了改** ✗ 记在案 ✓

### `run(organizationId)` —— 先一次读齐 7 张表

```java
public List<DiagnosisFinding> run(long organizationId) {
    List<DiagnosisFinding> findings = new ArrayList<>();

    Map<Long, GradeRef> grades = byId(gradeMapper.selectList(...), GradeRef::getId);
    Map<Long, ClassRef> classes = byId(classMapper.selectList(...), ClassRef::getId);
    Map<Long, SubjectRef> subjects = byId(subjectMapper.selectList(...), SubjectRef::getId);
    List<TeacherRef> teachers = teacherMapper.selectList(...);
    List<GradeSubjectRef> gradeSubjects = gradeSubjectMapper.selectList(...);
    List<AssignmentRef> assignments = assignmentMapper.selectList(...);
    List<GradeFixedSlotRef> fixedSlots = gradeFixedSlotMapper.selectList(...);

    checkMissingAssignments(findings, classes, subjects, gradeSubjects, assignments);
    checkClassCapacity(findings, classes, grades, gradeSubjects);
    checkTeacherOverload(findings, classes, teachers, gradeSubjects, assignments);
    checkFixedSlots(findings, grades, classes, subjects, fixedSlots);
    return findings;
}
```

**结构非常清楚** ✓：**7 次查询 → 4 个纯函数检查** ✓

🔴 **"4 个纯函数"是这里最好的设计** ✓：
四个 `check*` 方法**只接收数据、只往 `findings` 里加** ✓ **自己不查库** ✓
→ 它们**可以脱离数据库单独测试** ✓（传几个手工构造的 `ClassRef` / `AssignmentRef` 进去就行 ✓）
→ 而且"要查哪些表"这件事**只写在 `run` 里一处** ✓

> 💡 对照 4b-2 册的 `ScheduleInputLoader`：那个类**也是**"7 次查询 + 组装成纯数据" ✓
> **两个类结构一模一样** ✗ —— 因为它们在做**同一件事**：「7 张表 → 一份纯数据」 ✓
> 差别只是：`ScheduleInputLoader` 装配成 solver-core 的 `ScheduleInput` ✓
> 而这里是**直接跑检查**（没有中间的领域类型 ✓）
> ⚠ **其实这里也可以先装配成 `ScheduleInput` 再检查** ✗ —— 那样能**复用** ✓ 也能保证"诊断看的数据和求解看的数据完全一样" ✓✓
> **现在两者各查一遍** ✗ 如果中间有人改了数据，两边看到的可能不同 ✓（虽然同一个请求内几乎不可能 ✓）
> **这是一个可以改进的地方** ✓ 记在案 ✓

**顺序：`classes` / `subjects` 建成了 Map，而 `teachers` / `gradeSubjects` / `assignments` / `fixedSlots` 保持 List** ✓
—— 因为前者要**按 id 查名字** ✓ 后者要**遍历** ✓ **按用途选容器** ✓ 好 ✓

### ① `checkMissingAssignments` —— 有课时但没老师

```java
Map<String, Boolean> assigned = new HashMap<>();
for (AssignmentRef a : assignments) {
    assigned.put(a.getClassId() + ":" + a.getSubjectId(), Boolean.TRUE);
}
Map<Long, List<GradeSubjectRef>> byGrade = new HashMap<>();
for (GradeSubjectRef gs : gradeSubjects) {
    byGrade.computeIfAbsent(gs.getGradeId(), k -> new ArrayList<>()).add(gs);
}
for (ClassRef clazz : classes.values()) {
    for (GradeSubjectRef gs : byGrade.getOrDefault(clazz.getGradeId(), List.of())) {
        if (gs.getWeeklyHours() == null || gs.getWeeklyHours() <= 0) { continue; }
        if (assigned.containsKey(clazz.getId() + ":" + gs.getSubjectId())) { continue; }
        ... findings.add(new DiagnosisFinding("MISSING_ASSIGNMENT", ERROR, ...)) ...
    }
}
```

**算法**：把 `(班,科)` 的任教关系做成集合 ✓ 再遍历"每个班 × 它年级的每门课" ✓ 有课时但不在集合里 → 报 ✓

**三个细节**：

| 细节 | 说明 |
|---|---|
| **`weeklyHours <= 0` 跳过** ✓ | 周课时为 0 或负的科目**本来就不该排** ✓ 没配老师是正常的 ✓ **不报** ✓ —— 这个判断不能省 ✗ 否则会大量误报 ✓ |
| **`byGrade` 先按年级分组** ✓ | 避免"每个班都遍历全部 `gradeSubjects`" ✗ 把 O(班 × 全部科目) 降到 O(班 × 自己年级的科目) ✓ |
| ⚠ **字符串 key `"classId:subjectId"`** ✗ | 第 4 次出现这个模式了 ✗（4c-1 / 4c-2 / 4d / 本册 ✓）<br>而且这里只是**当集合用**（值是 `Boolean.TRUE` ✗ 从不读 ✓）→ **用 `Set<String>` 更直接** ✓ |

**文案**：
```
一年级一班的「语文」每周要上 8 节，但没有分配任何教师 —— 这节课永远排不出来
```
🔴 **注意"这节课永远排不出来"** ✓ —— 它说的不是"可能有问题" ✓ 而是**确定的结论** ✓
这与 `severity=ERROR` 是配套的 ✓：**报 ERROR 就必须真的确定** ✓ 否则用户会白改数据 ✗

### ② `checkClassCapacity` —— 座位够不够

```java
Map<Long, Integer> requiredByGrade = new HashMap<>();
for (GradeSubjectRef gs : gradeSubjects) {
    int hours = gs.getWeeklyHours() == null ? 0 : gs.getWeeklyHours();
    requiredByGrade.merge(gs.getGradeId(), hours, Integer::sum);
}
for (ClassRef clazz : classes.values()) {
    GradeRef grade = grades.get(clazz.getGradeId());
    if (grade == null) { continue; }
    int capacity = 0;
    for (String day : DAY_KEYS) {
        Integer periods = grade.getPeriodsPerDay() == null ? null : grade.getPeriodsPerDay().get(day);
        capacity += periods == null ? 0 : periods;
    }
    int required = requiredByGrade.getOrDefault(clazz.getGradeId(), 0);
    if (required > capacity) { ... }
}
```

**算法**：`capacity = Σ 各天节数` ✓ `required = Σ 该年级各科周课时` ✓ `required > capacity` → 报 ✓

🔴 **类注释里有一句容易被质疑的话** ✓：
> 注意这里**不扣固定课** —— 固定课占的也是座位，所以直接比总量就是正确的上界判断。

⚠ **"直接比总量"为什么是对的** ✗：
因为**固定课也占座位** ✓ 所以"总座位数"这个上界**不需要减去固定课** ✓
减了反而**错** ✗ —— 那会让"总课时 = 总座位 + 固定课数"这种**必然无解**的情况漏报 ✗
**这是一个很典型的"上界判断要放松而不是收紧"的例子** ✓

⚠ 但它是**充分不必要**的 ✗：`required ≤ capacity` **不代表能排出来** ✓
（还有教师上限 / 固定课冲突 / 规则约束 ✓）
**所以这个检查"报了必有错 ✓ 不报不代表没事"** ✓ —— 与 `ERROR` 的确定性要求一致吗 ✗？
hmm ✓ 报了就是"一定排不出来"（座位不够 ✓）✓ 所以报 ERROR 是对的 ✓
不报时不产生 finding ✓ 也就没有"确定性"的问题 ✓ ✓ 自洽 ✓

**`requiredByGrade.merge(gradeId, hours, Integer::sum)`** ✓ —— 一句话完成"累加" ✓
⚠ 比 `computeIfAbsent + put` 更紧凑 ✓ 但 `merge` 的语义要熟 ✓

**文案**：
```
一年级一班一周要排 30 节课，但只有 25 个时段，差了 5 节 —— 需要增加每天的节数或减少周课时
```
🔴 **给了两个具体出路** ✓（加节数 / 减课时 ✓）—— **比"请检查周课时"有用得多** ✓

### ③ `checkTeacherOverload` —— 教师超载（有一个很细的正确性处理）

```java
Map<String, Integer> weeklyHours = new HashMap<>();
for (GradeSubjectRef gs : gradeSubjects) {
    weeklyHours.put(gs.getGradeId() + ":" + gs.getSubjectId(),
            gs.getWeeklyHours() == null ? 0 : gs.getWeeklyHours());
}
// (班,科) → 教师数，用来判断课时是否独担
Map<String, Integer> teacherCount = new HashMap<>();
for (AssignmentRef a : assignments) {
    teacherCount.merge(a.getClassId() + ":" + a.getSubjectId(), 1, Integer::sum);
}

for (TeacherRef teacher : teachers) {
    int assigned = 0;
    for (AssignmentRef a : assignments) {
        if (!Objects.equals(a.getTeacherId(), teacher.getId())) { continue; }
        if (teacherCount.getOrDefault(a.getClassId() + ":" + a.getSubjectId(), 0) != 1) {
            continue;   // 多师分担，不能按全量计
        }
        ClassRef clazz = classes.get(a.getClassId());
        if (clazz == null) { continue; }
        assigned += weeklyHours.getOrDefault(clazz.getGradeId() + ":" + a.getSubjectId(), 0);
    }
    int limit = teacher.getMaxWeeklyHours() == null ? 0 : teacher.getMaxWeeklyHours();
    if (limit > 0 && assigned > limit) { ... }
}
```

🔴 **中间那个 `teacherCount != 1` 的判断是本册最细的一处** ✓ —— 类注释解释了 ✓：

> 注意同一个 `(班,科)` 若有多位教师，课时是**共同分担**而不是各自全担 ——
> 所以这里**只在"该 (班,科) 只有这一位教师"时才计入**，避免把正常情况误报成超载。

**举例**：一年级一班的语文每周 8 节 ✓ 配了张老师**和李老师两个人** ✓
- **不判断的话** ✗：张老师 +8 ✓ 李老师 +8 ✓ 两人都"分摊"了 8 节 ✓ **各自都被记了 8 节** ✗
  → 如果他们都还带了别的班 ✓ 就会**双双被误报超载** ✗
- **判断之后** ✓：这条 `(班,科)` 有 2 位老师 ✓ → **两人都不计这 8 节** ✓
  → 只统计"只有他一个人教"的那些 ✓ **不误报** ✓

⚠ **但这也意味着"两人分担时可能漏报真超载"** ✗：
如果张老师 + 李老师**合计**超了 ✓ 但各自单独看都不超 ✗ → 漏报 ✓
（比如上限 18 ✓ 两人各被算 0 ✓ 而实际各分担 4 节 ✓ 合计 8 ✓ 没超 ✓ —— 构造一个漏报的例子需要更多班 ✓ 但方向是存在的 ✓）

> 💡 **这是一次"宁可漏报，不可误报"的选择** ✓ —— 而它**与整个模块的哲学一致** ✓：
> **报 ERROR 就必须确定** ✓ 所以在不确定时**选择不报** ✓
> 对照 `FIXED_SLOT_SHARED_SLOT` 那条 ✓ —— 那也是"不确定就降级成 warning" ✓
> **同一个原则在两处体现** ✓ 这是好设计的一致性 ✓

**文案**：
```
张老师这学期被分配了 24 节课，上限是 18 节，超了 6 节 —— 可以减少一个班，或把上限调到 24 节
```
🔴 **"或把上限调到 24 节"是一个很聪明的建议** ✓ —— 它指出**两种都可能正确的解法** ✓：
- 用户的意图可能是"他确实该少带一个班" ✓
- 也可能只是"上限配低了" ✓
**诊断不该替用户决定** ✓ 只给出两种可能性 ✓ —— **这和铁律 4「助手只提议不执行」是同一个精神** ✓

### ④ `checkFixedSlots` —— 三个子检查（其中第三个是 warning）

#### 子检查 ① 同年级同一节固定了两门不同的课

```java
Map<String, List<GradeFixedSlotRef>> byGradeSlot = new TreeMap<>();
for (GradeFixedSlotRef slot : fixedSlots) {
    byGradeSlot.computeIfAbsent(slot.getGradeId() + ":" + slot.getDayOfWeek()
            + ":" + slot.getPeriodIndex(), k -> new ArrayList<>()).add(slot);
}
for (Map.Entry<String, List<GradeFixedSlotRef>> entry : byGradeSlot.entrySet()) {
    List<GradeFixedSlotRef> group = entry.getValue();
    long distinctSubjects = group.stream().map(GradeFixedSlotRef::getSubjectId).distinct().count();
    if (distinctSubjects > 1) { ... ERROR "FIXED_SLOT_CONFLICT" ... }
}
```

**用 `distinctSubjects > 1` 而不是 `group.size() > 1`** ✓ —— 很关键 ✓：
同一个 `(年级,科)` 固定到同一节两次（重复数据 ✗）**不是冲突** ✓ 只是冗余 ✓
**只有"不同的科目"才是冲突** ✓ —— 一个班同一节只能上一门课 ✓

**注意 `TreeMap`** ✓ —— 让输出的 finding 顺序**按 key 排序** ✓ 可复现 ✓
⚠ 但 key 是**字符串**（`"gradeId:day:period"` ✗）→ **字符串排序不是数值排序** ✗
`"10:0:0"` 会排在 `"2:0:0"` **前面** ✗
—— 影响的是**同一个响应里多条 finding 的顺序** ✓ 而这个顺序**可能被契约测试比对** ✗
⚠ **现在只有一个年级时无所谓** ✓ 但**10 个年级以上**就会出现"顺序看起来乱" ✓
**记为"字符串 key 的第 5 次出现，这次还带来了排序语义问题"** ✓

#### 子检查 ② 固定到当天不存在的节次

```java
if (grade != null && grade.getPeriodsPerDay() != null
        && slot.getDayOfWeek() != null && slot.getDayOfWeek() >= 0
        && slot.getDayOfWeek() < DAY_KEYS.size() && slot.getPeriodIndex() != null) {
    Integer periods = grade.getPeriodsPerDay().get(DAY_KEYS.get(slot.getDayOfWeek()));
    if (periods != null && slot.getPeriodIndex() >= periods) { ... ERROR "FIXED_SLOT_OUT_OF_RANGE" ... }
}
```

⚠ **六个条件的连乘** ✗ —— 每个都是在防一种 null 或越界 ✓ 但**读起来很累** ✓
**更清楚**：把"这个 slot 的坐标是否合法"抽成一个方法 ✓ 或者用早退 ✓：

```java
if (grade == null || grade.getPeriodsPerDay() == null) continue;
if (slot.getDayOfWeek() == null || slot.getPeriodIndex() == null) continue;
if (slot.getDayOfWeek() < 0 || slot.getDayOfWeek() >= DAY_KEYS.size()) continue;
Integer periods = ...;
if (periods == null || slot.getPeriodIndex() < periods) continue;
... 报错 ...
```
**逻辑完全一样** ✓ 但**每一行只问一件事** ✓ —— 这是"卫语句（guard clause）"相对"复合条件"的优势 ✓

⚠ 注意它**漏掉了一种情况** ✗：`slot.getDayOfWeek() < 0`（比如 -1 ✗）时 ✓
六个条件里 `>= 0` 不满足 ✗ → **整个 if 跳过** ✓ → **不报错** ✓
**但"固定课落在第 -1 天"显然也是一个应该报的越界** ✗
—— 它被静默忽略了 ✓ 记在案 ✓（现实中不会有人填 -1 ✓ 但**换课中间态的负值**是 -1 ✓
hmm ✗ —— 那是 `schedule_version_entries` 的中间态 ✓ 不是 `grade_fixed_slots` ✓ 所以不会混 ✓ OK ✓）

#### 🔴 子检查 ③ 同一节被多个年级固定 —— **刻意只报 warning**

```java
// ③ 同一节被多个年级固定。
//    ⚠ 只报 warning，不报 error：不同年级完全可以有不同的老师，所以这只说明
//      "**有可能**撞课"。要精确判定必须把 (年级,科目) 展开到具体教师 ——
//      那属于最小冲突集分析的范畴。**宁可给一个措辞准确的警告，
//      也不要给一个看起来很确定、但其实是猜的结论。**
Map<String, Set<Long>> gradesBySlot = new TreeMap<>();
...
findings.add(new DiagnosisFinding("FIXED_SLOT_SHARED_SLOT", DiagnosisFinding.WARNING,
        dayName(day) + "第 " + (period + 1) + " 节被 " + entry.getValue().size()
                + " 个年级同时固定 —— 若这些课是同一位老师，就会撞课",
        payload));
```

🔴🔴 **这段注释是全册最好的一段** ✓ ——

> **宁可给一个措辞准确的警告，也不要给一个看起来很确定、但其实是猜的结论。**

**为什么不能报 error** ✓：一年级和二年级都固定了"周一第 1 节 语文" ✓
如果一〈年〉级的语文是张老师 ✓ 二年级是李老师 ✓ → **完全不冲突** ✓
只有**同一位老师**时才是真冲突 ✗ —— 而"同一位老师"要**展开到任教关系**才知道 ✓

**文案里也体现了这个诚实** ✓：「**若**这些课是同一位老师，就会撞课」✓
—— 一个"若"字把不确定性写进了给用户看的话里 ✓

**`payload` 里给的是 `grade_ids`** ✓（而不是断言某个冲突 ✓）—— **把判断权留给下一个人** ✓

> 💡 **与子检查 ①② 对比** ✓：
> 那两条报 ERROR ✓ 因为"同一节固定两门课"**无论老师是谁都矛盾** ✓ 是**逻辑矛盾** ✓
> 这条报 WARNING ✓ 因为"两个年级同节固定"**只是可能**矛盾 ✓ 是**经验规律** ✓
> **同一个方法里两种严重度，判断标准是"矛盾是否与数据无关"** ✓
> —— 这个标准很清晰，但**没有写出来** ✗ 只体现在两个常量的选择上 ✓
> **值得补一句注释**：`ERROR = 与具体数据无关的逻辑矛盾` ✓ / `WARNING = 依赖数据才成立的怀疑` ✓

### 小工具

| 方法 | 说明 |
|---|---|
| `dayName(int day)` | `"周一".."周五"` ✓ 越界返回 `"第N天"` ✓ —— **越界也给个可读的说法而不是抛异常** ✓ 好 ✓ |
| `nameOfGrade(grades, id)` | 找不到返回 `"年级" + id` ✓ —— **不返回 null** ✓（否则文案里会出现 `null` ✗） |
| `nameOf(subjects, id)` | 同上，`"科目" + id` ✓ |
| `byId(rows, idOf)` | 通用"列表 → Map" ✓ 与 4d 册的 `nameMap` / `names` **是同族** ✓（本册第 3 个变体 ✗） |

⚠ **`dayName` 里 `"一二三四五".charAt(day)`** ✓ —— 用字符串下标取中文数字 ✓
**很紧凑** ✓ 但 `day >= 0 && day < 5` 的判断**不可省** ✗（越界 `charAt` 会抛异常 ✓）✓ 有 ✓

---

## 5d.3 `DiagnosisResponse` —— 三态是核心（45 行）

```java
public record DiagnosisResponse(
        String kind,            // OK | INFEASIBLE | UNKNOWN
        String severity,
        Long versionId,
        String jobToken,
        String ruleSetHash,
        List<DiagnosisFinding> staticFindings,
        List<ConflictRule> conflictRules,
        boolean deep,
        String note,
        LocalDateTime createdAt) {

    public record ConflictRule(Long ruleId, String ruleType, String description) { }

    public static final String KIND_OK = "OK";
    public static final String KIND_INFEASIBLE = "INFEASIBLE";
    public static final String KIND_UNKNOWN = "UNKNOWN";
}
```

### 类注释 —— 把"为什么必须分三态"讲透了

> 排课接口对外只有一句 `排课无解，请检查任教关系、周课时、固定时段和教师课时上限`
> （那是 Python 的行为，P1 逐字保留了）。但求解器其实能区分两件**完全不同**的事：
> - `INFEASIBLE` —— CP-SAT **证明了**无解。此时"**改数据**"是唯一出路
> - `UNKNOWN` —— 到时限还没证明。可能解存在只是没找到，**延长时间/换参数就行，不该去改数据**
>
> 把两者混成一句话，**会让用户去改本来没问题的数据**。所以这里必须分开报。

🔴 **"会让用户去改本来没问题的数据"** ✓ —— 这一句是**这个模块存在的全部理由** ✓
而且它把问题说得**用户视角**（用户会做什么 ✓）而不是"API 语义更清晰" ✗
—— **这是好的技术写作**：说明后果，而不是说明规范 ✓

### 两个列表：`staticFindings` 与 `conflictRules`

| 字段 | 是什么 | 什么时候有 |
|---|---|---|
| `staticFindings` | 静态检查的发现（`DiagnosisFinding` ✓） | **每次都有**（毫秒级 ✓） |
| `conflictRules` | **最小冲突集**（去掉它就能排出来的硬规则 ✓） | **只有深诊断才有** ✓ |

**两者是"不同层次"的结论** ✓：
- `staticFindings` 是**确定的**（ERROR 级 ✓）
- `conflictRules` 是**充分的**（"去掉它能排出来"✓ 不一定是唯一原因 ✓）

⚠ 所以它们的严重度**不该等同** ✗ —— 而 `highestSeverity` 把 `conflictRules` 非空也算成 ERROR ✓
（hmm ✗ —— 它算的是 `KIND_INFEASIBLE` → ERROR ✓ 见 §5d.4 ✓）
**合理** ✓：去掉某条硬规则就能排出来 ✓ 说明那条规则**确实**是障碍 ✓ 用户确实该改它 ✓

### `deep` 字段

```java
/** 本次是否真的做了深诊断。 */
boolean deep,
```

⚠ 这个字段**看起来多余** ✗（调用方自己知道传没传 `deep=true` 啊 ✗）
**但注释在 `DiagnosisService.persist` 里解释了它的必要性** ✓：

> ⚠ deep 必须落库：回读时要如实告诉调用方"这条记录是浅诊断还是深诊断"。
> 最初没存它，`latest()` 只好硬编码 `true` —— 于是排课失败留下的那条**浅**诊断
> **会谎称自己是深诊断**（实测踩到过）。

🔴 **这是一个"字段存在的理由"写得非常好的例子** ✓ —— 它回答的是
**"为什么不能靠调用方自己知道"** ✓ 因为**回读路径**（`latest` ✓）的调用方**不知道** ✓
**它只拿到一条数据库记录** ✗ 所以记录里必须自带这个信息 ✓

---

## 5d.4 `DiagnosisService` —— 两段式（349 行）

### 类注释：为什么"逐条去掉"而不是方案原稿的 `onlyEnforceIf`

> 方案 §6.4 写的是「硬约束改为 `onlyEnforceIf(flag)` 可开关约束，最小化违反加权和」。
> 那需要改动 solver-core 里**每一条 handler 的建模方式**（都要接受一个 flag），
> 而这里用了一个**不改内核**的等价做法：
> ```
> 对每条硬规则 R：把 R 从规则集里去掉，重新求解一次
>   → 能解出 → R 属于最小冲突集
>   → 仍无解 → R 不是（唯一/主要）原因
> ```
> 代价是要解 K 次（K = 硬规则条数），但：
> - K 通常很小（真实场景下用户配的硬规则一般 ≤ 5 条），而且**上限被 `MAX_TRIES` 卡住**
> - 每次求解**只用 `deep-time-limit` 秒**（默认 8 秒），不是排课时的 60 秒 —— 诊断只需要知道"有没有解"，不需要找到最优解
> - **不变量去不掉**（`teacher_no_conflict` / 周课时 / 教师上限始终生效），所以如果冲突来自不变量，这一轮会一无所获 —— **那时如实报告，而不是编一个原因**

🔴 **这是全项目"用工程手段绕开内核改动"的最完整的一次论证** ✓
三条理由都很实在 ✓ 尤其第三条 —— 它**主动说出了这个方案的局限** ✓

**"只管有没有解，不需要最优解"为什么能省时间** ✗：
CP-SAT 只要找到一个可行解就返回 `FEASIBLE` ✓ 而**证明最优**可能要长得多 ✓
所以 8 秒**足够判定"有没有解"** ✓ 而 60 秒才够"排得好" ✓
🔴 **这是一个可以推广的技巧** ✓：**判存在性比求最优便宜得多** ✓
（同一思想在 4d 册的性能讨论里也出现过 ✓：成本取决于你要什么 ✓）

### `MAX_TRIES = 5`

```java
/** 深诊断最多尝试几条硬规则 —— 卡住总耗时上界（× 每次的 deep-time-limit 秒）。 */
private static final int MAX_TRIES = 5;
```

**总耗时上界 = 5 × 8 秒 = 40 秒** ✓ —— **有界的** ✓ 好事 ✓

⚠ 但**截断是静默的** ✗：如果用户配了 8 条硬规则 ✓ **只试前 5 条** ✓
而 `note` 里**没有说"只试了 5 条"** ✗ → 用户会以为"这 5 条之外都没问题" ✗
**建议**：在 `note` 或 `payload` 里带上"尝试了 N 条，共 M 条" ✓ 记在案 ✓

### `diagnose` 与 `diagnoseWithStatus` —— 🔴 一个 Java 重载的坑

```java
public DiagnosisResponse diagnose(long organizationId, String jobToken, Long versionId,
                                  boolean deep, SolveOutcome lastOutcome) {
    return diagnoseWithStatus(organizationId, jobToken, versionId, deep,
            lastOutcome == null ? null : lastOutcome.status().name());
}
```

**注释解释了为什么不做成重载** ✓：

> ⚠ 刻意**不做成 `diagnose` 的重载**：那个方法的第四个参数可以是 null，
> 而 `diagnose(org, token, null, false, null)` 在有两个重载时**无法确定调用哪个**
> （编译期就报"对 diagnose 的引用不明确"，**实测踩到过**）。
> **参数里允许 null 的时候，名字比重载清楚。**

🔴 **这是一条关于 Java 重载的实用规则** ✓ —— 值得记住 ✓：

| 场景 | 用重载 |
|---|---|
| 参数**类型不同**且**没有 null 歧义** ✓ | ✓ 可以 |
| 参数个数不同 ✓ | ✓ 可以 |
| **两个重载只有某个参数类型不同，而调用方会传 `null`** ✗ | ✗ **绝对不行** ✗ 编译期就报"引用不明确" |
| **参数含义不同**（一个是"结果对象"✓ 一个是"状态字符串"✓） | ✗ **用不同的名字** ✓ |

⚠ 而**根本原因**是：`null` **没有类型** ✗ 编译器无法在 `SolveOutcome` 和 `String` 之间选择 ✓
**只要调用时出现一个字面 `null`，重载就崩** ✗ —— 而这在业务代码里很常见 ✓（"没有上一次结果" ✓）

**顺带**：`lastOutcome.status().name()` ✓ —— `enum.name()` 返回枚举常量名 ✓（`OPTIMAL` ✓）
与 `determineKind` 里 `switch (solverStatus)` 的字符串匹配**对应** ✓（见下 ✓）

### 🔴 `determineKind` —— 一个"不乐观"的默认分支

```java
private static String determineKind(List<DiagnosisFinding> findings, String solverStatus) {
    boolean certain = findings.stream().anyMatch(f -> ERROR.equals(f.severity()));
    if (certain) {
        return KIND_INFEASIBLE;          // 静态检查已确定 → 直接下结论
    }
    if (solverStatus == null) {
        return KIND_OK;                  // 没有求解信息 → 只看静态
    }
    return switch (solverStatus) {
        case "INFEASIBLE" -> KIND_INFEASIBLE;
        case "UNKNOWN" -> KIND_UNKNOWN;
        // OPTIMAL / FEASIBLE / MODEL_INVALID 之外的都当"不知道"，
        // 而不是乐观地当成功
        case "OPTIMAL", "FEASIBLE" -> KIND_OK;
        default -> KIND_UNKNOWN;         // 👈
    };
}
```

🔴 **那个 `default -> KIND_UNKNOWN` 值得单独说** ✓ —— 注释也点了 ✓：
> OPTIMAL / FEASIBLE / MODEL_INVALID 之外的都当"不知道"，**而不是乐观地当成功**

**为什么重要** ✗：CP-SAT 的状态枚举**将来可能增加** ✗（比如 `MODEL_INVALID` ✓ 就是后加的 ✓）
如果 `default` 写 `KIND_OK` ✗ → 遇到一个新状态会被当成"排课成功" ✗✓ **静默的错误结论** ✓
写 `KIND_UNKNOWN` ✓ → 至少**不撒谎** ✓（说"不知道"✓ 而不是"没事"✗）

> 💡 **这是一个通用的原则**：**`switch` 的 `default` 该往"保守/未知"那边倒，而不是"正常/成功"** ✓
> 因为它接住的是**你没预料到的情况** ✗ 而你没预料到的情况**更可能有问题** ✓
>
> ⚠ **但注意 `MODEL_INVALID` 会落到 `default` → `KIND_UNKNOWN`** ✓
> 而它其实是"**模型非法**"（代码 bug ✗）✓ 说"不知道"不算错 ✓ 但不够准确 ✓
> 记在案 ✓（`2a` 册也提过：`MODEL_INVALID` 会被归到"可重试"那一类 ✓ —— **同一类小瑕疵在两个地方** ✓）

### ⚠ `severity` 必须在 `kind` 定稿之后才算 —— 一处真实修过的 bug

```java
List<DiagnosisFinding> staticFindings = staticDiagnostics.run(organizationId);
String kind = determineKind(staticFindings, solverStatus);
String ruleSetHash = ruleSetService.hashOf(organizationId);

List<DiagnosisResponse.ConflictRule> conflictRules = List.of();
if (deep) {
    conflictRules = findConflictRules(organizationId, solverStatus);
    if (!conflictRules.isEmpty()) {
        kind = DiagnosisResponse.KIND_INFEASIBLE;      // 👈 深诊断之后 kind 可能变
    }
}

// ⚠ severity 必须在 kind **定稿之后**才算。
//   最初把它写在深诊断之前，于是"深诊断才发现不可行"的那次调用会返回
//   kind=INFEASIBLE 却 severity=info（两个字段自相矛盾，实测踩到过）。
String severity = highestSeverity(staticFindings, kind);
```

🔴 **这是一处"看起来只是顺序、其实是数据一致性"的 bug** ✓ ——

| 顺序 | 结果 |
|---|---|
| severity 先算 ✗ | `kind` 后来被改成 `INFEASIBLE` ✓ 而 `severity` 还是按旧的 `OK` 算的 → **`info`** ✗ → **响应自相矛盾** ✓ |
| **severity 后算** ✓ | 两个字段**必然一致** ✓ |

**这类 bug 的特征**（已经第三次见到了 ✗）：
- **两个字段由同一份输入派生** ✓
- 但**其中一个在派生之后又可能被改** ✗
- → **另一个就过期了** ✗
- **不会报错** ✓ 只在特定的分支组合下出现 ✓

（对照：5b 册的 `JobResponse` 里那个 `severity` 先于 `kind` 的坑 ✓ 是同一次教训 ✓；
以及 4c-2 册的 `publish` 里"归档必须在标记本版之前"✓ —— 同一族 ✓）

> 💡 **可推广的检查法**：**当一个方法里有"之后还会改的变量"时，所有依赖它的派生值都必须排在最后** ✓
> 更稳的做法：**把它们提到一个不可变的记录里一次性构造** ✓（本类的 `DiagnosisResponse` 正是这么做的 ✓）
> —— 顺序错了就编译不过 ✓ 而不是顺序错了还能跑 ✓

### 🔴 `note()` —— 全项目最值得单独读的方法

```java
/**
 * 结论的可信度说明。
 *
 * <p>⚠ 这一段是刻意写的：诊断**最危险的失败方式**不是没结论，
 * 而是给一个听起来确定、其实是猜的结论（"应该是教师超载吧"）。
 * 所以这里必须把"没找到问题"与"排不出来"这两件事的关系说清楚。
 */
private static String note(List<DiagnosisFinding> findings,
                           List<DiagnosisResponse.ConflictRule> conflictRules,
                           String kind, boolean deep) {
    if (KIND_OK.equals(kind)) {
        return findings.isEmpty()
                ? "静态检查未发现问题，最近一次求解也是成功的。"
                : "静态检查有提示项，但都不影响求解。";
    }
    if (!findings.isEmpty()) {
        return "静态检查已定位到确定的问题（见 static_findings）—— 这些必须修，否则一定排不出来。";
    }
    if (!conflictRules.isEmpty()) {
        return "静态检查未发现问题；深诊断显示：去掉 conflict_rules 里的规则后就能排出课表，冲突出在这些硬规则上。";
    }
    if (!deep) {
        return "静态检查未发现问题。加上 ?deep=true 可以做最小冲突集分析，判断冲突是否来自某条硬规则。";
    }
    return KIND_UNKNOWN.equals(kind)
            ? "静态检查未发现问题，而求解是**超时未证明**（不是证明无解）——建议延长时间限制或减少约束后重试，**不必急着改数据**。"
            : "静态检查与逐条放宽都没能找到原因：冲突来自**不变量**（教师不撞课 / 周课时必须排满 / 教师周上限）的组合，"
              + "或来自多条硬规则的共同作用。这类情况需要最小冲突集算法（P3 计划）。";
}
```

🔴 **为什么说它是全项目最值得读的方法** ✓ —— 它是一台**"结论可信度"的状态机** ✓：

```
                    ┌─────────────────────────────────────────┐
   kind=OK ────────►│ "未发现问题，最近一次求解也是成功的"      │
                    └─────────────────────────────────────────┘
   kind≠OK ────────► 有静态发现？ ──是──► "已定位到确定的问题，这些必须修"
                          │
                          否
                          ▼
                    有冲突集？ ──是──► "去掉 conflict_rules 就能排出来"
                          │
                          否
                          ▼
                    做过深诊断？ ──否──► "加上 ?deep=true 可以进一步分析"
                          │
                          是
                          ▼
                    kind=UNKNOWN？ ──是──► "超时未证明，**不必急着改数据**"
                          │
                          否
                          ▼
                    "冲突来自不变量或多条规则的共同作用 —— 需要最小冲突集算法（P3 计划）"
```

**七个分支，每一个都承认自己不知道什么** ✓ —— 这是它最大的价值 ✓：

| 分支 | 它诚实地说了什么 |
|---|---|
| "最近一次求解也是成功的" | 条件说清了（不只是"没问题" ✓） |
| "有提示项，但都不影响求解" | ⚠ 区分了 warning 与"没事" ✓ |
| "这些**必须修**，否则一定排不出来" | **确定**的结论才用"必须" ✓ |
| "去掉……就能排出来，冲突出在这些硬规则上" | **充分**结论 ✓ 措辞准确（没说"就是它"✓） |
| "加上 `?deep=true` 可以……" | **告诉用户下一步怎么做** ✓ 而不是停在"不知道" ✗ |
| **"超时未证明（不是证明无解）—— **不必急着改数据**"** | 🔴 **最重要的一条** ✓ 它直接阻止了用户做无用功 ✓ |
| "冲突来自不变量……或来自多条硬规则的共同作用" | **说出这个方法的局限** ✓ 而不是给个猜测 ✗ |

🔴 **最后一条里有一个过时的说法** ✗：
> 这类情况需要最小冲突集算法（**P3 计划**）

⚠ **最小冲突集算法已经实现了** ✗ —— 就是**本类的 `findConflictRules`** ✓
而这一条分支**恰恰是"深诊断跑完但没找到冲突"时才会走到** ✓
→ 也就是说：**用户已经用过了那个算法** ✓ 而 `note` 却告诉他"这个算法还没做" ✗✓

**它还给了用户一个错误的期待**：以为将来会有这个功能 ✗ 而其实已经用过了 ✓
**这处文案应该改成**：「冲突来自不变量或多条规则的共同作用 —— 这类情况**当前的最小冲突集算法覆盖不到**」✓

⚠ 顺便：这个分支里 `deep == true` ✓ 而且 `KIND_UNKNOWN` 为假 ✓
→ 也就是**求解状态是 `INFEASIBLE`（证明无解）但静态检查和逐条放宽都没找到原因** ✓
**这是最需要诚实的一种情况** ✓ 而它现在说"需要 P3 计划的算法" ✗ —— **把诚实打了折扣** ✓
记在案（本册最重要的一条发现 ✓）

### `latest()` —— 一个刻意的"混合"

```java
/**
 * <p>⚠ 一个刻意的混合：**冲突集用落库的**（那才是贵的那部分，也是它必须落库的原因），
 * **静态部分现场重算**（毫秒级，而且比落库那一刻的数据更"现在" ——
 * 用户可能刚改完某个班的周课时）。
 */
public DiagnosisResponse latest(long organizationId) {
    ScheduleDiagnosisEntity row = diagnosisMapper.selectOne(...orderByDesc(id).last("LIMIT 1"));
    if (row == null) { return null; }
    List<DiagnosisFinding> fresh = staticDiagnostics.run(organizationId);
    return new DiagnosisResponse(
            row.getKind(),
            highestSeverity(fresh, row.getKind()),
            row.getVersionId(),
            row.getJobToken(),
            stringOf(row.getPayload(), "rule_set_hash"),
            fresh,
            parseConflictRules(row.getPayload()),
            booleanOf(row.getPayload(), "deep"),
            stringOf(row.getPayload(), "note"),
            row.getCreatedAt());
}
```

🔴 **"贵的用旧的、便宜的用新的"** ✓ —— 这个混合很有想法 ✓
**理由**：静态检查毫秒级 ✓ 重算没成本 ✓ 而且**更符合用户当前的期望** ✓（他刚改了周课时 ✓ 想看新结果 ✓）
**深诊断几十秒** ✓ 不能重算 ✓ 所以用落库的 ✓

⚠ **但它带来一个含糊** ✗：同一个响应里 ✓
`staticFindings` 是**现在的** ✓ 而 `conflictRules` 是**那时候的** ✓
`createdAt` 用的是**落库时间** ✓ —— 所以"这个响应是什么时候的"**没有单一答案** ✗
**建议**：在 `note` 里说明"冲突集来自 MM-dd HH:mm 的深诊断" ✓ 记在案 ✓

⚠ **`highestSeverity(fresh, row.getKind())`** ✓ —— 用**新的** findings 算 severity ✓ 但 kind 用**旧的** ✓
**可能自相矛盾** ✗：旧 kind = `INFEASIBLE` ✓ 新 findings 为空 ✓ → severity = `ERROR`（因为 kind ✓）✓ 一致 ✓
反过来：旧 kind = `OK` ✓ 而用户刚改坏了数据 ✓ → 新 findings 有 ERROR ✓ → severity = `ERROR` ✓ 但 kind 还是 `OK` ✗
→ **`kind=OK` 配 `severity=error`** ✗ 矛盾 ✓
⚠ **这正是 `severity`/`kind` 那一对在另一条路径上的同一个问题** ✗（虽然方向不同 ✓）
**记为"`latest()` 里 kind 与 severity 用的是不同时刻的输入"** ✓

### `findConflictRules` —— 最小冲突集

```java
private List<DiagnosisResponse.ConflictRule> findConflictRules(long organizationId, String solverStatus) {
    // 只有"确实排不出来"才值得做这一步
    if ("OPTIMAL".equals(solverStatus) || "FEASIBLE".equals(solverStatus)) {
        return List.of();
    }
    List<ConstraintRuleEntity> hardRules = ruleSetService.listAll(organizationId).stream()
            .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
            .filter(r -> "hard".equalsIgnoreCase(r.getHardness()))
            .toList();
    if (hardRules.isEmpty()) { return List.of(); }

    List<ConstraintRule> allRules = ruleSetService.loadForSolve(organizationId);
    ScheduleInput input = inputLoader.load(organizationId);

    List<DiagnosisResponse.ConflictRule> conflicts = new ArrayList<>();
    int tried = 0;
    for (ConstraintRuleEntity hard : hardRules) {
        if (tried >= MAX_TRIES) { break; }
        tried++;
        List<ConstraintRule> without = allRules.stream()
                .filter(r -> !(r.type().equals(hard.getRuleType())
                        && r.params().values().equals(hard.getParams())))
                .toList();
        if (without.size() == allRules.size()) { continue; }   // 没匹配上，跳过而不是误报
        try {
            SolveOutcome outcome = generationService.solve(input, without, deepTimeLimitSeconds, 1);
            if (outcome.hasSolution()) {
                conflicts.add(new DiagnosisResponse.ConflictRule(
                        hard.getId(), hard.getRuleType(), hard.getDescription()));
            }
        } catch (RuntimeException e) {
            log.warn("深诊断尝试失败 rule={}: {}", hard.getId(), e.getMessage());
        }
    }
    return conflicts;
}
```

**六步** ✓ 逐点：

| 步 | 说明 |
|---|---|
| **① 成功就早退** ✓ | "只有确实排不出来才值得做这一步" ✓ —— 但 ⚠ **`solverStatus == null` 时也会往下走** ✗（见下 ✓） |
| **② 取出可配置的硬规则** ✓ | `enabled == TRUE` ✓ + `hardness == "hard"`（忽略大小写 ✓）<br>⚠ **不变量不在 `constraint_rules` 表里** ✓ 所以自然取不到 ✓ —— **这就是"不变量去不掉"的机制** ✓ 不是靠过滤 ✓ |
| **③ 载入全部规则 + 输入** ✓ | 一次 ✓ 循环里复用 ✓（**不重复查库** ✓ 好 ✓） |
| **④ 逐条"去掉它"** ✓ | `filter(r -> !(type 相同 && params 相同))` ✓ |
| **⑤ 匹配不上就跳过** ✓ | 注释：**"跳过而不是误报"** ✓ —— 与整个模块的哲学一致 ✓ |
| **⑥ 单条失败只记日志** ✓ | 不让整个诊断崩掉 ✓ |

#### ⚠ "去掉"的匹配方式是一个启发式

```java
.filter(r -> !(r.type().equals(hard.getRuleType())
        && r.params().values().equals(hard.getParams())))
```

**它按"类型 + 参数"匹配，而不是按"数据库 id"** ✗ ——
因为 `ConstraintRule`（solver-core 的领域类型 ✓）**没有 id** ✗（它不需要 ✓）
而 `ConstraintRuleEntity`（数据库实体 ✓）有 id ✓

⚠ **后果**：如果**两条硬规则的类型和参数完全相同**（比如重复添加了同一条规则 ✗）✓
→ **两条会被一起去掉** ✗ → 如果去掉后能解出来 ✓ **两条都会被报成冲突** ✗（其中一条可能是冤枉的 ✓）
注释说了"理论上不会" ✓（因为 `RuleSetService` 有版本去重 ✓ hmm ✓ 那是 hash 级别的 ✓ 未必挡得住"同表两条同类型同参数"✓）
**记为"启发式的边界情况"** ✓

#### ⚠ `solverStatus == null` 时会真的跑 K 次求解

`DiagnosisController` 的 `deep=true` 分支**传的是 `null`** ✗（它拿不到上次的求解状态 ✓）
→ 早退条件不满足 ✓ → **会真的跑最多 5 次求解** ✓（这是对的 ✓ 因为它的语义就是"重算一遍"✓）
但 `determineKind(..., null)` → **如果没有静态发现就返回 `KIND_OK`** ✗
→ 于是**深诊断找到了冲突时会改成 `INFEASIBLE`** ✓（有 `if (!conflictRules.isEmpty())` ✓）
→ **没找到时返回 `OK`** ✗ —— 而"上一次排课其实失败了"这件事**在这个路径上丢失了** ✗

🔴 **这正是 `diagnoseWithStatus` 的注释描述的那个问题** ✗：
> ⚠ 不这么做的后果很具体：排课因为**规则太紧**而失败、静态检查又没发现问题时，
> 诊断会退回 `kind=OK` —— **一个明明失败了的任务，诊断却说一切正常。**

**P3 给任务路径修好了** ✓（`JobRunner` 传真实的 `solver_status` ✓ 见 5b 册 ✓）
⚠ 但 **`DiagnosisController` 的 `deep=true` 路径仍然传 `null`** ✗
—— 因为 HTTP 那一刻**确实不知道**"上一次求解是什么状态" ✓（注释也说了 ✓）
**所以现在有两种"诊断"** ✗：
- **任务失败后自动跑的**（带状态 ✓ 结论准确 ✓）
- **用户手动点"重新诊断"**（不带状态 ✗ 可能说"一切正常"✗）

⚠ **这是一个真实的体验缺口** ✓：用户可以点两次诊断得到不同结论 ✓
**修法**：`DiagnosisController` 应该能从 `schedule_jobs` 里查最近一次的 `solver_status` ✓
（P3 之后这张表有了 ✓ 而控制器的注释还写着"已经从状态表丢失了"✗ —— **那句话在 P3 之后不再成立** ✓）
**记在案（第二重要的一条发现）** ✓

### `diagnoseWithStatus` 的落库条件

```java
// ⚠ 落库条件：深诊断（贵）**或**确实有问题（需要留痕）。
//    "一切正常"的浅诊断不落库 —— 它毫秒级就能重算，落库只会把表撑大。
if (deep || !DiagnosisResponse.KIND_OK.equals(kind)) {
    persist(organizationId, jobToken, versionId, response);
}
```

🔴 **"贵的"或"有问题的"才落库** ✓ —— 两个条件**各自有理由** ✓：
- `deep` ✓ → 重算要几十秒 ✓ 必须存 ✓
- `!OK` ✓ → 需要留痕 ✓（"什么时候开始排不出来的"✓ 这是运维会问的问题 ✓）
- **`OK` 且浅** ✓ → 毫秒级 ✓ 不存 ✓ 否则表会被"每次刷新"撑大 ✓

> 💡 这条规则同时服务于**性能和审计** ✓ —— 而且它**排除了唯一一种可以安全丢弃的情况** ✓
> 判断得很准 ✓

### `persist` 的落库内容与两处注释

```java
private void persist(long organizationId, String jobToken, Long versionId,
                     DiagnosisResponse response) {        Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("findings", response.staticFindings());
    payload.put("conflict_rules", response.conflictRules());
    payload.put("rule_set_hash", response.ruleSetHash());
    payload.put("note", response.note());
    // ⚠ deep 必须落库：回读时要如实告诉调用方"这条记录是浅诊断还是深诊断"。
    payload.put("deep", response.deep());
    ...
}
```

⚠ **方法体的第一个语句和签名在同一行** ✗（`{        Map<...> payload = ...` ✓）
—— 一处**格式化事故** ✓（大概是某次编辑把换行删了 ✓）
**编译器不介意** ✓ 但读的人会愣一下 ✓ **记在案** ✓

⚠ **落库的是"整个 payload"而不是分列** ✓ —— `findings` / `conflict_rules` / `note` 都塞进 JSON ✓
只有 `kind` / `severity` / `job_token` / `version_id` 是**独立列** ✓

**为什么这么分** ✗：
- **独立列的** = **要用来查询和过滤的** ✓（`kind` ✓ 按组织查最新的 ✓）
- **JSON 里的** = **只用来回读展示的** ✓（`findings` 的细节 ✓）
🔴 **这是一条很实用的判断法** ✓：**"这个字段会被 WHERE / ORDER BY 用到吗"** ✓ 会 → 独立列 ✓

⚠ `payload.put("findings", response.staticFindings())` ✗ —— 直接塞**Java 对象** ✓
由 `JacksonTypeHandler` 序列化 ✓ —— 所以 `findings` 的 JSON 形状**取决于 `DiagnosisFinding` 的定义** ✓
如果哪天改了那个 record ✗ **历史数据的 JSON 形状不会跟着变** ✗ → 回读时会解析失败或字段缺失 ✓
⚠ 这是"用 JSON 列存结构"的固有权衡 ✓ 且**没有版本号** ✗ 记在案 ✓

---

## 5d.5 `DiagnosisController` / 实体 / Mapper

### `DiagnosisController` —— 一处**有意的路径偏离**（讲得非常好）

```java
/**
 * <h2>⚠ 路径与方案 §13.2 有一处**有意的差异**</h2>
 * 方案写的是 {@code GET /api/schedule/jobs/{id}/diagnosis}，并在同一行注明
 * 「原稿是无参 {@code GET /api/schedule/diagnosis}；诊断必然是「某一次排课」的，必须带 job/version」。
 *
 * <p>但 {@code schedule_jobs} 是 **P3 的表**，现在还不存在。诊断要"必须带 job"就得先有 job ——
 * 所以 P2 采用组织级路径（方案自己也说这是原稿形态），并在**落库时记下 {@code job_token}**
 * （那是当前唯一的"尝试身份"）。P3 引入 jobs 后：
 * <ul>
 *   <li>加一列 {@code job_id}（表已预留语义），接口演进为 {@code jobs/{id}/diagnosis}</li>
 *   <li>组织级路径**保留**作为"给我最近一次诊断"的便捷入口（前端首页要它）</li>
 * </ul>
 * 这样演进只**新增**路径，不破坏已有调用方。
 */
```

🔴 **这是全项目写得最好的一段"偏离说明"之一**（与 4d 册 `SystemService` 的那段并列 ✓）✓
它具备**四个要素** ✓：
1. **引用原稿的确切文字** ✓（连"必须带 job/version"都引了 ✓）
2. **说明为什么现在做不到** ✓（`schedule_jobs` 还不存在 ✓ —— 一个**时序**原因 ✓）
3. **给出过渡方案** ✓（组织级路径 + 落库 `job_token` ✓）
4. **规划演进路径，并保证向后兼容** ✓（**只新增、不破坏** ✓）

**而且它顺手解释了一个设计** ✓：为什么 `schedule_diagnoses` 有一列 `job_token` ✗
—— 因为没有 `job_id` 可用 ✓ 而"尝试身份"这个概念要提前占位 ✓

#### `diagnosis(...)` 的两个分支

```java
if (Boolean.TRUE.equals(deep)) {
    return diagnosisService.diagnose(organizationId, null, versionId, true, null);
}
DiagnosisResponse latest = diagnosisService.latest(organizationId);
return latest != null
        ? latest
        : diagnosisService.diagnose(organizationId, null, versionId, false, null);
```

| 请求 | 行为 |
|---|---|
| `?deep=true` | **每次重算**深诊断 ✓（几十秒 ✓） |
| **不带参数** | 返回**最近一次落库的** ✓；**从没做过** → 现算一次静态检查 ✓ |

🔴 **"从没做过就现算"这个兜底很重要** ✓ —— 否则新用户第一次点诊断会拿到 **404 或 null** ✗
现在他拿到的是"一份静态体检报告" ✓ —— **一个可用的默认结果而不是错误** ✓

⚠ **而 `deep=true` 那条注释里有一句已经过时的话** ✗：
```java
// 显式要求重算 —— 不传 lastOutcome，因为"上一次求解的结果"已经从状态表丢失了；
```
> "已经从状态表丢失了" ✗ —— **P3 之后 `schedule_jobs` 里有 `solver_status`** ✓ 没有丢 ✓
**这句话在 P3 之前是对的** ✓ 之后就不成立了 ✓ —— 与本册前面那条"（P3 计划）"是**同一类过时** ✓
而且**它们是同一个修法的两半** ✓（把 `solver_status` 从任务表取出来传进去 ✓）

⚠ **`requirePrincipal` 在这里是内联的** ✗ —— 与 `SwapController` 一样 ✓ 与 `VersionController` / `ScheduleQueryController` / `SystemController` 不同 ✓
**第 5 个变体** ✗（4d 册已经记过这个重复 ✓ 本册又添一例 ✓）

### `ScheduleDiagnosisEntity` —— 一处**未兑现的承诺**

```java
/**
 * <p>{@code jobToken} 可为 null —— 按需诊断（还没发起排课时先问"为什么排不出来"）
 * 没有任务身份。**P3 引入 {@code schedule_jobs} 后会再加 {@code job_id}。**
 */
@TableName(value = "schedule_diagnoses", autoResultMap = true)
public class ScheduleDiagnosisEntity {
    ...
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;
}
```

🔴 **P3 已经做完了，但 `job_id` 没有加** ✗：

| 注释承诺 | 实际 |
|---|---|
| P3 引入 `schedule_jobs` ✓ | **做了** ✓ |
| 会再加 `job_id` ✗ | **没加** ✗ —— V3 迁移里 `schedule_diagnoses` 没有任何改动 ✓ |
| 接口演进为 `jobs/{id}/diagnosis` ✗ | **没做** ✗ —— 现在只有组织级路径 ✓ |

⚠ **三处承诺，兑现了零处** ✗ —— 而 `DiagnosisController` 的注释**也写了同样的计划** ✗
**所以有两处注释在描述一个没发生的未来** ✗

（这不是"错"✓ —— 计划赶不上变化很正常 ✓ 但**注释停留在旧计划上**就会误导读者 ✗
读者会以为"已经有了" ✓ 或者"这是个待办" ✓ —— 而 TODO 里并没有这一条 ✗）

#### `autoResultMap = true` —— 这个坑在本项目里第 4 次出现

```java
@TableName(value = "schedule_diagnoses", autoResultMap = true)
...
@TableField(typeHandler = JacksonTypeHandler.class)
private Map<String, Object> payload;
```

**四次** ✓：`Grade.periodsPerDay` ✓ / `GradeRef.periodsPerDay` ✓ / `ScheduleAuditLog.details` ✓ / **`ScheduleDiagnosisEntity.payload`** ✓

🔴 **但这一处的注释没有提这个坑** ✗ ——
对照 `ScheduleAuditLog`（4c-2 册 ✓）那里写了：
> [!] 必须配 `autoResultMap = true`：否则写入正常、**查询出来是 null**……

**这里只是默默地写对了** ✓ —— **没有留下为什么** ✗
⚠ 而 `DiagnosisService.latest()` **正是靠回读 `payload` 工作的** ✗
→ 如果哪天有人"清理没用的注解"删掉 `autoResultMap` ✗ → `latest()` 会**静默地**拿到 `payload = null` ✓
→ `parseConflictRules(null)` 返回空列表 ✓ `stringOf(null, ...)` 返回 null ✓
→ **诊断结果变成一份空壳，而不报错** ✗✓

> 💡 **这是"注释的价值"最直接的一个例子** ✓：
> 同一个坑，写了 `autoResultMap`（`ScheduleAuditLog` ✓）和没写（本类 ✗）
> **写了的那个，后人不会删** ✓ 没写的那个**迟早被删** ✓
> **"配置为什么必须是这样"比"配置是怎么样"重要得多** ✓

### `ScheduleDiagnosisMapper` —— 9 行空接口

**又一次印证那个判断法** ✓：空 `BaseMapper` → 只读/纯 CRUD 场景 ✓
（本册的两个 Mapper 都是 9 行 ✓ —— `DiagnosisService.latest()` 用的是 `selectOne` + 手写 `orderByDesc + LIMIT 1` ✓
而不是自定义 SQL ✓ 所以确实不需要 ✓）

---

## 本册小结

**把这一册串成一句话**：

> 排课失败时界面只有一句话 ✓ 而真实原因有五种 ✓ 其中**最危险的是"超时未证明"** ✗
> （用户会去改本来没问题的数据 ✓）✓
> 所以诊断分两段：**先静态**（毫秒、结论确定 ✓）**后深诊断**（几十秒、逐条去掉硬规则重解 ✓）✓
> 而每一条结论都配一句 `note` —— **说清它知道什么、不知道什么** ✓

**最该带走的三件事**：

1. 🔴 **`note()` 是一台"结论可信度"状态机**（§5d.4）✓
   七个分支，每个都承认自己不知道什么 ✓ 最值钱的一句是：
   **「超时未证明（不是证明无解）—— 不必急着改数据」** ✓
2. **`ERROR` 与 `WARNING` 的分界是"矛盾是否与数据无关"**（§5d.2 ④）✓
   "同节固定两门课" = 逻辑矛盾 → `ERROR` ✓ / "两个年级同节固定" = 依赖老师才成立 → `WARNING` ✓
   原话：**「宁可给一个措辞准确的警告，也不要给一个看起来很确定、但其实是猜的结论」** ✓
3. **`severity` 必须排在 `kind` 定稿之后算**（§5d.4）✓
   两个字段由同一份输入派生 ✓ 其中一个之后还会改 ✗ → 另一个就过期了 ✗ **而且不报错** ✓
   更稳的做法：把结果一次性构造进不可变记录 ✓（本类的 `DiagnosisResponse` 就是 ✓）

### 本册发现的问题（6 处，都没动代码）

| # | 问题 | 性质 |
|---|---|---|
| **1** | 🔴 **`note()` 结尾写着「需要最小冲突集算法（**P3 计划**）」，而那个算法就是本类的 `findConflictRules`** | ⚠ **过时文案** ✓ 而且这条分支**恰恰是"深诊断已跑完但没找到冲突"时才会走到** ✗ → 用户用过了那个算法，却被告知它还没做 ✗ <br>**应改成**「当前的最小冲突集算法覆盖不到」✓ |
| **2** | 🔴 **`DiagnosisController` 的 `deep=true` 传 `lastOutcome=null`**，注释理由是"上一次求解的结果已经从状态表丢失了" | ⚠ **这句话在 P3 之后不成立** ✗ —— `schedule_jobs.solver_status` 有它 ✓ <br>后果：**用户手动诊断可能得到 `kind=OK`，而任务失败后自动诊断得到 `INFEASIBLE`** ✗ —— 同一件事两个结论 ✓ <br>这正是 `diagnoseWithStatus` 的注释所描述的那个 bug ✓ **P3 只修了任务路径** ✓ |
| **3** | **`ScheduleDiagnosisEntity` 与 `DiagnosisController` 都承诺"P3 会加 `job_id`、接口演进为 `jobs/{id}/diagnosis`"** | ⚠ P3 做完了 ✓ 但 **`job_id` 没加、新路径没做** ✗ 而 TODO 里也没有这条 ✗ → 两处注释在描述一个没发生的未来 ✓ |
| **4** | **`ScheduleDiagnosisEntity` 用了 `autoResultMap = true` 但没写为什么** | ⚠ 同一个坑的第 4 次 ✓ 而**只有这一处没留注释** ✗ <br>后果：后人"清理没用的注解"时删掉它 → `latest()` **静默**拿到 `payload = null` ✓ 诊断变成空壳而不报错 ✓ |
| **5** | **`MAX_TRIES = 5` 的截断是静默的** | 用户配了 8 条硬规则 → 只试前 5 条 ✓ 而 `note` 不说 ✓ → 用户以为其余都没问题 ✗ |
| **6** | **`persist` 的方法体首行与签名同一行** ✗ | 格式化事故 ✓ 编译器不介意 ✓ 读的人会愣一下 ✓ |

**另外记下 3 处"设计与观察"**：
- **`fixedslot` 的字符串 key 带来的排序语义问题** ✗：`TreeMap` 按字符串排序 ✓
  `"10:0:0"` 会排在 `"2:0:0"` 前面 ✓ → **10 个年级以上时 finding 顺序会看起来乱** ✓
  而那个顺序**可能被契约测试比对** ✓（字符串 key 在本项目已出现 5 次 ✓）
- **`StaticDiagnostics` 与 `ScheduleInputLoader` 在做同一件事**（7 张表 → 纯数据 ✓）
  两者各查一遍 ✓ → **诊断看的数据与求解看的数据可能不同** ✗
  改成复用 `ScheduleInputLoader` 能同时解决"重复查询"和"数据一致性" ✓
- **`latest()` 是"贵的用旧的、便宜的用新的"的混合** ✓ 设计有想法 ✓
  但 `staticFindings`（新）与 `conflictRules`（旧）**时刻不同** ✗ 且 `kind`（旧）与 `severity`（按新 findings 算）**可能矛盾** ✗
