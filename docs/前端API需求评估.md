# 前端 API 需求评估

> 目的：**在 P1 冻结契约之前**，把前端真正需要的接口盘清楚。
>
> 方法：**从组件反推**，不是从后端能力往上列。
> 理由见文末「为什么不能从后端往上列」——方案 §13 那 11 个新增接口就是这么漏掉的。
>
> 依据：`frontend/src`（13 个文件 / 1,808 行）实测 + 方案 §7.4 的目标文件结构 + 后端 50 个端点实测。

---

## 一、现状盘点（实测）

### 1.1 前端规模

| 项 | 实测 |
|---|---|
| 文件 | 13 个（6 page + 1 component + api/types + 入口/css） |
| 行数 | 1,808 |
| 路由 | 5 个：`/data`、`/schedule/config`、`/schedule/generate`、`/schedule/view`、`/system` |
| 调用的 API 函数 | **39 个**（`api/index.ts` 里导出 40 个，覆盖后端约 40 个端点） |
| 全局状态 | 无（全部组件内 `useState`）；`zustand` 在依赖里但**一次没用** |

### 1.2 一个容易看漏的事实：**现在的"分页"是假的**

`DataManagement.tsx` 用了 antd 的 `<Table>`，看起来有分页。
但 antd Table 的分页是**前端分页**——它拿到的是**全量数组**，在浏览器里切片。

```ts
const [classes, setClasses] = useState<Class[]>([]);
api.getClasses().then(setClasses);      // ← 一次拉全部
<Table dataSource={classes} ... />      // ← antd 在内存里分页
```

现在 4 个班、7 个教师，完全没问题。**方案的目标是 160 班规模的多租户 SaaS**，
到时候一个 org 的课表条目可能是 **160 班 × 30 节 ≈ 4,800 行**，
`getGradeSchedule()` 或 `getClassSchedule()` 一次拉全量会很难看。

### 1.3 已有 vs 缺失（后端侧实测）

| 能力 | 现状 |
|---|---|
| 按父级 ID 过滤 | ✅ **有**。`list_classes(grade_id)`、`get_class_schedule(class_id, version_id)`、`get_assignments(teacher_id, class_id)` 都支持 |
| 分页 | ❌ **完全没有**（12 个列表接口全是 `response_model=list[XxxOut]`） |
| 关键字搜索 | ❌ 没有 |
| 排序 | ❌ 没有（只有 `versions` 硬编码按 `version_no desc`） |
| 乐观锁 | ✅ **有**（`swap` / `PATCH version` 都带 `expected_revision`） |
| 租户隔离 | ✅ **有且是自动的**（从 token 取 `organization_id`，前端不用传） |
| 关联名称 | ✅ **有**（`TeacherOut.subject_name`、`ClassOut.grade_name`、`AssignmentOut` 三个 name） |

> **好消息**：现有后端的设计质量不差——租户隔离自动、关联名已内联、乐观锁已实现。
> 这些模式在 Java 侧要**照搬**，不要重新设计。

---

## 二、现有 5 个页面逐页需求（已实现，P1 要逐字平移）

| 页面 | 用到的接口 | 说明 |
|---|---|---|
| `DataManagement.tsx`（404 行） | classes/grades/subjects/teachers 的 CRUD、grade-subjects、assignments、`autoAssignTeachers`、`setTeacherClasses`（17 个） | 4 个 Tab；表格**无分页参数** |
| `ScheduleConfig.tsx`（217 行） | `getGradeFixedSlots` / `createGradeFixedSlot` / `deleteGradeFixedSlot`、classes、grades、assignments | 固定时段配置 |
| `ScheduleGenerate.tsx`（171 行） | `generateSchedule`、`getScheduleStatus`（**1 秒轮询**）、versions、`getClassSchedule`、`swapScheduleEntry` | 拖拽调整**直接提交，无预览** |
| `ScheduleView.tsx`（416 行） | versions、class/teacher/grade schedule、`swapScheduleEntry`（带 `expected_revision`）、`copyScheduleVersion`、`publishScheduleVersion`、`updateScheduleVersion`（改备注）、`getScheduleAudit`、`exportSchedule`（下载 xlsx） | 最复杂的页面 |
| `SystemManagement.tsx`（106 行） | users CRUD、organizations、`getStorageStatus`、`clearGeneratedSchedules`、`compactDatabase` | 按角色隐藏 |

**这 40 个接口是 P1 的平移范围，必须逐字保持**（路径、状态码、响应体、`{"detail":...}`、401）。

---

## 三、新增功能逐组件反推（核心）

按方案 §7.4 的目标文件结构，逐个组件问「**它渲染需要哪些字段**」。

### 3.1 规则系统（P2）

组件：`pages/ScheduleRules.tsx`（新页面）、`components/RuleList.tsx`、`components/SchemaForm.tsx`

| 组件需要什么 | 接口 | 方案已列？ |
|---|---|---|
| 规则列表：**人话描述**、scope（组织/年级/班级/教师/科目）、**scope 的名称**（"三年级"而不是 `3`）、开关、硬/软、权重、来源（manual/assistant）、更新时间 | `GET /api/rules` | ✅ 列了，但⚠ **响应结构没定义**——必须含 `description`（handler 的 `describe()` 生成）、`scope_name`、`handler_display_name`、`params` |
| 全部规则类型的 schema（驱动动态表单） | `GET /api/rules/types` | ✅ |
| 新建 | `POST /api/rules` | ✅ |
| 编辑（**带乐观锁**，§7.5③ 明确要求） | `PUT /api/rules/{id}`（body 带 `expected_revision`） | ✅ 但 ⚠ 契约没写要带 revision |
| 删除 | `DELETE /api/rules/{id}` | ✅ |
| **启用/停用**（列表上的开关，不弹表单） | `PATCH /api/rules/{id}` | ❌ **方案漏了**。或复用 PUT（但那要传全量字段，不适合开关） |
| 试算影响 | `POST /api/rules/trial` | ✅ 但 ⚠ **语义没定**：同步还是异步？返回"约束数变化"还是"重排后的差异"？后者很贵，应该走任务队列 |
| **规则集版本历史**（"这版课表用的哪套规则"要能点开看） | `GET /api/rules/versions`、`GET /api/rules/versions/{id}` | ❌ **方案漏了**，但 §8 的审计链要求它 |

**小计：方案列 5 个，实际需要 8 个。**

### 3.2 不可行性诊断（P2）

组件：`components/DiagnosisPanel.tsx`（改造 `ScheduleGenerate.tsx`）

| 组件需要什么 | 接口 | 方案已列？ |
|---|---|---|
| 某次排课的诊断结果（分组、严重度、修改建议） | `GET /api/schedule/jobs/{id}/diagnosis` | ⚠ **方案写的是无参 `GET /api/schedule/diagnosis`** —— 诊断必然是"某一次排课"的诊断，必须带 job/version |
| 区分"真无解"与"超时未证明" | 同一接口的 `kind` 字段（`INFEASIBLE` / `UNKNOWN`） | ❌ 方案没提，但 §6.4 提到这个区分很重要 |

**需要一张表**：最小冲突集要软化硬约束重求解，**很贵**，必须落库 → `schedule_diagnoses`。
方案 §5.2 的 9 张新表里**没有这张**。

**小计：1 个接口（改语义）+ 1 张表。**

### 3.3 任务队列状态（P3）

组件：改造 `ScheduleGenerate.tsx`

现状：`setInterval(1000)` 轮询 `GET /api/schedule/status`，后端读 `scheduling_state`
（**一个 org 一行**）。所以前端**只能看到"当前这一件事"**，看不到队列。

| 组件需要什么 | 接口 | 方案已列？ |
|---|---|---|
| 当前任务详情（进度百分比、阶段、尝试次数、失败原因） | `GET /api/schedule/jobs/{id}` | ❌ |
| 队列列表（排队位置、他人提交的任务） | `GET /api/schedule/jobs?status=&limit=&offset=` | ❌ |
| 重试按钮（§7.3 明确要） | `POST /api/schedule/jobs/{id}/retry` | ❌ |
| 取消 | `POST /api/schedule/jobs/{id}/cancel` | ❌ |
| 实时进度（可选，替代 1 秒轮询） | SSE `GET /api/schedule/jobs/{id}/events` | ❌ |

**表要加字段**：`schedule_jobs` 需要 `progress`、`attempt`、`last_error`、`cancel_requested`。
方案只写了"状态、job_token、lease_until、重试次数、心跳时间"。

**小计：方案列 0 个，实际需要 4-5 个。**

### 3.4 空闲查询（P5）

组件：`components/AvailabilityPanel.tsx`（新）

| 组件需要什么 | 接口 | 方案已列？ |
|---|---|---|
| 教师某时段是否空闲（占用则显示班级+科目） | `GET /api/availability/teacher/{tid}?day=&period=` | ✅ 但 ⚠ **必须带 `version_id`** —— "空闲"是相对某一版课表的 |
| 班级同上 | `GET /api/availability/class/{cid}?day=&period=&version_id=` | ✅ 同样问题 |
| 教师全天空闲段 | `GET /api/availability/teacher/{tid}/free-slots?day=&version_id=` | ✅ 同样问题 |

**小计：3 个接口（方案列了，但参数要补）。**

### 3.5 交换干跑（P5）

组件：`components/SwapPreviewModal.tsx`（改造 `ScheduleView.tsx` 的拖拽）
现状：`movePreviewCourse` **直接调 `swap` 提交**（`ScheduleGenerate.tsx:101`），拖错了要重来。

| 组件需要什么 | 接口 | 方案已列？ |
|---|---|---|
| 能否交换 + 影响哪几条 + 有无冲突 | `POST /api/schedule/versions/{id}/swap/preview` | ✅ 但 ⚠ **响应结构完全没定义** |
| 预览要返回 `expected_revision`（供确认时带上） | 同上 | ❌ 方案没提，但不返回的话确认时会撞乐观锁 |
| 确认执行 | 复用现有 `POST .../swap` | ✅ 已存在 |

**响应至少要含**：`can_swap` / `affected_entries[{entry_id, before:{day,period}, after:{...}}]` /
`conflicts[{type, message}]` / `expected_revision`。

**小计：1 个接口（响应结构要定义）。**

### 3.6 排课助手（P4-P5）

组件：`components/assistant/AssistantDrawer.tsx`、`MessageList.tsx`、
`ToolCallTrace.tsx`、`ProposalCard.tsx`、`stores/assistantStore.ts`

| 组件需要什么 | 接口 | 方案已列？ |
|---|---|---|
| 对话（SSE 流式） | `POST /api/assistant/chat` | ✅ |
| **会话列表**（抽屉左侧要能切换历史会话） | `GET /api/assistant/conversations` | ❌ |
| **加载历史消息**（点开会话要能看到之前的对话） | `GET /api/assistant/conversations/{id}/messages` | ❌ |
| 确认草案 | `POST /api/assistant/actions/{id}/confirm` | ✅ |
| **取消草案**（`ProposalCard` 上的"取消"按钮） | `POST /api/assistant/actions/{id}/reject` | ❌ |
| 审计/工具轨迹查询 | `GET /api/assistant/actions?conversation_id=` | ✅（`GET /api/assistant/actions`） |
| **用量与配额**（§6.5 有成本控制，前端要显示"今日剩余额度"） | `GET /api/assistant/usage` | ❌ |
| 删除会话 | `DELETE /api/assistant/conversations/{id}` | ❌ |

**表要改**：`assistant_actions` 方案说是"每次工具调用"一行。
但 `ToolCallTrace` 要**按轮次**展示（一轮对话可能调 3 个工具），
所以这张表需要一个 **`turn_index`（或 `message_id`）**把同一轮归组 —— **方案没写**。

**小计：方案列 3 个，实际需要 8 个。**

### 3.7 质量评分（P5）

组件：`components/QualityScoreCard.tsx`（新）

| 组件需要什么 | 接口 | 方案已列？ |
|---|---|---|
| 单版课表的评分（多维度） | `GET /api/schedule/versions/{id}/score` | ❌（`score_timetable` 只是**助手工具**，没有对外 REST） |
| 与上版对比 | `GET /api/schedule/versions/{id}/score/diff?against={id}` | ❌ |

**表要定**：评分要落库吗？"与上版对比"需要历史评分 → 给 `schedule_versions` 加 `score JSON` 字段最简单。
**而且评分必须带 `rule_set_hash`** —— 同一个课表在不同规则集下分数不同，不记 hash 的分数没有意义。

**小计：2 个接口 + 1 个字段。**

### 3.8 数据导入（可选）

| 接口 | 方案已列？ |
|---|---|
| `POST /api/import/preview`（上传 Excel → LLM 列映射草案） | ❌ |
| `POST /api/import/commit` | ❌ |
| `GET /api/import/tasks/{id}` | ❌ |

**还要一张表**：`import_tasks`（或复用 `schedule_jobs` 的模式）。

**小计：3 个接口 + 1 张表。**（可选功能，可以砍）

### 3.9 其他被漏掉的

| 需求 | 接口 | 为什么需要 |
|---|---|---|
| **组织级审计列表** | `GET /api/schedule/audit-logs?limit=&offset=` | 现在只有 `GET /versions/{id}/audit`（版本级）。前端要"最近操作记录"就需要跨版本 |
| **轻量选项接口** | `GET /api/options/teaching-context` | 新建任教关系要选"班级 × 科目 × 教师"三个下拉。现在 `autoAssignTeachers` 那页已经拉全量了，规模大了浪费带宽 |
| **首页概览** | `GET /api/overview` | "班级数 / 教师数 / 课时缺口 / 最近版本 / 进行中任务"。现在首页直接是数据管理页，没有概览 |
| **批量删除** | `POST /api/teachers/batch-delete` 等 | 现在只能逐个删（`/assignments/auto`、`/grade-subjects/bulk` **已有** ✅） |

---

## 四、横切契约（不是新接口，但影响**所有**列表接口）

这是**最容易在 P1 漏掉、事后最难补**的一块。

### 4.1 分页

现在：`GET /api/teachers` → `[{...}, {...}]`（裸数组，全量）

要改成：`GET /api/teachers?page=1&size=20&keyword=张` → `{items: [...], total: 123}`

**⚠ 这会改变响应结构**，前端所有 `getXxx()` 都要跟着改。两个方案：

| 方案 | 做法 | 代价 |
|---|---|---|
| A | 有分页参数时返回 `{items,total}`，无参数时返回数组 | 同一个接口两种返回结构，**很难看，容易出错** |
| B | **统一改成 `{items,total}`**，前端一起改 | 动前端，但**趁 P1 迁移时一起做最省事** |

**建议 B**，而且**必须在 P1 做**——理由见下一节。

### 4.2 搜索与排序

- `?keyword=`：教师按姓名、班级按名称、科目按名称、规则按描述
- `?order_by=&order=asc|desc`：至少支持按 `name` / `created_at`

### 4.3 为什么必须在 P1 做，而不是等 P2

```
如果 P1 只平移现有 50 个接口（不带分页）
    ↓
P1 的契约测试把这 50 个接口的「裸数组响应」冻结成基线
    ↓
P2 前端开工，发现表格要分页
    ↓
要么改契约（P1 的契约测试全部失效，要重跑）
要么打补丁（同一个接口两种响应结构）
```

**分页是"平移时顺手做"的成本最低——那时你本来就在逐字核对每个接口。**

---

## 五、汇总

### 5.1 接口数

| 类别 | 方案 §13 | 实际需要 | 差 |
|---|---:|---:|---:|
| 现有（必须逐字平移） | 50 | 50 | — |
| 规则系统 | 5 | **8** | +3 |
| 诊断 | 1 | 1（改语义） | — |
| 任务队列 | **0** | **5** | +5 |
| 空闲查询 | 3 | 3（补参数） | — |
| 交换预览 | 1 | 1（定响应） | — |
| 助手 | 3 | **8** | +5 |
| 质量评分 | **0** | **2** | +2 |
| Excel 导入 | **0** | **3** | +3 |
| 组织级审计 | 0 | 1 | +1 |
| 概览 / 选项 / 批量 | 0 | 3 | +3 |
| **合计** | **61** | **≈ 85** | **+24** |

### 5.2 表

| 项 | 方案 | 实际需要 |
|---|---|---|
| 现有 | 16 | 16 |
| `schedule_jobs` | ✅ 列了 | 加字段：`progress` / `attempt` / `last_error` / `cancel_requested` |
| `schedule_diagnoses` | ❌ | **加** |
| `import_tasks` | ❌ | **加**（可选功能） |
| `schedule_versions.score` | ❌ | **加字段**（或单开表） |
| `assistant_actions` | ✅ 列了 | 加字段：`turn_index`（按轮归组） |
| 其余 6 张 | ✅ | 不变 |
| **合计** | **25** | **≈ 28** |

### 5.3 横切

| 项 | 状态 |
|---|---|
| 分页（12 个列表接口） | ❌ 全缺 |
| 搜索 | ❌ 全缺 |
| 排序 | ❌ 全缺 |
| 按父级过滤 | ✅ 已有 |
| 乐观锁 | ✅ 已有（`swap` / `PATCH version`） |
| 租户隔离 | ✅ 已有且自动 |
| 关联名称内联 | ✅ 已有 |

---

## 六、对 P1 的建议

1. **P1 范围不是"50 个接口"，而是"50 个接口 + 分页/搜索/排序契约"**
   这两块都是"平移"性质，必须一次做对。新增接口不在这里。

2. **新增接口按阶段排**（不要一次全设计）：
   - P2：规则 8 个 + 诊断 1 个
   - P3：任务队列 5 个
   - P4-P5：助手 8 个 + 评分 2 个 + 空闲 3 个 + 交换预览 1 个
   - 可选：导入 3 个

3. **产出一份 OpenAPI 作为冻结契约**（Python 是 FastAPI，`/openapi.json` 直接导出），
   然后 P1 的契约测试对着它跑，而不是对着"我记得的"行为跑。

4. **前端 `api/index.ts` 要同步改造**：分页会让每个 `getXxx()` 的返回类型变化。

---

## 附：为什么不能「从后端能力往上列」

方案 §13 的接口清单就是这么来的：把现有 50 个端点归类，再按新功能点补 11 个。
这个方向**天然会漏**，因为它不知道前端组件到底要什么数据。

这次漏掉的 24 个里，最典型的三类：

| 类型 | 例子 | 为什么会漏 |
|---|---|---|
| **方案自己要求了功能，但没列接口** | 任务队列（§7.3 明确写了"排队位置、进度、失败原因、重试按钮"） | 写清单时按"接口"想，没回头核对 §7.3 的功能清单 |
| **组件上有个按钮，就必然有个接口** | `ProposalCard` 的"取消" → `reject` | 只有看到组件才想得起来 |
| **横切关注点不体现在任何单个接口上** | 分页 | 它不是"某个新接口"，是"所有列表接口的属性" |

**所以「从组件反推」比「从能力往上列」可靠**——尤其是第三类，
只有拿组件清单过一遍才不会漏。
