# Orbit 代码导读 4b —— `orbit-scheduling`：课表版本 / 换课 / 查询 / 校验 / 审计 / 运维

> **与《架构说明》的分工**：
> [架构说明.md](架构说明.md) 讲「为什么这么分、各部分怎么协作」；
> **本篇讲「这个类/这个函数到底在干嘛、谁调它、不这么写会怎样」**。
>
> **格式基准**：[代码导读.md](代码导读.md)（第一篇 · `orbit-common`）。
> 本篇比第一篇**更细**：每个方法除了表格，还有按代码顺序的逐段说明。
>
> **本篇覆盖 6 个包、24 个类**（`orbit-scheduling` 的第 4b 期）：

| 包 | 类 | 负责 |
|---|---|---|
| `version` | `VersionService` / `VersionController` / `ScheduleVersion` / `ScheduleVersionMapper` / `ScheduleVersionEntry` / `ScheduleVersionEntryMapper` / `VersionResponse` / `VersionUpdateRequest` | 草稿的列表 / 改名 / 复制 / 发布 |
| `swap` | `SwapService` / `SwapController` / `SwapRequest` | 拖动换课（**全项目最"绕"的一段**） |
| `query` | `ScheduleQueryService` / `ScheduleQueryController` / `ExportService` / `ScheduleResponse` | 按班 / 教师 / 年级看课表（**刻意不分页**）+ 导出 xlsx |
| `validate` | `VersionValidator` | 独立的课表校验器 |
| `audit` | `ScheduleAuditLog` / `ScheduleAuditLogMapper` / `AuditResponse` | 审计流水 |
| `system` | `SystemService` / `SystemController` / `SystemStatsMapper` | `/api/system/*` 运维接口 |

---

## 0. 本模块在系统里的位置

`orbit-scheduling`（端口 **8083**）是"小学自动排课系统"的排课与课表中台。
它**不拥有**任何教务主数据 —— `users` / `classes` / `grades` / `subjects` / `teachers` /
`grade_fixed_slots` 这些表都是 `orbit-academic` 的属主，scheduling **只读不写**
（[架构说明.md](架构说明.md) §3.3 的"已知耦合"）。

本模块这 6 个包，正好是**"排完之后"**的那一半：

```
        求解器（orbit-solver-core / worker）
                 │  写出「一版课表的明细」
                 ▼
   schedule_versions ──(复制/发布)──► schedules（当前已发布课表的投影）
        │  ▲                                  ▲
        │  │ version/  列表·改名·复制·发布      │
        │  │                                  │
        │  └── schedule_version_entries ◄── swap/  拖动换课（负值腾挪）
        │                    │
        │                    └── validate/VersionValidator  发布前 + 每次换课后
        │
        ├── query/   按班/教师/年级看（读 schedules 或 entries） + 导出 xlsx
        ├── audit/   谁在什么时候改了什么（每个版本一条时间线）
        └── system/  /api/system/* 存储概览·清空课表·整理空间
```

**三条贯穿本模块的主线**，读的时候盯住它们：

1. **两张"课表"表的区别。**
   `schedule_version_entries` 是**每版**课表的明细（历史，一版一份，不可变）；
   `schedules` 只是"**最新发布的那一版**"的拷贝，供前端默认查看。
   发布时**先清空 `schedules` 再整体重写**。见 §1.4。
2. **拖动只改草稿，不改 `schedules`。** 换课改的是 `schedule_version_entries`，
   所以**已发布课表在"重新发布"之前不会变**。这不是 bug，是设计（见 §2.5 ⚠）。
3. **校验器不信任任何人。** `VersionValidator` 在**发布前**和**每次换课后**都整体重跑 ——
   不信任求解器、也不信任"这次换课本身看起来没问题"。见 §4。

### 阅读顺序建议

| 顺序 | 读什么 | 为什么 |
|---|---|---|
| 1 | **§2 `swap/`** | 全项目最绕的一段。**先读它**，因为 `validate/` 是它的下一步 |
| 2 | **§4 `validate/VersionValidator`** | 只有两条规则，但是理解"什么算合法课表"的唯一地方 |
| 3 | **§1 `version/`** | 版本生命周期和发布（发布时会调 `validate`） |
| 4 | **§3 `query/`** | 读得最多、逻辑最简单的一段 |
| 5 | **§5 `audit/`** | 16 行 + 100 行，10 分钟看完 |
| 6 | **§6 `system/`** | 运维接口，与业务正交 |
| 7 | **§7 ⚠ 陷阱清单** | 一次性把坑过一遍 |
| 8 | **§8 附录** | 路由表 / 表约束 / 契约测试对照 |

> 如果你只有 20 分钟：读 §2.3（负值腾挪的三步）+ §4.2（两条校验规则）+ §1.4（发布）。
> 这三段解释了本模块 80% 的设计。

---

## 1. `version/` —— 课表版本（8 个类）

**定位**：一版课表的"元数据 + 生命周期"。
明细（哪节课在哪个时段）在 `schedule_version_entries`；本包管的是**这一版本身**：
它叫什么名字、是草稿还是已发布、被谁改过几次（`revision`）。

```
VersionController   /api/schedule/versions/*（5 个接口）
VersionService      业务逻辑（列表 / 改名 / 复制 / 发布 / 审计）
ScheduleVersion          @TableName("schedule_versions")
ScheduleVersionMapper    空的 BaseMapper
ScheduleVersionEntry     @TableName("schedule_version_entries")
ScheduleVersionEntryMapper
VersionResponse          ← HTTP 出参（对应 Python ScheduleVersionOut）
VersionUpdateRequest     ← HTTP 入参（对应 Python ScheduleVersionUpdate）
```

**生命周期**：

```
        copy / 求解器产出
              │
              ▼
          ┌────────┐    publish     ┌───────────┐  下一版 publish  ┌──────────┐
          │ draft  │ ─────────────► │ published │ ───────────────► │ archived │
          └────────┘                └───────────┘                  └──────────┘
              ▲                                                          │
              └─────────────────── copy（复制为新草稿）──────────────────┘
```

**只有 `draft` 可以改**（改名 / 拖动）。已发布的一版是**冻结**的 —— 想改就得先 `copy`
（Python 的错误文案就是这么写的：`已发布课表不可拖动，请先复制为新草稿`）。

---

### 1.1 `ScheduleVersion` —— `schedule_versions` 实体（172 行）

[ScheduleVersion.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/version/ScheduleVersion.java)

| 字段 | 说明 |
|---|---|
| `id` | `@TableId(type = IdType.AUTO)` —— 自增，插入后 MyBatis-Plus 会把生成的 id 塞回对象（`copy()` 依赖这一点） |
| `organizationId` | 租户。**每个查询都要带它**，这是租户隔离的地基 |
| `versionNo` | 版本号，`uq_schedule_version_org_no (organization_id, version_no)` 唯一 |
| `name` / `description` | 展示用。`name` 上限 120（Python 的 `name[:120]`） |
| `status` | `draft` / `published` / `archived` |
| `revision` | **乐观锁**计数器。每次改名 / 拖动 / 发布都 +1 |
| `createdBy` / `publishedBy` / `createdAt` / `updatedAt` / `publishedAt` | 溯源 |
| `ruleSetHash` | **P2 新增列**。这一版用的规则集哈希 |
| `snapshotFingerprint` | **P3 新增列**。用的哪份输入快照 |
| `modelFingerprint` | **P3 新增列**。编译出的 CP-SAT 模型指纹 |

**为什么要有三个指纹**（类注释里写得很清楚，值得抄一遍）：
> 「评分必须与 `rule_set_hash` 绑定 —— 同一张课表在不同规则集下分数不同，
> **不记 hash 的分数没有意义**」。
> 三个指纹合起来，一个版本才能被**精确复现**：同一份输入快照 + 同一套规则 + 同一个模型。

> ⚠ `ruleSetHash` / `snapshotFingerprint` / `modelFingerprint` **只读不写**（本包的代码一行都不碰它们）。
> 它们由 `SchedulingState`/`JobResultWriter`/`ScheduleRunService` 那边写入。
> 所以 P1 之前生成的版本这三个字段是 `null` —— 这是**有意的**，不是漏填。

**`revision` 与 `job_token` 的区别**（类注释特意点了一句「别混」）：

| | `schedule_versions.revision` | `scheduling_state.job_token` |
|---|---|---|
| 类型 | **乐观锁** | **悲观栅栏**（fencing token） |
| 解决什么 | 两个管理员同时改名 / 拖动 | 一个"被判超时"的旧排课任务活过来继续写 |
| 怎么用 | 客户端带回 `expected_revision`，不等就 409 | 所有状态 UPDATE 都带 `WHERE job_token = ?` |

---

### 1.2 `ScheduleVersionMapper` / `ScheduleVersionEntryMapper`（各 9 行）

两个都是**空的** `BaseMapper`：

```java
@Mapper
public interface ScheduleVersionMapper extends BaseMapper<ScheduleVersion> { }
```

- **没有一行自定义 SQL** —— 本包所有查询都用 MyBatis-Plus 的 `Wrappers.lambdaQuery()` 拼出来。
- 放在 `version/` 而不放在 `version/mapper/`：见 [架构说明.md](架构说明.md) §6⑦
  「Mapper 就放在领域包里」，所以 `@MapperScan` 用 `annotationClass = Mapper.class` 而不是包通配。

> ⚠ 唯一的例外是 `SystemStatsMapper`（§6.3），它**必须**手写 SQL（跨表 UNION + DDL），见那一节。

---

### 1.3 `ScheduleVersionEntry` —— 一节课（100 行）

[ScheduleVersionEntry.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/version/ScheduleVersionEntry.java)

7 个业务字段：`organizationId` / `versionId` / `classId` / `subjectId` / `teacherId` /
`dayOfWeek` / `periodIndex`。

**这张表上挂着两条索引，一条是约束、一条只是索引 —— 这个区别是本模块最重要的前提**：

| 索引 | 类型 | 含义 |
|---|---|---|
| `uq_version_class_slot (version_id, class_id, day_of_week, period_index)` | **UNIQUE** | 一个班的同一时段**只能有一节课**（数据库强制） |
| `ix_version_teacher_slot (version_id, teacher_id, day_of_week, period_index)` | 普通 KEY | ⚠ **数据库不禁止教师撞课** —— 靠 `VersionValidator` 在换课 / 发布时校验 |

（DDL 见 [V1__baseline_legacy.sql](../backend-java/orbit-scheduling/src/main/resources/db/migration/scheduling/V1__baseline_legacy.sql#L116-L121)）

**`day_of_week` 允许出现负数** —— 类注释明确写了这一点：

> 「换课时的"腾挪"手法（见 `SwapService`）依赖这个唯一约束：先把要移动的记录改成负值
> （`day_of_week = -1, period_index = -id`）暂时"移出"唯一键的冲突范围，再放到目标位置。
> 所以 `day_of_week` 允许出现负数 —— **只在同一事务的中间态**。」

⚠ 这句话的措辞很重要：「只在同一事务的中间态」—— **事务没提交时，别的连接看不到负数**；
但如果事务被回滚就不会留下痕迹，见 §2.6。

---

### 1.4 `VersionService` —— 本包的核心（285 行）

[VersionService.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/version/VersionService.java)

#### 构造器与依赖（L52-62）

| 依赖 | 干什么 |
|---|---|
| `versionMapper` | `schedule_versions` 的 CRUD |
| `entryMapper` | `schedule_version_entries`（复制、计数、列表的 GROUP BY） |
| `scheduleEntityMapper` | ⚠ **`schedules`（当前已发布课表）** —— 只有 `publish()` 用到，因为"发布"就是写它 |
| `auditMapper` | 写 `schedule_audit_logs` |
| `validator` | `VersionValidator`，`publish()` 里调用 |

**注意 `scheduleEntityMapper` 的归属**：`ScheduleEntity` 在 `schedule/` 包，但**写它的人是 `version/` 的发布流程**。
读代码时如果只翻 `schedule/` 包会觉得"谁把 `schedules` 写进去的？"——答案在这里。

#### 两个常量

```java
private static final int MAX_NAME_LENGTH = 120;
private static final Map<String, SFunction<ScheduleVersion, ?>> SORTABLE = Map.of(
        "id", ScheduleVersion::getId, "version_no", ScheduleVersion::getVersionNo,
        "name", ScheduleVersion::getName, "status", ScheduleVersion::getStatus,
        "created_at", ScheduleVersion::getCreatedAt);
```

- `MAX_NAME_LENGTH = 120` —— 对应 Python 的 `version.name = name[:120]`。
- `SORTABLE` —— **排序白名单**，键是**客户端传来的 `order_by` 值**，值是**类型安全的列引用**
  （`SFunction` = 方法引用，不是字符串）。这是 `PageQuery.orderColumn(...)` 要求的形状：
  **绝不允许把用户输入拼进 `ORDER BY`**（见 [代码导读.md](代码导读.md) §1.2）。

#### `list(long organizationId, PageQuery query)` → `PageResponse<VersionResponse>`（L71-89）

| 它做的事 | 分页列出本组织的版本，每行带 `entry_count` |
|---|---|
| **谁调它** | `VersionController.list`（`GET /api/schedule/versions`） |

逐段：

1. **L72-73 建 wrapper 并立刻加租户条件。**
   ```java
   LambdaQueryWrapper<ScheduleVersion> wrapper = Wrappers.<ScheduleVersion>lambdaQuery()
           .eq(ScheduleVersion::getOrganizationId, organizationId);
   ```
   ⚠ **租户条件必须在 wrapper 上，不能"查出来再过滤"** —— 后者在分页时是错的
   （先 LIMIT 20 再过滤可能只剩 3 条）。
2. **L74-76 关键字。** `if (query.hasKeyword()) wrapper.like(name, query.keyword())`。
   注意传的是 `query.keyword()`（已 strip）而不是 `query.likePattern()` —— `like()` 自己会拼 `%...%`。
3. **L77 排序白名单校验。** `orderColumn(SORTABLE.keySet(), "version_no")`
   —— 不在白名单里抛 400；没传时用 `version_no`。
4. **L78 —— 这一行是本节最需要看第二遍的地方**：
   ```java
   boolean ascending = query.orderBy() == null ? false : query.ascending();
   ```
   ⚠ **默认是 `version_no DESC`**，对应 Python 的 `order_by(ScheduleVersion.version_no.desc())`
   —— 最新的一版在最前面。而 `PageQuery` 的默认是 **asc**。
   所以这里**只在客户端没指定 `order` 时**倒序（`orderBy() == null`）；
   显式传 `order=asc` 时按升序 —— 这是 Java 侧新增的能力（Python 不支持排序参数）。
   **如果照抄 `query.ascending()` 就会让版本列表顺序整个反过来**，而且不报错。
5. **L79 `wrapper.orderBy(true, ascending, SORTABLE.get(column))`。**
   ⚠ 第二个参数是 **`isAsc`**（MyBatis-Plus 的命名），不是 `isDesc` ——
   见 [代码导读.md](代码导读.md) §1.2 坑 3，实测反过一次。
6. **L81-82 真正分页。** `versionMapper.selectPage(new Page<>(query.pageNumber(), query.pageSize()), wrapper)`。
   ⚠ 这依赖 `PaginationInnerInterceptor` 被注册（在 `config/SchedulingInfraConfig` 里）——
   不注册时 `selectPage` **静默退化成全量查询**（踩坑总结 H6）。
7. **L83 一次 GROUP BY 拿所有版本的条目数**（`entryCounts`，见下），而不是每行查一次。
8. **L84-88 组装。** 用 `counts.getOrDefault(v.getId(), 0L)` —— 没有明细的版本显示 `0`，不是 null。

> ⚠ **契约现状**：这个接口在契约工具里是被特殊对待的 ——
> [compare.py](../tools/contract-diff/compare.py#L732-L735) 有一条
> `"schedule 版本列表默认页"` 用例，`python_baseline="first_page"`：
> Python 忽略分页参数返回全量，Java 返回默认页（20 条）。
> 这是 §9.2「列表响应改 `{items,total}`」那条**有意偏离**在本接口的体现。

#### `update(long, SchedulePrincipal, long versionId, VersionUpdateRequest)` → `VersionResponse`（L93-119）

`@Transactional`。改一个版本的 `name` / `description`。**四道关卡，顺序不能换**：

| 关卡 | 代码 | 失败 |
|---|---|---|
| ① 版本属于本组织 | `requireOwned(organizationId, versionId)` | 404 `课表版本不存在` |
| ② 必须是草稿 | `!"draft".equals(version.getStatus())` | 409 `只有草稿可以修改` |
| ③ 乐观锁 | `!Objects.equals(version.getRevision(), request.expectedRevision())` | 409 `版本已被其他用户修改，请刷新后重试` |
| ④ 名称非空 | `name.isEmpty()`（strip 之后） | 400 `方案名称不能为空` |

逐段细节：

- **L99 用 `Objects.equals` 而不是 `!=`**：两边都是 `Integer` 包装类型，
  `!=` 比的是**引用**，`revision > 127` 时必然不等 ✗ 这是个不报错的经典坑。
- **L102-108 名称**：
  ```java
  String name = request.name().strip();
  if (name.isEmpty()) throw badRequest("方案名称不能为空");
  version.setName(name.length() > 120 ? name.substring(0, 120) : name);
  ```
  ⚠ **`request.name() != null` 才处理** —— `name` 是可选的，不传就是"不改名"，
  与"传了空串"（→ 400）是两件事。`strip()` 在**判空之前**，所以 `"   "` 会被判空。
  超长**静默截断**到 120，不报错（Python 是 `name[:120]`，也不报错）。
- **L109-111 描述**：只 `strip()`，**没有长度校验、没有 NOT NULL 校验**
  —— 传空串就是清空描述。与 `name` 的处理**刻意不对称**（Python 也是）。
- **L112-114 版本号自增 + 时间戳。**
  ```java
  version.setRevision(version.getRevision() + 1);
  version.setUpdatedAt(UtcTimes.now());
  versionMapper.updateById(version);
  ```
  ⚠ `UtcTimes.now()` 而不是 `LocalDateTime.now()` —— 库里存的是**截断到秒的 UTC**，
  见 [代码导读.md](代码导读.md) §1.1。
- **L116-117 审计。** `action="updated"`，`details={"name": 改后的名字}`。
  ⚠ **只记 `name`，不记 `description`** —— 这一条是照抄 Python 的，
  所以"只改了描述"的操作在审计里看到的 `details.name` 是**当前名字**，
  分辨不出到底改了什么。属于已知的信息缺失，不是 bug。
- **L118 返回。** `countEntries(version.getId())` 再查一次库（不是复用列表的 counts）。

#### `copy(long, SchedulePrincipal, long versionId)` → `VersionResponse`（L123-158）

`@Transactional`。**复制为新草稿** —— Python 说 `已发布课表不可拖动，请先复制为新草稿`，
指的就是这个接口。

逐段：

1. **L124** `requireOwned` —— 源版本必须属于本组织。
2. **L125** `int nextNo = nextVersionNo(organizationId)` —— 见 §1.4 `nextVersionNo`，
   ⚠ **这里有一个已知竞态**。
3. **L126** `LocalDateTime now = UtcTimes.now()` —— 只取一次，让新行的三个时间戳**完全一致**。
4. **L128-139 建新版本行**：
   ```java
   copied.setVersionNo(nextNo);
   copied.setName(source.getName() + " - 副本");   // Python: f"{source.name} - 副本"
   copied.setDescription(source.getDescription() == null ? "" : source.getDescription());
   copied.setStatus("draft");
   copied.setRevision(0);                          // ← 新草稿从 0 开始
   copied.setCreatedBy(principal.userId());
   copied.setCreatedAt(now); copied.setUpdatedAt(now);
   versionMapper.insert(copied);
   ```
   - `description` 的 null → `""` 对齐 Python 的 `source.description or ""`。
   - `revision = 0`：**新草稿是一个新的乐观锁序列**，不是继承源的 revision。
   - ⚠ **三个溯源指纹（`ruleSetHash` / `snapshotFingerprint` / `modelFingerprint`）都不复制**。
     新草稿的它们是 `null`。这是"新草稿还没有被求解过"的正确表达。
   - `insert` 之后 `copied.getId()` 可用（`IdType.AUTO` 回填）—— 下一步依赖它。
5. **L141-154 整份拷贝明细**：
   ```java
   List<ScheduleVersionEntry> entries = entryMapper.selectList(...eq(versionId, source.getId()));
   for (ScheduleVersionEntry item : entries) {
       ScheduleVersionEntry copy = new ScheduleVersionEntry();
       copy.setOrganizationId(organizationId);   // ← 不是 item 的，是本组织的
       copy.setVersionId(copied.getId());         // ← 指向新版本
       copy.setClassId(item.getClassId()); ... copy.setDayOfWeek(item.getDayOfWeek());
       copy.setPeriodIndex(item.getPeriodIndex());
       entryMapper.insert(copy);
   }
   ```
   ⚠ **逐字段拷贝、逐行 INSERT**（不是 `INSERT ... SELECT`）。差异：
   - 源有 4,800 行时就是 4,800 次往返 —— 这是**已知的性能债**（Python 也是 `add_all` 后一次 commit，
     所以 SQLAlchemy 也是一行一条 INSERT，行为一致）。到 160 班规模要改成批量/`INSERT ... SELECT`。
   - **`day_of_week` 的负值会被原样拷走** —— 如果源版本里恰好存在"中间态残留"
     （理论上不可能，因为 swap 在同一事务里，但**表被手工改过就说不准**），
     副本会继承它。✅ 好在 `VersionValidator` 遇到负 `day_of_week` 会直接 409
     （`dayKey == null` → 抛），所以**下一次发布时会被拦住**，不会流出去。
   - **没有拷 `id`**（自增，新行拿新 id）。
6. **L155-156 审计**：`action="copied"`，`details={"source_version_id": 源 id}`。
   ⚠ 审计**记在新版本上**（`copied.getId()`），不是源版本上
   —— 所以"这一版是从哪来的"要从新版本的审计里看。
7. **L157 返回** `VersionResponse.of(copied, entries.size())` ——
   条目数是**内存里的 `entries.size()`**（刚拷的那份），**不重查库**。

#### `publish(long, SchedulePrincipal, long versionId)` → `VersionResponse`（L169-210）

**本包最重要的方法。** `@Transactional`，一个事务里做**五件事**：

| # | 做什么 | 代码 |
|---|---|---|
| ① | 只有**管理员**可以发布 | L170-172 |
| ② | 版本属于本组织 + 必须是草稿 | L173-176 |
| ③ | **整体校验**（拿回明细供后面复用） | L177 |
| ④ | 把上一版 `published` 归档 → 清空 `schedules` → 用本版明细重写 | L180-198 |
| ⑤ | 本版置 `published` + 审计 | L200-208 |

逐段：

1. **L170-172 权限。**
   ```java
   if (!principal.isAdmin()) throw BusinessException.forbidden("只有管理员可以发布课表");
   ```
   `isAdmin()` = `admin` 或 `platform_admin`（见 `SchedulePrincipal`），对应 Python 的
   `user.role not in {"admin", "platform_admin"}`。
   ⚠ **这一条写在最前面** —— 权限检查必须在任何"这条数据存不存在"的判断之前，
   否则非管理员可以用 404/409 的差异**探测别的组织有哪些版本 id**。
2. **L173-176** `requireOwned` + `!"draft"` → 409 `只有草稿可以发布`。
   ⚠ 这句文案（"只有草稿可以**发布**"）与 `update()` 的"只有草稿可以**修改**"不同，
   与 `SwapService` 的"已发布课表不可**拖动**，请先复制为新草稿"也不同 —— **三句都要逐字保留**。
3. **L177 —— 「校验并拿走明细」**：
   ```java
   List<ScheduleVersionEntry> entries = validator.validate(version);
   ```
   ⚠ **`validate` 的返回值不是布尔，是明细列表**。这是刻意的（Python 的 `_validate_version`
   也 `return entries`）：校验**必然要把明细读进内存**，那就不如**顺手返回给调用方复用**，
   后面重写 `schedules` 时不用再查一次库。少一次 4,800 行的往返。
   校验失败（超出上课时段 / 教师撞课）→ 抛 409，**事务回滚，什么都没写**。
4. **L178** `LocalDateTime now = UtcTimes.now()` —— 只取一次，让下面所有时间戳一致。
5. **L180-185 归档上一版 —— 注意这里用的是"部分更新"**：
   ```java
   ScheduleVersion archivePatch = new ScheduleVersion();
   archivePatch.setStatus("archived");
   archivePatch.setUpdatedAt(now);
   versionMapper.update(archivePatch, Wrappers.<ScheduleVersion>lambdaUpdate()
           .eq(ScheduleVersion::getOrganizationId, organizationId)
           .eq(ScheduleVersion::getStatus, "published"));
   ```
   - ⚠ 构造一个**只装了两个字段的"补丁对象"**，配 `versionMapper.update(entity, wrapper)`。
     这样生成的 SQL 只 `SET status=?, updated_at=?` ——
     如果用 `updateById` 会需要 id，用整对象还会把 `revision` 等字段覆盖掉。
   - ⚠ **`revision` 不动**。归档是**系统行为**，不是用户编辑，
     所以已归档版本的 revision 停在它被发布时的值。
   - ⚠ **`eq(status, "published")` 是必须的**：本组织的 `published` **最多一条**
     （由业务保证，数据库没有约束）。如果历史脏数据里有两条，这里会一起归档。
6. **L187-198 「先清空再重写」`schedules`**：
   ```java
   scheduleEntityMapper.delete(...eq(organizationId, organizationId));
   for (ScheduleVersionEntry item : entries) {
       ScheduleEntity row = new ScheduleEntity();
       row.setOrganizationId(organizationId); row.setClassId(item.getClassId());
       row.setSubjectId(item.getSubjectId()); row.setTeacherId(item.getTeacherId());
       row.setDayOfWeek(item.getDayOfWeek()); row.setPeriodIndex(item.getPeriodIndex());
       scheduleEntityMapper.insert(row);
   }
   ```
   - **为什么是删+插而不是更新？** 因为 `schedules` 的语义是"最新发布那一版的**拷贝**"，
     它和 `entries` 的行**没有对应关系**（不存 `version_id`、id 也不同）。见类注释：
     「`schedules` 只是"最新发布的那一版"的拷贝，供前端默认查看用」。
   - ⚠ **清空范围是"本组织"而不是"本版本"** —— `schedules` 里根本没有 `version_id`，
     它天然只存"当前"。
   - ⚠ `schedules` 上有唯一约束 `uq_schedule_org (organization_id, class_id, day_of_week, period_index)`
     （[V1](../backend-java/orbit-scheduling/src/main/resources/db/migration/scheduling/V1__baseline_legacy.sql#L63)）——
     因为已经先 DELETE 了本组织全部行，所以逐行 INSERT 不会撞。
   - ⚠ **`schedules` 没有指向 `schedule_versions` 的外键**（DDL 里只有 4 个 FK：org / class / subject / teacher）。
     所以删版本**不会**级联删 `schedules` —— 这正是 `SystemService.clearSchedules` 必须显式删四张表的原因（§6.2）。
7. **L200-205 本版转正**：
   ```java
   version.setStatus("published");
   version.setPublishedBy(principal.userId());
   version.setPublishedAt(now);
   version.setUpdatedAt(now);
   version.setRevision(version.getRevision() + 1);   // ← 发布也算一次修改
   versionMapper.updateById(version);
   ```
   ⚠ **发布让 `revision` +1**。语义是"这一版的内容基线变了"，
   所以客户端手里那份 revision 立刻失效（下次改名/拖动都要重新拿）。
8. **L207-208 审计**：`action="published"`，`details={"entry_count": N}`。
9. **L209 返回** `VersionResponse.of(version, entries.size())` —— 用内存里的明细条数，不重查。

> **为什么整个发布是原子的（`@Transactional`）**：
> 归档旧版、清空 `schedules`、写新 `schedules`、版本转正、写审计 —— 这五步**必须同生共死**。
> 中途失败（比如 `validate` 抛 409）时，**归档也必须回滚**，
> 否则会出现"旧版已 archived、新版还是 draft、`schedules` 被清空"的空窗 ——
> 那时前端看到的"当前课表"是空的，而且**没有任何版本是 published**。

#### `audit(long organizationId, long versionId)` → `List<AuditResponse>`（L213-220）

```java
requireOwned(organizationId, versionId);
return auditMapper.selectList(... eq(versionId).eq(organizationId)
        .orderByDesc(ScheduleAuditLog::getCreatedAt))
        .stream().map(AuditResponse::from).toList();
```

- **L214 先 `requireOwned`** —— 版本不存在就 404 `课表版本不存在`，
  **不是**返回空数组。所以"这个版本没有任何操作记录"和"这个版本不存在"是**两种响应**。
- **L215-218 双重条件** `versionId` + `organizationId` ——
  虽然 `versionId` 已经能唯一定位，但带上 `organizationId` 是"纵深防御"：
  万一上面那道 404 检查被误删，这一层仍然拦得住跨租户读取。
- ⚠ **`orderByDesc(createdAt)` 没有第二排序键**。
  `created_at` 是 `datetime`（秒精度，见 `UtcTimes`），
  所以**同一秒内的多条记录顺序由数据库决定，可能不稳定**。
  Python 也是单键 `desc()`，所以这是"等价的不确定性"。
  真要稳定应该补 `id desc` —— 但那会**改变已冻结的契约**，要一起改 Python 基线才敢动。
- ⚠ **返回的是裸数组**，**没有分页**。
  [项目方案 §13.1](智能教务中台-项目方案.md) 把 `schedule/versions/{id}/audit` 列进了
  「P1 要加的分页」名单，但实现时**没有加**（Python 的 `response_model=list[ScheduleAuditOut]` 也不分页）。
  这是**已知的名单与实现不一致**，需要时按 `query/` 那种"裸数组例外"的方式登记。

#### `requireOwned(long organizationId, long versionId)` → `ScheduleVersion`（L223-231）

```java
ScheduleVersion version = versionMapper.selectOne(... eq(id, versionId).eq(organizationId, organizationId));
if (version == null) throw BusinessException.notFound("课表版本不存在");
return version;
```

**它是本模块被复用最多的一个方法**，`public` 是刻意的：

| 谁调它 | 为什么 |
|---|---|
| `VersionService.update/copy/publish/audit` | 自己用 |
| `ScheduleQueryService.byClass/byTeacher/byGrade` | ⚠ **带 `version_id` 查课表时的租户隔离** —— 不加就能用别人的 version_id 读别人的课表 |
| `ExportService.export` | ⚠ 同上，导出路径 |

⚠ **别把它改成"返回 Optional 让调用方决定"** —— 那句话的文案
（`课表版本不存在`）是**契约的一部分**，统一在这里抛才能保证三处一字不差。

#### `nextVersionNo(long organizationId)` → `int`（L240-247）

```java
ScheduleVersion latest = versionMapper.selectOne(Wrappers.<ScheduleVersion>lambdaQuery()
        .select(ScheduleVersion::getVersionNo)      // ← 只 SELECT version_no
        .eq(ScheduleVersion::getOrganizationId, organizationId)
        .orderByDesc(ScheduleVersion::getVersionNo)
        .last("LIMIT 1"));                          // ← 手写 LIMIT
return (latest == null || latest.getVersionNo() == null ? 0 : latest.getVersionNo()) + 1;
```

- `.select(...)` 只取一列 —— 少一点网络字节。
- `.last("LIMIT 1")` 是**手写 SQL 片段**。这里安全（常量，无用户输入），
  但 ⚠ **它是"绕过 Wrapper 抽象"的口子**，看到 `.last()` 就要确认里面没有变量。
- `latest == null` → 返回 `1`（第一个版本）。对应 Python 的 `(func.max(...).scalar() or 0) + 1`。

> ⚠ **已知竞态（Python 也有，明确不改）**：类注释写得很直接：
> 「两个管理员同时复制会算出同一个号，后到的那个会撞 `uq_schedule_version_org_no` 唯一约束 → 500。
> P1 保持行为等价，不额外加锁；真要修应该用"唯一约束 + 重试"而不是先查后插。」
>
> 这是 [架构说明.md](架构说明.md) §6⑧ 列出的「已知不等价」之一（其实两边**都**错，所以"等价地错"）。
> **不要顺手加锁** —— 那会让 Java 与 Python 在并发下的行为分叉，而契约测试覆盖不到并发。

#### `countEntries(long versionId)` → `long`（L249-252）

`entryMapper.selectCount(...eq(versionId))` —— `COUNT(*)`。
用在 `update()` 和拖回原位的早退路径上（单版本，一次查询可以接受）。

#### `entryCounts(long organizationId)` → `Map<Long, Long>`（L255-270）

**列表接口专用**：一次 GROUP BY 拿全部版本的条目数。

```java
List<Map<String, Object>> rows = entryMapper.selectMaps(
        Wrappers.<ScheduleVersionEntry>query()
                .select("version_id", "COUNT(*) AS entry_count")
                .eq("organization_id", organizationId)
                .groupBy("version_id"));
```

- ⚠ **这里用的是字符串列名的 `Wrappers.query()`，不是 `lambdaQuery()`** ——
  因为 `selectMaps` 要的是"任意形状的结果集"，映射不到实体上。
  字符串列名**全是代码里的常量**（没有用户输入），所以安全。
- **遍历时做类型收窄**：
  ```java
  if (versionId instanceof Number id && count instanceof Number n) {
      counts.put(id.longValue(), n.longValue());
  }
  ```
  ⚠ **必须走 `Number`，不能强转 `Long`** —— MySQL 的 `COUNT(*)` 与 JDBC 驱动的
  返回类型可能是 `Long`、也可能是 `BigInteger`/`Integer`，强转会 `ClassCastException`。
  这种"某一版恰好返回 0 条就崩"的 bug 极难查。
- ⚠ **没有明细的版本不会出现在结果里**，所以调用方必须 `getOrDefault(..., 0L)`（L86 就是这么用的）。
- **对应 Python 的** `outerjoin(ScheduleVersionEntry, ...)` + `group_by(ScheduleVersion.id)`
  —— Python 用 outerjoin 保证"0 条的版本也在结果里"，Java 用"铺默认值"达到同样效果。

#### `audit(...)` 私有工厂（L272-284）

```java
private static ScheduleAuditLog audit(long organizationId, long versionId,
                                      SchedulePrincipal principal, String action,
                                      Map<String, Object> details)
```

造一条**未插入**的审计实体（`createdAt = UtcTimes.now()` 在这里设）。
调用方自己 `auditMapper.insert(...)`。

⚠ 它是 `static` 的 —— 不碰任何字段，纯粹是"少写 7 行 setter"。
`SwapService` **没有复用**它（那边自己手写了一遍，因为细节 map 的构造方式不同），
所以**两处构造审计的方式略有重复** —— 这是可以合并的小债。

---

### 1.5 `VersionController` —— 5 个接口（77 行）

[VersionController.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/version/VersionController.java)

```java
@RestController
@RequestMapping("/api/schedule/versions")
```

| 方法 | 路由 | 调谁 |
|---|---|---|
| `list(PageQuery, principal)` | `GET /api/schedule/versions` | `versionService.list(orgId, query)` |
| `update(Long, VersionUpdateRequest, principal)` | `PATCH /api/schedule/versions/{versionId}` | `versionService.update(...)` |
| `copy(Long, principal)` | `POST /api/schedule/versions/{versionId}/copy` | `versionService.copy(...)` |
| `publish(Long, principal)` | `POST /api/schedule/versions/{versionId}/publish` | `versionService.publish(...)` |
| `audit(Long, principal)` | `GET /api/schedule/versions/{versionId}/audit` | `versionService.audit(orgId, versionId)` |

**Controller 只做三件事**（取参数、调 Service、返回）—— 这里唯一多出来的是
`requirePrincipal(principal)`。它**不含业务逻辑**。

**⚠ 路径前缀与 `SwapController` 相同**（都是 `/api/schedule/versions`），
但拆成了两个类。类注释解释了为什么：

> 「⚠ 路径前缀是 `/api/schedule`（与 generate/status 同属一个 Python 路由），
> 但 Java 里拆成了两个 Controller：本类负责 versions，`ScheduleController` 负责 generate/status。
> **路径本身仍是契约**，拆分只是代码组织。」

`SwapController` 的注释给了拆分的**业务理由**：
「版本管理是 CRUD，换课是一套独立的规则引擎（固定课保护 + 跨班协同 + 整体重校验）」。

#### `requirePrincipal(SchedulePrincipal)`（L71-76）

```java
if (principal == null) throw BusinessException.unauthorized("请先登录");
return principal;
```

⚠ **为什么每个 Controller 都自己写一遍这个方法（4 个类各一份）？**

理论上 `SchedulingSecurityConfig` 已经 `.anyRequest().authenticated()`，
未认证的请求**到不了 Controller**（会被 `AuthenticationEntryPoint` 拦成 401）。
所以这里的 `principal == null` 是**防御性**的 —— 但**不能删**，因为：
1. 万一有人把某条路径加进 `permitAll`（P3 加 `/internal/**` 时就是这么干的），
   这个检查是最后一道；
2. `@AuthenticationPrincipal` 在"匿名但被放行"时给的是 `null`，
   直接 `principal.organizationId()` 会 NPE → 500（而 500 不是契约里的任何一条）。

⚠ **文案是 `请先登录`** —— 与 `AuthFailure.NO_TOKEN` 一致。
但注意 401 的**另外两种文案**（`登录已失效，请重新登录` / `账号不存在或停用`）
**不会**从这里出来，它们在 `JwtAuthenticationFilter` + `AuthenticationEntryPoint` 那条路径上
（见 [代码导读.md](代码导读.md) §1.10）。

---

### 1.6 `VersionResponse` —— HTTP 出参（29 行）

[VersionResponse.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/version/VersionResponse.java)

```java
public record VersionResponse(Long id, Integer versionNo, String name, String description,
                              String status, Integer revision, long entryCount,
                              Long createdBy, Long publishedBy,
                              LocalDateTime createdAt, LocalDateTime updatedAt,
                              LocalDateTime publishedAt)
```

对应 Python 的 `ScheduleVersionOut`。**12 个字段，一个不少、一个不多**
（**没有** `organizationId`，**没有**三个指纹 —— 指纹不对外暴露）。

| 细节 | 说明 |
|---|---|
| **字段名靠全局 `SNAKE_CASE`** | 输出 `version_no` / `entry_count` / `created_by` / `published_at` ✓ |
| **`entryCount` 是原始类型 `long`** | 不是 `Long` —— 它**永远有值**（0 也是值），不需要 null |
| ⚠ **`description` 的 null → `""`** | 工厂里写死：`version.getDescription() == null ? "" : version.getDescription()`。对应 Python 的 `description=version.description or ""`。**两者对前端是不同的**：`""` 能直接渲染，`null` 会显示 `undefined` |
| ⚠ **`createdAt` / `publishedAt` 可能是 `null`** | `publishedAt` 在草稿阶段确实是 null（Python 也输出 null）。**不要给它兜 `""`** —— 时间字段的 null 是契约 |

#### `static of(ScheduleVersion, long entryCount)`（L21-28）

唯一的工厂方法。⚠ **`entryCount` 是要调用方传的**，不是从实体上取的
—— 因为 `ScheduleVersion` 实体上根本没有这个字段（它是 `COUNT(*)` 的结果）。
所以调用方有三种写法，**都在代码里出现**：

| 调用点 | 传什么 | 为什么 |
|---|---|---|
| `list()` | 预先 GROUP BY 拿到的 `counts.getOrDefault(...)` | 避免 N+1 |
| `update()` / 拖回原位 | `countEntries(id)`（单独 COUNT） | 单行，不值得 GROUP BY |
| `copy()` / `publish()` | `entries.size()`（**内存里那份**） | 明细刚刚才在手上，重查是浪费 |

---

### 1.7 `VersionUpdateRequest` —— HTTP 入参（22 行）

[VersionUpdateRequest.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/version/VersionUpdateRequest.java)

```java
public record VersionUpdateRequest(
        String name, String description,
        @NotNull(message = "Field required") Integer expectedRevision) { }
```

⚠ **`expectedRevision` 是必填的**（只有它带 `@NotNull`）：

- Python 的 `ScheduleVersionUpdate` 里 `expected_revision: int` **没有默认值**
  → 缺了就是 422，不是"跳过乐观锁"。
- 类注释写了客户端的正确流程：
  > 「先拿到版本（含 revision）→ 用户编辑 → 提交时带上当时的 revision；
  > 不匹配就 409，避免两个管理员同时改名把对方的改动覆盖掉。」
- **`message = "Field required"` 是英文** —— 这是**有意**的：
  422 的 `msg` 文案不强求逐字（见 [代码导读.md](代码导读.md) §1.8 的 ⚠），
  但这里干脆用 Pydantic 的原字符串，让契约比对更干净。
- ⚠ **`name` / `description` 用 `String` 而不是 `@NotBlank`** ——
  因为"传了空串"要走**业务 400**（`方案名称不能为空`），
  而 `@NotBlank` 会把它变成 **422**。两种状态码，**不能混**
  （契约工具有专门的用例：`schedule 改名-空名称` → 期望 400）。

---

## 2. `swap/` —— 拖动换课（3 个类）★ 全项目最"绕"的一段

**先读 [架构说明.md](架构说明.md) §5.3（负值腾挪），那是理解这一节的钥匙。**

```
SwapController   POST /api/schedule/versions/{versionId}/swap
SwapService      业务逻辑（282 行，6 个阶段）
SwapRequest      ← HTTP 入参（对应 Python ScheduleSwapRequest）
```

**四个业务概念**（`SwapService` 的类注释列的，直接抄）：

| 概念 | 含义 |
|---|---|
| **同班拖动** | `target_class_id == 源班级` → 与目标位置的课**互换**（目标为空就是**移动**） |
| **跨班协同换时** | 两个班的课互换，且必须是**同一年级**（否则作息不同、换完更乱） |
| **镜像** | 跨班时还要处理"对方班在我原来的时段那一节"和"我在对方目标时段那一节" —— 它们是协同换时的一部分，**必须一起腾挪** |
| **固定课保护** | 涉及到的 `(年级, 星期, 节次)` 里若有年级固定课 → 禁止（409） |

---

### 2.1 `SwapController`（40 行）

[SwapController.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/swap/SwapController.java)

```java
@RestController
@RequestMapping("/api/schedule/versions")
public class SwapController {
    @PostMapping("/{versionId}/swap")
    public VersionResponse swap(@PathVariable Long versionId,
                                @Valid @RequestBody SwapRequest request,
                                @AuthenticationPrincipal SchedulePrincipal principal) {
        if (principal == null) throw BusinessException.unauthorized("请先登录");
        return swapService.swap(principal.organizationId(), principal, versionId, request);
    }
}
```

三个要点：

1. ⚠ **真实路径是 `/api/schedule/versions/{versionId}/swap`，不是 `/entries/swap`。**
   （`@RequestMapping("/api/schedule/versions")` + `@PostMapping("/{versionId}/swap")`；
   前端 [index.ts](../frontend/src/api/index.ts#L169-L170) 也是这样调的。
   ⚠ 注意 [代码导读.md](代码导读.md) §0.2 的教学示例里写的是
   `/api/schedule/versions/{id}/entries/swap` —— **那是举例时的笔误**，实际没有 `entries` 这一段。）
2. `@Valid @RequestBody SwapRequest` → 5 个字段全 `@NotNull`，
   缺字段由 `ApiExceptionHandler` 变成 **422 + `{"detail":[...]}`**（契约用例 `swap-缺字段` 期望 422）。
3. **返回 `VersionResponse`**（不是 SwapResponse）—— 换完课返回的是**这一版的新状态**
   （含新的 `revision`），客户端直接拿它刷新本地缓存。对应 Python 的 `response_model=ScheduleVersionOut`。

---

### 2.2 `SwapRequest`（25 行）

[SwapRequest.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/swap/SwapRequest.java)

| 字段 | 含义 |
|---|---|
| `sourceEntryId` | 要移动的那节课（`schedule_version_entries.id`） |
| `targetClassId` | 目标班级。**同班拖动就填自己** |
| `targetDayOfWeek` | 目标星期（0=周一，**0-based**） |
| `targetPeriodIndex` | 目标节次（0-based） |
| `expectedRevision` | 乐观锁，**必填** |

**它是"拖动一格"这个动作的完整描述**：移动哪节课 → 放到哪个班的哪一天哪一节。
⚠ **同班拖动与跨班协同换时共用这一个结构**，靠 `targetClassId` 是否等于源班级来区分 ——
所以**没有"模式"字段**，看请求本身看不出是哪种操作，必须结合数据。

---

### 2.3 `SwapService` —— 负值腾挪（282 行）★

[SwapService.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/swap/SwapService.java)

#### 2.3.1 先讲清"为什么不能直接 UPDATE 两行"

`schedule_version_entries` 上有唯一约束：

```
uq_version_class_slot (version_id, class_id, day_of_week, period_index)
```

想把 A 挪到 B 的位置、B 挪到 A 的位置时，**无论先改哪一个都会撞这个约束**：

```
初始：  A = (班1, 周一, 第1节)      B = (班1, 周一, 第2节)
想交换成：A = (班1, 周一, 第2节)    B = (班1, 周一, 第1节)

先改 A → UPDATE A SET day=0,period=1
         → 此刻 B 还在 (0,1)，同一班同一时段两行 → ✗ 撞 uq_version_class_slot
先改 B → 同理也会撞 ✗
```

> ⚠ **关键事实：唯一约束是在每条语句执行的那一刻检查的，不是等到 commit。**
> （InnoDB 下 `UPDATE` 立即做唯一性检查并加索引锁。）
> 所以"反正都在同一个事务里、最后状态是对的"——**这个理由不成立**。
> 中间态的冲突会在**第二条语句**上直接报错（`Duplicate entry ... for key 'uq_version_class_slot'`），
> 那个异常**不在** `ApiExceptionHandler` 的映射表里 → 会变成 **500**。
> **必须靠"顺序"绕开，而不是靠"事务"。**

Python 的解法（`schedule.py` 的 `swap_in_class`，L461-468）：把要移动的记录
**先改成"负值坐标"**，暂时移出冲突范围：

```python
first.day_of_week, first.period_index = -1, -first.id
db.flush()
if second:
    second.day_of_week, second.period_index = old_day, old_period
    db.flush()
first.day_of_week, first.period_index = destination_day, destination_period
db.flush()
```

**负值为什么安全、为什么用 `-id`：**

| 问题 | 答案 |
|---|---|
| 为什么负值不会冲突？ | 真实的 `day_of_week` 是 `0..4`、`period_index` 是 `0..N-1`，**负数与任何真实时段都不可能相等** |
| 为什么 `day_of_week = -1` 是常量就够？ | 因为同一事务里"负值中转区"最多同时有一条；而且 `day = -1` 已经把它移出真实区间了 |
| ⚠ **为什么 `period_index` 要用 `-first.id` 而不是 `-1`？** | 因为**跨班时会调两次 `swapInClass`**（见下面跨班分支），两次的"中转记录"可能同时在负值区。若都用 `(-1, -1)`，两条记录在 `(version, class, -1, -1)` 上会**互相撞**。用 `-id` 让每条记录的中转坐标**互不相同**（id 是唯一的） |
| 跨班的 `target` 为什么用 `-2`？ | 代码注释原话：「用 `-2` 区分于 `swapInClass` 里的 `-1`（**调试时能看出是谁在腾挪**）」——纯粹是排查友好，不改变行为 |

---

#### 2.3.2 依赖（L57-79）

| 依赖 | 用途 |
|---|---|
| `versionMapper` | **锁版本行**（`FOR UPDATE`）+ 最后更新 `revision` |
| `entryMapper` | 读/写明细（腾挪全靠 `updateById`） |
| `auditMapper` | 写审计 |
| `validator` | **换完的整体重校验** |
| `SnapshotMappers.ClassRefMapper` | 只读：班级（拿 `gradeId`） |
| `SnapshotMappers.GradeRefMapper` | 只读：年级（拿 `periodsPerDay`） |
| `SnapshotMappers.GradeFixedSlotRefMapper` | 只读：年级固定课（保护） |

⚠ **后三个都是 `snapshot/*Ref`（只读跨服务投影）** —— 换课需要读 academic 的表，
但**从不写**。这是 [架构说明.md](架构说明.md) §3.3 那条"已知耦合"的又一处落点。

`@Transactional`（L81）：⚠ **没有 `rollbackFor`**。
Spring 的默认回滚规则是"`RuntimeException` 与 `Error`"，
而 `BusinessException extends RuntimeException`（见 [代码导读.md](代码导读.md) §1.4），
所以**所有校验失败都会正确回滚**。
⚠ 但这也意味着：**如果以后有人在这里抛受检异常，事务不会回滚**（默认行为）。
需要受检异常时**必须显式写 `rollbackFor`**。

---

#### 2.3.3 `swap(...)` 主流程 —— 六个阶段

方法签名（L82-83）：

```java
@Transactional
public VersionResponse swap(long organizationId, SchedulePrincipal principal,
                            long versionId, SwapRequest request)
```

##### ① 锁版本行 + 三道校验（L84-97）

```java
ScheduleVersion version = versionMapper.selectOne(Wrappers.<ScheduleVersion>lambdaQuery()
        .eq(ScheduleVersion::getId, versionId)
        .eq(ScheduleVersion::getOrganizationId, organizationId)
        .last("FOR UPDATE"));
```

- ⚠ **`.last("FOR UPDATE")` —— 这是本模块唯一一处显式加行锁。**
  对应 Python 的 `.with_for_update().first()`。
  类注释解释了为什么必需：「拖动是并发敏感操作：两个管理员同时拖同一版必须串行」。
- ⚠ **`FOR UPDATE` 必须在事务里才有效**（否则语句结束就释放）—— 这就是 `@Transactional` 的第二个作用。
- ⚠ **`.last("FOR UPDATE")` 是字符串拼接的口子**，但内容是常量，无注入风险。
  看到 `.last()` 就要养成"确认里面没有变量"的习惯。

三道校验，**顺序与文案都要对上**：

| 顺序 | 条件 | 状态码 | 文案 |
|---|---|---|---|
| 1 | `version == null` | 404 | `课表版本不存在` |
| 2 | `!"draft".equals(status)` | 409 | `已发布课表不可拖动，请先复制为新草稿` |
| 3 | `!Objects.equals(revision, request.expectedRevision())` | 409 | `该草稿刚被其他用户修改，请刷新后重试` |

> ⚠ **`expected_revision`（乐观锁）与 `FOR UPDATE`（悲观锁）是两把不同的锁，都必须有**：
> - 乐观锁拦的是**"客户端的视图过期了"** —— 用户在浏览器里看到的课表是 10 分钟前的，
>   期间别人改过，那么这次拖动**基于错误的前提**，必须拒绝。这个信息只有客户端有。
> - 行锁拦的是**"两个请求同时到达"** —— 两个都带着正确的 revision，
>   但如果不串行化，它们的"读-改-写"会交错（都在校验通过后才写）。
> **少任何一个都会漏。** 契约用例 `swap-乐观锁不匹配` 期望 409，
> 而且注释里点明「在**任何写入之前**就 409，所以安全」—— 这个"写入之前"正是 L95 的位置。

##### ② 取源条目 + 两个班级 + 两个年级（L99-115）

```java
ScheduleVersionEntry source = entryMapper.selectOne(... eq(id, sourceEntryId).eq(versionId, version.getId()));
if (source == null) throw BusinessException.notFound("要移动的课程不存在");
ClassRef sourceClass = classMapper.selectOne(... eq(id, source.getClassId()).eq(organizationId, ...));
ClassRef targetClass = classMapper.selectOne(... eq(id, request.targetClassId()).eq(organizationId, ...));
if (sourceClass == null || targetClass == null) throw BusinessException.notFound("班级不存在");
GradeRef sourceGrade = gradeMapper.selectById(sourceClass.getGradeId());
GradeRef targetGrade = gradeMapper.selectById(targetClass.getGradeId());
```

- ⚠ **源条目必须"属于这一版"**（`.eq(versionId, version.getId())`），
  不能只按 id 查 —— 否则可以拖别的版本的课。文案：404 `要移动的课程不存在`
  （注意与"版本不存在"、"班级不存在"是**三句不同的话**）。
- ⚠ **班级查询带 `organizationId`**（租户隔离），**年级查询不带** ——
  因为年级是从"已经属于本组织的班级"上取 `gradeId` 再查的，
  在规范数据下必然同租户。这是一处**依赖数据不变式**的写法，
  如果 `classes.grade_id` 被指向别的组织的年级（DDL 没有阻止这种可能），
  这里会读出跨租户的年级。**属于已知的弱化，不是漏洞的入口**（因为它只影响校验的宽松度）。
- ⚠ `selectOne` 的**多行语义**：MyBatis-Plus 的 `selectOne` 查到 >1 行会抛
  `TooManyResultsException` → **500**；而 Python 的 `.first()` 会**安静地取第一行**。
  在"唯一约束保证不会重复"的表上等价；但**如果表被手工改坏**，两边的表现不同 ——
  已知的不等价，不修（修了反而与 Python 分叉）。

##### ③ 目标时段 / 源时段的时间范围校验（L117-134）

```java
int targetDay = request.targetDayOfWeek();
int targetPeriod = request.targetPeriodIndex();
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
boolean crossClass = !Objects.equals(source.getClassId(), targetClass.getId());
if (crossClass && !Objects.equals(sourceClass.getGradeId(), targetClass.getGradeId())) {
    throw BusinessException.badRequest("跨班协同换时仅支持同一年级的班级");
}
```

- ⚠ **`validTarget` 必须同时落在"两个班"当天的上课时间内**
  （`periods(sourceGrade, targetDay)` **且** `periods(targetGrade, targetDay)`）。
  为什么？跨班换时后，源班的课要落到目标班的那个时段，
  如果目标班那天只有 5 节而目标节次是第 6 节，换完就**超出目标班的作息**了。
- ⚠ **`validSourceForTarget` 是容易漏的一半**：源时段也必须落在**目标班**当天的范围内。
  类注释写得很清楚：「否则换过去对方那天根本没那么节课」。
  ⚠ 注意它**只查了目标年级**，没查源年级 —— 因为源条目本来就在源班里（已经合法）。
- `DAY_KEYS.size()` = **5**（周一~周五）—— 用 `DAY_KEYS` 而不是写死 `5`，两边共用一个常量。
- **文案：`目标节次不在两个班级的共同上课时间内`** —— 契约用例 `swap-目标节次越界` 期望 400。
- **跨班必须同年级**：文案 `跨班协同换时仅支持同一年级的班级`，
  契约用例 `swap-跨年级禁止` 期望 400（注释里写明"班级 1 属于一年级(5 节/天)，班级 3 属于二年级(6 节/天)"）。

> ⚠ **一处与 Python 的细微差异（防御性偏离）**：
> `periods(grade, day)` 在 `grade == null` 时返回 `0`，于是 `validTarget == false` → **400**。
> 而 Python 的 `source_grade.periods_per_day.get(...)` 在 `source_grade is None` 时会
> `AttributeError` → **500**。
> 也就是说：**当 `classes.grade_id` 指向一个不存在的年级时，Python 回 500、Java 回 400。**
> 这条路径在规范数据下走不到（`classes_ibfk` 外键保证 `grade_id` 有效），
> 所以没进契约用例。**如果你以后看到"Java 400 / Python 500"，先想到这类"防御性写法换了状态码"的地方。**

##### ④ 目标位置的那节课 + 两个早退（L136-145）

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

- **`oldDay` / `oldPeriod` 在腾挪之前取**（Python 在 L459 也是这样）——
  后面 `swapInClass` 会把 `source` 对象的字段改掉，所以必须**先快照**。
- ⚠ **拖回原位 → 早退，什么都不写**（不写审计、不动 `revision`）。
  Python 的 `return _version_out(db, version)` 与这里等价
  （Python 的 `_version_out` 在 `entry_count=None` 时自己 `COUNT`，Java 显式 `countEntries`）。
- **跨班时目标位置必须有课**（同班允许拖到空位 = 移动）。文案：
  `跨班拖动需要放到另一个班级已有课程上进行协同换时`。
  为什么？因为跨班"协同换时"的语义是**两节课对调**，对调的一方不存在就没有意义。

##### ⑤ 固定课保护（L147-171）

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
for (String key : protectedGradePositions) gradeIds.add(Long.parseLong(key.split(":")[0]));
List<GradeFixedSlotRef> fixedRules = gradeFixedSlotMapper.selectList(
        Wrappers.<GradeFixedSlotRef>lambdaQuery()
                .eq(GradeFixedSlotRef::getOrganizationId, organizationId)
                .in(GradeFixedSlotRef::getGradeId, gradeIds));
for (GradeFixedSlotRef rule : fixedRules) {
    if (protectedGradePositions.contains(rule.getGradeId() + ":" + rule.getDayOfWeek() + ":" + rule.getPeriodIndex())) {
        throw BusinessException.conflict("协同换时涉及固定课程，不能调整");
    }
}
```

逐点：

- **受影响的 4 个 (班, 日, 节) 位置**：源班的老位置、源班的新位置；
  跨班时再加目标班的老位置、目标班的新位置。
- ⚠ **Java 用"年级坐标"（`grade:day:period` 字符串集合），Python 用"班级坐标"再映射到年级**
  （`protected_grade_positions = {(class_grade_ids[class_id], day, period) ...}`）——
  **结果相同**，只是 Java 少了一个中间集合。⚠ `classGrade` 这个 map 就是为了做这个映射。
- ⚠ **为什么要按"年级"判而不是"班级"判**：**年级固定课**（`grade_fixed_slots`）是
  整个年级共享的（比如"三年级周三第 1 节是全校数学教研"），
  它约束的是**年级内的任何班**都不能占用那个时段。
- ⚠ **只查涉及到的年级**（`.in(gradeId, gradeIds)`）而不是查全表 ——
  一次查询、条件收窄。
- ⚠ **`gradeIds` 是空集也会执行查询吗？** 不会走到这里：
  `protectedGradePositions` 至少有源班的两条（`classGrade` 里一定有两项），
  所以 `gradeIds` 非空。
- ⚠ **字符串键 `"gradeId:day:period"` 是"用字符串当复合键"的常用手法**，
  解析时 `key.split(":")[0]` —— **负数也能正确解析**（`-1` 没有冒号）。
  但如果以后有人把键改成 `"a:b:c:d"` 就会静默错位。**这是脆弱点，改键格式时要一起改 `addProtected`。**
- **文案：`协同换时涉及固定课程，不能调整`**（409）。

##### ⑥ 腾挪（L173-197）—— 本模块的心脏

**同班分支**（L195-197）：

```java
swapInClass(source, target, oldDay, oldPeriod, targetDay, targetPeriod);
```

**跨班分支**（L174-194）：

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

**"镜像"是什么：**

| 变量 | 查询条件 | 含义 |
|---|---|---|
| `sourceMirror` | (源班, **目标**日, **目标**节) | **我要去的位置上，我自己班现在放着什么** |
| `targetMirror` | (目标班, **源**日, **源**节) | **对方班是否在我原来的位置上有一节课** |

> ⚠ **`targetOldDay` / `targetOldPeriod` 必须在 `target` 被改动之前读。**
> 这里有个**容易抄错的细节**：Python 是在 `swap_in_class(source, source_mirror, ...)` **之后**
> 才读 `target.day_of_week, target.period_index`（L484），
> ⚠ **但那时的 `target` 已经被 `swap_in_class` 的第二步改成了 `(old_day, old_period)`**！
>
> 等一下 —— 这两者**不等价**吗？看 `swapInClass(source, sourceMirror, oldDay, oldPeriod, targetDay, targetPeriod)`：
> 它内部把 **`sourceMirror`**（`second`）改成 `(oldDay, oldPeriod)`，
> **完全没有碰 `target`**。所以 Python 在 L484 读到的 `target` 仍是**原始值** ✓
> Java 在 `swapInClass` 之前读，也是原始值 ✓ —— **两者等价**。
> 这一点值得记下来：**"第二个参数是 sourceMirror、不是 target"** 是跨班分支最容易看错的地方
> （变量名 `second` 让人以为是 `target`）。

**跨班的四条搬运轨迹**（`target` / `targetMirror` 是目标班的两个实体）：

| 轨迹 | 谁动 | 从 | 到 |
|---|---|---|---|
| 1 | `source` | (源班, 老日, 老节) | (源班, 目标日, 目标节) —— 由 `swapInClass` 完成 |
| 2 | `sourceMirror`（若有） | (源班, 目标日, 目标节) | (源班, 老日, 老节) —— 由 `swapInClass` 完成 |
| 3 | `target` | (目标班, 原位置) | (目标班, 老日, 老节) —— 三次 `updateById` |
| 4 | `targetMirror`（若有） | (目标班, 老日, 老节) | (目标班, 原位置) —— 一次 `updateById` |

**`target` 为什么要走"负值"三步？** 因为轨迹 3 和轨迹 4 是**互换**：
`target` 要去 `targetMirror` 现在待着的地方（老位置）——
和 §2.3.1 开头的困境**一模一样**：

```
targetMirror 在 (目标班, 老日, 老节)   ← target 要去这里
target       在 (目标班, 目标日, 目标节) ← targetMirror 要去这里
```

所以 `target` 先 `(-2, -id)` 让位 → `targetMirror` 搬进 `target` 的原位置 → `target` 落到老位置。
**步骤顺序不能换**；换了就撞唯一约束（500）。

**四条轨迹为什么互不冲突** —— 逐个验证（记 `V` = version、班代称 A/B）：

| 步骤 | 语句 | 该 (班,日,节) 上此刻有谁 | 冲突？ |
|---|---|---|---|
| 1 | `source → (A,-1,-srcId)` | A 的目标位置由 `sourceMirror` 占（或为空） | 否（负值区） |
| 2 | `sourceMirror → (A,老日,老节)` | A 的老位置**已空**（步骤 1 腾走了 `source`） | 否 |
| 3 | `source → (A,目标日,目标节)` | A 的该位置**已空**（步骤 2 腾走了 `sourceMirror`） | 否 |
| 4 | `target → (B,-2,-tgtId)` | B 的目标位置腾空 | 否（负值区） |
| 5 | `targetMirror → (B,目标日,目标节)` | **B 的该位置刚被步骤 4 腾空** | 否 |
| 6 | `target → (B,老日,老节)` | **B 的老位置刚被步骤 5 腾空**（若 `targetMirror` 为 null，则本来就是空的） | 否 |

**逐个入参为 null 的分支：**

| 场景 | `sourceMirror` | `targetMirror` | 结果 |
|---|---|---|---|
| 双方在对方位置都有课 | 有 | 有 | 完整 4 条轨迹（真正的"协同换时"） |
| 只有我方位置被占 | 有 | 无 | 轨迹 2 执行；步骤 5 跳过；`target` 直接落到老位置（老位置因为步骤 2 已空） |
| 只有对方位置被占 | 无 | 有 | `swapInClass` 的 `second == null` → 跳过 step 2，`source` 直接落到目标位置；步骤 4/5/6 正常 |
| 双方各自只有一个位置 | 无 | 无 | 一次干净的跨班 2-循环 |

**`swapInClass` 的 null 语义**（见下）——它把 `second == null` 解释为"目标位置是空的，纯移动"。

##### ⑦ 整体重校验 + 收尾（L199-225）

```java
validator.validate(version);                       // ← 重跑整张表

version.setRevision(version.getRevision() + 1);
version.setUpdatedAt(UtcTimes.now());
versionMapper.updateById(version);

String action = crossClass ? "cross_swapped" : (target != null ? "swapped" : "moved");
Map<String, Object> details = new LinkedHashMap<>();
details.put("class_id", source.getClassId());
details.put("target_class_id", targetClass.getId());
details.put("from", List.of(oldDay, oldPeriod));
details.put("to", List.of(targetDay, targetPeriod));
details.put("source_entry_id", source.getId());
details.put("target_entry_id", target == null ? null : target.getId());
... auditMapper.insert(log);
return VersionResponse.of(version, countEntries(version.getId()));
```

- ⚠ **重校验是整个方法里最"反直觉"的一步**：类注释写了理由 ——
  「**不信任**"这次换课本身没冲突"，而是重新验证整张表，
  因为**一次拖动可能间接造成别处的冲突**」。
  例：把 A 班的第 3 节挪到第 4 节，恰好让"王老师"在（周二, 第 4 节）同时出现在两个班 ——
  换课的那些**目标/源位置校验一个都没碰到教师**，只有整表重扫才发现。
  校验失败 → `BusinessException`(409) → **事务回滚** → 这次拖动**完全没发生**（明细回到原样）。
- ⚠ **`validate()` 是在同一个事务里跑的**，所以它看到的正是**未提交的中间态**
  （负数都已经被改回来了，因为 ⑥ 已经做完了）。这是**故意**的：
  我们要校验"这次拖动之后的那张表"。
- **`revision + 1`** —— 与 `update()` / `publish()` 一样，**任何成功的写操作都让乐观锁失效**。
- **`action` 三选一**：`cross_swapped` / `swapped` / `moved`
  —— 判断依据是 `crossClass` 和 `target != null`。
  ⚠ **注意 `target != null` 在 `crossClass` 分支里恒为 true**
  （跨班时前面已经保证 `target != null`，否则 400 早退），所以实际的组合是：
  | 场景 | `target` | action |
  |---|---|---|
  | 同班，目标位置上**有**课（互换） | 非 null | `swapped` |
  | 同班，目标位置**空**（纯移动） | null | `moved` |
  | 跨班（必然有课） | 非 null | `cross_swapped` |
- **`details` 用 `LinkedHashMap`** —— ⚠ **审计的 JSON 键顺序要稳定**，
  否则同一操作在不同版本里 `details` 的键顺序不同，契约比对会出假差异。
  注意 `target_entry_id` 在 `moved` 场景是 **`null`**（Java 允许 `Map` 的值为 null；
  ⚠ 用 `Map.of(...)` 会直接**抛 NPE** —— 这就是这里必须用 `LinkedHashMap` 的另一个理由）。
- **`countEntries`** 在最后再查一次库 —— 此时事务未提交，但**本连接能看到自己的写** ✓。
  用内存算不出来（同班互换时条数不变，但跨班/移动时仍不变，其实**条数永远不变** ——
  换课不增删明细）。所以这一次查询**是可以省掉的**（直接用换课前的条数就行），
  但为了与 Python 的 `_version_out(db, version)` 行为完全一致（Python 确实查了库）就保留了。

---

#### 2.3.4 `swapInClass(...)` —— 三步腾挪（L234-251）

```java
private void swapInClass(ScheduleVersionEntry first, ScheduleVersionEntry second,
                         int oldDay, int oldPeriod, int destinationDay, int destinationPeriod) {
    if (first == null) {
        return;
    }
    first.setDayOfWeek(-1);
    first.setPeriodIndex(-first.getId().intValue());
    entryMapper.updateById(first);

    if (second != null) {
        second.setDayOfWeek(oldDay);
        second.setPeriodIndex(oldPeriod);
        entryMapper.updateById(second);
    }
    first.setDayOfWeek(destinationDay);
    first.setPeriodIndex(destinationPeriod);
    entryMapper.updateById(first);
}
```

逐行：

| 行 | 做什么 | 注意 |
|---|---|---|
| `if (first == null) return;` | 防御。**当前调用点永远传非 null 的 `source`**，所以走不到 —— 但保留它让方法自身完整 | ⚠ 与 Python 不同：Python 的 `swap_in_class` 没有这个判断（`first` 来自 `source`，必然非 null） |
| `first.setDayOfWeek(-1)` | 中转坐标的"日"部分 | 常数 `-1`，1..5 之外 |
| `first.setPeriodIndex(-first.getId().intValue())` | 中转坐标的"节"部分 | ⚠ **`-id` 而不是 `-1`** —— 跨班时本方法会调两次，两次的中转记录不能撞（见 §2.3.1） |
| `entryMapper.updateById(first)` | **发一条 UPDATE 并立刻执行** | ⚠ 这就是"flush"：**不是**攒到事务结束才发。`updateById` 靠主键更新**全部**字段（MyBatis-Plus 默认不含 null 字段） |
| `if (second != null) { second → (oldDay, oldPeriod) }` | 把占位的那节课搬到"旧位置" | ⚠ **此刻旧位置一定是空的**（step 1 腾走了 `first`）—— 这是本方法正确性的关键前提 |
| `first → (destinationDay, destinationPeriod)` | 落到目标位置 | ⚠ **此刻目标位置一定是空的**：同班时因为 `second` 刚被搬走；跨班时因为 `second` 就是 `sourceMirror`（在源班的目标位置上）**注意：`second` 的参数名容易让人以为是 `target`，但在跨班分支里传的是 `sourceMirror`** |

**`second` 是谁，取决于调用点**（这一点必须对照着看）：

| 调用点 | `first` | `second` | `destination` |
|---|---|---|---|
| 同班（L196） | `source` | `target`（目标位置上那节课，**可能为 null**） | (targetDay, targetPeriod) |
| 跨班（L179） | `source` | `sourceMirror`（**源班**在目标位置上的课，可能为 null） | (targetDay, targetPeriod) |

> ⚠ **同班 + `second` 存在时的语义**：`second`（原本在目标位置）被搬到**旧位置**，
> `first` 去**目标位置** —— 这就是标准的**两两互换** ✓
> ⚠ **同班 + `second == null`**（目标位置空）：只把 `first` 挪过去 —— **纯移动** ✓
> ⚠ **同班 + `second` 恰好 == `first`**：不可能，因为调用点已经用 `target.getId() == source.getId()` 早退（L139）。

⚠ **一处"物理上撞不上、但逻辑上会重复"的边界**（值得知道，因为校验器会报出来）：
若同班拖动时**另一个条目 `X` 恰好已经待在中转坐标 `(-1, -source.id)` 上**，
step 1 会和 `X` 撞唯一约束 → 500。但 `X` 要满足这个坐标意味着
"`day_of_week = -1` 且 `period_index = -sourceId`"——**只有被手工改坏的库或残留事务才会有**，
规范流程不可能（负数只在事务中间态出现）。所以这不是现实风险，但**它解释了"负值中转区"为什么必须看作"
绝不可能与真实数据重叠"的假设** —— 这个假设一旦破了，报的是 500 而不是 409。

---

#### 2.3.5 三个私有工具方法（L253-281）

| 方法 | 作用 |
|---|---|
| `addProtected(Set<String> positions, Map<Long,Long> classGrade, long classId, int day, int period)` | 把 `(该班的年级, day, period)` 以 `"grade:day:period"` 字符串塞进集合。`gradeId == null` 时**静默跳过**（不抛）：只有"班级查不到"才会为 null，而那条路已经在 ② 里 404 了 |
| `findEntry(versionId, classId, day, period)` | 按"版本 + 班 + 日 + 节"查一条明细 —— **正是 `uq_version_class_slot` 的四列**，所以最多一条。⚠ 用的是 `selectOne`：多行会抛 500（同 §2.3.3 ② 的说明） |
| `periods(GradeRef grade, int day)` | 从 `grade.getPeriodsPerDay()`（**JSON 列映射成 `Map<String,Integer>`**，键是 `"Mon".."Fri"`）取某天的节数。**三重防御**：`grade == null` / `periodsPerDay == null` / `day` 越界 → 返回 `0`（于是所有 `< periods` 的判断都会 false → 400） |
| `countEntries(versionId)` | `COUNT(*)`，与 `VersionService.countEntries` 重复实现（两个包各一份，**小重复**） |

⚠ `periods(...)` 用 `VersionValidator.DAY_KEYS.get(day)` 做键 —— **换课与校验共用同一个"星期几的字符串"
常量**，所以 `Mon..Fri` 的拼写**不可能漂移**。这是刻意的复用（`VersionValidator.DAY_KEYS` 是 `public static final`）。

---

### 2.4 ⚠ 换课的"可见性"陷阱：拖动**不会**改变已发布课表

这是本模块最容易被误解的一点，**必须说清楚**：

```
swap() 只写 schedule_version_entries（草稿明细）
       ↓
schedules（当前已发布课表的投影）一行都不动
       ↓
前端查 /api/schedule/class/{id}（不带 version_id）→ 看到的是【旧】课表
前端查 /api/schedule/class/{id}?version_id=N     → 看到的是【拖过之后】的草稿
```

类注释与 `VersionService.publish` 的注释一起说明了这个链路：
**只有 `publish` 才会把明细拷进 `schedules`**。

⚠ **所以"为什么我拖了课，家长看到的课表没变？"不是 bug** ——
这是"草稿 → 发布"两段式设计的**正常表现**，也正是
`已发布课表不可拖动，请先复制为新草稿` 这句文案的前提：
**已发布的版本根本不允许改，你要在草稿上改、改完再发布。**

⚠ **`swap` 也不碰 `scheduling_state`** —— 排课状态机（`running` 等）与换课无关。
所以 `/api/system/storage/compact` 的 `ensureNotRunning`（§6.3）**拦不住拖动**，
拖动在排课进行中也能做。两边（Python/Java）一致。

---

### 2.5 ⚠ 换课的并发与回滚小结

| 问题 | 答案 |
|---|---|
| 两个管理员同时拖同一版会怎样？ | 都不进入临界区：`SELECT ... FOR UPDATE` 让第二个请求**阻塞**到第一个提交，然后它读到的 `revision` 已经变了 → **409** |
| 换课失败（校验 409）时明细会怎样？ | **完全回滚**。`@Transactional` + `BusinessException extends RuntimeException` |
| 事务回滚后会不会留下负值坐标？ | **不会**。负值只存在于未提交的中间态；回滚把整个事务的写都撤销了 |
| 校验通过但 `versionMapper.updateById` 失败呢？ | 同样整体回滚（包括那些负值腾挪） |
| 事务提交后，负数还会存在吗？ | 不会。⑥ 结束时每条记录都被放到了真实坐标上（非负） |
| 万一留下负数（表被手工改坏）？ | 下一次 `publish` / `swap` 会被 `VersionValidator` 拦住（`dayKey == null` → 409 `存在超出班级上课时段的课程（记录 N）`） |

---

## 3. `query/` —— 课表查询与导出（4 个类）

```
ScheduleQueryController   /api/schedule/class|teacher|grade/{id} + /api/schedule/export
ScheduleQueryService      三个查询 + enrich
ExportService             导出 xlsx（Apache POI）
ScheduleResponse          ← HTTP 出参（对应 Python ScheduleOut）
```

### 3.1 ★ 为什么这里"刻意不分页"

这是本包**最需要解释**的设计决策。[项目方案 §13.1](智能教务中台-项目方案.md#L764-L766) 原文：

> **明确不分页的**：`/schedule/class|teacher|grade/{id}` 返回的是「一张课表」，
> 天然有上限（一个班一周最多几十行），**保持裸数组不变**（前端 `Timetable` 直接吃数组）。
> **这一点要显式标注，否则实现者会顺手改掉。**

三条理由，逐条说明：

1. **天然有上限。** 一个班一周 = 5 天 × 每天 5~6 节 = **最多 30 行**；一个年级 8 个班 ≈ 240 行。
   这和 `teachers`（可能几千人）不是同一类问题 ——
   **分页是为"无界的集合"准备的，这里是有界的**。
2. **页面需要完整数据。** 前端 `Timetable` 组件渲染的是一张**完整的周课表网格**（行=节次、列=星期）。
   分页会让它拿到半张表，然后要么写分页 UI（用户不会用）、要么再拼一次请求 ——
   **分页在这里只有坏处**。
3. **契约冻结。** Python 的 `response_model=list[ScheduleOut]` 返回**裸数组**，
   P1 的契约测试已经把"裸数组"冻结成基线（[项目方案](智能教务中台-项目方案.md#L633)）。
   改成 `{items,total}` 会让**前端 `Timetable` 与所有契约用例一起改**，
   而收益是零。**这是整个项目里唯一"保留裸数组"的一组接口。**

⚠ **与 `version/` 的对比是理解这条的关键**：

| 接口 | 响应 | 为什么不同 |
|---|---|---|
| `GET /api/schedule/versions` | `{items, total}` | 版本会随每次排课增长；160 班规模下会累积很多版 |
| `GET /api/schedule/versions/{id}/audit` | **裸数组** | 实现时没加分页（见 §1.4 audit 的 ⚠） |
| `GET /api/schedule/class/{id}` | **裸数组**（有意） | 一张课表有上限，前端要完整数据 |
| `GET /api/schedule/teacher/{id}` | **裸数组**（有意） | 同上（一个教师的周课时有上限） |
| `GET /api/schedule/grade/{id}` | **裸数组**（有意） | 上和（一个年级的全部班 ≈ 几百行，仍然有界） |
| `GET /api/system/storage` | 对象 | 不是列表 |

> ⚠ 三个查询接口**也不接受 `PageQuery`** —— 方法签名里根本没有这个参数
> （`@RequestParam(name="version_id", required=false)` 是唯一的可选参数）。
> **不要"顺手"给它们加上 `PageQuery`**，那会把"不分页"这个决定**默默推翻**。

---

### 3.2 `ScheduleQueryService`（170 行）

[ScheduleQueryService.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/query/ScheduleQueryService.java)

#### ★ `version_id` 决定查哪张表

类注释把这条放在最前：

| 请求 | 查哪张表 | 语义 |
|---|---|---|
| **不带** `version_id` | `schedules` | **当前已发布**的课表 |
| **带** `version_id` | `schedule_version_entries` | **指定版本**的明细 |

> 「所以同一个接口在"看已发布课表"和"看某版草稿"时读的是**不同的表**。
> 带 `version_id` 时会先校验该版本属于本组织（否则 404）——
> 这一步不能省，它是**租户隔离**的一部分：**不加就能用别人的 `version_id` 读到别人的课表**。」

⚠ 这就是 `VersionService.requireOwned` 被 `public` 出来的**业务理由**（§1.4）。
它在本包出现 **3 次**（`byClass` / `byTeacher` / `byGrade`），在 `ExportService` 里再出现 1 次 ——
**四处都省不得**。

#### `byClass(long organizationId, long classId, Long versionId)`（L60-71）

```java
if (versionId == null) {
    return enrich(scheduleEntityMapper.selectList(... eq(organizationId).eq(classId)), organizationId);
}
versionService.requireOwned(organizationId, versionId);          // ← 租户校验
return enrich(entryMapper.selectList(... eq(organizationId).eq(versionId).eq(classId)), organizationId);
```

- 两个分支的**唯一区别**是"表 + 过滤条件"。`versionId` 是包装类型 `Long`（可能为 null）——
  ⚠ **不能写成 `long`**，否则"没传 `version_id`"会变成绑定失败（`required = false` 时 Spring 给 null）。
- ⚠ **两个分支都带 `organizationId`**（纵深防御），即使已经 `requireOwned` 过。

#### `byTeacher(...)`（L74-85）

与 `byClass` **结构完全一样**，只把 `.eq(classId)` 换成 `.eq(teacherId)`。
⚠ **没有 `teacherId` 存在性校验** —— 老师不存在时返回**空数组**（不是 404）。
这与 Python 一致（Python 也是直接 filter，不查 `Teacher` 是否存在）。
⚠ **但 `version_id` 不存在时必须 404** —— 所以只有"版本"这一个维度有存在性检查。

#### `byGrade(...)`（L93-112）—— 唯一有"两段式"的一个

```java
List<Long> classIds = classRefMapper.selectList(Wrappers.<ClassRef>lambdaQuery()
                .select(ClassRef::getId)                    // ← 只 SELECT id
                .eq(ClassRef::getOrganizationId, organizationId)
                .eq(ClassRef::getGradeId, gradeId))
        .stream().map(ClassRef::getId).toList();
if (classIds.isEmpty()) {
    return List.of();                                       // ← 早退
}
if (versionId == null) { ... .in(ScheduleEntity::getClassId, classIds) ... }
versionService.requireOwned(organizationId, versionId);
return enrich(entryMapper.selectList(... .in(ScheduleVersionEntry::getClassId, classIds)), organizationId);
```

逐点：

1. **为什么先取班级 id 再 `IN`，而不是像 Python 那样 `join(Class)`？**
   类注释原话：「Python 是 `join(Class)` 过滤；这里先取出该年级的班级 id 再 `IN` ——
   效果相同，而且**不用在 Mapper 上写联表**」。
   ⚠ 这是刻意的取舍：**`*Ref` Mapper 都是空的 `BaseMapper`**（`SnapshotMappers` 里 7 个都是），
   一旦要联表就得手写 `@Select`，那会破坏"只读投影"的整齐性。
   代价是**多一次查询往返**（一次取 id、一次取明细）。
2. **`classIds.isEmpty()` 早退** —— 一个年级没有班 → **空数组**（不 404）。
   ⚠ **这个早退不只是优化**：`IN ()` 是**非法 SQL**，
   不早退的话 MyBatis-Plus 会拼出 `WHERE class_id IN ()` → SQL 语法错误 → 500。**必须留着。**
3. ⚠ **注意 `requireOwned` 的位置**：在 `classIds` 早退**之后**。
   也就是说"年级 empty + version_id 不存在"时返回的是**空数组，不是 404** ——
   这一细微顺序与 Python 不同吗？Python 是 `_schedule_query` **先** `_version_or_404`
   再 `join(Class).filter(...)`，所以 Python 会先 404。
   **⚠ Java 在"年级没有班"这种边缘输入下与 Python 不同（Java 空数组 / Python 404）。**
   （在规范数据下走不到：年级没有班说明数据不完整。**如果要做到位，应该把 `requireOwned` 提到 `classIds` 查询之前。** 这里按代码现状记录，不编造。）

#### `enrich(List<T> rows, long organizationId)` —— 补上名字（L120-145）

| 步骤 | 代码 | 说明 |
|---|---|---|
| ① | `if (rows.isEmpty()) return List.of();` | ⚠ **早退**：没有明细就不要白跑 3 次字典查询。Python 的 `_enrich` **没有**这个早退（Python 每次都会查三张全表）；行为等价，只是更快 |
| ② | 查 `subjects` / `teachers` / `classes` 三张全表（`eq(organizationId)`），各转成 `Map<Long,String>` | ⚠ **三次查询、各拉全组织**。这是 Python 的做法（`{item.id: item.name for item in db.query(...).all()}`）。到 160 班规模时"每查一次课表就拉一张全教师表"是**已知的性能债** |
| ③ | 逐行包成 `ScheduleResponse` | 用 `getOrDefault(id, "")` —— **找不到时是空串，不是 null** |

- ⚠ **③ 的空串语义是契约**（见 `ScheduleResponse` 的 ⚠）。
- ⚠ **`enrich` 是个泛型方法 `<T>`**，靠 `ScheduleRow.of(row)` 分辨两种实体 —— 见下。

#### `nameMap(...)`（L147-152）

```java
private static <T> Map<Long, String> nameMap(List<T> rows,
                                             Function<T, Long> idOf, Function<T, String> nameOf) {
    return rows.stream().filter(r -> idOf.apply(r) != null)
            .collect(Collectors.toMap(idOf, nameOf, (a, b) -> a, HashMap::new));
}
```

- ⚠ **`.filter(idOf.apply(r) != null)`**：`Collectors.toMap` 的**键不允许 null**
  （会抛 NPE）。所以先把"没有 id 的行"滤掉。**这不是可选的优化，是必需的防御。**
- ⚠ **`(a, b) -> a` 合并函数**：**没有它，重复键会抛 `IllegalStateException`**。
  表上有唯一约束时不会重复，但这类"看起来多余的第三参数"**正是防止 500 的那一行**。
- ⚠ **`HashMap::new` 显式指定**：不写会得到默认的 `HashMap`，但**显式写出来是意图声明**
  （此处**需要**去重，顺序无所谓 —— 与 §2.3.3⑥ 的 `LinkedHashMap` 恰好相反）。
- ⚠ **`ExportService.names(...)` 是另一个实现**（没有 filter、没有合并函数、手写循环）——
  **两处重复但不完全等价**（导出那边键为 null 时会静默覆盖/允许 null 键）。见 §3.4。

#### 内部 record `ScheduleRow`（L155-169）

```java
private record ScheduleRow(Long id, Long classId, Long subjectId, Long teacherId,
                           Integer dayOfWeek, Integer periodIndex) {
    static ScheduleRow of(Object row) {
        if (row instanceof ScheduleEntity e) { return new ScheduleRow(e.getId(), ...); }
        if (row instanceof ScheduleVersionEntry e) { return new ScheduleRow(e.getId(), ...); }
        throw BusinessException.badRequest("未知的课表行类型: " + Objects.toString(row));
    }
}
```

**它解决什么问题**：`schedules` 与 `schedule_version_entries` 的字段**完全一样**（除了没有 `versionId`），
但它们是**两个不同的类**、没有共同接口。所以用一个"适配函数"统一，
避免把 `enrich` 写两遍（类注释：「两种入参字段完全一样，用一个小的适配函数统一，避免写两遍」）。

- ⚠ **`of` 的参数类型是 `Object`**，不是泛型约束 —— 所以"传错类型"是**运行时**才发现。
  那个 `throw` 是**不可达的防御分支**（只有两个调用点，都传这两种实体）。
- ⚠ `Objects.toString(row)` 在 `row == null` 时给 `"null"` 而不是 NPE —— 小事，但省一次 500。
- ⚠ 文案 `未知的课表行类型: ...` **Python 侧没有对应文案**（Python 是鸭子类型，不会遇到）。
  所以这是一个**Java 独有的 400**，契约里没有它，也走不到。

---

### 3.3 `ScheduleQueryController`（91 行）

[ScheduleQueryController.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/query/ScheduleQueryController.java)

```java
@RestController
@RequestMapping("/api/schedule")
```

| 方法 | 路由 | 返回 |
|---|---|---|
| `byClass(Long, Long versionId, principal)` | `GET /api/schedule/class/{classId}` | `List<ScheduleResponse>`（**裸数组**） |
| `byTeacher(...)` | `GET /api/schedule/teacher/{teacherId}` | 同上 |
| `byGrade(...)` | `GET /api/schedule/grade/{gradeId}` | 同上 |
| `export(Long versionId, principal)` | `GET /api/schedule/export` | `ResponseEntity<byte[]>`（**xlsx 二进制**） |

三个查询接口的形状**完全一样**：

```java
@GetMapping("/class/{classId}")
public List<ScheduleResponse> byClass(
        @PathVariable Long classId,
        @RequestParam(name = "version_id", required = false) Long versionId,
        @AuthenticationPrincipal SchedulePrincipal principal) {
    return queryService.byClass(requirePrincipal(principal).organizationId(), classId, versionId);
}
```

- ⚠ **`@RequestParam(name = "version_id", ...)` 的 `name` 不能省**：
  Java 参数名是 `versionId`（camelCase），查询参数是 `version_id`（snake_case）。
  **两者对不上时 Spring 不报错，只是这个参数永远为 null**
  （表现是"传了 `version_id` 但查到的是已发布课表"——极难发现）。与 [代码导读.md](代码导读.md) §1.2 坑 2 同源。
- `required = false` → 不传就是 null → 查 `schedules`。
  ⚠ **不要写 `required = true`**：那样不传会 422，而"不传"是**最常见**的用法。
- **`List<ScheduleResponse>` 直接返回**（不是 `PageResponse`）—— 见 §3.1。

#### `export(...)`（L72-83）—— 唯一不返回 JSON 的接口

```java
ExportService.ExportFile file =
        exportService.export(requirePrincipal(principal).organizationId(), versionId);
return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, file.contentType())
        .header(HttpHeaders.CONTENT_DISPOSITION, file.contentDisposition())
        .contentType(MediaType.parseMediaType(file.contentType()))
        .body(file.content());
```

- **两个响应头**：`Content-Type` = `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，
  `Content-Disposition` = `attachment; filename=schedule.xlsx`。
  类注释强调：「前端靠前者识别文件类型、靠后者拿到下载文件名，**两个都不能少**」。
- ⚠ **`.header(CONTENT_TYPE, ...)` 与 `.contentType(...)` 是冗余的**（后者也会写 `Content-Type`）。
  保留 `.header` 是**为了与 Python 的显式设置逐字对应**（便于对照阅读），实际由 `contentType` 生效。
  改的时候**两个一起改**，别只删一个。
- ⚠ **出错时仍然返回 JSON**：`requirePrincipal` 抛 401、`export` 内部 `requireOwned` 抛 404 ——
  都走 `ApiExceptionHandler`，响应体是 `{"detail":"..."}`。
  类注释点明了这个"混搭"：「它**不是** JSON 接口……但出错时（版本不存在）仍然返回 JSON 的 404」。
  契约用例 `export-版本不存在` 期望 404 就是这么来的。
- ⚠ **返回 `ResponseEntity<byte[]>`（整个文件在内存里）**：
  一个年级的 xlsx 只有几十 KB，可接受。**到 160 班规模要改成流式**（`StreamingResponseBody`），
  否则大组织导出会占一份完整内存。

---

### 3.4 `ExportService` —— Apache POI 写 xlsx（209 行）

[ExportService.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/query/ExportService.java)

对应 Python 的 [routers/export.py](../backend/routers/export.py)（openpyxl）。

#### 版式（逐项对齐 Python）

| 项 | 规则 |
|---|---|
| 工作表 | **每个班一个 sheet**，表名 = **班级名**（截断到 31 字符 —— Excel 的硬限制） |
| 第 1 行 | `节次 \| 周一 \| 周二 \| 周三 \| 周四 \| 周五` |
| 之后每天一行 | 行首 `第N节`，单元格内容是 `科目名\n教师名`，空位是**不创建单元格** |
| 行数 | 该班**最大节次 + 1**（各班可以不同 —— 不同年级每天节数不同） |
| sheet 顺序 | 按 `class_id` **升序** |

#### 常量（L52-56）

```java
private static final List<String> DAY_NAMES = List.of("周一", "周二", "周三", "周四", "周五");
private static final String CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
private static final String FILENAME = "schedule.xlsx";
```

⚠ **`DAY_NAMES` 与 Python 的 `day_names` 逐字一致**（类注释强调）。
⚠ **与 `VersionValidator.DAY_KEYS`（`Mon..Fri`）是两套东西**：
前者是**中文表头**，后者是 `grades.periods_per_day` JSON 里的**英文键**。
**不要合并它们** —— 一个是输出、一个是查表键。

#### 内部 record `ScheduleRow` 与 ⚠ 命名陷阱（L90-92）

```java
private record ScheduleRow(long classId, long subjectId, long teacherId,
                           int dayOfWeek, int periodIndex) { }
```

⚠ **名字不能叫 `Row`** —— 类注释记录了踩坑原文：

> 「⚠ 名字**不能**叫 `Row`：那样会和 POI 的 `org.apache.poi.ss.usermodel.Row` 撞名
> （同一文件里两个 `Row`，编译器会挑本类的那个，
> 于是 `sheet.createRow()` 的返回值无法赋给 local 变量）。**实测踩过一次。**」

⚠ 与 `ScheduleQueryService.ScheduleRow` **同名但是两个不同的 record**
（一个带 `id`、一个带 `long` 原始类型）—— 分属两个类，不冲突，但**读代码时注意别串**。

#### `ExportFile`（L80）

```java
public record ExportFile(byte[] content, String contentType, String contentDisposition) { }
```

把"字节 + 两个响应头"打包返回，让 Controller 不碰 POI 也不碰文件名常量 ✓

#### `export(long organizationId, Long versionId)` → `ExportFile`（L100-184）

**第一步：取明细（两个分支 + 租户校验）**（L101-125）

```java
if (versionId == null) {
    rows = scheduleMapper.selectList(... eq(organizationId)
                    .orderByAsc(classId).orderByAsc(dayOfWeek).orderByAsc(periodIndex))
            .stream().map(e -> new ScheduleRow(...)).toList();
} else {
    versionService.requireOwned(organizationId, versionId);          // ⚠ 不能省
    rows = entryMapper.selectList(... eq(versionId).eq(organizationId)
                    .orderByAsc(classId).orderByAsc(dayOfWeek).orderByAsc(periodIndex))
            .stream().map(e -> new ScheduleRow(...)).toList();
}
```

- ⚠ **L113 的注释直接写了原因**：「不能省：不加这一步就能用别人的 `version_id` 导出别人的课表（租户隔离）」。
- ⚠ **注意 `versionId != null` 分支带了 `organizationId`，而 Python 也带了** ✓
- **排序三键 `class_id, day_of_week, period_index`** 对齐 Python 的 `order_by(...)`。
  ⚠ 有趣的是：导出**并不依赖**这个顺序（后面用 `TreeMap` 重分组、用 `find` 线性查找），
  但**保持与 Python 相同的 SQL**能让 EXPLAIN / 行为更好对照。
- ⚠ `version_id` 分支**没有** `organizationId` 之外的租户条件问题（`requireOwned` 已经查过）——
  这里再带一次是**纵深防御**。

**第二步：预取三张字典**（L127-135）

```java
Map<Long, String> subjects = names(subjectMapper.selectList(...eq(organizationId)), SubjectRef::getId, SubjectRef::getName);
Map<Long, String> teachers = names(...);
Map<Long, String> classes  = names(...);
```

⚠ 与 `ScheduleQueryService.enrich` 里那三行**几乎一样**，但用的是**本类的 `names(...)`**：
```java
private static <T> Map<Long, String> names(...) {
    Map<Long, String> result = new HashMap<>();
    for (T row : rows) result.put(idOf.apply(row), nameOf.apply(row));
    return result;
}
```
⚠ **它没有 `filter(id != null)`、也没有合并函数** —— 与 `nameMap` **不等价**。
在规范数据下两者相同。这是一处**重复实现**，合并时要注意语义差异（要按 `nameMap` 那份更安全的来）。

**第三步：按班分组，按 `class_id` 升序**（L137-141）

```java
Map<Long, List<ScheduleRow>> byClass = new TreeMap<>();
for (ScheduleRow row : rows) byClass.computeIfAbsent(row.classId(), k -> new ArrayList<>()).add(row);
```

⚠ **`TreeMap` 而不是 `HashMap`** —— 注释：`// TreeMap 保证按 class_id 升序建表（Python: sorted(by_class)）`。
⚠ **sheet 的顺序是契约的一部分**（`compare_exports.py` 会逐字比对工作表名**与顺序**），
所以这里**必须**是有序容器。

**第四步：写 workbook**（L143-183）

```java
try (XSSFWorkbook workbook = new XSSFWorkbook();
     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
    for (Map.Entry<Long, List<ScheduleRow>> entry : byClass.entrySet()) {
        long classId = entry.getKey();
        List<ScheduleRow> classRows = entry.getValue();
        String sheetName = classes.getOrDefault(classId, "班级" + classId);
        Sheet sheet = workbook.createSheet(truncate(sheetName));

        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("节次");
        for (int day = 0; day < DAY_NAMES.size(); day++) {
            header.createCell(day + 1).setCellValue(DAY_NAMES.get(day));
        }

        int maxPeriod = classRows.stream().mapToInt(ScheduleRow::periodIndex).max().orElse(-1) + 1;
        for (int period = 0; period < maxPeriod; period++) {
            Row excelRow = sheet.createRow(period + 1);
            excelRow.createCell(0).setCellValue("第" + (period + 1) + "节");
            for (int day = 0; day < DAY_NAMES.size(); day++) {
                ScheduleRow found = find(classRows, day, period);
                if (found == null) {
                    // ⚠ 空位不创建单元格，而不是写空字符串（见下）
                    continue;
                }
                Cell cell = excelRow.createCell(day + 1);
                cell.setCellValue(subjects.getOrDefault(found.subjectId(), "") + "\n"
                        + teachers.getOrDefault(found.teacherId(), ""));
            }
        }
    }
    workbook.write(out);
    return new ExportFile(out.toByteArray(), CONTENT_TYPE, "attachment; filename=" + FILENAME);
} catch (IOException e) {
    throw new IllegalStateException("导出课表失败", e);
}
```

逐点：

| 点 | 说明 |
|---|---|
| `try (XSSFWorkbook ...; ByteArrayOutputStream ...)` | **try-with-resources**：POI 的 workbook 持有临时文件/流，不关会泄漏 |
| **`XSSFWorkbook` 新建时一个 sheet 都没有** | ⚠ 与 openpyxl 的差异 ①：openpyxl 新建自带一个空的 `Sheet`，Python 要**显式删掉**（`if by_class and "Sheet" in workbook.sheetnames: del workbook["Sheet"]`）；POI **不用删** |
| `createSheet(truncate(sheetName))` | ⚠ 与 openpyxl 的差异 ②：POI 在**工作表名重复时会抛异常**；这里依赖"班级名在组织内唯一"（`uq_class_org_name`）。⚠ **但截断到 31 字符后可能变重复**（两个长班级名前 31 字符相同）—— 那种数据下 POI 会 500 而 openpyxl 会**静默改名**（加 `1`）。**属于已知的实现差异，未处理。** |
| `classes.getOrDefault(classId, "班级" + classId)` | 对齐 Python 的 `classes.get(class_id, f"班级{class_id}")` |
| `maxPeriod = max(periodIndex) + 1` | ⚠ **每班各算各的**（不同年级每天节数不同）。`orElse(-1) + 1` 在"该班一行都没有"时得 `0` → 只有表头行。⚠ **但 `byClass` 里的每个班至少有一行**（分组就是这么来的），所以 `orElse` 走不到 |
| ⚠ **空位 `continue`（不创建单元格）** | 这是**整个导出里最关键的一行**，注释写了原因：<br>「openpyxl 写 `""` 时不会真正生成单元格，读回来是 `None`；POI 写 `""` 会生成一个**空字符串单元格**，读回来是 `""`。两者在 Excel 界面里长得一样，但**程序读出来 `None ≠ ""`** —— 实测就是这么发现差异的。**不创建单元格两边才一致**」 |
| `subjects.getOrDefault(id, "") + "\n" + teachers.getOrDefault(id, "")` | 对齐 Python 的 `f"{subjects.get(found.subject_id, '')}\n{teachers.get(found.teacher_id, '')}"`。⚠ **找不到名字时是空串**，所以可能出现 `"数学\n"` 这种结尾换行 —— **这是对的，别 trim** |

**`truncate(String)`**（L187-189）：`name.length() <= 31 ? name : name.substring(0, 31)`
—— 对齐 Python 的 `title=...[:31]`。⚠ **按 UTF-16 码元计数**，中文占 1 个 `char`（BMP 内），
所以对中文班级名与 Python 的**字符**计数一致。

**`find(List<ScheduleRow>, day, period)`**（L191-198）：**线性扫描**找出该位置的课。
一个班 ≤ 30 行，OO(n²) 完全无所谓（≤ 900 次比较）。**不建索引是刻意的简单**。

**`catch (IOException e) → IllegalStateException("导出课表失败", e)`**：
⚠ 抛 `IllegalStateException`（RuntimeException）→ 被 `ApiExceptionHandler` 兜成 **500 `服务器内部错误`**。
⚠ **真实的 `IOException` 只写进日志**（`handleUnexpected` 里 `log.error`）—— 这是有意的（不外泄细节）。

#### ★ 为什么字节必然与 Python 不同 → 所以等价性靠"解析后比单元格矩阵"

**xlsx 就是一个 ZIP**。两个库（openpyxl / Apache POI）写出来的 ZIP **必然不同**：

| 差异来源 | 具体表现 |
|---|---|
| 压缩算法与级别 | 两个库用的 deflate 参数不同 |
| ZIP 内部条目顺序 | `[Content_Types].xml` / `xl/worksheets/sheet1.xml` … 的排布顺序不同 |
| 元数据 | `docProps/core.xml` 里的创建者、创建/修改时间戳（**每次导出都不同**） |
| XML 命名空间前缀与属性顺序 | 两个库的序列化器不同 |
| 空白与自闭合标签 | `<c r="B2"/>` vs `<c r="B2"></c>` |

所以**"字节相同"这个判据根本不可能成立** —— 它不是在测"内容一致"，而是在测"用的是同一个库"。

**替代判据：把两份 xlsx 都解析成「工作表名 → 单元格矩阵」再比。**
这就是 [tools/contract-diff/compare_exports.py](../tools/contract-diff/compare_exports.py) 干的事：

```python
def grid(path):
    """读出「工作表名 → 单元格矩阵」。空尾行/尾列会被裁掉（两个库的"已用区域"算法不同）。"""
    workbook = load_workbook(path)
    for name in workbook.sheetnames:
        for row in sheet.iter_rows(values_only=True):
            cells = list(row)
            while cells and cells[-1] is None:      # 裁掉行尾的 None
                cells.pop()
            rows.append(cells)
        while rows and not rows[-1]:                # 裁掉空尾行
            rows.pop()
```

它比三件事：
1. **工作表名与顺序**（`list(py) != list(jv)` → ❌）—— 这就是为什么 `TreeMap` 不能换成 `HashMap`；
2. **每张表的行数 / 列数**；
3. **单元格矩阵逐格相等** —— 有任何一格不同就把那一行打出来给人看。

⚠ **两个"裁掉"步骤是必需的**：POI 与 openpyxl 对"已用区域 / 空单元格"的记录方式不同，
不裁的话会在**行尾多出一串 `None` 或 `""`**，产生纯粹由实现造成的假差异。

> **这就是"等价性靠解析后比单元格矩阵"的完整逻辑链**：
> 字节不可比（ZIP）→ 必须解析 → 解析后要**先归一化**（裁尾）→ 再比**语义单位**（工作表 + 单元格）。
> 这也解释了为什么 `ExportService` 里那些"看起来多余"的 `continue` 和 `truncate`
> 是**必须的**：它们决定了归一化之后能不能对上。

#### ⚠ 一处**已知的行为差异**：空组织的导出

Python（[export.py:67-68](../backend/routers/export.py#L67-L68)）：

```python
if by_class and "Sheet" in workbook.sheetnames:
    del workbook["Sheet"]
```

⚠ **条件是 `by_class`（非空）** —— 所以当**一个班都没有**（`by_class == {}`）时，
那个 openpyxl 自带的 `"Sheet"` **不会被删**，导出的文件里**有一张名叫 `Sheet` 的空工作表**。

Java 的 `XSSFWorkbook` **新建时没有任何 sheet**，且 `byClass` 为空时循环体一次都不执行
→ 导出的文件**零个工作表**（`xl/workbook.xml` 里 `<sheets/>` 为空）。

**结论：班级数为 0 时，Python 的工作表列表是 `["Sheet"]`，Java 是 `[]`** ——
`compare_exports.py` 会在"工作表名/顺序不一致"这一条上报 ❌。
⚠ 规范数据下（每个组织都有班）走不到，所以契约用例里没覆盖。
**这是一处真实的、未被处理的等价性缺口**（不是编造的推测 —— 两边代码都在这里引到了）。

---

### 3.5 `ScheduleResponse`（13 行）

[ScheduleResponse.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/query/ScheduleResponse.java)

```java
public record ScheduleResponse(Long id, Long classId, Long subjectId, Long teacherId,
                               Integer dayOfWeek, Integer periodIndex,
                               String subjectName, String teacherName, String className) { }
```

对应 Python 的 `ScheduleOut`。**9 个字段**。

⚠ **类注释唯一的重点就是空串语义**：
> 「`subject_name` / `teacher_name` / `class_name` 在 Python 里是用 `.get(id, "")` 取的
> —— **找不到时是空字符串，不是 null**。这个区别对前端是可见的
> （空串能渲染、null 会显示 undefined），所以这里也用空串。」

**没有工厂方法** —— 因为它由 `enrich()` 直接 `new`（构造参数在那一行就全齐了），
不需要像 `VersionResponse` 那样从一个实体转换。

---

## 4. `validate/` —— 独立的课表校验器（1 个类）

```
VersionValidator   两条规则 + 一个常量 DAY_KEYS
```

### 4.1 它在体系里的位置

**只有一个类，但它是"排课结果正确性"的最后一道闸门。**
被调用 **2 处**：

| 谁调 | 什么时候 | 作用 |
|---|---|---|
| `VersionService.publish` | **发布前** | 确保发布出去的课表是合法的 |
| `SwapService.swap` | **每次换课/拖动后** | 确保拖动没有制造冲突 |

类注释解释了它的哲学：

> 「这是"排课结果正确性"的最后一道闸门 —— **求解器可能因为 bug 或数据变化产出非法结果，
> 这里不信任求解器，独立重算一遍。**」

⚠ **"为什么发布前和每次换课后都要跑"** 是两个不同的理由，**都要记住**：

- **发布前**：这是一份**要发给全校看的**课表。`schedule_version_entries` 里的数据可能来自
  求解器（可能有 bug）、可能来自**别的入口**（P1 之后还有 `JobResultWriter` 写结果）、
  也可能被**手工改过库**。发布是**不可逆的对外承诺**，所以在最后关口再扫一遍。
- **换课后**：因为**一次局部拖动可能造成别处的冲突**（§2.3.3⑦ 的例子）。
  拖动的那些"目标/源位置"校验**只看位置、不看教师**，所以只有整表重扫才发现教师撞课。
  ⚠ 而且换课是**交互式**的（用户拖一下、毫秒级返回），如果每次只校验局部，
  一个不合法状态会**在草稿里累积到下一次发布才炸** —— 那时已经很难定位是哪一次拖动引入的。

⚠ **它读的是 academic 属主的 `classes` / `grades` / `teachers` 表**。类注释：
> 「与鉴权同理，**读不违反铁律 2**，但拆库时要么走接口、要么用快照
> （方案 §3 铁律 3「排课输入用快照」正是为此准备的）。」

### 4.2 `DAY_KEYS` 常量（L41）

```java
public static final List<String> DAY_KEYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri");
```

- **对应 Python 的 `DAY_KEYS`，下标即 `day_of_week`**（0=Mon .. 4=Fri）。
- ⚠ **`public`** —— 因为 `SwapService` 也要用（§2.3.5 的 `periods()`）：
  **"星期几的英文键"只有这一份定义**，两处不可能漂移。
- ⚠ **它是 `grades.periods_per_day` 这个 JSON 列的键**（形如 `{"Mon":5,"Tue":5,...,"Fri":4}`），
  **不是给用户看的**（给用户看的是 `ExportService.DAY_NAMES` 的中文）。
- ⚠ **只有 5 天** —— 周六/周日不在系统内。`periods_per_day` 里也可能没有 `"Sat"`。

### 4.3 `validate(ScheduleVersion version)` → `List<ScheduleVersionEntry>`（L59-115）

**返回明细列表**（不是 boolean）—— 见 §4.7。

#### 第 1 段：取明细（L60-62）

```java
List<ScheduleVersionEntry> entries = entryMapper.selectList(
        Wrappers.<ScheduleVersionEntry>lambdaQuery()
                .eq(ScheduleVersionEntry::getVersionId, version.getId()));
return entries;   // 最后
```
⚠ **只按 `versionId` 过滤，没有 `organizationId`**。
`versionId` 已经是全局唯一的主键外键，且调用方都先做过 `requireOwned` 或已持有 `version` 对象 ——
所以安全。⚠ 但与 `VersionService.audit()` 那种"双条件"写法**不一致**，是风格上的小不统一。

#### 第 2 段：把班级与年级装成 id→对象 的映射（L64-73）

```java
Map<Long, ClassRef> classes = new java.util.LinkedHashMap<>();
for (ClassRef item : classRefMapper.selectList(Wrappers.<ClassRef>lambdaQuery()
        .eq(ClassRef::getOrganizationId, version.getOrganizationId()))) {
    classes.put(item.getId(), item);
}
Map<Long, GradeRef> grades = new java.util.LinkedHashMap<>();
for (GradeRef item : gradeRefMapper.selectList(Wrappers.<GradeRef>lambdaQuery()
        .eq(GradeRef::getOrganizationId, version.getOrganizationId()))) {
    grades.put(item.getId(), item);
}
```

- ⚠ **用 `LinkedHashMap` 手工 loop 而不是 `Collectors.toMap`** —— 两个原因：
  ① 需要**确定的插入顺序**（虽然这两个 map 只做查找，顺序不影响结果）；
  ② 避免 `toMap` 在重复键上抛异常（重复 id 不可能，但**别在这里冒险**）。
- ⚠ **不像 `SwapService` 那样先查 id 再逐条 `selectById`** ——
  一次拉全组织（Python 也是这么干的：`{item.id: item for item in db.query(Class)...}`）。
  代价是"每校的班级/年级表各拉一遍"，班级数是几十，可接受。

#### 第 3 段：逐条检查 + 建教师时段表（L82-98）

```java
// key = (teacher_id, day, period) → 该时段被排了课的班级 id 列表
//
// ⚠ 必须是 LinkedHashMap（保持遍历顺序 = 条目的读取顺序），不能用 HashMap：
//    下面"报第一个冲突"的行为依赖遍历顺序。Python 的 dict 是插入序，
//    HashMap 是无序的 —— 结果是两边都正确地拒绝了这次换课，
//    却报了不同的冲突（实测：Python 报"王老师在周1第2节"，Java 报"钱老师在周1第1节"），
//    响应体不一致。同一个输入必须给出同一个答案。
Map<String, List<Long>> teacherSlots = new java.util.LinkedHashMap<>();
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

逐点：

| 点 | 说明 |
|---|---|
| `clazz = classes.get(...)` → `grade = grades.get(clazz.getGradeId())` | **两级查找**：班级查不到 → `grade = null`（走第一条规则报错） |
| `dayKey` 的计算 | `dayOfWeek` 为 null 或**为负**或 ≥5 → `null`。⚠ **这一行就是"负值残留会被拦住"的地方**（`day_of_week = -1` → `dayKey == null` → 抛 409） |
| `periods` 的计算 | ⚠ **`dayKey == null` 时 periods 取 0**（不是抛异常）—— 因为 `null` 键无法查 JSON map。真正的报错交给下面那个 `if` |
| ⚠ **`if` 里的条件顺序** | `grade == null \|\| dayKey == null \|\| periodIndex == null \|\| periodIndex < 0 \|\| periodIndex >= periods`。**短路求值**保证 `periods` 为 0 时不会对 null 做比较。⚠ **把 `periods` 那个条件移到前面** 也不会错（`0` 是个合法 int），但**可读性会变差**，而且一旦有人把 `periods` 改成 `Integer` 就会 NPE |
| ⚠ **`>= periods` 而不是 `> periods`** | `periodIndex` 是 **0-based**，`periods = 5` 表示"第 0..4 节"。所以 `periodIndex == 5` 是非法的。**`ExportService` 里 `maxPeriod = max + 1` 就是同一个 0-based 约定的另一面** |
| 抛出的文案 | `存在超出班级上课时段的课程（记录 N）` —— ⚠ **`N` 是明细行的 `id`**，不是行号。**括号是全角 `（）`，N 前有一个半角空格** —— 这些都要逐字保留 |
| `teacherSlots` 的键 | `"teacherId:day:period"` **字符串**（不是 record/复合键）。⚠ **教师 id 为 null 时会拼成 `"null:0:1"`** —— 不会抛异常，只是把所有"教师为 null"的课当成同一个"教师"聚合。而 `teacher_id` 是 `NOT NULL` 且有外键，所以走不到 |
| `computeIfAbsent(...).add(classId)` | 值是**班级 id 列表** —— 长度为 2 表示"同一教师在同一时段出现在两个班" |

#### 第 4 段：报**第一个**教师冲突（L100-113）

```java
for (Map.Entry<String, List<Long>> slot : teacherSlots.entrySet()) {
    if (slot.getValue().size() <= 1) continue;
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

| 点 | 说明 |
|---|---|
| **遍历 `teacherSlots.entrySet()`** | ⚠ **顺序 = 明细的读取顺序**（因为 `LinkedHashMap`）。**第一个 size>1 的键就抛** —— 所以"报哪一个冲突"完全由**明细的读取顺序**决定 |
| ⚠ **这就是 §5.3 那条坑的落点** | [架构说明.md](架构说明.md) §5.3 末尾：<br>「⚠ 校验器里"报第一个冲突"必须用 `LinkedHashMap`。用 `HashMap` 时**两边都正确地拒绝了换课，却报了不同的冲突**（Python 说"王老师在周1第2节"，Java 说"钱老师在周1第1节"）—— **同一个输入给出不同答案**。踩坑总结 H10。」 |
| **`selectById(teacherId)` 在这里才查** | ⚠ **不是每条明细都查教师**（那会是 N+1）—— 只在**真要报错时**查一次拿名字。这是刻意的：**正常路径（无冲突）一次教师查询都不做** |
| `teacher == null ? "教师" : teacher.getName()` | 对齐 Python 的 `teacher.name if teacher else '教师'`。⚠ 兜底文案是**两个字"教师"**（不是空串） |
| **`day + 1` / `period + 1`** | ⚠ 输出是 **1-based** 的"周N第M节"（给人的），而内部是 0-based。**别把 `+1` 抄丢了** |
| 文案 | `{教师名}在周{day+1}第{period+1}节存在班级冲突` —— 契约里这一条**只有状态码和结构被比对**（文案依赖具体教师名，工具用 `VOLATILE` 类规则处理），但**格式本身要一致** |

#### 4.6 它**不**检查什么（很重要）

`VersionValidator` **只有两条规则**。以下都是它**不管**的（别指望它）：

| 不检查 | 谁管 |
|---|---|
| 教师每周课时上限 | 求解器的规则（`TeacherWeeklyLimit`） |
| 一科一天最多 1 节 / 不连排 | **软约束**（`SubjectMaxPerDay` / `SubjectNoConsecutive`）—— 只罚分，不拒绝 |
| 周课时必须排满 | 硬约束（`SubjectWeeklyHours`），求解器保证 |
| 年级固定课被占用 | **只检查"上课时间范围"** —— 固定课保护只在 `SwapService` 里做 |
| 同一班同一时段有多节课 | **数据库唯一约束**（`uq_version_class_slot`）挡着，根本进不来 |
| 班级/年级/教师/科目是否存在 | 外键挡着 |

⚠ **所以"通过 `validate` ≠ 课表完美"** —— 它只保证"课时不越界 + 教师不撞课"。
这与 Python 的 `_validate_version` **完全一致**（也只有这两条）。

#### 4.7 为什么返回 `List<ScheduleVersionEntry>` 而不是 `void`

| 调用方 | 怎么用返回值 |
|---|---|
| `VersionService.publish` | `List<ScheduleVersionEntry> entries = validator.validate(version);` → **后面重写 `schedules` 时直接遍历它**，省掉一次 4,800 行的查询 |
| `SwapService.swap` | `validator.validate(version);` → **忽略返回值**（换课不需要明细列表） |

⚠ **这不是"顺手加的功能"，是刻意的 API 设计**（Python 的 `_validate_version` 也 `return entries`）：
校验**必然**要把明细读进内存，那就返回给调用方复用。
⚠ **代价**是 `SwapService` 那一行看起来"像没用到结果"—— 别以为它可以删掉
（**调用的副作用是抛异常**）。

---

## 5. `audit/` —— 审计流水（3 个类）

```
ScheduleAuditLog        @TableName(value="schedule_audit_logs", autoResultMap=true)
ScheduleAuditLogMapper  空的 BaseMapper
AuditResponse           ← HTTP 出参（对应 Python ScheduleAuditOut）
```

### 5.1 `ScheduleAuditLog`（103 行）

[ScheduleAuditLog.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/audit/ScheduleAuditLog.java)

| 字段 | 说明 |
|---|---|
| `id` / `organizationId` / `versionId` | 归属 |
| `userId` / `userName` | **谁**。⚠ `userName` 是**冗余存的一份名字快照** —— 用户改名后，历史审计里显示的是**当时的**名字 |
| `action` | 动作字符串（见下） |
| `details` | MySQL **`json`** 列 |
| `createdAt` | 什么时候 |

#### ★ `@TableName(value = "schedule_audit_logs", autoResultMap = true)` + `@TableField(typeHandler = JacksonTypeHandler.class)`

```java
@TableName(value = "schedule_audit_logs", autoResultMap = true)
public class ScheduleAuditLog {
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> details;
```

⚠ **类注释记录的坑，原文如下**：
> 「⚠ `details` 是 MySQL `json` 列 → 需要 `JacksonTypeHandler`，
> 而且**必须配 `autoResultMap = true`**：否则**写入正常、查询出来是 null**
> （typeHandler 只在结果映射启用时生效）。**这个坑在 grades 的 `periods_per_day` 上踩过一次。**」

**为什么"写入正常、查询为 null"**：
- **写**的时候，MyBatis-Plus 会按 `@TableField(typeHandler=...)` 把 `Map` 序列化成 JSON 字符串 —— 这条路径**不依赖** `autoResultMap`。
- **读**的时候，结果集 → 实体的映射用的是 `resultMap`。
  自定义 `typeHandler` **只有放进 `resultMap` 才生效**，而 `autoResultMap = true` 就是
  "让 MyBatis-Plus 自动生成一个带这些 typeHandler 的 resultMap"。
- 不写它：SQL 查得到行、`id`/`action`/`userName` 都有值，**只有 `details` 是 null**
  → `AuditResponse.from` 兜成 `{}`（见下）→ **前端看到"这次操作没有详情"，不报错**。
  ⚠ **这是最难查的一类 bug**：数据在库里，接口不报错，只是悄悄少了一个字段。

**`action` 的取值**（类注释列的 Python 原字符串，**逐字保留**）：

| action | 谁写的 | details |
|---|---|---|
| `generated` | 排课生成（求解器落库那边） | — |
| `updated` | `VersionService.update` | `{"name": 改后的名字}` |
| `copied` | `VersionService.copy` | `{"source_version_id": N}` |
| `published` | `VersionService.publish` | `{"entry_count": N}` |
| `swapped` | `SwapService`（同班互换） | 见 §2.3.3⑦ |
| `cross_swapped` | `SwapService`（跨班协同换时） | 同上 |
| `moved` | `SwapService`（同班纯移动） | 同上 |

⚠ **本包不写任何审计记录** —— 写的地方是 `VersionService` 和 `SwapService`。
`audit/` 只提供**实体 + 读取视图**。

**`details` 的 null（Java）与缺省（Python）**：
- Java 字段是 `Map<String,Object>`，**没有被 `@NotNull` 注解**（DDL 里是 `json NOT NULL`，所以写的时候必须给值）。
- ⚠ `SwapService` 里 `details.put("target_entry_id", null)` —— **Java 允许 Map 的值为 null**，
  存进 JSON 就是 `"target_entry_id": null`。**别改用 `Map.of(...)`**（那里值为 null 会抛 NPE）。

**外键与级联**（[V1 DDL](../backend-java/orbit-scheduling/src/main/resources/db/migration/scheduling/V1__baseline_legacy.sql#L148-L150)）：

```sql
CONSTRAINT schedule_audit_logs_ibfk_2 FOREIGN KEY (version_id) REFERENCES schedule_versions (id) ON DELETE CASCADE
```

⚠ **`ON DELETE CASCADE`** —— 删版本时审计记录**自动跟着删**。
这解释了：① `SystemService.clearSchedules` 里删 `versions` 会连带清掉审计（但它仍然显式删一遍，见 §6.2）；
② **审计链是"按版本"的、不是全组织一条时间线** —— 版本删了，它的历史就没了。
⚠ 另两个外键（`organization_id`、`user_id`）**没有 CASCADE** —— 所以**有审计记录的用户/组织删不掉**。

### 5.2 `ScheduleAuditLogMapper`（9 行）

空的 `BaseMapper`。

### 5.3 `AuditResponse`（16 行）

[AuditResponse.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/audit/AuditResponse.java)

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

⚠ **三个要点**：

1. **只有 5 个字段，没有 `userId`、没有 `versionId`** —— 因为这是"某个版本的审计列表"，
   `versionId` 在同一响应里恒等，`userId` 是内部信息（**Python 的 `ScheduleAuditOut` 也不给**）。
2. **`details` 的 null → `Map.of()`**（不是 null）—— 对齐 Python 的 `details=item.details or {}`。
   ⚠ **`Map.of()` 是不可变空 Map**，Jackson 序列化成 `{}` ✓
   （注意 `Map.of()` 与 `new LinkedHashMap<>()` 输出都是 `{}`，但前者不可变 —— 这里不需要可变）。
3. ⚠ **`details` 里可能会有 null 值**（`target_entry_id: null`）——
   **`Map.of()` 那条分支不会**（它是空的），但**从库里反序列化出来的 `JacksonTypeHandler` 结果会**。
   `JacksonTypeHandler` 用的是 `ObjectMapper.readValue(..., Map.class)`，得到的是
   `LinkedHashMap`（**允许 null 值**）✓ 所以不会 NPE。

---

## 6. `system/` —— `/api/system/*` 运维接口（3 个类）

```
SystemController    /api/system/storage | /storage/schedules | /storage/compact （3 个）
SystemService       业务逻辑（215 行）
SystemStatsMapper   手写 SQL（跨表 UNION + DDL）
```

### 6.1 ★ 为什么这三个接口在 scheduling 而不是 academic

这是一个**架构决策**，`SystemService` 的类注释写得最清楚（**§13.1 原稿把它划给了 academic**）：

> **① 两个写接口操作的 4 张表**（`schedules` / `schedule_audit_logs` /
> `schedule_version_entries` / `schedule_versions`）**全部**是 scheduling 属主
> —— 放 academic 就**违反 §3 铁律 2**（一个表只有一个属主，只有属主能写）。
>
> **② 三条路径共用 `/api/system/storage` 前缀**，拆进程后**无法按路径分流**到两个服务。
>
> 读全部表**不违反**铁律 2（只禁止跨服务**写**），但**为路由一致性整组一起走**。

⚠ 这也是 [架构说明.md](架构说明.md) §6⑧ 列的「**有意偏离**」第 3 条：
「`scheduling_state` 懒初始化 + `/api/system/*` 归 scheduling。**理由：铁律 2**。」

**三个接口都要求管理员**（Python: `Depends(require_admin)`）。

---

### 6.2 `SystemService`（215 行）

[SystemService.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/system/SystemService.java)

#### 常量（L48-60）

```java
public static final String CLEAR_CONFIRM_VALUE = "CLEAR_SCHEDULES";   // Python 的清理确认串，逐字一致

public static final List<String> COUNTED_TABLES = List.of(
        "users", "grades", "classes", "subjects", "teachers",
        "grade_subjects", "teacher_assignments", "fixed_slots", "grade_fixed_slots",
        "schedules", "schedule_versions", "schedule_version_entries", "schedule_audit_logs");

public static final List<String> OPTIMIZABLE_TABLES = List.of(
        "schedule_audit_logs", "schedule_version_entries", "schedule_versions", "schedules",
        "grade_fixed_slots", "fixed_slots", "teacher_assignments", "grade_subjects",
        "teachers", "subjects", "classes", "grades", "users");
```

⚠ **两个列表都是 13 张表，但顺序完全不同 —— 这不是笔误**：

| 列表 | 顺序 | 为什么 |
|---|---|---|
| `COUNTED_TABLES` | users → … → `schedule_audit_logs` | **与 `SystemStatsMapper.tableCounts` 的 UNION 逐行一一对应**（注释明说了）。而且它是 `storage()` 铺 0 的顺序 —— ⚠ 这个顺序**会体现在 JSON 的 `counts` 键顺序里** |
| `OPTIMIZABLE_TABLES` | `schedule_audit_logs` → … → users | 是 Python 的固定列表，**逐字一致**（注释明说了）。⚠ 它是 `OPTIMIZE TABLE` 的执行顺序 —— 从"最会膨胀的"（审计/明细）开始 |

⚠ **两个列表都是 `public`** —— `COUNTED_TABLES` 被 `SystemStatsMapper` 的注释引用（两者必须同步），
`OPTIMIZABLE_TABLES` 是 `optimizeTable` 的**唯一合法入参来源**（白名单）。

#### 构造器（L73-93）

```java
public SystemService(SystemStatsMapper statsMapper,
                     ScheduleStateService stateService,
                     ScheduleEntityMapper scheduleMapper,
                     ScheduleAuditLogMapper auditMapper,
                     ScheduleVersionEntryMapper entryMapper,
                     ScheduleVersionMapper versionMapper,
                     StringRedisTemplate redis,
                     @Value("${MYSQL_HOST:127.0.0.1}") String databaseHost,
                     @Value("${MYSQL_PORT:3306}") int databasePort,
                     @Value("${MYSQL_DATABASE:orbit_schedule}") String databaseName)
```

- 前 7 个是 Bean。
- **后 3 个是 `@Value` 注入的环境变量**（有默认值）：
  `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE`。
  ⚠ **它们是给 `storage()` 拼 `database_path` 字符串用的** ——
  也就是"把连接信息当展示数据"（见下）。
- ⚠ 注意这 3 个都是**构造器参数**（不是字段上的 `@Value`）—— 便于测试时直接 `new`。

#### `storage(long organizationId)` → `Map<String, Object>`（L106-127）

```java
Map<String, Object> result = new LinkedHashMap<>();
result.put("database_type", "mysql");
result.put("database_path", databaseHost + ":" + databasePort + "/" + databaseName);
result.put("database_size_bytes", statsMapper.databaseSizeBytes(databaseName));

Map<String, Long> counts = new LinkedHashMap<>();
for (String table : COUNTED_TABLES) counts.put(table, 0L);          // ← 先铺 0
for (Map<String, Object> row : statsMapper.tableCounts(organizationId)) {
    Object table = row.get("table_name");
    Object count = row.get("row_count");
    if (table instanceof String name && count instanceof Number n) counts.put(name, n.longValue());
}
result.put("counts", counts);
result.put("redis", redisStatus());
return result;
```

逐点（**这个方法的注释是全文最值得读的之一**）：

| 点 | 说明 |
|---|---|
| `LinkedHashMap` × 2 | ⚠ **JSON 的键顺序必须稳定**，否则契约比对会出假差异。顶层是 `database_type` → `database_path` → `database_size_bytes` → `counts` → `redis` |
| ⚠ **`"mysql"` 必须小写** | 注释：<br>「⚠ `database_type` 的值是 **`"mysql"`（小写）** —— Python 取的是 `engine.dialect.name`。写成 `"MySQL"` 会让契约对不上（**实测过**）」 |
| ⚠ **`database_path` 在 MySQL 下装的是"连接描述"** | 注释：<br>「它是 `host:port/db`，**字段名有误导性但不能改**（前端在用）」。<br>⚠ 而且它是**从环境变量拼出来的** —— 只对"默认端口/默认 schema"这种标准部署准确。SQLite 时代它是文件路径，现在语义变了但名字没变 |
| ⚠ **Java 版不支持 SQLite** | 注释：「方案 §4.2 只做 MySQL，所以这两个字段**恒为 MySQL 形态**」。Python 侧如果跑在 SQLite 上会返回 `"sqlite"` + 文件路径 —— **这是有意的偏离** |
| **先铺 0 再覆盖** | 注释：<br>「先按固定表序铺 0，保证**没有行的表也出现在结果里**（Python 的字典也是这样）」。<br>⚠ 不铺 0 的话，`UNION ALL` 的结果里 `COUNT(*) = 0` 的**行仍然会返回**（`COUNT(*)` 无 `HAVING` 时不会消失）—— 所以"铺 0"真正的作用是**保证键顺序**（否则顺序取决于 SQL 引擎的 UNION 顺序，未必稳定） |
| `instanceof String / Number` 收窄 | ⚠ 与 `VersionService.entryCounts` 同一个理由：**不能强转 `Long`**，驱动可能给 `BigInteger` |
| `redis` 是**嵌套对象** | 见 `redisStatus()` |

#### `clearSchedules(long organizationId)` → `Map<String, Object>`（L142-156）

```java
@Transactional
public Map<String, Object> clearSchedules(long organizationId) {
    ensureNotRunning(organizationId);
    long deleted = scheduleMapper.delete(Wrappers.<ScheduleEntity>lambdaQuery()
            .eq(ScheduleEntity::getOrganizationId, organizationId));
    auditMapper.delete(...eq(ScheduleAuditLog::getOrganizationId, organizationId));
    entryMapper.delete(...eq(ScheduleVersionEntry::getOrganizationId, organizationId));
    versionMapper.delete(...eq(ScheduleVersion::getOrganizationId, organizationId));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("deleted", deleted);
    return result;
}
```

★ **删的顺序必须是子表在前**。注释原文：

> 「⚠ 删的顺序**必须**是子表在前：`schedules` 与 `audit_logs` 都引用了 `schedule_versions`...
> 准确说 audit/entries 有 `ON DELETE CASCADE`，但 **`schedules` 没有外键指向版本**。
> Python 的顺序是 `schedules → audit → entries → versions`，这里照做。」

| 顺序 | 表 | 为什么这个位置 |
|---|---|---|
| 1 | `schedules` | ⚠ **没有任何外键指向版本** —— 所以它**不会**被 CASCADE 删掉，必须**自己先删**。放最后的话版本已经没了，它还留着（脏数据） |
| 2 | `schedule_audit_logs` | 有 `ON DELETE CASCADE` 指向版本，但**显式删**保证语义清晰 + 顺序对齐 Python |
| 3 | `schedule_version_entries` | 同上（也有 CASCADE） |
| 4 | `schedule_versions` | **最后**。⚠ 注意它**没有** `DELETE` 条件之外的依赖 —— 但它的三个 FK（org / created_by / published_by）会阻止删除"有版本引用的用户"，那是另一回事 |

- **返回值 `deleted` 是 `schedules` 的行数**（`delete` 的返回值 = 受影响行数）。
  注释：「返回被删掉的 `schedules` 行数（Python 的 `deleted` 就是这个数）」。
  ⚠ **不是四张表的总和，也不含版本数** —— 前端拿它显示"已清除 N 条课表记录"。
- ⚠ **`ensureNotRunning` 在任何删除之前**（§6.2 末）。
- ⚠ **`@Transactional`**：四张表的删除同生共死。如果版本删除因为外键失败，前三个删除也回滚。

#### `compact(SchedulePrincipal principal)` → `Map<String, Object>`（L165-182）

```java
public Map<String, Object> compact(SchedulePrincipal principal) {
    ensureNotRunning(principal.organizationId());
    if (!"platform_admin".equals(principal.role())) {
        throw BusinessException.forbidden("MySQL 全库空间整理仅限平台管理员");
    }
    for (String table : OPTIMIZABLE_TABLES) {
        try {
            statsMapper.optimizeTable(table);
        } catch (Exception e) {
            log.warn("OPTIMIZE TABLE {} 失败: {}", table, e.getMessage());   // ← 单表失败不崩
        }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("database_size_bytes", statsMapper.databaseSizeBytes(databaseName));
    return result;
}
```

| 点 | 说明 |
|---|---|
| **为什么要求平台管理员** | 注释：<br>「⚠ 与 SQLite 的 `VACUUM` 不同，MySQL 这里是**全库级**操作，所以 Python 要求**平台管理员**（普通管理员 → 403 `MySQL 全库空间整理仅限平台管理员`）」<br>⚠ **这句文案与 `SystemController` 的 `仅管理员可以执行此操作` 不同**（§6.4 的 ⚠） |
| ⚠ **`ensureNotRunning` 在权限检查之前** | 也就是说：**排课进行中时，一个非平台管理员会先拿到 409 而不是 403**。这个顺序与 Python 一致吗？⚠ Python 的 `require_admin` 是 **FastAPI 的依赖**（在函数体执行**之前**注入），所以 Python 会**先 403**。**这是一处真实的顺序差异**（仅影响"排课进行中 + 权限不足"这一种叠加场景的状态码：Java 409 / Python 403）。**按代码现状记录。** |
| ⚠ **单表失败只 `log.warn`** | 注释：「单表失败不该让整个操作崩掉（权限、表被锁等都可能），但要留痕」。<br>⚠ **所以 `ok: true` 不表示 13 张表全部成功** —— 它是"操作已执行完"。这是**有意的宽松**（与 Python 的 try/except 一致） |
| **返回最新库大小** | 整理完再查一次 `databaseSizeBytes` —— 让用户看到效果 |
| ⚠ **不回滚** | **没有 `@Transactional`** —— 因为 `OPTIMIZE TABLE` 是 DDL（隐式提交），包在事务里没有意义 |

#### `ensureNotRunning(long organizationId)`（L185-190）——私有

```java
SchedulingState state = stateService.findByOrganization(organizationId);
if (state != null && "running".equals(state.getStatus())) {
    throw BusinessException.conflict("排课进行中，不能执行空间维护");
}
```

- 读 `scheduling_state`（**每组织一行**），`running` 就 409。
- ⚠ **`state != null` 的判断不能省**：`scheduling_state` 是**懒初始化**的
  （[架构说明.md](架构说明.md) §6⑧：「`scheduling_state` 懒初始化」）——
  新组织**还没有行**，`findByOrganization` 返回 null。
- ⚠ **`"running"` 字符串是硬编码的**，与 `ScheduleStateService.finish(...)` 里的一串状态名
  （`idle`/`running`/`done`/`failed`）**没有共享常量**。改状态名时要全局搜。
- 文案：`排课进行中，不能执行空间维护`。

#### `redisStatus()` → `Map<String, Object>`（L193-214）——私有

```java
Map<String, Object> result = new LinkedHashMap<>();
try {
    Map<String, Object> info = redis.execute((RedisConnection connection) -> {
        java.util.Properties properties = connection.serverCommands().info("memory");
        Map<String, Object> map = new LinkedHashMap<>();
        if (properties != null) properties.forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
    });
    Object used = info == null ? null : info.get("used_memory");
    result.put("available", true);
    result.put("used_memory", used instanceof Number n ? n.longValue() : 0L);
    result.put("used_memory_human",
            info == null ? "0B" : String.valueOf(info.getOrDefault("used_memory_human", "0B")));
} catch (Exception e) {
    result.put("available", false);       // ← 只报不可用，不抛
}
return result;
```

| 点 | 说明 |
|---|---|
| **用 `redis.execute(...)` 拿底层连接** | 因为要发 `INFO memory`（StringRedisTemplate 没有现成的 API）。`RedisCallback` 里拿到 `RedisConnection` 后用 `serverCommands().info("memory")` |
| ⚠ **`INFO memory` 返回 `java.util.Properties`** | 不是 Map。⚠ **`Properties` 是 `Hashtable<Object,Object>`**，所以 `forEach` 拿到的是 `Object` —— 必须 `String.valueOf(k)` 转成 String 放进 `Map<String,Object>` |
| ⚠ **`used_memory` 转 `long`** | `Properties` 里全是字符串，Redis 的 `used_memory:1234567` → 值是 `"1234567"`。⚠ **`instanceof Number` 在这里几乎总是 false**（因为它是 String！）→ **`used_memory` 很可能永远是 `0L`**。<br>（此处未完全理解：代码行为是 `used_memory` 取到 `String` 时落回 `0L`。可能依赖某个 Lettuce 版本的 `Properties` 会把数字转成数值类型；也可能是一个**未被发现的 bug**。对照 Python 的 `redis_status()` 才能确定是否等价 —— **建议实测一次 `GET /api/system/storage` 看 `redis.used_memory` 是不是 0**。） |
| ⚠ **`catch (Exception)` 只写 `available: false`** | 注释：<br>「与 Python 一致：Redis 不可用时**只报不可用，不影响整个接口**」。<br>⚠ **注意这个 catch 把 `available: true` 那条路径上的字段全丢了** —— 失败时响应只有 `{"available": false}`，**没有** `used_memory` / `used_memory_human`。<br>⚠ 而契约工具的归一化规则里提到 `used_memory`／`database_size_bytes` 属于 **`VOLATILE_VALUE_KEYS`（"键本身必须存在，只把值换成占位符"）** —— 也就是说**这两个键必须在**。Redis 挂掉时 Java 就不输出这两个键了。**这是一处需要与 Python 核对的边界**（Python 的失败分支是什么形状，要看 `redis_status()` 的源码；本模块看不到 Python 侧的实现文件，故不下结论）。 |

---

### 6.3 `SystemStatsMapper` —— 手写 SQL（65 行）

[SystemStatsMapper.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/system/SystemStatsMapper.java)

**为什么这里手写 SQL 而别处都用 `BaseMapper`**（类注释）：

> 「这个接口的作用就是"**跨表看一眼存储状况**"，需要的是**一张宽表式的汇总**，
> 拆成十几个 Mapper 调用反而更啰嗦、还多十几次往返。Python 那边也是一个循环查了 13 张表。」

#### `databaseSizeBytes(String schema)` → `long`（L27-32）

```java
@Select("""
        SELECT COALESCE(SUM(data_length + index_length), 0)
          FROM information_schema.tables
         WHERE table_schema = #{schema}
        """)
long databaseSizeBytes(@Param("schema") String schema);
```

- `data_length + index_length` = 数据 + 索引。
- ⚠ **`COALESCE(..., 0)` 不能省**：库不存在（或没有任何表）时 `SUM` 返回 `NULL`，
  而 Java 的 `long` **无法接收 null** → `IllegalArgumentException`/NPE。
- ⚠ **`#{schema}` 是占位符（PreparedStatement 参数）**，安全 ✓
  （对比下面的 `optimizeTable` 用 `${}` —— 那里是不得不）。
- ⚠ 用 **`information_schema`** —— MySQL 5.7+/8.0 都有，但**权限不足时可能查不到任何行**
  → 返回 0（不报错）。所以"大小永远是 0"通常是权限问题，不是代码问题。

#### `tableCounts(long organizationId)` → `List<Map<String,Object>>`（L40-55）

```java
@Select("""
        SELECT 'users' AS table_name, COUNT(*) AS row_count FROM users WHERE organization_id = #{orgId}
        UNION ALL SELECT 'grades', COUNT(*) FROM grades WHERE organization_id = #{orgId}
        ... （共 13 张）
        """)
List<Map<String, Object>> tableCounts(@Param("orgId") long organizationId);
```

- ⚠ **注释解释了为什么用一条 `UNION ALL` 而不是循环 13 次**：
  「比循环 13 次查询少 12 次往返，而且拿到的**快照更一致**
  （循环查的话，中间有写入就会得到互相矛盾的数字）」。
  ⚠ 后半句更重要 —— 它说的是**正确性**而不是性能：13 次独立查询之间可能有并发写入，
  于是"版本数 5 / 明细 4800"这种组合可能**从未同时成立过**。
  一条 SQL 在一个一致性读视图里算完 ✓
- ⚠ **13 行必须与 `SystemService.COUNTED_TABLES` 一一对应**
  （`COUNTED_TABLES` 的注释就写着"与 `tableCounts` 的 UNION 一一对应"）。
  **加一张表要同时改两处** —— 没有编译期检查，属于脆弱点。
- ⚠ **`'users' AS table_name` 是字符串字面量** —— 13 个表名硬编码在 SQL 里（不是拼参数）。
  这**正是安全的做法**：表名是常量，用户输入只出现在 `WHERE organization_id = #{orgId}`（参数化）。
- ⚠ 返回 `List<Map<String,Object>>`（不是实体）—— 因为结果形状是动态的（表名 + 计数）。
  MyBatis 的 `@Select` 返回 Map 时，列名是键 → `table_name` / `row_count`
  （**这就是 `SystemService.storage()` 里那两个 `row.get(...)` 的键名来源**）。
- ⚠ 13 张表**全部**有 `organization_id` 列 —— 这是本查询成立的前提。

#### `optimizeTable(String tableName)`（L63-64）

```java
@Update("OPTIMIZE TABLE `${tableName}`")
void optimizeTable(@Param("tableName") String tableName);
```

⚠ **类注释把这条风险写得很直白**：
> 「⚠ 表名用 `${}` 拼接是没办法的事（**DDL 标识符不能参数化**）。
> 调用方**必须**只传 `SystemService.OPTIMIZABLE_TABLES` 里的常量。」

| 点 | 说明 |
|---|---|
| **为什么必须 `${}`** | `OPTIMIZE TABLE ?` 是**语法错误** —— `?` 只能替换**值**，不能替换**标识符**。所以表名只能拼字符串 |
| **`\`` 反引号** | 转义标识符（表名可能撞保留字） |
| ⚠ **调用方只有一处**：`SystemService.compact` 的 `for (String table : OPTIMIZABLE_TABLES)` —— 白名单遍历。**任何"从用户输入取表名"的改动都会变成 SQL 注入** |
| ⚠ **`@Update` 而不是 `@Select`** | 虽然 `OPTIMIZE TABLE` 也返回结果集，但语义上是"维护操作" |
| ⚠ **它是 DDL：会隐式提交当前事务** | 所以 `compact` 里不能有 `@Transactional`（有也会被隐式提交破坏） |

---

### 6.4 `SystemController`（76 行）

[SystemController.java](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/system/SystemController.java)

```java
@RestController
@RequestMapping("/api/system")
public class SystemController {
    private static final String ADMIN_ONLY = "仅管理员可以执行此操作";
```

| 方法 | 路由 | 额外要求 |
|---|---|---|
| `storage(principal)` | `GET /api/system/storage` | 管理员 |
| `clearSchedules(String confirm, principal)` | `DELETE /api/system/storage/schedules?confirm=...` | 管理员 + `confirm` 必须等于 `CLEAR_SCHEDULES` |
| `compact(principal)` | `POST /api/system/storage/compact` | 管理员（Service 里再要求平台管理员） |

#### `requireAdmin(SchedulePrincipal)`（L67-75）

```java
if (principal == null) throw BusinessException.unauthorized("请先登录");
if (!principal.isAdmin()) throw BusinessException.forbidden(ADMIN_ONLY);
return principal;
```

⚠ **它跟 `VersionController.requirePrincipal` 不是一回事**：多了 `isAdmin()` 一关。
⚠ **文案 `仅管理员可以执行此操作` 来自 Python 的 `require_admin`** ——
而 `VersionService.publish` 的权限文案是 `只有管理员可以发布课表`，
`SystemService.compact` 的是 `MySQL 全库空间整理仅限平台管理员`。
**三句不同的文案，各有各的适用场景，别合并。**

#### ★ `clearSchedules(String confirm, ...)` —— 一个状态码陷阱（L51-60）

```java
@DeleteMapping("/storage/schedules")
public Map<String, Object> clearSchedules(
        @RequestParam(name = "confirm") String confirm,      // ⚠ 没有 required = false
        @AuthenticationPrincipal SchedulePrincipal principal) {
    SchedulePrincipal user = requireAdmin(principal);
    if (!SystemService.CLEAR_CONFIRM_VALUE.equals(confirm)) {
        throw BusinessException.badRequest("缺少安全确认参数");
    }
    return systemService.clearSchedules(user.organizationId());
}
```

**注释把这一点讲透了，原文**：
> 「`confirm` 是**必填的查询参数**，且必须等于 `CLEAR_SCHEDULES` —— 一个防误触的门槛。
> **两种情况的状态码不同，别合并**：
> - 参数**没传** → **422**（FastAPI 对必填查询参数的行为，见 `ApiExceptionHandler`）
> - 传了但**值不对** → **400 `缺少安全确认参数`**
>
> 所以这里**不能**写 `required = false` 再自己判空 —— 那样两种情况都会变成 400。」

对应契约用例（[compare.py](../tools/contract-diff/compare.py#L766-L769)）：
- `system 清空课表-缺少确认参数` → **422**
- `system 清空课表-确认参数错误`（`?confirm=WRONG`）→ **400**

⚠ **这是"没传"与"传错"必须走两条路径的又一个实例** ——
与 [代码导读.md](代码导读.md) §1.5 的 `MissingServletRequestParameterException` 是同一件事：
**别把 `@RequestParam` 写成 `required = false` 再自己判空。**

⚠ **`confirm` 的校验在 `requireAdmin` 之后** —— 所以"非管理员 + 没传 confirm"返回的是 **403**（不是 422）。
⚠ 这里又有一处与 Python 的顺序差异：Python 的 `require_admin` 也是 FastAPI 依赖（先执行），
而 `confirm` 在 Python 里是函数签名上的**必填参数** → FastAPI 的**参数校验也先于依赖**吗？
⚠ （此处未完全理解：FastAPI 里依赖与参数校验的执行顺序决定了"非管理员 + 缺 confirm"是 403 还是 422。
**这一组合场景没有被契约用例覆盖，两边的实际行为需要实测才能确定。** 代码现状是 **Java 403**。）

---

## 7. ⚠ 陷阱清单（一次过一遍）

按"踩过 / 反直觉"排列。带 🔴 的是**踩过并有文档记录**的。

| # | 陷阱 | 在哪 | 后果 |
|---|---|---|---|
| 1 | 🔴 **唯一约束在"每条语句"上检查，不是 commit 时** | `SwapService` | 直接 UPDATE 两行 → 第二条语句 `Duplicate entry` → **500**（不是 409） |
| 2 | 🔴 **负值中转的"节"必须用 `-id` 而不是 `-1`** | `SwapService.swapInClass` | 跨班时两次腾挪的中转记录**互相撞** → 500。用 `-2` / `-id` 区分 |
| 3 | 🔴 **校验器的"报第一个冲突"必须用 `LinkedHashMap`** | `VersionValidator` | 两边都拒绝换课、却报**不同的冲突**（Python 说"王老师周1第2节"，Java 说"钱老师周1第1节"）—— **同一输入不同答案**。踩坑总结 H10 |
| 4 | 🔴 **`@TableName(autoResultMap = true)` 不能省** | `ScheduleAuditLog` | `details` **写入正常、查询为 null** —— 不报错、只是悄悄少一个字段 |
| 5 | **`expected_revision`（乐观锁）与 `FOR UPDATE`（行锁）缺一不可** | `SwapService` | 少了乐观锁 → 基于过期视图的拖动被接受；少了行锁 → 两个并发请求交错 |
| 6 | ⚠ **拖动不改 `schedules`** | `swap/` | "我拖了课但家长看到的没变"是**正常**的，要发布（§2.4） |
| 7 | ⚠ **`orderBy` 第二个参数是 `isAsc` 不是 `isDesc`** | `VersionService.list` | 排序整个反过来，**不报错**（[代码导读.md](代码导读.md) §1.2 坑 3） |
| 8 | ⚠ **版本列表默认是 `version_no DESC`** | `VersionService.list` | 照抄 `query.ascending()` 会让"最新的版本"跑到最后（§1.4） |
| 9 | ⚠ **`Objects.equals` 而不是 `!=` 比 `Integer`** | `VersionService` / `SwapService` | `revision > 127` 时 `!=` 永远为真 → **所有请求都 409** |
| 10 | ⚠ **`nextVersionNo` 的竞态（Python 也有，明确不修）** | `VersionService.copy` | 两个管理员同时复制 → 撞唯一约束 → **500** |
| 11 | ⚠ **`schedules` 没有指向版本的外键** | `SystemService.clearSchedules` | 删版本**不会**级联删 `schedules` → 顺序错了会留脏数据（§6.2） |
| 12 | ⚠ **`IN ()` 是非法 SQL** | `ScheduleQueryService.byGrade` | 年级没有班时不早退 → SQL 语法错误 → **500** |
| 13 | ⚠ **`Collectors.toMap` 的键不能为 null，重复键会抛** | `ScheduleQueryService.nameMap` | 少了 `filter` 或合并函数就是 NPE / `IllegalStateException` |
| 14 | 🔴 **POI 写 `""` 会生成单元格，openpyxl 不会** | `ExportService` | 导出的 xlsx **程序读出来 `"" ≠ None`** —— 所以空位必须 `continue`，**不创建单元格** |
| 15 | 🔴 **导出不能比字节** | `ExportService` / `compare_exports.py` | xlsx 是 ZIP，压缩方式/条目顺序/时间戳都不同 → **必须解析成单元格矩阵再比** |
| 16 | ⚠ **POI 的 `createSheet` 重名会抛异常**（openpyxl 会静默改名） | `ExportService` | 两个长班级名截断到 31 字符后相同 → POI **500** |
| 17 | ⚠ **空组织的导出**：Python 留一张 `"Sheet"`，Java 零个 sheet | `ExportService` | 工作表列表不同 → `compare_exports.py` ❌（§3.4 末） |
| 18 | ⚠ **`ExportService.ScheduleRow` 不能叫 `Row`** | `ExportService` | 与 POI 的 `Row` 撞名 → `sheet.createRow()` 的返回值无法赋给 local 变量。**实测踩过一次** |
| 19 | ⚠ **审计的"没传"与"传错"是两种状态码** | `SystemController.clearSchedules` | 合并成 400 → 契约用例红（422 vs 400） |
| 20 | ⚠ **`used_memory` 的类型** | `SystemService.redisStatus` | `INFO` 返回 `Properties`（值全是 String），`instanceof Number` 很可能为假 → **`used_memory` 恒为 0**（§6.2 的"未完全理解"） |
| 21 | ⚠ **`ensureNotRunning` 与权限检查的顺序** | `SystemService.compact` | 排课进行中 + 权限不足时：Java **409** / Python（FastAPI 依赖）预期 **403** —— 已知顺序差异（§6.2） |
| 22 | ⚠ **`byGrade` 里 `requireOwned` 的位置** | `ScheduleQueryService` | "年级没有班 + version_id 不存在"时 Java 空数组 / Python 404（§3.2） |
| 23 | ⚠ **`Map.of(...)` 不接受 null 值** | `SwapService` 的审计 details | `target_entry_id` 可能是 null → 必须用 `LinkedHashMap`（§2.3.3⑦） |
| 24 | ⚠ **审计 `orderByDesc(createdAt)` 没有第二排序键** | `VersionService.audit` | 同一秒内的多条记录顺序不稳定（Python 也一样） |
| 25 | ⚠ **`OPTIMIZE TABLE` 的表名只能 `${}` 拼接** | `SystemStatsMapper` | DDL 标识符不能参数化 → **必须靠白名单**（`OPTIMIZABLE_TABLES`） |
| 26 | ⚠ **`OPTIMIZE TABLE` 是 DDL，会隐式提交** | `SystemService.compact` | 不能包在 `@Transactional` 里（包了也没用） |
| 27 | ⚠ **`COUNTED_TABLES` 与 `tableCounts` 的 UNION 必须同步** | `SystemService` / `SystemStatsMapper` | 加表忘了改另一处 → 计数缺失/多出。**没有编译期检查** |
| 28 | ⚠ **`.last("FOR UPDATE")` / `.last("LIMIT 1")` 是字符串口子** | `SwapService` / `VersionService` | 内容都是常量所以安全；**看到 `.last()` 就确认里面没有变量** |
| 29 | ⚠ **`selectOne` 多行会抛 500**（Python 的 `.first()` 安静取第一行） | 全模块 | 表被手工改坏时两边表现不同 —— 已知不等价 |
| 30 | ⚠ **`@Transactional` 默认只对 `RuntimeException`/`Error` 回滚** | `SwapService` / `VersionService` | 以后抛受检异常时**不会回滚**（必须显式 `rollbackFor`） |

---

## 8. 附录

### 8.1 路由总表（本模块 13 个接口）

| 方法 | 路由 | Controller | Service | 返回 |
|---|---|---|---|---|
| GET | `/api/schedule/versions` | `VersionController.list` | `VersionService.list` | `{items,total}` |
| PATCH | `/api/schedule/versions/{versionId}` | `VersionController.update` | `VersionService.update` | `VersionResponse` |
| POST | `/api/schedule/versions/{versionId}/copy` | `VersionController.copy` | `VersionService.copy` | `VersionResponse` |
| POST | `/api/schedule/versions/{versionId}/publish` | `VersionController.publish` | `VersionService.publish` | `VersionResponse` |
| GET | `/api/schedule/versions/{versionId}/audit` | `VersionController.audit` | `VersionService.audit` | **裸数组** |
| POST | `/api/schedule/versions/{versionId}/swap` | `SwapController.swap` | `SwapService.swap` | `VersionResponse` |
| GET | `/api/schedule/class/{classId}` | `ScheduleQueryController.byClass` | `ScheduleQueryService.byClass` | **裸数组** |
| GET | `/api/schedule/teacher/{teacherId}` | `ScheduleQueryController.byTeacher` | `ScheduleQueryService.byTeacher` | **裸数组** |
| GET | `/api/schedule/grade/{gradeId}` | `ScheduleQueryController.byGrade` | `ScheduleQueryService.byGrade` | **裸数组** |
| GET | `/api/schedule/export` | `ScheduleQueryController.export` | `ExportService.export` | **xlsx 二进制** |
| GET | `/api/system/storage` | `SystemController.storage` | `SystemService.storage` | 对象 |
| DELETE | `/api/system/storage/schedules?confirm=` | `SystemController.clearSchedules` | `SystemService.clearSchedules` | 对象 |
| POST | `/api/system/storage/compact` | `SystemController.compact` | `SystemService.compact` | 对象 |

### 8.2 相关的表与关键约束

| 表 | 属主 | 本模块怎么用 | 关键约束 |
|---|---|---|---|
| `schedule_versions` | scheduling | 本包 CRUD | `uq_schedule_version_org_no (organization_id, version_no)` |
| `schedule_version_entries` | scheduling | 明细：读/写（腾挪） | ★ `uq_version_class_slot (version_id, class_id, day_of_week, period_index)` —— **负值腾挪的存在理由**<br>`ix_version_teacher_slot (...)` 只是 KEY（**不禁止教师撞课**）<br>FK `version_id` → `schedule_versions` **ON DELETE CASCADE** |
| `schedules` | scheduling | **只有 `publish` 写**；`query`/`export` 读 | `uq_schedule_org (organization_id, class_id, day_of_week, period_index)`<br>⚠ **没有指向版本的 FK** |
| `schedule_audit_logs` | scheduling | 写（version/swap）、读（audit） | `details` 是 **`json NOT NULL`**；FK `version_id` **ON DELETE CASCADE** |
| `scheduling_state` | scheduling | `system` 读（`ensureNotRunning`） | `uq_scheduling_state_org` —— **每组织一行**（懒初始化） |
| `classes` / `grades` / `teachers` / `subjects` / `grade_fixed_slots` | **academic** | **只读**（换课/校验/查询/导出都要） | 见 [V1 DDL](../backend-java/orbit-scheduling/src/main/resources/db/migration/scheduling/V1__baseline_legacy.sql) |

### 8.3 契约测试里与本模块相关的用例

| 用例 | 期望 |
|---|---|
| `schedule 版本列表` | `list_endpoint` 模式（Java `{items,total}` vs Python 裸数组，自动补 `size=200`） |
| `schedule 版本列表默认页` | `python_baseline="first_page"` —— 验"默认页确实是 20 条 + total 是全量" |
| `schedule 版本审计` / `-版本不存在` | 200 / **404** |
| `schedule 改名-版本不存在` | 404 |
| `schedule 改名-缺 expected_revision` | **422**（不是 400） |
| `schedule 改名-乐观锁不匹配` | 409 |
| `schedule 改名-空名称` | **400**（不是 422） |
| `schedule 复制-版本不存在` / `发布-版本不存在` | 404 |
| `课表-按班（当前已发布，schedules 表）` | 裸数组 |
| `课表-按班（指定版本）` / `-按教师` / `-按年级` | 裸数组 |
| `课表-版本不存在应 404` | 404 |
| `system/storage 概览` | 200（`used_memory` / `database_size_bytes` 走 `VOLATILE_VALUE_KEYS`） |
| `system 清空课表-缺少确认参数` | **422** |
| `system 清空课表-确认参数错误` | **400** |
| `system 整理空间` | 200 |
| `swap-版本不存在` / `-乐观锁不匹配` / `-要移动的课程不存在` | 404 / 409 / 404 |
| `swap-缺字段` | 422 |
| `swap-目标节次越界` / `-跨年级禁止` | 400 / 400 |
| `export-版本不存在` | 404 |
| **成功的 swap / export** | ⚠ **不在 `compare.py` 里**：swap 会改基线数据（用 snapshot→act→compare→restore 单独验）；export 是二进制（用 [compare_exports.py](../tools/contract-diff/compare_exports.py) 比单元格矩阵） |

### 8.4 与 Python 的对照文件

| Java | Python |
|---|---|
| `version/` + `swap/` + `query/` + `validate/` + `audit/` | [backend/routers/schedule.py](../backend/routers/schedule.py)（562 行） |
| `query/ExportService` | [backend/routers/export.py](../backend/routers/export.py)（77 行） |
| `system/` | Python `routers/system.py` |

### 8.5 本模块"已知的不等价"汇总（照抄现状，不假装没有）

| # | 现象 | 严重度 |
|---|---|---|
| 1 | 空组织导出：Python 有 `Sheet` 表，Java 零 sheet | 中（`compare_exports.py` 会 ❌） |
| 2 | `byGrade` 的 `requireOwned` 位置：年级无班时 Java 空数组 / Python 404 | 低（数据不完整才触发） |
| 3 | `classes.grade_id` 指向不存在的年级：Java 400 / Python 500 | 低（外键保证不发生） |
| 4 | `compact` 的 `ensureNotRunning` 先于权限检查：Java 409 / Python 403 | 低（叠加场景） |
| 5 | `nextVersionNo` 的竞态（**两边都错，等价地错**） | 低 |
| 6 | `schedules` 表的清理归属（`/api/system` 整组归 scheduling） | **有意的偏离**（铁律 2） |
| 7 | `database_type` 恒为 `"mysql"`（Java 不支持 SQLite） | **有意的偏离**（方案 §4.2） |
| 8 | `schedule/versions/{id}/audit` 该分页却返回裸数组 | 中（方案 §13.1 名单与实现不一致） |
| 9 | `selectOne` 多行抛 500（Python 安静取第一行） | 低（表被手工改坏才触发） |
| 10 | POI `createSheet` 重名抛异常（openpyxl 静默改名） | 低 |

---

## 本篇小结

这 6 个包回答的是同一个问题的不同侧面：
**「一版课表从"生成出来"到"发给全校看"，中间要经过哪些关、每道关守什么？」**

| 包 | 它守的东西 |
|---|---|
| `version/` | **版本生命周期**（draft→published→archived）+ **发布是原子的**（校验→归档→重写 `schedules`→转正→审计，五步同生共死） |
| `swap/` | **并发的正确性**：`FOR UPDATE` + `expected_revision` 两把锁；**唯一约束下的腾挪**（负值中转三步，顺序不能换）；**不信任局部校验**（换完整体重跑） |
| `validate/` | **"什么算合法课表"的唯一定义**（两条规则）+ **报第一个冲突必须是确定性的**（`LinkedHashMap`） |
| `query/` | **读的两种语义**（`version_id` 决定查哪张表）+ **有意不分页** + **导出的等价性靠解析后的单元格矩阵，不靠字节** |
| `audit/` | **谁在什么时候改了什么**（`json` 列 + `autoResultMap` 的坑） |
| `system/` | **运维口子**（存储概览 / 清空 / 整理）+ **白名单与状态码的两种路径**（"没传" 422 vs "传错" 400） |

**读这一层的价值**：你以后遇到的**每一个"拖动没生效"**、**每一个"发布失败"**、
**每一个"两份 xlsx 不一样"**、**每一个"审计里少了 details"**，
答案都在这 24 个类里。
