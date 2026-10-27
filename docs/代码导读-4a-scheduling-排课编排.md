# 代码导读 · 4a. `orbit-scheduling` 排课编排

> **本册范围**：`backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/`，
> **共 9 个类**（全部逐行讲；无降级为表格说明的类）。
> 上游：[代码导读.md](代码导读.md)（第 1 册 `orbit-common`，格式与详细程度的基准）
> 骨架：[架构说明.md](架构说明.md) §4.4「一次排课的完整旅程」

**本册负责的 9 个类**：

| 类 | 一句话 | 行数 |
|---|---|---|
| [`ScheduleController`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/ScheduleController.java) | 两个 HTTP 门面：generate / status | 58 |
| [`ScheduleRunService`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/ScheduleRunService.java) | ★ **四道关卡**，全项目最复杂的一个方法 | 158 |
| [`ScheduleStateService`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/ScheduleStateService.java) | 状态行的读 / 收尾 / 心跳（全是条件 UPDATE） | 76 |
| [`SchedulingState`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/SchedulingState.java) | `scheduling_state` 实体：**每组织一行**的状态机 | 102 |
| [`SchedulingStateMapper`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/SchedulingStateMapper.java) | 自定 SQL 只有一条 `lockForRunning`（⚠ 当前**无人调用**） | 33 |
| [`ScheduleStatusResponse`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/ScheduleStatusResponse.java) | 对外状态体：7 个字段，其中 2 个**恒为 null** | 34 |
| [`ScheduleEntity`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/ScheduleEntity.java) | `schedules` 表：**当前已发布**课表的投影 | 86 |
| [`ScheduleEntityMapper`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/ScheduleEntityMapper.java) | 空 Mapper（纯 `BaseMapper`） | 9 |
| [`TimetableGenerationService`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/schedule/TimetableGenerationService.java) | ⚠ **已不在 generate 链路上**，只剩诊断在用 | 34 |

> 行数为源码实际总行数（含空行与 javadoc），来自 `Get-Content | Measure-Object`。

---

## 本模块在系统里的位置

排课天然是「**长时间运行的写操作 + 并发敏感**」，所以这一层要解决的不是"怎么排"，
而是「**同一所学校、同一时刻，只允许有一次排课在推进**」。
架构说明 §4.4 把这条链路画成了一条线，本册讲的就是那条线上**属于 scheduling 的前半段**：

```
① 前端 POST /api/schedule/generate
② ScheduleController.generate          ← 本册（只取参数、调 Service、返回）
③ ScheduleRunService.trigger           ← 本册（★ 四道关卡，顺序不能变）
       ① Redis 锁   ② 状态检查   ③ CAS 抢状态   ④ 冻结快照 + 入队
④ 立刻释放 Redis 锁 → 返回 {"status":"running","message":"已入队，等待求解…"}
   ══════════════ 进程边界（求解在 orbit-solver-worker） ══════════════
⑤ JobQueueService.claim / JobResultWriter   ← 第 5 册
⑥ ScheduleStateService.finishByOrganization ← 本册（三条终态路径都要调它）
```

**三条架构铁律里，本册是铁律 1 的落点**：

| 铁律 | 在本册的落点 |
|---|---|
| **① 排课原子性不跨服务** | `scheduling_state` 上的 CAS + `job_token` 栅栏、Redis 锁全部在 scheduling 进程内完成，**没有一次跨服务调用** |
| **② 禁止跨服务写库** | 本册 9 个类只写本服务属主的表（`scheduling_state`、`schedules`）；读 academic 的表发生在 `snapshot/ScheduleInputLoader`（第 4b 册） |
| **③ 排课输入用快照** | `trigger` 第 ④ 关先 `SnapshotService.freeze(...)` 再入队 —— 快照在**入队时**冻结，不是求解时现取 |

> ⚠ **P3 把这一层改写了一次**：P1/P2 是「抢到槽位 → 起进程内线程 → 求解 → 写结果」，
> P3 之后是「冻结快照 → 入队 → 独立进程领单求解 → 回写」。
> 本册要讲清的就是**哪些东西变了、哪些没变、以及变了之后锁的职责为什么必须跟着缩**。

---

## 阅读顺序建议

**只读 2 个类就能建立整体理解**（约 230 行）：

| 顺序 | 类 | 读完你会知道 |
|---|---|---|
| 1 | `ScheduleController` | 入口长什么样、为什么 `generate` 收下 body 却完全不看它 |
| 2 | `ScheduleRunService` | ★ 四道关卡的全部理由；CAS 里三个 `OR` 各挡什么；为什么锁必须在入队后立刻放 |

**然后按需深入**：

- 想搞清「状态行被谁改、带不带 token」→ `SchedulingStateService` → `SchedulingState` → `SchedulingStateMapper`
- 想搞清「前端看到的那 7 个字段哪来的」→ `ScheduleStatusResponse`
- 想搞清「排好的课表存在哪」→ `ScheduleEntity` / `ScheduleEntityMapper`
- 想搞清「求解现在谁在跑」→ `TimetableGenerationService`（答案会有点意外）

---

### 4a.1 `ScheduleController` —— 两个 HTTP 门面（58 行）

**谁调它**：前端（Vite 按 `/api/**` 分流到 :8083）。
`frontend/src/pages/ScheduleGenerate.tsx` 调 `/api/schedule/generate` 发起排课、
**每 1 秒**轮询 `/api/schedule/status` 驱动进度条。

**它调谁**：`ScheduleRunService.trigger`（generate）、`ScheduleStateService.findByOrganization`（status）。

它属于第 1 册 §0.1 说的那种 Controller：**只做三件事 —— 取参数、调 Service、返回**，
类里**没有任何业务逻辑**。

#### 字段与构造器

```java
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {
    private final ScheduleStateService stateService;
    private final ScheduleRunService runService;
```

| 行 | 做什么 |
|---|---|
| `@RestController` | `@Controller` + `@ResponseBody`：返回值直接序列化成 JSON |
| `@RequestMapping("/api/schedule")` | 两个方法的路径前缀。⚠ 这个前缀也是 **Vite 分流规则**的一部分：`/api/schedule/**` → :8083（见 `frontend/vite.config.ts` 与 `tools/contract-diff/compare.py` 的 `SCHEDULING_PREFIXES`） |
| 两个 `final` 字段 + 构造器注入 | 不用 `@Autowired` 字段注入 —— 全项目统一构造器注入，好处是**依赖是 final 的**、测试能直接 new |

> ⚠ 类级 javadoc 里仍然写着「进程内后台线程」——那是 P1 的措辞，P3 之后
> scheduling 侧**没有**任何跑求解的线程了。这是**注释滞后于代码**的又一处
> （与 `TimetableGenerationService` 里那个悬空的 `{@link ScheduleRunner}` 同源）。

#### `POST /api/schedule/generate`

```java
@PostMapping("/generate")
public ScheduleStatusResponse generate(
        @RequestBody(required = false) Map<String, Object> ignoredBody,
        @AuthenticationPrincipal SchedulePrincipal principal) {
    return runService.trigger(requirePrincipal(principal));
}
```

| 细节 | 为什么 |
|---|---|
| 参数名叫 **`ignoredBody`** | 名字就是文档：**它一个字节都不被读取**。请求体在 Python 侧是个空模型（`class ScheduleGenerateRequest: pass`）——「排哪所学校、用哪份数据」全部从库里取、从 token 里的组织上下文取 |
| `required = false` | ⚠ 不让「前端没带 body」直接变成 422。Python 那边空的 `{}` 也是合法的，所以这里**选更宽松的一侧**——宽松不会误伤真实前端，严格会 |
| 泛型用 `Map<String, Object>` 而不是某个 record | 既然不看，就不该为它建一个 DTO；建了反而会有人以为「加个字段就能传参」 |
| `@AuthenticationPrincipal SchedulePrincipal principal` | 由 `JwtAuthenticationFilter` 放进 `SecurityContext` 的**只读视图**（`security/SchedulePrincipal`，第 4b 册讲）。⚠ 注意它是 scheduling 自己的 `SchedulePrincipal`，**不是** academic 的 `OrbitUserPrincipal` —— 两个服务各有一份，靠 token 契约对齐 |
| `return runService.trigger(...)` | 直接返回 Service 的返回值，**不做任何包装**（形状由 `ScheduleStatusResponse` 决定） |

#### `GET /api/schedule/status`

```java
@GetMapping("/status")
public ScheduleStatusResponse status(@AuthenticationPrincipal SchedulePrincipal principal) {
    SchedulePrincipal user = requirePrincipal(principal);
    SchedulingState state = stateService.findByOrganization(user.organizationId());
    return state == null ? ScheduleStatusResponse.idleDefault()
            : ScheduleStatusResponse.from(state);
}
```

按代码顺序：

1. `requirePrincipal(principal)` —— 先兜底鉴权，拿到带 `organizationId` 的用户。
2. `stateService.findByOrganization(user.organizationId())` —— **按 `organization_id` 点查一行**。
   ⚠ 这一列上有唯一索引 `uq_scheduling_state_org`（见
   [V1__baseline_legacy.sql](../backend-java/orbit-scheduling/src/main/resources/db/migration/scheduling/V1__baseline_legacy.sql)），
   所以它是**索引点查**。
3. `state == null ? idleDefault() : from(state)` —— 这就是那一处**与 Python 的有意偏离**，
   详见 4a.6 的 ⚠ 段。

> ⚠ **为什么没有缓存**：这个接口**每校每秒一次**（前端轮询）。不加缓存是刻意的 ——
> 状态行变化很快（`running` → `done` → 用户看到结果），缓存会让进度条卡在旧值上。
> 代价只是一次索引点查，不值得为它引入失效逻辑。

#### `private static SchedulePrincipal requirePrincipal(SchedulePrincipal principal)`

```java
if (principal == null) {
    throw BusinessException.unauthorized("请先登录");
}
return principal;
```

| 细节 | 为什么 |
|---|---|
| 抛 `BusinessException`（401） | 交给 `ApiExceptionHandler`（第 1 册 §1.5）翻译成 FastAPI 形状的 `{"detail":"请先登录"}` |
| `static` | 不依赖实例状态 |
| ⚠ **几乎不可能为 null** | `SchedulingSecurityConfig` 的 `.anyRequest().authenticated()` 已经挡住了未认证请求。留着它是**防御性**的：万一哪天安全配置被改松，结果也是干净的 401 而不是 NPE → 500 |
| ⚠ 文案 `请先登录` 是**硬编码**的，没有引用 `AuthFailure.NO_TOKEN` | 两者文案相同但**来源不同**：`AuthFailure` 的常量语义是「过滤器写在请求属性上、入口点读出来」，这里是「Controller 自己兜底」。**改文案时要两处一起改** —— 项目里一个真实的小陷阱 |

---

### 4a.2 ★ `ScheduleRunService` —— generate 入口（158 行）

**对应 Python 的 `trigger_schedule`（`backend/routers/schedule.py:127-195`），P1 收官的那个接口。**

**谁调它**：只有 `ScheduleController.generate`。
**它调谁**：`ScheduleLockService`（①）、`ScheduleStateService`（② 与重读）、
`SchedulingStateMapper`（③ 直接做 CAS）、`SnapshotService` + `RuleSetService` + `JobQueueService`（④）。

⚠ **它直接注入 `SchedulingStateMapper` 而不是只用 `ScheduleStateService`** ——
因为第 ③ 关的 CAS 需要 `Wrappers.lambdaUpdate()` 拼出「三个 `OR` + 七个 `SET`」，
而 `ScheduleStateService` 刻意没有暴露这个口子（那层只提供有语义的三个方法）。

#### 依赖与构造器

```java
public ScheduleRunService(SchedulingStateMapper stateMapper,
                          ScheduleStateService stateService,
                          ScheduleLockService lockService,
                          JobQueueService queueService,
                          SnapshotService snapshotService,
                          RuleSetService ruleSetService,
                          @Value("${orbit.scheduling.lease-seconds:300}") int leaseSeconds,
                          @Value("${orbit.scheduling.max-attempts:3}") int maxAttempts) {
    ...
    this.leaseSeconds = Math.max(120, leaseSeconds);
    this.maxAttempts = Math.max(1, maxAttempts);
}
```

| 参数 | 用途 |
|---|---|
| `stateMapper` | 第 ③ 关的 CAS（唯一的用处） |
| `stateService` | 第 ② 关读状态、第 ③ 关失败时重读、入队失败时收尾、最后返回前重读 |
| `lockService` | 第 ① 关抢锁；四个失败分支里释放 |
| `queueService` | 第 ④ 关入队（`enqueue`） |
| `snapshotService` | 第 ④ 关冻结输入（`freeze`） |
| `ruleSetService` | 第 ④ 关算规则集指纹（`hashOf`） |
| `leaseSeconds` | 租约线长度。配置键 `orbit.scheduling.lease-seconds`，默认 **300** |
| `maxAttempts` | 任务最多被重试几次（只是**写进任务行**，本类不判断） |

两行归一化：

| 行 | 为什么 |
|---|---|
| `this.leaseSeconds = Math.max(120, leaseSeconds)` | ⚠ **不是防御性编程，是行为对齐**：Python 是 `SCHEDULE_LEASE_SECONDS = max(120, int(os.getenv(...)))`。租约短于 120 秒 ⇒ **求解还没跑完就被判定超时、被别人接管** ⇒ 同一所学校同时跑两个求解。⚠ `CapacityService` 用**同一个配置键、同一条下限**，两处必须一致：不一致会让「状态行的租约」与「槽位的租约」错位 |
| `this.maxAttempts = Math.max(1, maxAttempts)` | 至少重试一次。`0` 会让 `schedule_jobs.max_attempts=0`，任务一失败就再也不能被领走 |

> ⚠ **`random` 是字段而不是局部变量**（`private final SecureRandom random = new SecureRandom();`）：
> `newJobToken()` 每次都要取随机字节，而 `SecureRandom` 的构造相对昂贵。
> 本类是单例 Bean，所以复用一个实例。

#### ★ `ScheduleStatusResponse trigger(SchedulePrincipal principal)`

**四道关卡，顺序不能变。** 先看「顺序为什么是这个顺序」：

| 关 | 手段 | 挡什么 | 为什么必须在**这个**位置 |
|---|---|---|---|
| ① | Redis 锁 | **跨实例**的重复提交 | **在碰数据库之前就拦掉**：它最便宜（一次 Redis 往返），先做能省掉后面全部开销 |
| ② | 读状态行 | **同校**已有未过租约的 running | 必须在 CAS **之前**，因为它的目的是**给出一句带姓名的 409**。CAS 也能挡住，但 CAS 只会说「别人在跑」，说不出**是谁** |
| ③ | CAS 抢状态 | ②与③之间的竞态 + **接管过期任务** | 必须在**冻结快照之前**：没抢到状态就冻结快照，等于给一次不会发生的排课做无用功、还留下孤儿快照 |
| ④ | 冻结快照 + 入队 | 「求解时数据已被改」 | 最后 —— 前三关都过了才轮到真正落地；**失败要回头**把第 ③ 关抢到的状态标成 `failed` |

**第 0 步：先算好三个值**（第 84-87 行）

```java
long organizationId = principal.organizationId();
LocalDateTime now = UtcTimes.now();
LocalDateTime staleBefore = now.minusSeconds(leaseSeconds);
String jobToken = newJobToken();
```

| 行 | 为什么 |
|---|---|
| `UtcTimes.now()` | ⚠ **不是 `LocalDateTime.now()`**。库里存的是朴素 UTC，用本地时间会差 8 小时 —— 不报错、不崩，只在排序/展示时表现很怪（第 1 册 §1.1） |
| `staleBefore = now - leaseSeconds` | **租约线**。第 ② 关与第 ③ 关都用它判断「是不是过期了」 |
| `jobToken = newJobToken()` | 本次排课的**栅栏 token**。⚠ 它**先于抢锁**生成（Python 也是这个顺序）—— 因为它要同时写进状态行和任务行 |

**第 ① 关：Redis 锁**（第 89-93 行）

```java
ScheduleLockService.LockAttempt lock = lockService.acquire(organizationId);
if (!lock.acquired()) {
    throw BusinessException.conflict("本校已有排课任务正在运行，请稍后再试");
}
```

| 细节 | 说明 |
|---|---|
| 失败返回 | **409**，文案逐字为 `本校已有排课任务正在运行，请稍后再试`（对齐 Python） |
| ⚠ 此时**不释放锁** | 本来就没抢到，没什么可释放的；而且这把锁是**别人的** |
| ⚠ `acquired()` 的语义是「**可以继续**」 | 不是「真的拿到锁」。Redis 不可用时降级放行（`!redisEnabled || token != null`）。所以这一步**不是**同校互斥的唯一保证 —— 它只挡跨实例，真正的同校互斥在第 ②③ 关 |
| ⚠ 降级时 `lock.token()` 是 `null` | 后面所有 `lockService.release(...)` 会**静默返回**（`release` 第一行就是 `if (token == null) return;`） |

**第 ② 关：状态检查**（第 95-102 行）

```java
SchedulingState current = stateService.findByOrganization(organizationId);
if (current != null && "running".equals(current.getStatus())
        && current.getStartedAt() != null
        && !current.getStartedAt().isBefore(staleBefore)) {
    lockService.release(organizationId, lock.token());
    throw BusinessException.conflict(ownerOf(current) + "正在排课，请稍后再试");
}
```

四个条件**逐个都不能少**：

| 条件 | 在挡什么 / 不写会怎样 |
|---|---|
| `current != null` | 没有状态行 → **不拦**，放给第 ③ 关（那里会给出同样的 409） |
| `"running".equals(current.getStatus())` | ⚠ **常量在前**。`status` 列是 `NOT NULL`，但读回来的对象仍可能是 null；写成 `current.getStatus().equals("running")` 在脏数据下会 NPE |
| `current.getStartedAt() != null` | `started_at` 可空。NULL 意味着「从没记录过开始时间」⇒ **不能据此判超时**，放给第 ③ 关 |
| `!startedAt.isBefore(staleBefore)` | 等价于 `startedAt >= staleBefore` ⇒ **租约还没过**。⚠ `isBefore` 是**严格**比较，所以**恰好等于租约线算「还没过」** —— 与 Python 的 `started >= stale_before` 一致 |

失败文案是 `ownerOf(current) + "正在排课，请稍后再试"`，例如 `张三正在排课，请稍后再试`。

> ⚠ **顺序为什么是「先放锁再抛」**：不先放锁，这把锁会一直留到 TTL 到期（默认 300 秒），
> 这期间**所有**排课请求都被第 ① 关挡在门外 —— 一次误判 = 5 分钟全校不可排课。
> 第 ③ 关的失败分支同理。**本方法一共有 4 个 `release` 调用点，每一个都对应一条失败路径**，
> 漏掉任何一个都会变成「300 秒不可排课」。

**第 ③ 关：CAS 抢状态**（第 104-128 行）

```java
int acquired;
try {
    acquired = stateMapper.update(null, Wrappers.<SchedulingState>lambdaUpdate()
            .eq(SchedulingState::getOrganizationId, organizationId)
            .and(where -> where
                    .ne(SchedulingState::getStatus, "running")
                    .or().isNull(SchedulingState::getStartedAt)
                    .or().lt(SchedulingState::getStartedAt, staleBefore))
            .set(SchedulingState::getStatus, "running")
            .set(SchedulingState::getMessage, "已入队，等待求解…")
            .set(SchedulingState::getOwnerUserId, principal.userId())
            .set(SchedulingState::getOwnerName, principal.displayName())
            .set(SchedulingState::getStartedAt, now)
            .set(SchedulingState::getFinishedAt, null)
            .set(SchedulingState::getJobToken, jobToken));
} catch (RuntimeException e) {
    lockService.release(organizationId, lock.token());
    throw e;
}
if (acquired != 1) {
    lockService.release(organizationId, lock.token());
    SchedulingState state = stateService.findByOrganization(organizationId);
    throw BusinessException.conflict(ownerOf(state) + "正在排课，请稍后再试");
}
```

**手法先说清**：MyBatis-Plus 的 `update(entity, wrapper)`，第一个参数传 **`null`** ——
因为要写的列全部由 `.set(...)` 显式列出，传实体反而会引入「null 字段要不要写」的策略问题。
生成的 SQL 形状（`.and(w -> ...)` 会产生**一对括号**，这一点很重要）：

```sql
UPDATE scheduling_state
   SET status='running', message='已入队，等待求解…',
       owner_user_id=?, owner_name=?, started_at=?, finished_at=NULL, job_token=?
 WHERE organization_id = ?
   AND (status <> 'running' OR started_at IS NULL OR started_at < ?)
```

##### `WHERE` 里那三个 `OR` 各自在挡什么

| 条件 | 语义 | 挡什么 / 放开什么 |
|---|---|---|
| `status <> 'running'` | 当前不是 running | 挡 `running`；**放开 `idle` / `done` / `failed`** ⇒ 「上一次跑完了，可以再排一次」 |
| `started_at IS NULL` | 从没有开始时间 | 挡「有开始时间」的行；**放开 NULL** ⇒ 接管「状态是 running 但 `started_at` 是 NULL」的畸形行。（进程在写完 status 后、写 started_at 前崩了 —— 实际上两者是同一条 UPDATE，不会出现；这是**照抄 Python 的 `or_()`**，不自作主张删掉） |
| `started_at < staleBefore` | 开始时间**早于租约线** | ⚠ **这就是「接管过期任务」**：上一次跑崩了/被 kill 了（或 worker 根本没起来），租约过期后**必须允许我抢过来**，否则这所学校**永久锁死**。⚠ 这与 P3 的 Redis 锁那个 bug 是**同一类事故**：症状都是「这所学校再也发不起新排课」，而不是崩溃 |

> ⚠ **`ne` 对 NULL 的行为**：SQL 里 `NULL <> 'running'` 的结果是 **NULL（不是 TRUE）**。
> 所以当 `status` 列有脏数据为 NULL 时，真正起作用的是第二个 `OR`。
> 这也正是三个条件**必须用 `or()` 串在一个括号里**的原因 ——
> 写成 `status<>'running' AND started_at<线` 语义完全不同：它要求**同时**满足，
> 会把「上一次正常跑完（status=done，started_at 是刚刚）」也一起挡住 ⇒ 正常用户第二次排课直接 409。

> ⚠ **为什么 `rowcount != 1` 就要放弃**（而不是 `== 0`）：
> 唯一约束保证一个学校只有一行，所以「> 1」理论上不可能；写成 `!= 1` 是把
> **「异常的多行」也当失败**处理 —— 与 Python 的 `if acquired != 1` 逐字一致。
> 更本质的理由是：**这一条 UPDATE 同时是「判据」和「动作」**，
> `rowcount = 1` 是「我抢到了」的**唯一证据**。拿不到 1 就说明：
> ① 条件不满足（别人正持有未过期的 running），或 ② 行不存在。
> 两种情况下都**不能继续往下走** —— 因为第 ④ 关会冻结快照、写任务行，
> 而这次排课其实并没有拿到「这所学校的排课权」。继续走的结果是：
> 两个请求同时入队 ⇒ 两个 worker 同时求解 ⇒ 落两版课表。

##### `SET` 里的每一列在说什么

| `.set(...)` | 为什么 |
|---|---|
| `status = "running"` | 抢到了就是「在跑」 |
| `message = "已入队，等待求解…"` | ⚠ **P3 改过这一句**：P1/P2 是 `正在排课...`。类注释明确写了「对外文案与状态码没有变，只是 message 改了」—— 前端只把它当一句话显示，不参与解析。**状态码仍是 200/409、`status` 仍是 `running`** |
| `ownerUserId` / `ownerName` | 让第 ② 关能说出「**谁**正在排课」。⚠ `ownerName` 是**快照**（不是 join 出来的）：用户改名后，旧的排课记录仍显示当时的名字 —— 与 Python 一致 |
| `startedAt = now` | ① 租约判定的基准 ② 心跳复用的字段 |
| `finishedAt = null` | ⚠ **必须显式清掉**：上一次是 `done`/`failed` 时 `finished_at` 有值，不清就会「正在跑的任务带着完成时间」，前端显示错乱。⚠ 在 MyBatis-Plus 里 `.set(x, null)` 会真的写 `SET x = NULL`（区别于「传实体」时 null 字段默认被忽略）—— **这正是这里必须用 `.set()` 而不能传实体的原因** |
| `jobToken = jobToken` | 写入本次的栅栏 token |

**异常分支**（第 120-123 行）：CAS 抛异常（连接断了 / 死锁回滚）时
**先释放锁，再把异常往上抛**。不释放的后果同上：这把锁锁死这个学校 300 秒。
不吞异常：这是真的服务端故障，应该变成 500（`ApiExceptionHandler` 兜底）。

**行数判定**（第 124-128 行）的四个细节：

| 细节 | 为什么 |
|---|---|
| `!= 1` 而不是 `== 0` | 见上面那个 ⚠ |
| **为什么重新 `findByOrganization`** | 因为要填 409 文案里的**姓名**。行数是抢完才知道的，只有重读一次才知道现在是谁在跑 —— 可能是**别的请求刚刚抢到**的，不是第 ② 关看到的那个 |
| 这个分支什么时候会走到 | 第 ②③ 关之间（几毫秒）有另一个并发请求先完成了 CAS ⇒ 重读**一定能读到它的姓名** ✓。另一种情况是状态行**根本不存在**（见 4a.3 末尾的 ⚠） |
| `ownerOf(state)` 对 null 的处理 | 见 4a.2 的 `ownerOf`：返回 `其他用户`，与 Python 的 `state.owner_name if state else '其他用户'` 一致 |

**第 ④ 关：冻结快照 + 锁定规则集 + 入队**（第 130-149 行）

```java
try {
    SchedulingSnapshotEntity snapshot = snapshotService.freeze(organizationId);
    String ruleSetHash = ruleSetService.hashOf(organizationId);
    ScheduleJobEntity job = queueService.enqueue(organizationId, principal.userId(),
            ruleSetHash, snapshot.getId(), maxAttempts);
    log.info("排课任务已入队: org={} job={} snapshot={} 输入指纹={}",
            organizationId, job.getId(), snapshot.getId(),
            snapshot.getFingerprint().substring(0, 12));
} catch (RuntimeException e) {
    log.error("入队失败 org={}", organizationId, e);
    stateService.finish(organizationId, jobToken, "failed", "排课任务入队失败");
    lockService.release(organizationId, lock.token());
    throw e;
}
```

三件事的顺序有讲究：

| 步 | 做什么 | 为什么在这个顺序 |
|---|---|---|
| ① `snapshotService.freeze(org)` | 现查 academic 的 7 张表 → 规范化 JSON → sha256 指纹 → INSERT `scheduling_snapshots` | 必须在入队**之前**：任务的 `snapshot_id` 是外键式引用，没有快照就没有 id 可入队 |
| ② `ruleSetService.hashOf(org)` | 算「这次用哪套规则」的指纹（只覆盖 enabled 的行） | 同样要在入队前，因为它要写进任务行 |
| ③ `queueService.enqueue(...)` | INSERT `schedule_jobs(status='pending', job_token=…)` + 一条 `enqueued` 事件 | 最后 —— 它是「这次排课正式存在」的那一刻 |

`log.info` 那行的 `snapshot.getFingerprint().substring(0, 12)` 只取指纹前 12 字符：
`fingerprintOf` 是 **sha256 的 64 位小写 hex**，全打出来在日志里没人读。

**失败处理的三条，逐条都有理由**：

| 行 | 做什么 | 为什么 |
|---|---|---|
| `log.error("入队失败 org={}", organizationId, e)` | 打日志 | 与第 1 册 §1.5 的 `handleUnexpected` 同理：不打日志的话，「排课点不动」这件事在后端**什么都没有**，只能靠猜 |
| `stateService.finish(organizationId, jobToken, "failed", "排课任务入队失败")` | **收尾状态行** | ⚠ **这里用的是带 token 的 `finish`，不是 `finishByOrganization`** —— 因为还没入队，**没有换过 token**，`scheduling_state.job_token` 就是 `jobToken`，条件满足。收尾时必须做，否则状态卡在 `running` ⇒ 这所学校再也发不起新排课 |
| `lockService.release(...)` | 释放锁 | 同上；不释放 → 300 秒不可排课 |
| `throw e` | **往外抛** | 不吞异常：这是真的服务端故障，应该变成 500。⚠ 与 Python 的说法不同：Python 起线程失败时打的是 `"排课任务启动失败"`；这里是 `"排课任务入队失败"`（P3 换了说法，因为不再是「起线程」） |

> ⚠ **这个分支会留下一个「孤儿快照」**：`freeze` 成功了但 `enqueue` 失败 ⇒
> `scheduling_snapshots` 里多一行没有任何任务引用的快照。
> **这是有意的取舍**：快照是**只增的溯源记录**（带指纹、可复现），
> 为了「失败时不留垃圾」而回滚它，代价是让「冻结」这个动作变成需要事务的写操作；
> 而回滚还会制造一个**更糟**的新失败模式：快照删了、任务却已经插进去了。

##### ★⚠ 入队成功后立刻释放 Redis 锁（第 151-163 行）

```java
lockService.release(organizationId, lock.token());
```

**这是 P3 最关键的一处变化。** 源码注释里的原话逐条复述：

P1/P2 时锁要**一直握到求解结束**（由 runner 的 `finally` 释放），因为那时
「谁在跑」**只有锁知道**。P3 有了任务队列之后：

| 互斥需求 | P3 由谁保证 |
|---|---|
| **跨实例**的重复提交 | `schedule_jobs` 的 `FOR UPDATE SKIP LOCKED`（领单本身就是一次原子写，见第 5 册） |
| **同校**互斥 | `scheduling_state` 的 CAS（上面第 ③ 关）+ 任务 `status` |

⇒ 而锁**如果继续握着，第一次排课之后这所学校就被永久锁死了**：
**worker 根本不知道锁的存在，没人会去释放它。**

> ⚠ **这个 bug 是实测跑第二遍 generate 时才暴露的：直接 409。**
> 第一遍永远正常（锁是刚抢的、状态是刚写的），只有第二遍才撞上。
> 源码注释里的原话是「（这个 bug 是实测跑第二遍 generate 时才暴露的：直接 409。）」。
> 这类「第二次才炸」的问题，正是 `tools/contract-diff` 与「走通全链路」要抓的东西。
> 完整的四处同类漏项见 [踩坑总结.md](踩坑总结.md) K1。

**所以锁缩回它真正的职责**：**保护「检查 + CAS + 入队」这个短临界区**，
防止两个并发请求**同时通过状态检查**。

> 反过来说也成立：正因为这个临界区被锁保护着，第 ②③ 关之间的窗口才足够小；
> 而**就算撞了**，第 ③ 关的 CAS 与随后的重读也能给出正确的 409 ——
> **两层保护互为补充，而不是重复**（这是三层并发控制的分工，见架构说明 §5.1）。

**返回**（第 165 行）

```java
return ScheduleStatusResponse.from(stateService.findByOrganization(organizationId));
```

⚠ **必须重新读库再返回**，不能用内存里拼的对象。等价于 Python 的
`state = db.query(...).first(); return _state_out(state)`。
对时间字段尤其重要：重读拿到的是 MySQL 里**截断到秒**的值；
这次恰好与内存里的 `now` 一致，但那是**依赖「写前截断」这个约定**（第 1 册 §1.1），
不是巧合可以依赖的 —— 而 `status/message` 也可能已被别人改过。

#### `private static String ownerOf(SchedulingState state)`

```java
return state == null || state.getOwnerName() == null ? "其他用户" : state.getOwnerName();
```

对应 Python 的 `f"{state.owner_name or '其他用户'}正在排课，请稍后再试"`。

⚠ 它同时兜了**两种空值**：**行不存在**（`state == null`）与
**行存在但姓名是空**（`owner_name varchar(100) DEFAULT NULL`）。
两种都回 `其他用户` —— 这就是 409 文案里那个「其他用户」的来源。

#### `private String newJobToken()`

```java
byte[] bytes = new byte[24];
random.nextBytes(bytes);
return HexFormat.of().formatHex(bytes);
```

| 细节 | 为什么 |
|---|---|
| `24` 字节 | 对应 Python 的 `secrets.token_hex(24)` —— **24 字节 = 48 个十六进制字符** |
| `HexFormat.of().formatHex(...)` | Java 17+ 的标准编码，输出**小写**（与 Python `token_hex` 一致） |
| 列宽 64 够放 | 48 < 64 ✓（`job_token varchar(64)`） |
| ⚠ 与 `ScheduleLockService.newToken()` **不是一回事** | 那个是 `token_urlsafe(24)`（Base64URL、32 字符）。**两个随机串编码不同是刻意的**：锁的 token 只进 Redis、不落库；job token 要落库、要跨进程传，十六进制更省心 |

#### 类级注解：`@Service` 与「刻意不加 `@Transactional`」

类注释最后一段写得很明确：

> ⚠ **刻意不加 `@Transactional`**：这几步是「每步提交」的（CAS 后提交、入队后提交）。
> 包成一个大事务会让「抢到状态」到「任务可被领走」之间的时间窗被拉长，
> 也让失败回滚的语义变得和 P1 不同。

把这段拆开：

| 如果加了大事务 | 会发生什么 |
|---|---|
| CAS 与入队同事务 | 「状态已 running」与「任务可被领走」之间要多等一次提交；worker 的 `SKIP LOCKED` 在提交前**看不到**这个任务 ⇒ 排队延迟被拉长 |
| 失败时回滚 | 第 ④ 关失败会**把 CAS 一起回滚** ⇒ 状态行回到进入本方法之前的值，**与 Python 行为不同**（Python 的 CAS 已经 `db.commit()` 过了，失败时用的是**新 session** 去标 failed） |
| 第 ④ 关的 `stateService.finish(...)` | 它本身就要求「独立于调用方事务」（P1 的 `_finish` 用独立 `SessionLocal()`）。挂在外层事务上，回滚时会跟着消失 —— 那正是「状态卡在 running」这个症状的另一种成因 |

⇒ 三条合起来说明：**「排课原子性不跨服务」不等于「整条链路一个事务」**。
铁律 1 要求的是「这几个 CAS 点都在同一个服务里、由同一个 `job_token` 串起来」，
**不是**「一个 ACID 大事务」。这是很容易误读的地方。

---

### 4a.3 `ScheduleStateService` —— 状态行的读 / 收尾 / 心跳（76 行）

**核心主张**：所有写操作都是「**条件 UPDATE**」—— 判据放在 `WHERE` 里，
拿**受影响行数**当结果。于是「检查」和「写」是一条语句，中间**没有窗口**。

**谁调它**：`ScheduleRunService`（②③④ 关）、`ScheduleController.status`、
以及**任务路径**上的 `JobQueueService`（`fail` / `cancel`）与 `JobResultWriter.settle`。
**它调谁**：只有 `SchedulingStateMapper`。

#### `SchedulingState findByOrganization(long organizationId)`

| 它做的事 | `selectOne(org = ?)` |
|---|---|
| **谁调它** | `ScheduleController.status`、`ScheduleRunService.trigger`（用了 3 次） |
| **返回值** | 没有行 → **`null`**（由调用方决定怎么呈现；Controller 转成默认 idle） |

#### `int finish(long organizationId, String jobToken, String status, String message)`

对应 Python 的 `_finish`。SQL 形状（写在本类 javadoc 里）：

```sql
UPDATE scheduling_state SET status=?, message=?, finished_at=?
 WHERE organization_id=? AND job_token=? AND status='running'
```

| 逐段 | 在挡什么 |
|---|---|
| `job_token = ?` | ⚠ **fencing token**：一个「被判定超时」的旧任务即使活过来继续写，token 已经对不上 ⇒ **影响不到新任务**（铁律 1 的核心手法） |
| `status = 'running'` | 只允许从 `running` 收尾；已经是终态的行**不许被覆盖** |
| `finished_at = UtcTimes.now()` | 终态时间。同样是**截断到秒的 UTC** |
| **返回受影响行数** | `0` = 「这条状态已经被别人改过」（token 或 status 不匹配）。javadoc 里明确写了这个语义 |

> ⚠ **它刻意不开事务**：Python 用的是**新建的 `SessionLocal()`**，
> 所以即使求解过程的事务回滚了，状态更新依然落库。
> 这里不开事务（每条语句自动提交），效果相同。

#### `int finishByOrganization(long organizationId, String status, String message)`

**P3 的任务路径专用**。与 `finish` 的**唯一差别是去掉了 `job_token` 条件**。
类 javadoc 给了两条理由，都是关键：

1. **token 条件在这里已经无法满足了**：`trigger` 写进 `scheduling_state.job_token`
   的是**入队时**的 token，而**每次领单都会换新 token**（那是任务行栅栏的设计）——
   两者**必然不同**。带着旧 token 的 `finish` 会更新 **0 行**，
   等于**状态永远收不了尾**。
2. **防「旧任务覆盖新任务」的责任已经转移了**：P3 之后
   [`JobResultWriter.persistIfStillOwner`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/job/JobResultWriter.java)
   在落库前会 `SELECT ... FOR UPDATE WHERE job_token=? AND status='running'`，
   拿不到就**直接丢弃结果**、根本走不到这里。

所以判据改成「**这条状态现在是不是 running**」，安全性由**上游的 token 校验**保证。

##### ⚠⚠ 为什么每条终态路径都必须调它

**漏掉的症状不是崩溃，而是「这所学校再也发不起新排课」**：
任务已经 `failed`，而 `scheduling_state` 仍是 `running` ⇒ 之后每次 generate
都被 `trigger` 第 ② 关的 409 挡住（文案是「某某正在排课，请稍后再试」）。

**四条路径逐一核对**（本册实测 grep 的结果，调用点都在**别的类**里）：

| # | 路径 | 调用点 | 传的终态 |
|---|---|---|---|
| 1 | **完成** | `JobResultWriter.settle`（第 152 行） | `"done"`，文案 `排课完成，已生成草稿 V{n}，共 {N} 节课` |
| 2 | **失败（不再重试）** | `JobQueueService.fail`（第 246 行） | `"failed"`，文案 `排课无解，请检查任教关系、周课时、固定时段和教师课时上限` |
| 3 | **取消（还没被领走）** | `JobQueueService.cancel`（第 362 行） | `"idle"`，文案 `排课任务已取消` |
| 4 | **取消（worker 察觉后主动交还）** | `InternalJobController`（第 125 行） | `"idle"`，文案 `排课任务已取消` |

⚠ 每一处都带注释说明「漏了会怎样」，措辞不同但讲的是同一件事：

- `JobQueueService.fail` 里：「最初漏了这一步，症状是：任务已经 failed，而
  `scheduling_state` 仍是 `running`，于是**这所学校再也发不起新的排课**……
  与『Redis 锁没人释放』是同一类事故。」
- `JobQueueService.cancel` 里：「取消一个还没开始的任务同样是『终态』，必须收尾
  对外状态 —— 否则这所学校会一直显示『正在排课』，发不起新任务。
  （与失败路径是同一类问题，只是更容易被忽略：**取消看起来『什么都没发生』**。）」

> ⚠ **P3 实测漏了 4 处**（其中 3 处属于这一类，第 4 处是「接管时没释放死掉 worker 的槽位」）。
> 完整清单见 [踩坑总结.md](踩坑总结.md) K1 与
> [智能教务中台-项目方案.md](智能教务中台-项目方案.md) §15。
> **教训**：迁移一段「清理逻辑」时要**先列出它原本做的所有事**再逐项确认，
> 因为这类漏项的后果不是崩溃，而是「若干次操作之后系统悄悄不能用」。

> ⚠ **「重试退回队列」的情况刻意不动状态**：`JobQueueService.fail` 里
> `if (!requeued) { finishByOrganization(...) }` —— 因为那次任务**还在进行中**
> （只是换个人重做），把状态收尾成 failed 会让前端以为结束了。

#### `int heartbeat(long organizationId, String jobToken)`

```sql
UPDATE scheduling_state SET started_at = ?
 WHERE organization_id=? AND job_token=? AND status='running'
```

把 `started_at` 往前推，表示「我还活着」。条件同样是 **org + job_token + status='running'** 三件套
—— **返回 0 就是已被接管，心跳线程应停止**（javadoc 明确写了这个约定）。

> ⚠ **为什么复用 `started_at` 当心跳时间**（javadoc 原文）：
> 因为**超时判定与「正在排课」的展示都看这一个字段**，
> 另加一个 `heartbeat_at` 列会引入「两个时间哪个为准」的问题。
> Python 就是复用一个字段的 —— 这是行为对齐，不是偷懒。

> ⚠ **本方法当前在 scheduling 侧没有调用者**：实测 grep 显示只有
> `orbit-solver-worker` 的 `SchedulingClient.heartbeat(jobId, jobToken, progress)`
> —— 那个打的是**任务行**的 `/internal/jobs/{id}/heartbeat`（续的是
> `schedule_jobs.lease_until`，见第 5 册），**不是**这个方法。
> 也就是说：P3 之后**任务行的租约**才是权威的存活判据，
> 状态行的 `started_at` 只影响第 ② 关那句 409 的判断。
> 本方法保留着，语义与注释都仍然正确，但链路上已经没有它的位置了。

#### ⚠ 本类的一个隐藏前提（代码行为照实记录，未完全验证）

`finish*` 与 `heartbeat` 全部是 **UPDATE**。如果 `scheduling_state` **没有这一行**，
它们会静默更新 **0 行**：对外表现是「排课跑完了，但 `/status` 一直显示 `尚未开始排课`」。

本册在 `orbit-scheduling` 与 `orbit-academic` 两个模块里**都没有找到**插入
`scheduling_state` 行的代码（academic 的 `OrganizationService` 是**刻意不插**的 —— 铁律 2），
所以这一行的初始化来源只可能是**既有的历史数据 / 数据迁移脚本**
（Python 侧是 `POST /api/organizations` 与 `main.py` 的启动补插）。

- **代码行为是**：缺行时 `finish*` / `heartbeat` 静默无效；`/status` 返回默认 idle；
  `trigger` 的 CAS 会更新 0 行 → 走到第 ③ 关的 409 分支（文案 `其他用户正在排课，请稍后再试`）。
- **⚠ 这与 Python 一致**：Python 的 `.update()` 在缺行时同样返回 0、同样回 409 ——
  所以它**不是迁移引入的偏差**，而是「懒初始化只做了一半」的遗留：
  **读路径容忍缺行、写路径不容忍**。改之前请先确认数据里确实都有这一行。

---

### 4a.4 `SchedulingState` —— 每组织一行的状态机（102 行）

**它为什么是整套并发控制的地基**：表上有唯一约束
`uq_scheduling_state_org (organization_id)`。**一个组织只能有一行** ⇒
这一行就是「这所学校当前那次排课」的唯一真相，抢锁、CAS、租约全部围绕它展开。

#### 字段逐个

| 字段 | 列 / 类型 | 含义 | 谁写它 | 谁读它 |
|---|---|---|---|---|
| `id` | `id int AUTO_INCREMENT` | 主键 | — | — |
| `organizationId` | `organization_id int NOT NULL` | 哪个学校（**唯一约束在这一列**） | — | 所有查询的键 |
| `status` | `status varchar(20) NOT NULL` | `idle` / `running` / `done` / `failed` | `trigger` 的 CAS、`finish`、`finishByOrganization` | 第 ② 关、`/status` |
| `message` | `message varchar(500) NOT NULL` | **面向用户的一句话**（前端直接显示） | 同上 | `/status` |
| `ownerUserId` | `owner_user_id int NULL` | 谁点的排课（外键 → `users.id`） | `trigger` | 审计（本类不读） |
| `ownerName` | `owner_name varchar(100) NULL` | 上一条的**姓名快照**（用于「张三正在排课」） | `trigger` | `ownerOf(...)` |
| `startedAt` | `started_at datetime NULL` | ⚠ **双职责**：开始时间 + **心跳时间** | `trigger`、`heartbeat` | 第 ② 关的租约判定 |
| `finishedAt` | `finished_at datetime NULL` | 终态时间 | `finish*` | `/status` |
| `jobToken` | `job_token varchar(64) NULL` | **fencing token**（值只有 48 字符） | `trigger`、`finish` | `finish` / `heartbeat` 的 `WHERE` |

⚠ **`jobToken` 列宽 64、值只有 48 字符** —— 这是刻意的余量。
值来自 `newJobToken()`（`secrets.token_hex(24)` 的等价物），
**不是 UUID**（36 字符 + 连字符），也**不是 `token_urlsafe`**（那是 Redis 锁用的那个）。

#### 为什么是普通类 + getter/setter，不是 record

MyBatis-Plus 的实体需要**无参构造 + 可变字段**（它靠 setter 反射填充结果集），
record 不满足。这也是全项目 `*Entity` / `*Ref` 的统一样子。

> ⚠ 对比：`ScheduleStatusResponse` **是** record —— 因为它是**出 HTTP 的 JSON 形状**，
> 与 Entity **刻意分开**（第 1 册 §0.1 的约定）。两者的字段名几乎一样，
> 但一个是表、一个是契约，改一个不会自动影响另一个。

---

### 4a.5 `SchedulingStateMapper` —— 自定 SQL 只有一条（33 行）

```java
@Mapper
public interface SchedulingStateMapper extends BaseMapper<SchedulingState> {
    @Select("""
            SELECT * FROM scheduling_state
             WHERE organization_id = #{organizationId}
               AND job_token = #{jobToken}
               AND status = 'running'
             FOR UPDATE
            """)
    SchedulingState lockForRunning(@Param("organizationId") long organizationId,
                                   @Param("jobToken") String jobToken);
}
```

`extends BaseMapper<SchedulingState>` 带来了全部 CRUD；
本类**只额外加了一条**自定 SQL —— 而 `ScheduleStateService` 与 `ScheduleRunService`
用的全是 `Wrappers.lambdaQuery/lambdaUpdate` 拼出来的通用方法。
所以这个接口在架构上很轻，**唯一的重量级内容就是 `FOR UPDATE`**。

#### `SchedulingState lockForRunning(long organizationId, String jobToken)`

| 逐段 | 在挡什么 |
|---|---|
| `job_token = ? AND status='running'` | 「这单**现在**还是我的吗」 |
| `FOR UPDATE` | ⚠ **求解与落库之间的闸门**：求解可能跑几十秒，期间租约可能超时、任务可能已被接管。**不重新确认的话，一个已作废的求解结果会覆盖新任务的成果** |
| 返回 `null` | 意思是「已经不是我的任务了」⇒ 调用方应**丢弃结果**（**不是报错** —— 架构说明 §4.4 设计要点 3：「被接管不是错误，静默丢弃即可，新任务会自己收尾」） |

对应 Python（javadoc 里逐行抄了原文）：

```python
state = db.query(SchedulingState).filter(
    org == ..., job_token == job_token, status == "running",
).with_for_update().first()
if state is None or slot is None:
    db.rollback(); return        # 被接管了 —— 结果直接丢弃
```

> ⚠ **它是 `@Select` 是对的**（这本来就是查询）。
> 全项目的约定是「写操作必须用 `@Update`/`@Insert`/`@Delete`」——
> 因为用 `@Select` 标 UPDATE 时 MyBatis 会**当查询执行**、拿不到 rowcount，
> 撞上 `int` 返回类型就抛
> `BindingException: attempted to return null from a method with a primitive return type (int)`。
> **编译期完全看不出来**，只有真点到「排课」才炸（[踩坑总结.md](踩坑总结.md) B7）。
> 这个方法返回的是**实体**，所以没有那个问题。

> ⚠ **本方法当前没有调用者（本册实测 grep 的结论）**。
> 全项目里只有两处**注释**提到它：
> `ScheduleWorkerSlotMapper`（「与 `SchedulingStateMapper.lockForRunning` 配对使用」）
> 与 `ScheduleJobMapper.lockForRunning`（「这一步与 P1 的
> `SchedulingStateMapper.lockForRunning` 是同一个套路」）——
> 而**真正被调用的**是 `ScheduleJobMapper.lockForRunning`（`JobResultWriter` 里）。
> 也就是说：P3 把这把「闸门」从**组织级状态行**搬到了**任务行**，
> 这个方法是 P1 路径的**遗留**（保留着、语义仍然正确，但链路上已无位置）。
> ⚠ 那两处注释把它当成**仍然在用的东西**来举例，读到这里不要被误导。

---

### 4a.6 `ScheduleStatusResponse` —— 对外状态体（34 行）

```java
public record ScheduleStatusResponse(String status, String message,
                                     List<ScheduleResponse> schedules,
                                     String ownerName,
                                     LocalDateTime startedAt, LocalDateTime finishedAt,
                                     Long versionId)
```

**实测的 Python 响应形状**（写在类 javadoc 里，是拿 Python 跑出来抄的）：

```json
{"status":"done","message":"排课完成，已生成草稿 V2，共 92 节课",
 "schedules":null,"owner_name":"系统管理员",
 "started_at":"2026-09-23T00:58:13","finished_at":"2026-09-23T00:58:13","version_id":null}
```

#### 逐方法

| 方法 | 做什么 | 注意 |
|---|---|---|
| `static from(SchedulingState state)` | 把实体转成响应体 | ⚠ **`schedules` 与 `versionId` 硬编码传 `null`** |
| `static idleDefault()` | 「还没有状态行」时的默认值 | 返回 `("idle", "尚未开始排课", null, null, null, null, null)` |

#### ⚠ 为什么 `schedules` / `versionId` 恒为 `null`

Python 的 `_state_out(state)` **只填 5 个字段**，另外 2 个用 Pydantic 的默认值原样输出。
**保留它们是为了逐字对齐契约**（前端的 TypeScript 类型定义里也有这两个字段）。

它们看起来像「没写完」，其实是「**故意不动**」—— 这正是第 1 册 §0.3 说的那类代价：
一旦删掉这两个字段，契约测试就会红，然后要为这个差异写例外。

> ⚠ `schedules` 的类型是 `List<ScheduleResponse>`（来自 `query/ScheduleResponse`），
> 而不是某个 `*Entity`。这是「响应体与实体刻意分开」的又一处落地。

#### ⚠ `idleDefault()` 是与 Python 的**有意偏离**

Python 的 `_state_out(None)` 会 `AttributeError → 500`（它假设行一定存在，
因为 Python 的 `POST /api/organizations` 会顺手插一行 `scheduling_state`）。
P1 把初始化搬到了属主这边（铁律 2，见架构说明 §3.2 的 ⚠），
**academic 不再碰 `scheduling_state`**，所以这里必须容忍「还没有行」：
返回等价的默认 `idle`，而不是空指针 500。
**对外可见行为与 Python 一致**（Python 那边返回的也是那行 idle 状态）。

> ⚠ 与 4a.3 末尾那条 ⚠ 合起来看才是完整的：
> **读路径容忍缺行（这里），写路径不容忍（`finish*` 更新 0 行）**。
> 这是「懒初始化只做了一半」的遗留，已在前面的 ⚠ 里说明。

---

### 4a.7 `ScheduleEntity` —— `schedules` 表（86 行）

**一句话定位**：**当前已发布课表**的投影 —— 「最新发布的那一版」的拷贝，
供前端默认查看用。

**模式与 `SchedulingState` 完全一样**：`@TableName("schedules")` + `@TableId(type = IdType.AUTO)`
+ 7 个可变字段 + 全套 getter/setter。

#### 字段逐个

| 字段 | 列 | 含义 |
|---|---|---|
| `id` | `id int AUTO_INCREMENT` | 主键 |
| `organizationId` | `organization_id` | 哪个学校（唯一约束的第一列） |
| `classId` | `class_id` | 班级 |
| `subjectId` | `subject_id` | 科目 |
| `teacherId` | `teacher_id` | 教师 |
| `dayOfWeek` | `day_of_week` | 星期几 |
| `periodIndex` | `period_index` | 第几节 |

⚠ **`dayOfWeek` / `periodIndex` 用包装类型 `Integer` 而不是 `int`** ——
与 `SchedulingState` 的字段风格一致（MyBatis-Plus + setter 反射下包装类型更安全：
可以用 `null` 表示「未填」，也避免 primitive 默认值 `0` 冒充合法值）。

#### 为什么表名 `schedules` 与类名 `ScheduleEntity` 不一致

javadoc 写了理由：`Schedule` 这个词在本模块里**已经用来表示「排课」这件事**
（包名、服务名、`ScheduleController`、`ScheduleRunService`），
用 `ScheduleEntity` 避免读代码时混淆「**一条课表记录**」与「**排课这个动作**」。

#### 它与 `version/ScheduleVersionEntry` 的分工（最容易搞混的一对）

| 表 | 是什么 | 可变吗 | 谁写 |
|---|---|---|---|
| `schedule_version_entries` | **每版**课表的明细（`version_id` + 班级 + 时段） | **不可变**（历史） | `JobResultWriter`、`VersionService`、换课 |
| `schedules` | 「**最新发布的那一版**」的拷贝 | 发布时**先清空再整体重写** | `VersionService.publish` |

**表上的约束**（[V1__baseline_legacy.sql](../backend-java/orbit-scheduling/src/main/resources/db/migration/scheduling/V1__baseline_legacy.sql)）：

```sql
UNIQUE KEY `uq_schedule_org` (`organization_id`,`class_id`,`day_of_week`,`period_index`)
```

—— 一个班的同一时段只能有一节课。
⚠ 这条唯一约束是「**先清空再重写**」这个做法的直接原因：不先清空，重写会遇到唯一键冲突。

**谁读它**（本册实测 grep 的 4 个调用方）：

| 调用方 | 场景 |
|---|---|
| `query/ScheduleQueryService` | 课表查看。⚠ **带 `version_id` 时读的是 `schedule_version_entries`，不带时读的才是本表** —— 同一个接口在「看已发布课表」和「看某版草稿」时读不同的表 |
| `query/ExportService` | 导出 xlsx |
| `system/SystemService` | 运维：`scheduleMapper.delete(...)` 清库 |
| `version/VersionService` | 发布时先清空本表再整体重写 |

#### `ScheduleEntityMapper`（9 行）

```java
@Mapper
public interface ScheduleEntityMapper extends BaseMapper<ScheduleEntity> {
}
```

**完全是空的**：所有查询都由 MyBatis-Plus 的通用方法或 `LambdaQueryWrapper` 完成
（见上面 4 个调用方），不需要任何自定 SQL。
⚠ 对比 `SchedulingStateMapper`（有一条 `FOR UPDATE`）—— **这个差别本身就是信息**：
`FOR UPDATE` 只出现在「需要把判断与写绑成一条语句」的地方。

> ⚠ 与 `SchedulingStateMapper` 一样，注释里**没有**解释为什么是空的 ——
> 这不是遗漏：空 Mapper 是这个项目里最常见的一类文件（`@Mapper` + `extends BaseMapper`），
> 不需要为它写理由。

---

### 4a.8 `TimetableGenerationService` —— ⚠ 已不在 generate 链路上（34 行）

```java
@Component
public class TimetableGenerationService {

    public SolveOutcome solve(ScheduleInput input, List<ConstraintRule> rules,
                              int timeLimitSeconds, int numSearchWorkers) {
        SolverSettings settings = new SolverSettings(timeLimitSeconds, numSearchWorkers, false);
        return OrbitSolver.solve(input, rules, settings);
    }
}
```

**整个类只有一个方法，方法体只有两行。** 但它的**位置**比它的内容重要得多，
所以这段要讲的是「它为什么还在、以及谁还在用它」。

#### 它是一个纯粹的「参数搬运 + 转发」

| 代码 | 做什么 |
|---|---|
| `new SolverSettings(timeLimitSeconds, numSearchWorkers, false)` | 把两个 int 打包成 solver-core 的设置对象。⚠ 第三个参数源码里**没有说明是什么**（类 javadoc 与构造点都没写）—— **（此处未完全理解，代码行为是：把第三个参数写死为 `false`）** |
| `OrbitSolver.solve(input, rules, settings)` | solver-core 的**对外唯一入口**。⚠ `OrbitSolver` 是**静态方法**调用（不是注入的 Bean）—— 因为 solver-core 是**纯库**，不依赖 Spring |
| 返回值 `SolveOutcome` | 带 `status`（能区分「超时 UNKNOWN」与「真的无解 INFEASIBLE」） |

#### 规则从哪来：**它不决定**（javadoc 的核心主张）

> ⚠ 因此这个类现在**不决定用哪些规则**，只负责编译与求解 —— 规则集是**参数**。
> 这样「取规则」只有一个地方做，也就只有一个地方会取错。

这是刻意的职责切分：

| 谁 | 负责 |
|---|---|
| `RuleSetService.loadForSolve` / `rulesByHash` | **取**规则（从 `constraint_rules` 或冻结的 `constraint_rule_versions`） |
| `TimetableGenerationService` | **用**规则（交给 `OrbitSolver`） |

P2 之前这里的规则是**写死的** `RuleSets.legacyEquivalent()`（硬编码预设，
唯一目的是复现 Python 行为）。P2 之后改由数据库提供，于是「加一条约束」
从「改代码重新部署」变成「在页面上加一行」。

javadoc 里还有一句值得单独记下来：

> ⚠ 它仍然是……，只是内容变了：用**规则系统**编译（`compile.RuleCompiler`），
> 而不是 `legacy.FaithfulModelBuilder` —— **后者是等价性测试的基线，拿它当生产路径等于把死代码上线**。

#### ⚠⚠ 它现在到底做什么：P3 之后只剩**诊断**一条路

这是本册最容易读错的一处。P1 时它是 generate 的**进程内求解入口**，
由 `ScheduleRunner`（**这个类在 P3 已被删除**）在后台线程里调用。

**P3 之后**：
- 真正的排课求解跑在**独立进程** `orbit-solver-worker`（:8084），
  worker 通过 `/internal/jobs/claim` 拿到 `JobClaimResponse`（内含**冻结的输入快照 + 当时那套规则**），
  在进程内调 `OrbitSolver` —— **它根本不经过本类**。
- scheduling 侧的 `schedule/` 包**没有任何代码**调用本类。

**那谁还在调它？**（本册实测 grep 的结果 —— **只有一处**）

| 调用方 | 场景 | 怎么调 |
|---|---|---|
| [`DiagnosisService`](../backend-java/orbit-scheduling/src/main/java/com/orbit/scheduling/diagnosis/DiagnosisService.java)（第 271 行） | 不可行性诊断的**最小冲突集**：逐条去掉硬规则再求解，「去掉哪条之后能排出来，那条就在冲突集里」 | `generationService.solve(input, without, deepTimeLimitSeconds, 1)` ⚠ **`workers = 1`**（诊断只跑 8 秒，不值得并发） |

> ⚠ **有一个容易搞错的点**：`rule/RuleTrialService`（规则试算）
> **不调用本类** —— 它只调 `OrbitSolver.compile(input, rules)`（实测 grep：
> 只有 `OrbitSolver.compile`，没有 `solve`）。因为试算只需要**比较编译出的模型**
> （变量数、约束数、指纹），**不需要真的求解** —— 那正是它快的原因。
> 所以「本类的唯一调用方是 `DiagnosisService`」这句话是准确的。

> ⚠ **javadoc 里的 `{@link ScheduleRunner}` 是一个悬空引用**（那个类已被 P3 删除）。
> 这是**注释滞后于代码**，不是编译错误（javadoc 的 `@link` 指向不存在的类在默认构建里不报错），
> 也没有对应的 import。读到这里不要去找那个类。
> 同一个类里还有一处：`所以这个类现在不决定用哪些规则……由 {@link ScheduleRunner} 取` ——
> **规则现在由 `JobQueueService.claim` 取**（发给 worker），不是 `ScheduleRunner`。

#### 参数含义

| 参数 | 来源（调用方传的） | 对应 Python |
|---|---|---|
| `timeLimitSeconds` | `DiagnosisService` 传的是 `deepTimeLimitSeconds`（诊断的时间上限，**不是**排课的 60 秒） | `time_limit=60`（排课） |
| `numSearchWorkers` | `DiagnosisService` 传 `1` | `SCHEDULE_SOLVER_WORKERS` |

> ⚠ 也就是说：**本类本身没有任何配置项**，配置在调用方手里。
> 这再次印证了它的定位 —— 它不是「排课服务」，只是一层把参数转给纯库的适配器。

---

## 本册小结

### 一句话记住这 9 个类

| 类 | 一句话 |
|---|---|
| `ScheduleController` | 门面：`generate` 不看 body，`status` 容忍缺行 |
| `ScheduleRunService` | ★ 四道关卡，**顺序不能变**；锁在入队后立刻放 |
| `ScheduleStateService` | 三个条件 UPDATE；`finishByOrganization` 是**四条终态路径的共同出口** |
| `SchedulingState` | 每组织一行的状态机，**唯一约束是整套并发控制的地基** |
| `SchedulingStateMapper` | 唯一自定 SQL 是 `FOR UPDATE` 闸门（⚠ 现已被 `ScheduleJobMapper` 的同名方法取代） |
| `ScheduleStatusResponse` | 7 个字段，其中 2 个**恒为 null**（对齐 Python 的 Pydantic 默认值） |
| `ScheduleEntity` | `schedules` = 最新**已发布**那一版的拷贝，发布时先清空再重写 |
| `ScheduleEntityMapper` | 空 Mapper —— 这个「空」本身说明这里不需要条件 UPDATE |
| `TimetableGenerationService` | 已不在 generate 链路上，只剩 `DiagnosisService` 在用 |

### 三条最值得记住的结论

**① 每一道关卡都在挡一种**不同的**重复**：

| 关 | 挡的是 |
|---|---|
| Redis 锁 | **跨实例**的重复提交（多副本部署） |
| 状态检查（第 ② 关） | 同校**未过租约**的 running —— 而且它负责**说出是谁** |
| CAS（第 ③ 关） | ②③ 之间的几毫秒竞态 + **接管过期任务**（三个 `OR` 各管一段） |
| 快照 + 入队（第 ④ 关） | 「排队期间数据被改」—— 铁律 3 |

**② 这一类 bug 的症状全都是「这所学校再也发不起新排课」，不是崩溃**：

- Redis 锁没在入队后释放 ⇒ 第一次排课之后永久锁死（**实测过**：第二遍 generate 直接 409）
- 失败 / 取消 / 完成任一条路径漏掉 `finishByOrganization` ⇒ 状态卡在 `running`
- 第 ③ 关 CAS 少了 `started_at < staleBefore` ⇒ 崩溃过的任务再也不能被接管

三者可以互相掩盖（都是同一句 409 文案），所以**排查时要从 `scheduling_state`
那一行的 `status` / `started_at` / `job_token` 三个字段开始看**。

**③ 「排课原子性不跨服务」不是「一个大事务」**：
`ScheduleRunService` **刻意不加 `@Transactional`**；`ScheduleStateService.finish*`
**刻意独立于调用方事务**。铁律 1 要的是「这几个 CAS 点都在同一个服务里、
由同一个 `job_token` 串起来」，而不是 ACID 大事务。

### 与下一篇的分工

| 主题 | 在哪 |
|---|---|
| worker 怎么领单、`SKIP LOCKED`、租约接管、`job_token` 换发 | 第 5 册（`job` 包） |
| 快照怎么冻结、7 张表怎么读 | 第 4b 册（`snapshot` 包） |
| 课表版本 / 换课 / 发布 / 导出 | 第 4b 册 |
| 规则集哈希与版本 | 第 5 册（`rule` 包） |
