# LogLens 性能测试与容量评估报告

> 文档状态：基于当前代码与可复跑基准的性能记录。
>
> 测试日期：2026-10-20（Asia/Shanghai）。
>
> 适用范围：LogLens Java Agent 的日志采集、单消费者处理与本地 JSONL/SQLite 持久化路径。
>
> 性能数据是指定机器和指定短日志负载下的实测值，不构成其他机器、生产业务或长期稳定运行的容量承诺。

## 1. 执行摘要

本次性能验证围绕一个具体问题展开：目标 JVM 以突发方式产生 100,000 条短 INFO 日志时，LogLens 在保护业务日志调用延迟和完整采集之间如何取舍。

当前默认配置使用 8,192 槽 Disruptor、单个消费线程和 `DROP_NEWEST`。在 2026-10-20 的一次新默认策略复测中，应用侧 `observe` 日志调用循环约为 114,440 次/秒，但 LogLens 只持久化 8,663 条、丢弃 91,337 条；`replace-console` 调用循环约为 41,678 次/秒，持久化 14,846 条、丢弃 85,154 条。应用原始 FileAppender 在两种 Agent 模式中均完整写入 100,000 条。这里的高调用速度不代表 LogLens 无损处理能力，而是生产者在队列满时继续运行并丢弃 LogLens 采集副本的结果。

另用 `BLOCK` 策略进行了三轮全量持久化测试。`observe` 每轮均采集 100,000 条、丢弃 0 条，应用日志调用循环的中位吞吐为 7,611 次/秒；`replace-console` 同样零丢弃，中位吞吐为 7,749 次/秒。调用延迟的 p99 中位数分别为 59.2 微秒和 87.1 微秒，但三轮中还分别出现了约 1.10–2.06 秒和 0.66–0.69 秒的单次最大调用延迟。`BLOCK` 是以业务线程等待换取完整采集，最大调用延迟说明仅看 p99 也不足以评价长尾风险。

本报告能够支持的结论是：**在这台 Windows/JBR 21 主机、当前单线程基准和本地 JSONL/SQLite 配置下，零丢弃的端到端日志调用速度处于每秒约 7,000–8,000 条的量级；默认丢弃策略能让生产者继续前进，但在 100,000 条突发负载下会丢弃大多数 LogLens 采集副本。** 这不是经过长时间稳态、并发生产者、真实代理遥测或 SSE 客户端验证的生产容量上限。

## 2. 测试范围与不包含的内容

本次测试覆盖以下链路：

```text
SLF4J / Logback Logger 调用
        ↓
LogLens Agent 捕获与快照脱敏
        ↓
8,192 槽 MULTI Disruptor（本基准只有一个日志生产线程）
        ↓
一个 loglens-consumer 消费线程
        ↓
captured JSONL + SQLite 索引
        ↓
默认处理器链与 processed JSONL / SQLite
        ↓
会话关闭时排空队列并写入结束状态
```

当前实现中，每条被接收的日志会先进入 captured 存储，再经过处理器链并按处理结果写入 processed 存储；存储成功后，启用 Web 时才会广播给 SSE EventHub。消费者是串行执行这条路径的。Disruptor 是 MULTI producer 配置，但本次固定种子基准由单个演示线程调用 Logger，因此它没有测出多个应用线程并发生产时的吞吐表现。

测试**没有**启用 Web/API/SSE，没有并发历史查询，没有连接遥测采样，没有网络 I/O，也没有在多客户端下测量广播。测试没有比较不同 SQLite 参数、不同磁盘、不同 ring buffer 容量或不同处理器组合。因此本报告不对 SSE 性能、SQLite 搜索 QPS、Proxy Remote 遥测容量或多 Worker 方案作容量结论。

## 3. 被测实现与关键配置

| 项目 | 测试值 | 对结果的影响 |
|---|---|---|
| 运行模型 | 一个 Disruptor consumer，线程名 `loglens-consumer` | JSON 编码、文件追加、SQLite 写入和处理器链在单消费路径串行推进 |
| RingBuffer | 8,192 槽 | 限制可暂存的在途事件数；只扩大它不会提升消费者持续处理速度 |
| Producer 类型 | `MULTI` | 实际基准只有一个应用日志生产线程；配置本身不等于做了并发生产压测 |
| 默认溢出策略 | `DROP_NEWEST` | 队列满时不等待，增加 LogLens 的 dropped 计数，调用方继续执行原日志输出 |
| 对照溢出策略 | `BLOCK` | 仅在测试配置中使用；日志生产者等待 ring slot，队列腾出容量后继续发布 |
| 结构化存储 | JSONL + SQLite | `structured.enabled: true`；SQLite 使用 WAL 与 `synchronous=NORMAL` |
| Web | 关闭 | 不包含查询、SSE、水位握手、慢客户端或浏览器开销 |
| 文本输出 | combined 与 level 文件关闭 | 结果不包含额外的 LogLens 文本日志文件写入 |
| Agent 模式 | `observe`、`replace-console` | `replace-console` 在 LogLens 无法接收时回退到原 ConsoleAppender；本测试校验回退数量 |
| Logback 输出 | ConsoleAppender + FileAppender | 三种模式均校验原应用 FileAppender 输出完整 |

被测代码的主要入口见 [ProcessingRuntime.java](../loglens/loglens-runtime/src/main/java/io/loglens/runtime/ProcessingRuntime.java)、[SessionStorage.java](../loglens/loglens-storage/src/main/java/io/loglens/storage/SessionStorage.java) 和 [SqliteIndex.java](../loglens/loglens-storage/src/main/java/io/loglens/storage/SqliteIndex.java)。默认配置样例见 [loglens.yaml](../loglens/loglens.yaml)。

## 4. 测试环境

| 项目 | 记录值 |
|---|---|
| 操作系统 | Windows 10，build 26200 |
| JVM | OpenJDK 64-Bit Server VM，版本 `21.0.6`，使用 IDEA JBR |
| 逻辑处理器 | 20 |
| CPU 型号 | 基准记录未取得（`platform.processor()` 为空） |
| 内存容量与频率 | 未记录 |
| 磁盘型号、接口与文件系统 | 未记录；会话写入到当前工作区所在盘 |
| Java 进程参数 | `-Xms128m -Xmx512m` |
| 日志库 | Logback Classic/Core 1.5.18，SLF4J 2.0.17 |
| 事件数 | 每个模式 100,000 条 |
| 随机种子 | `20261012` |
| Logger bucket | 64 个名称固定的 logger |

CPU 型号、内存和磁盘信息缺失，限制了跨机器解释能力。复测是在同一工作站上进行，不能据此推算云主机、容器配额、云盘或生产服务器的表现。

## 5. 负载和执行方法

### 5.1 负载生成

[PerformanceDemo.java](../loglens/examples/logback-demo/src/main/java/com/example/PerformanceDemo.java) 使用 `SplittableRandom(20261012)` 生成 100,000 次 `INFO` 日志调用。每次调用从 64 个固定 Logger 中随机选择一个，消息模板为 `benchmark payload={}`，参数为随机 `long`。程序在每次 `logger.info(...)` 调用周围读取 `System.nanoTime()`，将调用耗时保存到数组，测试结束后排序并计算 p50、p95、p99 和最大值。

每个模式在独立子 JVM 中运行，依次执行 `baseline`、`observe`、`replace-console`。脚本使用相同的 Logback ConsoleAppender/FileAppender 配置，校验原始应用文件行数；Agent 模式还校验 `captured + dropped == 100,000`、Session `complete == true`，并检查 `replace-console` 的原 Console 回退行数等于 dropped 数。

默认策略结果既包含过去保存在 SDD 证据中的三轮归档数据，也包含 2026-10-20 的一轮复测。复测脚本按顺序运行三个模式，没有额外独立预热阶段，也没有随机化模式顺序。`BLOCK` 测试使用相同脚本和负载，仅将配置中的 `overflowPolicy` 改为 `BLOCK`，分三次独立运行；未修改项目默认配置。

### 5.2 指标定义

| 指标 | 含义 | 解释边界 |
|---|---|---|
| Logger 调用吞吐 | `100,000 / PerformanceDemo` 日志调用循环耗时 | 衡量应用线程在本负载下完成 Logger 调用的速率；`DROP_NEWEST` 下可能包含大量被拒绝的 LogLens 副本 |
| p50/p95/p99/max | 单次 `logger.info(...)` 调用耗时分位数 | 是调用入口延迟，不是磁盘提交延迟，也不是 SSE 到达浏览器的延迟 |
| `captured` | 会话元数据中已捕获日志数 | 成功进入 LogLens 消费与存储路径的事件数 |
| `dropped` | 队列满等原因拒绝的 LogLens 事件数 | 不等同于原业务 Appender 丢行；脚本另行校验原 FileAppender |
| `processCpuNanos` | 测量循环期间 JVM 进程 CPU 时间差 | 含生产与消费线程并发执行，可能大于墙钟；不是单线程 CPU 时间 |
| GC 与堆 | JVM 管理接口采集的 GC 次数/时间及间隔采样峰值堆 | 峰值堆每 1,024 条采样一次，不是连续精确峰值；不含 JVM 全部 native/direct memory |
| `wallNanos` | Python 启动子进程到其退出的墙钟时间 | 包含 JVM 启动、日志循环、队列排空和关闭；不等于 Logger 循环耗时 |
| 存储字节 | 测试目录下 LogLens 文件、会话文件和 SQLite native 库文件大小 | 是测试结束时文件大小，未测长期保留、清理周期或压缩比 |

## 6. 默认 `DROP_NEWEST` 测量结果

### 6.1 归档三轮结果

下表汇总 SDD 中保留原始 JSON 的三轮数据。吞吐和延迟是每个模式的范围；`captured / dropped` 逐轮列出，以便直接看到丢弃情况。

| 模式 | Logger 调用吞吐范围（中位数） | p50 范围 | p95 范围 | p99 范围 | 最大调用延迟范围 | 每轮 captured / dropped | 完整应用 FileAppender |
|---|---:|---:|---:|---:|---:|---|---|
| baseline | 191,530–198,103/s（197,981/s） | 3.9 µs | 7.8–8.1 µs | 18.1–19.1 µs | 3.1–4.1 ms | 无 Agent 采集 | 100,000 行 |
| observe | 32,615–94,378/s（35,980/s） | 6.8–21.7 µs | 23.4–63.0 µs | 68.4–162.3 µs | 6.9–18.0 ms | 8,662/91,338；11,435/88,565；10,648/89,352 | 每轮 100,000 行 |
| replace-console | 19,071–53,168/s（19,740/s） | 12.0–38.9 µs | 44.0–87.9 µs | 91.8–211.1 µs | 7.3–39.2 ms | 16,512/83,488；15,620/84,380；16,065/83,935 | 每轮 100,000 行 |

三轮归档的其他资源指标如下。进程 CPU 时间可能大于墙钟时间，因为生产与消费线程可并行消耗 CPU；峰值堆是采样值，不是精确最大值。

| 模式 | Process CPU 范围（中位数） | GC 次数 / 时间 | 采样峰值堆 | LogLens 会话数据 | 子进程墙钟 |
|---|---:|---:|---:|---:|---:|
| baseline | 1.28–1.36 s（1.30 s） | 3 / 6–7 ms | 79.1–79.2 MB | 0 B | 0.84–0.89 s |
| observe | 5.73–12.63 s（11.86 s） | 9 / 23–38 ms | 84.1–86.0 MB | 10.60–14.00 MB | 10.83–16.04 s |
| replace-console | 10.70–20.67 s（18.92 s） | 18 / 40–86 ms | 84.1–86.1 MB | 20.09–21.21 MB | 12.11–19.07 s |

归档三轮中，`observe` 的采集比例为 8.66%–11.44%，`replace-console` 为 15.62%–16.51%。`replace-console` 的 dropped 事件都通过原 ConsoleAppender 回退；这保护了原控制台输出，但不代表这些事件进入了 LogLens 历史存储。所有轮次 `sessionComplete == true`，表示已接收数据正常排空并关闭，不代表未接收的 dropped 事件被恢复。

三轮间 Agent 模式调用吞吐波动明显：`observe` 最高/最低约 2.9 倍，`replace-console` 约 2.8 倍。baseline 的波动很小。这说明 Agent 模式结果受消费者排空、调度和运行时状态影响较大；不能把中位数包装成稳定 SLA。

### 6.2 2026-10-20 默认策略复测

本次额外运行一轮当前脚本，数据已单独保存为 [performance-20261020-drop.json](../loglens/docs/sdd/evidence/performance-20261020-drop.json)。它提供当前环境的新鲜校验，但样本数只有一轮，不能替代稳定性测试。

| 模式 | Logger 调用吞吐 | p50 / p95 / p99 / max | captured / dropped | 采集比例 | CPU；GC | 峰值堆 | LogLens 会话数据 | 子进程墙钟 | 原 FileAppender |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 208,343/s | 4.2 / 8.2 / 16.6 µs / 3.9 ms | — | — | 1.03 s；3 次/7 ms | 77.9 MB | — | 0.86 s | 3,938,079 B |
| observe | 114,440/s | 6.4 / 15.9 / 49.8 µs / 7.2 ms | 8,663 / 91,337 | 8.66% | 4.44 s；9 次/16 ms | 84.2 MB | 10,603,934 B | 10.47 s | 3,938,079 B |
| replace-console | 41,678/s | 15.1 / 54.8 / 106.7 µs / 8.2 ms | 14,846 / 85,154 | 14.85% | 11.28 s；18 次/52 ms | 85.6 MB | 18,073,013 B | 13.35 s | 3,938,079 B |

这一轮的 `observe` 调用吞吐高于过去归档的三轮上限，但采集数量仍约为 8.7%。这正是不能把调用吞吐直接解释为消费者吞吐的例子：队列满后生产者继续运行，剩余事件被丢弃。`replace-console` 更慢，主要因为队列拒收后还要走原 ConsoleAppender 回退路径。

## 7. `BLOCK` 零丢弃测量结果

三轮数据分别保存在 [BLOCK 第 1 轮](../loglens/docs/sdd/evidence/performance-20261020-block-1.json)、[第 2 轮](../loglens/docs/sdd/evidence/performance-20261020-block-2.json) 和[第 3 轮](../loglens/docs/sdd/evidence/performance-20261020-block-3.json)。每轮每种 Agent 模式均写入 100,000 条、`dropped == 0`、Session 正常完成。该策略仅用于对照，不是当前默认值。

### 7.1 汇总指标

| 模式 | Logger 调用吞吐范围（中位数） | p50 范围 | p95 范围 | p99 范围 | 最大单次调用范围 | 子进程墙钟范围 | captured / dropped |
|---|---:|---:|---:|---:|---:|---:|---:|
| baseline（同轮无 Agent 对照） | 199,666–211,472/s（209,794/s） | 3.8–4.2 µs | 7.4–8.5 µs | 16.3–19.4 µs | 3.13–3.43 ms | 0.80–0.85 s | 无 Agent 采集 |
| observe | 6,873–7,634/s（7,611/s） | 6.3–6.5 µs | 17.0–20.5 µs | 54.7–65.6 µs | 1,098–2,059 ms | 22.48–24.48 s | 每轮 100,000 / 0 |
| replace-console | 7,189–8,100/s（7,749/s） | 7.5–7.8 µs | 19.7–27.1 µs | 83.0–95.2 µs | 656–693 ms | 22.21–24.09 s | 每轮 100,000 / 0 |

Agent 模式下的有效日志调用吞吐约为同轮 baseline 的 3.6%–3.7%，也就是在这组负载中约慢 27 倍。p50/p99 看起来仍是微秒级，但最大调用延迟达到数百毫秒至约两秒；这是 `BLOCK` 在队列满时让应用线程等待的直接结果。对代理网络线程、请求线程或任何不允许长时间停顿的业务线程，不能只根据零丢弃选择 `BLOCK`。

### 7.2 三轮明细

| 策略 | 模式 | 调用吞吐/s | p50 µs | p95 µs | p99 µs | max ms | CPU s | GC 次数/毫秒 | 采样堆 MB | 会话数据 MB | 子进程墙钟 s |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| BLOCK 1 | observe | 6,873 | 6.5 | 19.9 | 65.6 | 2,058.9 | 17.19 | 21 / 42 | 86.9 | 122.0 | 24.48 |
| BLOCK 2 | observe | 7,611 | 6.3 | 17.0 | 54.7 | 1,097.9 | 17.69 | 21 / 42 | 86.2 | 121.8 | 22.55 |
| BLOCK 3 | observe | 7,634 | 6.5 | 20.5 | 59.2 | 1,205.8 | 17.34 | 21 / 40 | 86.2 | 121.9 | 22.48 |
| BLOCK 1 | replace-console | 7,189 | 7.8 | 27.1 | 95.2 | 655.8 | 18.88 | 28 / 57 | 86.1 | 122.0 | 24.09 |
| BLOCK 2 | replace-console | 7,749 | 7.5 | 26.6 | 87.1 | 693.4 | 20.88 | 28 / 55 | 85.9 | 121.8 | 22.74 |
| BLOCK 3 | replace-console | 8,100 | 7.6 | 19.7 | 83.0 | 658.6 | 18.31 | 28 / 50 | 83.8 | 121.9 | 22.21 |

三轮 `BLOCK` 会话数据约为 121.8–122.0 MB，SQLite native 文件约 0.99 MB；原应用 FileAppender 固定为 3,938,079 B。会话数据包含本轮采集和处理所需的 JSONL/SQLite 文件，因此不能简单理解为每条业务日志的最小存储成本。按完整 100,000 条计算，当前测试目录中的 LogLens 会话数据约 1.22 KB/输入事件；这只是本负载的粗略磁盘规划系数。

## 8. 结果解读与容量边界

### 8.1 8,192 是缓冲量，不是处理容量

RingBuffer 容量表示可暂存的在途事件数量。默认 `DROP_NEWEST` 下，生产速率短时间高于消费速率时，空槽会迅速耗尽，新增事件被拒绝。把 RingBuffer 加大只能吸收更长的突发，同时增加内存占用；若持续输入速度仍高于单消费者和存储路径的处理速度，最终仍会填满。

基准里 Logger 循环约一秒就能产生十万条事件，而消费者需要继续工作并排空已经接收的队列。`wallNanos` 显著大于日志调用循环时间，正反映了“应用生产结束”与“LogLens 会话写完”的时间差。排队中的事件必须等待消费、文件追加、SQLite 索引和处理器链完成。

### 8.2 不要把三种速度混为一谈

1. **应用 Logger 调用速率**：应用线程每秒能返回多少次 `logger.info()`。`DROP_NEWEST` 下它可能很高，但其中很多调用没有被 LogLens 接收。
2. **完整采集的端到端速率**：在 `BLOCK` 下，生产者被消费路径反向限速；本轮测得约 6,900–8,100 次调用/秒并完成全量持久化。这是当前最接近“无丢弃参考速度”的指标，但仍不是独立测出的存储引擎极限。
3. **处理结果记录速率**：LogLens 有模板识别、折叠等处理器，输入采集事件数与最终 processed 记录数不必相等。不能只看 `recordSequence` 推断捕获完整性；应同时检查 captured、dropped、processorDropped 和 session 状态。

### 8.3 当前瓶颈判断

实现和测量共同表明，当前路径受单消费者的串行工作约束：事件序列化、JSONL 追加、SQLite 写入、处理器链和必要的刷新都由消费线程推进。SQLite 的 WAL 改善读写并发场景，但单个数据库写入仍然串行。当前基准没有单独拆分 JSONL、SQLite、处理器链和红action 的耗时，因此只能说它们共同位于消费路径上，不能声称某一项已经被证明是唯一瓶颈。

SSE 不在本次压测中启用，无法从这组数据得出“SSE 让吞吐下降多少”。当前 EventHub 发布位于持久化成功之后，主要是向有界客户端队列分发；没有证据表明它是本次每秒数千条无丢弃结果的主因。关闭 SSE 可能减少顺序、水位、重连和慢客户端管理复杂度，但吞吐收益必须通过启用/禁用 SSE 的同机对照测试量化。

### 8.4 当前数据不能证明的事

- 不能证明任意生产机器都能稳定处理 7,000–8,000 条/秒。
- 不能证明 8,192 队列可以无损承载 100,000 条突发；实测默认策略会丢弃大部分 LogLens 采集副本。
- 不能证明 `BLOCK` 适合代理关键线程；实测最大单次调用停顿达到 0.66–2.06 秒。
- 不能推算数百或数千并发 Logger、多个应用实例、长异常栈、MDC、大消息或遥测事件混入时的容量。
- 不能推算 SQLite 历史查询、全文/子串搜索、HTTP API、SSE 广播或浏览器渲染性能。
- 不能从三轮 100k 短负载推导长期磁盘增长、保留策略效果或数小时稳定性。

## 9. 复现步骤

在已构建 LogLens Agent、示例类和 Maven 本地依赖的环境中，从 `loglens/` 目录运行：

```powershell
python scripts/benchmark-modes.py
```

脚本默认使用 `DROP_NEWEST`、100,000 条输入，执行 baseline/observe/replace-console 三种模式，并把明细 JSON 与运行输出写到 `.build/performance/<run-id>/`。通过环境变量调整事件数或溢出策略：

```powershell
$env:LOGLENS_PERF_COUNT = '100000'
$env:LOGLENS_PERF_OVERFLOW_POLICY = 'BLOCK'
python scripts/benchmark-modes.py
Remove-Item Env:LOGLENS_PERF_COUNT
Remove-Item Env:LOGLENS_PERF_OVERFLOW_POLICY
```

`LOGLENS_PERF_OVERFLOW_POLICY` 仅接受 `DROP_NEWEST` 或 `BLOCK`。`BLOCK` 复测只更改基准生成的临时 YAML，不修改 `loglens.yaml` 默认值。报告归档了 2026-10-20 的原始结果 JSON；历史三轮 DROP_NEWEST 原始数据保存在 `loglens/docs/sdd/evidence/performance-run-1.json` 至 `performance-run-3.json`。

为了获得更可靠的容量结论，复跑时还应保存每轮的机器规格、JVM 参数、仓库提交号、磁盘位置、配置文件摘要和运行时间。当前脚本已经固定随机种子并校验丢弃核算与文件完整性，但尚未自动采集全部主机硬件信息。

## 10. 面向企业容量验证的后续测试方案

若要把本报告升级为可用于生产容量承诺的基准，建议保持代码版本和配置固定，并至少补齐以下矩阵：

| 维度 | 建议覆盖 |
|---|---|
| 生产并发度 | 1、4、16、64 个 Logger 调用线程；每线程独立计数并检查错误 |
| 日志体积 | 典型短消息、约 1 KiB、约 4 KiB、最大允许长度；加入异常栈与 MDC |
| 日志分布 | 单一模板高重复、多个模板、不同级别与 logger 数量，分别观察折叠效果 |
| 溢出策略 | `DROP_NEWEST` 与 `BLOCK` 分别测量；报告丢弃率与生产线程阻塞尾延迟 |
| 运行时长 | 预热后至少 10 分钟稳态，多轮重复；另外做突发、持续过载和恢复测试 |
| 存储环境 | 目标主机本地 SSD、目标云盘/容器卷；记录 WAL、磁盘队列、IOPS 与吞吐 |
| Web 负载 | Web 关闭/开启，0、1、10、100 个 SSE 客户端，含慢客户端和断线恢复 |
| Proxy Remote | 活跃连接数和会话采样率分档，测量遥测与日志混合时队列及丢弃情况 |
| 查询负载 | 历史 keyset 分页、筛选、详情 hydration 与并发读写，测量延迟和 SQLite 锁等待 |
| JVM 与主机 | CPU 型号、核数、内存、JDK vendor/build、GC、堆配置、OS、文件系统和容器限制 |

每轮应记录 Logger 调用延迟 p50/p95/p99/p99.9/max、调用吞吐、captured/dropped、队列剩余容量或占用趋势、processed/processorDropped、会话完整性、磁盘增长、SQLite/WAL 大小、CPU、GC、堆和进程退出排空时间。测试应设置预热阶段，随机化模式顺序或分开运行，避免固定顺序造成 JIT 和系统负载偏差。

正式容量值应根据业务允许的丢弃率和调用延迟定义，而不是取一次吞吐最高值。对 `DROP_NEWEST`，要同时给出生产速率和采集率；对 `BLOCK`，要同时给出完整采集速率与生产线程长尾阻塞。还需保留高峰期余量，并用目标部署环境复验。

## 11. 相关文档与原始证据

- [当前消费吞吐、SSE 与多 Worker 取舍](design/loglens-consumer-throughput-and-polling.md)
- [SDD 性能验证记录](../loglens/docs/sdd/verification.md#固定种子-100000-条三模式性能实测)
- [性能需求审计状态](../loglens/docs/sdd/audit.md)：R22 仍要求控制 JIT/系统负载、增加环境与 Java 版本样本，并据此制定及复核目标
- [固定种子基准脚本](../loglens/scripts/benchmark-modes.py)
- [基准负载程序](../loglens/examples/logback-demo/src/main/java/com/example/PerformanceDemo.java)
- [三轮归档原始结果 1](../loglens/docs/sdd/evidence/performance-run-1.json)、[结果 2](../loglens/docs/sdd/evidence/performance-run-2.json)、[结果 3](../loglens/docs/sdd/evidence/performance-run-3.json)
- [2026-10-20 默认策略复测](../loglens/docs/sdd/evidence/performance-20261020-drop.json)
- [2026-10-20 BLOCK 原始结果 1](../loglens/docs/sdd/evidence/performance-20261020-block-1.json)、[结果 2](../loglens/docs/sdd/evidence/performance-20261020-block-2.json)、[结果 3](../loglens/docs/sdd/evidence/performance-20261020-block-3.json)
