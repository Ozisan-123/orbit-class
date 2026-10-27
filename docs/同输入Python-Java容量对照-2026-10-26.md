# Python 与 Java 同输入排课容量对照（2026-10-26）

## 结论

在当前代码、同一份合成学校输入、OR-Tools 9.15.6755、每任务 2 个搜索线程、12 秒求解时限下，**Python 和 Java 都成功排到单校 160 班**；各自同时运行两个 160 班任务时，也都生成并校验了合计 **9,600 节课**。继续扩容后，Java 的 196 班在 12 秒内 **3/3 次成功**，197、198 班各 2/3，200 班 0/3；将 200 班时限放宽至 30 秒，Python 和 Java 都成功。因此先前基于另一份真实学校拓扑得出的“Java 最大已验证成功档位 48 班”**不是 Java 的通用容量上限**。

## 对齐方式

- 数据用 `backend/tests/benchmark_scheduler_scale.py` 的 `seed_organization()` 生成，Python 从隔离的临时 SQLite 读取，Java 从该数据库导出的同一份 `ScheduleInput` JSON 读取。Java 规则集使用 `RuleSets.legacyEquivalent()`，对应 Python 的硬约束和两条软约束。
- 每班 8 门科目、每周 30 节；每位教师最多负责 3 个班。20/40/80/120/160 班的 JSON 保留在 `output/bench/fair/school-*.json`。
- **固定课澄清**：旧生成器会往 `fixed_slots` 写每班 1 条固定课，但当前 Python `scheduler/engine.py` 查询的是 `grade_fixed_slots`，没有使用 `fixed_slots`。本轮单校测试在两边均移除了这批未生效的规则；双校 Python 复用了旧脚本，虽然表内有这些行，但当前求解函数不会读取它们。故本轮比较的是**无固定课生效**的同一类问题。历史 `BENCHMARK_REPORT.md` 对“固定课参与建模”的描述，不能用当前代码证实。
- 每个规模各运行 1 次，是容量复核，不是统计充分的性能排名。Python 耗时包含数据库读取、建模、求解及结果提取；Java 耗时是 JSON 已载入后的建模、求解及提取。两列耗时不可直接用来计算语言性能差。

## 单校结果

| 班级数 | 应排节数 | Python 结果 / 墙钟 | Java 结果 / 内核耗时 |
|---:|---:|---|---|
| 20 | 600 | 有效 600 节 / 3.015 s | `OPTIMAL`，有效 600 节 / 4.430 s |
| 40 | 1,200 | 有效 1,200 节 / 6.201 s | `OPTIMAL`，有效 1,200 节 / 5.851 s |
| 80 | 2,400 | 有效 2,400 节 / 14.232 s | `FEASIBLE`，有效 2,400 节 / 13.229 s |
| 120 | 3,600 | 有效 3,600 节 / 15.236 s | `FEASIBLE`，有效 3,600 节 / 13.546 s |
| 160 | 4,800 | 有效 4,800 节 / 16.527 s | `FEASIBLE`，有效 4,800 节 / 14.079 s |

“有效”要求总节数正确，并检查同班时段冲突、同教师时段冲突及每班每科周课时。Java 另外用 `TestFixtures.checkInvariants()` 验证任教关系。Python 现有 `generate_schedule()` 只返回课表，不暴露 CP-SAT 状态，因此表中不推断其 `OPTIMAL`/`FEASIBLE` 状态。

## 双校并发结果

两所学校各 160 班，每个任务各有 2 个搜索线程，求解器时限均为 12 秒。

| 实现 | 两任务结果 | 合计课程 | 两任务并发总墙钟 |
|---|---|---:|---:|
| Python 当前实现 | 两任务均有效，各 4,800 节 | 9,600 | 20.664 s |
| Java `orbit-solver-core` | 两任务均 `FEASIBLE` 且校验通过，各 4,800 节 | 9,600 | 15.063 s |

Python 双校实验经现有 SQLite 数据层；Java 双校实验是同一 JVM 中两个独立的 `OrbitSolver` 任务，未经过网关、数据库、Redis 或任务队列。**这不是完整微服务并发吞吐测试**，不能单凭总耗时断言 Java 服务端到端更快。Java 实验的两任务建模约 2.28/2.30 秒、求解约 12.74/12.73 秒，均包含在并发总墙钟内。

## 继续扩容：160 班之后

同一生成器、每任务 2 个搜索线程。Python 当前接口在限时内未找到解时返回空课表，不能区分 `UNKNOWN` 与 `INFEASIBLE`；但 200 班在 30 秒内成功，证明 12 秒失败是时限问题。

| 班级数 | 应排节数 | 12 秒 Python | 12 秒 Java |
|---:|---:|---|---|
| 180 | 5,400 | 0/1 次有效 | 1/1 次 `FEASIBLE` |
| 190 | 5,700 | 0/1 次有效 | 1/1 次 `FEASIBLE` |
| 195 | 5,850 | 0/1 次有效 | 1/1 次 `FEASIBLE` |
| 196 | 5,880 | 0/1 次有效 | **3/3 次 `FEASIBLE`** |
| 197 | 5,910 | 0/1 次有效 | 2/3 次 `FEASIBLE` |
| 198 | 5,940 | 0/1 次有效 | 2/3 次 `FEASIBLE` |
| 200 | 6,000 | 0/2 次有效 | 0/3 次有效，均 `UNKNOWN` |

200 班改为 **30 秒**：Python 1/1 次有效，Java 1/1 次 `FEASIBLE`，两边均输出并校验 6,000 节。Python 总墙钟 35.272 秒（含数据库读取和建模），Java 内核 32.191 秒（已载入 JSON 后）；仍不能直接计算服务性能差。

**当前最有用的容量表述**：对这份合成学校数据，12 秒搜索上限下 Java 在 196 班连续 3 次成功，197～198 班的 3 次成功率为 2/3，200 班连续 3 次未找到解；30 秒上限下 200 班可成功。由于 CP-SAT 多线程搜索有运行波动，而且只测试了一种数据分布，不能把“196 班”写成数学上的最大班级数或生产容量承诺。

## 数据与复现

- 单校 Python 原始结果：`output/bench/fair/python-*.json`；Java 原始结果：`output/bench/fair/java-*.csv`。
- 双校原始结果：`output/bench/fair/python-2x160.json`、`output/bench/fair/java-2x160.csv`。
- 临界档 Java 复测记录为 `java-196-r2.csv`、`java-196-r3.csv` 等；200 班 30 秒记录为 `python-200-30s.json`、`java-200-30s.csv`。
- Python 生成与测试：`tools/run_fair_python_case.py`；Java 测试：`backend-java/orbit-solver-core/src/test/java/com/orbit/solver/core/JavaSolverBenchmarkTest.java`。
- 160 班 JSON 的 SHA-256：`7bcc1e1c3a16b44a30e7bb2e95f49e8ee87d2dbd3aed13366cd0debfc951d231`。

从仓库根目录运行单校 Python，然后在 `backend-java` 目录运行 Java：

```powershell
& backend/venv/Scripts/python.exe tools/run_fair_python_case.py 160 12 2
cd backend-java
& 'C:\Users\Lenovo\tools\apache-maven-3.9.16\bin\mvn.cmd' -o -q -pl orbit-solver-core -am '-Dtest=JavaSolverBenchmarkTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dbench.enabled=true' '-Dbench.fairFixture=../../output/bench/fair/school-160.json' '-Dbench.limitSeconds=12' '-Dbench.workers=2' '-DargLine=-Xmx4096m' '-Dbench.output=../../output/bench/fair/java-160-repeat.csv' test
```

双校 Java 将 `-Dbench.fairConcurrency=2` 加入上述命令，并换一个输出文件名。双校 Python 用现有脚本的 `--child --classes 160 --case-concurrency 2 --time-limit 12 --solver-workers 2` 参数；如果本机临时目录受限，可将环境变量 `ORBIT_BENCH_TMPDIR` 指向仓库内可写的 `output/bench/fair`。
