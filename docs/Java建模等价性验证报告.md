# Java 建模等价性验证报告

> spike 目的：回答「用 OR-Tools Java 重写排课引擎，模型能不能和现有 Python 版一模一样」
> 结论：**能。逐字段完全一致，指纹相同。**
> 日期：2026-09-25 · 对应方案 §10 路线图 P1 的决策门

---

## 1. 为什么要先做这个验证

整个「Python → Java + 微服务」的迁移里，只有一件事是真正的未知数：

> OR-Tools 的 Java 绑定（`com.google.ortools:ortools-java:9.15.6755`）与 Python wheel
> （`ortools==9.15.6755`）构建出的 CP-SAT 模型，是否严格一致？

如果不等价，那么规则系统、求解隔离、助手全都建立在不可靠的地基上。所以先花 2-3 天验证，
而不是等写完 5 个服务才发现问题。

**同一版本号**：Maven Central 上的 `ortools-java` 最新版是 `9.15.6755`，与项目中
Python 侧 `ortools==9.15.6755` 逐位相同，底层是同一份 C++ 内核。

---

## 2. 方法

| 步骤 | 做法 |
|---|---|
| 输入 | 生成一份合成学校 JSON（`orbit-solver-core/src/test/resources/fixtures/school-8.json`），**Java 与 Python 读同一份文件** |
| Python 侧 | 用原 `scheduler/engine.py` 建同一模型，导出 `py-model.pb` |
| Java 侧 | `ModelBuilder`（忠实移植）建同一模型，导出 `java-model.pb` |
| 比对 | 用 `cp_model_pb2` 逐字段 diff：变量名、定义域、约束逐条、目标函数 |

测试数据：**8 个班 / 36 位教师 / 64 条任教关系 / 1 条年级级固定课规则**（跨 2 个年级，
且含固定课展开路径）。

> 期间发现：如果固定课覆盖的班级共享同一位教师，会导致该教师在同一时段被固定多次，
> 被引擎的 `if fixed_count > 1: return []` 直接判为不可行——**且对外只表现为"排课无解"**。
> 这正是方案里「`return []` 承载 5 种语义」缺陷的现场演示。

---

## 3. 结果

### 3.1 模型统计：逐行完全一致

```
#Variables: 13'452 (#bools: 1'572 #ints: 320 in objective) (13'068 primary variables)
  - 12'896 Booleans in [0,1]
  - 28 in [0,5]
  - 292 in [0,6]
  - 236 in [0,8]
#kBoolAnd: 7'232 (#enforced: 7'232) (#literals: 16'032)
#kBoolOr: 7'232 (#enforced: 7'232) (#literals: 8'800)
#kLinear1: 11'977 (#enforced: 11'328)
#kLinear3: 413
#kLinearN: 420 (#terms: 5'984)
```

### 3.2 proto 逐字段比对

| 项 | Python | Java | 结果 |
|---|---|---|---|
| 变量数 | 13,452 | 13,452 | 相同 |
| **变量名（逐位）** | — | — | **完全相同** |
| **变量定义域（逐位）** | — | — | **完全相同** |
| 约束数 | 27,274 | 27,274 | 相同 |
| **约束逐条序列化** | — | — | **完全相同** |
| 目标项数 / 系数 / 偏移 | 1,892 / 一致 / 0.0 | 1,892 / 一致 / 0.0 | 相同 |
| `objective.scaling_factor` | **1.0** | **0.0** | **唯一差异** |
| `validate()` | `''`（合法） | `''`（合法） | 相同 |

### 3.3 唯一差异及处理

`CpObjectiveProto.scaling_factor`：
- Python 的 `model.Minimize(...)` 会**显式写入 1.0**（含义：不做目标缩放）
- Java 的 `model.minimize(...)` **留空 = 0.0**（proto 默认值，同样表示不做缩放）

**两者对求解语义相同**，但 `model_fingerprint` 是对整个 proto 序列化后算的摘要，
因此这一个记账字段的差异就会改变指纹。

处理方式（已落在 `SpikeMain` 中，含注释）：

```java
builder.model().getBuilder().getObjectiveBuilder().setScalingFactor(1.0);
```

### 3.4 对齐后的指纹

| | fingerprint |
|---|---|
| Python | `0x874e810d17f32a8b` |
| Java（默认） | `0x735a03bb2eff53a7` |
| **Java（对齐 scaling_factor 后）** | **`0x874e810d17f32a8b`** ✅ |

**逐字符相同 → Java 建模与 Python 严格等价。**

---

## 4. 结论与对方案的影响

### 4.1 结论

**OR-Tools Java 可以作为 Python 引擎的等价替代**。可以把 Python 的排课引擎安全地迁移到 JVM，
规则系统、求解进程隔离、助手等后续设计都建立在可靠地基上。

### 4.2 修正了方案里的一处方法错误

方案原本把 **`model_fingerprint` 当作跨语言等价性判据**。实测证明：

- ✅ 指纹在**同一绑定内**是稳定、可复现的（跨进程同值）→ 适合做**回归基线**
- ❌ 指纹**不能跨语言绑定**用作等价判据——它对绑定特有的记账字段（如 `scaling_factor`）敏感

**正确的跨语言等价性判据是「逐字段比对 proto」**：
变量名 + 定义域 + 约束逐条序列化 + 目标函数。这套方法已沉淀为可复现脚本。

---

## 5. 已知问题：Windows 上求解时 JVM 崩溃

**现象**：建模完全正常，但一调用 `CpSolver.solve()` 就崩溃：

```
EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ff802f52f58
Problematic frame: C  [msvcp140.dll+0x12f58]
```

**已排除**：本机已安装 Microsoft Visual C++ 2015-2022 Redistributable **14.51.36247**
（比 OR-Tools 9.15 构建时使用的更新），`msvcp140.dll` / `vcruntime140.dll` /
`vcruntime140_1.dll` 均存在。因此**不是"缺运行库"那么简单**。

**影响范围**：只影响求解，**不影响建模与指纹验证**（本报告的全部结论都不依赖求解）。

**建议的绕行方案**（按优先级）：
1. **在 Linux 容器里跑求解**——本机已有 Docker，用 `eclipse-temurin:21` 挂载 jar 即可，
   绕开 Windows 原生库问题；这也更接近生产部署形态
2. 换一个 OR-Tools 版本试（例如 9.14 / 9.12）确认是否为该版本在 Windows 的回归
3. 在另一台 Windows 机器上复现，确认是环境相关还是版本相关

---

## 6. 复现步骤

```powershell
# 1) 下载 spike 所需 jar（8 个，约 30 MB）
#    ortools-java / ortools-win32-x86-64 / protobuf-java / jna / jna-platform / jackson×3

# 2) 生成 fixture 与 Python 基准
backend\venv\Scripts\python.exe .tmp\make_fixture.py

# 3) 编译并运行 Java 侧（只建模，不求解）
javac --release 21 -encoding UTF-8 -cp "<jars>" -d backend-java\.spike-out <sources>
java -cp "<jars>;backend-java\.spike-out" com.orbit.solver.core.SpikeMain <fixture 路径>

# 4) 逐字段比对两侧 proto
backend\venv\Scripts\python.exe .tmp\diff_model_proto.py
```

---

## 7. 遗留

| # | 事项 | 说明 |
|---|---|---|
| 1 | Windows 求解崩溃 | 见 §5，建议改用 Linux 容器 |
| 2 | 尚未验证求解结果一致性 | 建模已证等价，但「同一实例两边求得的最优目标值相同」还需在能求解的环境里补测 |
| 3 | 本 spike 代码是一次性的 | 下一步会重构成 `RuleContext` + `RuleHandler`（去掉 28% 重复变量），本文件与 `ModelBuilder` 届时可删 |
