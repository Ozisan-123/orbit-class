# 代码导读 · 4c-1. 换课：负值腾挪

> **本册范围**：`swap/`（3 个类）+ `validate/`（1 个类），共 **4 个类**：
> `SwapService`、`SwapController`、`SwapRequest`、`VersionValidator`。
>
> 版本管理（`version/`）+ 审计（`audit/`）见 **4c-2**。
>
> 上游：[代码导读.md](代码导读.md)（契约层）· [4a](代码导读-4a-scheduling-排课编排.md) · [4b-2](代码导读-4b2-scheduling-输入快照.md)
>
> 🔴 **本册是全项目最"绕"的一段逻辑** —— `SwapService` 的类注释自己就这么写的：
> 「对应 Python `schedule.py:381-510`，**全项目最"绕"的一段逻辑**」✓

---

## 本模块在系统里的位置

```
用户在课表上拖动一节课
        ↓
POST /api/schedule/versions/{versionId}/swap      ← SwapController
        ↓
   SwapService.swap()                              ← 本册的主角
        ├─ ① 锁版本行（FOR UPDATE）+ 草稿检查 + 乐观锁
        ├─ ② 边界检查（目标节次在不在两个班的上课时间内）
        ├─ ③ 跨班检查（必须同年级）+ 拖回原位 / 空位检查
        ├─ ④ 固定课保护（涉及的位置里有没有年级固定课）
        ├─ ⑤ **负值腾挪**（本册的核心）
        ├─ ⑥ VersionValidator.validate()  ← 整体重校验
        └─ ⑦ revision++ + 写审计
```

**为什么需要这个模块**：排课算法给出的是一个**初始解** ✓ 而现实里教务主任一定会手工微调 ✓
（"这两节换一下"「张老师周三上午有会」✓）
**换课不是"改数据库里的两个格子"那么简单** ✗ —— 它要同时保证：
课表仍然满足所有硬约束 ✓ 固定课不被破坏 ✓ 并发拖动不会互相覆盖 ✓ 每次改动可追溯 ✓

**这四件事分别由**：`VersionValidator`（约束 ✓）、固定课保护（④ ✓）、`FOR UPDATE` + `expectedRevision`（并发 ✓）、审计（⑦ ✓）

## 阅读顺序建议

1. **先读 `SwapService` 的类注释** —— 它把负值腾挪的原理讲清楚了 ✓ 不读它会看不懂后面 200 行。
2. **再读 `swapInClass`（方法，30 行）** —— 腾挪的完整三步在这里 ✓ 读懂它，跨班那段就只是"调两次 + 一个额外分支" ✓
3. **然后读 `swap` 方法本身** —— 七个步骤按顺序看 ✓
4. 最后读 `VersionValidator` —— 它独立于换课 ✓ 是"最后一道闸门" ✓

---

## 4c-1.1 `SwapRequest` —— 一次拖动的完整描述（25 行）

```java
public record SwapRequest(
        @NotNull(message = "Field required") Long sourceEntryId,
        @NotNull(message = "Field required") Long targetClassId,
        @NotNull(message = "Field required") Integer targetDayOfWeek,
        @NotNull(message = "Field required") Integer targetPeriodIndex,
        @NotNull(message = "Field required") Integer expectedRevision) { }
```

**5 个字段 = "移动哪节课 → 放到哪个班的哪一天哪一节" + 一个乐观锁版本号** ✓

**三个细节**：

| 细节 | 说明 |
|---|---|
| **全部 `@NotNull`** | 没有"可选"的字段 ✓ 拖动是一个完整动作 ✓ 缺任何一项都无法执行 ✓ |
| **`message = "Field required"`** ⚠ | 这是 **Pydantic 的原文**（英文 ✓）而不是中文 ✓ 因为 422 的 `msg` 要与 Python 对齐 ✓（见第 1 册 `ValidationErrorItem` ✓ —— 那里说"文案不强求逐字" ✓ 但这里**还是尽量用了原文** ✓ 说明契约测试确实在比对它 ✓ 或至少有人在往上靠 ✓） |
| **都是包装类型**（`Long` / `Integer`） | ⚠ 与 `PageQuery` 是同一个理由（见第 1 册）✓ 但这里更直接：`@NotNull` 校验的就是 `null` ✓ 用原始类型的话**压根表达不出"没传"和"传了 0"的区别** ✗ |

**`targetClassId` 承担了"区分两种换课"的职责** ✓（类注释）：
- `targetClassId == sourceEntry.classId` → **同班拖动** ✓（移动或同班互换 ✓）
- `targetClassId != sourceEntry.classId` → **跨班协同换时** ✓

> ⚠ **用一个字段编码两种语义** 是可以的（这里甚至挺自然 ✓）
> 但代价是：**调用方必须记得传对** ✓ 而且**服务端无法区分"用户想做同班拖动但传错了班级"和"用户真的想跨班"** ✗
> 现在的守卫是"跨班必须同年级 + 目标不能为空"（②③ ✓）✓ 够用 ✓

---

## 4c-1.2 `SwapController` —— 40 行，只做三件事（40 行）

```java
@RestController
@RequestMapping("/api/schedule/versions")
public class SwapController {

    @PostMapping("/{versionId}/swap")
    public VersionResponse swap(@PathVariable Long versionId,
                                @Valid @RequestBody SwapRequest request,
                                @AuthenticationPrincipal SchedulePrincipal principal) {
        if (principal == null) {
            throw BusinessException.unauthorized("请先登录");
        }
        return swapService.swap(principal.organizationId(), principal, versionId, request);
    }
}
```

**取参数 → 空判断 → 调 Service → 返回** ✓ 教科书式的薄 Controller ✓

**两个值得注意的点**：

**① 类注释解释了"为什么路径共用却拆成两个 Controller"** ✓

> 路径前缀与 `VersionController` 相同（`/api/schedule/versions`），但拆成两个 Controller 更清楚：
> 版本管理是 CRUD，换课是一套独立的规则引擎（固定课保护 + 跨班协同 + 整体重校验）。
> **路径本身仍是契约**，拆分只是代码组织。

⚠ 这是一个**很常见的误解点** ✗：「一个 URL 前缀应该对应一个 Controller」✗
实际上 Spring 允许**多个 Controller 共享前缀** ✓ 只要具体的 `@PostMapping` 路径不冲突 ✓
**按职责拆分比按 URL 拆分更合理** ✓ —— 而且这里两个类的**依赖完全不同** ✓
（`VersionService` 依赖版本表 + 审计 ✓ `SwapService` 还要依赖 `VersionValidator` + 三个 Ref Mapper ✓）

**② `principal == null` 的检查** ⚠

理论上到不了这里 ✓ 因为 `SchedulingSecurityConfig` 里 `anyRequest().authenticated()` ✓
但**留一个显式检查是对的** ✓：
- 如果将来有人给这个路径加了 `permitAll` ✗ 这里会立刻 401 ✓ 而不是 NPE ✗
- `@AuthenticationPrincipal` 在未认证时**注入 `null`** ✓ 不会抛异常 ✓ 所以必须自己判 ✗

⚠ **同类检查在各 Controller 里重复出现** ✗ —— 这可以抽成一个 `@AuthenticationPrincipal` 的包装注解 ✓
但现在这样**至少是显式的** ✓

---

## 4c-1.3 `VersionValidator` —— 最后一道闸门（116 行）

**类注释一句话说清了它的价值** ✓：

> 这是"排课结果正确性"的**最后一道闸门** —— 求解器可能因为 bug 或数据变化产出非法结果，
> 这里**不信任求解器，独立重算一遍**。

**"不信任求解器"** 这五个字是重点 ✓ ——
它意味着：即使求解器保证不冲突 ✓ 这里也要**自己再算一遍** ✓
因为课表**还会被人改**（换课 ✓）而人改的东西不受求解器约束 ✓

### 被谁调用（我 grep 过 ✓）

| 调用方 | 位置 | 时机 |
|---|---|---|
| `SwapService` | `:200` | **每次换课之后** ✓ 忽略返回值 |
| `VersionService` | `:177` | **发布之前** ✓ **使用**返回值（`List<ScheduleVersionEntry> entries = ...` ✓） |

> 💡 **为什么返回值只有一处用** ✗：`validate` 会**把条目顺便查出来** ✓
> 换课路径不需要（它自己已经有一份 ✓）✓ 发布路径需要（它接下来要写正式课表 ✓）✓
> **所以那个返回值不是"顺手多给的"** ✓ 而是发布路径真的要用 ✓
> —— 我 grep 确认过 ✓（差点写成"死接口" ✗）

### 两条校验规则

| # | 规则 | 违反时的响应 |
|---|---|---|
| 1 | 每条课都要落在**该班所在年级当天**的课时范围内 | 409 `存在超出班级上课时段的课程（记录 N）` |
| 2 | 同一位教师在同一时段不能出现在多个班 | 409 `X老师在周N第M节存在班级冲突` |

**两条都是硬约束** ✓ 且**都不依赖规则系统** ✓（`constraint_rules` 表 ✗）
—— 因为这两条是**课表能不能存在**的前提 ✓ 不是"用户可调的教学偏好" ✓
**把它们移到规则表里会让"把课表改坏"变成一个选项** ✗ 所以固定在这里是对的 ✓

### 逐段读 `validate`

#### ① 取出这个版本的全部条目

```java
List<ScheduleVersionEntry> entries = entryMapper.selectList(
        Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getVersionId, version.getId()));
```

⚠ 注意**没有分页** ✓ —— 一个版本的全部条目（92 条 ✓ 160 班时可能上万 ✓）一次读进内存 ✓
**这是有意的**：校验必须看全量 ✓ 分页会漏 ✗
代价是内存 ✓ 但 160 班 × 每周 30 节 ≈ 5000 条 × 几十字节 ✓ 可以接受 ✓

#### ② 把班级与年级读成 Map

```java
Map<Long, ClassRef> classes = new java.util.LinkedHashMap<>();
for (ClassRef item : classRefMapper.selectList(...)) { classes.put(item.getId(), item); }
Map<Long, GradeRef> grades = new java.util.LinkedHashMap<>();
for (GradeRef item : gradeRefMapper.selectList(...)) { grades.put(item.getId(), item); }
```

**用 `LinkedHashMap` 而不是 `HashMap`** ✓ —— 理由见下面那条血泪注释 ✓

⚠ **用 `java.util.LinkedHashMap` 全限定名** ✗ 而不是 import ✓
这个类 import 了 `java.util.HashMap` 和 `Map` ✓ 但**没 import `LinkedHashMap`** ✗
—— 大概是"先写了 `HashMap`、后来改成 `LinkedHashMap` 时没加 import、直接写全名" ✓
**无害但突兀** ✓ 记在这里 ✓

#### 🔴 ③ 那条最重要的注释：为什么必须 `LinkedHashMap`

```java
// key = (teacher_id, day, period) → 该时段被排了课的班级 id 列表
//
// ⚠ 必须是 LinkedHashMap（保持遍历顺序 = 条目的读取顺序），不能用 HashMap：
//    下面"报第一个冲突"的行为**依赖遍历顺序**。Python 的 dict 是插入序，
//    HashMap 是无序的 —— 结果是两边都正确地拒绝了这次换课，
//    却报了**不同的冲突**（实测：Python 报"王老师在周1第2节"，Java 报"钱老师在周1第1节"），
//    响应体不一致。同一个输入必须给出同一个答案。
Map<String, List<Long>> teacherSlots = new java.util.LinkedHashMap<>();
```

**这是全项目最值钱的一条注释之一** ✓ 它把一个**极难发现、极难归因**的差异讲透了：

| 层次 | 内容 |
|---|---|
| **表面现象** | "Java 和 Python 报了不同的冲突" ✗ |
| **为什么难查** | 两边**都正确地拒绝了** ✓ 状态码一样 ✓ 只是**文案不同** ✗ 而且**只在有多个冲突时**才出现 ✗ |
| **根因** | "报第一个冲突"这个行为**依赖 Map 的遍历顺序** ✓ 而 Python `dict` 是**插入序** ✓ Java `HashMap` 是**无序** ✓ |
| **修法** | 用 `LinkedHashMap` ✓ 让 Java 也有插入序 ✓ |

> 🔴 **可推广的教训**：**任何"取第一个满足条件的元素"的代码，都在隐式依赖容器的顺序** ✗
> 单测通常只造一个冲突 ✗ 所以永远测不到 ✓
> 这个坑在本项目出现过**至少两次**（一次在这里 ✓ 一次在"报第一个冲突"的别处 ✓ 见踩坑总结 H10 ✓）
> **判断法：代码里有"第一个"这个词，就要问"第一个是按什么顺序"** ✓

#### ④ 逐条检查（规则 1）+ 建立教师时段索引

```java
for (ScheduleVersionEntry entry : entries) {
    ClassRef clazz = classes.get(entry.getClassId());
    GradeRef grade = clazz == null ? null : grades.get(clazz.getGradeId());
    Integer dayOfWeek = entry.getDayOfWeek();
    String dayKey = (dayOfWeek != null && dayOfWeek >= 0 && dayOfWeek < DAY_KEYS.size())
            ? DAY_KEYS.get(dayOfWeek) : null;
    int periods = (grade == null || grade.getPeriodsPerDay() == null || dayKey == null)
            ? 0 : grade.getPeriodsPerDay().getOrDefault(dayKey, 0);
    if (grade == null || dayKey == null || entry.getPeriodIndex() == null
            || entry.getPeriodIndex() < 0 || entry.getPeriodIndex() >= periods) {
        throw BusinessException.conflict("存在超出班级上课时段的课程（记录 " + entry.getId() + "）");
    }
    teacherSlots.computeIfAbsent(
            entry.getTeacherId() + ":" + entry.getDayOfWeek() + ":" + entry.getPeriodIndex(),
            k -> new java.util.ArrayList<>()).add(entry.getClassId());
}
```

**三个要点**：

**① 闰式条件链** —— `dayKey == null` 有**两种来源** ✓：
- `entry.getDayOfWeek()` 是 `null`（数据异常 ✓）
- 或者它超出 `0..4` 的范围（比如 `-1` ✗ ← **负值腾挪的中间态** ✗✓）

⚠ **这很关键** ✗：如果换课过程中崩了 ✗ 而事务没能回滚 ✗ 或者有人直接改库 ✗
那么条目里会留下 `day_of_week = -1` 的**半成品** ✓
**而这里的检查会把它抓出来** ✓ 报"存在超出班级上课时段的课程" ✓
—— **校验器顺带成了"负值腾挪有没有留下垃圾"的探测器** ✓ 这是一个很好的副作用 ✓

**② `periods` 的计算有 4 层兜底** ✓（`grade == null` / `periodsPerDay == null` / `dayKey == null` / `getOrDefault(..., 0)` ✓）
—— 任何一层不满足都变成 `periods = 0` ✓ 于是 `periodIndex >= periods` **必然成立**（因为 `periodIndex >= 0` ✓）
→ **一律报"超出上课时段"** ✓ —— 兜底方向是**拒绝** ✓ 安全的 ✓

**③ 用字符串拼 key** ✗ `entry.getTeacherId() + ":" + entry.getDayOfWeek() + ":" + entry.getPeriodIndex()`
—— **stringly-typed** ✗ 见下面那条观察 ✓

#### ⑤ 报第一个冲突（规则 2）

```java
for (Map.Entry<String, List<Long>> slot : teacherSlots.entrySet()) {
    if (slot.getValue().size() <= 1) { continue; }
    String[] parts = slot.getKey().split(":");
    long teacherId = Long.parseLong(parts[0]);
    int day = Integer.parseInt(parts[1]);
    int period = Integer.parseInt(parts[2]);
    TeacherRef teacher = teacherRefMapper.selectById(teacherId);
    String teacherName = teacher == null ? "教师" : teacher.getName();
    // Python: f"{teacher.name}在周{day + 1}第{period + 1}节存在班级冲突"
    throw BusinessException.conflict(
            teacherName + "在周" + (day + 1) + "第" + (period + 1) + "节存在班级冲突");
}
```

**要点**：

| 点 | 说明 |
|---|---|
| **`size() <= 1` 跳过** | 只有 list 里有 **2 个以上**班级才是冲突 ✓（1 个是正常的 ✓ 0 个不可能 ✓ —— 因为 key 是插入时创建的 ✓） |
| ⚠ **看起来是 N+1 查询** | `selectById(teacherId)` 在循环里 ✗ —— 但**它最多执行一次** ✓ 因为紧接着就 `throw` 了 ✓ **所以不是 N+1** ✓ 但一眼看去很像 ✓ 值得说明 ✓ |
| **`split(":")` 把 key 拆回来** ✗ | 拼进去又拆出来 ✓ —— **这是 stringly-typed key 的典型代价** ✓ 见下 |
| **`teacher == null ? "教师" : ...`** | 兜底：教师被删了也要能报错 ✓ 不能 NPE ✗ |
| ⚠ **`周{day + 1}第{period + 1}`** | **下标 +1** ✓ 因为内部是 0-based ✓ 而用户看到的是 1-based ✓ 注释直接引了 Python 的 f-string ✓ —— **这两处 +1 是文案契约的一部分** ✓ 改错了用户会说"我这个老师周三没课啊" ✓ |

### ⚠ `VersionValidator` 里的 3 个观察（不是 bug）

| # | 观察 | 说明 |
|---|---|---|
| **1** | **字符串拼 key**（`"teacherId:day:period"` ✗） | 拼进去、再 `split` 拆出来 ✓ <br>⚠ **同一个项目里已经有更好的做法** ✓：`SlotVariableIndex` 用的是 `record SlotKey(long classId, int day, int period)` ✓ 编译期类型安全 ✓ 不用拆 ✓<br>**两种约定并存** ✓ 这是"迁移期间两种风格都活着"的典型 ✓ |
| **2** | 用了 `java.util.LinkedHashMap` **全限定名** | 没 import ✓ 见上文 ✓ |
| **3** | `DAY_KEYS` 是 **`public static`** 常量 | ⚠ 它被 `SwapService` 引用（`VersionValidator.DAY_KEYS.size()` ✓）<br>**一个"校验器"的类型里放一个被别处当通用常量用的字段** ✗ —— 位置有点怪 ✓ <br>更合适的位置是 `orbit-common` 或一个 `Week` 工具类 ✓（因为 `SlotVariableIndex` 里**也有一份** `DAY_KEYS` ✗ → **同一天名数组至少存在 3 份** ✓ 见下） |

#### ⚠ 顺带发现：`DAY_KEYS` / `DAYS` 在项目里至少出现 3 次

| 位置 | 形式 |
|---|---|
| `VersionValidator` | `public static final List<String> DAY_KEYS = List.of("Mon",...,"Fri")` |
| `SlotVariableIndex`（2a 册） | `static final int DAYS = 5` + `private static final String[] DAY_KEYS = {...}` |
| 前端 `ScheduleGenerate.tsx` | `const DAY_KEYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri']` |

**三份独立的"周一..周五"** ✗ —— 如果将来要支持六天 ✗ 要改三处 ✓ 漏一处就是**静默不一致** ✓
（后端认为有 5 天、前端画 6 列 ✓ 第 6 天永远是空的 ✓ 不报错 ✓）

> 💡 **这是"重复的常量"最典型的危害**：它不是重复代码那么简单 ✗
> 而是**一个业务规则（"一周上几天"）散落在多处** ✓ 每一处都"看起来对" ✓
> 现在**没有 bug** ✓ 但这是一个真实的可维护性负债 ✓ 我记在这里，没有改动代码 ✓

#### 校验器与事务的关系（一个值得知道的细节）

`SwapService.swap` 是 `@Transactional` 的 ✓ 而 `validate` 在它**内部**被调用 ✓
→ 校验器读到的**是事务内未提交的状态** ✓（同一个连接 ✓ 所以能看到自己刚做的修改 ✓）
**这是对的**：校验的正是"这次换课之后的样子" ✓

⚠ 但如果**别的事务**同时改了同一版本 ✗ 校验器**看不到** ✓
—— 不过 `swap` 开头已经 `FOR UPDATE` 锁住了版本行 ✓ 所以并发修改被挡在门外 ✓ 逻辑是闭合的 ✓

---

## 4c-1.4 `SwapService` —— 🔴 本册的核心（282 行）

### 先理解问题：唯一约束让"互换"变成一件难事

表上有唯一约束（类注释）：
```sql
uq_version_class_slot (version_id, class_id, day_of_week, period_index)
```

**它保证"一个班的同一时段只能有一节课"** ✓ —— 这是课表的基本前提 ✓

**但它让"互换两节课"变成不可能直接做的事** ✗：

```
初始：A 在 (三, 2)，B 在 (三, 3)     想换成 A→(三,3)、B→(三,2)

先改 A：  UPDATE A SET day=3, period=3
          ✗ 撞唯一约束！(三,3) 已经有 B 了

先改 B：  UPDATE B SET day=3, period=2
          ✗ 撞唯一约束！(三,2) 已经有 A 了
```

**无论先改哪个都会撞** ✗ —— 因为约束是**逐条语句检查的** ✓ MySQL 没有"延迟约束"（deferred constraints ✗）

### 解法：把一条记录先改成**负数坐标**

类注释里的三步：
```java
first.day_of_week, first.period_index = -1, -first.id     // 负数不会与任何真实时段冲突
second.day_of_week, second.period_index = old_day, old_period
first.day_of_week, first.period_index = destination_day, destination_period
```

**为什么负数一定不冲突** ✓：
- 真实时段：`day ∈ 0..4`、`period ∈ 0..n-1` ✓（都是非负 ✓）
- 负数坐标：`day = -1` ✓ `period = -id`（负数 ✓）
- **两者的值域不重叠** ✓ → 负值记录**永远不会**和任何真实记录撞唯一约束 ✓

**为什么 `period_index` 用 `-id` 而不是固定 `-1`** ✗ —— 类注释说了：

> 负数用 `-first.id` 而不是固定 -1：**两次调用（跨班时会调两次）不会互相撞**。

⚠ 这就是关键 ✗：跨班换时要调两次 `swapInClass` ✓
如果两次都用 `(-1, -1)` ✗ 那么**第一条还在负值状态时**，第二条也进负值 ✗
→ 两条都在 `(version_id, class_id, -1, -1)` ✗ —— **如果它们同班就会撞** ✓
用 `-id` 保证**每条记录的负坐标都不同** ✓（因为 id 唯一 ✓）

> 💡 **这是一个很漂亮的技巧** ✓ —— 但它有一个**隐含前提** ✗：
> **`day_of_week` 与 `period_index` 两列必须允许负数** ✓
> 如果建表时用了 `TINYINT UNSIGNED` ✗ 那么 `SET day_of_week = -1` 会**报错或被截断** ✓
> ⚠ **我没有核对迁移文件里这两列的类型** ✓ —— 从"换课功能实测可用"反推，它们是有符号的 ✓
> 但这是一个**必须知道**的耦合 ✓ 类注释也点出了：「改表结构时必须一起看这里」✓
>
> ✅ **后来在写 4c-2 时找到了明确答案** ✓ —— 它写在 `ScheduleVersionEntry` 的类注释里：
> > 所以 `day_of_week` 允许出现负数 —— **只在同一事务的中间态**。
>
> **前提是被显式记录过的** ✓ 只是它写在**另一张表的实体**上 ✗
> —— 一个"知识放错位置"的小例子：答案就在代码里，但在你**不会去找的那个文件**里 ✓

### `swap` 方法的七个步骤

#### ① 锁版本行 + 三个前置检查

```java
ScheduleVersion version = versionMapper.selectOne(Wrappers.<ScheduleVersion>lambdaQuery()
        .eq(ScheduleVersion::getId, versionId)
        .eq(ScheduleVersion::getOrganizationId, organizationId)
        .last("FOR UPDATE"));
if (version == null) { throw BusinessException.notFound("课表版本不存在"); }
if (!"draft".equals(version.getStatus())) {
    throw BusinessException.conflict("已发布课表不可拖动，请先复制为新草稿");
}
if (!java.util.Objects.equals(version.getRevision(), request.expectedRevision())) {
    throw BusinessException.conflict("该草稿刚被其他用户修改，请刷新后重试");
}
```

**这里同时用了两种并发保护，它们管的不是一回事** ✓：

| 手段 | 挡什么 | 生命周期 |
|---|---|---|
| **`FOR UPDATE`**（行锁 ✓） | **同时**发生的两个拖动 ✗ 第二个会**等**第一个提交 ✓ | 事务结束 |
| **`expectedRevision`**（乐观锁 ✓） | **先后**发生的两个拖动 ✗ 第二个拿到的是**旧版本号** ✓ → 409 ✓ | 请求之间 |

**为什么两个都要** ✗：
- 只有 `FOR UPDATE` ✗：两个管理员**先后**打开页面 ✓ 各自看到 revision=5 ✓
  甲先提交（revision→6 ✓）✓ 乙后提交 ✓ —— 行锁早释放了 ✗ 乙会**基于过期的视图**覆盖甲的结果 ✗
- 只有 `expectedRevision` ✗：两个请求**同时**带着 revision=5 ✓ 都能通过检查 ✗
  然后各自 `revision++` ✗ 结果是 6 和 6 ✗ **其中一人的改动凭空消失** ✗

> 💡 **这是"悲观锁 + 乐观锁"配合的标准场景** ✓ 值得记住这个区分：
> **行锁解决"同时"，版本号解决"先后"** ✓

⚠ **`.last("FOR UPDATE")` 是字符串拼接** ✗ —— 内容是硬编码的 ✓ 无注入风险 ✓
但它是 MyBatis-Plus 里唯一能加 `FOR UPDATE` 的方式 ✓（`LambdaQueryWrapper` 没有专用 API ✓）

⚠ **`selectOne(...FOR UPDATE)` 如果查不到会不会锁不到东西** ✗ —— 不会 ✓ 查不到就是没这行 ✓ 直接 404 ✓

**三个检查的顺序也有讲究** ✓：不存在（404）→ 状态不对（409）→ 版本号不对（409）✓
**先 404 再 409** ✓ 因为"版本不存在"和"版本不能改"是不同的问题 ✓ 报错了才不会误导 ✓

#### ② 取出源条目与两个班级

```java
ScheduleVersionEntry source = entryMapper.selectOne(...eq(id, sourceEntryId).eq(versionId, version.getId()));
if (source == null) { throw BusinessException.notFound("要移动的课程不存在"); }
```

⚠ **注意 `.eq(ScheduleVersionEntry::getVersionId, version.getId())`** ✓
—— **不只要查 id，还要查它属于这个版本** ✓
否则用户可以传**别的版本**的条目 id ✗ 而版本行锁的是**当前这个版本** ✗
→ **跨版本的并发修改就没被锁保护** ✗✓ 这是一个很重要的细节 ✓

**接着查出源班级与目标班级** ✓ 都是 `ClassRef` ✓ 都带 `organizationId` 过滤 ✓（租户隔离 ✓）
然后**不判空就 `selectById(sourceClass.getGradeId())`** ✗ —— 等等 ✓ 有判空：
```java
if (sourceClass == null || targetClass == null) { throw 404; }
GradeRef sourceGrade = gradeMapper.selectById(sourceClass.getGradeId());
```
✓ 判空在前 ✓ 顺序对 ✓

⚠ **`gradeMapper.selectById(...)` 没有带 `organizationId`** ✗
—— 因为 `gradeId` 来自已经过租户校验的 `sourceClass` ✓ 所以是安全的 ✓
但这是一种**"传递性信任"** ✓ 读代码时要顺着链条看才能确认 ✓ 值得说明 ✓

#### ③ 边界检查（两个方向都要查）

```java
boolean validTarget = targetDay >= 0 && targetDay < VersionValidator.DAY_KEYS.size()
        && targetPeriod >= 0 && targetPeriod < periods(sourceGrade, targetDay)
        && targetPeriod < periods(targetGrade, targetDay);
boolean validSourceForTarget = source.getDayOfWeek() >= 0
        && source.getDayOfWeek() < VersionValidator.DAY_KEYS.size()
        && source.getPeriodIndex() >= 0
        && source.getPeriodIndex() < periods(targetGrade, source.getDayOfWeek());
if (!validTarget || !validSourceForTarget) {
    throw BusinessException.badRequest("目标节次不在两个班级的共同上课时间内");
}
```

🔴 **为什么两个方向都要查** ✗ —— 这是最容易漏的一处 ✓

| 检查 | 含义 | 漏了会怎样 |
|---|---|---|
| `validTarget` | 目标时段要落在**两个班**当天的上课时间内 | 把课拖到目标班根本没有的节次（比如一年级只有 5 节 ✗ 拖到第 6 节 ✗）|
| **`validSourceForTarget`** | **源时段**也要落在**目标班**当天的时间内 | 跨班换时时，把"目标班的课"换到源班那个位置 ✗ 而**目标班那天可能没那么多节** ✗ → 换过去之后**目标班出现一个超出课时的格子** ✗ ✓ |

⚠ 注释写得很准：「源时段也必须落在**目标班**当天的时间内 —— 否则换过去对方那天根本没那么节课」✓

**为什么同班拖动时第二个检查也成立** ✓：同班时 `targetGrade == sourceGrade` ✓
源时段本来就在自己班的范围内 ✓ 所以自动满足 ✓ —— **不冲突** ✓

#### ④ 跨班必须同年级 + 拖回原位 + 空位检查

```java
boolean crossClass = !Objects.equals(source.getClassId(), targetClass.getId());
if (crossClass && !Objects.equals(sourceClass.getGradeId(), targetClass.getGradeId())) {
    throw BusinessException.badRequest("跨班协同换时仅支持同一年级的班级");
}
```

⚠ **为什么跨班必须同年级** ✓ 类注释：「否则作息不同、换完更乱」✓
—— 一年级一天 5 节 ✓ 二年级 6 节 ✓ 互换会让两边的课时数都不对 ✓（上面那个检查会拦 ✗ 但语义上也不该允许 ✓）
**两道防线** ✓

```java
int oldDay = source.getDayOfWeek();
int oldPeriod = source.getPeriodIndex();
ScheduleVersionEntry target = findEntry(version.getId(), targetClass.getId(), targetDay, targetPeriod);
if (target != null && Objects.equals(target.getId(), source.getId())) {
    // 拖回原位 → 什么都不做，直接返回（Python 也是提前 return）
    return VersionResponse.of(version, countEntries(version.getId()));
}
if (crossClass && target == null) {
    throw BusinessException.badRequest("跨班拖动需要放到另一个班级已有课程上进行协同换时");
}
```

**两个早退/拒绝** ✓：

| 情况 | 处理 | 为什么 |
|---|---|---|
| **拖回原位**（`target.id == source.id` ✓） | **直接返回 200** ✓ 不报错、不改动 | ⚠ 这是**幂等性**的设计 ✓ 用户把课拖回原处 = 无事发生 ✓ 报错会很怪 ✓ 而且**前端拖拽经常会产生这种"零位移"事件** ✓（松开鼠标的位置就是它原来的位置 ✓） |
| **跨班 + 目标是空的** | **400** ✓ | ⚠ **跨班只支持"互换"，不支持"移过去"** ✗ 因为源班级那个位置会空出来 ✓ 而课表不该有空缺（周课时必须排满 ✓）✓ |

> 💡 **"跨班不能拖到空位"这条规则的业务含义**：跨班换时本质是"两个班对调两节课" ✓
> 如果目标是空的 ✗ 那移过去之后**源班级会缺一节课** ✓ 而周课时是硬约束 ✓ 课表就非法了 ✓
> **所以它不是一个"功能没做"，而是"这个操作本身不合法"** ✓ 报 400 是对的 ✓

#### ⑤ 固定课保护（第四步，用字符串 key ✗）

```java
Set<String> protectedGradePositions = new HashSet<>();
Map<Long, Long> classGrade = new HashMap<>();
classGrade.put(sourceClass.getId(), sourceClass.getGradeId());
classGrade.put(targetClass.getId(), targetClass.getGradeId());
addProtected(protectedGradePositions, classGrade, sourceClass.getId(), oldDay, oldPeriod);
addProtected(protectedGradePositions, classGrade, sourceClass.getId(), targetDay, targetPeriod);
if (crossClass) {
    addProtected(protectedGradePositions, classGrade, targetClass.getId(), oldDay, oldPeriod);
    addProtected(protectedGradePositions, classGrade, targetClass.getId(), targetDay, targetPeriod);
}
Set<Long> gradeIds = new HashSet<>();
for (String key : protectedGradePositions) {
    gradeIds.add(Long.parseLong(key.split(":")[0]));
}
List<GradeFixedSlotRef> fixedRules = gradeFixedSlotMapper.selectList(...in(gradeId, gradeIds));
for (GradeFixedSlotRef rule : fixedRules) {
    if (protectedGradePositions.contains(rule.getGradeId() + ":" + rule.getDayOfWeek() + ":" + rule.getPeriodIndex())) {
        throw BusinessException.conflict("协同换时涉及固定课程，不能调整");
    }
}
```

**逻辑**：把"这次换课会碰到的 (年级, 星期, 节次)"全列出来 ✓ 查有没有年级固定课 ✓ 有就 409 ✓

**该列哪些位置** ✓：

| 情况 | 要保护的位置 |
|---|---|
| 同班 | 源班的 (源时段) + (目标时段) —— **2 个** |
| 跨班 | 上面 2 个 + 目标班的 (源时段) + (目标时段) —— **4 个** ✓ |

⚠ 注意是**按"班级 → 年级"折算成年级级位置** ✓（因为固定课是**年级级**的 ✓）
所以同年级的两个班，它们的"周三第 2 节"是**同一个受保护位置** ✓
`HashSet` 自动去重 ✓ —— **这就是为什么用 Set 而不是 List** ✓

⚠ **字符串 key 又一次出现** ✗ `"gradeId:day:period"` ✓ 然后 `split(":")[0]` 拆回来 ✓
**与 `VersionValidator` 里的做法完全一样** ✗ —— 说明这是一种**成规模的风格** ✓ 不是偶然 ✓

⚠ **`addProtected` 用 `classGrade` 这个 Map 来查班级的年级** ✗
—— 但 `SwapService` 里**已经有 `sourceClass` / `targetClass` 两个对象** ✓ 直接取 `getGradeId()` 就行 ✗
多建一个 Map 再查一遍 ✓ 是**绕了一圈** ✓ —— 大概是为了让 `addProtected` 的签名统一 ✓

⚠ **`fixedslot` 冲突的文案是"协同换时涉及固定课程，不能调整"** ✗
—— 但它**同班拖动时也会抛**（前两个 `addProtected` 是无条件的 ✓）
**所以同班拖动碰到固定课，也会报"协同换时……"** ✗ 文案与实际不符 ✓

> 🔴 **这是一个真实的文案 bug** ✓ 触发条件：同班拖动，且源或目标位置在该年级有固定课 ✓
> 用户会看到"协同换时涉及固定课程" ✓ 而他做的是同班拖动 ✓ **会以为程序搞错了** ✓
> **修法**：文案改成"涉及固定课程，不能调整" ✓ 或按 `crossClass` 分两句 ✓
> ⚠ **我没有验证过这个场景**（需要真实数据 + 手工拖动 ✓）✓ 但**从代码路径看必然如此** ✓
> 因为 `addProtected` 的前两行调用没有 `if (crossClass)` 守卫 ✓
> **标记为"代码路径推导，未实测"** ✓

#### ⑥ 腾挪（核心）

**同班拖动**

```java
swapInClass(source, target, oldDay, oldPeriod, targetDay, targetPeriod);
```
一行 ✓ 全部逻辑在 `swapInClass` ✓

**跨班协同换时** —— 分四步，**顺序不能变**：

```java
ScheduleVersionEntry sourceMirror = findEntry(version.getId(), sourceClass.getId(), targetDay, targetPeriod);
ScheduleVersionEntry targetMirror = findEntry(version.getId(), targetClass.getId(), oldDay, oldPeriod);
swapInClass(source, sourceMirror, oldDay, oldPeriod, targetDay, targetPeriod);

int targetOldDay = target.getDayOfWeek();
int targetOldPeriod = target.getPeriodIndex();
target.setDayOfWeek(-2);
target.setPeriodIndex(-target.getId().intValue());
entryMapper.updateById(target);
if (targetMirror != null) {
    targetMirror.setDayOfWeek(targetOldDay);
    targetMirror.setPeriodIndex(targetOldPeriod);
    entryMapper.updateById(targetMirror);
}
target.setDayOfWeek(oldDay);
target.setPeriodIndex(oldPeriod);
entryMapper.updateById(target);
```

**先把"镜像"这个词解释清楚** ✓ 类注释：
> **镜像**：跨班时还要处理"对方班在我原来的时段那一节"和"我在对方目标时段那一节"

用具体例子最清楚 ✓。设：
- 甲班（三年级）的课 `A` 在 **(三, 2)**
- 乙班（三年级）的课 `B` 在 **(三, 4)**
- 用户把 `A` 拖到乙班的 (三,4) ✓

那么涉及的**四个位置**：

| 位置 | 原来是谁 | 最终应该是谁 |
|---|---|---|
| 甲班 (三,2) | **A** | `targetMirror`（乙班原来在 (三,2) 的那节课，如果存在） |
| 甲班 (三,4) | `sourceMirror`（甲班原来在 (三,4) 的课，如果存在） | **B** |
| 乙班 (三,2) | `targetMirror` | **A**（A 被移过来 ✓）hmm ✗ |
| 乙班 (三,4) | **B** | `sourceMirror` |

Hmm ✗ 让我重新按代码推 ✓：

- `source` = A（甲, 三, 2）✓
- `target` = B（乙, 三, 4）✓
- `sourceMirror` = `findEntry(甲, targetDay=3, targetPeriod=4)` = 甲班在 (三,4) 的课 = **S** ✓
- `targetMirror` = `findEntry(乙, oldDay=3, oldPeriod=2)` = 乙班在 (三,2) 的课 = **T** ✓

`swapInClass(source=A, second=sourceMirror=S, old=(3,2), dest=(3,4))`：
1. `A → (-1, -A.id)` ✓
2. `S → (3,2)` ✓（S 从 (三,4) 搬到 A 原来的位置 ✓）
3. `A → (3,4)` ✓（A 搬到 S 原来的位置 ✓）

**结果**：甲班 (三,2)=S ✓ (三,4)=A ✓（S 和 A 互换了 ✓）

然后：
- `targetOldDay/Period` = B 的原位置 (3,4) ✓
- `B → (-2, -B.id)` ✓
- `T → (3,4)` ✓（T 从 (三,2) 搬到 B 原来的位置 ✓）
- `B → (3,2)` ✓（B 搬到 A 原来的位置 ✓）

**最终**：

| 位置 | 最终 | 直观解释 |
|---|---|---|
| 甲 (三,2) | **S** | 甲班原来 (三,4) 的课，搬到了 A 空出的位置 |
| 甲 (三,4) | **A** | 用户拖动的课 ✓ |
| 乙 (三,2) | **B** | 用户拖动的目标课 ✓ |
| 乙 (三,4) | **T** | 乙班原来 (三,2) 的课，搬到了 B 空出的位置 |

**所以"跨班协同换时"实际是两组独立的同班互换** ✓：
- 甲班内部：A 和 S 互换 ✓（`swapInClass` 一次 ✓）
- 乙班内部：B 和 T 互换 ✓（手写的那四行 ✓）

> 💡 **这解释了为什么 `target` 那一段是"手写的"而不是再调一次 `swapInClass`** ✓
> 因为 `swapInClass(first, second, oldDay, oldPeriod, dest)` 的语义是
> "把 first 从 old 挪到 dest、把 second 挪到 old" ✓
> 而乙班这边要做的是"把 B 从 (3,4) 挪到 (3,2)、把 T 挪到 (3,4)" ✓
> —— 参数顺序与 `swapInClass` 的约定**正好相反** ✗（`swapInClass` 是"second 去 old" ✓ 这里要的是"second 去 dest" ✓）
> **所以没法复用** ✓ 手写是对的 ✓ 但**没有注释说明这一点** ✗
> 读代码的人会问"为什么这里不调 `swapInClass`" ✓ —— 这是一个值得补注释的地方 ✓

⚠ **`-2` 与 `-1` 的区别** ✓ 注释：「用 -2 区分于 `swapInClass` 里的 -1（调试时能看出是谁在腾挪）」✓
—— 一个**为了排查方便**的设计 ✓ 因为两条记录的负坐标除了符号之外无法区分 ✓
**代价**：`-2` 是一个"魔法数字" ✗ 没有常量名 ✓（`NEGATIVE_DAY_FROM_CROSS_CLASS = -2` ✓ 会更好 ✓）

⚠ **`targetMirror` 的搬移在 `target` 之后** ✓ 顺序不能反 ✗：
- `target` 要搬到 `(oldDay, oldPeriod)` = `targetMirror` 的位置 ✓
- **所以 `targetMirror` 必须先腾出去** ✓
代码正是这样 ✓（`targetMirror` 在 187-191 ✓ 而 `target` 落到 `(oldDay, oldPeriod)` 在 192-194 ✓）

#### ⑦ 整体重校验 + revision++ + 审计

```java
validator.validate(version);                     // ⑥

version.setRevision(version.getRevision() + 1);
version.setUpdatedAt(UtcTimes.now());
versionMapper.updateById(version);

String action = crossClass ? "cross_swapped" : (target != null ? "swapped" : "moved");
```

**三种 action 语义清楚** ✓：

| action | 何时 |
|---|---|
| `cross_swapped` | 跨班协同换时 ✓ |
| `swapped` | 同班、目标位置有课 ✓ |
| `moved` | 同班、目标位置为空 ✓ |

⚠ **审计详情用 `LinkedHashMap`** ✓（`java.util.LinkedHashMap` ✓ 保序 ✓ 落库成 JSON 时可读 ✓）
—— 和同一个方法里用 `HashSet` / `HashMap` 形成对比 ✗
**说明作者知道"落库的顺序重要"** ✓ 只是没对"临时集合"这么要求 ✓

**审计记录了什么** ✓：
```java
details.put("class_id", ...); details.put("target_class_id", ...);
details.put("from", List.of(oldDay, oldPeriod));
details.put("to", List.of(targetDay, targetPeriod));
details.put("source_entry_id", ...); details.put("target_entry_id", target == null ? null : target.getId());
```
**6 个字段足够复现这次操作** ✓ 而且 `target_entry_id` 允许 `null` ✓（表示"移到了空位" ✓）

⚠ **`details` 是 `Map<String, Object>`** ✓ 而 `ScheduleAuditLog.details` 用 `JacksonTypeHandler` 映射成 JSON ✓
**这意味着审计的结构是"自由的"** ✓ —— 好处是不用改表就能加字段 ✓ 代价是**没有类型约束** ✓
如果将来有人写错了 key 名 ✗ 不会有任何报错 ✓ 只是查询时找不到 ✓

#### 一个隐藏的"写放大"

⚠ **一次同班互换会执行 3 次 UPDATE** ✓（`swapInClass` 里三次 `updateById` ✓）
跨班是 **3 + 3 = 6 次** ✓（`swapInClass` 3 次 + target 那段的 3 次 ✓）

**为什么不能合并成一次** ✗ —— **因为唯一约束是逐条语句检查的** ✓
必须让数据库在**每一条语句之后**都处于合法状态 ✓
**所以"多写几次"是负值腾挪这个方案的固有代价** ✓ 不是没优化 ✓

> 💡 这也解释了为什么 `@Transactional` 在这里是**必需**的 ✓
> 因为中间状态（记录处于负坐标 ✓）**不合法** ✗
> 如果事务中途失败且不回滚 ✗ 课表里就会留下 `day_of_week = -1` 的垃圾 ✓
> —— 而 `VersionValidator` 恰好能把它检测出来 ✓（见 §4c-1.3 ④ ✓）✓ **两道防线对上了** ✓

### `swapInClass` —— 三步腾挪（30 行）

```java
private void swapInClass(ScheduleVersionEntry first, ScheduleVersionEntry second,
                         int oldDay, int oldPeriod, int destinationDay, int destinationPeriod) {
    if (first == null) { return; }
    first.setDayOfWeek(-1);
    first.setPeriodIndex(-first.getId().intValue());
    entryMapper.updateById(first);          // ① first 先"消失"到负坐标

    if (second != null) {
        second.setDayOfWeek(oldDay);
        second.setPeriodIndex(oldPeriod);
        entryMapper.updateById(second);     // ② second 搬到 first 原来的位置
    }
    first.setDayOfWeek(destinationDay);
    first.setPeriodIndex(destinationPeriod);
    entryMapper.updateById(first);          // ③ first 落到目标位置
}
```

**三步顺序的必要性**（这是全册最该记住的一段 ✓）：

| 步骤 | 为什么必须在这个位置 |
|---|---|
| ① first → 负值 | **必须先腾出 `old` 位置** ✓ 否则 ② 里 second 搬过去会撞 ✗ |
| ② second → `old` | **必须搬走 second** ✓ 否则 ③ 里 first 搬到 `destination` 会撞 ✗（因为 second 原本就在那里 ✓） |
| ③ first → `destination` | 此时 `destination` 已空 ✓ 而 `old` 已被 second 占了 ✓ 两条都合法 ✓ |

⚠ **`if (first == null) return;`** ✓ 防御 ✓ 但调用方（`swap`）已经保证 `source != null` ✓
（而跨班时传的是 `source` ✓ 同班时也是 `source` ✓ —— 所以这段防御**永远不会触发** ✗ 死代码 ✓）

⚠ **`second == null` 的情况** ✓ 同班拖到空位 ✓ → 只做 ① 和 ③ ✓ **就是"移动"** ✓
—— **"移动"和"交换"用同一个方法** ✓ 靠 `second` 是否为空区分 ✓ 很紧凑 ✓

### ⚠ `SwapService` 的隐含依赖清单

这是**读这个类时最容易忽略、但最重要**的一张表 —— 它的正确性依赖**这么多外部事实**：

| # | 依赖 | 在哪里被依赖 |
|---|---|---|
| 1 | 唯一约束 `uq_version_class_slot` **存在** | 负值腾挪的**前提** ✓ 类注释明确写了「这个设计直接依赖唯一约束的存在（否则没必要绕）」✓ |
| 2 | `day_of_week` / `period_index` 两列**允许负数** | ① 步写 `-1` ✓ ⚠ 未核对列类型 |
| 3 | 唯一约束是**逐条语句**检查（不是延迟到提交） | 否则不需要三步 ✓ |
| 4 | 固定课只在**年级级**（`grade_fixed_slots`） | ④ 步只查这一张 ✓（刻意不含班级级 `fixed_slots` ✓ 与 4b-2 册一致 ✓） |
| 5 | 跨班换时**必须同年级** | ③ 步的边界检查建立在"两个班作息相同"之上 ✓ hmm —— **其实不完全** ✗ 同年级的班 `periods_per_day` 相同 ✓（来自年级 ✓）所以成立 ✓ |
| 6 | `revision` 列**非空** | `version.getRevision() + 1` 会 NPE ✓ 如果为 null ✗ |
| 7 | `SwapService` 与 `VersionValidator` **在同一个事务里** | ⑦ 的校验必须看到未提交的改动 ✓ |

> 💡 **第 1、2、3 条尤其值得记住** ✓ —— 它们把"改表结构"和"改换课逻辑"**绑在了一起** ✓
> 如果有人把唯一约束去掉（"反正应用层会检查" ✗）✓
> 负值腾挪**不会报错** ✓ 只是变成了一段没必要的怪代码 ✗ 而真正的并发保护也没了 ✗
> **类注释已经警告了**：「改表结构时必须一起看这里」✓

---

## 本册小结

**把这一册串成一句话**：

> 拖动一节课 ✗ 不是"改两个格子" ✓ 而是**在"一个班一个时段只能有一节课"这个唯一约束下做一次置换** ✓
> 因为约束是逐条语句检查的 ✗ 所以必须**先把一条记录挪到负数坐标"暂时不存在"** ✓
> 挪完之后**重跑一遍整表校验**（不信任自己 ✓）✓ 最后 `revision++` + 写审计 ✓

**最该带走的三件事**：

1. **负值腾挪的三步顺序不可变**（§4c-1.4 `swapInClass`）✓
   `first` 先"消失" → `second` 占位 → `first` 落到目标 ✓ 每一步之后数据库都合法 ✓
2. **`LinkedHashMap` 不是风格问题**（§4c-1.3 ③）✓
   "报第一个冲突"隐式依赖遍历顺序 ✓ 用 `HashMap` 会让两边**报不同的冲突** ✗ 而**两边都拒绝得对** ✓ 所以极难发现 ✓
3. **行锁和版本号管的是不同的事**（§4c-1.4 ①）✓
   `FOR UPDATE` 挡"同时" ✓ `expectedRevision` 挡"先后" ✓ 缺一个都会丢改动 ✓

### 本册发现的问题（4 处，都没动代码）

| # | 问题 | 性质 |
|---|---|---|
| **1** | 🔴 **固定课冲突的文案与场景不符** | 同班拖动碰到固定课时，报的是 **"协同换时涉及固定课程，不能调整"** ✗ 而用户做的是同班拖动 ✓ <br>**根因**：`addProtected` 的前两次调用**没有 `if (crossClass)` 守卫** ✓ 但文案写死了"协同换时" ✓ <br>⚠ **从代码路径推导，未实测** ✓ |
| **2** | `DAY_KEYS` / `DAYS` 在项目里**至少 3 份**（`VersionValidator` / `SlotVariableIndex` / 前端） | ⚠ 支持六天要改三处 ✓ 漏一处是**静默不一致**（后端 5 天、前端画 6 列 ✓ 第 6 列永远空 ✓ 不报错）✓ |
| **3** | 字符串拼 key（`"gradeId:day:period"` / `"teacherId:day:period"`）在**两个类里各出现一次** | ⚠ 拼进去再 `split` 拆出来 ✓ 而**同一个项目里已有更好的做法**（`SlotVariableIndex` 用 `record SlotKey` ✓）✓ 两种约定并存 ✓ |
| **4** | `swapInClass` 的 `if (first == null) return;` 是**死代码**；`-2` 是**魔法数字**；`java.util.LinkedHashMap` 全限定名 | ⚠ 都是小瑕疵 ✓ 不影响正确性 ✓ |

**另外记下两处"必读的隐含前提"**（不是 bug ✓）：
- **负值腾挪依赖三件事同时成立**：唯一约束存在 ✓ 两列允许负数 ✓ 约束逐条语句检查 ✓
  类注释警告了「改表结构时必须一起看这里」✓
- **跨班协同换时实际是"两组独立的同班互换"** ✗ 而乙班那组**没法复用 `swapInClass`** ✓
  因为参数约定正好相反 ✓ **这一点没有注释说明** ✗ 读的人会问"为什么不复用" ✓
