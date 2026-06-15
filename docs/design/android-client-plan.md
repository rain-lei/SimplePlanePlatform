# Android 客户端（手机梯子）— 技术设计方案

| 属性     | 内容                                                          |
| -------- | ----------------------------------------------------------- |
| 文档版本 | v1.0                                                        |
| 项目名称 | SimplePlanePlatform Android 客户端                          |
| 所属模块 | android-app (Kotlin) + plane-core (Rust，复用 tun-adapter) |
| 作者     | zhanghonghao                                                |
| 状态     | Planning                                                    |
| 关联文档 | docs/design/tun-mode-plan.md                                |

---

## 1. 背景与目标

### 1.1 动机

现有 SimplePlanePlatform 已具备桌面端（macOS/Windows/Linux）的完整代理能力：proxy-local 接收 SOCKS5/HTTP CONNECT，经 Filter 链 + 集群容错 + HTTP/2 多路复用 + AEAD 加密发往 proxy-remote。tun-adapter（Rust）实现了系统级 TUN 透明代理。

但这套方案完全无法直接搬到手机上：

- Android 普通 App 无权修改全局系统代理设置。
- Android 普通 App 无权创建 TUN 设备或修改路由表（需要 root）。
- Rust tun-adapter 依赖 `utun`/`WinTUN`、`networksetup`/`netsh`、修改路由表，这些在 Android 上都不存在。
- Java/Netty 在 Android 上虽能运行，但包体积大、对移动端不友好，不适合作为手机端入口。

### 1.2 核心思路

Android 官方提供 `VpnService` API——这正是所有手机梯子 App（Clash for Android、v2rayNG、Shadowsocks）的工作原理。它的本质是：**用户授权一次后，系统创建一个虚拟网卡并把全机流量导到一个 TUN 文件描述符（fd）上，交给 App 处理。无需 root。**

这与你现有 tun-adapter 做的事情几乎一模一样，唯一区别是：

| 维度 | 桌面 tun-adapter | Android |
| ---- | --------------- | ------- |
| TUN fd 来源 | 自己创建 utun/WinTUN | 系统 VpnService 递给你 |
| 路由表 | 自己用 route/netsh 改 | 系统根据 VpnService.Builder 配置自动管理 |
| 回环防护 | 自己排除路由/fwmark | `Builder.addDisallowedApplication(自身)` + protect(socket) |
| DNS 分流 | /etc/resolver、NRPT | `Builder.addDnsServer` + FakeDNS |

### 1.3 设计目标

1. 用 Android `VpnService` 实现全局透明代理，无需 root。
2. **最大化复用现有 Rust 代码**：`stack` / `fake_dns` / `router` / `config` 几乎零改动，仅替换平台耦合层与出站层。
3. **Android 端直连 proxy-remote**：手机上不运行 Java proxy-local，新增 Rust 实现的"加密 HTTP/2 出站"，对接 proxy-remote。
4. 复用既有的健康检查 + 自动降级思想（代理不可用时直连，"宁可不代理，也不能断网"）。
5. 真正的国内直连 / 国外代理分流（手机网络切换频繁，全量绕远程不可接受）。
6. 提供最小但可用的 UI：开关、节点配置、连接状态、流量统计。

---

## 2. 整体架构

### 2.1 分层架构全景

```
┌──────────────────────────────────────────────────────────────────┐
│                       Android 应用层                                │
│        浏览器 / 微信 / 任意 App （正常 socket 调用，无感知）        │
└─────────────────────────────┬────────────────────────────────────┘
                              │ connect / sendto
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Android 系统网络栈                              │
│   VpnService 建立的虚拟网卡 tun0：全局流量 → TUN fd                │
│   （路由 / DNS / 应用白名单均由 VpnService.Builder 声明）          │
└─────────────────────────────┬────────────────────────────────────┘
                              │ 原始 IP 包 (L3)，通过 ParcelFileDescriptor
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│              android-app (Kotlin) — 薄壳层                          │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ PlaneVpnService extends VpnService                            │ │
│  │  • Builder 配置（地址/路由/DNS/MTU/disallow self）            │ │
│  │  • establish() → 拿到 TUN fd (int)                           │ │
│  │  • protect(socket) → 保证出站不回环（核心机制）              │ │
│  │  • 前台 Service + 通知栏常驻（防后台杀）                      │ │
│  └─────────────────────────────┬────────────────────────────────┘ │
│                                │ JNI: 把 fd + config 传给 Rust     │
│                                ▼                                    │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ plane-core (Rust, 编译为 .so via cargo-ndk)                  │ │
│  │                                                              │ │
│  │  【复用 tun-adapter】                                        │ │
│  │   • stack.rs   用户态 TCP/IP 栈 (smoltcp)  —— 零改动        │ │
│  │   • fake_dns.rs FakeDNS 引擎                —— 零改动        │ │
│  │   • router.rs  路由分流引擎                  —— 零改动        │ │
│  │   • config.rs  配置（TOML→改为 JSON/结构体传参）—— 微调     │ │
│  │                                                              │ │
│  │  【新增/替换】                                               │ │
│  │   • android_tun.rs   用系统 fd 替代 tun_device.rs           │ │
│  │   • outbound.rs      加密 HTTP/2 出站，替代 socks5.rs       │ │
│  │   • protect_socket   通过 JNI 回调 protect(fd)              │ │
│  └─────────────────────────────┬────────────────────────────────┘ │
└────────────────────────────────┼──────────────────────────────────┘
                                 │ 加密 HTTP/2 over TCP（你的协议）
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│                  proxy-remote (Java/Netty) — 零改动                 │
│   接收 ProxyMessage → Outbound → 连接目标 → 双向透传               │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计原则

**复用最大化**：tun-adapter 中真正平台无关的纯逻辑（协议栈、FakeDNS、路由）原样复用，只替换"流量入口"（系统 fd 替代自建 TUN）和"流量出口"（加密 HTTP/2 替代本地 SOCKS5）。

**端上极薄**：Android 上不运行任何 JVM 代理进程。Kotlin 只负责 VpnService 生命周期、UI、JNI 桥接；所有数据面热路径在 Rust。

**直连 proxy-remote**：手机端不经过 proxy-local，少一跳，省电省延迟。proxy-remote 完全零改动（它本来就只接收标准 ProxyMessage）。

**容错隔离**：出站不可达时本地降级为直连（通过 `protect` 过的 socket 直接出网）；VpnService 被系统杀死时，TUN 自动销毁，系统流量回归正常网卡，不会断网。

---

## 3. 关键技术点：与桌面方案的差异

桌面 tun-adapter 的两个平台耦合点，在 Android 上的对应方案如下。

### 3.1 TUN fd 来源：系统递交，而非自建

桌面端 `tun_device.rs` 调用 `tun2::create_as_async` 自己创建 utun。Android 上改为：

```kotlin
// Kotlin 侧
val builder = Builder()
    .setSession("SimplePlane")
    .setMtu(1500)
    .addAddress("198.18.0.1", 30)        // TUN 网卡地址（与 FakeIP 段一致）
    .addRoute("0.0.0.0", 0)              // 全局流量走 TUN
    .addDnsServer("198.18.0.2")          // 把 DNS 指向 FakeDNS（TUN 内地址）
    .addDisallowedApplication(packageName) // 关键：自身流量不走 TUN，防回环
    .setBlocking(false)
val pfd: ParcelFileDescriptor = builder.establish()!!
val tunFd: Int = pfd.detachFd()          // 传给 Rust
```

Rust 侧不再创建设备，而是直接基于这个 fd 做异步读写：

```rust
// android_tun.rs（替代 tun_device.rs）
use tokio::io::unix::AsyncFd;
use std::os::unix::io::RawFd;

pub struct AndroidTun {
    fd: AsyncFd<RawFd>,   // 直接包装系统给的 fd
    mtu: usize,
}
// read()/write() 通过 libc::read/write + AsyncFd 就绪通知实现，
// 与桌面 tun_device 暴露相同的 (reader, writer) 接口，
// 这样 stack.rs 完全感知不到差异。
```

> **要点**：让 `AndroidTun::split()` 返回与桌面 `TunManager::split()` 相同签名的 `(tun_reader, tun_writer)`，则 `stack::stack_loop(...)` 调用方代码完全不变。

### 3.2 回环防护：protect(socket) 替代 fwmark/排除路由

桌面端用 fwmark（Linux）或排除路由（macOS）防止 tun-adapter 自己的出站流量重新进入 TUN。Android 提供了更优雅的官方机制：**`VpnService.protect(fd)`**。

被 protect 的 socket 会绕过 VPN 路由直接走物理网卡。因此 Rust 出站层在每次 `connect` 之前，必须把底层 socket fd 回传给 Kotlin 调用 `protect`：

```rust
// 出站 TCP socket 创建后、connect 之前
let sock = socket2::Socket::new(Domain::IPV4, Type::STREAM, None)?;
let raw_fd = sock.as_raw_fd();
// JNI 回调到 Kotlin：vpnService.protect(raw_fd)
jni_protect_socket(raw_fd)?;   // 不 protect 就会死循环！
sock.connect(&remote_addr)?;
```

```kotlin
// Kotlin 侧 JNI 导出函数
external fun nativeProtect(fd: Int): Boolean
fun protectFd(fd: Int): Boolean = protect(fd)   // VpnService.protect
```

> **这是整个 Android 方案最容易踩坑的地方**。所有出站连接（连 proxy-remote 的、直连的、DoH 查询的）都必须先 protect，否则它们的 IP 包会再次进入 TUN 形成死循环，表现为"开了 VPN 就完全断网"。

### 3.3 出站层：加密 HTTP/2 替代本地 SOCKS5

桌面端 `socks5.rs` 把流量转发到本机 `127.0.0.1:1080` 的 Java proxy-local。Android 端没有 proxy-local，需要在 Rust 里实现一个直连 proxy-remote 的出站客户端，承担原本 proxy-local + proxy-transport-netty 的职责：

```rust
// outbound.rs（替代 socks5.rs）
// 职责：把 smoltcp 重组出的 TCP 字节流，封装成 ProxyMessage，
//       经 AEAD 加密 + HTTP/2 多路复用，发往 proxy-remote。

pub async fn proxy_via_remote<S>(
    target: &str, port: u16,
    app_stream: S,                 // 来自 smoltcp 的字节流（接口同 socks5.rs）
    h2: Arc<Http2Connection>,      // 复用的 HTTP/2 连接（多路复用）
) -> Result<()>
where S: AsyncRead + AsyncWrite + Unpin + Send + 'static
{
    // 1. 在共享 H2 连接上开一个新 stream（对应一个代理请求）
    let (send, recv) = h2.open_stream(target, port).await?;
    // 2. 发送 CONNECT 语义的 ProxyMessage（type=CONNECT, host, port）
    // 3. 加密：AEAD（与桌面同一套 proxy-crypto 算法，Rust 重新实现或用 ring/aes-gcm）
    // 4. 双向转发（结构同 socks5.rs 的 a2p / p2a 双协程）
}
```

**需要在 Rust 侧对齐的协议细节**（与 Java 端保持二进制兼容）：

- `ProxyMessage` 的编解码格式（type / streamId / host / port / data 布局）——需对照 `proxy-common/model/ProxyMessage.java` 和 `proxy-transport-netty/codec/ProxyCodec.java`。
- AEAD 加密算法：建议手机端先只支持 **ChaCha20-Poly1305**（移动 CPU 无 AES 硬件加速时 ChaCha20 更快），对应桌面 `proxy-crypto/ChaCha20Cipher.java`。
- HTTP/2：Rust 用 `h2` crate，对齐你设定的 connection window（16MB）和应用层 credit 背压（64 帧 / 1MB，见记忆中的三级流控设计）。

> **架构收益**：proxy-remote 完全零改动。它分不清连接来自桌面 proxy-local 还是手机 Rust 出站层——只要 ProxyMessage 二进制格式和加密套件一致即可。这正是你当初协议分层解耦的红利。

### 3.4 直连分流：手机场景的必做项

桌面 MVP 里"直连也走 SOCKS5"（main.rs 的 `direct_tcp_stream` 注释）是权宜之计。手机上必须做真正的直连，否则国内流量绕远程会很费电、增加延迟、且代理挂掉就全断。

实现方式：`router.rs` 判定为 `Direct` 的连接，走一个 **protect 过的本地 socket 直接 connect 真实目标**：

```rust
async fn direct_connect(target_ip: Ipv4Addr, port: u16, app_stream: SmolTcpStream) {
    let sock = new_protected_tcp_socket()?;  // 已 protect，绕过 TUN
    sock.connect((target_ip, port)).await?;
    copy_bidirectional(app_stream, sock).await?;
}
```

注意：Direct 路径需要真实 IP。对于 FakeIP 命中的连接，要么先做真实 DNS 解析（经 protect 的 DoH），要么 Direct 规则只对"已是真实 IP 的连接"生效。建议规则顺序：先 IP/GeoIP 规则（拿真实 IP 直连），FakeIP 域名命中的走代理由远端解析。

---

## 4. 模块设计与代码复用清单

### 4.1 Rust 侧（plane-core）

| 文件 | 来源 | 改动量 | 说明 |
| ---- | ---- | ------ | ---- |
| `stack.rs` | 复用 tun-adapter | 零 | smoltcp 协议栈，平台无关 |
| `fake_dns.rs` | 复用 tun-adapter | 零 | FakeDNS 引擎 |
| `router.rs` | 复用 tun-adapter | 微 | 规则引擎，Direct 动作语义增强 |
| `error.rs` | 复用 tun-adapter | 微 | 错误类型 |
| `config.rs` | 复用 tun-adapter | 中 | TOML → 改为从 JNI 接收结构体/JSON |
| `android_tun.rs` | **新增** | - | 包装系统 fd（替代 tun_device.rs） |
| `outbound.rs` | **新增** | - | 加密 HTTP/2 出站（替代 socks5.rs） |
| `proxy_proto.rs` | **新增** | - | ProxyMessage 编解码（对齐 Java） |
| `crypto.rs` | **新增** | - | ChaCha20-Poly1305（对齐 Java AEAD） |
| `jni_bridge.rs` | **新增** | - | JNI 导出：start/stop/protect 回调 |
| `lib.rs` | **新增** | - | crate 入口（替代 main.rs） |

> 估算：约 60-70% 的现有 Rust 代码可直接复用，新增工作集中在出站层和 JNI 桥。

### 4.2 Kotlin 侧（android-app）

| 组件 | 职责 |
| ---- | ---- |
| `PlaneVpnService` | VpnService 生命周期、Builder 配置、establish、protect、前台 Service |
| `NativeBridge` | JNI 声明：`nativeStart(fd, configJson)` / `nativeStop()` / `nativeProtect(fd)` |
| `MainActivity` | 开关按钮、节点配置入口、连接状态、流量统计展示 |
| `ConfigStore` | 节点配置持久化（proxy-remote 地址/端口/密钥/加密套件） |
| `TrafficStats` | 从 Rust 拉取上下行字节、活跃连接数 |

### 4.3 JNI 接口契约

```
Kotlin → Rust:
  nativeStart(tunFd: Int, configJson: String): Long   // 返回 handle
  nativeStop(handle: Long)
  nativeStats(handle: Long): String                   // JSON: {up, down, conns, healthy}

Rust → Kotlin（回调）:
  protect(fd: Int): Boolean                            // 必须，防回环
  onStatus(state: String)                              // 可选，状态上报
```

---

## 5. 数据流：一次 HTTPS 请求的完整路径（Android）

以浏览器访问 `https://www.google.com` 为例：

```
Step 1 — DNS
  浏览器 → sendto(198.18.0.2:53, "www.google.com A?")
  系统 → TUN fd → Rust 读到 UDP 包
  fake_dns: 分配 198.18.5.37 → 记录映射 → 构造响应写回 TUN
  浏览器收到 "www.google.com → 198.18.5.37"

Step 2 — TCP 建连
  浏览器 → connect(198.18.5.37:443)
  系统 → TUN fd → Rust 读到 SYN
  stack(smoltcp): 用户态完成三次握手 → 暴露字节流
  router: 查 FakeIP 表得 www.google.com → 规则判定 Proxy

Step 3 — 出站（直连 proxy-remote）
  outbound: 在共享 H2 连接上开 stream
    → ProxyMessage(CONNECT, www.google.com, 443)
    → ChaCha20-Poly1305 加密 → HTTP/2 帧
    → [出站 socket 已 protect，绕过 TUN] → proxy-remote
  proxy-remote → 连接 google.com:443 → 双向透传

Step 4 — 回程
  google → proxy-remote → (H2+加密) → Rust outbound → smoltcp → TUN fd → 浏览器
```

直连分支（如访问国内站点，router 判定 Direct）：

```
  router: 真实 IP / GeoIP=CN → Direct
  direct_connect: new_protected_tcp_socket → connect 真实目标
    → [已 protect，走物理网卡] → 目标服务器（不经过 proxy-remote）
```

---

## 6. 健康检查与降级（复用桌面思想）

复用 main.rs 的 `health_check_loop` 思想，但检测目标从"本地 SOCKS5"改为"proxy-remote 的 H2 连接可用性"：

| 触发条件 | 降级动作 | 恢复条件 |
| -------- | -------- | -------- |
| proxy-remote 连续 N 次握手/心跳失败 | 新连接的 Proxy 动作临时改走 Direct | H2 连接恢复后自动切回 |
| VpnService 被系统杀死 | TUN 自动销毁，流量回归物理网卡 | 用户重开 / 前台 Service 拉起 |
| 网络切换（WiFi↔蜂窝） | 重建 H2 连接 + 重新 protect | 监听 ConnectivityManager 回调 |

> Android 特有：必须监听 `ConnectivityManager.NetworkCallback`，网络切换时 protect 过的旧 socket 会失效，需要重建出站连接并把新 socket 重新 protect 到当前活动网络。

---

## 7. 跨平台/工程适配

### 7.1 编译产物

| ABI | 说明 |
| --- | ---- |
| arm64-v8a | 现代手机主力，必须 |
| armeabi-v7a | 老设备兼容，可选 |
| x86_64 | 模拟器调试用 |

工具链：`cargo-ndk` + Android NDK，输出 `.so` 打进 APK 的 `jniLibs/`。

```bash
cargo ndk -t arm64-v8a -t armeabi-v7a -o ./android-app/src/main/jniLibs build --release
```

### 7.2 后台存活（Android 重点难题）

- 前台 Service + 常驻通知（Android 8+ 强制）。
- 引导用户加入电池优化白名单（国产 ROM 后台管控激进）。
- `START_STICKY` + VpnService 被杀后的重连策略。

### 7.3 权限与合规

- 仅需 `BIND_VPN_SERVICE` + 前台 Service 权限，无需 root，无需 system 权限。
- 首次启动会弹出系统级 VPN 授权对话框（`VpnService.prepare()`），用户确认一次即可。
- 自用/学习用途。不建议上架公开应用商店，分发走 APK 直装或内部渠道。

### 7.4 包体积控制

- `.so` 经 `strip` + LTO + `opt-level="z"` 后单 ABI 约 3-6MB。
- 只打 arm64-v8a 可显著减小体积；用 App Bundle 按 ABI 分发更优。
- 避免引入 GeoIP 大库（mmdb 可达数 MB），MVP 用精简的域名/CIDR 规则文件即可。

---

## 8. 实现计划（分阶段）

### Phase A：链路打通（MVP，目标=一条 TCP 链路跑通）

| 序号 | 任务 | 预估 | 说明 |
| ---- | ---- | ---- | ---- |
| A1 | Android 工程脚手架 + cargo-ndk 集成 | 4h | 空 VpnService 能编译、能弹授权框 |
| A2 | JNI 桥接最小闭环（start/stop/protect） | 6h | Kotlin↔Rust 能互调，protect 生效 |
| A3 | 系统 fd 接入 Rust（android_tun.rs） | 4h | 复用 stack.rs，能从 fd 读到 IP 包 |
| A4 | FakeDNS + smoltcp 复用验证 | 3h | 直接搬桌面模块，确认能重组出字节流 |
| A5 | **outbound.rs：ProxyMessage + ChaCha20 + H2** | 12h | 核心新增，对齐 Java 二进制格式 |
| A6 | 端到端：手机浏览器 → proxy-remote → 目标 | 5h | 打通一条 HTTPS 链路即里程碑达成 |

**Phase A 合计：~34h。关键 Go/No-Go 点是 A5——协议二进制兼容必须与 Java 端逐字节对齐。**

### Phase B：可用性增强

| 序号 | 任务 | 预估 |
| ---- | ---- | ---- |
| B1 | 真正的直连分流（protect socket 直连） | 5h |
| B2 | 路由规则引擎接入（域名/CIDR/GeoIP-CN） | 6h |
| B3 | 健康检查 + 自动降级 | 4h |
| B4 | 网络切换监听 + H2 重连 + 重新 protect | 5h |
| B5 | 前台 Service + 通知栏 + START_STICKY | 4h |
| B6 | 节点配置 UI + 持久化 | 5h |
| B7 | 流量统计 / 活跃连接展示 | 3h |

**Phase B 合计：~32h**

### Phase C：产品化

| 序号 | 任务 | 预估 |
| ---- | ---- | ---- |
| C1 | UDP 支持（QUIC/游戏，对齐 tun-mode-plan Phase 2） | 8h |
| C2 | 多节点切换 + 订阅导入 | 6h |
| C3 | 规则订阅（远程规则集更新） | 4h |
| C4 | 电池优化引导 + 稳定性加固（watchdog） | 4h |
| C5 | 单元测试（协议编解码、路由匹配、加密互通） | 6h |

**Phase C 合计：~28h**

---

## 9. 技术风险与对策

| 风险 | 影响 | 概率 | 对策 |
| ---- | ---- | ---- | ---- |
| 忘记/漏 protect 出站 socket | 开 VPN 即断网（死循环） | 高 | 封装统一的 `new_protected_socket()`，所有出站强制走它；加自检 |
| ProxyMessage/加密与 Java 不兼容 | 链路完全不通 | 中 | A5 阶段用桌面 proxy-local 抓包对照逐字节验证；先写互通单测 |
| smoltcp 在移动端吞吐不足 | 高负载卡顿 | 中 | 复用桌面已验证的 stack.rs；必要时参考 clash-rs patch |
| 后台被系统杀 | 代理中断 | 高 | 前台 Service + 白名单引导 + START_STICKY 重连 |
| 网络切换后旧连接失效 | 切网后断流 | 高 | NetworkCallback 监听 + H2 重建 + 重新 protect |
| 移动 CPU 无 AES 加速 | AES-GCM 慢 | 低 | 默认 ChaCha20-Poly1305 |
| APK 体积过大 | 安装体验差 | 低 | strip + LTO + 单 ABI + App Bundle |

---

## 9.5 质量保障与持续交付（CI/CD）

> 本章是对前述短板的补齐：Android + Rust 混合工程的测试与交付远比单语言项目复杂，必须提前规划，否则后期补测成本极高。

### 9.5.1 测试金字塔

混合工程的测试分为四层，从下到上数量递减、成本递增、运行频率递减：

```
                    ┌─────────────────┐
                    │  E2E (手机/模拟器) │  少量，慢，nightly
                    │  真机 VPN 链路打通  │
                  ┌─┴─────────────────┴─┐
                  │  协议互通测试 (跨语言)  │  关键，每次 PR
                  │ Rust 出站 ↔ Java remote │
                ┌─┴─────────────────────┴─┐
                │  Kotlin 单测 + Instrumented │  中量
                │  VpnService / JNI 桥 / UI    │
              ┌─┴─────────────────────────┴─┐
              │       Rust 单元测试            │  大量，快，每次提交
              │ stack/fakedns/router/crypto/codec │
              └─────────────────────────────┘
```

### 9.5.2 第一层：Rust 单元测试（基础，最高优先级）

现有 tun-adapter 已有 `socks5.rs` / `config.rs` 的单测（mock server + duplex stream），这是好基础，继续沿用同样风格。**plane-core 必须覆盖的核心单元**：

| 模块 | 测试要点 | 备注 |
| ---- | ---- | ---- |
| `fake_dns.rs` | 域名↔FakeIP 双向映射、LRU 淘汰、IP 池循环复用 | 复用桌面，补边界用例 |
| `router.rs` | 各规则类型匹配、优先级、默认动作、Direct/Proxy/Reject 判定 | 表驱动测试 |
| `crypto.rs` | ChaCha20-Poly1305 加解密往返、错误密钥拒绝、tampering 检测 | **新增，必测** |
| `proxy_proto.rs` | ProxyMessage 编解码往返、边界长度、非法输入 | **新增，必测** |
| `stack.rs` | smoltcp 字节流重组（喂构造的 IP 包，断言输出流） | 复用桌面 |

约定：`cargo test` 全绿是合并门槛。覆盖率用 `cargo-llvm-cov`，核心模块目标行覆盖 ≥ 80%。

### 9.5.3 第二层：协议互通测试（本项目最关键的测试）

这是整个方案最大风险点（A5 协议二进制兼容）对应的防线。**目标：自动化验证 Rust 出站层与 Java proxy-remote 能真正握手、加密互通、传数据。**

设计一个跨语言集成测试 harness：

```
┌──────────────────────┐   加密 H2 + ProxyMessage    ┌─────────────────────┐
│ Rust 测试 (plane-core)│ ──────────────────────────→ │ Java proxy-remote   │
│  构造一条代理请求      │                              │ (测试模式启动)       │
│  CONNECT echo-server  │ ←────────────────────────── │ → 连接本地 echo 服务 │
└──────────────────────┘      回显数据逐字节比对        └─────────────────────┘
```

实现要点：

- CI 中先 `mvn package` 构建 proxy-remote，用一个固定测试密钥/端口启动它（指向本地 echo server）。
- Rust 侧用 `#[tokio::test]` 发起真实出站，断言 echo 数据完整往返。
- **加密互通向量测试**：在 Java 端用固定 key/nonce 加密一段已知明文，导出密文；Rust 端解密断言等于明文（反向亦然）。这能在不启动整条链路的情况下，快速定位"加密算法实现不一致"。
- **编解码向量测试**：Java 端把若干 ProxyMessage 序列化成字节，存为测试 fixture；Rust 端解析断言字段一致（反向亦然）。

> 这套互通测试一旦建立，A5 阶段的"逐字节对齐"就从"手动抓包对照"变成"CI 自动回归"，是把最大风险常态化兜底的关键投资。

### 9.5.4 第三层：Kotlin 测试

| 类型 | 工具 | 覆盖 |
| ---- | ---- | ---- |
| JVM 单测 | JUnit5 + MockK | ConfigStore 序列化、节点解析、状态机逻辑 |
| Instrumented 测试 | androidx.test + 模拟器 | VpnService 生命周期、JNI 桥 start/stop、protect 回调被调用 |
| JNI 契约测试 | 模拟器上跑 | nativeStart 返回 handle、nativeStats 返回合法 JSON、异常不崩溃 |

**protect 防回环的专项测试**（最易出致命 bug 的点）：写一个 instrumented 测试，断言"出站连接建立前 protect(fd) 一定被调用"——用 spy/计数器验证，防止回归时漏掉某条出站路径导致全局断网。

### 9.5.5 第四层：E2E（端到端）

- 在 CI 的 Android 模拟器里启动 VPN，配置指向 CI 内启动的 proxy-remote + 本地测试网站，`curl`/Espresso 验证能取到预期内容。
- 频率：nightly 或 release 前，不进每次 PR（慢且不稳定）。
- 真机冒烟：release 前人工在真机跑一遍核心场景（开关、切网、后台存活）。

### 9.5.6 CI 流水线（GitHub Actions）

按"快反馈优先"原则分 job，PR 触发快层，nightly/release 触发慢层：

```yaml
# .github/workflows/android-ci.yml （示意）
name: android-ci
on:
  pull_request:
  push: { branches: [main] }

jobs:
  rust-test:                 # 最快，每次必跑
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - uses: Swatinem/rust-cache@v2
      - run: cargo fmt --check
      - run: cargo clippy -- -D warnings
      - run: cargo test --workspace
      - run: cargo llvm-cov --fail-under-lines 80   # 核心模块覆盖率门禁

  protocol-interop:          # 跨语言互通，每次 PR
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4   # 构建并启动 proxy-remote
        with: { distribution: temurin, java-version: '8' }
      - run: mvn -q -pl proxy-remote -am package
      - run: ./scripts/start-remote-test.sh &      # 测试模式启动
      - run: cargo test --test interop -- --include-ignored

  android-build:             # 编译 .so + APK
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: nttld/setup-ndk@v1
      - run: cargo install cargo-ndk
      - run: cargo ndk -t arm64-v8a -t armeabi-v7a -o app/src/main/jniLibs build --release
      - run: ./gradlew :app:testDebugUnitTest assembleRelease
      - uses: actions/upload-artifact@v4
        with: { name: apk, path: app/build/outputs/apk/release/*.apk }

  e2e:                       # 慢层，仅 nightly / release
    if: github.event_name == 'schedule' || startsWith(github.ref, 'refs/tags/')
    runs-on: macos-latest    # 模拟器需要硬件加速
    steps:
      - uses: actions/checkout@v4
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          script: ./gradlew connectedCheck
```

### 9.5.7 质量门禁（合并/发布的硬约束）

| 门禁 | 触发时机 | 不通过的后果 |
| ---- | ---- | ---- |
| `cargo fmt` + `clippy -D warnings` | 每次 PR | 阻断合并 |
| Rust 单测全绿 + 核心覆盖率 ≥ 80% | 每次 PR | 阻断合并 |
| 协议互通测试通过 | 每次 PR | 阻断合并（防止协议漂移） |
| Kotlin 单测 + Instrumented 通过 | 每次 PR | 阻断合并 |
| APK 能成功构建 | 每次 PR | 阻断合并 |
| E2E 通过 | release tag | 阻断发布 |

### 9.5.8 持续交付（CD）

- **版本与产物**：打 tag（如 `android-v1.0.0`）触发 release 流水线，自动构建签名 APK + 生成变更日志，发布到 GitHub Release。
- **签名管理**：keystore 通过 GitHub Secrets 注入，绝不入库。
- **多渠道**：默认 arm64-v8a 单包；如需上架用 App Bundle 让平台按 ABI 切分。
- **可观测性回流**：APK 内置匿名崩溃上报（如自建 endpoint 收集 Rust panic + Kotlin crash），release 后观察稳定性指标决定是否灰度放量。
- **灰度策略**：先小范围（自己 + 少量测试用户）跑一周，确认后台存活率、切网成功率、崩溃率达标，再扩大。

### 9.5.9 测试相关任务补充（并入实现计划）

| 序号 | 任务 | 预估 | 归属阶段 |
| ---- | ---- | ---- | ---- |
| Q1 | Rust 核心模块单测（crypto/codec/router/fakedns） | 8h | Phase A（边写边测） |
| Q2 | 协议互通测试 harness（Rust↔Java remote） | 8h | Phase A（A5 同步建） |
| Q3 | GitHub Actions：rust-test + protocol-interop + android-build | 6h | Phase A 末 |
| Q4 | Kotlin 单测 + protect 防回环专项测试 | 5h | Phase B |
| Q5 | E2E（模拟器 VPN 链路） + nightly 流水线 | 6h | Phase C |
| Q6 | CD：签名构建 + Release 自动发布 + 崩溃上报 | 5h | Phase C |

**测试/交付合计：~38h**（之前三个 Phase 共约 94h，加上质量体系后总量约 132h，质量投入占比约 29%，对一个网络基础设施类项目是合理的。）

---

## 10. 与桌面方案的对接关系

### 10.1 完全复用（proxy-remote 零改动）

- proxy-remote 的 Outbound 出站、Session 管理 → 完全复用
- ProxyMessage 协议格式 → 复用（Rust 重新实现编解码，保持二进制兼容）
- AEAD 加密套件 → 复用算法定义（Rust 重新实现 ChaCha20-Poly1305）
- HTTP/2 多路复用 + 背压策略 → 复用设计参数（16MB window / 64 帧 credit）

### 10.2 复用 Rust 纯逻辑（tun-adapter → plane-core）

- stack.rs / fake_dns.rs / router.rs / error.rs → 直接复用
- config.rs → 输入源从 TOML 改为 JNI 传参

### 10.3 Android 新增

- VpnService 薄壳（Kotlin）
- 系统 fd 接入（android_tun.rs）替代自建 TUN
- 加密 HTTP/2 出站（outbound.rs）替代本地 SOCKS5 转发
- JNI 桥 + protect 回调

### 10.4 架构示意

```
桌面: 浏览器 → 系统代理/TUN → proxy-local(Java) → proxy-remote ─┐
                                                                 ├─→ 目标
手机: App → VpnService → plane-core(Rust，直连) → proxy-remote ──┘

         两条客户端路径，共用同一个 proxy-remote 和同一套协议。
```

---

## 11. 总结

这套方案的核心价值在于：**它几乎不需要重写业务逻辑，而是把现有架构的"客户端入口"换了一个实现**。能做到这一点，恰恰证明了 SimplePlanePlatform 当初的分层解耦是成功的——协议层、加密层、传输策略与具体的"流量从哪来"完全无关。

工作量集中且可控：真正的新增只有"加密 HTTP/2 出站层"和"VpnService + JNI 桥"两块，其余 60-70% 的 Rust 数据面代码可直接搬运。建议严格按 Phase A 推进，先用一条 HTTPS 链路验证协议二进制兼容性（最大风险点），再逐步补分流、UI 和稳定性。

> 附：最易踩坑的三件事——(1) 所有出站 socket 必须 protect；(2) ProxyMessage 要与 Java 端逐字节对齐；(3) 网络切换要重建连接并重新 protect。把这三点处理好，方案就成立。