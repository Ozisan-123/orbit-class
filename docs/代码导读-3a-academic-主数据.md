# Orbit 代码导读 3a —— `orbit-academic` 教务主数据（逐类逐方法）

> **与《代码导读》的分工**：
> [代码导读.md](代码导读.md)（第一篇）讲的是 `orbit-common` 契约层 —— 「两个服务要怎么表现才算同一个系统」。
> **本篇讲 `orbit-academic` 里"教务主数据"这一半**：
> 「年级/班级/科目/教师/任教关系/周课时/固定课时这些接口，每一行代码到底在干什么」。
>
> 认证登录、组织/工作空间、`/api/system/*`、以及 `orbit-common` 里的东西**不在本篇**，是另一篇（3b）。

---

## 0. 动笔说明（先说清取舍，避免"为什么这个类只有一张表"的疑问）

### 0.1 覆盖范围与**降级为表格**的部分

本模块共 **80 个 `.java` 文件**，本篇覆盖其中 **48 个**：

| 分类 | 数量 | 本篇的处理方式 |
|---|---|---|
| Controller | 7 | **逐方法 + 逐行** |
| Service | 8 | **逐方法 + 逐行**（重点：入参校验 → 查库 → 组装 → 返回） |
| Mapper | 11 | **逐个讲**（含"为什么它里面一行自定义 SQL 都没有"） |
| Entity | 9 | 逐字段 |
| **Request / Response（record）** | **25** | ⚠ **降级为表格批量说明**（见 §1.4），只对**带校验注解**、**与 Python 默认值相关**、**有非显然语义**的字段额外展开 |

**为什么 DTO 降级**：它们是纯数据形状，一个 record 往往只有 1–15 行、除了字段声明没有逻辑。
真正容易出错的地方（默认值、`@NotNull`、范围校验、JSON 字段名）在 §1.4 的表格里逐列标出，
比给 25 个 record 各写一段"它是个 record"更有信息量。
**但**：**每一个 DTO 的每一个字段**都在 §1.4 的表格里出现过，没有一个字段被跳过。

### 0.2 本篇收录的 48 个类（按包）

```
grade/        7  GradeController  GradeService  Grade  GradeMapper
                 GradeResponse  GradeCreateRequest  GradeUpdateRequest
clazz/        7  ClassController  ClassService  Clazz  ClassMapper
                 ClassResponse  ClassCreateRequest  ClassUpdateRequest
subject/     12  SubjectController  SubjectService  Subject  SubjectMapper
                 SubjectResponse  SubjectCreateRequest
                 GradeSubjectController  GradeSubjectService  GradeSubject  GradeSubjectMapper
                 GradeSubjectResponse  GradeSubjectCreateRequest  GradeSubjectBulkRequest
teacher/      7  TeacherController  TeacherService  Teacher  TeacherMapper
                 TeacherResponse  TeacherCreateRequest  TeacherUpdateRequest
assignment/   9  AssignmentController  AssignmentService  TeacherAssignment
                 TeacherAssignmentMapper  AssignmentResponse  AssignmentCreateRequest
                 AssignmentAutoRequest  AssignmentAutoResponse  TeacherClassAssignmentsRequest
fixedslot/    6  GradeFixedSlotController  GradeFixedSlotService  GradeFixedSlot
                 GradeFixedSlotMapper  GradeFixedSlotResponse  GradeFixedSlotCreateRequest
                 FixedSlot  FixedSlotMapper          ← 注意固定课是 2 个实体 / 8 个类
                 （上两行计入 8 个，见 §1.3 的说明）
```

> 实际计数：7 + 7 + 12 + 7 + 9 + 8 = **50**。其中 `GradeFixedSlot*` 6 个 + `FixedSlot*` 2 个 = 8，
> 所以 `fixedslot` 包是 8 个类，合计 **50 个**。
> （`docs/架构说明.md` §3.2 说模块共 78 个类，口径与本篇逐文件计数略有出入，以逐文件为准。）

### 0.3 被**明确排除**的类（属于 3b 篇）

| 包 | 类 | 为什么排除 |
|---|---|---|
| `auth` | `AuthController` `LoginAttemptGuard` `JwtAuthenticationFilter` `OrbitUserDetailsService` `OrbitUserPrincipal` `PythonPbkdf2PasswordEncoder` `LoginRequest` `LoginResponse` `PasswordChangeRequest` | 认证登录 |
| `user` | `User` `UserMapper` `UserResponse` `UserCreateRequest` `UserUpdateRequest` `UserService` `UserController` | 账号管理 |
| `organization` | `Organization` `OrganizationMapper` `OrganizationResponse` `OrganizationCreateRequest` `OrganizationService` `OrganizationController` | 组织/工作空间 |
| `health` | `HealthController` | 运维 |
| `config` | `SecurityConfig` `JwtConfig` `MybatisPlusConfig` | 基础设施（`MybatisPlusConfig` 与 `OrganizationMapper.lockById` 只在被本篇的类**调用到时**才提及，见 §3.5、§6.5） |
| — | `AcademicApplication` | 启动类 |

### 0.4 引用证据的来源

本篇所有"与 Python 行为对齐"的断言，都来自**实际读过的** Python 源码，不是推测：

| Python 文件 | 对应本篇 |
|---|---|
| [backend/routers/grades.py](../backend/routers/grades.py) | §2 |
| [backend/routers/classes.py](../backend/routers/classes.py) | §3 |
| [backend/routers/subjects.py](../backend/routers/subjects.py) | §4 |
| [backend/routers/teachers.py](../backend/routers/teachers.py) | §5 |
| [backend/routers/assignments.py](../backend/routers/assignments.py) | §6、§7 |
| [backend/routers/fixed_slots.py](../backend/routers/fixed_slots.py) | §8 |
| [backend/schemas.py](../backend/schemas.py) | §1.4 |

契约用例见 [tools/contract-diff/compare.py](../tools/contract-diff/compare.py) 的 `CASES` 列表。
**本篇标"实测"的地方，都是那个工具的 CASES 里真有用例的**（我会把用例名一起写出来）。

---

## 1. 本模块在系统里的位置

### 1.1 一句话定位

`orbit-academic`（:8081）是「**这所学校长什么样**」的唯一权威：
组织、账号、年级、班级、科目、教师、周课时、任教关系、固定课。
**它不理解排课**，也不知道 `job_token` / 租约 / CP-SAT 是什么。

```
                   浏览器
                      │
              Vite 按路径分流
        ┌─────────────┴──────────────┐
        │ /api/schedule/**           │ 其余 /api/**
        │ /api/rules/** 等           │
        ▼                            ▼
  orbit-scheduling :8083      orbit-academic :8081   ← 本篇
        │                      ┌──────────────────────────┐
        │  只读 SQL（不写）      │ grade  clazz  subject    │
        └─────────────────────►│ teacher  assignment      │
                               │ fixedslot                │
                               └──────────────────────────┘
```

**两条铁律直接决定了本篇代码的写法**（原文见 [架构说明.md](架构说明.md) §1）：

1. **禁止跨服务写库** —— 每个表只有一个属主服务。所以：
   - 删班级时要连任教关系、固定课一起删，但**不能**去删 `schedules`（见表主是 scheduling）；
   - 删年级时**不自己动** `classes` / `grade_subjects` 的表，而是**调对方的 Service**
     （`ClassService.deleteAllInGrade` / `SubjectService.deleteGradeSubjectsByGrade`）。
2. **排课输入用快照** —— scheduling 会**只读**地拉走本篇这 7 张表
   （`grades` `classes` `subjects` `teachers` `grade_subjects` `teacher_assignments` `grade_fixed_slots`）。
   所以本篇**任何字段语义的改动都会变成排课输入的变化** ——
   `max_weekly_hours`、`weekly_hours`、`periods_per_day` 不是普通字段，是求解器的约束参数。

### 1.2 URL → Controller → Service 对照表（本篇全部 24 个接口）

| 方法 | 路径 | Controller 方法 | Service 方法 | Python |
|---|---|---|---|---|
| GET | `/api/grades` | `GradeController.list` | `GradeService.list` | `grades.py:11` |
| POST | `/api/grades` | `.create` | `.create` | `grades.py:15` |
| PUT | `/api/grades/{gradeId}` | `.update` | `.update` | `grades.py:24` |
| DELETE | `/api/grades/{gradeId}` | `.delete` | `.delete` | `grades.py:37` |
| GET | `/api/classes` | `ClassController.list` | `ClassService.list` | `classes.py:12` |
| POST | `/api/classes` | `.create` | `.create` | `classes.py:22` |
| PUT | `/api/classes/{classId}` | `.update` | `.update` | `classes.py:32` |
| DELETE | `/api/classes/{classId}` | `.delete` | `.delete` | `classes.py:47` |
| GET | `/api/subjects` | `SubjectController.list` | `SubjectService.list` | `subjects.py:11` |
| POST | `/api/subjects` | `.create` | `.create` | `subjects.py:16` |
| DELETE | `/api/subjects/{subjectId}` | `.delete` | `.delete` | `subjects.py:47` |
| GET | `/api/grade-subjects` | `GradeSubjectController.list` | `GradeSubjectService.list` | `assignments.py:63` |
| POST | `/api/grade-subjects` | `.upsert` | `.upsert` | `assignments.py:77` |
| POST | `/api/grade-subjects/bulk` | `.bulk` | `.bulkUpdate` | `assignments.py:260` |
| DELETE | `/api/grade-subjects/{id}` | `.delete` | `.delete` | `assignments.py:300` |
| GET | `/api/teachers` | `TeacherController.list` | `TeacherService.list` | `teachers.py:11` |
| POST | `/api/teachers` | `.create` | `.create` | `teachers.py:22` |
| PUT | `/api/teachers/{teacherId}` | `.update` | `.update` | `teachers.py:34` |
| DELETE | `/api/teachers/{teacherId}` | `.delete` | `.delete` | `teachers.py:54` |
| GET | `/api/assignments` | `AssignmentController.list` | `AssignmentService.list`（**内存分页**） | `assignments.py:14` |
| POST | `/api/assignments` | `.create` | `.create` | `assignments.py:44` |
| DELETE | `/api/assignments/{id}` | `.delete` | `.delete` | `assignments.py:35` |
| POST | `/api/assignments/auto` | `.autoAssign` | `.autoAssign` | `assignments.py:104` |
| PUT | `/api/assignments/teacher/{teacherId}` | `.setTeacherClasses` | `.setTeacherClasses` | `assignments.py:197` |
| GET | `/api/fixed-slots/by-grade` | `GradeFixedSlotController.list` | `GradeFixedSlotService.list` | `fixed_slots.py:14` |
| POST | `/api/fixed-slots/by-grade` | `.create` | `.create` | `fixed_slots.py:26` |
| DELETE | `/api/fixed-slots/by-grade/{slotId}` | `.delete` | `.delete` | `fixed_slots.py:103` |

（27 行 —— 因为 `grade-subjects` 的 4 个接口在 Python 里和 `assignments` 同文件，但在 Java 里归 `subject` 包。）

### 1.3 包归属规则（**为什么 `GradeSubject` 在 `subject` 包、`FixedSlot` 在 `fixedslot` 包**）

这三条规则解释了本篇所有的"文件放在哪儿"：

| 规则 | 落点 |
|---|---|
| **表的属主决定包** | `grade_subjects` 表达的是「**某科目**在某年级的周课时」，属主是**科目**领域 → 实体/Mapper/Service 都放 `subject/` |
| **但有 2 个例外由历史决定** | `fixed_slots`（**班级级**固定课）虽然和 `grade_fixed_slots` 同属 academic，但**当前没有任何 Controller/Service 写它** —— 只有 `ClassService.deleteOwnedChildren` 会删它。所以它只有 `FixedSlot` + `FixedSlotMapper` 两个类，**没有 Service、没有 Controller、没有 DTO**。这不是遗漏，是「旧表（方案 §5.1 标注为"（旧）"）暂时没有对外接口」 |
| **类名撞 Java 关键字 → 加 z** | `Class` → `Clazz`，包名 `clazz`（`clazz/Clazz.java:10-11` 的注释写明了） |

### 1.4 全部 25 个 Request/Response DTO 一览（**降级说明：本节用表格代替逐类展开**）

#### 1.4.1 五个"新建"请求 —— 注意**哪些字段是必填、默认值落在哪里**

| DTO | JSON 字段 | Java 类型 | 校验注解 | Python 默认值 | 默认值在哪落实 |
|---|---|---|---|---|---|
| `GradeCreateRequest` | `name` | `String` | `@NotNull("Field required")` | 必填 | — |
| | `periods_per_day` | `Map<String,Integer>` | — | `{"Mon":6,...,"Fri":6}` | ⚠ **`GradeService.DEFAULT_PERIODS_PER_DAY`（Service 层）**，不在 DTO |
| `ClassCreateRequest` | `name` | `String` | `@NotNull` | 必填 | — |
| | `grade_id` | `Long` | `@NotNull` | 必填 | ⚠ **`Long` 不能写 `long`** —— 缺字段时 Spring 传 null |
| `SubjectCreateRequest` | `name` | `String` | `@NotNull` | 必填 | — |
| | `category` | `String` | — | `"minor"` | ⚠ `SubjectService.create` 里 `null \|\| isBlank()` → `"minor"` |
| | `weekly_hours_by_grade` | `Map<Long,Integer>` | — | `{}` | Service 里 `!= null && !isEmpty()` 才处理 |
| `TeacherCreateRequest` | `name` | `String` | `@NotNull` | 必填 | — |
| | `subject_id` | `Long` | `@NotNull` | 必填 | — |
| | `max_weekly_hours` | `Integer` | — | `18` | ⚠ `TeacherService.DEFAULT_MAX_WEEKLY_HOURS = 18`（Service 层） |
| `GradeSubjectCreateRequest` | `grade_id` | `Long` | `@NotNull` | 必填 | — |
| | `subject_id` | `Long` | `@NotNull` | 必填 | — |
| | `weekly_hours` | `Integer` | `@NotNull` | 必填 | ⚠ **没有范围校验**（Python `GradeSubjectCreate` 也没有） |
| `AssignmentCreateRequest` | `teacher_id` / `class_id` / `subject_id` | 三个 `Long` | 全部 `@NotNull` | 三个都必填 | — |
| `GradeFixedSlotCreateRequest` | `grade_id` / `subject_id` / `day_of_week` / `period_index` | 四个 `Long`/`Integer` | 全部 `@NotNull` | 四个都必填 | — |

> **为什么默认值统一放在 Service 而不是 DTO 上**：`GradeCreateRequest` 的类注释写明
> 「不放在 DTO 上，避免**默认值散落两处**」。两个地方都写作默认值，改一处漏一处 =
> 线上出现两套默认值，且只有部分路径走错的那一套。

#### 1.4.2 四个"修改"请求 —— **全部是 PATCH 语义（虽然路径是 PUT）**

| DTO | 字段 | 语义 | ⚠ |
|---|---|---|---|
| `GradeUpdateRequest` | `name` `periods_per_day` | 两个都可选，**只改传了的** | `GradeUpdateRequest.java:8` 明说：路径是 PUT 但语义是 PATCH，**前端只传 `name` 时 `periods_per_day` 必须原样保留** |
| `ClassUpdateRequest` | `name` `grade_id` | 同上 | 同上 |
| `TeacherUpdateRequest` | `name` `subject_id` `max_weekly_hours` | 同上 | 同上 |
| `TeacherClassAssignmentsRequest` | `class_ids` | `@NotNull List<Long>` | ⚠ **空列表 ≠ 不改动** —— 空列表表示「清空该教师的所有班级」。见 §6.5 |

> **⚠ 为什么不能用"构造部分实体 + `updateById`"实现 PATCH**：
> `GradeService.update` 的注释写得很清楚 —— MyBatis-Plus 的 `updateById` **只更新非 null 字段**，
> 所以它**无法区分**「把 `periods_per_day` 改成 null」与「不改它」，
> 而 Python 的 `if data.periods_per_day is not None:` 是能区分的。
> 所以必须先 `selectOne` 查出整行、在内存里改、再 `updateById`。**8 个 Service 的 update 全是这个形状。**

#### 1.4.3 其余请求 DTO

| DTO | 字段 | 说明 |
|---|---|---|
| `AssignmentAutoRequest` | `replace_existing: Boolean`、`subject_ids: List<Long>` | 两个都可省：`false` / `[]`（**空列表 = 不限定科目**，不是"一个都不选"）。⚠ Controller 上**刻意不加 `@Valid`**，因为整个 body 都可省（`@RequestBody(required=false)`） |
| `GradeSubjectBulkRequest` | `subject_id: Long`、`items: List<Item>` | `@NotNull` + `@Valid`（**嵌套校验必须靠 `@Valid` 递归下去**，漏了就完全不生效） |
| `GradeSubjectBulkRequest.Item` | `grade_id: Long`、`weekly_hours: Integer` | ⚠ **`weekly_hours` 带 `@Min(0)` / `@Max(40)`** —— 见下面的专段 |

**⚠ `weekly_hours` 的 0..40 是一个"必须落在 422 上"的校验**（`GradeSubjectBulkRequest.java:23-25`）：

```
Python:  weekly_hours: int = Field(ge=0, le=40)   →  Pydantic 校验 → 422
Java:    @Min(0) @Max(40)                          →  Jakarta 校验（@Valid）→ 422
```

两边**必须都是 422**（不是 400）—— 因为超范围在 Python 里是**请求校验**，不是业务规则。
`@Min/@Max` 正好落在 Spring 的 `MethodArgumentNotValidException` 路径上，由
`ApiExceptionHandler.handleBodyValidation` 转 422（见 [代码导读.md](代码导读.md) §1.5）。
**契约用例**：`grade-subjects 批量课时 41（超上限）`（expect 422）、
`grade-subjects 批量课时 -1（超下限）`（expect 422）—— 这两个用例存在本身就说明它是被测过的契约点。

#### 1.4.4 六个响应 DTO（全部是 record，字段即 JSON 键）

| DTO | 字段 | ⚠ 关键点 |
|---|---|---|
| `GradeResponse` | `id` `name` `periods_per_day` | `from(Grade)` 静态工厂；**不返回 `organization_id`**（Python `GradeOut` 也没有） |
| `ClassResponse` | `id` `name` `grade_id` `grade_name` | `grade_name` 是**联出来的**（不在 `classes` 表里），Python 用 `joinedload(Class.grade)`；这里用**批量查一次年级**避免 N+1 |
| `SubjectResponse` | `id` `name` `category` | 只有三个字段，`SubjectResponse.java:9` 说明**不返回 `organization_id`**（当前用户就在这个组织里，返回是冗余的） |
| `GradeSubjectResponse` | `id` `grade_id` `subject_id` `weekly_hours` `subject_name` | `subject_name` 同样是联出来的 |
| `TeacherResponse` | `id` `name` `subject_id` `max_weekly_hours` `subject_name` | ⚠ `max_weekly_hours` 是**可空**的（Python 里 nullable，见 §5.3 的实测差异） |
| `AssignmentResponse` | `id` `teacher_id` `class_id` `subject_id` + `teacher_name` `class_name` `subject_name` | 三个名字都是联出来的；Python 用 `joinedload(teacher, class_)` + **每行再查一次 Subject**（N+1），Java 改成了三次批量查 |
| `AssignmentAutoResponse` | `assigned_count` `preserved_count` `unassigned: List<String>` `teacher_loads: Map<String,Integer>` | ⚠ `teacher_loads` 的 key 是**教师姓名**（Python 原样如此，人看的报表）→ **同名教师会互相覆盖**，`AssignmentAutoResponse.java:11` 注明「既有行为，不在 P1 修正」 |

> **字段名怎么变成 snake_case**：靠 `application.yml:58` 的
> `spring.jackson.property-naming-strategy: SNAKE_CASE` **一处配置**解决全部字段命名，
> 不在每个 DTO 上贴 `@JsonProperty`。⚠ 但报错位置（422 的 `loc`）也要用同一个策略 ——
> `ApiExceptionHandler` 刻意复用 Jackson 的 `PROPERTY_NAMING`，两者不会漂移。

### 1.5 阅读顺序建议

本篇按**依赖方向**排（后面的类会用到前面的表）：

```
第 1 遍（主干，必须逐行读）
  §2 grade      ← 最简单的一套五件套，先在这里把"模板"看懂
  §3 clazz      ← 引入"跨领域调用"（grade）与"批量取名"
  §4 subject    ← 引入"新增时顺带写子表 + 事务"
  §5 teacher    ← 与 §3/§4 同构，可快读
  §6 assignment ← 全模块最重的两个算法接口
  §7 subject/GradeSubject ← upsert + 批量（0 = 删除）
  §8 fixedslot  ← 全项目校验最多的写接口（5 道关卡）

第 2 遍（横向）
  §9 行为对齐清单       ← 24 个接口与 Python 的逐条差异
  §10 已知偏离与未解问题
```

**如果只有 20 分钟**：读 §2.2（Service 的"模板"）、§6.3（自动分配算法）、§8.2（固定课 5 道关卡）、§9。

### 1.6 骨架：所有 Service 都长这样

看懂一个，其余 7 个的 80% 就懂了。**下面这个模板在本篇被重复了 24 次**：

```java
// ① 列表：租户过滤 → 关键字 → 白名单排序 → 真分页 → 批量补名 → 包 {items,total}
public PageResponse<XResponse> list(long organizationId, [过滤参数,] PageQuery query) {
    LambdaQueryWrapper<X> wrapper = Wrappers.<X>lambdaQuery()
            .eq(X::getOrganizationId, organizationId);        // 租户隔离，显式写
    if (query.hasKeyword()) wrapper.like(X::getName, query.keyword());
    String column = query.orderColumn(SORTABLE.keySet(), "id");  // 白名单校验，非法→400
    wrapper.orderBy(true, query.ascending(), SORTABLE.get(column)); // ⚠ 第二参是 isAsc
    Page<X> page = xMapper.selectPage(new Page<>(query.pageNumber(), query.pageSize()), wrapper);
    return PageResponse.of(/* 批量补名 */, page.getTotal());
}

// ② 写：校验归属（404 文案逐字对齐）→ 建实体 → insert → 用"联出来的名字"组装响应
@Transactional
public XResponse create(long organizationId, XCreateRequest request) {
    requireOwned(organizationId, request.xxxId());   // 不属于本组织 → 404
    X x = new X();
    x.setOrganizationId(organizationId);
    ...
    xMapper.insert(x);
    return XResponse.of(x, relatedName(x.getXxxId()));   // ⚠ insert 后 id 已回填（IdType.AUTO）
}

// ③ 改：**先查再改**（PATCH 语义，见 §1.4.2）→ 校验新引用 → updateById → 组装响应
@Transactional
public XResponse update(long organizationId, long id, XUpdateRequest request) {
    X x = requireOwned(organizationId, id);
    if (request.name() != null) x.setName(request.name());
    ...
    xMapper.updateById(x);
    return XResponse.of(x, relatedName(x.getXxxId()));
}

// ④ 查单个：**两个条件一起写**（id + organization_id），查不到 404
public X requireOwned(long organizationId, long id) {
    X x = xMapper.selectOne(Wrappers.<X>lambdaQuery()
            .eq(X::getId, id).eq(X::getOrganizationId, organizationId));  // ⚠ 两个条件缺一不可
    if (x == null) throw BusinessException.notFound("XXX不存在");          // 文案是契约
    return x;
}
```

> ⚠ **`requireOwned` 里的两个 `eq` 缺一不可**。只写 `eq(getId, id)` 的后果不是"多查了些数据"，
> 而是 **A 学校可以改/删 B 学校的行** —— 一个学校看到另一个学校的数据是严重事故，
> 见 `SubjectService.java:25-31` 的说明：**租户隔离是显式写的，不靠 MyBatis-Plus 租户插件自动拼条件**，
> 因为拦截器一旦被绕过（写原生 SQL、或用了没走拦截器的 API）就**静默失去隔离**；
> 显式写在每个查询里，**代码评审时看得见**。

---

## 2. `grade` 包 —— 年级（4 个接口，7 个类）

**定位**：`grades` 表的 CRUD。年级是**层级最顶端的主数据**（班级挂在年级下、年级课时挂在年级上），
所以它的 `delete` 是唯一一个**要连带清理两个其他领域**的接口。

### 2.1 `GradeController`（63 行，4 个接口）

**一句话定位**：`/api/grades/*` 的 HTTP 入口。**只做三件事**：取参数、调 Service、返回。
**谁调它**：前端 `frontend/src/api/index.ts:104-107` 的 `getGrades/createGrade/updateGrade/deleteGrade`；
契约用例 `grades 列表`/`grades 新建缺字段`/`grades 修改不存在` 等。

```java
@RestController
@RequestMapping("/api/grades")
public class GradeController {
    private final GradeService gradeService;
    public GradeController(GradeService gradeService) { this.gradeService = gradeService; }
```

**逐行**：
- `@RestController` = `@Controller` + `@ResponseBody`（返回值直接序列化成 JSON，不走视图解析）。
- 构造器注入（不是 `@Autowired` 字段注入）：字段 `final`，**依赖不可能在运行中途被换掉**，
  也保证"这个类需要什么"在构造签名上一眼可见。
- **类上没有任何 `@Validated` / 权限注解** —— 因为这一整套接口**只要登录**，
  不要管理员（Python 侧也是 `Depends(get_current_user)`，不是 `require_admin`）。
  权限在 `SecurityConfig` 里统一要求 `authenticated()`（见 `config/SecurityConfig.java:118`）。

#### `list(PageQuery query, @AuthenticationPrincipal OrbitUserPrincipal principal)` → `GET /api/grades`

| 项 | 内容 |
|---|---|
| **逐行** | `return gradeService.list(requirePrincipal(principal).organizationId(), query);` —— **一行**：把「当前用户的组织 id」和「分页参数」交给 Service |
| **谁调它** | 前端 `getGrades()`；契约用例 `grades 列表`、`grades 列表+关键字（Java 新增）` |
| ⚠ **`PageQuery` 没有注解** | 它是 record，Spring 把它当 `@ModelAttribute` 做**构造器绑定**。**不能写 `@RequestBody`**（查询参数不在 body 里）。它的紧凑构造器负责归一化（page≤0→1、size≤0→20、上限 200、order 只认 desc） |
| ⚠ **`@AuthenticationPrincipal` 可能为 null** | 授权层已保证 authenticated，但**异常场景下确实可能为 null**（`SubjectController.java:70-77` 的注释）。那时应该给 **401**，而不是让 NPE 变成 500 |
| **返回** | `PageResponse<GradeResponse>` → `{"items":[...],"total":N}` |

#### `create(@Valid @RequestBody GradeCreateRequest, ...)` → `POST /api/grades`

- **逐行**：`@Valid` 触发 Jakarta 校验（`name` 缺失 → 422，走 `handleBodyValidation`）；
  然后 `gradeService.create(orgId, request)`。
- ⚠ **HTTP 状态码是 200 不是 201**：Python 的 `@router.post` 没写 `status_code`，
  FastAPI 默认 **200**；Spring 的 `@PostMapping` 默认也是 200 → **两边一致，不用额外处理**。
  `SubjectController.java:29-32` 把这个"默认值恰好相同"专门记了一笔：
  **将来如果有接口用了 201，就必须显式写 `@ResponseStatus`**。

#### `update(@PathVariable Long gradeId, @Valid @RequestBody GradeUpdateRequest, ...)` → `PUT /api/grades/{gradeId}`

- **逐行**：路径变量 `gradeId` → Service 的第二个参数。**没有**「参数是不是本组织的」判断 ——
  那个判断在 Service 的 `requireOwned` 里（**Controller 里一行业务逻辑都没有**）。

#### `delete(@PathVariable Long gradeId, ...)` → `DELETE /api/grades/{gradeId}`

- **逐行**：调 `gradeService.delete(...)`（**返回值是 void**），然后 `return Map.of("ok", true);`。
- ⚠ **响应体形状是 `{"ok":true}`**，Python 是 `return {"ok": True}` —— 逐字对齐。
  用 `Map.of` 而不是定义个 DTO：这是唯一的形状，为它造一个 record 不划算。

#### `private static OrbitUserPrincipal requirePrincipal(OrbitUserPrincipal principal)`

```java
if (principal == null) throw BusinessException.unauthorized("请先登录");
return principal;
```

- **为什么每个 Controller 都有一份这个私有方法**：`SubjectController.java:74-76` 的注释解释了 ——
  这段小检查**每个 Controller 都要有**，「与其复制，不如放在 `OrbitUserPrincipal` 的静态方法里」。
  ⚠ **但它实际上没有被抽出去** —— 7 个 Controller 各自复制了一份**逐字相同**的私有静态方法。
  这是一处**已知的重复**（不影响行为，但改文案要改 7 处）。见 §10。

### 2.2 `GradeService`（116 行，5 个公开方法）

**一句话定位**：年级的业务逻辑 + **跨领域的删除编排**。
**谁调它**：`GradeController`（4 个接口）+ **`ClassService` / `GradeSubjectService` 等通过 `GradeMapper` 查它**（不是通过 Service）。

```java
public static final Map<String, Integer> DEFAULT_PERIODS_PER_DAY = Map.of(
        "Mon", 6, "Tue", 6, "Wed", 6, "Thu", 6, "Fri", 6);

private static final Map<String, SFunction<Grade, ?>> SORTABLE = Map.of(
        "id", Grade::getId, "name", Grade::getName);
```

- **`DEFAULT_PERIODS_PER_DAY`**：对应 Python `GradeCreate.periods_per_day` 的默认值
  （`backend/schemas.py:7`：`dict = {"Mon":6,...,"Fri":6}`）。**`public`** 是因为它是**契约的一部分**，
  `tools/contract-diff` 的用例 `grades 新建只有 name（应套用默认 periods_per_day）` 就是在测它。
- ⚠ **用 `Map.of(...)` 得到的是**不可变** Map**（`ImmutableCollections.MapN`）。
  所以 `create` 里必须 `new LinkedHashMap<>(DEFAULT_PERIODS_PER_DAY)` 拷一份 ——
  直接把这个常量塞进实体，后面任何 `setPeriodsPerDay(...).put(...)` 都会抛
  `UnsupportedOperationException`。见 §2.2 的 `create`。
- **`SORTABLE`**：**排序白名单**。把「用户传来的字符串」（`order_by=name`）映射成
  **类型安全的列引用**（`Grade::getName`），而不是把字符串拼进 `ORDER BY`（SQL 注入的经典入口）。
  `PageQuery.orderColumn(SORTABLE.keySet(), "id")` 会校验，不在其中直接 **400**。
  契约用例：`subjects 列表+非法排序字段（Java 独有校验）`（`order_by=organization_id` → 400）。

#### `list(long organizationId, PageQuery query)` → `PageResponse<GradeResponse>`

**逐行拆解**：

```java
LambdaQueryWrapper<Grade> wrapper = Wrappers.<Grade>lambdaQuery()
        .eq(Grade::getOrganizationId, organizationId);        // ① 租户隔离
if (query.hasKeyword()) {
    wrapper.like(Grade::getName, query.keyword());            // ② 关键字 → LIKE %kw%
}
String column = query.orderColumn(SORTABLE.keySet(), "id");   // ③ 白名单校验（非法→400）
wrapper.orderBy(true, query.ascending(), SORTABLE.get(column)); // ④ 排序
Page<Grade> page = gradeMapper.selectPage(
        new Page<>(query.pageNumber(), query.pageSize()), wrapper); // ⑤ 真分页
return PageResponse.of(page.getRecords().stream().map(GradeResponse::from).toList(),
        page.getTotal());                                     // ⑥ 组装
```

| 步 | 在做什么 | 不这么写会怎样 |
|---|---|---|
| ① | 显式加 `organization_id = ?` | 一个学校看到另一个学校的数据 |
| ② | `hasKeyword()` 为真才加 `LIKE` | ⚠ 无脑调用 `like(...)` 会拼出 `LIKE '%%'`（能跑，但把索引用废了） |
| ③ | `orderColumn` 校验白名单 | 直接拼用户输入 → SQL 注入；不校验会让非法字段静默回退到默认排序（"传了但没反应"） |
| ④ | ⚠ **第二参是 `isAsc`** | 传 `query.desc()` 会让方向**整个反过来**，而且**不报错**。`SubjectService.java:71` 在原位写了这条警告 |
| ⑤ | `selectPage` | ⚠ **它不会自己分页**！加 `LIMIT` 的是 `PaginationInnerInterceptor`（`config/MybatisPlusConfig.java`），没注册时**不报错**、返回全部记录、`total` 也不对 |
| ⑥ | `PageResponse.of(records, total)` | 直接用裸数组 → 前端拿不到 `total`（Python 侧就是裸数组，这里是 §9.2 的分页契约变更） |

- **与 Python 对齐**：Python `list_grade` 是 `db.query(Grade).filter(org).all()` ——
  **没有 ORDER BY、没有分页、完全忽略 `page`/`size`**。
  Java 侧新增了「真分页 + 排序白名单」，属于**刻意的能力增强**（方案 §9.2），
  契约上靠 `python_baseline="superset"` 断言「Java 返回的必须是 Python 全量的子集」。
- ⚠ **`selectPage` 用的是 `PaginationInnerInterceptor`**，它同时在 `MybatisPlusConfig` 里被明确指定
  `DbType.MYSQL`（分页方言 + count 语句生成都靠它）。

#### `create(long organizationId, GradeCreateRequest request)` → `GradeResponse`

```java
@Transactional
public GradeResponse create(long organizationId, GradeCreateRequest request) {
    Grade grade = new Grade();
    grade.setOrganizationId(organizationId);                 // ① 租户从 principal 来，**不从请求体来**
    grade.setName(request.name());                           // ②
    grade.setPeriodsPerDay(request.periodsPerDay() == null
            ? new LinkedHashMap<>(DEFAULT_PERIODS_PER_DAY)   // ③ 缺省 → 深拷一份默认值
            : request.periodsPerDay());
    gradeMapper.insert(grade);                               // ④ id 被回填
    return GradeResponse.from(grade);                        // ⑤ 直接读内存对象
}
```

- ⚠ **① `organizationId` 只来自 `principal`**，`GradeCreateRequest` 里**根本没有**这个字段 ——
  这是"客户端不可能伪造租户"的结构性保证。
- ⚠ **③ 为什么必须 `new LinkedHashMap<>(...)`**：`DEFAULT_PERIODS_PER_DAY` 是 `Map.of` 造的**不可变 Map**，
  不拷贝直接塞进实体，之后任何 `getPeriodsPerDay().put(...)` 都会炸。拷一份还顺带**隔离了共享状态**
  （否则所有用默认值的年级会共享同一个 Map 实例）。
- ⚠ **⑤ 为什么不重新查库**（Python 是 `db.commit(); db.refresh(grade)`）：
  `@TableId(type = IdType.AUTO)` 会在 `insert` 后**把自增主键回填到实体**，
  其余字段本来就是刚写下去的值。**所以内存对象就是库里的行**，不需要再往返一次。
  这与第一篇 `UtcTimes` 讲的"不截断纳秒"问题**同源**：Python 靠 `refresh` 拿库里的值，
  Java 靠"写之前就把值规整好"（时间截断、Map 拷贝）。
- `@Transactional` 这里是**防御性**的（只有一条 insert），但保持与 Python 的
  `db.commit()` 语义一致；也便于将来加子表写入。

#### `update(long organizationId, long gradeId, GradeUpdateRequest request)` → `GradeResponse`

**这是全项目 PATCH 语义的样板**，注释（`GradeService.java:77-83`）把理由写全了：

```java
Grade grade = requireOwned(organizationId, gradeId);   // ① 先查（查不到 404「年级不存在」）
if (request.name() != null) grade.setName(request.name());            // ② 只改传了的
if (request.periodsPerDay() != null) grade.setPeriodsPerDay(request.periodsPerDay());
gradeMapper.updateById(grade);                        // ③ 整行写回
return GradeResponse.from(grade);                     // ④
```

- **为什么"先查再改"而不是"构造部分实体走 `updateById`"**（注释原文）：
  后者**无法区分**「把 `periods_per_day` 改成 null」与「不改它」，
  而 Python 的 `if data.periods_per_day is not None` 能区分。
- ⚠ **`updateById` 只更新非 null 字段**，所以 `grade` 里为 null 的字段不会被写成 NULL ——
  但我们是从库里查出来的完整行，所以这里**所有字段都是非 null 的**（`grades.periods_per_day` 是 NOT NULL）。
- ⚠ **没有任何"名称唯一"的预检查**：`uq_grade_org_name` 撞了会由数据库抛
  `DuplicateKeyException` → 被兜底成 **500**。Python 侧同样是 `IntegrityError` → 500。
  **两边一致，所以 P1 不加工**（更友好的 409 属于 P2 范围）。

#### `delete(long organizationId, long gradeId)` → `void`

```java
@Transactional
public void delete(long organizationId, long gradeId) {
    Grade grade = requireOwned(organizationId, gradeId);                    // ① 404 兜底
    classService.deleteAllInGrade(organizationId, gradeId);                 // ② 班级（+其任教关系/固定课）
    subjectService.deleteGradeSubjectsByGrade(organizationId, gradeId);     // ③ 年级课时
    gradeMapper.deleteById(grade.getId());                                  // ④ 自己
}
```

- **① 为什么先查一次**：查不到要 404（Python `if not grade: raise HTTPException(404,"年级不存在")`），
  而且**查到了才动手删子表**（否则删两次也没意义）。
- **②③ 是跨领域调用，不是直接删表**。类注释（`GradeService.java:21-25`）说明了原因：
  Python 侧靠 `Grade.classes` / `Grade.grade_subjects` 两个
  `cascade="all, delete-orphan"`，SQLAlchemy 替它删子行；Java 没有 ORM 级联，
  于是**通过对方领域的 Service 清理** —— 「每张表只由属主领域读写，跨领域走对方的接口」。
- ⚠ **③ 的清理条件写的是 `organization_id + grade_id`**，而 `ClassService.deleteAllInGrade`
  也是两个条件。**多写一次 `organization_id` 不是冗余**：它是"万一 grade_id 被跨租户猜中"的第二道门。
  这里虽然 grade 已校验过归属，但保持这个习惯能让每一张被删的 SQL 都自带租户边界。
- ⚠ **Python 的 `Class` 上其实有第三个 cascade：`schedules`（当前课表）**。
  Java **刻意不删** —— `schedules` 的表属主在 scheduling（方案 §5.1）。
  当前该表是空的（真课表在 `schedule_versions` / `schedule_versions_entries`），
  所以**今天没有可观察差异**。已记入方案 §15 的 P1 遗留（见 `ClassService.java:112-117`）。

#### `requireOwned(long organizationId, long gradeId)` → `Grade`

```java
Grade grade = gradeMapper.selectOne(Wrappers.<Grade>lambdaQuery()
        .eq(Grade::getId, gradeId)
        .eq(Grade::getOrganizationId, organizationId));   // ⚠ 两个条件
if (grade == null) throw BusinessException.notFound("年级不存在");  // ⚠ 文案是契约
return grade;
```

- ⚠ **文案 `年级不存在` 逐字对齐 Python**（`backend/routers/grades.py:28`、`:41`）。
  这个字符串**同时被 4 个地方复用**：`GradeService.update` / `.delete` / `ClassService.requireGradeOwned`
  （`ClassService.java:171`）/ `GradeSubjectService.requireGradeOwned`（`GradeSubjectService.java:179`）——
  都对着 Python 里同一句 `owned_or_404(db, Grade, ..., "年级不存在")`。
- ⚠ **它是 `public` 的**（其他 4 个 Service 的 `requireOwned` 是 `private`）。
  但目前**没有任何本模块的 Java 代码调它** —— 其他领域都是自己用 `GradeMapper` 查。
  这是一个**为将来保留的公开入口**（比如 scheduling 拆库后要来问归属）。

### 2.3 `Grade` 实体（71 行）

**一句话定位**：`grades` 表的一行。**谁用它**：`GradeService`、`ClassMapper`/`SubjectMapper` 的查询、`GradeFixedSlotService`。

```java
@TableName(value = "grades", autoResultMap = true)
public class Grade {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private String name;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Integer> periodsPerDay;
```

| 行 | 作用 | ⚠ |
|---|---|---|
| `@TableName(..., autoResultMap = true)` | 表名 + 开启 `resultMap` | ⚠ **`autoResultMap = true` 不能省**，见下 |
| `@TableId(type = IdType.AUTO)` | 自增主键，`insert` 后回填 | 对应 MySQL `AUTO_INCREMENT` |
| `organizationId` | 租户列 | MyBatis-Plus 的 `map-underscore-to-camel-case` 把它映射到 `organization_id` |
| `@TableField(typeHandler = JacksonTypeHandler.class)` | **JSON 列 ↔ Map** | ⚠ 见下 |

**⚠ `autoResultMap = true` 不能省（类注释 `Grade.java:14-23` 专门讲了）**：

`periods_per_day` 是 MySQL 的 **`json` 列**（`V1__baseline_legacy.sql:71`：`periods_per_day json NOT NULL`），
需要用 `JacksonTypeHandler` 在「JSON 文本 ↔ Map」之间转换。
而 **MyBatis-Plus 只在使用 `autoResultMap` 时才会把这个 typeHandler 用在查询结果映射上**。不写它：
- **插入是好的**（typeHandler 在写入路径生效）
- **查询出来是 null**（或直接报类型转换错误），**而不报缺少配置**

→ 又是一个「**配置漏了但只坏一半路径**」的坑（和第一篇 `PageQuery` 的三个坑同一类）。

**⚠ 为什么字段类型是 `Map<String, Integer>` 而不是 `Object`**：它就是「每天几节课」，
写清类型才能在**编译期**挡住错误形状的数据（`{"Mon": "六节"}` 编译不过）。

**⚠ 没有 `created_at` / `updated_at` 字段** —— 因为表里就没有（`V1__baseline_legacy.sql:67-77`）。
所以本篇**没有一处调用 `UtcTimes`**（对比 scheduling 的表）。
这也解释了为什么 `GradeCreateRequest` 里没有时间字段。

### 2.4 `GradeMapper`（9 行）—— 以及"为什么 11 个 Mapper 里 10 个都是空的"

```java
@Mapper
public interface GradeMapper extends BaseMapper<Grade> {
}
```

- **一句话定位**：`grades` 表的数据库访问。
- ⚠ **它一行 SQL 都没有**。这是**本篇一个系统性的发现**，值得单独说：

> **本篇 11 个 Mapper 中，只有 `OrganizationMapper`（不在本篇范围）有自定义 SQL
> （`@Select("SELECT id FROM organizations WHERE id = #{id} FOR UPDATE")`，见 §6.3）。
> 其余 10 个（`GradeMapper` `ClassMapper` `SubjectMapper` `GradeSubjectMapper` `TeacherMapper`
> `TeacherAssignmentMapper` `FixedSlotMapper` `GradeFixedSlotMapper` …）全是空的 `BaseMapper<T>` 扩展。
>
> **含义**：本模块的**全部 SQL 都是 MyBatis-Plus 生成的** ——
> `selectList(Wrapper)` / `selectPage(Page, Wrapper)` / `insert` / `updateById` / `delete(Wrapper)`。
> 所以问"这个接口发了什么 SQL"，答案是**把 Wrapper 读成 WHERE 子句**：
> ```
> Wrappers.<Grade>lambdaQuery().eq(Grade::getOrganizationId, 1).like(Grade::getName, "一")
>   → SELECT id, organization_id, name, periods_per_day FROM grades
>     WHERE (organization_id = ? AND name LIKE ?)
> ```
> （`SELECT` 的列来自实体字段；`@TableField(typeHandler=...)` 会在这里被用上，**前提是
> `autoResultMap = true`**，见 §2.3。）
>
> **代价**：`selectList` 默认**查全部列**。想只查 id/name 必须显式
> `.select(Grade::getId, Grade::getName)`（`ClassService.gradeNames`、`TeacherService.subjectNames`
> 都这么做了 —— 见 §3.2、§5.2）。

- **`@Mapper`**：让 MyBatis 生成实现类并注册成 Bean（`MybatisPlusConfig` 只是加拦截器，
  不做 Mapper 扫描 —— 扫描靠 `@Mapper` 注解或启动类上的 `@MapperScan`）。

### 2.5 grade 包的 DTO

见 §1.4.1 / §1.4.2 / §1.4.4 的表格（`GradeResponse` / `GradeCreateRequest` / `GradeUpdateRequest`）。
**唯一要额外记一笔的**是 `GradeCreateRequest.java:16-18` 的设计说明：

> 「`name` 必填（缺了 422）；`periods_per_day` 可省，省略时用默认值 ——
> **默认值在 Service 里落实（`GradeService.DEFAULT_PERIODS_PER_DAY`），不放在 DTO 上，
> 避免"默认值散落两处"**。」

---

## 3. `clazz` 包 —— 班级（4 个接口，7 个类）

**定位**：`classes` 表的 CRUD。比 grade 多两件事：
① 列表要**联出年级名**；② 删除要清理**两张子表**（任教关系 + 班级级固定课）。

### 3.1 `ClassController`（71 行，4 个接口）

**一句话定位**：`/api/classes/*` 的 HTTP 入口。**谁调它**：前端 `getClasses(gradeId?)` 等
（`frontend/src/api/index.ts:109-113`）；契约用例 `classes 列表` / `classes 列表+grade_id 过滤` / `classes 新建非法年级`。

与 `GradeController` **唯一的实质差异**在 `list` 上：

```java
@GetMapping
public PageResponse<ClassResponse> list(
        @RequestParam(name = "grade_id", required = false) Long gradeId,
        PageQuery query,
        @AuthenticationPrincipal OrbitUserPrincipal principal) {
    return classService.list(requirePrincipal(principal).organizationId(), gradeId, query);
}
```

- ⚠ **`@RequestParam(name = "grade_id")` 必须显式写参数名**。类注释（`ClassController.java:24-25`）写明：
  「参数名是 snake_case，所以显式写 `@RequestParam(name = "grade_id")` —— **靠字段名猜会静默不绑定**」。
  这就是第一篇 `PageQuery` 坑 2 的同型问题：**参数名对不上时 Spring 不报错，只是这个参数永远是 null**。
  表现是「传了 `grade_id` 但过滤没生效」。
- `required = false` → 不传就是"不过滤"（`null`）。**注意与 §3.2 的 "0 也算不过滤" 是两件事**。
- ⚠ 参数顺序：`gradeId` 在 `PageQuery` 之前。**Spring 不关心顺序**（各自独立绑定），
  但写在前面的更容易被人看到。

其余三个方法（`create` / `update` / `delete`）与 `GradeController` **逐字同构**，见 §2.1，不再重复。

### 3.2 `ClassService`（197 行，8 个方法 / 其中 4 个私有）

**一句话定位**：班级业务逻辑 + **年级名批量联查** + **删除时的子表清理**。
**谁调它**：`ClassController` + **`GradeService.delete`**（调 `deleteAllInGrade`）。

依赖 4 个 Mapper —— 这个数量本身就是信息：

```java
private final ClassMapper classMapper;
private final GradeMapper gradeMapper;                    // 为了联 grade_name
private final TeacherAssignmentMapper assignmentMapper;   // 为了删子表
private final FixedSlotMapper fixedSlotMapper;            // 为了删子表
```

```java
private static final Map<String, SFunction<Clazz, ?>> SORTABLE = Map.of(
        "id", Clazz::getId,
        "name", Clazz::getName,
        "grade_id", Clazz::getGradeId);
```

- **白名单比 grade 多一个 `grade_id`** —— 因为「按年级排序」对班级列表是有意义的用法。
  注意 `orderColumn` 的错误文案会把允许的字段列出来（`PageQuery.java:121-123`）：
  `不支持的排序字段: xxx（可选: ...）`，所以白名单里的键名**直接出现在面向用户的报错里**。

#### `list(long organizationId, Long gradeId, PageQuery query)` → `PageResponse<ClassResponse>`

```java
var wrapper = Wrappers.<Clazz>lambdaQuery().eq(Clazz::getOrganizationId, organizationId);
if (gradeId != null && gradeId != 0L) {            // ① ⚠ 0 被当成"不过滤"
    wrapper.eq(Clazz::getGradeId, gradeId);
}
if (query.hasKeyword()) wrapper.like(Clazz::getName, query.keyword());   // ②
String column = query.orderColumn(SORTABLE.keySet(), "id");              // ③
wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));          // ④

Page<Clazz> page = classMapper.selectPage(new Page<>(...), wrapper);     // ⑤
List<Clazz> records = page.getRecords();
Map<Long, String> gradeNames = gradeNames(records.stream().map(Clazz::getGradeId).toList()); // ⑥
return PageResponse.of(records.stream()
        .map(c -> ClassResponse.of(c, gradeNames.get(c.getGradeId()))).toList(),
        page.getTotal());                                                // ⑦
```

**⚠ ① `gradeId != 0L` 的秘密**（方法注释 `ClassService.java:49-56` 原文讲清楚了）：

```python
# backend/routers/classes.py:15
if grade_id:            # ← 0 是 falsy，被当成"不过滤"
    q = q.filter(Class.grade_id == grade_id)
```

Python 写的是 `if grade_id:`，所以 **`grade_id=0` 会被当成"不过滤"**，返回全部班级。
Java 这里**逐字保持一致**（`gradeId != null && gradeId != 0L`），而不是写成更"正确"的
`gradeId != null`。注释的理由：「虽然 `grade_id=0` 现实中不会查出东西，但"行为等价"是 P1 的目标，
**这种一处一行的差异不值得为它开例外**」。
**这个 `gradeId != 0L` 的写法在本篇出现了 4 次**：`ClassService.list`、
`GradeSubjectService.list`（§7.2）、`GradeFixedSlotService.list`（§8.2）、
`AssignmentService.list`（§6.2，两处：teacher_id 和 class_id）。
→ **它是一条贯穿全模块的约定，不是某处的笔误。**

**⚠ ⑥ 为什么要批量取名**（而不是每行查一次）：

```java
private Map<Long, String> gradeNames(Collection<Long> gradeIds) {
    List<Long> ids = gradeIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) return Map.of();                    // ⚠ 空 IN () 会生成语法错的 SQL，必须短路
    return gradeMapper.selectList(Wrappers.<Grade>lambdaQuery()
                    .select(Grade::getId, Grade::getName)   // ⚠ 只查两列
                    .in(Grade::getId, ids))
            .stream().collect(Collectors.toMap(Grade::getId, Grade::getName, (a, b) -> a));
}
```

| 细节 | 为什么 |
|---|---|
| `.filter(Objects::nonNull).distinct()` | `gradeId` 在库里有 NOT NULL 约束，但**防御性**；`distinct()` 让 20 个班只查 3 个年级 |
| `if (ids.isEmpty()) return Map.of()` | ⚠ **`IN ()` 是非法 SQL**。空列表必须短路（Python 的 `joinedload` 不生成 IN，所以 Python 侧没这个问题） |
| `.select(Grade::getId, Grade::getName)` | 不写就会 `SELECT *`，把一个 `json` 列（`periods_per_day`）白读回来 |
| `(a, b) -> a` 合并函数 | 主键唯一，正常不会撞；写了是**防御 MyBatis-Plus 的 `toMap` 在重复键上抛异常** |
| **对应 Python 的 `joinedload(Class.grade)`** | Python 一条 JOIN 拿回来；Java 是「主查询 + 一次 IN 查询」= **2 条 SQL**，效果等价、避免 N+1 |

**⚠ ⑦ `gradeNames.get(c.getGradeId())` 可能返回 null**：Python 是
`grade_name=c.grade.name if c.grade else None`（`classes.py:19`），Java 的 `Map.get` 缺键也返回 null →
序列化成 `"grade_name": null`。**两边一致**（因为 `application.yml:54` 明确
`default-property-inclusion: always`，null 字段不会被删掉）。

#### `create(long organizationId, ClassCreateRequest request)` → `ClassResponse`

```java
@Transactional
public ClassResponse create(long organizationId, ClassCreateRequest request) {
    requireGradeOwned(organizationId, request.gradeId());      // ① 先证明年级是本组织的
    Clazz clazz = new Clazz();
    clazz.setOrganizationId(organizationId);
    clazz.setName(request.name());
    clazz.setGradeId(request.gradeId());
    classMapper.insert(clazz);                                 // ②
    return ClassResponse.of(clazz, gradeName(clazz.getGradeId()));  // ③ 联名
}
```

- **① 对应 Python `owned_or_404(db, Grade, data.grade_id, ..., "年级不存在")`**（`classes.py:24`）。
  契约用例 `classes 新建非法年级` expect **404**（不是 400！）。
- ⚠ **`requireGradeOwned` 里的 null 判断**（见下面是 §3.2 的私有方法段）：`gradeId == null` 也走 404。
  但正常路径下 `@NotNull` 已经先挡成 422 了 —— 所以 `null` 分支是**防御性**的
  （比如将来有内部调用绕过校验）。
- **③ 用的是 `gradeName(...)`（单个），它内部调 `gradeNames(List.of(gradeId))`** ——
  即"单个名字"和"批量名字"共用一条代码路径。好处：**两条路径不可能漂移**。
  Python 也是这么做的（`create` / `update` 里各自又查了一次 Grade）。

#### `update(long organizationId, long classId, ClassUpdateRequest request)` → `ClassResponse`

```java
Clazz clazz = requireOwned(organizationId, classId);      // ① 404「班级不存在」
if (request.name() != null) clazz.setName(request.name());          // ②
if (request.gradeId() != null) {                                    // ③
    requireGradeOwned(organizationId, request.gradeId());           // ④ ⚠ 改引用也要校验归属
    clazz.setGradeId(request.gradeId());
}
classMapper.updateById(clazz);
return ClassResponse.of(clazz, gradeName(clazz.getGradeId()));      // ⑤
```

- ⚠ **④ 的顺序很关键**：**先校验、再赋值**。反过来的话，校验失败时实体已经被改脏 ——
  在 `@Transactional` 里抛异常虽然会回滚，但任何"catch 了异常继续用这个对象"的代码都会拿到脏数据。
  这个"**先校验后赋值**"的顺序在本篇的 update 方法里是一致的（`TeacherService.update:88-91` 同样）。
- ⚠ **③ 与 ② 是独立的 `if`，不是 `else if`** —— 允许「同时改名字和年级」。

#### `delete(long organizationId, long classId)` → `void`

```java
@Transactional
public void delete(long organizationId, long classId) {
    Clazz clazz = requireOwned(organizationId, classId);       // ① 404「班级不存在」
    deleteOwnedChildren(organizationId, List.of(clazz.getId())); // ② 子表
    classMapper.deleteById(clazz.getId());                     // ③ 自己
}
```

**② 删哪些子表**（`deleteOwnedChildren`）：

```java
private void deleteOwnedChildren(long organizationId, List<Long> classIds) {
    assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
            .eq(TeacherAssignment::getOrganizationId, organizationId)
            .in(TeacherAssignment::getClassId, classIds));     // 任教关系
    fixedSlotMapper.delete(Wrappers.<FixedSlot>lambdaQuery()
            .eq(FixedSlot::getOrganizationId, organizationId)
            .in(FixedSlot::getClassId, classIds));             // 班级级固定课
}
```

- **对应 Python `Class` 上的三个 cascade**：`assignments` ✓、`fixed_slots` ✓、
  **`schedules` ✗ 刻意不删**。
- **⚠ 为什么要有 `organization_id` 条件**（这里 id 已经是本组织的、`in` 也只是它自己的 id）：
  保证**每一条 DELETE 都自带租户边界**，即使上游校验被改动也不会跨租户误删。
- ⚠ **`fixed_slots` 是"没有 Service 的表"**：它在本模块里**只有这一处写操作**
  （没有任何 Controller / Service / DTO 暴露它）。所以 `ClassService` 必须直接持有 `FixedSlotMapper` ——
  这是"某个领域只有删、没有查改"时的必然形状。见 §1.3 的说明。
- ⚠ **删固定课前不检查"有没有被引用的教师"**：`fixed_slots.teacher_id` 有外键
  （`V1__baseline_legacy.sql:213`，指向 `teachers.id`，**没有 ON DELETE**），但这不影响删除
  （删的是 `fixed_slots` 自己，不是 `teachers`）。Python 侧同样是级联删掉。

#### `deleteAllInGrade(long organizationId, long gradeId)` → `void`（**供 `GradeService.delete` 调用**）

```java
@Transactional
public void deleteAllInGrade(long organizationId, long gradeId) {
    List<Long> classIds = classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                    .select(Clazz::getId)                          // ⚠ 只查 id
                    .eq(Clazz::getOrganizationId, organizationId)
                    .eq(Clazz::getGradeId, gradeId))
            .stream().map(Clazz::getId).toList();
    if (classIds.isEmpty()) return;                                // ① 短路
    deleteOwnedChildren(organizationId, classIds);                 // ②
    classMapper.delete(Wrappers.<Clazz>lambdaQuery()
            .eq(Clazz::getOrganizationId, organizationId)
            .eq(Clazz::getGradeId, gradeId));                      // ③ 按条件删，不按 id 列表
}
```

| 步 | 为什么 |
|---|---|
| 先 `selectList` 而不是直接删 | 因为**子表清理需要 class_id 列表**（`in (...)`），必须先把 id 查出来 |
| `.select(Clazz::getId)` | 只要 id，别把整行读回来 |
| ① `isEmpty()` 短路 | ⚠ 否则 `deleteOwnedChildren` 会拼出 `IN ()` 非法 SQL |
| ③ 用**条件删除**而不是 `deleteByIds(classIds)` | 两个条件（org + grade）就是完整的过滤条件，不需要把 id 列表回传给数据库（少一次大 IN） |
| **方法放在班级这一侧实现** | 类注释（`ClassService.java:128-131`）：「放在班级这一侧实现，而不是让 `GradeService` 直接操作班级的表 —— 每张表只由它的属主领域读写，跨领域时通过对方的 Service」 |

#### `requireOwned` / `requireGradeOwned` / `gradeOwned` / `gradeName` / `gradeNames`

```java
public Clazz requireOwned(long organizationId, long classId) {
    Clazz clazz = classMapper.selectOne(...);              // id + org 两个条件
    if (clazz == null) throw BusinessException.notFound("班级不存在");   // ⚠ 文案
    return clazz;
}

private void requireGradeOwned(long organizationId, Long gradeId) {
    if (gradeId == null || !gradeOwned(organizationId, gradeId)) {
        throw BusinessException.notFound("年级不存在");     // ⚠ Python 用 owned_or_404
    }
}

private boolean gradeOwned(long organizationId, Long gradeId) {
    return gradeMapper.selectCount(Wrappers.<Grade>lambdaQuery()
            .eq(Grade::getId, gradeId)
            .eq(Grade::getOrganizationId, organizationId)) > 0;
}
```

- **文案清单**（都对过 Python）：`班级不存在`（`classes.py:36`、`:51`）、
  `年级不存在`（`classes.py:24`、`:40`）。
- ⚠ **`selectCount` 而不是 `selectOne`**：只关心"在不在"，不关心内容 ——
  `selectCount` 生成 `SELECT COUNT(*)`，**不会去读 `periods_per_day` 这个 json 列**。
- ⚠ **`gradeOwned` 返回 `boolean`，`requireGradeOwned` 返回 `void`（抛异常）** ——
  两个方法分开是为了让"要不要抛"这个决定只有一个地方（避免有调用方忘了检查布尔返回值）。
  这个"**检查型**check + 抛异常型 require"的配对在本篇出现了 3 次（另两处是
  `SubjectService.ensureGradesOwnedBy`、`AssignmentService.requireOwned`）。

### 3.3 `Clazz` 实体（58 行）与 `ClassMapper`（9 行）

```java
@TableName("classes")          // ⚠ 没有 autoResultMap —— 因为没有 JSON 列
public class Clazz {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private String name;
    private Long gradeId;
```

- **一句话定位**：`classes` 表的一行。
- **⚠ 类名 / 包名为什么是 `Clazz` / `clazz`**：`class` 是 Java 关键字（类名、包名都不能用）。
  与 README 的包归属一致。
- **⚠ 唯一约束是 `uq_class_org_name (organization_id, name)`，不是 `(grade_id, name)`**
  （`Clazz.java:13-15` + `V1__baseline_legacy.sql:103`）：
  所以「一年级1班」和「二年级1班」**不能同名**。
  注释明确写了「**这是既有 schema 的行为，不要"顺手修正"**」。
  实际后果：新建班级撞名 → 数据库 `DuplicateKeyException` → 500（Python 也是 500）。
- **`ClassMapper` 与 `GradeMapper` 逐字相同**（空 `BaseMapper<Clazz>`）。

### 3.4 clazz 包的 DTO

见 §1.4.1 / §1.4.2 / §1.4.4。
**额外记一笔**：`ClassCreateRequest.java:8-9` 解释了「`gradeId` 用 `Long` 而不是 `long`：
缺字段时 Spring 会传 null，原始类型接不住」，并把它称为「**H1 那个坑**」。

> ⚠ **本模块的注释里有三处"坑编号"引用（H1、H2）指向一份本篇和第一篇都没收录的清单**：
> `ClassCreateRequest.java:9`（H1）、`ClassController.java:25`（H2）。
> [文档索引](#10-已知偏离与未解问题) 里第一篇用的是 `B2`/`B5` 这类编号，对不上。
> 详见 §10 的未解问题 U3。

---

## 4. `subject` 包（上）—— 科目（3 个接口，6 个类）

**定位**：`subjects` 表的 CRUD。**它是本篇第一个"写入时要顺带写另一张表"的接口**
（`POST /api/subjects` 会同时写 `grade_subjects`），也是第一个在 **Service 里做字符串处理**的。

### 4.1 `SubjectController`（84 行，3 个接口）

**一句话定位**：`/api/subjects/*` 的 HTTP 入口。
**谁调它**：前端 `getSubjects/createSubject/deleteSubject`（`frontend/src/api/index.ts:115-117`）。

**⚠ 这个类有一份特别有价值的类注释（`SubjectController.java:22-33`）**，把三个接口的形状差异列成了表：

| 方法 | 路径 | Python | 这里 |
|---|---|---|---|
| GET | `/api/subjects` | **裸数组** | **`{items, total}`**（§9.2 新增分页） |
| POST | `/api/subjects` | 200 SubjectOut | 同 |
| DELETE | `/api/subjects/{id}` | 200 `{"ok":true}` | 同 |

**⚠ 没有 PUT**：Python 的 `routers/subjects.py` 里**只有 3 个接口**（GET/POST/DELETE），
没有"改科目"。所以 Java 侧也**没有** `SubjectUpdateRequest`。
→ **本篇 6 组主干里，只有 subject 和 assignment 没有 update。**

**⚠ POST 返回 200 不是 201**：见 §2.1 的说明（Python 的 `@router.post` 没有指定 `status_code`，
FastAPI 默认 200；Spring `@PostMapping` 默认也是 200）。

#### `list(PageQuery query, ...)` —— 有个值得注意的注释

```java
/**
 * <p>{@link PageQuery} 会被 Spring 当作 {@code @ModelAttribute} 做**构造器绑定**
 * （record 的紧凑构造器负责归一化：page/size 缺省、size 上限、order 合法性）。
 * 没传任何参数时 {@code page=0,size=0} 由原始类型默认值给出，然后被归一化成 1/20。
 */
```

> ⚠ **这段注释的最后一句是不准确的**（`SubjectController.java:49`）：
> `PageQuery` 的字段是**包装类型 `Integer`**（`PageQuery.java:48-49`），
> 缺省时 Spring 传的是 **`null`**，不是 `0`。
> 这正是第一篇 §1.2「坑 1」讲的事：**当初用原始类型 `int` 才会绑定失败 422**，
> 改成 `Integer` 之后由紧凑构造器把 `null` 归一化成 1/20。
> **结果（1/20）是对的，但归因写错了** —— 读代码时不要被这句话误导。
> 建议的修法：把 `不传任何参数时 page/size 为 null，由紧凑构造器归一化为 1/20` 写进注释。
> （已记入 §10 的 U1。）

#### `create` / `delete`

与 grade 同构（`@Valid @RequestBody` / `@PathVariable` + `Map.of("ok", true)`），不重复。

### 4.2 `SubjectService`（182 行，8 个方法）

**一句话定位**：科目业务逻辑 + **新增时校验年级归属并写 `grade_subjects`**。
**谁调它**：`SubjectController`；**`GradeService.delete`** 调 `deleteGradeSubjectsByGrade`。

**⚠ 类注释是一段关于"租户隔离为什么不靠拦截器"的完整论证**（`SubjectService.java:25-31`），
摘录：

> 每个方法都接收 `organizationId` 并**显式**加到查询条件里。Python 侧也是这么写的。
> **为什么不用 MyBatis-Plus 的租户插件自动拼条件**：拦截器一旦被绕过
> （写原生 SQL、或用了没走拦截器的 API）就**静默失去隔离**，
> 而一个学校看到另一个学校的数据是严重事故。显式写在每个查询里，**代码评审时看得见**。

```java
private static final Map<String, SFunction<Subject, ?>> SORTABLE = Map.of(
        "id", Subject::getId, "name", Subject::getName, "category", Subject::getCategory);

/** 未指定排序时的默认列。Python 侧没有 ORDER BY，实际返回的就是主键序。 */
private static final String DEFAULT_SORT = "id";
```

- ⚠ **`DEFAULT_SORT` 单独抽成常量的原因写在注释里**：Python 侧**没有 ORDER BY**
  （`subjects.py:13` 就是 `.all()`），实际返回的是 InnoDB 的主键序。
  写成"显式按 id 升序"是**把 Python 的偶然行为固定成契约** —— 这样排序参数一旦被前端用起来，
  默认序不会变。**这个手法在本篇出现 4 次**（`grade` / `class` / `teacher` 都没有单独抽常量，
  直接把 `"id"` 字面量传给 `orderColumn`；只有 `subject` 抽了，因为它有注释要挂）。

#### `list(long organizationId, PageQuery query)` → `PageResponse<SubjectResponse>`

与 `GradeService.list` 逐字同构（§2.2），**唯一差别是 `orderBy` 那一行多了一条原位警告**：

```java
String column = query.orderColumn(SORTABLE.keySet(), DEFAULT_SORT);
// ⚠ 第二参是 isAsc，所以传 ascending() 而不是 desc() —— 传反了不会报错，只会排序方向相反
wrapper.orderBy(true, query.ascending(), SORTABLE.get(column));
```

**契约用例**：`subjects 列表`（`list_endpoint`）、`subjects 列表+关键字（Java 新增能力）`（superset）、
`subjects 列表+排序（Java 新增能力）`（`order_by=name&order=desc`，superset）、
`subjects 列表+非法排序字段（Java 独有校验）`（`order_by=organization_id` → **400**）、
`subjects 列表+超大 size（Java 独有）`（`size=999999` → 被截到 200）。

#### `create(long organizationId, SubjectCreateRequest request)` → `SubjectResponse`

**这是本篇第一个"一个事务里写两张表"的方法**，逐行：

```java
@Transactional
public SubjectResponse create(long organizationId, SubjectCreateRequest request) {
    String name = request.name() == null ? "" : request.name().strip();     // ①

    Subject subject = new Subject();
    subject.setOrganizationId(organizationId);
    subject.setName(name);
    subject.setCategory(request.category() == null || request.category().isBlank()
            ? "minor" : request.category());                               // ②
    subjectMapper.insert(subject);                                         // ③ 先拿 id

    Map<Long, Integer> hoursByGrade = request.weeklyHoursByGrade();
    if (hoursByGrade != null && !hoursByGrade.isEmpty()) {                 // ④
        ensureGradesOwnedBy(organizationId, hoursByGrade.keySet());        // ⑤ 校验
        for (Map.Entry<Long, Integer> entry : hoursByGrade.entrySet()) {
            Integer hours = entry.getValue();
            if (hours != null && hours > 0) {                              // ⑥ ⚠ 0 与负数不建行
                GradeSubject link = new GradeSubject();
                link.setOrganizationId(organizationId);
                link.setGradeId(entry.getKey());
                link.setSubjectId(subject.getId());                        // ⑦ 用刚回填的 id
                link.setWeeklyHours(hours);
                gradeSubjectMapper.insert(link);
            }
        }
    }
    return SubjectResponse.from(subject);                                  // ⑧
}
```

**逐步对照 Python（`backend/routers/subjects.py:16-44`）**：

| 步 | Python | Java | 一致？ |
|---|---|---|---|
| ① | `Subject(name=data.name, ...)` —— **不做 strip** | `request.name().strip()` | ⚠ **不一致，见下** |
| ② | `category=data.category`（Pydantic 已填默认 `"minor"`） | `null 或 blank → "minor"` | 基本一致 |
| ③ | `db.add(subj); db.flush()` | `insert(subject)` | ✓ 都是"先拿 id" |
| ④ | `if data.weekly_hours_by_grade:`（falsy 检查） | `!= null && !isEmpty()` | ✓ |
| ⑤ | 查 `Grade.id IN (...)`，`if grade_ids != owned_grade_ids: rollback; 400` | `ensureGradesOwnedBy` → 400 | ✓ 文案一致 |
| ⑥ | `if weekly_hours > 0` | `hours != null && hours > 0` | ⚠ **null 分支不同，见下** |
| ⑦ | `subject_id=subj.id` | `subject.getId()` | ✓ |
| ⑧ | `db.commit(); db.refresh(subj)` | 直接返回内存对象 | ✓（见 §2.2 的说明） |

> **⚠ 差异 1：`name` 上多做了一次 `.strip()`**（`SubjectService.java:90`）。
> Python 侧 `subjects.py:18` 是 `name=data.name` —— **原样存入，不去空白**。
> 所以 `POST /api/subjects {"name":"  语文  "}`：
> - Python 存 `"  语文  "`（前后带空格），响应里也带空格；
> - Java 存 `"语文"`。
>
> **这是一个真实的、可观察的行为差异**，而契约用例里**没有覆盖它**
> （CASES 里的 subjects 新建用例用的是普通名字，见 `compare.py:576-583`），
> 所以影子对比工具**抓不到**。它符合"把显然的输入规整一下"的直觉，
> 但**违反了"行为等价"的目标**。已记入 §9 / §10。
>
> **另外**：`request.name() == null ? ""` 这个分支在正常路径下走不到（`@NotNull` 已挡成 422），
> 是纯防御。

> **⚠ 差异 2：`hours == null` 的处理**。
> Python：`weekly_hours > 0` 直接比较 → `None > 0` 抛 `TypeError` → **500**。
> Java：`hours != null && hours > 0` → **静默跳过**这条，请求仍返回 200。
> 即 `{"weekly_hours_by_grade": {"1": null}}`：
> **Python 500，Java 200 且不建关联行**。
> 这属于「**Java 比 Python 更宽容**」的偏离。契约用例没覆盖。
> （Java 侧这样写是合理的防御，但严格来说应当与 Python 一起 500 —— 或者两边都改好。
> 已记入 §10。）

**⑤ `ensureGradesOwnedBy` 逐行**：

```java
private void ensureGradesOwnedBy(long organizationId, Collection<Long> gradeIds) {
    Set<Long> requested = new HashSet<>(gradeIds);
    List<Grade> found = gradeMapper.selectList(Wrappers.<Grade>lambdaQuery()
            .select(Grade::getId)                                 // ⚠ 只查 id
            .eq(Grade::getOrganizationId, organizationId)
            .in(Grade::getId, requested));
    Set<Long> owned = found.stream().map(Grade::getId).collect(Collectors.toSet());
    if (!owned.containsAll(requested)) {                          // ⚠ 子集判断
        throw BusinessException.badRequest("包含不存在或不属于当前学校的年级");
    }
}
```

- **注释（`SubjectService.java:160-170`）解释了 `containsAll` 为什么够用**：
  「因为查询本身带 `IN (requested)`，找到的必然是请求集合的**子集**，
  所以「子集相等」等价于「请求的每一个都被找到」」。
  Python 写的是 `if grade_ids != owned_grade_ids:`（双向相等）；Java 写 `!owned.containsAll(requested)`（单向包含）。
  **在"found ⊆ requested"恒成立的前提下两者等价** —— 这是个依赖前提的等价，注释把它点明了。
- **⚠ 文案 `包含不存在或不属于当前学校的年级` 是本模块被复用最多的错误文案之一**，
  出现在：`SubjectService.ensureGradesOwnedBy`（§4.2）、`GradeSubjectService.bulkUpdate`（§7.2）、
  以及 `AssignmentService.autoAssign` 的**科目版**（`包含不存在或不属于当前学校的科目`）。
  契约用例：`subjects 新建非法年级`（400）、`grade-subjects 批量非法年级`（400）。
- ⚠ **空集合会走 `in ()`**！但上游有 `!hoursByGrade.isEmpty()` 保护（④），
  所以 `requested` 一定非空。**这是一个"靠调用方保证"的前提** ——
  另一个调用方（`GradeSubjectService.bulkUpdate`）就自己做了空集短路（见 §7.2）。

#### `delete(long organizationId, long subjectId)` → `void`

**⚠ 类注释（`SubjectService.java:119-133`）把两个"已知的 500"写清楚了**，值得整段引用：

```java
Subject subject = subjectMapper.selectOne(... id + org ...);
if (subject == null) throw BusinessException.notFound("科目不存在");   // ⚠ 文案
gradeSubjectMapper.delete(Wrappers.<GradeSubject>lambdaQuery()
        .eq(GradeSubject::getSubjectId, subjectId));                 // ① ⚠ 必须先删关联行
subjectMapper.deleteById(subjectId);                                 // ②
```

> **① 必须先删 `grade_subjects` 的关联行**：Python 侧 `Subject.grade_subjects` 上是
> `cascade="all, delete-orphan"`，SQLAlchemy 会替它删子行；
> 而数据库的 `grade_subjects_ibfk_3` 外键**没有 `ON DELETE CASCADE`**
> （`V1__baseline_legacy.sql:128`），直接删科目会被外键拦住、报一个难以理解的完整性错误。
>
> **⚠ 已知的、与 Python 一致的行为**：如果该科目**已被教师引用**（`teachers.subject_id`），
> 删除会因外键失败（`teachers_ibfk_2`，`V1__baseline_legacy.sql:146`）。
> Python 侧同样会失败（`Teacher.subject` 上没有 cascade，SQLAlchemy 会尝试把 `subject_id`
> 置 NULL，而该列 **NOT NULL**）。**两边都是 500，行为等价。**
> 更友好的做法（返回 409 + 人话提示）属于 P2 的前端改造范围，已记入 TODO。

- ⚠ **① 的删除条件里没有 `organization_id`**（只按 `subject_id`）。
  **这是本篇唯一一处"子表删除不带 org 条件"** —— 因为 `subjectId` 刚在第 1 行被证明属于本组织，
  且 `grade_subjects.subject_id` 的取值必然指向同一租户的科目（写入时就是这么写的）。
  与 §3.2 `deleteOwnedChildren` 带的 org 条件相比，**风格不统一**（但行为正确）。
- ⚠ **删除用 `deleteById(subjectId)` 而不是 `deleteById(subject.getId())`** ——
  这里两者恒等（`subject.getId()` 就是从 `subjectId` 查出来的）。
  和 §2.2 `GradeService.delete` 用 `grade.getId()` 的写法不一致，**属于无害的不统一**。

#### `deleteGradeSubjectsByGrade(long organizationId, long gradeId)` → `void`（**供 `GradeService.delete` 调用**）

```java
@Transactional
public void deleteGradeSubjectsByGrade(long organizationId, long gradeId) {
    gradeSubjectMapper.delete(Wrappers.<GradeSubject>lambdaQuery()
            .eq(GradeSubject::getOrganizationId, organizationId)
            .eq(GradeSubject::getGradeId, gradeId));
}
```

- **对应 Python `Grade.grade_subjects` 的 cascade**。
- ⚠ **这个方法带 `organization_id` 条件**（对比同类的 `delete` 不带）——
  两个方法的条件风格不一致。带 org 条件更安全，是更该保留的那个写法。
- ⚠ **`@Transactional` 的传播行为**：它被 `GradeService.delete`（本身 `@Transactional`）调用，
  默认 `Propagation.REQUIRED` → **加入外层事务**，不会变成两个独立事务。
  这是"跨领域调 Service"能保持原子性的关键（否则删了班级、删年级课时失败，
  班级就回不来了）。**本模块所有跨领域调用都是这个模式。**

### 4.3 `Subject` / `SubjectMapper` / `GradeSubject` / `GradeSubjectMapper`

```java
@TableName("subjects")   // 同样没有 autoResultMap —— 没有 JSON 列
public class Subject {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private String name;
    /** {@code major}（主科）/ {@code minor}（副科）等；Python 侧默认 {@code "minor"}。 */
    private String category;
```

- ⚠ **唯一约束 `uq_subject_org_name (organization_id, name)`** —— 同一学校内科目名不能重复，
  跨学校可以重名（`V1__baseline_legacy.sql:88`）。
- ⚠ **`category` 是 `varchar(30) NOT NULL`，没有枚举约束**（表里也没 CHECK）。
  所以 Java/Python 都接受任意字符串（`"语文"` 也能当 category 存进去）。
- **`SubjectResponse` 只有三个字段**，`SubjectResponse.java:8-9` 说明**不返回 `organization_id`**
  （虽然表里有 —— 当前用户本来就在这个组织里，返回它是冗余的）。**这是 Python `SubjectOut` 的字段集。**

`GradeSubject` / `GradeSubjectMapper` 见 §7.1（放在 `subject` 包但属于"年级课时"业务）。

---

## 5. `teacher` 包 —— 教师（4 个接口，7 个类）

**定位**：`teachers` 表的 CRUD。与 `clazz` 高度同构（列表联"科目名"而不是"年级名"），
但有**一个可观察的差异**（`max_weekly_hours` 的可空性），见 §5.3。

### 5.1 `TeacherController`（63 行，4 个接口）

**一句话定位**：`/api/teachers/*` 的 HTTP 入口（**没有过滤参数**，`list` 只吃 `PageQuery`）。
**谁调它**：前端 `getTeachers()` 等（`frontend/src/api/index.ts:126-129`）；
契约用例 `teachers 列表` / `teachers 新建（应套用默认 max_weekly_hours=18）` / `teachers 新建非法科目`。

四个方法与 `GradeController` **逐字同构**（§2.1），没有任何额外逻辑。不重复。

### 5.2 `TeacherService`（149 行，8 个方法）

**一句话定位**：教师业务逻辑 + **科目名批量联查** + 删除时清任教关系。
**谁调它**：`TeacherController`；**注意 `AssignmentService` 不调它** ——
`AssignmentService` 自己持有 `TeacherMapper`（因为要按 `subject_id` 建索引、要算负载，不是"取一个教师"）。

```java
/** Python 的 {@code TeacherCreate.max_weekly_hours} 默认值。 */
public static final int DEFAULT_MAX_WEEKLY_HOURS = 18;

private static final Map<String, SFunction<Teacher, ?>> SORTABLE = Map.of(
        "id", Teacher::getId,
        "name", Teacher::getName,
        "subject_id", Teacher::getSubjectId,
        "max_weekly_hours", Teacher::getMaxWeeklyHours);
```

- **`DEFAULT_MAX_WEEKLY_HOURS = 18`** 对应 `backend/schemas.py:70` 的 `max_weekly_hours: int = 18`。
  `public` + 常量：契约的一部分，契约用例 `teachers 新建（应套用默认 max_weekly_hours=18）` 在测它。
- **白名单 4 个字段**（本篇最多的一个）—— 比 grade/class 多 `subject_id` 和 `max_weekly_hours`。
  ⚠ `max_weekly_hours` 进白名单意味着**用户可以直接按它排序**；这不会越权（它本来就是本组织的数据）。

#### `list(long organizationId, PageQuery query)` → `PageResponse<TeacherResponse>`

与 `ClassService.list` 同构（§3.2），把"批量取年级名"换成"批量取科目名"：

```java
Map<Long, String> subjectNames = subjectNames(records.stream().map(Teacher::getSubjectId).toList());
return PageResponse.of(records.stream()
        .map(t -> TeacherResponse.of(t, subjectNames.get(t.getSubjectId()))).toList(),
        page.getTotal());

private Map<Long, String> subjectNames(Collection<Long> subjectIds) {
    List<Long> ids = subjectIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) return Map.of();                       // ⚠ ALWAYS 短路（IN () 是非法 SQL）
    return subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                    .select(Subject::getId, Subject::getName) // ⚠ 只查两列
                    .in(Subject::getId, ids))
            .stream().collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
}
```

- **对应 Python `joinedload(Teacher.subject)`**（`teachers.py:14`）。Java 是 2 条 SQL，避免 N+1。
- ⚠ **`teachers.subject_id` 是 NOT NULL**，所以理论上不会有 null —— 但 `.filter(Objects::nonNull)` 保留了
  （防御 + 与 `ClassService.gradeNames` 保持一致）。

#### `create(long organizationId, TeacherCreateRequest request)` → `TeacherResponse`

```java
@Transactional
public TeacherResponse create(long organizationId, TeacherCreateRequest request) {
    requireSubjectOwned(organizationId, request.subjectId());       // ① 404「科目不存在」
    Teacher teacher = new Teacher();
    teacher.setOrganizationId(organizationId);
    teacher.setName(request.name());
    teacher.setSubjectId(request.subjectId());
    teacher.setMaxWeeklyHours(request.maxWeeklyHours() == null
            ? DEFAULT_MAX_WEEKLY_HOURS : request.maxWeeklyHours());  // ② 默认 18
    teacherMapper.insert(teacher);
    return TeacherResponse.of(teacher, subjectName(teacher.getSubjectId()));
}
```

- **① 对应 Python `owned_or_404(db, Subject, data.subject_id, ..., "科目不存在")`**（`teachers.py:24`）。
  契约用例 `teachers 新建非法科目` expect **404**。
- **② 默认值落在 Service**（与 `GradeService.create` 同样的理由：默认值不散落两处）。

#### `update` / `delete`

```java
Teacher teacher = requireOwned(organizationId, teacherId);       // 404「老师不存在」⚠
if (request.name() != null) teacher.setName(request.name());
if (request.subjectId() != null) {
    requireSubjectOwned(organizationId, request.subjectId());    // ⚠ 先校验后赋值（同 §3.2）
    teacher.setSubjectId(request.subjectId());
}
if (request.maxWeeklyHours() != null) teacher.setMaxWeeklyHours(request.maxWeeklyHours());
teacherMapper.updateById(teacher);
return TeacherResponse.of(teacher, subjectName(teacher.getSubjectId()));

@Transactional
public void delete(long organizationId, long teacherId) {
    Teacher teacher = requireOwned(organizationId, teacherId);
    assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
            .eq(TeacherAssignment::getOrganizationId, organizationId)
            .eq(TeacherAssignment::getTeacherId, teacherId));     // ① 只删任教关系
    teacherMapper.deleteById(teacher.getId());
}
```

- **⚠ `requireOwned` 的文案是 `老师不存在`（不是"教师不存在"）**！
  对齐 `teachers.py:38`、`:58` 的 `"老师不存在"`。
  ⚠ **而 `AssignmentService.setTeacherClasses` 里同一个意思用的是 `教师不存在`**
  （对齐 `assignments.py:205` 的 `"教师不存在"`）。
  → **同一个概念在本模块有两套文案，各自对齐各自的 Python 行**。
  这是"行为等价"的**必然产物**，也是最容易被"顺手统一"改坏的地方。
  见 §9 的文案清单和 §10 的提醒。
- **⚠ `delete` 的注释（`TeacherService.java:99-105`）说明了两个"两边都失败"的场景**：
  > Python 的 `Teacher` 上只有 `assignments` 一个 cascade，所以先删任教关系；
  > `fixed_slots.teacher_id` 的外键**没有** cascade，如果该教师已被固定课引用，
  > 两边都会失败（Python 会尝试把外键置 NULL 而该列 NOT NULL）。**行为等价，这里不额外加工。**
  （`fixed_slots_ibfk_4`：`V1__baseline_legacy.sql:213`，确认没有 `ON DELETE`。）

#### `requireOwned` / `requireSubjectOwned` / `subjectName` / `subjectNames`

```java
public Teacher requireOwned(long organizationId, long teacherId) {
    Teacher teacher = teacherMapper.selectOne(... id + org ...);
    if (teacher == null) throw BusinessException.notFound("老师不存在");
    return teacher;
}

private void requireSubjectOwned(long organizationId, Long subjectId) {
    if (subjectId == null || subjectMapper.selectCount(Wrappers.<Subject>lambdaQuery()
            .eq(Subject::getId, subjectId)
            .eq(Subject::getOrganizationId, organizationId)) == 0) {
        throw BusinessException.notFound("科目不存在");
    }
}
```

- ⚠ `requireSubjectOwned` 是 `private`（`GradeSubjectService` 里有一个**同名同实现**的 private 方法，
  见 §7.2）。**两处重复的代码**，各自对齐各自的 Python 行 —— 属于 §10 的可维护性问题，不是行为问题。

### 5.3 `Teacher` 实体（63 行）—— 以及一个**真实的跨模块差异**

```java
@TableName("teachers")
public class Teacher {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private String name;
    private Long subjectId;
    private Integer maxWeeklyHours;    // ⚠ 注意是 Integer（可空）
```

**⚠ 类注释（`Teacher.java:10-11`）点出了一个跨模块语义**：

> `max_weekly_hours` 是**排课的硬约束输入**：一位教师一周最多上多少节。
> 注意它和「教师不可用时段」是**两回事** —— 后者在 solver-core 里是规则参数，**不在这张表**。

**⚠ 表定义里的 `max_weekly_hours` 是 `NOT NULL`**（`V1__baseline_legacy.sql:139`：
`` `max_weekly_hours` int NOT NULL ``），但 **Java 实体用的是可空 `Integer`、DTO 也是 `Integer`**。
这是刻意的宽松：`TeacherUpdateRequest.maxWeeklyHours` 为 null 表示"不改它"（PATCH 语义要求能区分 null）。
**风险**：如果有人构造 `Teacher` 时忘了设 `maxWeeklyHours` 就走 `updateById`，
MyBatis-Plus 会因为 null 而**跳过该列**（不会写 NULL，也不会报错）——
即"看起来改了、其实没改"。在 update 路径上这恰好是我们要的行为（因为我们是先查再改，字段都非 null）。

**⚠ `TeacherResponse.maxWeeklyHours` 也是 `Integer`**，对应 Python `TeacherOut.max_weekly_hours: int`
（`schemas.py:81`）—— 但 Python 的模型列是 nullable 的（`models.py` 里才有最终答案）。
**此处未完全理解的点**：Python `schemas.py` 声明为 `int`（非 Optional），
如果库里真有 `NULL`，Pydantic 会校验失败；而 Java 会原样输出 `null`。
由于表定义是 `NOT NULL`，**实际上取不到 NULL** —— 所以两边都观察不到差异。
**代码行为是**：`TeacherResponse` 允许 null，序列化成 `"max_weekly_hours": null`。

### 5.4 `TeacherMapper`（9 行）与 teacher 包的 DTO

`TeacherMapper` 与 `GradeMapper` 逐字相同（空 `BaseMapper<Teacher>`）。
DTO 见 §1.4.1 / §1.4.2 / §1.4.4。

---

## 6. `assignment` 包 —— 任教关系（5 个接口，9 个类）

**定位**：`teacher_assignments` 表的 CRUD **+ 本模块最重的两个算法接口**
（`autoAssign` 自动分配、`setTeacherClasses` 覆盖式设置教师班级）。
**这是本篇最需要逐行读的一段。**

### 6.1 `AssignmentController`（93 行，5 个接口）

**一句话定位**：`/api/assignments/*` 的 HTTP 入口。
**谁调它**：前端 `getAssignments/createAssignment/autoAssignTeachers/setTeacherClasses/deleteAssignment`
（`frontend/src/api/index.ts:133-144`）。

**⚠ 类注释（`AssignmentController.java:23-26`）点出了一个刻意的形状不一致**：

> `GET` 是**分页列表**（返回 `{items,total}`），
> 而 `PUT /teacher/{id}` 返回的是**裸数组** ——
> 因为它是写操作，返回的是"操作后的状态"，**不属于 §9 的分页契约范围**。两者形状不同是刻意的。

**⚠ 这个类还是本篇唯一"用全限定名写类型"的类**：

```java
public com.orbit.common.dto.PageResponse<AssignmentResponse> list(
        @RequestParam(name = "teacher_id", required = false) Long teacherId,
        @RequestParam(name = "class_id", required = false) Long classId,
        com.orbit.common.dto.PageQuery query,
        @AuthenticationPrincipal OrbitUserPrincipal principal) {
```

- `PageResponse` / `PageQuery` 写成**全限定名**（`com.orbit.common.dto.XXX`）而不是 import。
  功能上完全等价（编译结果一样），**但与本篇其他 6 个 Controller 的风格不一致**。
  属于代码整洁问题（见 §10 U2）。

#### `list(...)` —— **唯一一个在 Controller 里做分页的接口**

```java
long organizationId = requirePrincipal(principal).organizationId();
List<AssignmentResponse> all = assignmentService.list(organizationId, teacherId, classId);  // ① 全量
long total = all.size();                                                                    // ②
int from = Math.min((int) ((long) (query.pageNumber() - 1) * query.pageSize()), all.size()); // ③
int to = Math.min(from + query.pageSize(), all.size());                                      // ④
return com.orbit.common.dto.PageResponse.of(all.subList(from, to), total);                   // ⑤
```

**⚠ 这是本篇唯一的内存分页，必须逐点讲清**：

| 步 | 在做什么 | ⚠ |
|---|---|---|
| ① | Service 把**全量**结果（过滤后）返回 | 过滤在 SQL 里做完了 |
| ② | `total` = 全量条数 | 与真分页的语义一致（`total` 是**满足条件的总条数**） |
| ③ | `from` = `(page-1) * size`，**再夹到 `all.size()`** | ⚠ 双重保护：`Math.min(...)` 防越界；`(long)` 强转防 `(page-1)*size` **int 溢出**（`size` 上限 200，但 `page` 无上限） |
| ④ | `to` = `from + size`，同样夹住 | ⚠ `from + query.pageSize()` 是 int 加法 —— 因为 `from ≤ all.size()`（一个内存 List 的长度），不会溢出 |
| ⑤ | `subList(from, to)` | ⚠ `subList` 返回的是**原列表的视图**（不是拷贝），但这里 `all` 马上被丢弃、`PageResponse` 的紧凑构造器会 `List.copyOf` 做防御性拷贝 → **安全** |

**注释（`AssignmentController.java:46-47`）解释了为什么不做真分页**：
> 内存分页：这一层的过滤在 SQL 里已经做完，条数由业务规模决定
> （一个学校最多几千条分配），**不值得为它单独写一套分页 SQL**。

**⚠ 与真分页的行为差异**：真分页（`selectPage`）是"数据库算 COUNT + LIMIT"，
内存分页是"全查回来再切"。**对外可观察的行为完全一致**（除性能），
所以契约用例能直接通过 —— 但**它确实违反了"分页应该在 SQL 里"的常识**。
当规模从 4 个班涨到 160 个班时（`teacher_assignments` 会到几千行）需要重新评估。

**契约用例**：`assignments 列表`（`list_endpoint`）、
**`assignments 列表默认页（应为 20 条 + total 全量）`**（`python_baseline="first_page"`）。
→ 后一个用例专门验证「默认 `size` 是 20 而不是全量」：
`compare.py:348-363` 的逻辑是「`total` 必须等于 Python 全量的长度，且 `items` 必须是排序后全量的**前 N 条**」。

#### `create` / `delete`

与 grade 同构，**唯一差别是接口路径没有前缀参数**：
- `POST /api/assignments` → `assignmentService.create(orgId, request)`；
- `DELETE /api/assignments/{assignmentId}` → `delete(...)` + `Map.of("ok", true)`。

#### `autoAssign(@RequestBody(required = false) AssignmentAutoRequest request, ...)`

```java
/** 自动分配教师。请求体可省（两个字段都有默认值）—— 所以不加 {@code @Valid}。 */
@PostMapping("/auto")
public AssignmentAutoResponse autoAssign(
        @RequestBody(required = false) AssignmentAutoRequest request,
        @AuthenticationPrincipal OrbitUserPrincipal principal) {
    AssignmentAutoRequest effective = request == null
            ? new AssignmentAutoRequest(null, null) : request;
    return assignmentService.autoAssign(requirePrincipal(principal).organizationId(), effective);
}
```

- ⚠ **`@RequestBody(required = false)` + 不加 `@Valid`**：因为 Python 的
  `AssignmentAutoRequest` 两个字段都**有默认值**（`replace_existing=False`、`subject_ids=[]`），
  所以 `POST /api/assignments/auto` **不带 body 也必须成功**。
  加了 `@Valid` 会让 `@NotNull` 之类的注解生效 —— 但这个 DTO 上**一个校验注解都没有**，
  所以加不加都一样；**真正的关键是 `required = false`**（不加会 400/415）。
- ⚠ **`request == null` 时手动 `new AssignmentAutoRequest(null, null)`**：
  把 null 变成"两个字段都是 null 的实例"，这样 Service 里只需要处理字段级的 null，
  不需要再判 body 的 null。**默认值仍由 Service 落实**（两个字段都 null → false / 空列表）。
- **契约用例**：`assignments/auto` 相关的用例在 CASES 里**没有单列**
  （只有 `assignments 设置教师班级缺字段` 等）。所以这个"可省 body"的行为**没有被影子对比覆盖**。

#### `setTeacherClasses(@PathVariable Long teacherId, @Valid @RequestBody TeacherClassAssignmentsRequest, ...)`

- **⚠ `@Valid` 是必须的**：`TeacherClassAssignmentsRequest.classIds` 是 `@NotNull`，
  契约用例 `assignments 设置教师班级缺字段`（`PUT /api/assignments/teacher/1` body `{}`）
  expect **422** —— 就是靠这个 `@Valid`。
- **返回 `List<AssignmentResponse>`（裸数组）**，不是 `PageResponse`。

### 6.2 `AssignmentService`（392 行）—— 本篇最重的类

**一句话定位**：任教关系的 CRUD + **两个租户级串行的重算法**。
**谁调它**：`AssignmentController`（5 个接口）。

**⚠ 类注释（`AssignmentService.java:33-35`）**：
> 含两个"重"接口：自动分配（`autoAssign`）与设置教师班级（`setTeacherClasses`）。
> **两者都先对租户行加写锁再操作。**

依赖 **6 个 Mapper**（本篇最多）—— 每个都有明确用途：

| Mapper | 用途 |
|---|---|
| `assignmentMapper` | 主表 CRUD |
| `teacherMapper` | 校验教师归属 + 查名字 + 按科目分组 |
| `classMapper` | 校验班级归属 + 查名字 + 按 id 索引 |
| `subjectMapper` | 校验科目归属 + 查名字 + 科目名兜底 |
| `gradeSubjectMapper` | **周课时**（负载计算与可行性校验的输入） |
| `organizationMapper` | **租户级写锁**（`lockById`） |

#### `list(long organizationId, Long teacherId, Long classId)` → `List<AssignmentResponse>`

```java
var wrapper = Wrappers.<TeacherAssignment>lambdaQuery()
        .eq(TeacherAssignment::getOrganizationId, organizationId);
if (teacherId != null && teacherId != 0L) wrapper.eq(TeacherAssignment::getTeacherId, teacherId);  // ⚠ 0
if (classId != null && classId != 0L)     wrapper.eq(TeacherAssignment::getClassId, classId);      // ⚠ 0
wrapper.orderByAsc(TeacherAssignment::getId);                    // ① 与 Python 同序
return toResponses(wrapper);                                     // ②
```

- **⚠ 两个 `!= 0L`**：逐字对齐 Python 的 `if teacher_id:` / `if class_id:`（`assignments.py:20-23`）。
  见 §3.2 的"贯穿全模块的约定"。
- **① 为什么显式 `orderByAsc(id)`**：Python 没有任何 ORDER BY，返回的是 InnoDB 的主键序。
  显式加上它，**让"内存分页"的切片边界稳定**（否则分页会随执行计划抖动）。
  这是"内存分页"这个设计的必要配套。
- **⚠ 它返回 `List` 而不是 `PageResponse`** —— 分页在 Controller 里做（§6.1）。
  **这是本模块唯一一个把分页放在 Controller 的接口。**
- **② `toResponses(wrapper)` 传的是 wrapper 而不是结果集**：让方法内部决定要不要执行查询
  （见下面的工具方法）。

#### `create(long organizationId, AssignmentCreateRequest request)` → `AssignmentResponse`

```java
@Transactional
public AssignmentResponse create(long organizationId, AssignmentCreateRequest request) {
    Teacher teacher = requireOwned(teacherMapper, Teacher::getId, Teacher::getOrganizationId,
            request.teacherId(), organizationId, "老师不存在");          // ①
    Clazz clazz = requireOwned(classMapper, Clazz::getId, Clazz::getOrganizationId,
            request.classId(), organizationId, "班级不存在");            // ②
    Subject subject = requireOwned(subjectMapper, Subject::getId, Subject::getOrganizationId,
            request.subjectId(), organizationId, "科目不存在");          // ③
    if (!Objects.equals(teacher.getSubjectId(), subject.getId())) {      // ④
        throw BusinessException.badRequest("老师与科目不匹配");
    }
    TeacherAssignment assignment = new TeacherAssignment();
    assignment.setOrganizationId(organizationId);
    assignment.setTeacherId(request.teacherId());
    assignment.setClassId(request.classId());
    assignment.setSubjectId(request.subjectId());
    assignmentMapper.insert(assignment);
    return new AssignmentResponse(assignment.getId(), assignment.getTeacherId(),
            assignment.getClassId(), assignment.getSubjectId(),
            teacher.getName(), clazz.getName(), subject.getName());      // ⑤ 直接用查出来的实体
}
```

- **①②③ 用的是泛型 `requireOwned`**（见 §6.2 的工具方法段），**文案各自独立**：
  `老师不存在` / `班级不存在` / `科目不存在`。对齐 Python `assignments.py:46-48`。
- **⚠ ④ 是"业务规则"而不是"租户规则"**：Python `if teacher.subject_id != subj.id: raise 400 "老师与科目不匹配"`
  （`assignments.py:49-50`）。→ **一位教师只能教自己的科目**。
  ⚠ **用 `Objects.equals(...)` 而不是 `!=`**：比的是 `Long` 对象，
  用 `!=` 比较引用会在 `id > 127` 时**随机出错**（Integer 缓存）。这里的 `subject.getId()` 来自刚查出的实体，
  `teacher.getSubjectId()` 来自另一个实体 —— **两个 Long 实例**，必须用 `equals`。
- **⚠ ⑤ 为什么不重新查名字**：三个实体**就在手边**（①②③ 刚查出来），
  所以直接 `teacher.getName()` —— **比 Python 更省**（Python 在这里也是直接用对象，`assignments.py:57-59`）。
  对比 `list` 的场景（只有 id 列表），那里才需要批量联查。**这个区别是"有对象就用、没对象才查"。**

#### `delete(long organizationId, long assignmentId)` → `void`

```java
TeacherAssignment assignment = assignmentMapper.selectOne(Wrappers.<TeacherAssignment>lambdaQuery()
        .eq(TeacherAssignment::getId, assignmentId)
        .eq(TeacherAssignment::getOrganizationId, organizationId));
if (assignment == null) throw BusinessException.notFound("分配不存在");   // ⚠ 文案（assignments.py:39）
assignmentMapper.deleteById(assignment.getId());
```

#### `autoAssign(long organizationId, AssignmentAutoRequest request)` → `AssignmentAutoResponse`

**⚠ 这是全模块最重要的一个方法。类注释（`AssignmentService.java:108-122`）明确说：
「算法逐句照搬 Python（`routers/assignments.py:104-194`），包括排序键与选人规则 ——
因为它的输出（哪些班分到哪位老师）是**可观察的行为**，改了任何一处都会与 Python 产生不同结果。」**

**Python 原文（`assignments.py:104-194`）分七步**，Java 也分七步。逐段对照：

**(0) 入参规整**

```java
boolean replaceExisting = Boolean.TRUE.equals(request.replaceExisting());   // ⚠ 不是 request.replaceExisting()
List<Long> subjectIds = request.subjectIds() == null ? List.of() : request.subjectIds();
```

- ⚠ `Boolean.TRUE.equals(...)` 而不是 `request.replaceExisting()`：后者在 `Boolean` 为 null 时会 **NPE**。
  对应 Python 的 `replace_existing: bool = False`。
- `subjectIds` 为 null → 空列表，对应 `subject_ids: list[int] = []`。

**(1) 加租户级写锁**

```java
organizationMapper.lockById(organizationId);
```

- 对应 Python `db.query(Organization).filter(...).with_for_update().one()`（`assignments.py:112`）。
- ⚠ **这是本篇唯一一处自定义 SQL 的调用点**。SQL 在 `OrganizationMapper.java:27-28`：
  ```java
  @Select("SELECT id FROM organizations WHERE id = #{id} FOR UPDATE")
  Long lockById(@Param("id") Long id);
  ```
  **逐句讲 SQL**：
  - `SELECT id`（不是 `SELECT *`）—— `FOR UPDATE` 的加锁效果与选择哪些列无关，
    只取 `id` 就够了；少读一个大对象。
  - `FROM organizations WHERE id = #{id}` —— **锁的是"租户那一行"**，不是具体业务表。
    这是"用一个稳定的对象把租户内的操作串行化"的常见手法（`OrganizationMapper.java:22-24` 的注释原话）。
  - `FOR UPDATE` —— InnoDB 的**行级排他锁**，**必须在事务里**（`autoAssign` 上有 `@Transactional`，
    所以有事务），事务提交/回滚时自动释放。
  - `#{id}` 是 MyBatis 的参数占位（编译成 JDBC 的 `?`，**不是字符串拼接** → 无注入风险）。
    ⚠ **不能写成 `${id}`** —— 那是字符串替换，会注入。
- **锁的粒度含义**（注释 `OrganizationMapper.java:22-25`）：
  「**这两个接口在同一学校内是串行的**（不同学校互不影响）」。
- **不加锁的后果**：两个管理员同时提交，会**各自基于过期的分配情况做决策**，
  产生重叠或超额的分配（Python 注释 `assignments.py:110-111` 也是这个意思）。

**(2) 拉取本租户的全部输入 + 校验科目范围**

```java
List<Clazz> classes = classMapper.selectList(... org ...);                    // 全部班级
List<Teacher> teachers = teacherMapper.selectList(... org ...);               // 全部教师
Map<Long, Subject> subjects = subjectMapper.selectList(... org ...)
        .stream().collect(Collectors.toMap(Subject::getId, Function.identity()));

Set<Long> subjectScope = new HashSet<>(subjectIds);
if (!subjectScope.isEmpty() && !subjects.keySet().containsAll(subjectScope)) {
    throw BusinessException.badRequest("包含不存在或不属于当前学校的科目");    // ⚠ 400 + 文案
}
```

- 对应 Python `assignments.py:113-118`。
- ⚠ **`containsAll`（单向包含）vs Python 的 `subject_scope.issubset(subjects)`** ——
  这里两边**语义完全相同**（都是"请求的每一个科目都存在"）。
  （对比 §4.2 的 `ensureGradesOwnedBy`：那里 Java 用单向、Python 用双向相等，
  靠"found ⊆ requested"的前提保证等价；这里两边都是单向。）
- **契约用例**：`包含不存在或不属于当前学校的科目` 对应的用例**没有单独列在 CASES 里**
  （CASES 只有 grades/subjects/classes 的非法引用用例）。

**(3) 查需求（周课时表），并按范围过滤**

```java
var requirementQuery = Wrappers.<GradeSubject>lambdaQuery()
        .eq(GradeSubject::getOrganizationId, organizationId);
if (!subjectScope.isEmpty()) requirementQuery.in(GradeSubject::getSubjectId, subjectScope);
List<GradeSubject> requirements = gradeSubjectMapper.selectList(requirementQuery);
```

- 对应 Python `assignments.py:119-122`。
- ⚠ **`in` 只在 scope 非空时加**：空 scope 表示"全部科目"，
  加 `IN ()` 会生成非法 SQL —— 这个"空集合 → 不过滤"的语义**与 Python 一致**
  （Python 的 `if subject_scope:` 也是这个意思）。

**(4) `replace_existing` 时先删旧分配**

```java
if (replaceExisting) {
    var deleteQuery = Wrappers.<TeacherAssignment>lambdaQuery()
            .eq(TeacherAssignment::getOrganizationId, organizationId);
    if (!subjectScope.isEmpty()) deleteQuery.in(TeacherAssignment::getSubjectId, subjectScope);
    assignmentMapper.delete(deleteQuery);
}
```

- 对应 Python `assignments.py:124-131`（含 `db.flush()`）。
- ⚠ **删除范围也受 `subjectScope` 限制** —— `replace_existing=true` 且指定了科目时，
  只删这些科目的旧分配，不动别的科目。
- ⚠ **Java 没有对应的 `flush()`**：MyBatis-Plus 的 `delete` 是**立即执行的 SQL**（不是延迟到 commit），
  所以后面的 `selectList` 一定看得到删除结果。Python 需要 `flush()` 是因为 ORM 的 UoW 可能还挂着未刷新的状态。

**(5) 重新查 existing（**删除之后**），算负载**

```java
List<TeacherAssignment> existing = assignmentMapper.selectList(... org ...);     // ① 删之后再查
Map<Long, Clazz> classesById = classes.stream()
        .collect(Collectors.toMap(Clazz::getId, Function.identity()));
Map<String, Integer> hoursByGradeSubject = requirements.stream()
        .collect(Collectors.toMap(r -> key(r.getGradeId(), r.getSubjectId()),
                GradeSubject::getWeeklyHours, (a, b) -> a));                     // ② 复合键
Set<String> assignedKeys = existing.stream()
        .map(a -> key(a.getClassId(), a.getSubjectId())).collect(Collectors.toSet());  // ③

Map<Long, Integer> loads = new HashMap<>();
teachers.forEach(t -> loads.put(t.getId(), 0));                                   // ④ 每人从 0 开始
for (TeacherAssignment assignment : existing) {
    Clazz clazz = classesById.get(assignment.getClassId());
    if (clazz != null) {
        int hours = hoursByGradeSubject.getOrDefault(
                key(clazz.getGradeId(), assignment.getSubjectId()), 0);           // ⑤ 按 (年级,科目) 取课时
        loads.merge(assignment.getTeacherId(), hours, Integer::sum);              // ⑥ 累加
    }
}
```

- **① 必须"删之后再查"**：`existing` 是"保留下来的分配"（Python 也是这个顺序，`assignments.py:133`）。
  `preserved_count` 就是它的 size（见第 7 步）。
- **② `hoursByGradeSubject` 的键是 `gradeId + ":" + subjectId` 的字符串**（`key()` 辅助方法，
  `AssignmentService.java:334-336`）。⚠ 类注释专门标了：
  「用复合键 `(grade_id, subject_id)`：同一对只会有一条（DB 有唯一约束）」
  （`uq_grade_subject_org`，`V1__baseline_legacy.sql:121`）。
  **用字符串拼键是安全的**：两个都是 `Long`，`1 + ":" + 23` vs `12 + ":" + 3` 不会撞。
- **③ `assignedKeys` 是 `(classId, subjectId)` 的集合** —— 用来判断"这个班这个科目已经有人教了"。
- **④⑤⑥ 负载计算**：**这是"不是从零开始排"的关键**。已存在的分配所对应的课时**先计入负载**，
  这样自动分配不会把已经在教课的老师当成空手。
  ⚠ `loads.merge(teacherId, hours, Integer::sum)` —— 如果 `teacherId` 不在 `loads` 里（理论上不会，
  因为 ④ 已经把全体教师放进去了），`merge` 会以 0 为基础 put。
  Python 写的是 `loads[assignment.teacher_id] = loads.get(...) + hours`，**语义相同**。
  ⚠ **`clazz != null` 才累加**：班可能已被删（`existing` 是历史分配），跳过（Python `if cls:` 一致）。

**(6) 建"任务列表"并按 Python 的排序键排序**

```java
Map<Long, List<Teacher>> teachersBySubject = new HashMap<>();
for (Teacher teacher : teachers) {
    teachersBySubject.computeIfAbsent(teacher.getSubjectId(), k -> new ArrayList<>()).add(teacher);
}

record Job(int weeklyHours, Clazz clazz, Long subjectId) { }        // ⚠ 方法内局部 record
List<Job> jobs = new ArrayList<>();
for (Clazz clazz : classes) {
    for (GradeSubject requirement : requirements) {
        if (Objects.equals(requirement.getGradeId(), clazz.getGradeId())
                && !assignedKeys.contains(key(clazz.getId(), requirement.getSubjectId()))) {
            jobs.add(new Job(requirement.getWeeklyHours(), clazz, requirement.getSubjectId()));
        }
    }
}
jobs.sort(Comparator.comparingInt(Job::weeklyHours).reversed()      // ① 周课时降序
        .thenComparing(job -> job.clazz().getId())                  // ② 班级 id 升序
        .thenComparing(Job::subjectId));                            // ③ 科目 id 升序
```

- **`teachersBySubject`**：对应 Python `teachers_by_subject.setdefault(...)`（`assignments.py:149-151`）。
  ⚠ **key 可能是 null**（`teachers.subject_id` 是 NOT NULL，所以实际不会）—— `HashMap` 允许 null key。
- **`record Job(...)` 是方法内局部 record**（Java 16+）：Python 用的是三元组 `(weekly_hours, cls, subject_id)`。
  ⚠ 用 record 而不是三元组是**有意的**：`job.weeklyHours()` 比 `job[0]` 更难写错。
- **排序键逐字对应 Python `jobs.sort(key=lambda item: (-item[0], item[1].id, item[2]))`**：
  | Python | Java |
  |---|---|
  | `-item[0]`（周课时**降序**） | `Comparator.comparingInt(Job::weeklyHours).reversed()` |
  | `item[1].id`（班级 id 升序） | `.thenComparing(job -> job.clazz().getId())` |
  | `item[2]`（科目 id 升序） | `.thenComparing(Job::subjectId)` |
  ⚠ **`reversed()` 只作用于它前面那个 `comparingInt`**（不是整个比较器）—— 这是正确的用法，
  但如果写成 `Comparator.comparingInt(...).thenComparing(...).reversed()` 就会**把后两级也反转**，
  结果与 Python 不同。**这是一个非常容易写错、且不报错的地方。**
- **为什么"先满足课时多的"**（类注释 `AssignmentService.java:116-117`）：
  「先满足课时多的，**避免大科目被挤掉**」。
  比如数学 6 节/周、音乐 1 节/周，如果先排音乐，可能把唯一那位音乐老师的时间占满，
  而数学老师的选择更多 —— 反过来则不会有这个问题。
- **⚠ `subjectId` 可能为 null 的排序风险**：`thenComparing(Job::subjectId)` 若遇到 null 会 **NPE**。
  `grade_subjects.subject_id` 是 NOT NULL，所以取不到 null。

**(7) 逐个任务选人（或记为 unassigned）**

```java
int assignedCount = 0;
List<String> unassigned = new ArrayList<>();
for (Job job : jobs) {
    int weeklyHours = job.weeklyHours();
    List<Teacher> candidates = teachersBySubject.getOrDefault(job.subjectId(), List.of())
            .stream()
            .filter(t -> loads.getOrDefault(t.getId(), 0) + weeklyHours
                    <= t.getMaxWeeklyHours())                        // ① 容量约束
            .toList();
    if (candidates.isEmpty()) {
        Subject subject = subjects.get(job.subjectId());
        String subjectName = subject != null ? subject.getName() : "科目" + job.subjectId();   // ② 兜底
        unassigned.add(job.clazz().getName() + "－" + subjectName
                + "（需要 " + weeklyHours + " 节/周）");                                     // ③ 文案
        continue;
    }
    Teacher chosen = candidates.stream()
            .min(Comparator
                    .comparingDouble((Teacher t) -> loads.getOrDefault(t.getId(), 0)
                            / (double) Math.max(t.getMaxWeeklyHours(), 1))   // ④ 负载率
                    .thenComparingInt(t -> loads.getOrDefault(t.getId(), 0)) // ⑤ 绝对负载
                    .thenComparing(Teacher::getId))                          // ⑥ id 小者优先
            .orElseThrow();
    TeacherAssignment assignment = new TeacherAssignment();
    assignment.setOrganizationId(organizationId);
    assignment.setTeacherId(chosen.getId());
    assignment.setClassId(job.clazz().getId());
    assignment.setSubjectId(job.subjectId());
    assignmentMapper.insert(assignment);                             // ⑦ ⚠ 立即 insert
    loads.merge(chosen.getId(), weeklyHours, Integer::sum);           // ⑧ 更新负载
    assignedCount++;
}
```

- **① 候选人筛选**：`已有负载 + 本次课时 ≤ 教师上限`。
  ⚠ **`t.getMaxWeeklyHours()` 若为 null 会 NPE**（自动拆箱）。表定义 NOT NULL，所以实际不会。
  对应 Python `loads.get(...) + weekly_hours <= teacher.max_weekly_hours`。
- **② 科目名兜底 `"科目" + subjectId`**：逐字对齐 Python
  `subjects.get(subject_id).name if subjects.get(subject_id) else f"科目{subject_id}"`（`assignments.py:168`）。
  ⚠ 会走到这个分支的场景：`subjectScope` 非空时 `requirements` 被过滤过，
  但 `subjects` 是本租户全量 —— 所以兜底**几乎不可能触发**（防御性）。
- **③ `unassigned` 的文案格式**：`班级名－科目名（需要 N 节/周）`。
  ⚠ **中间那个是全角连字符 `－`（U+FF0D），不是 ASCII 的 `-`**！
  Python 源码里是 `f"{cls.name}－{subject_name}（需要 {weekly_hours} 节/周）"` —— 同样是全角。
  **这是一个"复制粘贴时极易被改成半角"的契约字符**。同理还有 `（`/`）` 是全角括号。
- **④ 选人键：负载率 = `负载 / max(上限, 1)`**
  - ⚠ **`Math.max(t.getMaxWeeklyHours(), 1)`**：防止**上限为 0 时除零**。
    对应 Python `max(item.max_weekly_hours, 1)`（`assignments.py:174`）。
    ⚠ 但注意：这里的类型是 `int`（`Math.max(int,int)`），而分子被转成 `(double)` ——
    **浮点除法**，不是整数除法。写成 `loads / Math.max(...)` 而不加 `(double)` 会得到整数除法，
    **选人结果完全不同且不报错**。
  - **"负载率最低者优先"的含义**：让老师的负荷**均衡**（一位上限 18 节、已排 9 节的老师，
    负载率 0.5；一位上限 6 节、已排 4 节的老师，负载率 0.667 → 选前者）。
- **⑤ 并列时取绝对负载低的**，**⑥ 再并列取 id 小的**。
  ⚠ `min(Comparator)` 在完全并列时返回**第一个**遇到的元素，而 `candidates` 的顺序
  来自 `teachersBySubject` 的 `ArrayList`（即 `teacherMapper.selectList` 的返回顺序）。
  加了 ⑥ 之后**结果不再依赖数据库返回顺序** —— 这是必要的，否则同一份数据两次运行可能给出不同结果。
  **Python 也是靠 `item.id` 兜底**（`assignments.py:176`）。
- **⑦ 立即 `insert`（不是收集起来批量插）**：
  Python 是 `db.add(...)` 收集、最后 `db.commit()`（`assignments.py:179-188`）。
  Java 逐条 `insert` 在同一个事务里 —— **最终结果相同**，只是 SQL 条数多。
  ⚠ **但 `loads` 的更新必须手动做**（⑧）—— Python 也是手动做的，
  因为 `db.add` 之后 ORM 并不会自动帮你算负载。
- **⚠ 整个循环的复杂度是 O(jobs × teachers)**，且**每个 job 都可能发一条 INSERT**。
  160 班规模时 `jobs` 约 160×10 = 1600 条，`teachers` 几百 → 几十万次比较 + 1600 条 INSERT。
  **在同一个事务里、且持有租户写锁** —— 这是"这两个接口在同一学校内串行"的实际代价。已记入 §10。

**收尾（返回体）**：

```java
Map<String, Integer> teacherLoads = new LinkedHashMap<>();
for (Teacher teacher : teachers) {
    teacherLoads.put(teacher.getName(), loads.getOrDefault(teacher.getId(), 0));   // ⚠ key 是姓名
}
return new AssignmentAutoResponse(assignedCount, existing.size(), unassigned, teacherLoads);
```

- ⚠ **`preserved_count = existing.size()`**（不是 `teachers.size()` 也不是 `jobs.size()`）。
  对应 Python `preserved_count=len(existing)`（`assignments.py:191`）。
  **它的含义**：本次运行后**保留下来的旧分配条数**（`replace_existing=false` 时就是全部的旧分配）。
- ⚠ **`teacherLoads` 用 `LinkedHashMap` + 姓名做 key**：
  - 用 `LinkedHashMap` 是为了**输出顺序稳定**（Python 的 dict 也是插入序 → `teachers` 的查询顺序）。
  - ⚠ **同名教师会互相覆盖**（`AssignmentAutoResponse.java:11` 注明「既有行为，不在 P1 修正」）——
    **两边都这样，所以是一致的**。但这是个潜伏的坑：如果学校有两位"张老师"，
    返回的 `teacher_loads` 里只有一条。
- ⚠ **`assignedCount` 与 `existing.size()` 是两个不同的数**：
  `assigned_count` 是本次新分配数，`preserved_count` 是保留的旧分配数。
  `assigned_count` 用 `int` 累加（方法内局部变量），不是 `existing` 的差值 ——
  与 Python 一致（Python 也是单独的计数器）。

#### `setTeacherClasses(long organizationId, long teacherId, TeacherClassAssignmentsRequest request)` → `List<AssignmentResponse>`

**⚠ 类注释（`AssignmentService.java:250-264`）列了三条校验的**逐字文案**，值得整段引用**：

> 三条校验，文案逐字对齐 Python：
> - 400 `包含不存在或不属于当前学校的班级`
> - 400 `这些班级所属年级没有开设该科目：A、B`
> - 400 `所选班级合计 N 节/周，超过教师上限 M 节/周`

**逐段拆解**：

```java
@Transactional
public List<AssignmentResponse> setTeacherClasses(long organizationId, long teacherId,
                                                  TeacherClassAssignmentsRequest request) {
    organizationMapper.lockById(organizationId);                   // ① 同一把租户锁

    Teacher teacher = teacherMapper.selectOne(Wrappers.<Teacher>lambdaQuery()
            .eq(Teacher::getId, teacherId)
            .eq(Teacher::getOrganizationId, organizationId));
    if (teacher == null) throw BusinessException.notFound("教师不存在");   // ② ⚠ 文案是「教师」
```

- **① 与 `autoAssign` 用同一把锁** → **这两个接口互相之间也串行**。
- ⚠ **② 文案 `教师不存在`**（Python `assignments.py:205` 是 `"教师不存在"`），
  **与 `TeacherService.requireOwned` 的 `老师不存在` 不同**。见 §5.2 的说明与 §9 的清单。
  ⚠ 而且这里**没有复用 `TeacherService.requireOwned`**（它就在同一个模块里、文案只差一个字）——
  因为**文案不同，不能复用**。这是一个"看起来是重复代码、实际上不能合并"的例子。

```java
    Set<Long> requestedIds = new HashSet<>(request.classIds());
    List<Clazz> ownedClasses = requestedIds.isEmpty() ? List.of()
            : classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                    .eq(Clazz::getOrganizationId, organizationId)
                    .in(Clazz::getId, requestedIds));
    if (!ownedClasses.stream().map(Clazz::getId).collect(Collectors.toSet()).equals(requestedIds)) {
        throw BusinessException.badRequest("包含不存在或不属于当前学校的班级");       // ③
    }
```

- **③ 用的是"双向集合相等"**（`ownedIds.equals(requestedIds)`），
  对应 Python `if {item.id for item in owned_classes} != requested_ids:`（`assignments.py:211`）——
  **逐字对应**。（对比 §4.2 的 `containsAll`：那里 Python 也是双向，Java 用了单向 + 前提论证。）
- ⚠ **`requestedIds.isEmpty()` 短路**：`IN ()` 非法，必须短路（Python 用 `if requested_ids else []`）。
- ⚠ **`request.classIds()` 里如果有重复 id**：塞进 `HashSet` 会去重 →
  `requestedIds.size()` 小于原始列表长度。Python 也是 `set(data.class_ids)`，**两边一致**。

```java
    Map<Long, Integer> offeredHours = gradeSubjectMapper.selectList(
                    Wrappers.<GradeSubject>lambdaQuery()
                            .eq(GradeSubject::getOrganizationId, organizationId)
                            .eq(GradeSubject::getSubjectId, teacher.getSubjectId())
                            .gt(GradeSubject::getWeeklyHours, 0))          // ④ ⚠ > 0
            .stream()
            .collect(Collectors.toMap(GradeSubject::getGradeId, GradeSubject::getWeeklyHours, (a, b) -> a));

    List<String> invalidClasses = ownedClasses.stream()
            .filter(c -> !offeredHours.containsKey(c.getGradeId()))
            .map(Clazz::getName).toList();
    if (!invalidClasses.isEmpty()) {
        throw BusinessException.badRequest(
                "这些班级所属年级没有开设该科目：" + String.join("、", invalidClasses));   // ⑤
    }
```

- **④ `gt(weeklyHours, 0)`**：课时为 0 的记录不算"开设"。对应 Python 的
  `.filter(GradeSubject.weekly_hours > 0)`（`assignments.py:220`）。
  ⚠ **注意对比 §7.2 的 `bulkUpdate`：那条路径把 `weekly_hours = 0` 当作"删除记录"**，
  所以正常情况下不会有 0 的记录 —— 但如果有（历史数据 / 直接改库），这里会把它排除。
- **⑤ 文案的拼接细节**：
  - 分隔符是 **`、`（全角顿号）** —— `String.join("、", invalidClasses)`，逐字对齐 Python 的 `'、'.join(...)`。
  - 冒号是 **`：`（全角冒号）**。
  - ⚠ **`invalidClasses` 的顺序 = `ownedClasses` 的顺序**（即 `classMapper.selectList` 的返回顺序）。
    Python 也是 `[item.name for item in owned_classes ...]`，顺序来自查询。
    **没有 ORDER BY** → 顺序取决于执行计划/主键序。**两边都没排序，所以是"同样的不稳定"。**
    契约测试若要覆盖这条文案，必须先把 class 顺序固定下来。

```java
    int totalHours = ownedClasses.stream()
            .mapToInt(c -> offeredHours.getOrDefault(c.getGradeId(), 0)).sum();
    if (totalHours > teacher.getMaxWeeklyHours()) {
        throw BusinessException.badRequest("所选班级合计 " + totalHours + " 节/周，超过教师上限 "
                + teacher.getMaxWeeklyHours() + " 节/周");                        // ⑥
    }
```

- **⑥ 文案的拼接**：`所选班级合计 {total} 节/周，超过教师上限 {max} 节/周`。
  ⚠ **空格的位置也要对齐**：`合计 ` 后面一个半角空格、`节/周，` 后面全角逗号、
  `上限 ` 后面一个半角空格、`节/周` 结尾。
  Python 原文：`f"所选班级合计 {total_hours} 节/周，超过教师上限 {teacher.max_weekly_hours} 节/周"`
  （`assignments.py:233`）—— **逐字相同，包括半角空格**。
- ⚠ **超过了才报错（`>`），等于上限是允许的**。
- ⚠ `getMaxWeeklyHours()` 若为 null → NPE。表定义 NOT NULL。

```java
    assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
            .eq(TeacherAssignment::getOrganizationId, organizationId)
            .eq(TeacherAssignment::getTeacherId, teacher.getId()));           // ⑦ 先删该教师全部旧分配
    if (!requestedIds.isEmpty()) {
        assignmentMapper.delete(Wrappers.<TeacherAssignment>lambdaQuery()
                .eq(TeacherAssignment::getOrganizationId, organizationId)
                .eq(TeacherAssignment::getSubjectId, teacher.getSubjectId())
                .in(TeacherAssignment::getClassId, requestedIds));             // ⑧ 再从别的教师手里抢
        for (Long classId : requestedIds.stream().sorted().toList()) {         // ⑨ ⚠ 排序
            TeacherAssignment assignment = new TeacherAssignment();
            assignment.setOrganizationId(organizationId);
            assignment.setTeacherId(teacher.getId());
            assignment.setClassId(classId);
            assignment.setSubjectId(teacher.getSubjectId());
            assignmentMapper.insert(assignment);
        }
    }
    return list(organizationId, teacher.getId(), null);                        // ⑩
```

- **⑦⑧ 的顺序不可交换**：
  先删"这个教师的全部旧分配"（⑦），再删"这些班这个科目的分配（不管是谁的）"（⑧）。
  ⚠ **⑧ 是"从别的教师手里抢过来"** —— 保证**一个班的某科目只有一位负责教师**（注释 `:260-261`）。
  如果只做 ⑦ 不做 ⑧，会出现一个班一个科目**两位**教师（撞 `uq_assignment_org` 唯一约束 → 500）。
- **⑨ `sorted()` 是关键**：`requestedIds` 是 `HashSet`，**迭代顺序不确定**。
  Python 写的是 `for class_id in sorted(requested_ids)`（`assignments.py:254`）。
  → **必须 `sorted()`**，否则 INSERT 的顺序不稳定，分配的 `id` 会不同，
  而 `id` 出现在响应里（→ 契约不一致）。
- **⚠ `requestedIds.isEmpty()` 时**：⑦ 仍然执行（清空该教师的**所有**分配），⑧⑨ 跳过。
  这正是「空列表 = 清空」的语义（`TeacherClassAssignmentsRequest.java:10-11` 的注释强调了这点）。
- **⑩ 直接调 `list(...)` 复用查询**：对应 Python 结尾的 `return list_assignments(teacher_id=teacher.id, ...)`
  （`assignments.py:257`）—— **Python 也是复用的**。
  ⚠ `classId` 传 null → 不过滤；`teacherId` 非 null → 只返回该教师的。
  ⚠ **但注意 `list` 会按 `id ASC` 排序**，而 Python 的复用也是同一条查询 → 一致。

**契约用例**：`assignments 设置教师班级缺字段`（422）、
`assignments 设置教师班级-非法教师`（`teacher/999999` + `{"class_ids": []}` → **404**）。

#### 工具方法（4 个）

**(a) `private static String key(Long gradeOrClassId, Long subjectId)`**

```java
return gradeOrClassId + ":" + subjectId;
```

- ⚠ **参数名 `gradeOrClassId` 说明它是被复用的**：在 `autoAssign` 里，
  它对 `GradeSubject` 用 `(gradeId, subjectId)`，对 `TeacherAssignment` 用 `(classId, subjectId)` ——
  **两套不同的语义共用同一个方法**。这是刻意的（两处都只是"复合键"），
  但**读代码时容易误以为它只用在一个地方**。
- ⚠ **用字符串拼键而不是 `record` / `Map.Entry`**：性能上没必要（数据量小），
  可读性上 `record` 更好。**这是可以改进的点**（但不影响行为，见 §10 U4）。

**(b) `private List<AssignmentResponse> toResponses(LambdaQueryWrapper<TeacherAssignment> wrapper)`**

```java
List<TeacherAssignment> rows = assignmentMapper.selectList(wrapper);       // ① 在这里才执行查询
if (rows.isEmpty()) return List.of();                                      // ② 提前返回
Map<Long, String> teacherNames = names(rows.stream().map(TeacherAssignment::getTeacherId).toList(),
        ids -> teacherMapper.selectList(Wrappers.<Teacher>lambdaQuery()
                .select(Teacher::getId, Teacher::getName).in(Teacher::getId, ids)),
        Teacher::getId, Teacher::getName);                                 // ③ 三次批量查
Map<Long, String> classNames = names(..., classMapper ...);
Map<Long, String> subjectNames = names(..., subjectMapper ...);
return rows.stream()
        .map(a -> new AssignmentResponse(a.getId(), a.getTeacherId(), a.getClassId(),
                a.getSubjectId(), teacherNames.get(a.getTeacherId()),
                classNames.get(a.getClassId()), subjectNames.get(a.getSubjectId())))
        .toList();
```

- **对应 Python `assignments.py:16-32`**：Python 是
  `joinedload(TeacherAssignment.teacher, TeacherAssignment.class_)`（teacher 和 class 一次 JOIN 拿回）
  **+ 循环里每行再查一次 Subject**（`subj = db.query(Subject).filter(...).first()`）→ **N+1**。
  Java 改成**三次批量 IN 查询**（教师、班级、科目各一次）→ **总共 4 条 SQL**，效果等价。
- ⚠ **参数是 `wrapper` 而不是结果集** —— 因为这个方法既被 `list` 用（要复用过滤条件），
  也被 `setTeacherClasses` 间接用。**传 wrapper 就意味着"查询在这里发生"**，
  这样 `list` 的代码不必先执行查询再传结果。
- ⚠ **② `rows.isEmpty()` 提前返回**：避免三次 `IN ()`（非法 SQL）。**这三处 + `ClassService` 两处 +
  `TeacherService` 一处，构成"空集合必须短路"这个在本篇出现 6 次的约定。**
- ⚠ **③ 三个 `select` 都只取 `id` 和 `name`**：不读整行。

**(c) `private static <T> Map<Long, String> names(Collection<Long> rawIds, Function<List<Long>,List<T>> loader, Function<T,Long> idOf, Function<T,String> nameOf)`**

```java
List<Long> ids = rawIds.stream().filter(Objects::nonNull).distinct().toList();
if (ids.isEmpty()) return Map.of();                                 // ⚠ 短路（IN () 非法）
return loader.apply(ids).stream().collect(Collectors.toMap(idOf, nameOf, (a, b) -> a));
```

- **这是一个泛型"批量取名"工具**：把「拿 id 列表 → 查 → 变成 id→name 的 Map」抽成了一个方法，
  三次调用共用。**这是本篇唯一一处把"批量取名"抽象掉的地方**（`ClassService.gradeNames` 和
  `TeacherService.subjectNames` 各自写了一份具体实现）。
- ⚠ **`loader` 是一个 `Function<List<Long>, List<T>>`**（接受 id 列表、返回实体列表）——
  这样就把"用哪个 Mapper、过滤什么条件"交给调用方，方法本身只负责去重/短路/收 Map。

**(d) `private static <T> T requireOwned(BaseMapper<T> mapper, SFunction<T,?> idColumn, SFunction<T,?> orgColumn, Long id, long organizationId, String notFoundMessage)`**

```java
if (id == null) throw BusinessException.notFound(notFoundMessage);      // ① null 也走 404
T found = mapper.selectOne(Wrappers.<T>lambdaQuery()
        .eq(idColumn, id)
        .eq(orgColumn, organizationId));                                // ② 两个条件
if (found == null) throw BusinessException.notFound(notFoundMessage);   // ③
return found;
```

- **⚠ 用全限定名写参数类型**（`com.baomidou.mybatisplus.core.mapper.BaseMapper<T>`），
  与 §6.1 的 Controller 同源 —— 没 import。风格问题。
- **这是 `AssignmentService` 与 `GradeFixedSlotService` 各自复制了一份的泛型方法**
  （`GradeFixedSlotService.java:204-218` 逐字相同）。两处重复（见 §10 U4）。
- ⚠ **① `id == null` 也返回 404**：对应 Python `owned_or_404` 的实现
  （如果 `id` 是 None，`filter(Model.id == None)` 也查不到 → 404）。
  ⚠ 但在正常路径上 `@NotNull` 已经挡成 **422** —— **所以这个 null 分支是防御性的**，
  它对应的"实际会发生 404"的场景是**内部调用**（比如 `AssignmentService` 内部的
  `request.teacherId()`）—— 而那条路径也被 `@NotNull` 挡着。
  **结论：null 分支目前不可达**（这是好事，说明两层校验都在）。

### 6.3 `TeacherAssignment` 实体（63 行）与 `TeacherAssignmentMapper`（9 行）

```java
@TableName("teacher_assignments")
public class TeacherAssignment {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private Long teacherId;
    private Long classId;
    private Long subjectId;
```

- **唯一约束 `uq_assignment_org (organization_id, teacher_id, class_id, subject_id)`**
  （`V1__baseline_legacy.sql:159`）。
  ⚠ **注意它是"四元组"唯一** —— 所以**同一位教师同一个班同一个科目只能有一条记录**，
  但**同一个班同一个科目可以有两位不同教师**（这就是 §6.2 `setTeacherClasses` 第⑧步要"抢"的原因）。
- ⚠ **`TeacherAssignment` 类名与表名不一致**：表是 `teacher_assignments`，
  实体是 `TeacherAssignment`（单数、无下划线）。**MyBatis-Plus 靠 `@TableName` 显式指定**，
  不靠命名约定（`TeacherAssignment` → 默认会推导成 `teacher_assignment`，**是错的**）。
- **类注释（`TeacherAssignment.java:11`）点出它的系统角色**：
  「这张表是**排课的硬约束来源之一**：引擎靠它知道「一个班的一个科目由哪位老师上」」。

### 6.4 `AssignmentController` 之外 —— assignment 包的 5 个 DTO

见 §1.4.1 / §1.4.2 / §1.4.3 / §1.4.4。
**⚠ 其中一个字段级的坑值得单独强调**（已在 §6.2 展开）：
`AssignmentAutoResponse.teacherLoads` 用**教师姓名**做 key → 同名覆盖。

### 6.5 一个跨模块的说明：`OrganizationMapper.lockById` 为什么在 academic

`lockById` 严格来说属于「组织」领域（3b 篇），但**它唯一的使用者是本篇的 `AssignmentService`**
（两次：`autoAssign`、`setTeacherClasses`），所以在这里讲清它的 SQL 与理由（见 §6.2 第 (1) 步）。

**⚠ 一个必须点出的耦合**：`organizations` 表的属主是 academic，
所以"锁租户"这件事**只有 academic 能做**。如果将来排课侧的并发控制也需要"每校串行"，
scheduling **不能**加这把锁（铁律 2：禁止跨服务写库）——
它只能用 Redis 锁（`ScheduleLockService`，见 [架构说明.md](架构说明.md) §3.3）。
→ **两套"每校互斥"机制用的是不同的底座**，这是跨服务拆分的必然结果。

---

## 7. `subject` 包（下）—— 年级课时 / 周课时（4 个接口，6 个类）

**定位**：`grade_subjects` 表（「某年级某科目每周几课时」）的 CRUD。
**它是排课输入的核心之一**：引擎按它给每个班生成「科目 × 周课时」的需求。
**⚠ 它虽然叫"年级课时"，但表属主是科目领域**，所以类都在 `subject` 包（见 §1.3）。

### 7.1 `GradeSubject` 实体（64 行）与 `GradeSubjectMapper`（9 行）

```java
@TableName("grade_subjects")
public class GradeSubject {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId;
    private Long gradeId;
    private Long subjectId;
    private Integer weeklyHours;
```

- **唯一约束 `uq_grade_subject_org (organization_id, grade_id, subject_id)`**
  （`V1__baseline_legacy.sql:121`）—— **这是 `AssignmentService.autoAssign` 用
  `(grade_id, subject_id)` 做复合键的根据**（见 §6.2 第 (5) 步 ②）。
- **类注释（`GradeSubject.java:12`）**：
  「这个表是**排课输入的核心**之一：引擎按它给每个班生成「科目 × 周课时」的需求」。
- `weeklyHours` 是 `int NOT NULL`（`V1__baseline_legacy.sql:119`），Java 用可空 `Integer`
  （理由同 §5.3：PATCH/部分更新语义需要能表示"不设"）。

### 7.2 `GradeSubjectService`（190 行，7 个方法）

**一句话定位**：年级课时的 upsert / 批量更新 / 删除 + 组装响应时的科目名联查。
**谁调它**：`GradeSubjectController`（4 个接口）。

```java
/**
 * 年级课时（{@code grade_subjects}）业务逻辑 ——
 * 对应 Python {@code routers/assignments.py} 里的 {@code /grade-subjects} 四个接口。
 *
 * <p>为什么和科目放一个包：{@code grade_subjects} 表达的是「**某科目**在某年级的周课时」，
 * 属主是科目领域；实体与 Mapper 也在这里。
 */
```

#### `list(long organizationId, Long gradeId, PageQuery query)` → `PageResponse<GradeSubjectResponse>`

```java
var wrapper = Wrappers.<GradeSubject>lambdaQuery()
        .eq(GradeSubject::getOrganizationId, organizationId);
if (gradeId != null && gradeId != 0L) wrapper.eq(GradeSubject::getGradeId, gradeId);   // ⚠ 0
wrapper.orderByAsc(GradeSubject::getId);                                              // ① 固定序
Page<GradeSubject> page = gradeSubjectMapper.selectPage(new Page<>(...), wrapper);
return PageResponse.of(toResponses(page.getRecords()), page.getTotal());
```

- **⚠ 没有关键字/排序参数**：`query` 只用了 `pageNumber()` / `pageSize()` ——
  **`keyword`/`order_by` 传了也不生效**（Python 也没有这两个参数）。
  这与 grade/class/subject/teacher 的 list **形状不同**（那些都支持 keyword + 排序）。
  ⚠ **但 `PageQuery` 的构造器仍然会校验 `order_by` 吗？** 不会 ——
  `orderColumn(...)` 才是校验入口，这里**根本没调用它** →
  **传 `order_by=任意值` 也不会 400，静默忽略**。
  契约用例里**没有** grade-subjects 的非法排序用例（对比 subjects 有）—— 所以这不是"漏测"，是"该接口不支持排序"。
- **① 为什么显式 `orderByAsc(id)`**：同 §6.2 `list` 的理由 —— Python 没有 ORDER BY，
  显式固定成主键序，让分页边界稳定。
  ⚠ **Python 的 `list_grade_subjects` 还额外返回了本租户的全部记录（不只该科目）**，见 `bulkUpdate` 的收尾。

#### `upsert(long organizationId, GradeSubjectCreateRequest request)` → `GradeSubjectResponse`

```java
@Transactional
public GradeSubjectResponse upsert(long organizationId, GradeSubjectCreateRequest request) {
    requireGradeOwned(organizationId, request.gradeId());        // ① 404「年级不存在」
    requireSubjectOwned(organizationId, request.subjectId());    // ② 404「科目不存在」

    GradeSubject existing = gradeSubjectMapper.selectOne(Wrappers.<GradeSubject>lambdaQuery()
            .eq(GradeSubject::getOrganizationId, organizationId)
            .eq(GradeSubject::getGradeId, request.gradeId())
            .eq(GradeSubject::getSubjectId, request.subjectId()));    // ③ 查 (org, grade, subject)
    GradeSubject entity;
    if (existing != null) {
        existing.setWeeklyHours(request.weeklyHours());
        gradeSubjectMapper.updateById(existing);                      // ④ 已存在 → 改课时
        entity = existing;
    } else {
        entity = new GradeSubject();
        entity.setOrganizationId(organizationId);
        entity.setGradeId(request.gradeId());
        entity.setSubjectId(request.subjectId());
        entity.setWeeklyHours(request.weeklyHours());
        gradeSubjectMapper.insert(entity);                            // ⑤ 不存在 → 新建
    }
    return toResponses(List.of(entity)).get(0);                       // ⑥ 复用组装逻辑
}
```

- **① ② 的顺序**：先年级后科目 —— 对齐 Python `assignments.py:79-80`（先 `Grade` 后 `Subject`）。
  ⚠ **顺序影响错误优先级**：两个都不属于本组织时，报的是"年级不存在"。
  契约用例 `grade-subjects 新建非法年级` expect 404。
- **③ 三个条件一起查**：`(org, grade, subject)`。⚠ **第三个条件不能省** ——
  这就是"upsert 的键"，也是表上的唯一约束。
- **④⑤ 的差异**：Python 在 `existing` 分支里 `db.commit(); db.refresh(existing)`
  （`assignments.py:86-90`），新建分支也是 commit+refresh。
  Java 靠 `@Transactional` 统一提交、靠内存对象值组装响应（见 §2.2 的说明）。
- **⑥ 复用 `toResponses`** —— **这让"带科目名的响应"只有一条组装路径**。
  ⚠ `toResponses(List.of(entity)).get(0)` 这个写法有点绕（造单元素列表再取第一个），
  但它保证了新建/更新两条路径的响应形状**不可能不一致**。
- **⚠ 语义**：这个接口是**幂等的**（同一个请求重复提交结果相同）——
  类注释（`GradeSubjectService.java:58-59`）：「Python 的行为是：已存在 (grade, subject) 就改
  `weekly_hours`，否则新建 —— 所以同一个请求重复提交是幂等的」。
- ⚠ **`weeklyHours` 没有范围校验**（`GradeSubjectCreateRequest` 上只有 `@NotNull`）——
  Python 的 `GradeSubjectCreate` 也没有（`schemas.py:51-54`）。
  → 这条路径能写入 41 或 -5，而 `bulkUpdate` 路径不能。**两边一致**（Python 同样不一致）。

#### `bulkUpdate(long organizationId, GradeSubjectBulkRequest request)` → `List<GradeSubjectResponse>`

**逐段**：

```java
@Transactional
public List<GradeSubjectResponse> bulkUpdate(long organizationId, GradeSubjectBulkRequest request) {
    requireSubjectOwned(organizationId, request.subjectId());          // ① 404「科目不存在」

    Set<Long> gradeIds = request.items().stream()
            .map(GradeSubjectBulkRequest.Item::gradeId)
            .collect(Collectors.toSet());                              // ② 去重
    Set<Long> ownedGradeIds = gradeIds.isEmpty() ? Set.of()
            : gradeMapper.selectList(Wrappers.<Grade>lambdaQuery()
                    .select(Grade::getId)
                    .eq(Grade::getOrganizationId, organizationId)
                    .in(Grade::getId, gradeIds))
            .stream().map(Grade::getId).collect(Collectors.toSet());    // ③ ⚠ 空集短路
    if (!gradeIds.equals(ownedGradeIds)) {
        throw BusinessException.badRequest("包含不存在或不属于当前学校的年级");   // ④
    }
```

- **① 先校验科目**（Python `assignments.py:266`）。
- **②③④ 与 Python `assignments.py:267-275` 逐字对应**：
  `grade_ids` 是 `set`，`owned_grade_ids` 也是 `set`，然后 `!=` 比较（**双向相等**）。
  Java 的 `!gradeIds.equals(ownedGradeIds)` 就是双向相等。✓
- ⚠ **③ 的空集短路**：`grade_ids` 为空时 Python 写的是 `... if grade_ids else set()`（内联三元），
  Java 也是三元。**空 items 时不会发 `IN ()`**。
  ⚠ 而且空 items 时 `gradeIds` = `Set.of()`、`ownedGradeIds` = `Set.of()` → **相等 → 不报错**。
  即 `POST /api/grade-subjects/bulk {"subject_id":1,"items":[]}` **返回 200 + 该科目的全部记录**（不删不改）。
  这与 Python 一致。

```java
    Map<Long, GradeSubject> existing = gradeSubjectMapper.selectList(
                    Wrappers.<GradeSubject>lambdaQuery()
                            .eq(GradeSubject::getOrganizationId, organizationId)
                            .eq(GradeSubject::getSubjectId, request.subjectId()))
            .stream().collect(Collectors.toMap(GradeSubject::getGradeId, gs -> gs, (a, b) -> a));  // ⑤

    for (GradeSubjectBulkRequest.Item item : request.items()) {
        GradeSubject current = existing.get(item.gradeId());
        if (item.weeklyHours() == 0) {                          // ⑥ 0 = 删除
            if (current != null) gradeSubjectMapper.deleteById(current.getId());
        } else if (current != null) {                           // ⑦ 有 → 改
            current.setWeeklyHours(item.weeklyHours());
            gradeSubjectMapper.updateById(current);
        } else {                                                // ⑧ 无 → 建
            GradeSubject created = new GradeSubject();
            created.setOrganizationId(organizationId);
            created.setGradeId(item.gradeId());
            created.setSubjectId(request.subjectId());
            created.setWeeklyHours(item.weeklyHours());
            gradeSubjectMapper.insert(created);
        }
    }
    return allForSubject(organizationId, request.subjectId());   // ⑨
}
```

- **⑤ `existing` 的 key 是 `gradeId`**（同一科目下，一个年级只有一条 —— 唯一约束保证）。
  对应 Python `existing = {item.grade_id: item for item in ...}`（`assignments.py:277-282`）。
- **⑥ `weeklyHours() == 0` → 删除**。类注释（`GradeSubjectService.java:88-89`）：
  「`weeklyHours == 0` 表示**删除**该年级的记录（**不是设为 0**）」。
  ⚠ **`item.weeklyHours()` 是 `Integer`，`== 0` 会触发自动拆箱**。
  如果为 null 会 **NPE** —— 但 `@NotNull` 已经把它挡成 422（契约用例
  `grade-subjects 批量缺字段` expect 422）。
- **⚠ ⑥ 的边界**：`weeklyHours() == 0` 时 `current == null`（本来就没有这条）→ **什么都不做**。
  这是个**静默的 no-op**（幂等，符合直觉）。
- **⑦ 改了 `current` 之后 `updateById`**：⚠ **因为 `current` 是从库里查出来的完整实体，
  所以 `updateById` 会写回所有列** —— 包括 `grade_id` / `subject_id`（值不变）。
  这与"只更新变化的列"是不同的 SQL，但**结果相同**。
- **⑨ `allForSubject(...)` 而**不是**只返回本次涉及的年级**：

```java
/** 某科目的全部年级课时（裸数组），供批量更新后返回。 */
public List<GradeSubjectResponse> allForSubject(long organizationId, Long subjectId) {
    return toResponses(gradeSubjectMapper.selectList(Wrappers.<GradeSubject>lambdaQuery()
            .eq(GradeSubject::getOrganizationId, organizationId)
            .eq(GradeSubject::getSubjectId, subjectId)
            .orderByAsc(GradeSubject::getId)));
}
```

- **⚠ 这就对应 Python 的 `return list_grade_subjects(db=db, user=user)`（`assignments.py:298`）——
  注意 Python 调的是 `list_grade_subjects`（`grade_id` 参数缺省 → **不加 grade 过滤**），
  所以 Python 返回的是「**该租户的全部年级课时**」而不是「该科目的」**！
  Java 的 `allForSubject` **多加了 `.eq(subjectId)`** → 返回「**该科目的**全部年级课时」。
  **这是一个真实的行为差异**（见 §9 与 §10）。**
- ⚠ **响应顺序**：Java 显式 `orderByAsc(id)`；Python 无 ORDER BY（主键序）→ 通常相同。
- ⚠ **返回值是"裸数组"**，不是 `{items,total}`：`GradeSubjectController.java:26-30` 的类注释写明
  「`POST /bulk` 返回**裸数组**（写操作返回"操作后的状态"，不分页）」。
  前端 `frontend/src/api/index.ts:122-123` 也注明了这点。

#### `delete(long organizationId, long gradeSubjectId)` → `void`

```java
GradeSubject entity = gradeSubjectMapper.selectOne(Wrappers.<GradeSubject>lambdaQuery()
        .eq(GradeSubject::getId, gradeSubjectId)
        .eq(GradeSubject::getOrganizationId, organizationId));
if (entity == null) throw BusinessException.notFound("不存在");      // ⚠ 文案只有三个字！
gradeSubjectMapper.deleteById(entity.getId());
```

- **⚠ 文案是 `不存在`（不是"年级课时不存在"）** —— 逐字对齐 Python
  `assignments.py:304` 的 `HTTPException(status_code=404, detail="不存在")`。
  **这是全模块最短的错误文案，也是最容易被"顺手改清楚"改坏的一处。**
  契约用例：`grade-subjects 删除不存在` expect 404。
- ⚠ **本篇还有另一个"极短文案"**：`GradeFixedSlotService.delete` 是 `年级固定课程不存在`（§8.2）——
  **同为"删固定课/课时"，文案长度完全不同**。因为 Python 就不同。

#### 工具方法

```java
private List<GradeSubjectResponse> toResponses(Collection<GradeSubject> rows) {
    if (rows == null || rows.isEmpty()) return List.of();                    // ①
    List<Long> subjectIds = rows.stream().map(GradeSubject::getSubjectId)
            .filter(Objects::nonNull).distinct().toList();
    Map<Long, String> subjectNames = subjectIds.isEmpty() ? Map.of()
            : subjectMapper.selectList(Wrappers.<Subject>lambdaQuery()
                    .select(Subject::getId, Subject::getName)
                    .in(Subject::getId, subjectIds))
            .stream().collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));  // ②
    return rows.stream()
            .map(gs -> new GradeSubjectResponse(gs.getId(), gs.getGradeId(), gs.getSubjectId(),
                    gs.getWeeklyHours(), subjectNames.get(gs.getSubjectId())))                    // ③
            .toList();
}
```

- **① `rows == null` 也判**（其他 Service 的同类方法只判 `isEmpty`）—— 防御性。
- **② 只查 `id` + `name`**，三次防御（`filter(nonNull)` / `distinct` / `isEmpty` 短路）齐备。
- **③ `subjectNames.get(...)` 缺键返回 null** → `"subject_name": null`，
  对应 Python `gs.subject.name if gs.subject else None`（`assignments.py:73`）。

```java
private void requireGradeOwned(long organizationId, Long gradeId) {
    if (gradeId == null || gradeMapper.selectCount(...eq id...eq org...) == 0)
        throw BusinessException.notFound("年级不存在");
}
private void requireSubjectOwned(long organizationId, Long subjectId) {
    if (subjectId == null || subjectMapper.selectCount(...eq id...eq org...) == 0)
        throw BusinessException.notFound("科目不存在");
}
```

- ⚠ **与 `TeacherService.requireSubjectOwned` 逐字相同**（两处重复）。
- ⚠ **两个方法都是 `private`**，所以 `SubjectService` 里的同名逻辑**没有被复用**
  （`SubjectService.ensureGradesOwnedBy` 是"批量版"、返回 400 而不是 404，**语义不同，不能合并**）。
  → 本篇共 **4 个 `requireGradeOwned`/`requireSubjectOwned` 变体**：
  | 位置 | 返回 | 粒度 |
  |---|---|---|
  | `ClassService.requireGradeOwned` | 404 `年级不存在` | 单个（用 `selectCount`） |
  | `TeacherService.requireSubjectOwned` | 404 `科目不存在` | 单个 |
  | `GradeSubjectService.requireGradeOwned` | 404 `年级不存在` | 单个 |
  | `GradeSubjectService.requireSubjectOwned` | 404 `科目不存在` | 单个 |
  | `SubjectService.ensureGradesOwnedBy` | **400** `包含不存在或不属于当前学校的年级` | **批量** |
  **这 5 个"看起来一样"的方法有 3 种不同行为** —— 这是"行为等价"项目的典型形态：
  **相似 ≠ 可合并**。

### 7.3 `GradeSubjectController`（75 行，4 个接口）

**一句话定位**：`/api/grade-subjects/*` 的 HTTP 入口。
**谁调它**：前端 `getGradeSubjects/createGradeSubject/updateGradeSubjectsBulk/deleteGradeSubject`
（`frontend/src/api/index.ts:119-124`）。

**⚠ 类注释（`GradeSubjectController.java:24-30`）把"四种不同形状"列全了**，这是本篇最值得记的一段：

> 形状上有意不一致（都对齐 Python）：
> - `GET` 是分页列表 → `{items,total}`（§9 的分页契约）
> - `POST` 返回单条对象
> - `POST /bulk` 返回**裸数组**（写操作返回"操作后的状态"，不分页）
> - `DELETE` 返回 `{"ok":true}`

**⚠ `/bulk` 的路径**：`@PostMapping("/bulk")` 挂在类级 `@RequestMapping("/api/grade-subjects")` 下 →
完整路径 `/api/grade-subjects/bulk`，与 Python `@router.post("/grade-subjects/bulk")` 一致（`assignments.py:260`）。

**⚠ `@Valid` 在 `/bulk` 上是必须的**：`GradeSubjectBulkRequest.items` 上的 `@Valid` 会递归到
`Item` 的 `@Min/@Max` —— 契约用例 `grade-subjects 批量课时 41（超上限）` / `-1（超下限）`
expect **422** 就是靠这条链。

```java
@PostMapping("/bulk")
public List<GradeSubjectResponse> bulk(@Valid @RequestBody GradeSubjectBulkRequest request,
                                       @AuthenticationPrincipal OrbitUserPrincipal principal) {
    return gradeSubjectService.bulkUpdate(requirePrincipal(principal).organizationId(), request);
}
```

---

## 8. `fixedslot` 包 —— 固定课时（3 个接口，8 个类）

**定位**：`grade_fixed_slots`（年级级固定课，有完整接口）+ `fixed_slots`（班级级固定课，**只有实体+Mapper**）。
**这是全项目校验最多的写接口。**

### 8.1 `GradeFixedSlotController`（63 行，3 个接口）

**一句话定位**：`/api/fixed-slots/by-grade/*` 的 HTTP 入口。
**谁调它**：前端 `getGradeFixedSlots/createGradeFixedSlot/deleteGradeFixedSlot`
（`frontend/src/api/index.ts:149-151`）。

**⚠ 类注释（`GradeFixedSlotController.java:23-24`）点出路径是契约**：

```java
@RestController
@RequestMapping("/api/fixed-slots/by-grade")     // ⚠ 注意 by-grade 这一段
public class GradeFixedSlotController {

    @DeleteMapping("/{slotId}")                  // → /api/fixed-slots/by-grade/{slotId}
```

> 注意路径里带 `by-grade` 这一段：它是 Python 路由前缀的一部分，
> **不能用 `@RequestMapping("/api/fixed-slots")` 图省事 —— 路径是契约。**
>
> ⚠ Python 侧是 `router = APIRouter(prefix="/api/fixed-slots")` +
> `@router.delete("/by-grade/{slot_id}")`（`fixed_slots.py:10`、`:103`），
> Java 把 `by-grade` 提进类级 `@RequestMapping`、类内只写 `/{slotId}` ——
> **拼出来的完整路径相同**。这是"前缀可以搬、但不能少"的例子。

三个方法与 grade 同构（§2.1）。

### 8.2 `GradeFixedSlotService`（219 行，4 个方法）

**一句话定位**：年级固定课的 list/create/delete。
**谁调它**：`GradeFixedSlotController`。

**⚠ 类注释（`GradeFixedSlotService.java:26-42`）是本篇最重要的一段文档**，完整引用：

> `create` 是全项目**校验最多**的接口：它要在写之前证明
> 「把整个年级的某个科目固定在同一时间」这件事**在教师层面是可行的**，否则排课阶段必然无解。
> 五道关卡（文案逐字对齐 Python）：
> 1. 年级、科目都属于本组织（404）
> 2. 所选节次在该年级当天课时范围内 → 400 `所选节次不在该年级的上课时间内`
> 3. 该年级已有班级 → 400 `该年级还没有班级`
> 4. 每个班都已**唯一确定**该科目的任教教师 → 400 `这些班级尚未唯一确定任教教师：A、B`
> 5. 这个年级的班不是同一位老师教 → 409 `同一教师负责该年级多个班，不能把这些班的课程固定在同一时间`
> 6. 与其他年级同一时段的固定课教师不冲突 → 409 `与其他年级的固定课程存在教师时间冲突`
>
> 已经存在同一 (年级, 星期, 节次) 的记录时是**改科目**而不是新建（upsert）。

> ⚠ **注释说"五道关卡"，但实际列了 6 条编号**（含下游 ① 的 2 个 404）与 **5 个抛错点**。
> 这是注释自身的小不一致（不影响行为）。见 §10 U5。

```java
/** 对应 Python 的 {@code DAY_KEYS}，下标就是 {@code day_of_week}。 */
private static final List<String> DAY_KEYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri");
```

- 逐字对应 `fixed_slots.py:11` 的 `DAY_KEYS = ["Mon", "Tue", "Wed", "Thu", "Fri"]`。
- ⚠ **只有 5 天**（周一到周五）—— 所以"星期六"是无效的（第 ② 关会拒绝）。

#### `list(long organizationId, Long gradeId, PageQuery query)` → `PageResponse<GradeFixedSlotResponse>`

与 `GradeSubjectService.list`（§7.2）**逐字同构**：只吃 `grade_id` 过滤、固定 `orderByAsc(id)`、
`gradeId != 0L` 的约定、`keyword`/`order_by` 传了不生效。不重复。

#### `create(long organizationId, GradeFixedSlotCreateRequest request)` → `GradeFixedSlotResponse`

**逐关卡拆解**：

```java
@Transactional
public GradeFixedSlotResponse create(long organizationId, GradeFixedSlotCreateRequest request) {
    Grade grade = requireOwned(gradeMapper, Grade::getId, Grade::getOrganizationId,
            request.gradeId(), organizationId, "年级不存在");                 // ① 关卡 1a（404）
    requireOwned(subjectMapper, Subject::getId, Subject::getOrganizationId,
            request.subjectId(), organizationId, "科目不存在");               // ① 关卡 1b（404）
```

**关卡 ①（两个 404）**：
- 用泛型 `requireOwned`（与 `AssignmentService` 里那个**逐字相同**的私有方法）。
- **⚠ `"科目不存在"` 在这里被丢弃了**（第二次调用没有接收返回值）——
  我们只需要它"要么返回、要么抛异常"。
- 对应 Python `fixed_slots.py:32-33`（两行 `owned_or_404`）。
- **契约用例**：`fixed-slots 新建非法年级` expect 404。

```java
    // ② 节次必须落在该年级当天课时范围内。
    //    ⚠ Python 写的是 `day_of_week in range(5) and period_index in range(periods_per_day.get(DAY_KEYS[day_of_week], 0))`
    //    —— 靠 `and` 的短路保证 DAY_KEYS 不会被越界访问。这里必须保持同样的顺序。
    int dayOfWeek = request.dayOfWeek();
    int periodIndex = request.periodIndex();
    boolean validSlot = false;
    if (dayOfWeek >= 0 && dayOfWeek < DAY_KEYS.size()) {                      // ②a 星期范围
        int periodsOfDay = grade.getPeriodsPerDay() == null ? 0
                : grade.getPeriodsPerDay().getOrDefault(DAY_KEYS.get(dayOfWeek), 0);   // ②b 当天课时
        validSlot = periodIndex >= 0 && periodIndex < periodsOfDay;           // ②c 节次范围
    }
    if (!validSlot) {
        throw BusinessException.badRequest("所选节次不在该年级的上课时间内");   // ⚠ 400 + 文案
    }
```

**关卡 ②（节次/星期范围）—— 这是本方法最需要逐字讲清的一段**：

| Python（`fixed_slots.py:34-37`） | Java | 说明 |
|---|---|---|
| `data.day_of_week in range(5)` | `dayOfWeek >= 0 && dayOfWeek < DAY_KEYS.size()` | `range(5)` = `0,1,2,3,4`；Java 用 `DAY_KEYS.size()`（=5），语义相同 |
| `and`（短路） | `if (...) { ... }` 包裹 | ⚠ **必须短路**：否则 `DAY_KEYS.get(dayOfWeek)` 对 `dayOfWeek=7` 会抛 `IndexOutOfBoundsException` → **500 而不是 400** |
| `grade.periods_per_day.get(DAY_KEYS[...], 0)` | `getOrDefault(DAY_KEYS.get(dayOfWeek), 0)` | 该天没配课时 → 0 → 任何 `period_index` 都不合法 |
| `data.period_index in range(periods_per_day.get(...))` | `periodIndex >= 0 && periodIndex < periodsOfDay` | ⚠ `range(n)` 是 `0..n-1`；Java 用 `< periodsOfDay` |
| `periods_per_day` 为 null | `grade.getPeriodsPerDay() == null ? 0 : ...` | ⚠ **Python 会 `AttributeError`（None.get）→ 500**；**Java 返回 0 → 400**。**差异！见 §9/§10** |

- **⚠ 关键实现细节：为什么用 `if` 包裹而不是 `&&` 连写**。
  Java 的 `&&` **也是短路的**，所以理论上可以写成
  `dayOfWeek >= 0 && dayOfWeek < 5 && periodIndex >= 0 && periodIndex < grade.getPeriodsPerDay().get(...)`——
  但那样**每写一个条件都要重复一次 `DAY_KEYS.get(dayOfWeek)`**，很容易漏。
  用 `if` 包裹 + 一个 `validSlot` 变量更直白，且**默认 false** 保证了"任何没覆盖到的情况都算无效"。
- **本地变量 `dayOfWeek` / `periodIndex`**：`request.dayOfWeek()` 是 `Integer`，
  `int dayOfWeek = request.dayOfWeek()` **自动拆箱**。
  ⚠ 若为 null → NPE。**`@NotNull` 已经挡成 422**（契约用例 `fixed-slots 新建缺字段` expect 422）。
  拆箱一次之后，后面所有比较都是原生类型（更快、也不可能 NPE）。
- **契约用例**：
  - `fixed-slots 节次越界` expect **400**
  - `fixed-slots 星期越界` expect **400** ← 专门测 ②a（而不是让它 500）

```java
    // ③ 该年级要有班级
    List<Clazz> gradeClasses = classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
            .eq(Clazz::getOrganizationId, organizationId)
            .eq(Clazz::getGradeId, grade.getId()));
    if (gradeClasses.isEmpty()) {
        throw BusinessException.badRequest("该年级还没有班级");                 // ⚠ 400 + 文案
    }
```

**关卡 ③（年级要有班级）**：
- **"把整个年级的某科目固定在同一时间"这件事的前提是"该年级有班"** ——
  一个班都没有时，规则没有意义（而且教师校验也无从谈起）。
- ⚠ **读的是 `grade.getId()` 而不是 `request.gradeId()`** —— 两者恒等（`grade` 就是按它查出来的），
  但用查出来的实体更安全（如果将来 `requireOwned` 做了什么归一化）。

```java
    // ④ 每个班都要**唯一**确定该科目的任教教师
    List<Long> classIds = gradeClasses.stream().map(Clazz::getId).toList();
    List<TeacherAssignment> assignments = assignmentMapper.selectList(
            Wrappers.<TeacherAssignment>lambdaQuery()
                    .eq(TeacherAssignment::getOrganizationId, organizationId)
                    .in(TeacherAssignment::getClassId, classIds)
                    .eq(TeacherAssignment::getSubjectId, request.subjectId()));    // ① 一条 SQL 拿全部
    Map<Long, List<TeacherAssignment>> byClass = new HashMap<>();
    for (TeacherAssignment assignment : assignments) {
        byClass.computeIfAbsent(assignment.getClassId(), k -> new ArrayList<>()).add(assignment);  // ② 按班分组
    }
    List<String> incomplete = gradeClasses.stream()
            .filter(c -> byClass.getOrDefault(c.getId(), List.of()).size() != 1)  // ③ ⚠ != 1（不是 == 0）
            .map(Clazz::getName).toList();
    if (!incomplete.isEmpty()) {
        throw BusinessException.badRequest(
                "这些班级尚未唯一确定任教教师：" + String.join("、", incomplete));    // ⚠ 400 + 文案
    }
```

**关卡 ④（每班唯一确定任教教师）**：
- **① 一条 SQL 拿全部**（不是每班查一次）：`IN (classIds)` + `subject_id = ?`。
  对应 Python `fixed_slots.py:47-51`（也是一条 `.in_(class_ids)`）。
- **⚠ ③ 判断的是 `size() != 1`，不是 `size() == 0`**！
  这就是文案里"**唯一**确定"的含义：
  - `size() == 0` → 还没分配教师 → 不合法；
  - `size() == 2` → **有两位教师**（表允许，见 §6.3）→ **也不合法**；
  - `size() == 1` → 合法。
  对应 Python `len(by_class.get(item.id, [])) != 1`（`fixed_slots.py:55`）—— **逐字对应**。
- **⚠ `classIds` 非空**（关卡 ③ 已保证 `gradeClasses` 非空）→ 不会发 `IN ()`。
  **这是一个"前置关卡保证了后置查询合法"的例子**（对比 §3.2 需要显式短路的地方）。
- **⑤ 文案**：`这些班级尚未唯一确定任教教师：` + `、`.join(班名)。
  全角冒号 + 全角顿号，与 Python `f"这些班级尚未唯一确定任教教师：{'、'.join(incomplete)}"`
  （`fixed_slots.py:57`）逐字相同。
  ⚠ **`incomplete` 的顺序 = `gradeClasses` 的顺序**（查询顺序，无 ORDER BY）—— 同 §6.2 的说明。

```java
    // ⑤ 该年级各班不能是同一位教师（否则同一时间他要同时上多个班）
    List<Long> teacherIds = gradeClasses.stream()
            .map(c -> byClass.get(c.getId()).get(0).getTeacherId())              // ① ⚠ 直接 get(0)
            .toList();
    if (new HashSet<>(teacherIds).size() != teacherIds.size()) {                  // ② 有重复
        throw BusinessException.conflict("同一教师负责该年级多个班，不能把这些班的课程固定在同一时间");
    }
```

**关卡 ⑤（教师不能重复）**：
- **⚠ ① `byClass.get(c.getId()).get(0)` 为什么不会 NPE / 越界**：
  上一关已经保证 `size() == 1` 对**每一个** `gradeClasses` 里的班成立。
  **两个关卡的顺序不可交换** —— 如果先做 ⑤ 再做 ④，`get(0)` 会在"某班没有分配"时抛
  `IndexOutOfBoundsException` → 500。**这是"校验顺序本身就是逻辑"的例子。**
- **② `HashSet(teacherIds).size() != teacherIds.size()`** = "有重复的教师 id"。
  对应 Python `len(set(teacher_ids)) != len(teacher_ids)`（`fixed_slots.py:59`）—— 逐字对应。
- **⚠ 语义**：如果这个年级有 3 个班，都由同一位数学老师教 —— 那么"把数学固定在周三第 2 节"
  意味着**这位老师要同时出现在 3 个班** → 物理不可能 → **409 Conflict**（不是 400！）。
  ⚠ **这是本篇唯一一个 409**，用 `BusinessException.conflict(...)`（对应 Python
  `HTTPException(status_code=409, ...)`，`fixed_slots.py:60`）。
- **为什么是 409 而不是 400**：409 = "请求与当前资源状态冲突"（教师的分配关系导致的），
  而 400 = "请求本身不合法"。**这是 Python 的既有选择，Java 照抄。**

```java
    // ⑥ 与其他年级同一时段的固定课比教师
    GradeFixedSlot current = gradeFixedSlotMapper.selectOne(
            Wrappers.<GradeFixedSlot>lambdaQuery()
                    .eq(GradeFixedSlot::getOrganizationId, organizationId)
                    .eq(GradeFixedSlot::getGradeId, grade.getId())
                    .eq(GradeFixedSlot::getDayOfWeek, dayOfWeek)
                    .eq(GradeFixedSlot::getPeriodIndex, periodIndex));            // ① 查"本格已有什么"
    Long excludeId = current == null ? -1L : current.getId();                     // ② ⚠ 哨兵 -1
    List<GradeFixedSlot> otherRules = gradeFixedSlotMapper.selectList(
            Wrappers.<GradeFixedSlot>lambdaQuery()
                    .eq(GradeFixedSlot::getOrganizationId, organizationId)
                    .eq(GradeFixedSlot::getDayOfWeek, dayOfWeek)
                    .eq(GradeFixedSlot::getPeriodIndex, periodIndex)
                    .ne(GradeFixedSlot::getId, excludeId));                       // ③ 排除自己
    Set<Long> myTeacherIds = new HashSet<>(teacherIds);
    for (GradeFixedSlot rule : otherRules) {
        List<Long> otherClassIds = classMapper.selectList(Wrappers.<Clazz>lambdaQuery()
                        .select(Clazz::getId)
                        .eq(Clazz::getOrganizationId, organizationId)
                        .eq(Clazz::getGradeId, rule.getGradeId()))
                .stream().map(Clazz::getId).toList();                              // ④ 对方年级的班
        if (otherClassIds.isEmpty()) continue;                                     // ⑤ 短路（防 IN ()）
        Set<Long> otherTeacherIds = assignmentMapper.selectList(
                        Wrappers.<TeacherAssignment>lambdaQuery()
                                .select(TeacherAssignment::getTeacherId)
                                .eq(TeacherAssignment::getOrganizationId, organizationId)
                                .in(TeacherAssignment::getClassId, otherClassIds)
                                .eq(TeacherAssignment::getSubjectId, rule.getSubjectId()))
                .stream().map(TeacherAssignment::getTeacherId)
                .collect(java.util.stream.Collectors.toSet());                     // ⑥ 对方时段的教师
        if (!java.util.Collections.disjoint(myTeacherIds, otherTeacherIds)) {       // ⑦ ⚠ 有交集
            throw BusinessException.conflict("与其他年级的固定课程存在教师时间冲突");
        }
    }
```

**关卡 ⑥（跨年级教师时间冲突）**：
- **① 先查"这个格子（本年级 × 这个星期 × 这个节次）已经有什么"** ——
  这既是 upsert 的判据，也是 ③ 里要排除的"自己"。
- **② 哨兵 `-1L`**：逐字对齐 Python `GradeFixedSlot.id != (current.id if current else -1)`
  （`fixed_slots.py:72`）。
  ⚠ **为什么用 -1 而不是"分两种查询"**：`-1` 不可能等于任何真实自增主键（从 1 开始），
  所以"排除一个不存在的东西" = "不排除任何东西"。**一把代码走两条路径**。
  ⚠ **`-1L` 必须是 `Long`**（不能写 `-1`）—— `ne(SFunction, Object)` 如果收到 `Integer -1`
  会生成的 SQL 参数类型与列类型不完全匹配（MySQL 会隐式转换，实际能跑，但不干净）。
- **③ `ne`（not equal）**：`AND id <> -1` 或 `AND id <> {current.id}`。
- **④ 对每一条其他规则，查对方年级的班**。⚠ **这里是 N+1**：
  `otherRules` 有 N 条就查 N 次班级 + N 次任教关系。
  Python 也是这个形状（`fixed_slots.py:74-83` 在循环里查询）—— **两边都是 N+1，行为等价**。
- **⑤ `if (otherClassIds.isEmpty()) continue;`**：⚠ 两条作用：
  ① 防 `IN ()` 非法 SQL；② 语义上"对方年级没有班 → 不可能有教师冲突 → 跳过"。
  Python **没有这个检查**（`fixed_slots.py:75-78` 直接 `.in_([])`）——
  ⚠ **SQLAlchemy 对空 `in_([])` 生成 `1 != 1`（永远假）**，所以 Python 的结果是"空集 →
  `other_teacher_ids` 为空 → 无交集 → 不报错"。**Java 用 `continue` 达到同样效果** ✓ 等价。
- **⑥ 用 `.select(TeacherAssignment::getTeacherId)` 只查教师 id**（不需要整行）。
- **⑦ `Collections.disjoint(a, b)`**：判断两个集合**是否有交集** ——
  `!disjoint` = "有交集" = 冲突。对应 Python `if set(teacher_ids) & other_teacher_ids:`
  （`fixed_slots.py:84`）—— 逐字对应（`&` 是集合交集）。
- **⚠ `java.util.stream.Collectors` / `java.util.Collections` 写成全限定名**（没 import）——
  风格问题（见 §10 U2）。
- **⚠ 文案 `与其他年级的固定课程存在教师时间冲突`** 也是 **409**（Python `:85`）。

```java
    if (current != null) {
        current.setSubjectId(request.subjectId());
        gradeFixedSlotMapper.updateById(current);          // ⑦ upsert：已存在 → 只改科目
        return GradeFixedSlotResponse.from(current);
    }
    GradeFixedSlot created = new GradeFixedSlot();
    created.setOrganizationId(organizationId);
    created.setGradeId(grade.getId());
    created.setSubjectId(request.subjectId());
    created.setDayOfWeek(dayOfWeek);
    created.setPeriodIndex(periodIndex);
    gradeFixedSlotMapper.insert(created);                  // ⑧ 新建
    return GradeFixedSlotResponse.from(created);
```

**收尾（upsert）**：
- **⚠ upsert 的键是 `(grade, day, period)`**（对齐表上的
  `uq_grade_fixed_slot_org (organization_id, grade_id, day_of_week, period_index)`，
  `V1__baseline_legacy.sql:182`）—— **subject 不在键里**，所以"同一格再提交一个不同科目"
  = **改科目**（不是新增第二条）。类注释 `:42` 写明了这点。
- **⚠ 注意 ① 的查询用的是 `current`（早先查出来的），而 `current` 是在 ⑥ 之前查的** ——
  中间**没有其他写操作**（同一事务），所以 `current` 仍然有效。
- **⚠ 更新时只 `setSubjectId`，不动 `dayOfWeek`/`periodIndex`** —— 因为那两个就是查询条件（值相同）。
- **⚠ Python 是 `db.commit(); db.refresh(rule)`（`fixed_slots.py:98-99`）**，
  Java 靠事务提交 + 内存对象（见 §2.2 的说明）。
- ⚠ **`GradeFixedSlotResponse.from(...)` 两个分支都用** → 响应形状不可能不一致。

#### `delete(long organizationId, long slotId)` → `void`

```java
GradeFixedSlot slot = gradeFixedSlotMapper.selectOne(Wrappers.<GradeFixedSlot>lambdaQuery()
        .eq(GradeFixedSlot::getId, slotId)
        .eq(GradeFixedSlot::getOrganizationId, organizationId));
if (slot == null) throw BusinessException.notFound("年级固定课程不存在");   // ⚠ 文案
gradeFixedSlotMapper.deleteById(slot.getId());
```

- **⚠ 文案 `年级固定课程不存在`**（7 个字）—— 对齐 Python `fixed_slots.py:114`。
  对比 §7.2 的 `不存在`（3 个字，`grade-subjects`）—— **同样是删固定课类记录，文案长度不同。**
- 契约用例：`fixed-slots 删除不存在` expect 404。

#### `private static <T> T requireOwned(...)`

**与 `AssignmentService` 里的那个逐字相同**（`GradeFixedSlotService.java:204-218`）——
两处重复（见 §10 U4）。参数类型用全限定名（没 import）。

### 8.3 `GradeFixedSlot` / `FixedSlot` 两个实体

```java
// ① 年级级（有完整接口）
@TableName("grade_fixed_slots")
public class GradeFixedSlot {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId; private Long gradeId; private Long subjectId;
    private Integer dayOfWeek;   private Integer periodIndex;
}

// ② 班级级（**只有实体 + Mapper，没有任何接口**）
@TableName("fixed_slots")
public class FixedSlot {
    @TableId(type = IdType.AUTO) private Long id;
    private Long organizationId; private Long classId; private Long subjectId; private Long teacherId;
    private Integer dayOfWeek;   private Integer periodIndex;
}
```

**⚠ `GradeFixedSlot` 的类注释（`GradeFixedSlot.java:10-11`）讲清了两个表的区别**：

> 与 `FixedSlot` 的区别：`fixed_slots` 是**班级**级（指定到教师），
> 这张是**年级**级（该年级所有班同时上同一科目，**教师由分配关系推出来**）。

→ **这就是为什么 `GradeFixedSlot` 表里没有 `teacher_id`**：
教师在**运行时**由 `teacher_assignments` 推出来（这正是关卡 ④⑤⑥ 在做的事）。
而 `FixedSlot` 有 `teacher_id`（写死）。

**唯一约束对比**：

| 表 | 唯一约束 | 含义 |
|---|---|---|
| `grade_fixed_slots` | `(org, grade_id, day_of_week, period_index)` | 一个年级的一个时段只能有一节固定课 |
| `fixed_slots` | `(org, class_id, day_of_week, period_index)` | 一个班的一个时段只能有一节固定课 |

- **`FixedSlot` 的类注释（`FixedSlot.java:13-14`）**：
  「这是排课的三类"固定"输入之一（另外两类是 `grade_fixed_slots` 与教师不可用时段），
  对应 solver-core 里 `RuleContext` 收敛掉的那些 `fixed` 处理。」

### 8.4 `GradeFixedSlotMapper` / `FixedSlotMapper`（各 9 行）

两个都是空的 `BaseMapper<T>` 扩展，与 `GradeMapper` 逐字相同。

**⚠ `FixedSlotMapper` 在本篇只有 1 个使用点**：`ClassService.deleteOwnedChildren`
（删班级时连带删固定课）。**没有任何查询**。所以：
- 它**没有 Service**、没有 Controller、没有 DTO；
- `FixedSlot` 实体上**没有任何 `@TableField` 注解**（无 JSON 列、无枚举）。

### 8.5 fixedslot 包的 DTO

见 §1.4.1 / §1.4.4。`GradeFixedSlotResponse` 有 `from(GradeFixedSlot)` 静态工厂
（`GradeFixedSlotResponse.java:7-10`），**没有 `of(...)` 重载**（因为不需要联名）。

---

## 9. 行为对齐清单（24 个接口 × Python）

### 9.1 错误文案全表（**这是契约，改一个字都是破坏**）

| 状态码 | 文案 | 出现位置（Java） | 对齐 Python |
|---|---|---|---|
| 401 | `请先登录` | 7 个 Controller 的 `requirePrincipal` | `get_current_user` 的 401 |
| 404 | `年级不存在` | `GradeService.requireOwned:112`、`ClassService.requireGradeOwned:171`、`GradeSubjectService.requireGradeOwned:179`、`GradeFixedSlotService.create:86` | `grades.py:28`/`:41`、`classes.py:24`/`:40`、`assignments.py:79`、`fixed_slots.py:32` |
| 404 | `班级不存在` | `ClassService.requireOwned:163`、`AssignmentService.create:79` | `classes.py:36`/`:51`、`assignments.py:47` |
| 404 | **`老师不存在`** | `TeacherService.requireOwned:121`、`AssignmentService.create:77` | `teachers.py:38`/`:58`、`assignments.py:46` |
| 404 | **`教师不存在`** | `AssignmentService.setTeacherClasses:275` | `assignments.py:205` ⚠ **与上面不同！** |
| 404 | `科目不存在` | `SubjectService.delete:140`、`TeacherService.requireSubjectOwned:130`、`GradeSubjectService.requireSubjectOwned:187`、`GradeFixedSlotService.create:88`、`AssignmentService.create:81` | `subjects.py:51`、`teachers.py:24`/`:42`、`assignments.py:80`/`:266`、`fixed_slots.py:33` |
| 404 | `分配不存在` | `AssignmentService.delete:103` | `assignments.py:39` |
| 404 | **`不存在`**（3 字） | `GradeSubjectService.delete:143` | `assignments.py:304` ⚠ **极短，易被"改清楚"** |
| 404 | `年级固定课程不存在` | `GradeFixedSlotService.delete:199` | `fixed_slots.py:114` |
| 400 | `不支持的排序字段: X（可选: ...）` | `PageQuery.orderColumn:121`（Java 独有） | — （Python 不认这个参数） |
| 400 | `包含不存在或不属于当前学校的年级` | `SubjectService.ensureGradesOwnedBy:179`、`GradeSubjectService.bulkUpdate:106` | `subjects.py:31`、`assignments.py:275` |
| 400 | `包含不存在或不属于当前学校的科目` | `AssignmentService.autoAssign:141` | `assignments.py:118` |
| 400 | `包含不存在或不属于当前学校的班级` | `AssignmentService.setTeacherClasses:284` | `assignments.py:212` |
| 400 | `老师与科目不匹配` | `AssignmentService.create:83` | `assignments.py:50` |
| 400 | `这些班级所属年级没有开设该科目：A、B` | `AssignmentService.setTeacherClasses:301-302` | `assignments.py:227` |
| 400 | `所选班级合计 N 节/周，超过教师上限 M 节/周` | `AssignmentService.setTeacherClasses:308-309` | `assignments.py:233` |
| 400 | `所选节次不在该年级的上课时间内` | `GradeFixedSlotService.create:102` | `fixed_slots.py:39` |
| 400 | `该年级还没有班级` | `GradeFixedSlotService.create:110` | `fixed_slots.py:45` |
| 400 | `这些班级尚未唯一确定任教教师：A、B` | `GradeFixedSlotService.create:129-130` | `fixed_slots.py:57` |
| **409** | `同一教师负责该年级多个班，不能把这些课程固定在同一时间` | `GradeFixedSlotService.create:138` | `fixed_slots.py:60` |
| **409** | `与其他年级的固定课程存在教师时间冲突` | `GradeFixedSlotService.create:174` | `fixed_slots.py:85` |

**⚠ 三条最容易被"顺手改好"的文案**（改了就破坏契约）：
1. **`老师不存在` vs `教师不存在`** —— 同一个概念两套文案，各自对齐各自的 Python 行。
2. **`不存在`**（`grade-subjects` 删除）—— 只有 3 个字，看起来像漏写了。
3. **`老师与科目不匹配`** —— "老师"不是"教师"，与相邻的 `教师不存在` 不一致。

**⚠ 不可见的字符级契约**（肉眼几乎看不出）：
- `autoAssign` 的 `unassigned` 用 **全角连字符 `－`（U+FF0D）**：`班级名－科目名（需要 N 节/周）`
- 列表分隔符是 **全角顿号 `、`**（`String.join("、", ...)`）
- 冒号是 **全角 `：`**（`这些班级尚未唯一确定任教教师：`）
- 半角空格的位置：`所选班级合计 {n} 节/周，超过教师上限 {m} 节/周`

### 9.2 状态码选择的对齐

| 场景 | Python | Java | 一致 |
|---|---|---|---|
| 缺必填字段 | 422（Pydantic） | 422（`@Valid` → `handleBodyValidation`） | ✓ |
| `weekly_hours` 超 0..40 | 422（Pydantic `Field(ge=0,le=40)`） | 422（`@Min/@Max`） | ✓ |
| 引用不存在的实体（写入时） | 404（`owned_or_404`） | 404（`BusinessException.notFound`） | ✓ |
| 引用不存在的年级（subject 新建） | 400（显式 `grade_ids != owned_grade_ids`） | 400 | ✓ |
| 固定课节次越界 | 400 | 400 | ✓ |
| 固定课教师冲突 | **409** | **409** | ✓ |
| 排序字段不在白名单 | —（Python 无此参数） | 400 | Java 独有 |
| 唯一约束冲突 | 500（`IntegrityError`） | 500（`DuplicateKeyException` → 兜底） | ✓ |
| 外键约束冲突（删被引用的科目/教师） | 500 | 500 | ✓ |
| `POST` 成功 | **200** | **200**（Spring 默认） | ✓ |

### 9.3 分页与排序的对齐

| 接口 | Python | Java | 契约处理方式 |
|---|---|---|---|
| `GET /api/grades`、`/classes`、`/subjects`、`/teachers`、`/grade-subjects`、`/fixed-slots/by-grade` | **裸数组、完全忽略 page/size** | `{items,total}`，真分页（`PaginationInnerInterceptor`） | `list_endpoint`：断言 `items` 排序后 == Python 全量 且 `total` == Python 长度 |
| `GET /api/assignments` | 裸数组 | `{items,total}`，**内存分页** | `first_page`：断言 `total` == Python 全量长度、`items` == 排序后前 N 条 |
| 关键字 / `order_by` / `order` | **不支持** | 支持（白名单 + 400 校验） | `superset`：断言 Java 结果是 Python 全量的**子集** |
| 默认排序 | 无 `ORDER BY`（主键序） | 显式 `id ASC` | 把偶然行为固定成契约 |
| `grade_id=0` / `teacher_id=0` / `class_id=0` | 当成"不过滤" | 同样（`!= 0L`） | 逐字对齐 |

### 9.4 分页相关的三个"必读"点

1. **`PaginationInnerInterceptor` 不注册 = 静默不分页**（`MybatisPlusConfig.java:12-30`）。
   P1 的验证里有一条是**实测** `?size=1` 只返回 1 条、而 `total` 仍是总数。
2. **`PageQuery.DEFAULT_SIZE = 20` / `MAX_SIZE = 200`** —— 前端
   `frontend/src/api/index.ts:60-74` 用 `size=200` 一次拉全量（**迁移期方案**），
   超过 200 条会**打告警**而不是静默截断。P2 前端改造期要换成真服务端分页。
3. **`assignment` 是内存分页** —— 唯一的例外（§6.1）。

---

## 10. 已知偏离与未解问题

### 10.1 与 Python 的**真实行为差异**（4 处）

> 这四处都是**读源码逐行对出来的**，其中前两处**契约用例没有覆盖**，所以影子对比工具抓不到。

| # | 位置 | Python 行为 | Java 行为 | 严重度 |
|---|---|---|---|---|
| **D1** | `SubjectService.create:90` | `name` **原样存入**（`"  语文  "` 保留空格） | `request.name().strip()` → `"语文"` | 中（可观察、未被测试覆盖） |
| **D2** | `SubjectService.create:106` | `weekly_hours_by_grade` 里值为 `null` → `None > 0` → **TypeError → 500** | `hours != null` → **静默跳过 → 200** | 中（Java 更宽容） |
| **D3** | `GradeSubjectService.allForSubject:149-154` | `bulk` 后返回**该租户的全部**年级课时（`list_grade_subjects` 不带 grade 过滤） | 返回**该科目的**全部年级课时（多加了 `.eq(subjectId)`） | **高**（响应内容不同，若库里有多科目的数据，条数就不同） |
| **D4** | `GradeFixedSlotService.create:97-98` | `grade.periods_per_day` 为 `NULL` → `None.get(...)` → **AttributeError → 500** | `== null ? 0` → 逻辑上任何节次都非法 → **400** | 低（表中该列是 `NOT NULL`，取不到 NULL） |

> ⚠ **D3 值得优先处理**：`POST /api/grade-subjects/bulk` 的响应体在"库里存在多个科目的年级课时"时，
> Java 会比 Python **少返回**其他科目的记录。当前库里如果只有一个科目配了课时，两边看起来一样 ——
> **这正是契约工具抓不到的原因**（用例之间互相污染得太少）。
> 建议加一条契约用例：先建两个科目各自的年级课时，再对其中一个跑一次 bulk，比对响应条数。

### 10.2 代码整洁问题（**不影响行为，但影响可维护性**）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| **U1** | 注释说错了归因：`没传任何参数时 page=0,size=0 由原始类型默认值给出` —— 实际是传 `null`（字段是 `Integer`），由紧凑构造器归一化 | `SubjectController.java:49` | 改注释（结论 1/20 是对的，归因错了） |
| **U2** | **全限定名代替 import**：`com.orbit.common.dto.PageQuery` / `PageResponse` / `BaseMapper` / `SFunction` / `java.util.stream.Collectors` / `java.util.Collections` | `AssignmentController.java:39-42`、`AssignmentService.java:340/377-379`、`GradeFixedSlotService.java:172-173/204-206` | 加 import |
| **U3** | 注释引用了 `H1` / `H2` 两个"坑编号"，但**本篇与 `代码导读.md` 都没有这份清单**（第一篇用的是 `B2`/`B5` 一类编号） | `ClassCreateRequest.java:9`、`ClassController.java:25` | 补链接或改成自解释的描述 |
| **U4** | **重复代码**：① `requirePrincipal` 在 7 个 Controller 里各一份；② 泛型 `requireOwned` 在 `AssignmentService` 与 `GradeFixedSlotService` 各一份；③ `requireSubjectOwned` 在 `TeacherService` 与 `GradeSubjectService` 各一份；④ 批量取名在 `ClassService` / `TeacherService` / `AssignmentService` 三处；⑤ `key(gradeOrClassId, subjectId)` 用字符串拼复合键 | 见各节 | **注意**：`SubjectController.java:74-76` 的注释**已经提议**把 `requirePrincipal` 抽到 `OrbitUserPrincipal` 的静态方法里，但**没有做**。②③ 的重复**不能盲目合并**（文案不同，见 §9.1） |
| **U5** | 类注释说"五道关卡"但列了 6 条编号（5 个抛错点） | `GradeFixedSlotService.java:31-40` | 统一口径 |
| **U6** | `SubjectService.delete:143` 的子表删除条件不带 `organization_id`（同类的 `deleteGradeSubjectsByGrade` 带了）；`delete` 用 `deleteById(subjectId)` 而 `GradeService.delete` 用 `deleteById(grade.getId())` | `SubjectService.java:142-144` | 统一风格（带租户边界更安全） |
| **U7** | `GradeSubjectService.list` / `GradeFixedSlotService.list` / `AssignmentService.list` **不接受排序参数**，但因为**没有调用 `orderColumn(...)`**，传 `order_by=任意值` **不会 400**（静默忽略）。其他 4 个 list 会 400 | 见 §7.2 / §8.2 / §6.2 | **这是刻意的**（Python 也不支持）—— 但"同一套 `PageQuery` 在不同接口上校验强度不同"值得在注释里说明 |

### 10.3 性能与规模化风险

| # | 风险 | 位置 | 触发条件 |
|---|---|---|---|
| **P1** | `autoAssign` 是 O(jobs × teachers) + 每 job 一条 INSERT，**全程持有租户写锁** | `AssignmentService.autoAssign:210-241` | 160 班规模：jobs≈1600、teachers 数百 → 数十万次比较 + 1600 条 INSERT，**同一学校内的其他写操作全部排队** |
| **P2** | `setTeacherClasses` 逐条 INSERT（不是批量） | `AssignmentService.setTeacherClasses:320-327` | 一次设置几百个班时 |
| **P3** | `assignments` 的列表是**内存分页**（全量查回来再切） | `AssignmentController.list:45-51` | 分配数上千时，每次列表请求都会拉全量 |
| **P4** | `GradeFixedSlotService.create` 关卡 ⑥ 是 **N+1**（每条其他规则查 2 次） | `GradeFixedSlotService.java:156-176` | 同时段固定课规则数 × 2 次查询。**Python 同样 N+1，行为等价** |
| **P5** | 批量取名是"主查询 + 1 次 IN"，**但 `selectList` 默认查全列**（只有三处显式 `.select(...)` 收窄了列） | 各 Service | 大表 + 大 JSON 列时（`grades.periods_per_day`） |

### 10.4 未解问题（**需要有人去确认的**）

- **Q1**：D3（`allForSubject` 多加了 subject 过滤）是**刻意的修正**还是**迁移时的手误**？
  代码里**没有注释说明**这点，而 `GradeSubjectService` 的其他地方注释都很详尽 ——
  这更像是手误。**建议查 commit history 或问原作者。**
- **Q2**：`AssignmentController.list` 的内存分页有没有**被明确批准**？
  注释解释了"不值得为它单独写分页 SQL"，但没提这是否记入了 TODO。
  ⚠ 注意契约用例**专门为它写了一条 `first_page`**（`compare.py:667-668`）——
  说明它是**被验证过、被接受**的行为。
- **Q3**：`H1` / `H2` 这两个坑编号指向哪份清单？（见 U3）
- **Q4**：`teacher_assignments` 的 `uq_assignment_org` 是**四元组**唯一，
  所以"一个班一个科目两位教师"是**表层面允许**的。
  `setTeacherClasses` 会主动"抢"，`GradeFixedSlotService` 关卡 ④ 会把 `size() == 2` 判为不合法 ——
  但**没有地方阻止 `POST /api/assignments` 直接造出这种状态**（Python 也没有）。
  这是**刻意的宽松**还是待修的漏洞？（当前行为等价，但它是"固定课校验"存在的原因。）

---

## 11. 本篇小结

`orbit-academic` 的教务主数据这 50 个类，回答的是同一个问题的不同侧面：
**「一所学校的主数据，要怎么被读写才算和 Python 版是同一个系统？」**

| 层 | 守住的一致性 |
|---|---|
| **7 个 Controller** | 路径（含 `by-grade` 这种段）、HTTP 方法、**默认 200 不是 201**、`requirePrincipal` → 401 `请先登录`、写操作返回 `{"ok":true}` |
| **8 个 Service** | 租户隔离**显式写在每个查询里**、PATCH 语义（先查再改）、P1 的五条对齐规则（`!= 0L`、`containsAll` 的前提、`sorted()` 的稳定序）、**每一句中文错误文案** |
| **11 个 Mapper** | 只有 1 个带自定义 SQL（`lockById` 的 `FOR UPDATE`）；其余全是 MyBatis-Plus 生成 → **读 Wrapper 就是读 WHERE** |
| **9 个 Entity** | `@TableName` 显式指定（不靠命名推导）、**`autoResultMap = true` 才能让 JSON typeHandler 在查询路径生效** |
| **25 个 DTO** | `Integer`/`Long` 而非原始类型（缺字段传 null）、**默认值只在 Service 里落实一次**、`@Min/@Max` 落在 422 路径上 |

**三个最值得记住的东西**：

1. **`requireOwned` 里的两个 `eq` 缺一不可** —— 这是全模块的安全底线，
   而且它**不靠框架、靠人写**（`SubjectService.java:25-31` 说明了为什么不用租户插件）。
2. **`autoAssign` 的排序键与选人键**（§6.2 第 (6)(7) 步）——
   它的输出是可观察行为，**任何一处改动都会与 Python 分叉**；
   `reversed()` 的位置、`(double)` 强转、`.thenComparing(Teacher::getId)` 的兜底，一个都不能少。
3. **`GradeFixedSlotService.create` 的五道关卡顺序不可交换** ——
   关卡 ④ 保证 `size() == 1`，关卡 ⑤ 才敢 `get(0)`。
   **校验顺序本身就是逻辑。**

**最后一句话**：读这一层时最该有的警觉是 ——
**"这段代码看起来能写得更好"和"这段代码可以改"是两件事**。
本篇里至少 9 处（§9.1 的三条文案 + §9.1 的四个字符级契约 + §9.4 的分页形状）
是"故意没写好"的，改之前请先读注释、再去 `tools/contract-diff/compare.py` 里找对应用例。
