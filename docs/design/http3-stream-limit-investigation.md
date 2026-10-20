# HTTP/3 Stream 额度耗尽复现报告

> 日期：2026-10-20。基线：`fcd6570`；仅新增诊断测试，生产代码未修改。
>
> 本报告记录本地真实 QUIC/HTTP/3 实验。线上服务未停止、替换或压测；本地复现能够证明当前代码存在该问题，但不能替代线上连接的逐条诊断。

## 1. 已确认的问题

**实际目标 TCP 连接可以已经全部关闭，QUIC 的双向 stream 额度却没有恢复。**

正常 CONNECT → DISCONNECT 流程也能复现。因此 `QUICHE_ERR_STREAM_LIMIT` 不意味着用户真的同时保持了上千条业务连接，也不能靠客户端 stream 映射为空来证明协议层已经完成释放。

核心证据是：服务端上限设为 32，串行创建连接，每次完成后断开目标连接，目标 TCP 同时活跃峰值仅为 1；累计完成 32 条后，第 33 条无法建立。此时客户端活跃 stream 计数为 0、目标 TCP 为 0，但服务端仍有 32 个 request stream，客户端观测到对端允许新建的双向 stream 额度为 0。

## 2. 环境与测试边界

| 项目 | 实际使用 |
|---|---|
| 执行环境 | Windows 工作区，WSL Ubuntu 22.04 执行 Linux 原生 QUIC |
| Java | OpenJDK 17.0.18 |
| QUIC 依赖 | Netty `netty-codec-classes-quic` 4.2.18.Final 及 Linux native 实现 |
| 服务端 | 实际 `Http3Server → ExchangeHandler → DispatchInvoker → OutboundConnector` |
| 客户端 | 实际 `HeaderExchangeClient → Http3Client → Http3Connection` |
| 目标服务 | 本地 Netty TCP 服务，可主动关闭或拒绝连接 |
| 连接 | 每个场景建立一条独立 QUIC 连接，场景内部不重连、不轮换节点 |
| 请求方式 | 串行 CONNECT，按场景关闭；每轮后等待 10ms，避免瞬时建流突发 |
| 基础矩阵 | 服务端双向 stream 上限 32，计划循环 160 次 |
| 加密 | 基础矩阵 `none`；真实上限复测单独记录套件 |

这是生命周期诊断，不是业务吞吐测试。测试直接驱动 Exchange 层，没有实际启动浏览器或完整的 `proxy-local` HTTP/SOCKS 入口，也没有覆盖 Android 原生数据面、远端网络丢包或公网 NAT。

## 3. 观测项

测试在场景结束、关闭测试客户端之前采集：

- `completed`：完成预期 CONNECT/关闭动作的循环数。
- `clientStreams`：客户端当前活跃的 request stream 数量。
- `serverStreams`：服务端 Exchange pipeline 对应的尚未关闭的 request channel 数量。
- `serverSessions`：实际 SessionManager 中的条目数量，包括尚未移除的 CLOSED session。
- `targetActive / targetPeak`：目标 TCP 当前活跃数及场景峰值。
- `backendConnects`：真正到达服务端 Invoker 的 CONNECT 数量，防止把本地超时误计为成功建流。
- `peerCredit`：在 QUIC EventLoop 读取 `peerAllowedStreams(BIDIRECTIONAL)`，表示当前还允许新建的双向流数量。

服务端会话表与客户端内部连接通过测试代码反射读取，未为诊断增加生产 API。所有测试创建的服务、连接和线程在 finally 中关闭。

## 4. 32 上限场景矩阵

| 场景 | 完成 / 计划 | 客户端 stream | 服务端 stream | 服务端 session | 目标 TCP 活跃 / 峰值 | 剩余额度 |
|---|---:|---:|---:|---:|---:|---:|
| 正常 CONNECT → DISCONNECT | 32 / 160 | 0 | 32 | 0 | 0 / 1 | 0 |
| CONNECT 后直接 `closeStream()` | 32 / 160 | 0 | 32 | 32 | 32 / 32 | 0 |
| 目标 TCP 主动关闭 | 32 / 160 | 32 | 32 | 32 | 0 / 1 | 0 |
| 目标端口拒绝连接 | 32 / 160 | 32 | 32 | 0 | 0 / 0 | 0 |
| 服务端不返回 CONNECT 响应 | 32 / 160 | 32 | 32 | 0 | 0 / 0 | 0 |
| 对照：DISCONNECT + 服务端输入关闭后主动 close | 160 / 160 | 0 | 0 | 0 | 0 / 1 | 22 |
| 对照：客户端强制双向 shutdown/reset | 160 / 160 | 0 | 0 | 160 | 160 / 160 | 22 |

前五项的断言失败是有意保留的复现证据，不能表述为“测试全部通过”。最后两项是仅在测试中施加的干预，不是生产修复。

### 4.1 正常断开为什么仍耗尽

正常 DISCONNECT 已完成目标连接与 session 清理，`HeaderExchangeClient` 也调用了客户端 `closeStream()`。但服务端没有在输入方向结束后同步结束输出方向，服务端 request channel 仍存活。客户端本地移除状态并不代表双方的 QUIC 流生命周期已经结束。

测试在服务端 ExchangeHandler 中增加输入关闭事件处理，仅执行 `ctx.close()`，就能连续完成超过初始上限的连接。这个对照把问题进一步定位到双端关流配合，而不是目标 TCP 同时连接数、加大 stream 上限或客户端业务映射大小。

这里记录的是 Netty/QUIC 当前实际行为，不把单个 Java `Channel.closeFuture()` 完成当作协议额度已恢复的充分条件。

### 4.2 为什么不能只强制 reset

强制双向 shutdown/reset 后，协议额度可以恢复，但测试结束时仍有 160 条目标 TCP 连接和 160 个服务端 session 等待处理。这说明“恢复建流能力”和“立即释放远端目标资源”是两个验收目标。

该场景没有发送业务 DISCONNECT，现有服务端主要依靠每 10 秒的扫描清理已经失活的入站 session。不能因为不再报 STREAM_LIMIT 就认为资源释放完整。

### 4.3 CONNECT 超时的特殊说明

该场景由测试 Invoker 收到 CONNECT 后保持 Future 未完成，客户端控制请求超时设为 100ms。上限耗尽后的请求同样会超时，因此测试额外验证 CONNECT 是否真正到达服务端；没有到达时立即停止并报告，不计入成功循环。

初版诊断曾只统计本地超时次数，无法区分是否建流成功；最终矩阵使用 `backendConnects` 校正后的结果。

### 4.4 真实 1000 上限与 ChaCha 套件复测

为排除“仅把阈值调小才出现”的疑问，使用当前兼容的 `chacha20` 套件、服务端上限 1000，同一 QUIC 连接计划串行创建 1100 次：

| 场景 | 完成 / 计划 | 客户端 stream | 服务端 stream | 服务端 session | 目标 TCP 活跃 / 峰值 | 剩余额度 |
|---|---:|---:|---:|---:|---:|---:|
| 原实现正常 DISCONNECT | 1000 / 1100 | 0 | 1000 | 0 | 0 / 1 | 0 |
| 测试中增加对端输入关闭后的 close | 1100 / 1100 | 0 | 0 | 0 | 0 / 1 | 898 |

原实现第 1001 次尝试出现 `QUICHE_ERR_STREAM_LIMIT`；客户端控制请求随后表现为约 1.5 秒超时。对照组的 1100 次 CONNECT 均到达服务端并成功完成，未依赖重连 QUIC 父连接来重置额度。

剩余额度是采样瞬间值，MAX_STREAMS 更新可能分批进行；不要求结束时立即等于配置的初始值。对照成功的依据是累计建流超过初始额度、两端 stream 与目标资源归零，并保有可用额度。

### 4.5 等待超过 10 秒清理周期的复测

另用 `aes-gcm-v2`、上限 32，在循环结束后额外等待 12 秒再采样：

| 场景 | 完成 / 计划 | 服务端 stream | 服务端 session | 目标 TCP 活跃 | 剩余额度 |
|---|---:|---:|---:|---:|---:|
| 原实现正常 DISCONNECT，等待 12 秒 | 32 / 160 | 32 | 0 | 0 | 0 |
| 强制双向 reset，等待 12 秒 | 160 / 160 | 0 | 0 | 0 | 22 |

正常断开的 stream 残留没有被现有扫描回收，不能解释成“等几秒就会恢复”的短暂延迟。强制 reset 的目标连接则被现有扫描最终清理，但采样前峰值达到 160，说明它只证明了延迟兜底有效，并不能代替及时释放。

该复测也说明问题不局限于旧 ChaCha 套件。三组实验分别覆盖 `none`、`chacha20` 与 `aes-gcm-v2`，但不是每一种场景都对全部套件做了排列组合。

## 5. 根因涉及的代码路径

| 文件 | 当前缺口 |
|---|---|
| `proxy-transport-http3/.../handler/Http3StreamHandler.java` | 缺少对完整流输入关闭事件的对等结束处理；当前主要在 channelInactive 执行回调 |
| `proxy-transport-http3/.../Http3Client.java` | closeStream 清理本地状态并 close channel，不能单独保证远端结束另一方向；失败事件也没有完整传递到浏览器流生命周期 |
| `proxy-exchange/.../header/HeaderExchangeClient.java` | DISCONNECT 完成后关闭 stream，但 CONNECT 失败/超时没有同等的结束处理 |
| `proxy-local/.../handler/HttpConnectHandler.java`、`Socks5ConnectHandler.java` | CONNECT 失败时主要注销浏览器映射并关闭浏览器；传输 stream 仍需显式清理 |
| `proxy-remote/.../outbound/OutboundHandler.java` | 目标主动断开只 close session，没有串联 session 移除、通知客户端与入站 stream 结束 |
| `proxy-remote/.../outbound/SessionManager.java` | 10 秒兜底扫描只检查入站 channel 活跃性；入站仍活跃时 CLOSED session 不能由此及时清理 |

另外静态审查发现，目标连接迟到成功与 session 关闭之间存在竞态，仍需单独测试；本矩阵不把它当成已经通过实验确认的 STREAM_LIMIT 主因。

## 6. 可重复运行

测试位于 `proxy-remote/src/test/java/com/proxy/remote/integration/Http3OutboundIntegrationTest.java` 的 `streamCreditsMustBeRecycledWithSequentialConnections` 方法。它通过系统属性显式开启，普通测试默认跳过诊断矩阵。

在具备 Maven、JDK 17 和 Linux QUIC native 依赖的 Linux 环境，仓库根目录执行：

```bash
mvn -B -pl proxy-remote -am test \
  '-Dtest=Http3OutboundIntegrationTest#streamCreditsMustBeRecycledWithSequentialConnections' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhttp3.lifecycle.probe=true
```

可调属性：

| 属性 | 默认值 | 含义 |
|---|---|---|
| `http3.lifecycle.limit` | 32 | 服务端单 QUIC 连接双向 stream 初始上限 |
| `http3.lifecycle.cycles` | 上限 × 5 | 计划串行循环数 |
| `http3.lifecycle.mode` | all | 场景名称，支持逗号分隔 |
| `http3.lifecycle.cipher` | none | 两端共同使用的加密套件，测试固定密钥仅用于本地 |
| `http3.lifecycle.settleMs` | 0 | 结束采样前额外等待，观察现有定期扫描 |

真实 1000 上限对照命令：

```bash
mvn -B -pl proxy-remote -am test \
  '-Dtest=Http3OutboundIntegrationTest#streamCreditsMustBeRecycledWithSequentialConnections' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dhttp3.lifecycle.probe=true \
  -Dhttp3.lifecycle.limit=1000 \
  -Dhttp3.lifecycle.cycles=1100 \
  '-Dhttp3.lifecycle.mode=disconnect,disconnect-peer-close' \
  -Dhttp3.lifecycle.cipher=chacha20
```

复现成功时 Maven 应报告相应失败，而不是以绿色构建掩盖问题。场景结束的 `LIFECYCLE` 行与 `QUICHE_ERR_STREAM_LIMIT` 日志用于核对具体数据。

## 7. 后续修复的验收要求

修复应优先闭合两端生命周期，不改成简单放大上限：

1. 正常 DISCONNECT 响应送出后，两端结束 stream，确认后续建流额度持续恢复。
2. 输入 FIN/reset、目标主动断开、浏览器取消、CONNECT 失败与超时均触发对应流的幂等清理。
3. 在途 CONNECT 和迟到成功不能重新激活已关闭 session，也不能留下无人管理的目标 socket。
4. 移除会话、关闭目标 socket、结束入站流以及通知浏览器形成闭环，不只删除 Map 条目。
5. 关闭单条流不能失败其他节点或其他流的控制请求；传输连接可以继续复用。
6. 在同一 QUIC 连接上以低业务并发累计完成远超 1000 次循环，并验证两端流、session、目标 socket 与 pending Future 回落。

修复提交前，这份报告只能说明“问题已复现与定位”，不能说明线上故障已经解决。

## 8. 证据与原有测试回归

本地原始日志保存在工作区 `.build/quic-lifecycle/`，该目录为本机测试产物，不属于默认提交内容：

| 文件 | 内容 |
|---|---|
| `matrix-32.log` | 7 场景完整矩阵，5 个复现断言失败、2 个干预对照通过 |
| `limit-1000-chacha20.log`、`limit-1000-chacha20.xml` | 1000 上限与 1100 次循环对照，保留 Maven 与 JUnit 结果 |
| `settle-12s-aes-gcm-v2.log` | 超过 10 秒扫描周期的等待复测 |
| `original-outbound-regression.log` | 关闭诊断矩阵后，原有 HTTP/3 出站集成测试回归 |

原有出站集成测试在 `none`、`chacha20-poly1305-v2`、`aes-gcm-v2` 三个套件下，各完成真实目标 TCP 的 `2MiB + 1` 字节回传比对；结果 **3 项通过、0 失败，诊断方法 1 项跳过，BUILD SUCCESS**。这只证明原有大数据互通测试仍通过，不代表本次发现的 stream 生命周期问题已解决。
