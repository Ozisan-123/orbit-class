# 智能教务中台 · Java 后端（多模块）

> 完整方案见 [`../docs/智能教务中台-项目方案.md`](../docs/智能教务中台-项目方案.md)。

## 一、Maven 依赖（编译期）

```
orbit-common        共享异常 / DTO / 租户上下文
                    └─ 刻意不引 spring-boot-starter-web，避免把 Servlet 栈拖进 WebFlux 网关

orbit-solver-core   求解内核库（非可运行服务）
                    └─ 规则 schema + 规则编译 + RuleContext + CP-SAT 引擎
                    └─ 只依赖 ortools-java，不依赖 Spring / MySQL
                       ↑                              ↑
       orbit-scheduling（出 schema、校验规则参数）   orbit-solver-worker（真正求解）
                                                      └─ ⚠ 不依赖 MySQL / MyBatis / common

orbit-academic      教务基础数据（依赖 common）
orbit-assistant     智能助手（依赖 common）
orbit-gateway       网关（不依赖任何内部模块，见下）
```

**为什么有 `orbit-solver-core` 这个库模块**：规则既有「参数 schema」（调度服务要用来校验和出表单）又有「编译成约束」（求解进程要用来建模）。若两部分各写一套必然漂移，所以抽成共享库，**规则定义只有一份**。

## 二、运行期调用（HTTP，与 Maven 依赖无关）

```
            gateway  8080
       ┌────────┬────────┬────────┐
       ▼        ▼        ▼        ▼
  academic  scheduling  assistant
   8081       8083        8085
       ▲        ▲            ▲
       │        │            │
       │   solver-worker  8084（内部端口，仅暴露 actuator）
       │        │  └─ 领任务 / 报心跳 / 回结果
       └────────┘
   scheduling → academic：拉取排课输入快照
   assistant  → scheduling / academic：工具调用
```

## 三、骨架纪律（对应方案 §3 五条铁律）

| 铁律 | 在骨架里的体现 |
|---|---|
| 排课原子性不跨服务 | `scheduling/{job,capacity,lock,version}` 同模块同事务域；求解被推到最后一步 |
| 禁止跨服务写库 | 每模块 `resources/db/migration/<模块名>/` 各管自己的表，Flyway 用独立 history 表 |
| 排课输入用快照 | `academic/snapshot` 出数据、`scheduling/snapshot` 冻结数据 |
| 助手只提议不执行 | `ToolGateway` 把只读、草案预检、确认执行分成三个入口，写操作必须经人工确认 |
| 禁止编造 | 模型只选白名单工具；回答数据来自工具结果；缺参数和名称重名时明确追问 |

### 包归属（谁在哪里写什么）

| 包 | 放什么 | 明确不放 |
|---|---|---|
| `solver.core.model` | 求解输入 / 输出 POJO | 持久化注解 |
| `solver.core.engine` | 变量构建、成员布尔、求解、结果提取、求解统计 | ❌ 具体业务约束（那些是**规则**） |
| `solver.core.rule` | `RuleHandler` 接口 + 参数 schema（一处定义三处用） | ❌ 数据库实体 |
| `solver.core.rule.handler` | 每条规则的 CP-SAT 实现 | — |
| `solver.core.compile` | `RuleContext`（收敛固定课处理）、`RuleCompiler`、`PenaltyCollector` | — |
| `scheduling.rule` | `ConstraintRule` 实体、Mapper、`RuleController`、`RuleService`（CRUD / 参数校验 / 试算影响） | ❌ 规则实现 ❌ 编译逻辑 |
| `scheduling.{schedule,version,query,availability,swap,capacity,job,lock,diagnosis,validate,audit,snapshot}` | 排课编排、课表版本、调课、并发控制、任务队列、诊断、独立校验、审计 | ❌ 求解（在 worker 进程） |
| `academic.snapshot` | **产出**排课输入快照 | ❌ 消费快照 |
| `scheduling.snapshot` | **冻结并消费**快照 | ❌ 产出快照 |
| `assistant.{controller,service,tool,repository,security}` | 会话、SSE、模型/本地路由、只读/草案/确认、审计 | ❌ 直连其他服务的业务表 ❌ 自动发布课表 |

**为什么 `rule` 与 `compile` 只存在于 `solver-core`**：规则实现要调 CP-SAT（依赖 ortools），
而 `RuleContext` 是「把散落在建模代码里的固定课处理收敛到一处」的共享视图——两者都必须**只有一份**，
否则调度服务与求解进程会各写一套并逐渐漂移。`orbit-scheduling` 依赖 `solver-core`，
只是为了**复用 schema 做参数校验**与**向前端 / 助手输出规则类型清单**，不是为了自己再实现一遍。

## 四、构建与启动

```bash
# 前置：JDK 21 + Maven
docker compose up -d            # MySQL 8.4 + Redis 7
mvn -q clean package

# 逐个启动（或由 compose/IDE 统一拉起）
java -jar orbit-academic/target/*.jar
java -jar orbit-scheduling/target/*.jar
java -jar orbit-solver-worker/target/*.jar
java -jar orbit-assistant/target/*.jar
java -jar orbit-gateway/target/*.jar     # 前端开发时 Vite proxy 指向 :8080
```

## 五、当前验证基线

- 全量 Java 单元测试：87 个通过；
- 前端 `npm run lint` 与 `npm run build` 通过；
- 默认启动拓扑包含 academic、scheduling、solver-worker、gateway、assistant；
- 助手详细设计、配置和验收步骤见 [`../docs/代码导读-8-智能助手.md`](../docs/代码导读-8-智能助手.md)。
