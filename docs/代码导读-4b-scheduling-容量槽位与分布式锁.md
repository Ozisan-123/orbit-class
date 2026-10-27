# 代码导读 · 4b. 容量槽位与分布式锁

> **本册范围**：`orbit-scheduling` 的 `capacity/`（3 个类）+ `lock/`（1 个类），共 **4 个类**。
> 上游：[代码导读.md](代码导读.md)（第 1 册，契约层）· [代码导读-4a](代码导读-4a-scheduling-排课编排.md)（排课编排）
>
> ⚠ **本册是补写的**：这一块原来在旧版 `4a`（2115 行 / 37 个类）里，
> 那次覆盖式重写把它弄丢了，而 `snapshot/` 的 9 个类也一并丢了。
> 本册只覆盖 `capacity` + `lock`；`snapshot/` 见下一册。

---

## 本模块在系统里的位置

排课是**长时间运行的写操作**，所以"同时能有几个任务在跑"必须被管住 —— 否则 8 所学校同时点"排课"就能把 CPU 打满。

这一册的两个类是那套管理手段里**最底层的两个**：

- **`capacity/`（容量槽位）** = **全局并发度**。数据库里固定几行（默认 2 行），抢到一行才能求解。
  它不依赖 Redis、不依赖进程内状态 —— **是整套并发控制里最可靠的一道**。
- **`lock/`（Redis 分布式锁）** = **跨实例的互斥**。同一所学校同时只能有一个排课任务在跑。

> ⚠ 这两个东西长得很像（都是"抢一个名额"），但**管的事情完全不同**：
> 槽位管的是「**整个系统**能同时跑几个」，锁管的是「**某一所学校**能不能开始」。
> 混起来理解会得出完全错误的结论。

## 阅读顺序建议

1. **先看 §4b.0 的四层全景** —— 不先建立这个框架，后面四个类会越看越乱。
2. 再看 **`ScheduleWorkerSlotMapper`** —— 所有原子操作都在它的 SQL 里，`CapacityService` 只是它的包装。
3. 最后看 **`ScheduleLockService`** —— 它是独立的一套（Redis），和数据库那套没有耦合。

---

## 4b.0 先看全景：四层并发控制，各管一件事

这是全项目最容易看糊涂的地方。**四层不是冗余，是各管一件事**：

| 层 | 实现 | 管什么 | 失败时（挡不住）会怎样 |
|---|---|---|---|
| **① Redis 锁** | `ScheduleLockService` | **同一所学校**同时只能有一个排课任务 | 同一校同时跑两个求解，写出版本互相覆盖 |
| **② 容量槽位** | `CapacityService` | **整个系统**同时最多 2 个（`MAX_CONCURRENT_SCHEDULES`） | CPU 被打满，所有接口变慢 |
| **③ 状态 CAS** | `ScheduleStateService` | 状态行的**原子占位**，并顺带"接管过期任务" | 重复触发同一个任务 |
| **④ `job_token` 栅栏** | `ScheduleJobMapper` / `JobResultWriter` | **具体某个任务**的结果只被接受一次 | 被接管后醒来的旧 worker 覆盖新任务成果 |

**它们的可靠性是递减的**：

```
Redis 锁      ← 最不可靠：Redis 挂了就降级放行（有意为之，见 §4b.4）
容量槽位      ← 最可靠：纯数据库，不依赖任何外部组件
状态 CAS      ← 可靠：数据库
job_token     ← 可靠：数据库，而且是"最后一道"
```

**为什么允许 Redis 锁降级**：因为**它挡不住的东西，后面三层能挡住** ✓
Redis 挂了 → ①放行 → 但 ②容量槽位（数据库）照样限全局并发 ✓ ③CAS 照样保证同校不重复 ✓
**这就是"降级"敢写的原因** —— 不是"无所谓"，是"有兜底" ✓

> ⚠ **但 ② 有一个前提**：槽位必须在**领单时**抢（P3 之后是这样），
> 而不是"入队时"抢。入队时抢会让排队的任务**空占产能** —— 见 §4b.3 的说明。

---

## 4b.1 `ScheduleWorkerSlot` —— 表 `schedule_worker_slots` 的一行

```java
@TableName("schedule_worker_slots")
public class ScheduleWorkerSlot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long organizationId;
    private String jobToken;
    private LocalDateTime leaseUntil;
    // ... 4 对 getter/setter
}
```

**一行 = 一个可用的求解名额**，共 3 个业务字段：

| 字段 | 含义 | 为空表示 |
|---|---|---|
| `organization_id` | 谁占着（只用于排查和统计） | 槽位空闲 |
| `job_token` | **占用凭证** —— 所有续租/释放都要带它做条件 | 槽位空闲 |
| `lease_until` | 租约到期时刻 | 槽位空闲 |

**只有 3 个字段，但它们是"谁占着"这件事的最小表达** ✓
`organization_id` 其实**不参与任何判断** ✗（判断只看 `job_token` 和 `lease_until` ✓）—— 它存在的意义是**人排查时能一眼看出"这个槽位被哪个学校占了"** ✓

### ⚠ `IdType.AUTO` 与"预置 id"的矛盾（其实不矛盾）

表里的行是**预置**的，id 就是 `1..maxConcurrent` ✓，而 `@TableId(type = IdType.AUTO)` 声明的是"主键由数据库自增" ✗

两者**看起来矛盾，实际不冲突** ✓：
- 预置走的是 `ScheduleWorkerSlotMapper.insertIgnore(slotId)` —— **自定义 `@Insert` SQL，显式给了 id** ✓ MyBatis-Plus 的主键策略**不参与** ✓
- `IdType.AUTO` 只在用 MyBatis-Plus 的 `insert(entity)` 时才生效 ✓ 而这里从来不用 ✓

> 换句话说：`AUTO` 是个**从未被使用的声明** ✓ 换成 `IdType.INPUT` 语义更准 ✓ 但改它没有任何行为收益 ✓ —— 记着就行，**别以为它会跟预置 id 打架** ✓

---

## 4b.2 `ScheduleWorkerSlotMapper` —— 6 个原子操作（其中 2 个是死代码）

**这个类是整个容量机制的核心** ✓ —— `CapacityService` 只是它的薄包装 ✓

### 🔴 类注释里的那条铁律：写操作必须用对注解

```java
@Update(...)   // ✅ 能拿到 rowcount
@Select(...)   // ❌ MyBatis 当查询执行，rowcount 拿不到
```

用 `@Select` 标 UPDATE 时：MyBatis 拿不到"受影响行数"→ 方法返回 `null` → 撞上 `int` 返回类型就抛：

```
BindingException: Mapper method 'tryAcquire attempted to return null
                  from a method with a primitive return type (int)
```

⚠ **构建期完全看不出来** ✗ 只有真的点到"排课"才炸 ✓ 实测踩过一次（踩坑总结 B7）✓

### 逐方法

#### ① `tryAcquire(slotId, orgId, jobToken, leaseUntil, now)` —— 抢

```sql
UPDATE schedule_worker_slots
   SET organization_id = #{organizationId},
       job_token = #{jobToken},
       lease_until = #{leaseUntil}
 WHERE id = #{slotId}
   AND (job_token IS NULL OR lease_until IS NULL OR lease_until < #{now})
```

**一条语句同时完成「判断是否可用」与「占用」** ✓ 逐句讲那个 `WHERE`：

| 子句 | 含义 |
|---|---|
| `job_token IS NULL` | 槽位**从来没被占过**（预置出来的空行） |
| `lease_until IS NULL` | 被占过但租约字段是空（理论上不该出现，兜底） |
| `lease_until < #{now}` | **租约已过期** → 上一个持有者崩了，槽位可以回收 |

**返回受影响行数**：`1` = 抢到了 ✓ `0` = 被别人占着（或没这行）✓

**为什么这样写就原子了**：两个请求同时打过来，InnoDB 的行锁会让它们**串行** ✓
先到的把 `job_token` 从 `NULL` 改掉 ✓ 后到的再执行时 `WHERE` 已经不成立 ✓ → `rowcount=0` ✓
**一个能拿到 1，另一个必然拿到 0** ✓ 不需要额外的查询、不需要乐观锁重试 ✓

> 这正是方案选 **MyBatis 而不是 JPA** 的原因 ✓ —— 条件 UPDATE 的 `rowcount` 就是"我抢到了吗"的答案 ✓
> JPA 的 `@Modifying` 查询默认返回 `void`，要拿行数得额外声明，而且很容易被"脏检查 + 自动 flush"打乱时序 ✓

**`#{now}` 是参数传进来的，不是 SQL 里的 `NOW()`** ✓ —— 这是刻意的 ✓ 因为 `UtcTimes.now()` 截断到秒 ✓ 而 MySQL 的 `NOW()` 依赖数据库时区 ✓ 两边可能差几秒 ✓ 传参保证"判断过期"和"写租约"用的是**同一个时间基准** ✓

> ⚠ **一处小瑕疵**：这条 SQL 外面包了 `<script>...</script>` ✗ 但里面**没有任何动态标签**（没有 `<if>` / `<foreach>`）✓
> `<script>` 只在写动态 SQL 时才需要 ✓ 这里**是多余的** ✓ 无害，但读的时候会以为"这里有条件拼接"而多找一圈 ✓

#### ② `renew(slotId, jobToken, leaseUntil)` —— 续租

```sql
UPDATE schedule_worker_slots
   SET lease_until = #{leaseUntil}
 WHERE id = #{slotId} AND job_token = #{jobToken}
```

`AND job_token = #{jobToken}` 就是**"只有持有者能续"** ✓
被别人接管后旧持有者再来续 → `rowcount=0` ✓ 它据此知道自己已经出局 ✓

#### ③ `release(slotId, jobToken)` —— 按槽位号释放

```sql
UPDATE schedule_worker_slots
   SET organization_id = NULL, job_token = NULL, lease_until = NULL
 WHERE id = #{slotId} AND job_token = #{jobToken}
```

三个字段一起清 ✓ `AND job_token = ...` 保证**不会误释放别人的槽位** ✓（幂等 ✓）

#### ④ `releaseByToken(jobToken)` —— **按 token 释放**（P3 新增，主力）

```sql
UPDATE schedule_worker_slots
   SET organization_id = NULL, job_token = NULL, lease_until = NULL
 WHERE job_token = #{jobToken}
```

**为什么不用 ③**：任务回写结果时手里**只有 `jobToken`，没有 slotId** ✗
slotId 是领单那一次才知道的 ✓ 而任务表**没有存它** ✓（存了就要多一列、多一次改表 ✓）

**为什么 token 够用**：claim 时用的 token **就是** job 的 token ✓ 而它**每次领单都会换新的**（见 5a 册）✓
所以一个 token **唯一确定一个槽位** ✓ 不需要为了释放而多存一列 ✓

> ⚠ **它是幂等的，而且这个性质被依赖**：任务被接管后，旧 worker 醒来再来释放 →
> `WHERE job_token = 旧token` 匹配 0 行 ✓ **无害** ✓
> 这正是"接管时要用旧 token 释放槽位"那个修复能成立的前提（见 5a 册 / 踩坑总结 K1）✓

**实际调用点 5 处**（我 grep 过 ✓）：
`JobQueueService:150`（**接管时用旧 token 释放** ✓）、`:216`、`:237`、`:267`（三条终态路径 ✓）、`JobResultWriter:151` ✓

#### ⑤ `insertIgnore(slotId)` —— 幂等预置

```sql
INSERT IGNORE INTO schedule_worker_slots (id) VALUES (#{slotId})
```

**为什么用 `INSERT IGNORE` 而不是"先查再插"**：多个实例同时启动时 ✓
两边都可能查到"不存在"然后都去插 ✓ 其中一个会撞主键 ✓
**交给数据库忽略重复更简单也更可靠** ✓（对应 Python 的 `INSERT IGNORE` ✓）

#### ⑥ `lockForJob(slotId, jobToken)` —— ⚠ **死代码**

```sql
SELECT * FROM schedule_worker_slots
 WHERE id = #{slotId} AND job_token = #{jobToken}
 FOR UPDATE
```

注释说它「与 `SchedulingStateMapper.lockForRunning` 配对使用」✓ —— 但**两个都已经是死代码** ✗

**我实测 grep 的结果**：

| 方法 | 调用者 |
|---|---|
| `ScheduleWorkerSlotMapper.lockForJob` | **无** ✗ 只有声明 |
| `SchedulingStateMapper.lockForRunning` | **无** ✗ 只有声明（另有两处注释提到它） |
| **`ScheduleJobMapper.lockForRunning`** | **被 `JobResultWriter:72` 调用** ✓ ← 这才是真正在用的闸门 |

**它们是 P1「进程内线程」路径的遗留** ✓ —— 那时候"求解与落库之间确认租约"是锁这两行 ✓
P3 换成任务队列之后，确认改成了锁 **`schedule_jobs` 那一行**（见 5b 册）✓ 这两个方法就没人调了 ✓

> ⚠ **读代码时的提醒**：`lockForJob` 的注释写得很像那么回事（"结果落库前锁住槽位行，确认租约仍然是自己的"）✓
> 但**没有任何调用者** ✗ 别把它当成现在的机制 —— 这是我自己上一轮读旧稿时踩过的坑 ✓
> 判断"某段代码是不是活的"**只能靠 grep 调用点** ✗ 不能靠注释像不像 ✓

---

## 4b.3 `CapacityService` —— 抢 / 续 / 放（+ 1 个死方法）

`@Service`，同时 `implements ApplicationRunner` ✓ —— 这个组合是刻意的 ✓

### 构造器与两个下限

```java
public CapacityService(ScheduleWorkerSlotMapper slotMapper,
                       @Value("${orbit.scheduling.max-concurrent-schedules:2}") int maxConcurrent,
                       @Value("${orbit.scheduling.lease-seconds:300}") int leaseSeconds) {
    this.maxConcurrent = Math.max(1, maxConcurrent);
    // Python: max(120, ...) —— 租约不允许短于 120 秒，否则求解还没跑完就被判定超时
    this.leaseSeconds = Math.max(120, leaseSeconds);
}
```

| 参数 | 下限 | 为什么 |
|---|---|---|
| `maxConcurrent` | `≥ 1` | 0 会让**永远抢不到槽位** → 排课彻底不可用 ✓ 下限保证"至少能跑一个" |
| `leaseSeconds` | **`≥ 120`** | ⚠ **这不是防御性编程，是行为对齐** ✓ Python 是 `SCHEDULE_LEASE_SECONDS = max(120, int(os.getenv(...)))` ✓ |

⚠ **`Math.max(120, ...)` 的后果值得说清**：如果你把租约配成 60 秒，它会被**静默抬到 120** ✓
不会报错、不会有日志 ✓ 你以为配了 60，实际是 120 ✓

**并且这个下限必须与别处一致**：`ScheduleRunService` 的状态行租约用的是**同一个配置键、同一条下限** ✓
两处不一致会让「状态行的租约」与「槽位的租约」**错位** ✓ —— 一个已经过期（可以接管）而另一个还没过期 ✓
症状是"偶尔两个求解同时跑同一所学校" ✓ 极难查 ✓

### `run(ApplicationArguments)` —— 启动时预置槽位行

```java
@Override
public void run(ApplicationArguments args) {
    for (long slotId = 1; slotId <= maxConcurrent; slotId++) {
        slotMapper.insertIgnore(slotId);
    }
    log.info("容量槽位已就绪：{} 个（租约 {} 秒）", maxConcurrent, leaseSeconds);
}
```

**为什么实现 `ApplicationRunner`**：必须在**应用启动完成后**、**任何请求进来之前**把槽位行准备好 ✓
`ApplicationRunner` 正好是这个时机 ✓（`@PostConstruct` 太早 —— MyBatis 可能还没就绪 ✓）

**为什么是循环插入而不是一条批量**：因为要**幂等** ✓ 已存在的行 `INSERT IGNORE` 直接忽略 ✓
而且 `maxConcurrent` 是 2 这种小数字 ✓ 循环开销可以忽略 ✓

### `acquire(organizationId, jobToken)` —— 抢

```java
public Optional<Long> acquire(long organizationId, String jobToken) {
    LocalDateTime now = UtcTimes.now();
    LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
    for (long slotId = 1; slotId <= maxConcurrent; slotId++) {
        if (slotMapper.tryAcquire(slotId, organizationId, jobToken, leaseUntil, now) == 1) {
            return Optional.of(slotId);
        }
    }
    return Optional.empty();
}
```

逐点讲：

| 做法 | 为什么 |
|---|---|
| **`now` 只取一次**，复用给"判断过期"和"写租约" | ⚠ 如果每行都重新 `UtcTimes.now()` ✓ 前几行失败花掉的时间会让**后面的行用更晚的 now** ✓ 看似更准 ✓ 实际让"同一批尝试"的时间基准不一致 ✓ 排查时对不上 ✓ |
| **按 id 顺序试**（1 → N） | 简单、确定 ✓ 优先填满编号小的槽位 ✓ 人排查时"槽位 1 在跑"更直观 ✓ |
| **返回 `Optional<Long>`** | ⚠ **空表示"容量已满"** ✓ 这是**正常状态**不是错误 ✓ 接口据它回 **429** ✓ |
| 抢不到时**不做任何重试/等待** | 因为调用方（`claim`）的处理是"这次没有，下次轮询再来" ✓ 在这里 sleep 会占着 HTTP 线程 ✓ |

**⚠ 一处刻意的设计：整个循环不加 `@Transactional`**

类注释写得很清楚：Python 的实现是**每个槽位尝试后立刻 commit** ✓

```python
for slot_id in range(1, MAX + 1):
    changed = ...update(...)
    db.commit()          # ← 逐个提交
    if changed == 1: return slot_id
```

如果整个循环包在一个事务里 ✗：
1. **失败的尝试虽然不留数据，但会让事务持有一批行锁** ✓ 别的实例更难抢 ✓
2. **"抢到"到"外层提交"之间的时间窗被拉长** ✓ 这段时间里数据库里看不到你占了槽位 ✓ 但别人也抢不到（行锁）✓ → 变成一个**隐形的互斥窗口** ✓

所以这里 mapper 调用**不开事务** ✓ 每条语句自动提交 ✓ 与 Python 行为一致 ✓

> ⚠ 但这带来一个**必须知道的后果**：`acquire` 成功之后，如果**后续代码抛异常** ✓
> 槽位**不会自动回滚** ✗（因为它已经独立提交了 ✓）
> 所以调用方必须保证"抢到槽位之后无论走哪条路都要释放" ✓ —— 这正是 P3 里那三条终态路径
> （完成 / 失败 / 取消）**都必须释放槽位**的原因 ✓ 漏一条就是**永久泄漏一个槽位** ✓
> 泄漏 2 个（默认上限就是 2）→ **队列彻底停摆** ✓ 这是踩坑总结 K1 的第 2、4 条 ✓

### `renew(slotId, jobToken)` —— ⚠ **死代码**

```java
public boolean renew(long slotId, String jobToken) {
    return slotMapper.renew(slotId, jobToken, UtcTimes.now().plusSeconds(leaseSeconds)) == 1;
}
```

**我 grep 过：没有任何调用者** ✗（`slotMapper.renew` 只被这一个方法调用 ✓）

**这意味着一件真事**：**槽位的租约从来不会被续** ✗
它只在 `acquire` 那一刻被设成 `now + leaseSeconds`（默认 300 秒）✓ 之后**再也没人改它** ✓

**后果**（⚠ 以下是我**从代码路径推导**的，没有实测）：

```
一个求解跑超过 300 秒（time-limit 60 秒 × 加上排队/网络/重试，通常不会）
  → 槽位的 lease_until 过期
  → 别的学校调 claim 时，tryAcquire 认为这个槽位"已超时"，抢走它
  → 全局并发数超过 maxConcurrent
```

**注意它不会破坏"同一所学校"的互斥** ✓ —— 那是 ①Redis 锁 和 ③状态 CAS 管的 ✓
破坏的只是「**全局并发上限**」这一条 ✓ 也就是"CPU 可能被多打一份" ✓

**要不要修**：取决于你对"最长一次求解耗时"的估计 ✓
默认 `time-limit=60s` + 租约 `300s`，**正常情况下够用** ✓ 但配置成 `time-limit=600s` 就会踩 ✓
**建议**：要么在心跳里顺带 `renew` 槽位（worker 侧本来就有心跳线程 ✓ 改动很小 ✓），
要么把这条**明确写进配置校验**——`lease-seconds` 必须 > `time-limit-seconds` ✓ 否则启动就拒绝 ✓
（已记入待办，未改动代码。）

### `release(slotId, jobToken)` 与两个读取器

```java
public void release(long slotId, String jobToken) { slotMapper.release(slotId, jobToken); }
public int maxConcurrent() { return maxConcurrent; }
public int leaseSeconds() { return leaseSeconds; }
```

- `release` —— **不返回是否成功** ✓ 因为释放是幂等的 ✓ 调用方不需要知道 ✓
  （对比 `renew` 返回 boolean ✓ —— 因为续租失败**意味着出局**，调用方必须知道 ✓
  **这个差异是有意义的**，不是随意的 ✓）
- 两个读取器 —— `maxConcurrent()` / `leaseSeconds()`，给运维接口与排查用 ✓

---

## 4b.4 `ScheduleLockService` —— Redis 分布式锁

**它和数据库那套完全独立** ✓ 是四层里的第 ① 层 ✓

### 锁的 key 与 Python 逐字一致

```java
private static final String KEY_PREFIX = "orbit:schedule_lock:";
private static String key(long organizationId) { return KEY_PREFIX + organizationId; }
```

⚠ **key 必须与 Python 逐字一致** ✓ 因为迁移期**双跑**：从 Java 发起的排课与从 Python 发起的排课
会争**同一把锁** ✓ 否则两边各排一次 ✓ 同一学校就会同时跑两个求解 ✓

> 这是一个**只有迁移期才存在**的约束 ✓ 它带来一个实际好处：影子对比测试时不打架 ✓

### 三个动作都是原子的 —— 但方式不同

| 动作 | 实现 | 为什么原子 |
|---|---|---|
| **抢** | `SET key token NX EX ttl` | 一条 Redis 命令完成"不存在才设置 + 带过期" ✓ |
| **释放** | **Lua 脚本**：比对 token 再 `DEL` | 见下 |
| **续租** | **Lua 脚本**：比对 token 再 `EXPIRE` | 见下 |

#### 释放的 Lua（逐句）

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
```

| 行 | 做什么 |
|---|---|
| `redis.call('get', KEYS[1])` | 读当前锁的持有者 token |
| `== ARGV[1]` | 和"我手里的 token"比 |
| `del` + `return 1` | **我是持有者** → 删掉（返回 1） |
| `return 0` | **我不是持有者** → 什么都不做（返回 0） |

#### 🔴 为什么**不能**直接 `DEL`

**这是分布式锁最经典的 bug** ✓ 场景：

```
时刻 T1   任务 A 抢到锁，TTL 300 秒
时刻 T2   任务 A 卡住（GC / 网络 / 求解超时），锁到期自动消失
时刻 T3   任务 B 抢到了同一把锁
时刻 T4   任务 A 醒过来，执行"释放锁" → 直接 DEL → **把 B 的锁删掉了** ✗
时刻 T5   任务 C 认为没锁，抢到 → **B 和 C 同时跑** ✗
```

**加上 token 比对之后**：T4 时 A 的 token ≠ 锁里 B 的 token → `return 0` ✓ **什么都不做** ✓

⚠ 这个 bug **看起来能跑** ✓ 只有在"任务超时 + 另一个任务刚好抢到"时才会出事 ✓
平时测不出来 ✓ —— 所以这段 Lua 不是"更优雅"，是**正确性所必需** ✓

#### 续租的 Lua

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('expire', KEYS[1], ARGV[2])
end
return 0
```

同样的比对 ✓ 只是把 `del` 换成 `expire` ✓ 返回的就是 `expire` 的结果（1 = 成功，0 = key 不存在）✓

**为什么必须用 Lua 而不是"先 GET 再 EXPIRE"**：两步之间**可能被别人插进来** ✗
Redis 执行 Lua 时是**单线程原子**的 ✓ 中间不会插入别的命令 ✓

### `acquire(organizationId)` —— 抢锁

```java
public LockAttempt acquire(long organizationId) {
    String token = newToken();
    try {
        Boolean ok = redis.opsForValue().setIfAbsent(
                key(organizationId), token, Duration.ofSeconds(lockSeconds));
        // ok == null 表示在 pipeline/事务里没拿到结果，按失败处理（保守）
        return new LockAttempt(true, Boolean.TRUE.equals(ok) ? token : null);
    } catch (Exception e) {
        log.warn("获取排课锁失败，降级为无锁执行: {}", e.getMessage());
        return new LockAttempt(false, null);
    }
}
```

**三个要点**：

1. **`token` 先生成，再尝试设置** ✓ —— 即使设置失败，token 也已经确定 ✓
   这样"抢到"的那一刻就有一个**只有我知道的凭证** ✓

2. ⚠ **`Boolean.TRUE.equals(ok)` 而不是 `ok`** ✓
   `setIfAbsent` 返回 `Boolean` ✓ 理论上可能是 `null`（在 pipeline/事务里执行时拿不到结果）✓
   直接拆箱会 **NPE** ✓ 用 `TRUE.equals(...)` 把 `null` 安全地当成**失败** ✓（保守方向正确 ✓）

3. **`catch (Exception)` → 降级为"无锁执行"** ✓
   这不是"忽略错误" ✓ 是**有意的可用性优先** ✓ 见下 ✓

### 🔴 `LockAttempt` 的语义：`acquired()` 是**"可以继续"**，不是"真的拿到锁"

```java
public record LockAttempt(boolean redisEnabled, String token) {
    public boolean acquired() {
        return !redisEnabled || token != null;
    }
}
```

真值表：

| `redisEnabled` | `token` | `acquired()` | 情况 |
|---|---|---|---|
| `true` | 非空 | **true** | 正常抢到锁 |
| `true` | `null` | **false** | 锁被别人占着 → 接口回 **409** |
| `false` | `null` | **true** | ⚠ **Redis 不可用 → 当作拿到锁**（降级放行） |

**为什么敢降级放行**：因为**后面三层还在** ✓
容量槽位（数据库）照样限全局并发 ✓ 状态 CAS 照样保证同校不重复 ✓
**Redis 只负责"更早地拦掉"，不是唯一防线** ✓

⚠ **命名来自 Python 的 `LockAttempt.acquired`** ✓ 刻意保持一致以免两边语义漂移 ✓
但这个名字**容易被误读** ✓ 读代码时记住：**它问的是"能不能继续"，不是"锁在我手里"** ✓

### `release` / `renew`

```java
public void release(long organizationId, String token) {
    if (token == null) { return; }          // 降级模式没有 token，无事可做
    try {
        redis.execute(RELEASE_SCRIPT, List.of(key(organizationId)), token);
    } catch (Exception e) {
        // Python 这里也是 pass：释放失败不该影响业务流程
        log.warn("释放排课锁出错: {}", e.getMessage());
    }
}

public boolean renew(long organizationId, String token) {
    if (token == null) { return true; }     // 与 Python 一致：无 token（降级模式）视为成功
    try {
        Long result = redis.execute(RENEW_SCRIPT, List.of(key(organizationId)),
                token, String.valueOf(lockSeconds));
        return result != null && result > 0;
    } catch (Exception e) {
        log.warn("续租排课锁出错: {}", e.getMessage());
        return false;
    }
}
```

**两处"无 token"的处理方向相反，都是对的**：

| 方法 | `token == null` 时 | 为什么 |
|---|---|---|
| `release` | **直接返回**（什么都不做） | 降级模式下**本来就没锁** ✓ 释放个不存在的东西没有意义 ✓ |
| `renew` | **返回 `true`**（视为成功） | 降级模式**不需要续租** ✓ 返回 `false` 会让调用方以为"我出局了" → **主动放弃任务** ✗ 那是灾难 ✓ |

> ⚠ 这个不对称**很容易看漏** ✗ 一眼扫过去会以为"两个都在处理 null，应该一样" ✓
> 实际上它们的方向相反 ✓ 而且是**必须相反** ✓

**`renew` 的失败处理与 `acquire` 也不同**：`acquire` 的异常 → 降级放行 ✓
`renew` 的异常 → **返回 `false`** ✓ 为什么 ✗ —— 续租失败意味着"锁可能已经不在我手里了" ✓
**保守地告诉调用方"我出局了"是更安全的方向** ✓

### `newToken()` —— 与 Python 的 `secrets.token_urlsafe(24)` 对齐

```java
private String newToken() {
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
}
```

24 字节随机 → Base64URL **无填充** → 32 个字符 ✓ 与 Python 的 `token_urlsafe(24)` **同长度同字符集** ✓

> ⚠ **与 `JobQueueService.newJobToken()` 不是一回事** ✓
> 那个是**十六进制** ✓ 两个随机串编码不同是**刻意的**：
> 锁的 token 只进 Redis、不落库 ✓ 而 job token 要**落库、要跨进程传** ✓ 十六进制更省心（无 `+` `/` `=` 的转义问题）✓

### `lockSeconds` 的 TTL

`@Value("${orbit.scheduling.lock-seconds:300}")` ✓ 默认 300 秒 ✓

**⚠ 但 P3 之后这个 TTL 的意义变了** ✗ ——
锁现在只在「检查 + CAS + 入队」这个**短临界区**里持有 ✓ 全程**毫秒级** ✓
所以 300 秒的 TTL **远远用不到** ✓ 它更像是"万一进程在临界区里崩了，最多 300 秒后锁自动释放"的兜底 ✓

> **这正是 P3 必须把锁提前释放的原因** ✓（见 4a 册 / 踩坑总结 K1 第 1 条）
> 如果继续握到求解结束 ✗ 那么：worker 崩溃时**没人会去释放这把锁** ✗
> 而 TTL 是 300 秒 ✓ 之后虽然锁会自动消失 ✓ 但**新的排课任务在 300 秒内全被 409 挡掉** ✓
> 用户看到的是「本校已有排课任务正在运行，请稍后再试」✓ 而其实根本没人在跑 ✓

---

## 4b.5 本册发现的 3 个问题

按"要不要动代码"排序：

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| **1** | **`CapacityService.renew` 无调用者 → 槽位租约从不续租** ✗ | 配置成 `time-limit > lease-seconds` 时，全局并发上限会被突破（**不影响同校互斥**） | 要么在 worker 心跳里顺带续槽位，要么**启动时校验** `lease-seconds > time-limit-seconds`（更简单、更稳） |
| **2** | **`ScheduleWorkerSlotMapper.lockForJob` 是死代码** ✗（`SchedulingStateMapper.lockForRunning` 也一样） | 无功能影响；但**注释把它写得像核心机制**，读者会被带偏 | 删掉，或把注释改成"P1 遗留、当前无调用者" |
| **3** | `tryAcquire` 的 SQL 外层 `<script>` 是多余的 ✗ | 无功能影响 | 可删；留着会让人以为有条件拼接 |

**三处都还没有实测验证过行为**（第 1 条是从代码路径推导的 ✓ 第 2、3 条是 grep 事实 ✓）
—— **我没有改动任何代码** ✓ 已记入待办 ✓

> 顺便说一句**方法论**：第 2 条是本册最值得记的教训 ——
> **判断"一段代码是不是活的"只能靠 grep 调用点，不能靠注释像不像** ✓
> 我自己上一轮读旧稿时就把 `lockForJob` 当成了核心机制 ✓ 直到 grep 才发现没有调用者 ✓
