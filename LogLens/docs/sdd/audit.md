# 第一版验收审计（进行中）

源规格：requirements.md R01–R24。以下不是完成声明，部分通过只证明列出的证据场景。

| 需求 | 当前证据 | 未完成或不足的证据 |
|---|---|---|
| R01 | JBR21 与 Corretto 17 普通 Java/Boot 实际 premain 启动 | Java 17 仅覆盖 Corretto 17.0.14；其他发行版/版本组合 |
| R02 | 双/多级 Appender、默认 additivity、TurboFilter 拒绝、DEBUG 关闭、reset；JBR21/Corretto17 observe/replace 均单次采集 | 其他 Logback 版本兼容矩阵、非 additive 多层配置 |
| R03 | observe/replace、原 File 保留、reset；真实 Async 包装 Console 子 JVM 回退 observe；固定种子 100k 突发中 replace-console 控制台回退数等于 publish drop 数 | 独立确定性单事件缓冲满子 JVM |
| R04 | EventSnapshotter/RealLogbackSnapshotTest | caller 开启、循环/巨大异常图、对象存活边界 |
| R05 | 嵌套 runtime/bridge 布局、Boot 插件真实运行 | 应用依赖冲突组合与 loader 生命周期 |
| R06 | MULTI concurrency, DROP_NEWEST burst accounting, real BLOCK ring saturation, shutdown rejection of a blocked publisher, full drain of 1,024 accepted events, and complete=true on STOP are verified | None for the R06 first-version contract |
| R07 | Registry 冲突测试；JBR21/Corretto17 子 JVM 加载外部节点工厂和整链；两个不同整链 provider 冲突时明确诊断、默认保留原业务启动、STRICT 终止 | — |
| R08 | ChainRunnerTest 单次/异步约束/失败副本/flush | 插件阻塞超时实际会话不完整、决策解释持久化 |
| R09 | ProcessorTest covers fixed-window timing, error bypass, exception-type grouping, capacity eviction, bounded/zero samples, source-range continuity, group-key collision resistance, named-group extraction/optional misses/MDC override, original-template preference/ordered normalization, highlight FIFO eviction/disable mode, and factory validation | Production default chain composition and remaining regex, field, and configuration boundary matrix need final review |
| R10 | 可控时钟窗口、ERROR、容量、STOP/真实汇总 | 多线程来源范围一致性、强杀未汇总展示 |
| R11 | ConfigLoaderTest 不变性/非法字段/路径/摘要；JBR21 与 Corretto17 实际 strict=true + 非法 YAML 终止子 JVM | 真实运行改配置与重启对照 |
| R12 | Captured/output redaction plus real plugin-derived fields: sensitive configured keys are masked, message patterns are applied to source group values, SQLite/group JSONL/processed JSONL are scanned for sentinel plaintext, and historical source lookup still succeeds | Other adversarial plugin output channels and Throwable/object retention checks |
| R13 | SQLite/JSONL, exact-level text output, real sessions, active/final segment manifest, write-failure health fallback, torn-tail recovery, injected captured-index failure and post-segment processed tags-transaction abort; read-only history rebuild restores both authoritative JSONL rows while session remains incomplete | None for the first-version file-before-index rollback/recovery cases; wider hardware/process-failure coverage remains under R20 |
| R14 | Date/size rotation; maxDisk and retentionDays cleanup; index removal for captured and independent expired group segments; 100k rebuild with complete 100-page keyset traversal; TextExporter re-export | None for the first-version R14 acceptance cases |
| R15 | Read-only HTTP API, Origin/CSP/limits, exact-level filter, inclusive sequence watermark, captured keyset page, real loopback SSE with client-capacity 503 and disconnect slot recovery | Executor saturation/rejection behavior |
| R16 | EventHub overflow; real loopback ready/record/query; raw non-reading TCP socket with 1KiB receive buffer under 32KiB event stream is dropped, healthy socket continues receiving; 500 publishes complete under a 1 s aggregate call bound while deliberate 5 ms pacing keeps the healthy reader active | Cross-batch reconnect/deduplication in a real browser against concurrent history traffic |
| R17 | UI 源码、语法与资源 HTTP；Chrome 实测主题、XSS 文本呈现、详情、筛选、FINAL/CAPTURED_ONLY 分页、SSE 水位衔接和暂停接收/恢复；实现已补日志行 role/可见名称、Enter/Space 激活、`:focus-visible` 样式 | 键盘浏览器断言尚未通过：Playwright 路径 ACL 拒绝，标准库 CDP Chrome 启动后 `Page.enable` 无响应；其他浏览器与真实后端并发压力仍缺证据 |
| R18 | viewer 可执行导出、安全编码与大小测试 | 浏览器导出安全、captured-only UI、历史服务实际只读审计 |
| R19 | Strict configuration failure and normal port shutdown; web bind failure after plugin/storage startup closes plugin and releases full session directory; storage-directory startup failure closes the assembled plugin, preserves the original IOException with node-close failure suppressed, and releases the plugin JAR; stuck-plugin deadline yields incomplete metadata and deferred cleanup after consumer return | Failure injection at remaining runtime startup phases and forced process kill |
| R20 | Output failure health fallback, plugin exception, captured-index insert failure, processed tags transaction abort after JSONL append with rollback/rebuild, slow-client queues, DROP/BLOCK saturation, blocked publisher exit, accepted batch drain, and stuck-plugin timeout/incomplete/deferred cleanup verified | Persistent failures at other disk/index stages, slow-disk behavior and forced-kill cleanup |
| R21 | 模块/API/Agent/viewer/Boot/SPI/README；release 脚本生成并检查 Agent/Viewer/API 三个公开 JAR | 版本化归档/签名等发布流程（第一版未要求） |
| R22 | 固定种子三模式 100k、同主机三次测量调用时延、吞吐、进程 CPU、GC、采样堆、磁盘、丢弃和输出回退；记录显著波动 | 控制 JIT/系统负载、更多硬件与 Java 版本；基于稳定实测制定并复核性能目标 |
| R23 | 没有 Attach/热加载/远程中心实现 | 最终源码与文档交叉检查 |
| R24 | IDEA 脚本 E 盘 cache/tmp/user，实际使用 | 所有验证命令持续核对 C 盘无写入 |

## Proxy Remote 扩展验收（R25–R34）

本节补充连接监控扩展的当前证据；不改变上表 R01–R24 的基础需求状态，也不关闭 G2–G7。

| 需求 | 当前证据 | 未完成或不足的证据 |
|---|---|---|
| R25 | Java 17/21 Agent 子 JVM 与真实 ProxyRemoteServer 烟测；实际 systemd proxy-remote.service 使用 Corretto 17.0.20 启动并通过健康检查 | Java 8 真实 JVM 未安装；其他服务端版本未验证 |
| R26 | Agent/Netty 生命周期测试；线上活动连接 API 在部署验收时返回 16 项 | 生产验收只核对数量，没有读取或核实单条 peer/transport/关闭字段 |
| R27 | 实际 SimplePlane 编译的 SessionManager/OutboundSession + EmbeddedChannel 验证 open/active/close 与 uplink=12/downlink=7 | 未在生产连接上检查逐会话字节最终值 |
| R28 | Advice 只传结构化遥测，字节切点只读取数组长度；测试 JSONL 不包含 payload | 仍依赖目标服务版本维持匹配方法语义 |
| R29 | 共用 Agent、bridge、Disruptor、会话目录和 UI 的实现及队列饱和/排空测试 | 无 |
| R30 | 遥测 JSONL/SQLite、查询/SSE、gap 恢复、loopback 与 Host/Origin 测试；实际服务健康和本地 API 可达 | 真实公网网络边界由部署环境另行负责；面板必须通过隧道访问 |
| R31 | Chrome 连接页交互 smoke；实际部署页面 HTTP 200，活动连接 API 返回数据 | 未在生产环境验收历史筛选、Stream-to-log 检索和真实流量图表 |
| R32 | Web 绑定服务器 loopback，SSH 隧道访问，未开放公网监听 | 服务器防火墙和 SSH 账号策略不属于 Agent 自身保证 |
| R33 | 共享队列饱和、非阻塞遥测发布、shutdown drain 与 drop 计数测试通过 | 真实线上峰值负载与长期丢弃率尚未测量 |
| R34 | Java 17/21 Agent 验证；生产 Corretto 17.0.20 实际启动；Java 8 兼容守卫测试 | Java 8 行为仅测试守卫分支，未在 Java 8 JVM 上实跑 |

## 优先待办

1. 修正已发现的需求缺口（决策持久化、元数据片段、历史未完成视图等），先维护契约。
2. 执行浏览器与大历史验证，修复 UI 水位/筛选/分页边界。
3. 满缓冲、故障、停机/强杀、插件冲突与 Java17 兼容验证。
4. 性能三模式固定种子实验，记录全部指标及环境。
5. 最终逐条审计，所有不足证据补齐后才能标 G7/目标完成。

绿色 Maven reactor 和小规模子 JVM 脚本不能替代这些门禁。
