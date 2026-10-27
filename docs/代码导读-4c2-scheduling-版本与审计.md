# 代码导读 · 4c-2. 课表版本与审计

> **本册范围**：`version/`（8 个类）+ `audit/`（3 个），共 **11 个类**：
> `VersionService`、`VersionController`、`ScheduleVersion`、`ScheduleVersionMapper`、
> `ScheduleVersionEntry`、`ScheduleVersionEntryMapper`、`VersionResponse`、`VersionUpdateRequest`、
> `ScheduleAuditLog`、`ScheduleAuditLogMapper`、`AuditResponse`。
>
> 换课（负值腾挪）与独立校验器见 [4c-1](代码导读-4c1-scheduling-换课负值腾挪.md)。
>
> 上游：[代码导读.md](代码导读.md)（契约层）· [4a](代码导读-4a-scheduling-排课编排.md)（排课编排）

---

## 本模块在系统里的位置

**排课产出的不是一个"课表"，而是一个"版本"** ✓

```
                    generate（求解）
                          ↓
              schedule_versions（一版草稿，status=draft）
                 + schedule_version_entries（它的明细：一节课一行）
                          ↓  人工拖动/换课（4c-1）
                          ↓
              管理员点「发布」（publish）
                          ↓
        ┌─────────────────┴──────────────────┐
        ▼                                    ▼
  本版 status=published          schedules 表被"整体重写"为它的拷贝
  上一版 status=archived         ← 这张表是「当前正式课表」的投影，
                                   查询课表的接口读的是它
```

**为什么要分"版本表 + 明细表"和另一张 `schedules`** ✗：

| 表 | 是什么 | 谁读 |
|---|---|---|
| `schedule_versions` + `schedule_version_entries` | **所有版本**（草稿 / 已发布 / 已归档） | 版本列表、审计、发布流程 |
| **`schedules`** | **只有最新发布的那一版**的投影 | 所有"看课表"的接口（`query/` ✓ 4d 册） |

**好处**：查课表时不用先查"哪一版是当前的" ✓ 直接读 `schedules` ✓ 少一次 JOIN ✓ 而且那张表小得多 ✓
**代价**：发布时要**整体重写**它 ✗（先清空再写 ✓）→ 见 §4c-2.1 `publish` ✓

**审计（`audit/`）是版本的第二条线** ✓：每次改名 / 复制 / 发布 / 换课都写一条 ✓
它回答的是"**这版课表是怎么变成现在这样的**" ✓ 而版本表只回答"现在是什么" ✓

## 阅读顺序建议

1. **`ScheduleVersion`** —— 先看实体 ✓ 尤其是它那三个**溯源指纹列**的注释 ✓（那是 P2/P3 的成果 ✓）
2. **`VersionService` 的四个业务方法**（list / update / copy / publish ✓）—— 本册主体 ✓
3. **`ScheduleVersionEntry` 的类注释** —— 🔴 它写清了**唯一约束 vs 普通索引**的区别 ✓
   这一条是理解"为什么教师撞课要靠校验器"的钥匙 ✓
4. 其余（Controller / DTO / Mapper / 审计）扫一眼即可 ✓

---

## 4c-2.1 两个实体

### `ScheduleVersion` —— 171 行，其中 40 行是三个指纹列

```java
@TableName("schedule_versions")
public class ScheduleVersion {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private Integer versionNo;          // 组织内递增，有唯一约束
    private String name;
    private String description;
    private String status;              // draft / published / archived
    private Integer revision;           // 乐观锁
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    // —— 以下三列是 P2/P3 加的 ——
    private String ruleSetHash;
    private String snapshotFingerprint;
    private String modelFingerprint;
}
```

**类注释里有一句特别值得记的话** ✓：

> `revision` 是**乐观锁**：客户端改之前先拿到当前 revision，提交时带上 `expected_revision`，
> 不匹配就 409（`版本已被其他用户修改，请刷新后重试`）。
> **与 `job_token`（悲观栅栏）是两种不同的并发控制，别混。**

🔴 **这一句直接回答了"项目里两种并发控制怎么分"** ✓ —— 值得单独展开 ✓：

| | `revision`（乐观锁） | `job_token`（悲观栅栏，P3） |
|---|---|---|
| 表 | `schedule_versions` | `schedule_jobs` |
| 谁持有 | **客户端**（前端拿到、提交时带回）✓ | **服务端**（领单时生成）✓ |
| 挡什么 | **人的并发编辑** ✓ 两个管理员先后打开同一个草稿 ✗ | **进程的并发执行** ✓ 两个 worker 领同一个任务 ✗ |
| 冲突时 | **409 让用户刷新重试** ✓（人参与解决 ✓） | **静默丢弃结果** ✓（无人参与 ✓ 新任务自己收尾 ✓） |
| 语义 | "**你看到的版本过期了**" | "**你已经不是持有者了**" |
| 为什么这样选 | 用户在场 ✓ 让他重新看一遍是**正确**的做法 ✓ | 用户不在场 ✓ 报错只会制造噪音 ✓ |

> 💡 **判断用哪种的简单法则**：
> **冲突时"有没有人能做出更好的决定"** ✓
> - 有（两个管理员）→ 乐观锁 ✓ 让人来重试 ✓
> - 没有（两个进程）→ 栅栏 ✓ 让系统自己收尾 ✓
>
> 本册的两处并发控制（`update` / `publish` 的 revision ✓）与 5b 册的（`job_token` ✓）
> 用的正是这两种 ✓ 而它们**恰好在项目里共存** ✓ 所以这句"别混"的提醒很有必要 ✓

#### 三个溯源指纹列 —— 为什么值得单独说

```java
/**
 * 生成这一版时用的规则集哈希（P2 新增，V2 迁移加的列）。
 *
 * 方案 §5.2：「评分必须与 rule_set_hash 绑定 —— 同一张课表在不同规则集下分数不同，
 * 不记 hash 的分数没有意义」。有了它，审计链才能回答「这版课表用的哪套规则」，
 * 并保证 **P1 之前生成的版本为 null**（那时还没有规则表）。
 */
private String ruleSetHash;

/** 用的哪份**输入快照**（P3）。与 ruleSetHash、modelFingerprint 合起来可精确复现。 */
private String snapshotFingerprint;

/** 编译出的 CP-SAT 模型指纹（P3）。 */
private String modelFingerprint;
```

**三列 = 精确复现一版课表所需的全部信息** ✓：

| 列 | 回答 | 来自 |
|---|---|---|
| `rule_set_hash` | **用哪套规则** | P2 ✓ 规范化哈希（5c 册） |
| `snapshot_fingerprint` | **用哪份输入数据** | P3 ✓ 入队时冻结的快照（5a 册） |
| `model_fingerprint` | **编译出的模型** | P3 ✓ `OrbitSolver.fingerprintOf`（2a 册） |

**为什么"可复现"这么重要** ✗ —— 因为排课**不是确定性的** ✓（2a 册讲过：CP-SAT 在多个最优解间选择不确定 ✓）
所以"三个月前那版课表是怎么来的"这个问题 ✗ **只有靠这三个指纹**才能回答 ✓
没有它们 ✓ 你只知道"有过这一版" ✓ 而**无法重放** ✓

⚠ **注意 `ruleSetHash` 的注释里那句"保证 P1 之前生成的版本为 null"** ✓
—— 这是一个**刻意的数据语义** ✓：`null` 意味着"这版是在有规则表之前生成的" ✓
而不是"忘了写" ✓ **把 null 赋予意义** ✓ 是数据设计里很实用的一招 ✓
（代价：查询时 `WHERE rule_set_hash IS NULL` 要记得写 ✓）

⚠ **三个字段的 getter/setter 被放在了类的最前面**（第 53-75 行 ✓）而其余字段的在后面 ✗
—— 说明它们是**后加的** ✓（在文件里"插队"了 ✓）
**无害，但读起来突兀** ✓ 也侧面印证了"P2/P3 增量加列"的历史 ✓

### `ScheduleVersionEntry` —— 100 行，但类注释是**全册最值钱的一段**

```java
@TableName("schedule_version_entries")
public class ScheduleVersionEntry {
    private Long organizationId, versionId, classId, subjectId, teacherId;
    private Integer dayOfWeek, periodIndex;
}
```

**字段本身毫无意外** ✓ 但类注释讲了**两条约束的不同性质** ✓：

> 唯一约束有两层意义（都在这张表的 DDL 里）：
> - `uq_version_class_slot (version_id, class_id, day_of_week, period_index)`：
>   一个班的同一时段只能有一节课
> - `ix_version_teacher_slot (version_id, teacher_id, day_of_week, period_index)`
>   **只是普通索引 —— 数据库不禁止教师撞课**，靠 `VersionValidator` 在换课/发布时校验

🔴 **这两行的区别，决定了整个"校验收敛在哪里"的设计** ✓：

| 约束 | 类型 | 谁在保护 |
|---|---|---|
| **一个班一个时段一节课** | **唯一约束** ✓ | **数据库**（硬保护 ✓ 连 bug 都绕不过去 ✓） |
| **一个教师一个时段一节课** | **普通索引** ✗ | **只有 `VersionValidator`** ✓ —— 一旦有路径绕过它，数据库**不会拦** ✗ |

**为什么教师那边不建唯一约束** ✗ —— 一个合理的推断是：
**跨班换时的中间态会让同一位教师"短暂地"出现在两个班** ✓（腾挪时记录处于负坐标 ✓ 而教师字段没变 ✗）
如果教师也有唯一约束 ✗ 那腾挪手法就得再加一层"暂时改教师"✗ 复杂度暴涨 ✗
（⚠ **这是我的推断，代码里没有说明** ✓ 标记为推断 ✓）

**所以 `VersionValidator` 不是"多一道保险"** ✗ —— 对教师冲突而言，**它是唯一的一道** ✓
这提高了它的地位 ✓ 也解释了为什么它要"不信任求解器，独立重算一遍" ✓（4c-1 册 ✓）

**而且这两条约束的差别还有一个直接后果** ✓：
`SwapService` 的负值腾挪**只对班级唯一约束必要** ✓
如果只有教师约束 ✓ 那交换两节课根本不用绕 ✓ —— 所以**负值腾挪的存在，正是因为班级约束是硬约束** ✓

> ⚠ **我之前在 4c-1 册里问"负值腾挪依赖两列允许负数，但没核对列类型"** ✓
> 同一段注释里就有答案：
> > 所以 `day_of_week` 允许出现负数 —— **只在同一事务的中间态**。
>
> **答案本来就在代码里** ✓ 只是它写在**另一张表的实体**上 ✗ —— 一个"知识放错位置"的小例子 ✓

---

## 4c-2.2 三个 DTO

### `VersionResponse` —— 29 行，但有一个"null 变空串"的契约

```java
public record VersionResponse(Long id, Integer versionNo, String name, String description,
                              String status, Integer revision, long entryCount,
                              Long createdBy, Long publishedBy,
                              LocalDateTime createdAt, LocalDateTime updatedAt,
                              LocalDateTime publishedAt) {

    public static VersionResponse of(ScheduleVersion version, long entryCount) {
        return new VersionResponse(
                version.getId(), version.getVersionNo(), version.getName(),
                version.getDescription() == null ? "" : version.getDescription(),   // 👈
                version.getStatus(), version.getRevision(), entryCount,
                version.getCreatedBy(), version.getPublishedBy(),
                version.getCreatedAt(), version.getUpdatedAt(), version.getPublishedAt());
    }
}
```

**类注释解释了那个 `null → ""`** ✓：

> ⚠ `description` 在 Python 侧**可能是 None 但输出成 ""**
> （`description=version.description or ""`），所以这里也给空串而不是 null，
> **两者对前端是不同的（前者能直接渲染，后者会显示 undefined）**。

🔴 **这是一个容易被当成"多此一举"的地方** ✗ —— 但它有实际后果 ✓：

| 后端返回 | 前端 `{version.description}` 渲染出 |
|---|---|
| `""` ✓ | **空** ✓ |
| `null` ✓ | **`undefined`** ✗（React 会把它当字符串渲染出来 ✗ hmm，实际 React 对 `null` 渲染为空 ✓ 但对 `undefined` 也是空 ✓） |

⚠ **更准确的说法**：React 对 `null` 和 `undefined` **都不会渲染出文字** ✓
但它们在**其他场景**下有区别 ✗：
- `description.length` → `null` 抛 TypeError ✗ `""` 返回 0 ✓
- antd 的 `<Input value={x}>` → `null` 会变成非受控组件 ✗ `""` 是受控的 ✓
- `JSON.stringify` 后 `null` 会被保留 ✓ 而契约测试会看到字段类型不同 ✓

**所以这个映射是必要的** ✓ 只是注释里的理由（"显示 undefined"）说得不太准 ✓
**真正的理由是"契约一致"** ✓ —— Python 输出 `""` ✓ Java 也必须 `""` ✓ 否则契约测试红 ✓

> 💡 这类 DTO 里的"小转换"，**看代码时最容易划过去** ✗
> 但它们是"两边行为等价"的具体落点 ✓ 每一个都对应 Python 里一行 `or ""` / `or {}` / `or []` ✓
> **本册三个 DTO 各有一个** ✓（见下两小节 ✓）

### `VersionUpdateRequest` —— 22 行

```java
public record VersionUpdateRequest(
        String name,
        String description,
        @NotNull(message = "Field required") Integer expectedRevision) { }
```

**注意三个字段的三处不同** ✓：

| 字段 | 注解 | 含义 |
|---|---|---|
| `name` | **无** ✓ | 可选 ✓ 不传就不改 ✓（Python `Optional[str] = None` ✓） |
| `description` | **无** ✓ | 同上 ✓ |
| `expectedRevision` | **`@NotNull`** ✓ | **必填** ✓ |

⚠ **`expectedRevision` 必填这件事，注释特意说明了** ✓：
> `expected_revision` 是**必填**（Python 没有默认值，缺了是 422）

🔴 **"必填"与"可选"的区别直接决定了状态码** ✓：
- 不传 `expectedRevision` → **422** ✓（`{"detail":[{"type":"missing","loc":["body","expectedRevision"],...}]}` hmm — `loc` 会是 snake_case ✓ 因为 `ApiExceptionHandler.jsonName` 转换 ✓）
- 传了但值不对 → **409** ✓（`版本已被其他用户修改，请刷新后重试` ✓）

**这两个不同的状态码是契约** ✓ 前端据此区分"我请求写错了"（不该发生 ✓）和"别人改过了"（要提示刷新 ✓）
—— 如果都写成 400 ✗ 前端就没法区分 ✓ 只能一律提示"刷新重试" ✓ 而真正的协议错误会被掩盖 ✗

> 💡 这是第 1 册 `MissingServletRequestParameterException` 那条的**同族** ✓
> 「**没传 → 422；传了但不对 → 4xx**」✓ 项目里这条规则在多处出现 ✓

### `AuditResponse` —— 16 行，一个"null 变空 Map"

```java
public record AuditResponse(Long id, String userName, String action,
                            Map<String, Object> details, LocalDateTime createdAt) {

    public static AuditResponse from(ScheduleAuditLog log) {
        return new AuditResponse(log.getId(), log.getUserName(), log.getAction(),
                // Python: details=item.details or {}
                log.getDetails() == null ? Map.of() : log.getDetails(),
                log.getCreatedAt());
    }
}
```

**与 `VersionResponse` 的那处完全同构** ✓（`or ""` ↔ `or {}` ✓）
—— 说明这个项目对"Python 的 `or` 兜底"是**逐个对齐**的 ✓ 而不是"大概差不多" ✓

⚠ **`AuditResponse` 不包含 `versionId` / `organizationId` / `userId`** ✗
—— 因为它只在"某个版本的审计列表"里返回 ✓ 版本 id 是路径参数 ✓ 组织是当前租户 ✓
**所以省略是合理的** ✓ 但少了 `userId` ✗ —— 只有 `userName` ✓
（`userName` 是**快照**（写审计时的名字 ✓）而 `userId` 才是标识 ✓
如果用户改名了 ✗ 审计里显示的是当时的名字 ✓ —— **这其实是审计该有的行为** ✓ 好设计 ✓）

---

## 4c-2.3 `VersionController` —— 5 个接口（77 行）

```java
@RestController
@RequestMapping("/api/schedule/versions")
public class VersionController {

    @GetMapping                      public PageResponse<VersionResponse> list(PageQuery query, ...)
    @PatchMapping("/{versionId}")    public VersionResponse update(@Valid @RequestBody VersionUpdateRequest, ...)
    @PostMapping("/{versionId}/copy")     public VersionResponse copy(...)
    @PostMapping("/{versionId}/publish")  public VersionResponse publish(...)
    @GetMapping("/{versionId}/audit")     public List<AuditResponse> audit(...)

    private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
        if (principal == null) { throw BusinessException.unauthorized("请先登录"); }
        return principal;
    }
}
```

**5 个接口 = "看列表 / 改名 / 复制 / 发布 / 看审计"** ✓ 对应 Python 的 versions 部分 ✓

**三个值得注意的点**：

**① 类注释与 `VersionController`/`SwapController` 的关系** ✓
> ⚠ 路径前缀是 `/api/schedule`（与 generate/status 同属一个 Python 路由），
> 但 Java 里拆成了两个 Controller：本类负责 versions，`ScheduleController` 负责 generate/status。
> **路径本身仍是契约**，拆分只是代码组织。

—— 和 `SwapController` 的注释说的是同一件事 ✓ **三个 Controller 共享前缀** ✓（`ScheduleController` / `VersionController` / `SwapController` ✓）
⚠ 其中 `SwapController` 与**本类**共用 `/api/schedule/versions` ✗（一个管 CRUD ✓ 一个管换课 ✓）

**② `PATCH` 而不是 `PUT`** ✓ —— Python 那边也是 PATCH ✓（部分更新 ✓ 只改传了的字段 ✓）
**用 PUT 会语义不符** ✗（PUT 表示"整体替换" ✓ 而这里 `name` 不传就不改 ✓）

**③ ⚠ 与 `SwapController` 的一处不一致** ✓

| | `SwapController` | `VersionController` |
|---|---|---|
| 空 principal 检查 | **内联在两行里**（`if (principal == null) throw ...` ✓）| **抽成了 `requirePrincipal(...)` 私有静态方法** ✓ |

**同一个包、同一件事、两种写法** ✗ —— 都不算错 ✓ 但读的人会以为有什么区别 ✓
**更好的做法是其中一个**（比如抽到一个共享的工具 ✓ 或统一内联 ✓）

> 💡 这是"**同一个项目里两种风格并存**"的第 4 个例子了 ✗
> 前面见过：字符串 key vs `record` key ✓（4c-1 / 4c-2 ✓）
> `LinkedHashMap` vs `HashMap` ✓ 全限定名 vs import ✓ `DAY_KEYS` 三份 ✓
> **共同的原因**：代码是**分阶段增量写的**（P1 平移 → P2 规则 → P3 队列 ✓）
> 每一阶段都在当时的上下文里做了合理选择 ✓ 但**没人回头统一** ✓
> 这不是"质量差" ✗ 而是**增量开发的必然产物** ✓ 值得知道，不必焦虑 ✓

---

## 4c-2.4 `VersionService` —— 本册主体（285 行）

### 🔴 先看一个"好例子"：类型安全的排序白名单

```java
private static final Map<String, SFunction<ScheduleVersion, ?>> SORTABLE = Map.of(
        "id", ScheduleVersion::getId,
        "version_no", ScheduleVersion::getVersionNo,
        "name", ScheduleVersion::getName,
        "status", ScheduleVersion::getStatus,
        "created_at", ScheduleVersion::getCreatedAt);
```

**这是一个"白名单"** ✓ 但它的类型值得注意：`Map<String, SFunction<ScheduleVersion, ?>>` ✓
—— 值是**方法引用** ✓ 不是列名字符串 ✓

**对比第 1 册的 `PageQuery.orderColumn(Set<String> allowed, ...)`** ✓

| | `PageQuery.orderColumn` | 这里的 `SORTABLE` |
|---|---|---|
| 白名单元素 | `Set<String>` —— **列名字符串** ✗ | `SFunction` —— **方法引用** ✓ |
| 返回 | `String`（还要传给 `orderBy` ✗）| 直接就是 `wrapper.orderBy` 要的类型 ✓ |
| 改字段名时 | 编译器**不知道** ✗ 字符串不会跟着改 ✗ | **编译期报错** ✓ |
| 是否可能注入 | 白名单挡得住 ✓ 但"拼字符串进 SQL"这个形态还在 ✗ | **形态上就不可能** ✓ |

```java
String column = query.orderColumn(SORTABLE.keySet(), "version_no");
boolean ascending = query.orderBy() == null ? false : query.ascending();
wrapper.orderBy(true, ascending, SORTABLE.get(column));
```

⚠ **注意 `SORTABLE.get(column)` 仍然是"字符串 → 方法引用"的一次查表** ✗
—— 所以白名单的**校验**（`orderColumn` ✓）与**使用**（`SORTABLE.get` ✗）是**两步** ✓
中间理论上可能不一致（比如 `SORTABLE` 改了而 `keySet()` 没跟上 ✓ —— 其实不会 ✓ 它们是同一份 ✓）

> 💡 **更好的形态**：让 `orderColumn` 直接返回 `SFunction` ✓
> 那样"校验通过的列"与"用到的列"**在类型上就是同一个东西** ✓
> 但 `PageQuery` 在 `orbit-common` ✓ 它**不能依赖 MyBatis-Plus** ✗（第 1 册讲过：common 要薄 ✓）
> **所以这个"两步"是被模块边界逼出来的** ✓ —— 不是疏忽 ✓
> 这也解释了为什么只有 `VersionService` 用了 `SFunction`：**它在 scheduling 里** ✓ 可以用 ✓
> **同一个项目两种白名单写法，原因是"所在的模块能不能依赖 MyBatis-Plus"** ✓ 这是一个**合理**的差异 ✓

### `list` —— 分页列表（19 行）

```java
public PageResponse<VersionResponse> list(long organizationId, PageQuery query) {
    LambdaQueryWrapper<ScheduleVersion> wrapper = Wrappers.<ScheduleVersion>lambdaQuery()
            .eq(ScheduleVersion::getOrganizationId, organizationId);
    if (query.hasKeyword()) {
        wrapper.like(ScheduleVersion::getName, query.keyword());
    }
    String column = query.orderColumn(SORTABLE.keySet(), "version_no");
    boolean ascending = query.orderBy() == null ? false : query.ascending();
    wrapper.orderBy(true, ascending, SORTABLE.get(column));

    Page<ScheduleVersion> page = versionMapper.selectPage(
            new Page<>(query.pageNumber(), query.pageSize()), wrapper);
    Map<Long, Long> counts = entryCounts(organizationId);
    return PageResponse.of(
            page.getRecords().stream()
                    .map(v -> VersionResponse.of(v, counts.getOrDefault(v.getId(), 0L)))
                    .toList(),
            page.getTotal());
}
```

**⚠ 第 78 行那个 `ascending` 的算法很值得看** ✓：

```java
boolean ascending = query.orderBy() == null ? false : query.ascending();
```

**它在表达一条业务规则** ✓ 类注释写明了：

> ⚠ 默认顺序是 `version_no DESC`（Python 的 `order_by(version_no.desc())`）——
> 最新的一版在最前面。这与 `PageQuery` 默认的 asc 不同，所以这里**只在用户没指定 order 时**
> 用 desc：显式传 `order=asc` 时按升序，**属于 Java 新增的能力**。

🔴 **这是一个"迁移期如何对待默认值"的范例** ✓：

| 情况 | 行为 | 来源 |
|---|---|---|
| 用户**没传** `order` | **desc** ✓（最新在前 ✓） | **Python 的行为** ✓ 契约 |
| 用户**传了** `order=asc` | **asc** ✓ | **Java 新增** ✓ |

**为什么不能直接用 `PageQuery.ascending()`** ✗ —— 因为它的默认是 **asc** ✗（第 1 册 ✓）
而 Python 这边的默认是 **desc** ✗ → **直接用会让默认顺序反过来** ✓
用户看到"版本列表最新的在最后" ✗ 而且**不会报错** ✓ **只有肉眼能发现** ✗

⚠ 而**显式传 `order=asc` 时又必须尊重用户** ✓ —— 因为 `PageQuery` 允许传 ✓ 传了就该生效 ✓
所以这个三元表达式**同时处理了两种来源的默认值** ✓ 它是对的 ✓ 但**一眼看不出为什么** ✓
**多亏有注释** ✓（如果没有，下一个人很可能会"简化"成 `query.ascending()` ✗ 然后默认顺序就反了 ✗）

> 💡 **可推广的教训**：**"默认值"也是契约的一部分** ✗
> Python 的 `order_by(version_no.desc())` 里那个 `desc()` 是**它接口行为的一部分** ✓
> 而 Java 的通用分页组件有自己的默认 ✓ 两者**撞上了** ✓
> 处理方式不是"改通用组件"（会影响 12 个接口 ✗）也不是"忽略"（默认顺序会反 ✗）
> 而是**在这个接口里显式表达"没传就用 Python 的默认"** ✓ —— 代价是一行难懂的三元 ✓ 加注释 ✓

**⚠ `entryCounts(organizationId)` 一次算全部版本的条目数** ✓：

```java
private Map<Long, Long> entryCounts(long organizationId) {
    List<Map<String, Object>> rows = entryMapper.selectMaps(
            Wrappers.<ScheduleVersionEntry>query()
                    .select("version_id", "COUNT(*) AS entry_count")
                    .eq("organization_id", organizationId)
                    .groupBy("version_id"));
    Map<Long, Long> counts = new HashMap<>();
    for (Map<String, Object> row : rows) {
        Object versionId = row.get("version_id");
        Object count = row.get("entry_count");
        if (versionId instanceof Number id && count instanceof Number n) {
            counts.put(id.longValue(), n.longValue());
        }
    }
    return counts;
}
```

🔴 **这是本类里最漂亮的一处** ✓ —— 它避免了一个明显的 N+1 ✓：

| 做法 | 查询次数（10 个版本） |
|---|---|
| 每个版本调一次 `countEntries` ✗ | **11 次**（1 次列表 + 10 次计数 ✓） |
| **一次 `GROUP BY version_id`** ✓ | **2 次** ✓ |

**类注释点了出来**：「一次 GROUP BY 拿到所有版本的条目数（Python 用 outerjoin + group_by）」✓
—— 也就是说 **Python 也是这么做的** ✓ 这里是**忠实翻译** ✓ 而不是"Java 顺手优化" ✓

⚠ **但它用的是原始列名字符串** ✗（`"version_id"` / `"COUNT(*) AS entry_count"` / `"organization_id"` ✓）
—— 同一个类里其他地方全用方法引用 ✓ 这里退回了字符串 ✗
**原因是 MyBatis-Plus 的 `selectMaps` + 聚合函数没有类型安全的写法** ✓（`select(SFunction...)` 支持列 ✓ 但 `COUNT(*) AS x` 不行 ✗）
**所以这是一处被框架逼出来的妥协** ✓ 与 `PageQuery.orderColumn` 那个是同族 ✓

⚠ **`instanceof Number` 的防御** ✓ —— JDBC 返回的可能是 `Long` / `BigInteger` / `BigDecimal` ✓
用 `Number` 接住再 `longValue()` ✓ **比强转 `(Long)` 稳** ✓
**`instanceof ... && ...` 的模式匹配**（Java 16+ ✓）✓ 现代写法 ✓

⚠ **`new HashMap<>()`** ✗ —— 这里用 `HashMap` 没问题 ✓（只是查表 ✓ 不涉及"报第一个"✓）

### `update` —— 改名（28 行）

```java
@Transactional
public VersionResponse update(long organizationId, SchedulePrincipal principal,
                              long versionId, VersionUpdateRequest request) {
    ScheduleVersion version = requireOwned(organizationId, versionId);
    if (!"draft".equals(version.getStatus())) {
        throw BusinessException.conflict("只有草稿可以修改");
    }
    if (!java.util.Objects.equals(version.getRevision(), request.expectedRevision())) {
        throw BusinessException.conflict("版本已被其他用户修改，请刷新后重试");
    }
    if (request.name() != null) {
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw BusinessException.badRequest("方案名称不能为空");
        }
        version.setName(name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name);
    }
    if (request.description() != null) {
        version.setDescription(request.description().strip());
    }
    version.setRevision(version.getRevision() + 1);
    version.setUpdatedAt(UtcTimes.now());
    versionMapper.updateById(version);
    ...
}
```

**逐点**：

| 代码 | 说明 |
|---|---|
| `requireOwned` | 404 兜底 ✓（文案 `课表版本不存在` ✓） |
| `status != draft` → 409 `只有草稿可以修改` | ✓ 与 Python 文案一致 ✓ |
| `revision` 不匹配 → 409 | 乐观锁 ✓ |
| **`.strip()`** | ⚠ **`strip()` 而不是 `trim()`** ✓ —— `strip()` 是 Java 11+ 的 ✓ 处理 Unicode 空白更完整 ✓ **更正确** ✓ |
| **空名 → 400** | ⚠ 这是**校验**：用户传 `"   "` 会被 strip 成 `""` ✓ 然后 400 ✓ **不是静默忽略** ✓ 对 ✓ |
| **超长截断到 120** | ⚠ **不是报错，是截断** ✓ 注释标明了 Python 是 `name[:120]` ✓ 所以是**忠实翻译** ✓（虽然"截断"比"报错"更容易让人困惑 ✓） |
| `description` **不判空** | ⚠ 与 `name` 不同 ✓ 空描述是允许的 ✓（`version.setDescription("")` ✓ 而 `VersionResponse` 又会把 `null` 变 `""` ✓ 两条路径都到 `""` ✓ 一致 ✓） |
| `revision + 1` | ⚠ 如果 `revision` 为 `null` → NPE ✗（列应非空 ✓ 且 `copy` 里设了 0 ✓ `publish` 也设 ✓） |
| `updateById(version)` | **整行更新** ✓ 不是"只改 name 列" ✓ —— 会把内存里读到的其他列一起写回 ✓ <br>⚠ 在 `@Transactional` + 刚才 `selectOne` 读到的前提下 ✓ 没有"用旧值覆盖新值"的风险 ✓（因为同一事务 ✓ 而且乐观锁挡住了并发的用户改动 ✓） |

**审计写的是"改后的名字"** ✓：
```java
auditMapper.insert(audit(organizationId, version.getId(), principal,
        "updated", Map.of("name", version.getName())));
```
⚠ **`Map.of("name", ...)` 若 `version.getName()` 是 `null` 会抛 NPE** ✗
—— `Map.of` 不允许 null 值 ✓（第 4e 册讲过同一个坑 ✓）
**这里安全吗** ✗：`name` 只在 `request.name() != null` 时才改 ✓ 如果原本就是 `null` 且用户没传 name ✓ 那 `version.getName()` 就是 `null` ✗ → **NPE** ✓
⚠ 但 `name` 列应该非空（建表时 `NOT NULL` ✓）✓ 所以实际不会 ✓
**标记为"依赖列非空"** ✓

### `copy` —— 复制为新草稿（37 行）

```java
@Transactional
public VersionResponse copy(long organizationId, SchedulePrincipal principal, long versionId) {
    ScheduleVersion source = requireOwned(organizationId, versionId);
    int nextNo = nextVersionNo(organizationId);
    LocalDateTime now = UtcTimes.now();

    ScheduleVersion copied = new ScheduleVersion();
    copied.setOrganizationId(organizationId);
    copied.setVersionNo(nextNo);
    copied.setName(source.getName() + " - 副本");            // Python: f"{source.name} - 副本"
    copied.setDescription(source.getDescription() == null ? "" : source.getDescription());
    copied.setStatus("draft");
    copied.setRevision(0);
    copied.setCreatedBy(principal.userId());
    copied.setCreatedAt(now);
    copied.setUpdatedAt(now);
    versionMapper.insert(copied);

    List<ScheduleVersionEntry> entries = entryMapper.selectList(...eq(versionId, source.getId()));
    for (ScheduleVersionEntry item : entries) {
        ScheduleVersionEntry copy = new ScheduleVersionEntry();
        copy.setOrganizationId(organizationId);
        copy.setVersionId(copied.getId());        // 👈 insert 之后才有 id
        copy.setClassId(item.getClassId());
        ... 其余 5 个字段逐个搬 ...
        entryMapper.insert(copy);
    }
    auditMapper.insert(audit(organizationId, copied.getId(), principal,
            "copied", Map.of("source_version_id", source.getId())));
    return VersionResponse.of(copied, entries.size());
}
```

**要点**：

| 点 | 说明 |
|---|---|
| **`" - 副本"` 后缀** | ⚠ 中文，带前后空格 ✓ 与 Python 的 f-string **逐字一致** ✓ 这是**用户可见的文案** ✓ 属于契约 ✓ |
| **`description` 兜底为 `""`** | ✓（与 `VersionResponse` 那处呼应 ✓ —— 源为 null 时不能把 null 写进新行 ✗ 列若非空会报错 ✓） |
| **`revision = 0`** | ✓ 新草稿从 0 开始 ✓（不继承源的 revision ✓ 因为它是一个**新对象** ✓） |
| ⚠ **`copied.getId()` 在 `insert` 之后才可用** | ✓ MyBatis-Plus 会把自增主键回填进实体 ✓ —— **这是它相对 JPA 的一个便利** ✓ 但**依赖"insert 立刻回填"这个行为** ✓ |
| 🔴 **逐条 `insert` 明细** | ⚠ **92 条 → 92 次 INSERT 往返** ✗ 160 班 × 30 节 ≈ 5000 条 → **5000 次往返** ✗ <br>**这是本类最明显的一处性能问题** ✓ 批量插入（`saveBatch` / `INSERT ... VALUES (...),(...)` ✓）能把它降到个位数 ✓ <br>⚠ 但我**没有实测**耗时 ✓ 只在 4 个班的规模上跑过 ✓ **标记为"规模问题，未实测"** ✓ |
| **审计的 details 只有一个字段** | `{"source_version_id": N}` ✓ 够用 ✓（用户能从"复制"这个动作推出其余 ✓） |

**🔴 `nextVersionNo` 的已知竞态** —— 类注释写得很坦诚 ✓：

```java
/**
 * 下一个版本号 = 当前最大值 + 1。
 *
 * <p>⚠ 这里有一个**已知的竞态**（Python 也有）：两个管理员同时复制会算出同一个号，
 * 后到的那个会撞 {@code uq_schedule_version_org_no} 唯一约束 → 500。
 * P1 保持行为等价，不额外加锁；**真要修应该用"唯一约束 + 重试"而不是先查后插**。
 */
private int nextVersionNo(long organizationId) { ... }
```

**这段注释有三层价值** ✓：
1. **承认问题存在** ✓（而不是假装没有 ✓）
2. **说明为什么现在不修** ✓ —— "P1 保持行为等价" ✓（修了会与 Python 行为不同 ✗ 而 P1 的判据是等价 ✓）
3. **给出正确的修法方向** ✓ —— "唯一约束 + 重试"而不是"先查后插 + 锁" ✓

🔴 **第 3 点尤其值得展开** ✓ —— 这是**并发 ID 生成的标准答案** ✓：

| 做法 | 问题 |
|---|---|
| 先查 max 再 +1 插入（现在 ✗） | 竞态窗口在"查"与"插"之间 ✓ 两个请求都能查到同一个 max ✗ |
| 加锁（`SELECT ... FOR UPDATE` 或分布式锁 ✗） | 能修 ✓ 但**要把整个组织串行化** ✗ 而且锁的粒度要对 ✓ |
| **依赖唯一约束 + 冲突时重试** ✓ | 数据库保证唯一 ✓ 冲突了就重查一次 ✓ **无锁、正确、代价只在冲突时付** ✓ |

> 💡 **"让数据库的约束来发现冲突，而不是靠查询来避免冲突"** ✓
> 这是本册最值得带走的一条工程原则 ✓
> 而且它和 P3 的 `job_token` 栅栏是同一个思想的不同侧面 ✓ ——
> **都是"不要试图在应用层避免冲突，而是让冲突发生时能被检测到并安全处理"** ✓

### `publish` —— 发布（43 行，做了四件事）

```java
@Transactional
public VersionResponse publish(long organizationId, SchedulePrincipal principal, long versionId) {
    if (!principal.isAdmin()) {
        throw BusinessException.forbidden("只有管理员可以发布课表");     // ① 权限
    }
    ScheduleVersion version = requireOwned(organizationId, versionId);
    if (!"draft".equals(version.getStatus())) {
        throw BusinessException.conflict("只有草稿可以发布");
    }
    List<ScheduleVersionEntry> entries = validator.validate(version);   // ② 校验
    LocalDateTime now = UtcTimes.now();

    ScheduleVersion archivePatch = new ScheduleVersion();               // ③ 归档上一版
    archivePatch.setStatus("archived");
    archivePatch.setUpdatedAt(now);
    versionMapper.update(archivePatch, Wrappers.<ScheduleVersion>lambdaUpdate()
            .eq(ScheduleVersion::getOrganizationId, organizationId)
            .eq(ScheduleVersion::getStatus, "published"));

    scheduleEntityMapper.delete(Wrappers.<ScheduleEntity>lambdaQuery()  // ④ 整体重写 schedules
            .eq(ScheduleEntity::getOrganizationId, organizationId));
    for (ScheduleVersionEntry item : entries) {
        ScheduleEntity row = new ScheduleEntity();
        ... 逐个字段搬 ...
        scheduleEntityMapper.insert(row);
    }

    version.setStatus("published");                                     // ⑤ 标记本版已发布
    version.setPublishedBy(principal.userId());
    version.setPublishedAt(now);
    version.setUpdatedAt(now);
    version.setRevision(version.getRevision() + 1);
    versionMapper.updateById(version);

    auditMapper.insert(audit(organizationId, version.getId(), principal,
            "published", Map.of("entry_count", entries.size())));
    return VersionResponse.of(version, entries.size());
}
```

**四件事的顺序不能变** ✓：

| # | 做什么 | 为什么在这个位置 |
|---|---|---|
| ① | **权限检查** | ⚠ **放在最前面** ✓ —— 非管理员**连"这个版本存不存在"都不该知道** ✓ 先查再拒会泄露信息 ✓ |
| ② | **校验明细**（`validator.validate` ✓） | ⚠ **在归档和重写之前** ✓ —— 校验失败要**什么都没改** ✓ 如果先归档再校验 ✗ 失败后上一版已经被归档了 ✗ 而当前课表还在 ✓ **状态就不一致了** ✓ |
| ③ | **归档上一版** | ⚠ 用**条件 UPDATE 一条搞定** ✓ 而不是"查出来再逐个改" ✓ |
| ④ | **整体重写 `schedules`** | 先 `delete` 再逐条 `insert` ✓ |
| ⑤ | 标记本版 + 审计 | 最后 ✓ |

#### 🔴 ① 权限检查的位置 —— 一个容易被忽视的细节

```java
if (!principal.isAdmin()) { throw BusinessException.forbidden("只有管理员可以发布课表"); }
ScheduleVersion version = requireOwned(organizationId, versionId);
```

**权限检查在"查版本"之前** ✓ —— 顺序是对的 ✓
如果反过来 ✗：非管理员传一个不存在的 id → 得到 **404** ✓ 传一个存在的 → 得到 **403** ✓
→ **通过二分就能探测出"哪些版本 id 存在"** ✗
**先检查权限** → 无论 id 存不存在都是 403 ✓ **不泄露任何信息** ✓

⚠ **这是"权限先于存在性"的一般原则** ✓ 在别的地方也成立 ✓
（对照：`SwapService` 的顺序是"查版本（404）→ 查状态（409）" ✓ 因为那里**没有角色要求** ✓ 所有登录用户都可以拖动 ✓ 所以不存在泄露问题 ✓ —— **两个类顺序不同，是因为权限模型不同** ✓）

#### ⚠ ③ 的那个条件 UPDATE

```java
versionMapper.update(archivePatch, Wrappers.<ScheduleVersion>lambdaUpdate()
        .eq(ScheduleVersion::getOrganizationId, organizationId)
        .eq(ScheduleVersion::getStatus, "published"));
```

**一条 UPDATE 把"本组织所有已发布的"改成 `archived`** ✓
⚠ 语义是"**最多应该只有一条**" ✓ 但如果数据异常（有两条 published ✗）✓ 它会把两条都归档 ✓
—— **这比"报错"更宽容** ✓ 也符合"发布新版本时旧的都该归档"的意图 ✓

⚠ **`archivePatch` 是一个只设了两个字段的空实体** ✓ 只有 `status` 和 `updatedAt` ✓
—— MyBatis-Plus 的 `update(entity, wrapper)` **只更新实体里非 null 的字段** ✓（默认策略 ✓）
**所以 `name` / `revision` 等不会被覆盖** ✓ ✓ 这是一个必须知道的 MyBatis-Plus 行为 ✓
（如果它更新全部字段 ✗ 那 `name` 会被置 null ✗ 数据就毁了 ✓）

#### 🔴 ④ 整体重写的代价

```java
scheduleEntityMapper.delete(...);        // 删掉本组织当前课表
for (ScheduleVersionEntry item : entries) {
    scheduleEntityMapper.insert(row);    // 再逐条写回来
}
```

**"先清空再写"** ✓ —— 类注释说明了理由：

> `schedules` 是"最新发布的那一版"的拷贝，所以是**先清空再写**。

⚠ **与 `copy` 一样的性能问题** ✗：92 条 → 92 次 INSERT ✓ 160 班时约 5000 次 ✗
**而且这里更贵** ✗ —— 因为发布是**用户等待的同步操作** ✓（`copy` 也是 ✓）✓
**批量插入能把"点发布后等几秒"变成"瞬间完成"** ✓

⚠ **另一个考虑**：如果 `delete` 成功而 `insert` 中途失败 ✗
`@Transactional` 会整体回滚 ✓ → `schedules` 恢复原样 ✓ **当前正式课表不受影响** ✓
**所以"先删后写"在事务保护下是安全的** ✓ —— 如果不在事务里 ✗ 那中间态就是"课表空了" ✗ 灾难 ✓

#### 🔴 `publish` **没有乐观锁** —— 一处不对称

对比：

| 方法 | 检查 status | 检查 `expectedRevision` |
|---|---|---|
| `update` | ✓ | **✓** |
| `swap`（4c-1） | ✓ | **✓**（`request.expectedRevision()` ✓） |
| **`publish`** | ✓ | **✗ 没有** ✗ |

**后果**：两个管理员**同时**点发布同一条草稿 ✓
- 两个请求都读到 `status = draft` ✓（各自的读 ✓）
- 都通过检查 ✓
- 都执行归档 + 重写 + 标记 ✓
- **第二个的 `requireOwned` 读到的可能是 draft（如果它在第一个提交之前读的 ✓）** ✓
→ 结果：`schedules` 被写两遍（内容相同 ✓ 因为同一版 ✓）✓ 审计多一条 ✓ 归档和标记重复执行 ✓

**有没有实际危害** ✗：
- `schedules` 内容相同 ✓ 无害 ✓
- `version.revision` 会 +2 ✗（跳号 ✓ 但 revision 只是乐观锁令牌 ✓ 跳号无害 ✓）
- `published_at` / `published_by` 被第二个请求覆盖 ✓（同一秒、可能不同人 ✓ 但都是管理员 ✓）
- 审计多一条 `published` ✓ **反而是好事**（记录了确实有两次发布动作 ✓）

> 💡 **所以这是一个"没有乐观锁但危害有限"的不对称** ✓
> 但它**不对称**这件事本身值得一提 ✓ —— 读代码的人会问"为什么 `update` 要 revision 而 `publish` 不要" ✗
> **可能的原因**：`publish` 在 Python 那边也是无 revision 的 ✓（属于"忠实翻译"✓）
> 或者：publish 是"终态操作"✓ 重复执行的语义是幂等的 ✓ 所以不需要保护 ✓
> ⚠ **我无法从代码确定是哪个原因** ✓ **标记为"未说明的不对称"** ✓

### `audit` / `requireOwned` / `countEntries` / `audit(工厂)` —— 四个辅助方法

```java
/** 某版本的审计记录（按时间倒序）。 */
public List<AuditResponse> audit(long organizationId, long versionId) {
    requireOwned(organizationId, versionId);                      // 👈 先确认归属
    return auditMapper.selectList(Wrappers.<ScheduleAuditLog>lambdaQuery()
                    .eq(ScheduleAuditLog::getVersionId, versionId)
                    .eq(ScheduleAuditLog::getOrganizationId, organizationId)
                    .orderByDesc(ScheduleAuditLog::getCreatedAt))
            .stream().map(AuditResponse::from).toList();
}
```

**`requireOwned` 先跑一遍，只为"确认这个版本属于本组织"** ✓
—— 然后查询里**又带了一次 `organizationId` 条件** ✓ **两层都有** ✓ 冗余但安全 ✓
⚠ 值得注意的是：**没有分页** ✗ —— 一个版本的审计记录会随时间增长 ✓（每次改名/换课一条 ✓）
**当前规模下没问题** ✓ 长期看应该分页 ✓（对照 5b 册的任务列表就是服务端分页 ✓ —— 同样是"会长大的表" ✓）

```java
public ScheduleVersion requireOwned(long organizationId, long versionId) {
    ScheduleVersion version = versionMapper.selectOne(...);
    if (version == null) { throw BusinessException.notFound("课表版本不存在"); }
    return version;
}
```
**公开方法**（`public` ✓）—— 被 `SwapService` 之外的地方也可能用 ✓（`ScheduleQueryService`? ✓ hmm 未核实 ✗）
**它是"版本归属校验"的唯一定义处** ✓ 好 ✓

```java
private long countEntries(long versionId) {
    return entryMapper.selectCount(Wrappers.<ScheduleVersionEntry>lambdaQuery()
            .eq(ScheduleVersionEntry::getVersionId, versionId));
}
```
⚠ **单版本计数** ✓ 只在 `update` 的返回值里用了一次 ✓
**与 `entryCounts`（批量）并存** ✗ —— 两个做同一件事的方法 ✓ 一个单条一个批量 ✓
**合理**（`update` 只关心一个版本 ✓）✓ 但名字很像 ✓ 容易看错 ✓

```java
private static ScheduleAuditLog audit(long organizationId, long versionId,
                                      SchedulePrincipal principal, String action,
                                      Map<String, Object> details) { ... }
```
**一个私有静态工厂** ✓ —— 把"对象构造 + 8 行 setter"从四个调用点收成一处 ✓
⚠ 它是 `VersionService` 私有的 ✗ 而 `SwapService` 里**有几乎一样的 12 行代码** ✓（4c-1 册 ✓）
**重复了** ✗ —— 两个 Service 各写了一份审计对象构造 ✓
**更合适的位置是 `audit/` 包里的一个工厂** ✓ 记在这里 ✓

---

## 4c-2.5 审计（`audit/` 三个类）

### `ScheduleAuditLog` —— 103 行，含第二次出现的 JSON 列坑

```java
@TableName(value = "schedule_audit_logs", autoResultMap = true)      // 👈
public class ScheduleAuditLog {
    private Long id, organizationId, versionId, userId;
    private String userName, action;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> details;                             // MySQL json 列

    private LocalDateTime createdAt;
}
```

**类注释里的 action 取值清单** ✓：
> `generated` / `updated` / `copied` / `published` / `swapped` / `cross_swapped` / `moved`

⚠ **这是这一列的唯一取值文档** ✗ —— 没有枚举 ✗ 没有约束 ✗
所以**拼错一个 action 不会有任何报错** ✓ 只是那个值永远查不出来 ✓
（对照 `ScheduleJobEventEntity` 里 P3 用了**常量** ✓ —— 两种风格并存 ✓）

#### 🔴 `autoResultMap = true` —— 同一个坑，第二次被记下来

```java
/**
 * <p>⚠ {@code details} 是 MySQL {@code json} 列 → 需要 {@link JacksonTypeHandler}，
 * 而且**必须配 {@code autoResultMap = true}**：否则写入正常、**查询出来是 null**
 * （typeHandler 只在结果映射启用时生效）。这个坑在 grades 的 {@code periods_per_day} 上踩过一次。
 */
```

**这是同一个坑在本项目里的第 3 次出现** ✓：

| 位置 | 列 |
|---|---|
| `academic` 的 `Grade` | `periods_per_day` |
| `scheduling` 的 `GradeRef`（4b-2 册 ✓） | `periods_per_day` |
| **这里** | `details` |

⚠ **三次都是"写进去正常、读出来是 null"** ✓ —— 静默 ✓ 不报错 ✓ 而且**只在读那条记录时才发现** ✓

**但注释的写法在进化** ✓：
- `GradeRef`：只说"这个坑在 academic 的 Grade 上踩过一次，这里直接带上" ✓（记了"踩过" ✓ 没说**为什么** ✓）
- **这里**：说出了**机制** —— "typeHandler 只在结果映射启用时生效" ✓✓

> 💡 **抱怨 vs 解释** ✗：第一处只记了"有坑" ✓ 第二处记了"为什么有坑" ✓
> **后者对读者更有用** ✓ —— 因为知道机制之后 ✓ 遇到**别的** json 列会主动想起 ✓
> 只记"有坑"的话 ✓ 下次遇到新列（比如 P3 的 `ScheduleJobEntity` ✓）还是可能忘 ✓

### `AuditResponse` 与 `ScheduleAuditLogMapper`

- `AuditResponse` —— 16 行 ✓ 见 §4c-2.2 ✓（`details` null → `Map.of()` ✓）
- `ScheduleAuditLogMapper` —— **9 行空 `BaseMapper`** ✓

⚠ **又一个空 Mapper** ✓ —— 本册 3 个 Mapper（`ScheduleVersionMapper` / `ScheduleVersionEntryMapper` / `ScheduleAuditLogMapper` ✓）**全是空的** ✗
**因为全是只读 + MyBatis-Plus 的 CRUD** ✓ 没有一处需要自定义原子 SQL ✓
（对照 5a 册的 `ScheduleJobMapper`：塞满 `FOR UPDATE SKIP LOCKED` 等自定义 SQL ✓）
—— **"Mapper 里有没有自定义 SQL"继续是判断读写场景的好指标** ✓（4b-2 / 4e 册都提过 ✓ 这是第 3 次验证 ✓）

---

## 本册小结

**把这一册串成一句话**：

> 排课的产出是**版本**（草稿 → 发布 → 归档 ✓）✓ 发布时把明细**整体重写**到 `schedules`
> （那是"当前正式课表"的投影 ✓ 让查课表不用每次找"哪一版是当前的" ✓）✓
> 每一版上记着**三个溯源指纹**（规则 / 快照 / 模型 ✓）✓ 让任何一版都能被**精确复现** ✓
> 而每次操作都进**审计** ✓ 回答"这版是怎么变成现在这样的" ✓

**最该带走的三件事**：

1. **`revision` 与 `job_token` 是两种并发控制，别混**（§4c-2.1）✓
   判断法则：**冲突时"有没有人能做出更好的决定"** ✓ 有 → 乐观锁让人重试 ✓ 没有 → 栅栏让系统收尾 ✓
2. **"先查后插"生成递增号是错的，"唯一约束 + 重试"才对**（§4c-2.4 `nextVersionNo` ✓）
   这个类的注释**自己给出了正确答案** ✓ —— 让数据库的约束去发现冲突，而不是靠查询去避免冲突 ✓
3. **`autoResultMap = true` 是 JSON 列的必需项**（§4c-2.5）✓
   同一个坑在项目里出现 **3 次** ✓ 而注释的写法从"记坑"进化到了"讲机制" ✓

### 本册发现的问题（5 处，都没动代码）

| # | 问题 | 性质 |
|---|---|---|
| **1** | 🔴 **`copy` 与 `publish` 逐条 `insert` 明细** | 92 条 → 92 次往返 ✗ 160 班 ≈ 5000 条 → **5000 次往返** ✗ 而这两条路径都是**用户在同步等待**的 ✓ 批量插入能显著改善 ✓<br>⚠ **未实测耗时**（只在 4 个班的规模跑过）✓ 标记为"规模问题" ✓ |
| **2** | ⚠ **`publish` 没有乐观锁**（`update` / `swap` 都有） | 并发双击发布会重复执行归档 + 重写 + 审计 ✓ 危害有限（内容相同 ✓）但**不对称** ✓ 且代码里**没有说明为什么** ✗ |
| **3** | **审计对象构造在两个 Service 里各写了一份**（`VersionService.audit(...)` 与 `SwapService` 里的 12 行 ✓） | 重复 ✓ 更合适的位置是 `audit/` 包的一个工厂 ✓ |
| **4** | **`action` 取值没有枚举/常量** ✗（只有类注释里列的 7 个字符串 ✓） | 拼错不报错 ✓ 只是查不出来 ✓ 而 P3 的 `ScheduleJobEventEntity` **用了常量** ✗ → 两种风格并存 ✓ |
| **5** | `countEntries`（单版本）与 `entryCounts`（批量）两个方法名太像 | 一个查一次 ✓ 一个一次查全部 ✓ 并存是合理的 ✓ 但**名字容易看错** ✓ |

**另外记下 3 处"两套风格并存"**（本册又添了几例 ✓）：
- `requirePrincipal` 抽成方法（本册 ✓）vs 内联（`SwapController` ✓）
- 排序白名单：`SFunction` 类型安全（本册 ✓）vs 列名字符串（`PageQuery` ✓）
  —— **原因是模块边界**：`orbit-common` 不能依赖 MyBatis-Plus ✓ 所以那个差异是**合理的** ✓
- 空 Mapper（本册 3 个 ✓）vs 塞满自定义 SQL（`ScheduleJobMapper` ✓）
  —— **这个差异是有意义的指示器** ✓ 不是风格问题 ✓
