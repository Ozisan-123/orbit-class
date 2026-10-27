# 代码导读 · 4d. 课表查询 / 导出 / 存储运维

> **本册范围**：`query/`（4 个类）+ `system/`（3 个），共 **7 个类**：
> `ScheduleQueryService`、`ScheduleQueryController`、`ScheduleResponse`、`ExportService`、
> `SystemService`、`SystemController`、`SystemStatsMapper`。
>
> 上游：[代码导读.md](代码导读.md)（契约层）· [4c-2](代码导读-4c2-scheduling-版本与审计.md)（版本与审计）

---

## 本模块在系统里的位置

这一册是**"排课算完之后，人怎么看它"** ✓

```
       排课 → 版本 → 发布
                      ↓
              schedules（当前正式课表）
                      ↓
        ┌─────────────┼──────────────┬───────────────┐
        ▼             ▼              ▼               ▼
   按班看        按教师看        按年级看        导出 xlsx
   ─────────────────────────────  ─────────────────────────
        query/ 四个类                    （query/ 的第 4 个类）

   另有 system/ 三个类：看存储状况、清空课表、整理空间（管理员）
```

**两个包的共同点**：它们都**不产生业务数据** ✓ 一个是"只读地展示" ✓ 一个是"运维地清理" ✓
所以本册没有并发控制的戏份 ✗ —— 反而有两个**很有趣的契约细节**（空单元格 ✓ 与 `confirm` 参数的两种状态码 ✓）

## 阅读顺序建议

1. **`ScheduleQueryService`** —— 先看"`version_id` 决定查哪张表"这个设计 ✓ 它是三个接口的共同骨架 ✓
2. **`ExportService`** —— 本册最有意思的一节 ✓（POI vs openpyxl 的**两处行为差异** ✓ 其中一处极隐蔽 ✓）
3. **`SystemController` + `SystemService`** —— 看两处"文案与状态码是契约"的细节 ✓
4. `SystemStatsMapper` 与两个 DTO 扫一眼 ✓

---

## 4d.1 `ScheduleQueryService` —— 三个视角，一张骨架（170 行）

### 🔴 核心设计：`version_id` 决定**查哪张表**

类注释开门见山：

> - **不带** `version_id` → 查 `schedules`（**当前已发布**的课表）
> - **带** `version_id` → 查 `schedule_version_entries`（指定版本的明细）
>
> 所以**同一个接口在"看已发布课表"和"看某版草稿"时读的是不同的表**。

**这个设计值得展开** ✗ —— 它是"用一个可选参数表达两种数据源" ✓

| 请求 | 读的表 | 语义 |
|---|---|---|
| `GET /api/schedule/class/3` | `schedules` | **当前正式课表** ✓ |
| `GET /api/schedule/class/3?version_id=7` | `schedule_version_entries` | **第 7 版**（可能是草稿 ✓） |

**好处**：前端只需要一个接口 ✓ 不传就是"看正式的" ✓ 传了就是"看某个版本" ✓
**代价**：接口内部有两条几乎一样的查询路径 ✗（本类里重复了 3 次 ✓ 见下 ✓）

⚠ **另一种设计**是"发布时把 `schedules` 也当成一个特殊版本"✓ 那样只有一个数据源 ✓
但那样 `schedules` 表就没有存在意义了（P1 的判据是行为等价 ✓ Python 就是这么分的 ✓ 所以照做 ✓）

### 🔴 租户隔离：`requireOwned` 那一步**不能省**

```java
versionService.requireOwned(organizationId, versionId);
return enrich(entryMapper.selectList(...), organizationId);
```

类注释特意写了：

> 带 `version_id` 时会先校验该版本属于本组织（否则 404）——
> 这一步不能省，它是**租户隔离**的一部分：**不加就能用别人的 version_id 读到别人的课表**。

⚠ **注意查询本身也带了 `organizationId` 条件** ✓（`.eq(ScheduleVersionEntry::getOrganizationId, organizationId)` ✓）
—— 所以**即使不调 `requireOwned` 也读不到别人的数据** ✓（查询条件挡住了 ✓）

**那为什么还要调** ✗ —— 两个理由 ✓：
1. **返回 404 而不是空数组** ✓ —— 不调的话会返回 `[]` ✓ 前端无法区分"这个版本没有课"和"这个版本不存在/不归你" ✗
2. **防御性**：如果将来有人改查询时漏了那个 `.eq` ✗ 这行还能兜住 ✓ —— **两层防线** ✓

> 💡 **"两层都有"是刻意的冗余** ✓ —— 和 4c-2 册 `VersionService.audit` 里的做法一模一样 ✓
> （那里也是先 `requireOwned` 再在查询里带 `organizationId` ✓）
> **这种冗余是好事** ✓ 因为它防的是"未来的一次疏忽" ✓ 而不是当前的问题 ✓

### 三个方法（结构完全一样）

```java
public List<ScheduleResponse> byClass(long organizationId, long classId, Long versionId) {
    if (versionId == null) {
        return enrich(scheduleEntityMapper.selectList(Wrappers.<ScheduleEntity>lambdaQuery()
                .eq(ScheduleEntity::getOrganizationId, organizationId)
                .eq(ScheduleEntity::getClassId, classId)), organizationId);
    }
    versionService.requireOwned(organizationId, versionId);
    return enrich(entryMapper.selectList(Wrappers.<ScheduleVersionEntry>lambdaQuery()
            .eq(ScheduleVersionEntry::getOrganizationId, organizationId)
            .eq(ScheduleVersionEntry::getVersionId, versionId)
            .eq(ScheduleVersionEntry::getClassId, classId)), organizationId);
}
```

**三个方法（byClass / byTeacher / byGrade ✓）的骨架一字不差** ✓ 只有**过滤字段**不同：
`.eq(classId)` ✓ / `.eq(teacherId)` ✓ / `.in(classId, 该年级的班级 id)` ✓

**⚠ 同一条分支逻辑重复了 3 遍** ✗（6 段查询 ✓ 每段都要记得带 `organizationId` 和 `requireOwned` ✓）
**更好**：抽一个 `query(organizationId, versionId, Function<Wrapper, ...>)` ✓
**现在的风险**：将来加第四个视角（比如"按科目看"✗）时要**复制第 7 段** ✓ 而漏掉 `organizationId` 就是**跨租户泄漏** ✗

> 💡 **判断该不该抽的法则**：**重复的代码里有没有"漏一行就出安全问题"的部分** ✓
> 这里**有**（`organizationId` ✓）→ **应该抽** ✓
> 如果重复的只是"三个字段名不同" ✗ 那复制反而更清楚 ✓

### `byGrade` 的两步：先取班级 id，再 `IN`

```java
List<Long> classIds = classRefMapper.selectList(Wrappers.<ClassRef>lambdaQuery()
                .select(ClassRef::getId)
                .eq(ClassRef::getOrganizationId, organizationId)
                .eq(ClassRef::getGradeId, gradeId))
        .stream().map(ClassRef::getId).toList();
if (classIds.isEmpty()) {
    return List.of();               // 👈 早退：避免 IN () 这种非法 SQL
}
```

**类注释解释了为什么不用 JOIN** ✓：
> Python 是 `join(Class)` 过滤；这里**先取出该年级的班级 id 再 `IN`** ——
> 效果相同，而且**不用在 Mapper 上写联表**。

⚠ 这是一个真实的取舍 ✓：

| 做法 | 优点 | 代价 |
|---|---|---|
| **JOIN**（Python ✓） | 一次查询 ✓ | 要么写 XML/`@Select` 联表 ✗ 要么用 MyBatis-Plus 不支持的写法 ✗ 破坏"Mapper 保持空接口"的一致性 ✗ |
| **两步 `IN`**（现在 ✓） | 每个 Mapper 都是空 `BaseMapper` ✓ 与全项目一致 ✓ | **多一次往返** ✓ 而且 `IN` 列表可能很长 ✗ |

**`if (classIds.isEmpty()) return List.of();`** ✓ —— 这一行是**必需的** ✗：
空列表会生成 `IN ()` ✓ 而那是**非法 SQL** ✗（MySQL 会报语法错误 ✓）
**早退避免了一次数据库报错** ✓ （MyBatis-Plus 也可能自己处理 ✓ 但显式早退更可靠 ✓）

> 💡 `IN` 列表长度也是个隐患 ✓：一个年级 5 个班无所谓 ✓
> 但整校 160 个班、或将来"按学校看课表"✗ 就可能撞上 MySQL 的 `max_allowed_packet` / 占位符上限 ✓
> 现在**没有分页也没有分批** ✗ 但 160 班 × 30 节 = 4800 行 ✓ 仍在安全区 ✓ **记在这里** ✓

### `enrich` —— 补上名字（26 行）

```java
private <T> List<ScheduleResponse> enrich(List<T> rows, long organizationId) {
    if (rows.isEmpty()) {
        return List.of();
    }
    Map<Long, String> subjects = nameMap(subjectRefMapper.selectList(...), SubjectRef::getId, SubjectRef::getName);
    Map<Long, String> teachers = nameMap(teacherRefMapper.selectList(...), TeacherRef::getId, TeacherRef::getName);
    Map<Long, String> classes  = nameMap(classRefMapper.selectList(...), ClassRef::getId, ClassRef::getName);

    List<ScheduleResponse> result = new java.util.ArrayList<>(rows.size());
    for (T row : rows) {
        ScheduleRow view = ScheduleRow.of(row);
        result.add(new ScheduleResponse(view.id(), view.classId(), view.subjectId(),
                view.teacherId(), view.dayOfWeek(), view.periodIndex(),
                // Python: .get(id, "") —— 找不到时是空串
                subjects.getOrDefault(view.subjectId(), ""),
                teachers.getOrDefault(view.teacherId(), ""),
                classes.getOrDefault(view.classId(), "")));
    }
    return result;
}
```

**三段结构：空则早退 → 建三张名字表 → 逐行拼装** ✓

**⚠ 三张名字表是"全表读进内存"** ✗：

```java
subjectRefMapper.selectList(eq(organizationId, organizationId))     // 该组织的全部科目
```

**每次看课表都会读三张全表** ✗（科目 / 教师 / 班级 ✓）
—— 一次课表 30 行 ✓ 却要为它读 3 张完整表（可能几百行 ✓）

⚠ **这不是 N+1**（只有 3 次查询 ✓ 与行数无关 ✓）—— 已经避免了最坏的情况 ✓
但它是**"每次都重读少量、极少变化的数据"** ✗
**科目/教师/班级的名字一天变不了几次** ✓ 却每次看课表都读一遍 ✓
**加一个短 TTL 的缓存会显著减少查询** ✓ —— 但要注意"改了名字要立刻生效"的期望 ✓（TTL 短就没问题 ✓）

> 💡 **判断法**：**"与本次请求行数无关的固定几次查询"** 和 **"随行数增长的查询"** 要分开看 ✓
> 前者不是 N+1 ✓ 但它可能是**可缓存的** ✓ 后者才是必须消灭的 ✓
> 本类是前者 ✓ 所以优化优先级**低于** 4c-2 册里那两个"逐条 insert" ✗

**三处 `getOrDefault(..., "")`** ✓ —— 这是**第 3 次出现这个模式** ✓：

| 册 | 位置 | Python 原文 |
|---|---|---|
| 4c-2 | `VersionResponse` 的 `description` | `version.description or ""` |
| 4c-2 | `AuditResponse` 的 `details` | `item.details or {}` |
| **4d** | **`ScheduleResponse` 的三个名字** | **`.get(id, "")`** |

**规律**：**Python 里每一个 `or X` / `.get(k, X)` 都要在 Java 里显式对齐** ✓
因为 Python 的"缺省值"是**隐式**的 ✓ 而 Java 的类型系统**不表达缺省** ✗
**所以这类转换点是"翻译工作"的一部分** ✓ 而且**每漏一个都是一个契约差异** ✗

### `ScheduleRow` —— 一个很聪明的适配器

```java
/** 把两种"课表行"适配成同一个视图，供 {@link #enrich} 统一处理。 */
private record ScheduleRow(Long id, Long classId, Long subjectId, Long teacherId,
                           Integer dayOfWeek, Integer periodIndex) {

    static ScheduleRow of(Object row) {
        if (row instanceof ScheduleEntity e) { return new ScheduleRow(e.getId(), ...); }
        if (row instanceof ScheduleVersionEntry e) { return new ScheduleRow(e.getId(), ...); }
        throw BusinessException.badRequest("未知的课表行类型: " + Objects.toString(row));
    }
}
```

**它解决的问题**：`schedules` 和 `schedule_version_entries` 是**两张表、两个实体类** ✗
但它们的**字段完全一样**（id / classId / subjectId / teacherId / dayOfWeek / periodIndex ✓）
→ 如果不适配 ✗ `enrich` 就要写两遍（泛型 `T` 上加不了"有这些 getter"的约束 ✗）

**用 `record` + `instanceof` 模式匹配做适配** ✓ 这是一个**干净且类型安全**的做法 ✓

⚠ **但它引入了一个新的失败点** ✗：
```java
throw BusinessException.badRequest("未知的课表行类型: " + Objects.toString(row));
```
**这行永远不会执行** ✓（因为只有两处调用 ✓ 各自传的都是这两种实体之一 ✓）
—— **死代码** ✗ 但它是**合理的防御**（泛型 `T` 理论上可以是任何类型 ✓ 编译器不保证 ✓）
⚠ 而且它抛的是 **400** ✗ —— 这是个**服务端 bug**（代码传错了类型 ✓）✓ 应该 500 ✓

> 💡 不过这个选择也**有道理**：抛 500 会走"兜底"分支 ✓ 日志里会被记为"未预期的异常" ✓
> 而 400 会带着具体消息返回 ✓ **对开发者来说更显眼** ✓
> 严格说应该抛 `IllegalStateException`（内务错误 ✓）✓ 但那会被 `ApiExceptionHandler` 兜成 500 ✓
> **取舍**：让开发期更容易看见 vs 让语义更正确 ✓ 现在选了前者 ✓

### `nameMap` —— 一个通用的小工具

```java
private static <T> Map<Long, String> nameMap(List<T> rows,
                                             Function<T, Long> idOf,
                                             Function<T, String> nameOf) {
    return rows.stream().filter(r -> idOf.apply(r) != null)
            .collect(Collectors.toMap(idOf, nameOf, (a, b) -> a, HashMap::new));
}
```

**四个参数** ✓ 其中三个值得说：

| 参数 | 作用 | 为什么需要 |
|---|---|---|
| `idOf` / `nameOf` | **函数式取字段** ✗ | 因为三个类型（科目/教师/班级 ✓）没有共同父类 ✓ 只能传函数 ✓ 否则要写三遍 ✓ |
| `.filter(r -> idOf.apply(r) != null)` | **过滤 null 键** | ⚠ `Collectors.toMap` **不允许 null 键** ✗ 不过滤会 **NPE** ✓ |
| `(a, b) -> a` | **合并函数** | ⚠ `Collectors.toMap` 遇到**重复键**会抛 `IllegalStateException` ✗ 有了它才取第一个 ✓ |
| `HashMap::new` | Map 工厂 | ⚠ 不传的话默认也是 `HashMap` ✓ 写了更明确 ✓ |

🔴 **那三个细节（filter / 合并函数 / 工厂）是 `Collectors.toMap` 的三个坑** ✓
**这个工具方法把它们一次性处理掉了** ✓ —— 这是个值得复用的写法 ✓

⚠ **但 `ExportService` 里有一份等价的手写版本** ✗（见 §4d.2 的 `names(...)` ✓）
—— **同一个包里两个"名字表"工具** ✓ 一个用 stream ✓ 一个用 for 循环 ✓
**重复了** ✗ 而且手写版**没有处理 null 键** ✗（`result.put(idOf.apply(row), ...)` ✓ 若 id 为 null 会放进一个 null 键 ✓ 而 `HashMap` 允许 null 键 ✓ → **不会报错，但会多一个 null 键** ✓ —— 后果是 `getOrDefault(subjectId, "")` 永远不会命中它 ✓ 无害 ✓）

---

## 4d.2 `ScheduleResponse` 与 `ScheduleQueryController`

### `ScheduleResponse` —— 13 行，一处契约

```java
public record ScheduleResponse(Long id, Long classId, Long subjectId, Long teacherId,
                               Integer dayOfWeek, Integer periodIndex,
                               String subjectName, String teacherName, String className) { }
```

类注释：
> ⚠ `subject_name` / `teacher_name` / `class_name` 在 Python 里是用 `.get(id, "")` 取的 ——
> **找不到时是空字符串，不是 null**。这个区别对前端是可见的（空串能渲染、null 会显示 undefined），
> 所以这里也用空串。

⚠ **"null 会显示 undefined"这个说法在第 1 册我就指出过不太准** ✗（React 对两者都渲染空 ✓）
**更准确的理由是"契约一致"** ✓ —— Python 输出 `""` ✓ Java 必须也是 `""` ✓ 否则契约测试红 ✓
（真正会出问题的场景：`name.length` ✗、antd 的受控组件 ✗、`JSON.stringify` 的字段类型 ✗）

**这套注释在三个类里重复出现** ✓（`VersionResponse` / `AuditResponse` / `ScheduleResponse` ✓）
**说明作者对这一点有清醒的认识** ✓ 只是理由的表述有点笼统 ✓

### `ScheduleQueryController` —— 3 个裸数组 + 1 个二进制（91 行）

```java
@GetMapping("/class/{classId}")
public List<ScheduleResponse> byClass(@PathVariable Long classId,
        @RequestParam(name = "version_id", required = false) Long versionId, ...)
```

**⚠ 三个接口都返回裸数组、都不分页** ✗ —— 类注释说明了这是**有意的例外** ✓：

> ⚠ 三个都返回**裸数组**，**不分页** —— 这是 §9 明确标注的例外：
> 一张课表天然有上限（一个班一周最多几十行），页面上的表格需要完整数据，
> 而且前端 `Timetable` 组件直接吃数组。**分页在这里只有坏处**。

🔴 **这个"例外"值得单独说** ✓ —— 因为项目里其他列表接口**都**是 `{items, total}` ✓
（第 1 册讲过：`PageResponse` 是 P1 引入的契约变更 ✓）

**判断标准**：**这个列表有没有"天然上限"** ✓

| 接口 | 有上限吗 | 用分页吗 |
|---|---|---|
| `/api/grades` | 有（一个学校几个年级 ✓）hmm ✗ **但仍然用了分页** ✓ | ✓（因为在 `PageResponse` 的统一改造里 ✓） |
| **`/api/schedule/class/{id}`** | **有，且很小**（≤ 40 行 ✓） | ✗ **刻意不用** ✓ |
| `/api/schedule/audit` | ✗ **会随时间无限增长**（4c-2 册指出 ✓） | ✗ **还没分页** ✗ |
| `/api/schedule/jobs` | ✗ 会长大 | ✓ **服务端分页**（5b 册 ✓） |

> 💡 **所以这个项目里其实有三种列表形态** ✓：
> **`{items,total}` 分页** ✓ / **裸数组（有天然上限）** ✓ / **裸数组（但会增长 ✗ 待改）** ✓
> 后两者长得很像 ✗ **只能靠"数据有没有上限"来区分** ✓ 而这个判断**没有写在类型里** ✗
> —— 这是本册记下的一个**结构性观察** ✓

### `export` —— 二进制接口（11 行）

```java
@GetMapping("/export")
public ResponseEntity<byte[]> export(@RequestParam(name = "version_id", required = false) Long versionId, ...) {
    ExportService.ExportFile file = exportService.export(requirePrincipal(principal).organizationId(), versionId);
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, file.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION, file.contentDisposition())
            .contentType(MediaType.parseMediaType(file.contentType()))
            .body(file.content());
}
```

**类注释点出了它与别的接口的不同** ✓：
> ⚠ 它**不是 JSON 接口** —— 返回的是二进制文件，所以不能用 `ApiExceptionHandler` 那套 JSON 错误体来类比，
> 但出错时（版本不存在）仍然返回 JSON 的 404。

⚠ **最后一句话是重点** ✗：**成功的响应是二进制** ✓ 而**失败的响应是 JSON** ✓
**同一个接口两种内容类型** ✗ —— 这是 REST 里常见但容易踩的地方 ✓
（前端如果无脑 `response.json()` ✗ 成功时会解析失败 ✓ 必须看 `Content-Type` 或 `responseType: 'blob'` ✓）

**⚠ 注意 `contentType` 被设置了两遍** ✗：
```java
.header(HttpHeaders.CONTENT_TYPE, file.contentType())   // 第 1 次：作为普通响应头
.contentType(MediaType.parseMediaType(file.contentType()))  // 第 2 次：作为 ResponseEntity 的类型
```
**后者会覆盖前者** ✓ 效果一样 ✓ —— **冗余但没有害处** ✓
（大概是从两处不同来源拼起来的 ✓ 一个想"逐字对齐 Python 的响应头"✓ 一个想"用 Spring 的类型化 API"✓）

⚠ **`file.contentType()` 是字符串** ✗ 所以第 1 次用 `header(...)` 传字符串 ✓ 第 2 次要 `MediaType.parseMediaType(...)` 转 ✓
**如果这个字符串非法** ✗ `parseMediaType` 会抛 `InvalidMediaTypeException` ✓ → 500 ✓
**而它是一个硬编码常量** ✓ 所以不会 ✓

**`Content-Disposition: attachment; filename=schedule.xlsx`** ✓
⚠ 注意**没有** `filename*=UTF-8''...` 的 RFC 5987 形式 ✗ —— 因为文件名是纯 ASCII（`schedule.xlsx` ✓）✓ 不需要 ✓
（如果改成中文文件名（比如"三年二班.xlsx"✗）就必须用 RFC 5987 ✓ 否则浏览器会乱码 ✓ **记在这里** ✓）

### 三个 `requirePrincipal` —— 第四次出现的同一个 4 行块

```java
private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal) {
    if (principal == null) { throw BusinessException.unauthorized("请先登录"); }
    return principal;
}
```

⚠ **这是本册的第 2 份**（`ScheduleQueryController` 一份 ✓ `VersionController` 一份 ✓ `SwapController` 内联一份 ✓ `SystemController` 是带角色检查的变体 ✓）
**同一个 4 行块在 4 个 Controller 里出现了 4 次** ✗

**三种形态** ✗：

| Controller | 形态 |
|---|---|
| `SwapController` | **内联**（`if (principal == null) throw ...` ✓） |
| `VersionController` / `ScheduleQueryController` | **私有静态 `requirePrincipal`** ✓ |
| `SystemController` | **私有静态 `requireAdmin`**（多一层角色检查 ✓） |

**标准做法**：Spring Security 的 `AuthenticationPrincipalArgumentResolver` 可以**扩展到"永远非 null，否则直接 401"** ✓
或者抽一个 `@CurrentUser` 注解 + 一个 `HandlerMethodArgumentResolver` ✓
—— **一次改动能消掉 4 处重复** ✓ 并让"忘了判空"这个错误**不可能发生** ✓

⚠ 现在如果某个新 Controller 忘了判空 ✗ 而那个路径恰好被 `permitAll` 了 ✗ 就是 **NPE → 500** ✓（而不是 401 ✓）
**记录在案** ✓ 这是本册最值得提的结构性重复 ✓

---

## 4d.3 `ExportService` —— 本册最有意思的一节（209 行）

**它把课表导出成 xlsx** ✓（Apache POI ✓）

### 版式（类注释逐项对齐了 Python）

| 项 | 值 |
|---|---|
| 工作表 | **每班一个** ✓ 表名 = 班级名（**截断到 31 字符** ✗ Excel 的硬限制 ✓） |
| 第一行 | `节次 \| 周一 \| 周二 \| 周三 \| 周四 \| 周五` ✓ |
| 之后每天一行 | 行首 `第N节` ✓ 单元格内容 = `科目名\n教师名` ✓ 空位是**不创建单元格** ✓ |
| 行数 | 该班**最大节次 + 1** ✓（各班可不同 ✓ 因为不同年级每天节数不同 ✓） |

### 🔴 有意思的地方：POI 与 openpyxl 的两处行为差异

**差异 ①：新建工作簿时有没有默认工作表**

> openpyxl 新建工作簿会**自带一个空的 `Sheet`**，所以要显式删掉；
> **POI 的 `XSSFWorkbook` 新建时一个工作表都没有**，不用删。

⚠ 这个差异**看起来很小，但会让"第一个表名"出错** ✗：
- openpyxl：不删的话，第一个班的数据会写进那个默认的 `"Sheet"` ✗ **或者**多出一个空表 ✓
- POI：不新建就没有表 ✓
**两边都要按各自的行为处理** ✓ 照搬另一边的代码会出事 ✓

**差异 ②：工作表名重复时的行为**

> openpyxl 的 `create_sheet` 允许先建后改；POI 的 `createSheet(name)` 在工作表名重复时会**抛异常**。
> 这里依赖"班级名在组织内唯一"这个既有约束（`uq_class_org_name`），所以不会撞上。

🔴 **这是一个"依赖数据库约束来保证应用不崩"的例子** ✓ —— 值得想一下 ✓：

| 层 | 保证 |
|---|---|
| 数据库 | `uq_class_org_name` 保证同组织内班级名唯一 ✓ |
| 应用 | 因此 `createSheet(班级名)` 不会重名 ✓ |

⚠ **但有两处缝隙** ✗：
1. **截断到 31 字符之后可能重名** ✗ —— 两个班名"一年级实验班（东校区）"和"一年级实验班（西校区）"✓
   如果它们的前 31 个字符相同 ✗ **截断后就一样了** ✓ → `createSheet` **抛异常** ✗ → 500 ✓
2. **跨组织**：`schedules` 里只有本组织的数据 ✓ 所以不会 ✓

⚠ **缝隙 1 是真实的** ✓ 中文班级名一般很短（"三年二班"✓ 4 个字符 ✓）✓ 所以现在不会触发 ✓
但如果班级名带了长后缀（校区 / 学年 ✗）就可能 ✓ **记在这里** ✓
**更稳的做法**：重名时加后缀（`name`, `name (2)` ✓ —— openpyxl 就是这么做的 ✓）

### 🔴 本册最值钱的一处：空位**不创建单元格**

```java
ScheduleRow found = find(classRows, day, period);
if (found == null) {
    // ⚠ 空位**不创建单元格**，而不是写空字符串。
    //    openpyxl 写 "" 时不会真正生成单元格，读回来是 None；
    //    POI 写 "" 会生成一个空字符串单元格，读回来是 ""。
    //    两者在 Excel 界面里长得一样，但**程序读出来 None ≠ ""** ——
    //    实测就是这么发现差异的。不创建单元格两边才一致。
    continue;
}
Cell cell = excelRow.createCell(day + 1);
cell.setCellValue(subjects.getOrDefault(found.subjectId(), "") + "\n"
        + teachers.getOrDefault(found.teacherId(), ""));
```

🔴 **这是全册最精彩的一处** ✓ —— 它抓到了一个**"肉眼完全相同、程序读出不同"**的差异 ✓

| | openpyxl 写 `""` | POI 写 `""` |
|---|---|---|
| Excel 界面 | 空 ✓ | 空 ✓ |
| **程序读** | **`None`**（单元格不存在 ✓） | **`""`**（单元格存在但内容为空 ✗） |

**两边都"看起来对"** ✓ 只有**解析回去逐格比对**才能发现 ✗
（而 `compare_exports.py` 正是这么做的 ✓ —— 第 1 册讲过：xlsx 是 ZIP ✓ 字节必然不同 ✓ 所以解析成单元格矩阵再比 ✓）

**解法**：**空位两边都不创建单元格** ✓ —— 这样"不存在"这个状态在两边一致 ✓

> 💡 **这个坑的普适性很高** ✗：
> **"空"在不同实现里至少有三种表示** —— **不存在** ✓ **空字符串** ✓ **null** ✓
> 而它们是**可区分的** ✓ 但**在人看来都一样** ✗
> 这与第 1 册 `PageResponse` 的 `items: []` vs 省略字段是**同一族问题** ✓
> 也与 4c-2 册的 `null → ""` 是同一族 ✓（那里是**主动对齐**✓ 这里是**被动发现**✗）
>
> **判据**：**"这个空值会不会被程序读出来"** ✓ 会 → 必须对齐 ✓ 不会（只给人看 ✓）→ 可以不管 ✓

### ⚠ `ScheduleRow` 不能叫 `Row`

```java
/**
 * <p>⚠ 名字**不能**叫 {@code Row}：那样会和 POI 的 {@code org.apache.poi.ss.usermodel.Row}
 * 撞名（同一文件里两个 Row，编译器会挑本类的那个，
 * 于是 {@code sheet.createRow()} 的返回值无法赋给 local 变量）。实测踩过一次。
 */
private record ScheduleRow(long classId, long subjectId, long teacherId,
                           int dayOfWeek, int periodIndex) { }
```

🔴 **这是一个编译期就能发现、但原因很难第一时间想到的坑** ✓
**Java 的"简单名优先"规则**：同一编译单元里，**本类的嵌套类型**会**遮蔽**（shadow ✓）import 进来的类型 ✓
所以如果叫 `Row` ✗ 那 `Row header = sheet.createRow(0);` 就会尝试把 POI 的 `Row` 赋给本类的 `Row` ✗ → **类型不匹配** ✓

⚠ **报错信息会说"Row 不能转换为 Row"** ✗ —— 看起来荒谬 ✓ 只有知道遮蔽规则才能反应过来 ✓
**加后缀（`ScheduleRow` ✓）是最简单的解法** ✓ 而且**顺带表达了语义**（这是"我的行"不是"Excel 的行" ✓）

> 💡 **可推广**：**在文件里定义自己的类型时，先看它会不会和被 import 的类型重名** ✓
> 常见的高危名字：`Row` / `Cell` / `Sheet`（POI ✓）· `Page`（MyBatis-Plus ✓）· `Builder` / `Status` / `Request` ✓
> —— 本项目的 `ScheduleRow`（在 `ScheduleQueryService` 里也有一份 ✓）正是这个命名习惯的产物 ✓

### ⚠ `find(...)` 是线性扫描 —— O(天 × 节 × 行)

```java
private static ScheduleRow find(List<ScheduleRow> rows, int day, int period) {
    for (ScheduleRow row : rows) {
        if (row.dayOfWeek() == day && row.periodIndex() == period) {
            return row;
        }
    }
    return null;
}
```

**它在双层循环里被调用** ✗（`day` 5 × `period` ≤6 ✓ = 最多 30 次 ✓）
每次**遍历该班的全部行** ✗（≤30 ✓）→ **每班约 900 次比较** ✓

**当前规模完全无所谓** ✓（900 次比较是纳秒级 ✓）
⚠ 但它是 **O(格子数 × 行数)** ✗ 如果将来支持"一天 12 节 + 周六"✗ 会变成 6×12×72 ≈ 5000 ✓ 仍无所谓 ✓
**真正的问题**是它**每次都从头上找** ✗ —— 用一个 `Map<(day,period), row>` 就是 O(1) ✓ 代码还更短 ✓

> 💡 不过要注意：**"代码更短"这件事在这里不成立** ✗
> 建 Map 要多写 3 行（key 类型 / 遍历填充 ✓）✓ 而收益在当前规模下是 0 ✓
> **所以现在这样是合理的** ✓ —— 只是要知道它是 O(n²) 的形态 ✓ 在数据变大时第一个该改这里 ✓

### `names(...)` —— 与 `ScheduleQueryService.nameMap` 重复

```java
private static <T> Map<Long, String> names(List<T> rows,
                                           Function<T, Long> idOf,
                                           Function<T, String> nameOf) {
    Map<Long, String> result = new HashMap<>();
    for (T row : rows) {
        result.put(idOf.apply(row), nameOf.apply(row));
    }
    return result;
}
```

**同一个包里两个等价工具** ✗（`nameMap` 用 stream + 处理 null 键 ✓；`names` 用 for + 不处理 ✓）
**重复** ✗ 且行为略有差异（null 键 ✓）✓ 记在这里 ✓

### 导出顺序：`TreeMap` 保证与 Python 的 `sorted(by_class)` 一致

```java
// 按班分组；TreeMap 保证**按 class_id 升序**建表（Python: sorted(by_class)）
Map<Long, List<ScheduleRow>> byClass = new TreeMap<>();
```

⚠ **工作表顺序是契约的一部分** ✗ —— 因为 `compare_exports.py` 会比对工作表名列表 ✓
如果顺序不同 ✗ 要么比不过 ✓ 要么比对工具要先排序 ✓
**`TreeMap` 的 key 是 `Long`（class_id ✓）→ 自然按数值升序 ✓** 与 Python 的 `sorted()` ✓ 一致 ✓

⚠ 而**每个工作表内部的行顺序**由 `period` 循环保证 ✓（1..maxPeriod ✓）✓

---

## 4d.4 `SystemService` / `SystemController` / `SystemStatsMapper`

### 🔴 `SystemService` 的类注释 —— 一处**公开的、有理有据的"偏离方案"**

```java
/**
 * <p><b>为什么这组接口在 scheduling 而不是 academic</b>（§13.1 原稿把它划给 academic）：
 * <ol>
 *   <li>两个写接口操作的 4 张表（schedules / schedule_audit_logs /
 *       schedule_version_entries / schedule_versions）**全部**是 scheduling 属主 ——
 *       放 academic 就违反 §3 铁律 2</li>
 *   <li>三条路径共用 {@code /api/system/storage} 前缀，拆进程后**无法按路径分流**到两个服务</li>
 * </ol>
 * 读全部表不违反铁律 2（只禁止跨服务**写**），但为路由一致性整组一起走。
 */
```

🔴 **这是全项目最好的一段"偏离说明"** ✓ —— 它具备三个要素 ✓：
1. **指出原稿怎么写的** ✓（"§13.1 原稿把它划给 academic" ✓）
2. **给出两条独立理由** ✓（铁律 2 ✓ + 路由可分流性 ✓）
3. **说明代价与取舍** ✓（"读全部表不违反铁律 2……但为路由一致性整组一起走" ✓）

**第 2 条理由尤其值得一提** ✗ —— 它是一个**部署期**的约束 ✓ 而不是业务约束 ✓：

```
开发期没有真网关，Vite 按路径前缀分流：
    /api/schedule/** → 8083
    /api/system/**   → ？（原稿想给 8081）
但 /api/system/storage 前缀下同时有"读"和"写"两类接口 ✗
    GET    /api/system/storage            ← 只读，随便哪个服务都行
    DELETE /api/system/storage/schedules  ← 写 4 张 scheduling 的表 ✗
    POST   /api/system/storage/compact    ← 全库 DDL ✗
→ 前缀相同的接口**不能拆到两个服务** ✗（Vite 只能按前缀分流 ✓）
```

**所以"路由前缀"在这里变成了一个架构约束** ✓ —— 这个洞察很实用 ✓
**（真正的网关可以按方法+路径分流 ✓ 但开发期的 Vite 不行 ✓ 而方案选的是"整组一起走"这个更简单的解 ✓）**

### `storage` —— 存储概览

```java
public Map<String, Object> storage(long organizationId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("database_type", "mysql");
    result.put("database_path", databaseHost + ":" + databasePort + "/" + databaseName);
    result.put("database_size_bytes", statsMapper.databaseSizeBytes(databaseName));

    Map<String, Long> counts = new LinkedHashMap<>();
    // 先按固定表序铺 0，保证**没有行的表也出现在结果里**（Python 的字典也是这样）
    for (String table : COUNTED_TABLES) {
        counts.put(table, 0L);
    }
    for (Map<String, Object> row : statsMapper.tableCounts(organizationId)) {
        Object table = row.get("table_name");
        Object count = row.get("row_count");
        if (table instanceof String name && count instanceof Number n) {
            counts.put(name, n.longValue());
        }
    }
    result.put("counts", counts);
    result.put("redis", redisStatus());
    return result;
}
```

**三处值得说**：

**① `database_type` 必须是 `"mysql"`（小写）** ✓
注释：「Python 取的是 `engine.dialect.name`。写成 `"MySQL"` 会让契约对不上（**实测过**）」✓
—— 一个**大小写**上的契约 ✓ 而它**没有任何编译期保护** ✗

**② `database_path` 装的是连接描述** ✓
注释：「在 MySQL 下装的其实是**连接描述**（`host:port/db`），**字段名有误导性但不能改**（前端在用）」✓
—— **字段名是历史包袱** ✓ 但改它要同步改前端 ✓ 所以留着 + 写注释 ✓ **正确的处理** ✓

**③ 🔴 "先铺 0 再覆盖" 这个顺序很关键** ✓

```java
for (String table : COUNTED_TABLES) { counts.put(table, 0L); }   // 先全部铺 0
for (Map<String, Object> row : statsMapper.tableCounts(...)) { counts.put(name, n); }  // 再覆盖
```

**目的**（注释 ✓）：「保证**没有行的表也出现在结果里**」✓
⚠ 如果反过来（先查再补 0 ✗）就需要"查完之后找出没出现的表"✓ 更啰嗦 ✓

🔴 **但它带来一个隐藏的协同要求** ✗：
`COUNTED_TABLES`（Java 里的列表 ✓）与 `SystemStatsMapper.tableCounts` 的 **`UNION ALL` 13 个分支** ✗
**必须一一对应** ✓ —— 而这一点**只有注释在维护** ✓：

```java
/** 统计行数的表（与 {@link SystemStatsMapper#tableCounts} 的 UNION 一一对应）。 */
public static final List<String> COUNTED_TABLES = List.of(...13 张表...);
```

🔴 **如果有人在 SQL 里加了一张表但忘了加到 `COUNTED_TABLES`** ✗：
- SQL 会返回它 ✓
- 但 `counts` 里**没有它的初始 0** ✓
- `counts.put(name, n)` **会加进去** ✓ → **结果里会出现它** ✓ ✓
（顺序会排在最后 ✗ 因为 `LinkedHashMap` ✓ —— **顺序变了** ✗ 而顺序可能被契约测试比对 ✗）

⚠ **反过来**（加到 `COUNTED_TABLES` 但忘了加到 SQL ✗）：
- `counts` 里会有它，值是 **0** ✓
- **看起来完全合理** ✗ —— 一张空表本来就是 0 ✓
- **而且不会报错** ✓ → **这就是最危险的方向** ✗

> 💡 **两个方向的后果完全不同** ✓：
> 一个"顺序变了" ✓（浅层差异 ✓ 契约测试能抓 ✓）
> 一个"静默显示 0" ✗（**看起来对** ✗ 只有人肉核对才会发现 ✓）
> **所以这个"列表 ↔ SQL"的对应关系值得用程序保证** ✓（比如让 SQL 从一个常量生成 ✗ 或者加一个断言测试 ✓）
> **现在只有注释** ✓ 记在案 ✓

### `clearSchedules` —— 删四张表

```java
@Transactional
public Map<String, Object> clearSchedules(long organizationId) {
    ensureNotRunning(organizationId);
    long deleted = scheduleMapper.delete(...);   // schedules
    auditMapper.delete(...);                     // schedule_audit_logs
    entryMapper.delete(...);                     // schedule_version_entries
    versionMapper.delete(...);                   // schedule_versions
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    result.put("deleted", deleted);
    return result;
}
```

**删的顺序** ✓ —— 注释说得很细 ✓：
> ⚠ 删的顺序**必须**是子表在前：`schedules` 与 `audit_logs` 都引用了 `schedule_versions`...
> 准确说 audit/entries 有 `ON DELETE CASCADE`，但 `schedules` **没有外键**指向版本。
> Python 的顺序是 schedules → audit → entries → versions，这里照做。

🔴 **注意注释里的自我修正** ✓：「准确说……」✓
—— 作者先写了一个笼统的理由（"都引用了"✗）然后**自己纠正**了 ✓
**这种"写下来再核实"的痕迹很有价值** ✓ —— 它告诉读者"我查过了" ✓ 而不是"我猜的" ✓

⚠ **而且注释里出现了一个省略号（"引用了 `schedule_versions`..." ✓）** ✗ —— 句子没写完 ✓
小瑕疵 ✓ 但恰好在这个"自我修正"的位置 ✗ 说明改的时候没回头读整句 ✓

**`ensureNotRunning` 放在最前面** ✓：
```java
private void ensureNotRunning(long organizationId) {
    SchedulingState state = stateService.findByOrganization(organizationId);
    if (state != null && "running".equals(state.getStatus())) {
        throw BusinessException.conflict("排课进行中，不能执行空间维护");
    }
}
```
⚠ **文案是"空间维护"** ✗ 但它**也被 `clearSchedules` 用** ✓ 而清空课表不是"空间维护" ✓
—— **与 4c-1 册那个"协同换时"的文案错配是同一族问题** ✗（同一句文案服务了两个不同场景 ✓）
**这次更可接受** ✓（"空间维护"比"清空课表"宽泛 ✓ 用户不至于看不懂 ✓）**但仍是同一类** ✓

⚠ **`state.getStatus()` 是 `"running"` 时拒绝** ✓ —— 但 P3 之后"running"这个对外状态**由任务队列维护** ✓
（5b 册讲过：三条终态路径都要收尾它 ✓）✓ 所以这个检查**依赖那三条路径都没漏** ✓
如果某条漏了 ✗ 状态会永远停在 `running` ✗ → **清空课表永远被拒** ✗（症状与 4c-2 的"发不起新排课"同源 ✓）

### `compact` —— `OPTIMIZE TABLE`

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
            log.warn("OPTIMIZE TABLE {} 失败: {}", table, e.getMessage());
        }
    }
    ...
}
```

**四个要点**：

| 点 | 说明 |
|---|---|
| **要求平台管理员** ✓ | 注释解释了：**MySQL 的 `OPTIMIZE TABLE` 是全库级操作** ✓ 与 SQLite 的 `VACUUM` 不同 ✓ 所以权限更高 ✓ |
| **逐表 try/catch** ✓ | 注释：「单表失败不该让整个操作崩掉（权限、表被锁等都可能），但要留痕」✓ —— **宽进严出但要留证据** ✓ |
| ⚠ **`ensureNotRunning` 在权限检查之前** ✗ | 顺序问题：非平台管理员能得到 **409「排课进行中」** 而不是 **403** ✗ → **泄露了"是否正在排课"这个信息** ✓ <br>（对照 4c-2 的 `publish`：那里是**权限先于存在性** ✓ —— **两个类的顺序相反** ✗ 见下） |
| ⚠ **没有返回每张表的结果** ✗ | 只有 `ok: true` + 新的大小 ✓ —— 失败的被静默记进日志 ✓ 用户看不到"哪张表没整理成" ✓ |

🔴 **`ensureNotRunning` 与权限检查的顺序，与 4c-2 的 `publish` 正好相反** ✗：

| | `publish`（4c-2 ✓） | `compact`（本册 ✓） |
|---|---|---|
| 先检查 | **权限** ✓ | **系统状态** ✓ |
| 后检查 | 存在性 ✓ | 权限 ✓ |
| 后果 | 非管理员**无法探测**任何信息 ✓ | 非平台管理员能**探测到"是否正在排课"** ✓ |

**哪个对** ✗ —— 通用原则是「**权限先于一切**」✓（`publish` 那种 ✓）
所以 `compact` 这里**应该调换两行** ✓
⚠ 但危害很小（"是否正在排课"不是敏感信息 ✓ 而且这条接口本来就要管理员 ✓）
**记为"顺序不一致，建议统一为权限优先"** ✓

### `redisStatus` —— 降级但不报错

```java
private Map<String, Object> redisStatus() {
    Map<String, Object> result = new LinkedHashMap<>();
    try {
        Map<String, Object> info = redis.execute((RedisConnection connection) -> {
            java.util.Properties properties = connection.serverCommands().info("memory");
            ...
        });
        Object used = info == null ? null : info.get("used_memory");
        result.put("available", true);
        result.put("used_memory", used instanceof Number n ? n.longValue() : 0L);
        result.put("used_memory_human", ...);
    } catch (Exception e) {
        // 与 Python 一致：Redis 不可用时只报不可用，不影响整个接口
        result.put("available", false);
    }
    return result;
}
```

**三点**：
- **`redis.execute(RedisCallback)`** ✓ 直接拿原生连接 ✓ 因为 `INFO memory` 不在 `StringRedisTemplate` 的 API 里 ✓
- ⚠ **`catch (Exception)` 吞掉但不打日志** ✗ —— 注释说"与 Python 一致" ✓ 但**日志都没有** ✗
  后果：Redis 一直连不上 ✗ 运维只看 `/api/system/storage` 会看到 `available: false` ✓ 但**日志里没有任何痕迹** ✗
  **建议至少 `log.debug`** ✓ （对比 `compact` 里的循环 ✓ 那里就打日志了 ✓ —— **两处风格不一致** ✗）
- **`available: false` 时 `used_memory` 字段根本不出现** ✗（因为 `catch` 里只 put 了 `available` ✓）
  → **响应结构在"可用 vs 不可用"时不同** ✗ 前端读 `data.redis.used_memory` 会是 `undefined` ✓
  ⚠ 与 Python 是否一致**未核对** ✓ —— 但从"Python 的 `redis_status()` 也这么写"的注释看，大概是 ✓

### `SystemStatsMapper` —— 手写 SQL（65 行）

```java
/**
 * <p>为什么用**手写 SQL** 而不是复用各领域的 Mapper：
 * 这个接口的作用就是"跨表看一眼存储状况"，需要的是**一张宽表式的汇总**，
 * 拆成十几个 Mapper 调用反而更啰嗦、还多十几次往返。
 * Python 那边也是一个循环查了 13 张表。
 *
 * <p>⚠ 所有表名都来自**代码里的固定列表**（{@link SystemService#COUNTED_TABLES}），
 * 绝不接受用户输入 —— {@code OPTIMIZE TABLE} 的表名无法用占位符参数化
 * （它是 DDL，不是值），所以只能拼字符串，那就必须保证拼进去的是白名单里的常量。
 */
```

**三个方法**：

| 方法 | 做什么 |
|---|---|
| `databaseSizeBytes(schema)` | `SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = #{schema}` ✓ |
| `tableCounts(orgId)` | **一条 13 分支的 `UNION ALL`** ✓ 一次拿齐所有表的行数 ✓ |
| `optimizeTable(tableName)` | `OPTIMIZE TABLE \`${tableName}\`` ✓ |

#### 🔴 `${}` vs `#{}` —— 全项目唯一一处字符串插值

```java
@Update("OPTIMIZE TABLE `${tableName}`")
void optimizeTable(@Param("tableName") String tableName);
```

⚠ **`${}` 是字符串**直接拼接**进 SQL** ✗ —— 这正是 SQL 注入的经典入口 ✓
而 `#{}` 是**占位符参数化** ✓ 安全 ✓

**为什么这里必须用 `${}`** ✓ 注释解释了 ✓：
> `OPTIMIZE TABLE` 的表名无法用占位符参数化（**它是 DDL，不是值**）✓

🔴 **这个解释是对的** ✓ —— SQL 的参数化只能用于**值**（`WHERE x = ?` ✓）
**表名 / 列名是标识符** ✗ 参数化后 SQL 解析器不认 ✓（`OPTIMIZE TABLE ?` 是语法错误 ✓）

**所以唯一的防线是"调用方只传白名单里的常量"** ✓ —— 而这一点**靠注释约束** ✗
⚠ **代码上没有任何强制** ✗：`optimizeTable(String)` 接受**任意字符串** ✓
如果将来有人在别处调它并传了用户输入 ✗ 就是**远程 DDL 注入** ✓

**可以强化的做法**（都很简单 ✓）：
- 方法签名改成 `optimizeTable(OptimizableTable table)` 用**枚举** ✓ → 编译期就限死了 ✓
- 或者在方法里**再校验一次**白名单 ✓（`if (!OPTIMIZABLE_TABLES.contains(name)) throw ...` ✓）

> 💡 **这是本项目里唯一一处 `${}`** ✓ —— 值得记住它的位置和理由 ✓
> **审计一个项目里的 SQL 注入面，第一步就是搜 `${`** ✓ 而这里搜到的**唯一一处是有理的** ✓
> 但"有理"不等于"有防护" ✗ —— **它现在的防护是"调用方自觉"** ✓ 记在案 ✓

#### `tableCounts` 用 `UNION ALL` 的两个理由

注释给了两个 ✓：
> 用一条 `UNION ALL` 拿全部表 —— 比循环 13 次查询**少 12 次往返**，
> 而且拿到的**快照更一致**（循环查的话，中间有写入就会得到互相矛盾的数字）。

🔴 **第二个理由比第一个更有价值** ✓ —— "**一致的快照**" ✗：
循环查询时，如果查到第 5 张表时有别的请求写入了第 2 张表 ✗
→ 结果里"第 2 张表的行数"和"第 5 张表的行数"**不属于同一时刻** ✗
**对"存储概览"这种要给人看的汇总数字，一致性比性能更重要** ✓

⚠ 但严格说 `UNION ALL` 的一条语句在 MySQL 里**也不保证 Repeatable Read 下的完全一致** ✗
（取决于隔离级别与是否在同一事务里 ✓ 这里**没有 `@Transactional`** ✗）
—— 所以"更一致"是**相对的** ✓ 不是绝对的 ✓ **记在这里** ✓

---

## 本册小结

**把这一册串成一句话**：

> `query/` 是"**排课算完之后人怎么看它**" ✓ 三个视角共用一条骨架 ✓
> 而 `version_id` 这个可选参数**决定了读哪张表**（正式课表 or 某个版本 ✓）；
> `system/` 是"**存储层面的读与清**" ✓ 其中 `OPTIMIZE TABLE` 是全库操作所以要求平台管理员 ✓

**最该带走的三件事**：

1. 🔴 **"空"有三种表示，必须对齐**（§4d.3）✓
   openpyxl 写 `""` → 读回 **`None`**；POI 写 `""` → 读回 **`""`** ✗
   **Excel 界面里长得完全一样** ✗ 只有程序读得出来 ✓
   解法：**两边都不创建单元格** ✓ —— 这是"用同一个不存在的状态"来保证一致 ✓
2. **`${}` 表名拼接是没办法的事，但要用白名单兜住**（§4d.4 `optimizeTable` ✓）
   DDL 的标识符**不能参数化** ✓ 所以只能拼 ✓ 而"唯一防线是调用方自觉"这件事**应该写进代码而不是注释** ✓
3. **"列表 ↔ SQL"这类对应关系只有注释在维护时，危险方向是"静默显式 0"**（§4d.4 ✓）
   加到 Java 列表但忘加到 SQL → 结果里显示 **0** ✓ **看起来完全合理** ✗ 只有人肉核对才发现 ✓

### 本册发现的问题（6 处，都没动代码）

| # | 问题 | 性质 |
|---|---|---|
| **1** | 🔴 **`requirePrincipal` 这个 4 行块在 4 个 Controller 里重复了 4 次**，而且有**三种写法**（内联 / 方法 / 带角色 ✓） | ⚠ 结构性重复 ✓ 漏判空会让 NPE 变成 500 而不是 401 ✓ <br>**标准解法**：扩展 `AuthenticationPrincipalArgumentResolver` 或加 `@CurrentUser` 注解 ✓ 一次改动消掉 4 处 ✓ |
| **2** | ⚠ **`compact` 的 `ensureNotRunning` 在权限检查之前** | 非平台管理员会得到 409 而不是 403 ✗ → **泄露"是否正在排课"** ✓ <br>与 4c-2 的 `publish`（**权限先于一切** ✓）顺序相反 ✓ 建议统一 ✓ |
| **3** | ⚠ **`COUNTED_TABLES` 与 `UNION ALL` 的 13 个分支只靠注释对应** | 加到列表忘加到 SQL → **静默显示 0** ✗（看起来合理 ✓ 只有人肉核对能发现 ✓） |
| **4** | **`redisStatus` 吞掉异常且不打日志**（而同类的 `compact` 循环**打了日志** ✗） | 两处风格不一致 ✓ Redis 长期不可用时日志里没有任何痕迹 ✓ 建议至少 `log.debug` ✓ |
| **5** | **同一个包两个等价的"名字表"工具**（`ScheduleQueryService.nameMap` ✓ 用 stream + 处理 null 键；`ExportService.names` ✓ 用 for + 不处理 ✓） | 重复 ✓ 且行为略有差异 ✓ |
| **6** | `ScheduleRow.of` 的兜底分支抛 **400** 而不是 500，且是**死代码** | ⚠ 服务端内务错误抛 400 语义不准 ✓ 但作者可能刻意选它（**开发期更容易看见** ✓）✓ 取舍合理 ✓ 只是没写理由 ✓ |

**另外记下 3 处"结构性观察"**：
- **项目的列表接口有三种形态**：`{items,total}` 分页 ✓ / 裸数组（有天然上限 ✓）/ **裸数组（会增长但还没分页 ✗）** ✓
  后两者长得一样 ✗ 只能靠"数据有没有上限"区分 ✓ **而这个判断没写在类型里** ✗
- **`ExportService` 依赖数据库约束 `uq_class_org_name` 来避免 `createSheet` 抛异常** ✓
  ⚠ 但**截断到 31 字符之后可能重名** ✗（两个班名的前 31 字符相同 ✓）→ 500 ✓ 记在案 ✓
- **`/export` 成功返回二进制、失败返回 JSON** ✗ —— 同一接口两种内容类型 ✓
  前端必须按 `Content-Type` 分支（或 `responseType: 'blob'` ✓）✓ 否则成功时会解析失败 ✓
