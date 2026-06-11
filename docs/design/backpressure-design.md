# 应用层背压设计：从 TCP 到 HTTP/2 到应用层的三级流控体系

## 背景

在高吞吐代理场景下，当远端服务器处理速度跟不上客户端发送速度时，数据会在各层缓冲区中堆积。单纯依靠 TCP 层面的自然背压（接收缓冲区满 → 停止 ACK → 对端发送窗口收缩）虽然最终能生效，但反应链路太长、粒度太粗。我们需要一套**应用层面的背压机制**，在数据堆积的早期就介入控制，避免内存被无节制地吞噬。

## 三级流控的层次关系

整个数据通路上存在三道流控闸门，从底层到上层依次是：

**第一层：TCP 流控（内核态）**

TCP 的流控由两个独立的窗口共同约束发送方的实际发送速率：**接收窗口（rwnd）** 和 **拥塞窗口（cwnd）**。发送方在任意时刻能发出的未确认数据量 = `min(rwnd, cwnd)`。两者各自有独立的生命周期和调整逻辑。

**连接建立阶段（三次握手）：Window Scale 的协商**

TCP 报文头的 Window 字段只有 16 bit，裸值最大只能表示 65535 字节。在高带宽链路上这远远不够（一条 1Gbps × 10ms RTT 的链路，BDP 就有 1.25MB），于是 RFC 1323 引入了 Window Scale 选项。

协商过程如下：主动发起方在 SYN 报文的 TCP Options 中携带 `Window Scale: shift_count`，被动方在 SYN-ACK 中同样携带自己的 shift count。**双方的 shift count 是各自独立声明的**——A 声明的 shift count 用于 A 后续通告自己接收窗口时的缩放，B 声明的用于 B 通告自己接收窗口时的缩放，两者可以不同。

一旦三次握手完成，各自的 shift count 在整个连接生命周期内固定不变。后续每个 ACK 报文头部的 16-bit Window 字段值左移对应 shift count 位，得到真实接收窗口大小：`实际窗口 = Window字段值 << shift_count`。

**shift count 的计算方式（以 macOS 为例）：** 内核在发 SYN/SYN-ACK 时，需要选择一个足以表达将来可能达到的最大接收窗口的 shift count。macOS 内核根据 `autorcvbufmax`（接收缓冲区自动调优的上限，默认 4MB）来计算——它找到能让 `65535 << shift >= autorcvbufmax` 成立的最小 shift 值。对于 4MB（4194304 字节）：`65535 << 6 = 4194240`（不够），`65535 << 7 = 8388480`（够了），所以实际协商的 shift count = 7。这意味着 16-bit Window 字段能表达的最大窗口为 `65535 << 7 ≈ 8MB`，足够覆盖 auto-tuning 涨到 4MB 的需求。

注意：macOS 的 `net.inet.tcp.win_scale_factor = 3` 这个参数**不是** SYN 中携带的 shift count。它是内核 auto-tuning 算法内部使用的一个增长速率乘数，控制接收缓冲区随吞吐量增长的速度。

Window Scale 选项必须双方都支持才生效。如果任一方的 SYN 不携带该选项，则双方都回退到无缩放模式（窗口上限 64KB）。现代操作系统（macOS、Linux、Windows）默认全部启用。

**连接建立后：接收窗口的初始值**

握手完成后，接收方第一次通告的窗口大小 = 接收缓冲区的可用空间。macOS 的初始接收缓冲区由 `net.inet.tcp.recvspace = 131072` 决定，即 128KB。所以连接刚建立时，对端看到的接收窗口约为 128KB。注意 SYN/SYN-ACK 报文本身的 Window 字段不应用缩放（RFC 1323 明确规定），握手完成后的第一个纯 ACK 开始才应用缩放。

**数据传输阶段：接收窗口（rwnd）的变化**

接收窗口的值始终等于**接收缓冲区中当前的剩余可用空间**。内核在构造每个 ACK 报文时，取"接收缓冲区总大小 − 已接收但尚未被应用层 `read()` 取走的数据量"，再右移 shift count 位后填入 Window 字段。因此 rwnd 是逐 ACK 实时变化的：

- 应用层消费速度 ≥ 数据到达速度 → 缓冲区不堆积 → rwnd 保持在接近缓冲区总大小的水平
- 应用层消费速度 < 数据到达速度 → 缓冲区堆积 → rwnd 逐渐缩小
- 应用层完全停止消费 → 缓冲区填满 → rwnd 降到 0

同时，内核实现了 **TCP 接收缓冲区自动调优（auto-tuning）**。它的工作方式是：内核持续监测每条连接上应用层的消费速率和链路的带宽延迟积（BDP = bandwidth × RTT）。当判断"当前缓冲区大小 < BDP"即缓冲区成为吞吐瓶颈时，内核主动扩大该连接的接收缓冲区。缓冲区变大意味着可用空间增大，下一个 ACK 通告的窗口值也随之增大，发送方就能发更多数据。macOS 上这个自动扩大的上限为 `net.inet.tcp.autorcvbufmax = 4194304`（4MB），由小到大的增长过程由 `win_scale_factor` 控制增速。整个过程对应用层完全透明。

**数据传输阶段：拥塞窗口（cwnd）的变化**

cwnd 由发送方内核单方面维护，接收方完全看不到。它反映的是"发送方对当前网络拥塞程度的估计"，调整遵循拥塞控制算法（macOS 使用 Cubic，Linux 可选 Cubic/BBR 等）：

- **慢启动（Slow Start）：** 连接建立后，cwnd 从初始拥塞窗口（IW）开始。macOS 的 `local_slowstart_flightsize = 8` 个 MSS（约 11.6KB），公网连接类似（现代内核一般为 10 个 MSS ≈ 14.6KB）。慢启动阶段每收到一个确认了新数据的 ACK，cwnd 增加一个 MSS——宏观效果是每经过一个 RTT，cwnd 翻倍（指数增长）。
- **拥塞避免（Congestion Avoidance）：** 当 cwnd 增长到达慢启动阈值 ssthresh 时，切换到线性增长——每个 RTT 内 cwnd 仅增加约一个 MSS。
- **丢包响应：** 检测到丢包（三次重复 ACK 触发快速重传，或 RTO 超时触发超时重传）时，Cubic 算法将 cwnd 乘以 β（默认 0.7），ssthresh 设为新的 cwnd。如果是超时重传则更激进——cwnd 直接重置为 1 MSS，重新慢启动。

因此，一条新建 TCP 连接的起始发送速率受 IW 限制（约 14KB/RTT），需要经历多个 RTT 的指数增长才能逐步逼近链路带宽。这也是 HTTP/2 单连接复用的核心优势之一——连接保持活跃，cwnd 已经"热"在高位，不需要每次请求都从慢启动爬起。

**背压触发：Zero Window**

当接收方的接收缓冲区被完全填满（应用层持续不调用 `read()`），ACK 中通告的 Window 值降到 0。发送方收到这个 Zero Window 通告后**必须停止发送任何新的数据段**（但已发未确认的段继续等待 ACK）。

为了避免死锁（接收方恢复了空间但 ACK 丢失导致发送方永远不知道），发送方启动**持续定时器（Persist Timer）**，以指数退避的间隔（初始约 500ms，最大 60s）周期性发送**窗口探测段（Window Probe）**——这是一个仅含 1 字节数据的段，强制接收方回复一个 ACK 以通告当前窗口值。一旦接收方回复非零窗口，发送方恢复正常发送。

这就是 TCP 层面的自然背压：最终是应用层的消费能力决定了通告窗口的大小，进而控制对端的发送速率。但问题在于，从应用层停止消费到对端感知到 Zero Window 并停止发送，中间隔了"缓冲区填满"这个过程——在接收缓冲区高达 4MB 的情况下，这意味着 4MB 数据已经堆积在内核中。这就是为什么我们需要更靠近应用层的、更早介入的背压机制。

**第二层：HTTP/2 流控窗口（传输层）**

RFC 7540 规定 HTTP/2 的默认流控窗口为 **65535 字节**（64KB - 1），连接级和 stream 级各自独立维护。本项目中做了两处扩大：stream 级通过 `initialWindowSize(1024 * 1024)` 设为 1MB；连接级在连接建立后通过 `incrementWindowSize` 手动拉到约 16MB。Netty 的 `Http2FrameCodec` 默认行为是在应用层读取 DATA 帧后自动发送 WINDOW_UPDATE 帧归还窗口字节，对端收到后恢复发送额度。

**第三层：应用层信用额度（业务层）**

本次设计的 `BackpressureHandler`，通过维护一个信用额度计数器（permits），对进入应用处理流程的 DATA 帧数量做精确控制。默认 64 permits，考虑到 HTTP/2 默认最大帧大小为 16384 字节（16KB），64 帧 × 16KB = 1MB，正好与 stream 级窗口大小对齐。

## 各层窗口大小对比

| 层级 | 窗口/额度 | RFC/系统默认值 | 本项目实际值 |
|------|-----------|---------------|-------------|
| TCP rwnd（接收窗口） | 内核 auto-tuning | 初始 128KB，auto-tuning 上限 4MB（macOS），shift=7 可表达最大 ~8MB | 由内核根据 BDP 自动调整 |
| TCP cwnd（拥塞窗口） | 发送方维护 | IW = 8~10 MSS（约 12~14KB），慢启动指数增长 | 稳态后由 Cubic 算法动态控制 |
| HTTP/2 连接级窗口 | WINDOW_UPDATE 控制 | 65535 字节 | ~16MB |
| HTTP/2 stream 级窗口 | SETTINGS + WINDOW_UPDATE | 65535 字节 | 1MB |
| 应用层 credit | BackpressureHandler | — | 64 帧 ≈ 1MB |

从数值关系来看，HTTP/2 连接级窗口（16MB）远大于 TCP 接收窗口的 auto-tuning 上限（4MB），这意味着 HTTP/2 层面基本不会成为流控瓶颈。而应用层 credit 约 1MB 是最紧的那道闸门，它在数据量远未触及 TCP 和 HTTP/2 层限制时就提前介入。这个"越靠近业务层、闸门越紧、响应越快"的梯度设计确保了内存消耗可控，同时不干扰正常吞吐。

## HTTP/2 窗口更新的默认行为

Netty 的 `Http2FrameCodec` 内部持有 `DefaultHttp2LocalFlowController`，其默认行为是：

1. 当 DATA 帧从 stream channel 被读出（到达 `ProxyMessageDecoder`）时，flow controller 将这些字节标记为"已消费"
2. 已消费字节累积超过窗口大小的一半时，自动组装 WINDOW_UPDATE 帧发回客户端
3. 客户端收到 WINDOW_UPDATE 后，其可发送窗口增大，恢复发送

整个过程对业务代码完全透明。这也是为什么"在 Filter 层拦截已经来不及了"——帧到达 stream channel 的那一刻，窗口字节已经被消费，WINDOW_UPDATE 可能已经在回去的路上了。

## 窗口恢复机制

HTTP/2 的流控窗口本质是一个"可用额度计数器"：对端发数据时减小，收到 WINDOW_UPDATE 时增大。启动时通过 `incrementWindowSize` 设置的 16MB 是窗口容量上限，不是一次性资源。恢复时不需要再次手动调用 `incrementWindowSize`，Netty 自动发送的 WINDOW_UPDATE 帧就能让窗口逐步回到满额状态。

具体恢复流程：`setAutoRead(true)` → Netty 恢复从 socket 读取 → 消费积压在 TCP 缓冲区中的帧 → flow controller 发出 WINDOW_UPDATE → 客户端窗口回升 → 恢复正常发送。

## 背压触发的信号传导路径

当应用层额度耗尽时，信号从上层逐级向下传导：

```
BackpressureHandler: credits = 0
    │
    ▼ setAutoRead(false) on parent channel
    │
Netty EventLoop: 停止对 socket 调 read()，不再解码新的 HTTP/2 帧
    │
    ▼ Netty 无新帧可消费，不再发送 HTTP/2 WINDOW_UPDATE 帧
    │
    ▼ 同时，TCP 接收缓冲区中数据持续堆积（内核仍在从网卡收数据）
    │
    ▼ 接收缓冲区剩余空间缩小，内核在 ACK 中通告的 TCP 窗口值随之减小
    │
    ▼ 对端可用的 HTTP/2 发送窗口耗尽（因无 WINDOW_UPDATE 补充）
    │
    ▼ 对端可用的 TCP 发送窗口也在缩小（因接收方通告窗口减小）
    │
客户端: 双重约束下停止发送，数据阻塞在本地发送队列中
```

恢复时反向传导：`release()` 归还额度 → 排空队列 → `setAutoRead(true)` → Netty 恢复读取 → WINDOW_UPDATE 发出 → 客户端恢复发送。

## 设计决策

**为什么不用 Filter 做背压？**

`@Activate` + Filter 机制作用在 `ExchangeHandler` 之后的业务调用链上，操作的是 `Invocation` 对象。但到达 Filter 时 HTTP/2 DATA 帧已经被完全解码并消费了窗口字节。背压需要在更早的位置——Netty Pipeline 中 `ProxyMessageDecoder` 之前——拦截原始帧，才能真正控制"不发 WINDOW_UPDATE"。

**为什么用 `setAutoRead(false)` 而不是手动 `consumeBytes`？**

Netty 4.1 的 Frame API 模式下，`Http2FrameCodec` 会在帧到达 stream channel 时自动消费窗口字节。虽然理论上可以通过 `autoAckReceivedData(false)` + 手动写 `Http2WindowUpdateFrame` 来实现更精细的控制，但 `setAutoRead(false)` 更简单直接——它从源头卡住了帧的读入，Netty 根本没有机会消费新的窗口字节。在单连接模型下，连接级暂停就是全局暂停，语义完全匹配。

**为什么放在 `proxy-transport-netty` 模块而不是新建模块？**

这是一个纯传输层组件——控制 HTTP/2 窗口、拦截 DATA 帧、管理字节级流控。它只依赖 Netty 的 HTTP/2 API，与 `ProxyMessageDecoder`、`HeartbeatHandler` 是同级的 ChannelHandler。不需要引入额外依赖，也不需要 SPI 注册，通过配置项 `backpressure=true` 决定是否挂载到 pipeline 即可。

**为什么不需要修改 `Http2FrameCodecBuilder`？**

`setAutoRead(false)` 作用在 parent channel 上，阻止了新帧从 TCP socket 被读入。既然没有新帧进来，Netty 的自动窗口消费机制就无帧可消费，等效于"不发 WINDOW_UPDATE"。已经进入队列的帧其窗口字节确实已被消费（存在一批的滞后），但这个误差量（≤ maxPermits × 16KB）是可控的。

## 配置参考

在 `remote.yml` 中：

```yaml
# 应用层背压（默认关闭）
backpressure: false
backpressurePermits: 64
```

`backpressurePermits` 的选择建议：值太小会频繁触发背压影响吞吐，值太大则失去提前介入的意义。默认 64 对应约 1MB 的在途数据量，在大多数场景下是一个合理的平衡点。如果目标服务器响应较慢（如数据库查询），可以适当调小到 32 或 16；如果是高吞吐的 CDN 回源场景，可以适当调大到 128 或 256。
