# TUN 透明代理模式 — 技术规划方案

| 属性     | 内容                                              |
| -------- | ------------------------------------------------- |
| 文档版本 | v1.1                                              |
| 项目名称 | Netty-Proxy TUN 透明代理                          |
| 所属模块 | tun-adapter (Rust) + proxy-local (Java/Netty)     |
| 作者     | zhanghonghao                                      |
| 状态     | Planning                                          |

---

## 1. 背景与动机

### 1.1 当前模式的局限

当前 proxy-local 以 SOCKS5/HTTP CONNECT 代理模式工作，用户的应用程序必须**主动感知**代理的存在——要么手动配置系统代理，要么使用支持 SOCKS5 的客户端。这带来几个问题：

第一，不是所有应用都尊重系统代理设置。很多命令行工具（curl 需要 `-x` 参数）、游戏客户端、IoT 设备根本不走系统代理。

第二，UDP 流量无法代理。SOCKS5 的 UDP ASSOCIATE 实现复杂且几乎没有客户端支持，HTTP CONNECT 则完全不支持 UDP。

第三，DNS 泄漏。即使配了代理，DNS 请求可能走本地 ISP 的 DNS 服务器而不经过代理链路，暴露用户真实的访问意图。

### 1.2 TUN 模式的优势

TUN（网络隧道）模式通过在操作系统内核中创建一个虚拟网卡设备，将**系统全局流量**（或路由规则匹配的流量）劫持到用户态程序处理。对上层应用完全透明——应用以为自己在正常上网，实际上所有 IP 包都被路由到了我们的代理链路。

核心优势：全局透明（无需应用配置）、支持 TCP/UDP/ICMP、DNS 请求完全可控、可实现精细化的路由分流（国内直连/国外代理/广告拦截）。

### 1.3 设计目标

在不侵入现有 Java 代理核心的前提下，通过一个独立的 Rust TUN 适配层接管系统流量，与 proxy-local 无缝对接。具体目标：

1. **系统全局 TCP/UDP 流量透明代理**，应用无感知。
2. **DNS 请求拦截与智能解析**，支持 FakeDNS + 真实远端解析双模式。
3. **高性能**：TUN 设备读写 + 用户态协议栈处理延迟 < 0.5ms（P99）。
4. **与现有 Java 架构零侵入对接**，proxy-local 仍然接收标准 SOCKS5 连接。
5. **跨平台支持**：macOS（utun）、Linux（/dev/net/tun）、Windows（Wintun）。
6. **精细路由**：支持基于域名/IP/GeoIP/进程名的分流策略。

---

## 2. 整体架构

### 2.1 分层架构全景

```
┌──────────────────────────────────────────────────────────────────────┐
│                        用户应用层                                      │
│         浏览器 / curl / 游戏 / 任意网络程序                            │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ 正常 socket 调用 (connect/sendto/...)
                            ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     操作系统内核网络栈                                  │
│                                                                        │
│   路由表规则：全局流量 → TUN 虚拟网卡 (utun3 / tun0)                  │
│              直连流量 → 物理网卡 (en0 / eth0)                         │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ 原始 IP 包 (L3)
                            ▼
┌──────────────────────────────────────────────────────────────────────┐
│                   tun-adapter (Rust 进程)                              │
│                                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  TUN Device Driver                                               │  │
│  │  • macOS: utun (via System Configuration Framework)              │  │
│  │  • Linux: /dev/net/tun (ioctl TUNSETIFF)                         │  │
│  │  • Windows: Wintun (Ring Buffer, kernel driver)                  │  │
│  └──────────────────────────┬──────────────────────────────────────┘  │
│                              │ Raw IP Packets                          │
│                              ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  Userspace TCP/IP Stack (smoltcp / lwIP Rust binding)            │  │
│  │                                                                   │  │
│  │  • TCP: SYN → SYN-ACK → 三次握手完成 → 提取字节流               │  │
│  │  • UDP: 直接提取 payload + src/dst                               │  │
│  │  • 输出：(目标IP, 目标Port, 协议类型, 字节流/数据报)            │  │
│  └──────────────────────────┬──────────────────────────────────────┘  │
│                              │                                         │
│                              ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  Router & DNS Engine                                              │  │
│  │                                                                   │  │
│  │  • FakeDNS: 拦截 DNS 请求 → 分配假 IP → 建立 fakeIP↔domain 映射 │  │
│  │  • GeoIP/GeoSite 路由判定：直连 / 代理 / 拦截                    │  │
│  │  • [Phase 2+] 进程级路由（实现复杂度高，见 4.4 节说明）          │  │
│  └──────────────────────────┬──────────────────────────────────────┘  │
│                              │                                         │
│                              ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  SOCKS5 Client (内置轻量级 SOCKS5 客户端)                        │  │
│  │                                                                   │  │
│  │  需代理的流量:                                                    │  │
│  │    → SOCKS5 CONNECT(真实域名:端口) → 127.0.0.1:1080              │  │
│  │    → 转发字节流                                                   │  │
│  │                                                                   │  │
│  │  直连的流量:                                                      │  │
│  │    → 直接通过物理网卡发出 (bypass TUN 路由)                       │  │
│  └──────────────────────────┬──────────────────────────────────────┘  │
│                              │ SOCKS5 协议                             │
└──────────────────────────────┼──────────────────────────────────────────┘
                               │ TCP 连接到 127.0.0.1:1080
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     proxy-local (Java/Netty 进程)                      │
│                                                                        │
│   SOCKS5 Server (已有) ← 接收 tun-adapter 的连接                     │
│     → Filter Chain → ClusterInvoker → ExchangeClient                  │
│     → HTTP/2 → proxy-remote                                           │
│                                                                        │
│   【零改动】对 proxy-local 来说，流量来源从浏览器变成了                │
│   tun-adapter，但协议接口完全一致（标准 SOCKS5）                      │
└──────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     proxy-remote (Java/Netty 进程)                     │
│                                                                        │
│   接收请求 → Outbound → 连接目标网站 → 双向透传                      │
│                                                                        │
│   【零改动】                                                           │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计原则

**关注点分离**：TUN 设备操作、用户态协议栈、路由决策全部在 Rust 进程中完成；Java 进程只负责代理协议。两者通过标准 SOCKS5 协议对接，松耦合。

**性能分层**：包处理的热路径（每秒数十万 IP 包的解析和转发）用 Rust 实现零拷贝处理；代理协议的冷路径（建连、加密协商）用 Java 的丰富生态。

**容错隔离**：tun-adapter 崩溃时自动恢复路由表（通过 drop guard），不影响系统正常联网。proxy-local 崩溃时 tun-adapter 的 SOCKS5 连接失败，流量走 fallback 直连。

---

## 3. 技术选型

### 3.1 为什么选 Rust 作为 TUN 适配层

| 维度 | Rust | Go | C |
|------|------|------|------|
| 内存安全 | 编译期保证，无 GC 停顿 | GC 可能引入延迟抖动 | 手动管理，易出错 |
| 系统调用 | 零开销 FFI，直接调用 libc | cgo 有性能开销 | 原生但开发效率低 |
| 异步 IO | tokio 生态成熟（io_uring 支持） | goroutine 调度优秀 | 需自建事件循环 |
| 用户态协议栈 | smoltcp（纯 Rust，活跃维护） | gVisor netstack（重量级） | lwIP（经典但 C 风格） |
| 跨平台 | 一套代码编译多平台 | 同上 | 条件编译复杂 |
| 二进制大小 | ~2-5MB（strip 后） | ~8-15MB | 最小但需手动依赖管理 |
| 与 Java 对接 | SOCKS5 over loopback（零依赖） | 同上 | 同上 |

**结论**：Rust 在系统编程安全性、性能零开销、异步生态成熟度上全面领先。尤其 smoltcp 作为纯 Rust 用户态 TCP/IP 栈，轻量（无 OS 依赖）、可嵌入、维护活跃，是 TUN 场景的最佳选择。

### 3.2 核心依赖库

| 组件 | 库 | 版本 | 用途 |
|------|-----|------|------|
| 异步运行时 | tokio | 1.x | 异步 IO、定时器、任务调度 |
| TUN 设备 | tun2 (rust-tun fork) | latest | 跨平台 TUN 设备创建与读写 |
| 用户态 TCP 栈 | smoltcp | 0.11+ | IP 包解析、TCP 状态机、UDP 处理 |
| DNS 解析 | hickory-dns (trust-dns) | 0.24+ | DNS 协议解析、FakeDNS 实现 |
| GeoIP | maxminddb | latest | MaxMind GeoLite2 IP 地理位置判定 |
| GeoSite | 自定义解析 | - | v2ray/domain-list-community 规则解析 |
| SOCKS5 客户端 | tokio-socks 或手写 | - | 向 proxy-local 发起 SOCKS5 连接 |
| 配置 | serde + toml | - | TOML 配置文件解析（路由规则、监听地址等） |
| 日志 | tracing + tracing-subscriber | - | 结构化日志 |
| 进程信息 | sysinfo / proc-macro | - | 获取连接所属进程（进程级路由） |

### 3.3 用户态协议栈选型：smoltcp

为什么不直接解析原始 IP 包自己拼 TCP？因为 TCP 协议栈的正确实现极其复杂（重传、拥塞控制、窗口管理、TIME_WAIT、乱序重组...）。smoltcp 是专为嵌入式/用户态场景设计的轻量 TCP/IP 栈：

- 无堆分配（可选），适合高性能场景
- 支持 TCP、UDP、ICMP、IPv4/IPv6
- 无 OS 依赖，纯逻辑实现，时钟和缓冲区由调用方提供
- 活跃维护，被 Rust 嵌入式生态广泛使用
- 许可证 MIT/Apache-2.0

工作模式：我们把从 TUN 设备读到的 IP 包"喂"给 smoltcp，它负责 TCP 握手/重传/拆包，最终给我们一个字节流接口（类似 `TcpSocket::recv/send`）。我们拿到字节流后就可以转发给 SOCKS5 了。

---

## 4. 详细设计

### 4.1 TUN 设备管理

#### 4.1.1 设备创建

```rust
// macOS: 使用 utun
let config = tun2::Configuration::default();
config.name("utun3");          // macOS 会自动分配编号
config.address("198.18.0.1");  // TUN 网关地址（FakeDNS 段）
config.netmask("255.254.0.0"); // /15 网段
config.mtu(1500);
config.up();                   // 启用设备

let device = tun2::create_as_async(&config)?;
```

#### 4.1.2 路由表操作与回环防护

TUN 设备创建后，需要添加路由规则让系统流量走 TUN，同时**必须确保 tun-adapter 自身发出的流量（连接 proxy-local、直连流量）不会重新进入 TUN 设备**，否则会形成死循环导致网络中断。

**macOS 方案：排除路由 + 绑定物理网卡**

```bash
# 1. 全局流量走 TUN（两条 /1 路由优先级高于默认路由 0.0.0.0/0）
sudo route add -net 0.0.0.0/1 -interface utun3
sudo route add -net 128.0.0.0/1 -interface utun3

# 2. 排除 proxy-remote 的真实 IP（避免代理流量回环）
sudo route add -host <proxy-remote-ip> -gateway <原网关>

# 3. 排除本地回环（tun-adapter → proxy-local 走 loopback，天然不经过 TUN）
# 127.0.0.0/8 默认走 lo0，无需额外配置

# 4. 排除局域网段
sudo route add -net 10.0.0.0/8 -gateway <原网关>
sudo route add -net 172.16.0.0/12 -gateway <原网关>
sudo route add -net 192.168.0.0/16 -gateway <原网关>
```

**Linux 方案：fwmark + 策略路由（推荐，更健壮）**

Linux 上推荐使用 `SO_MARK` + `ip rule` 策略路由，即使 proxy-remote 的 IP 动态变化（DNS 解析、多节点切换）也不会回环：

```bash
# 1. tun-adapter 进程对所有自身发出的 socket 设置 fwmark=0x1
#    （Rust 代码中通过 setsockopt SO_MARK 实现）

# 2. 策略路由：带 mark 的包走主路由表（物理网卡），不走 TUN
ip rule add fwmark 0x1 lookup main prio 10
ip rule add not fwmark 0x1 lookup tun_table prio 20

# 3. TUN 路由表
ip route add default dev tun0 table tun_table

# 4. 主路由表保持原样（物理网卡出口）
```

```rust
// Rust 侧：为所有出站 socket 设置 fwmark
use std::os::unix::io::AsRawFd;

fn set_socket_mark(socket: &TcpStream, mark: u32) -> std::io::Result<()> {
    let fd = socket.as_raw_fd();
    unsafe {
        let ret = libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_MARK,
            &mark as *const u32 as *const libc::c_void,
            std::mem::size_of::<u32>() as libc::socklen_t,
        );
        if ret != 0 {
            return Err(std::io::Error::last_os_error());
        }
    }
    Ok(())
}
```

**关键原则**：tun-adapter 到 proxy-local 的 SOCKS5 连接走 127.0.0.1 回环（天然不经过 TUN）；tun-adapter 的直连流量通过 fwmark（Linux）或排除路由（macOS）绕过 TUN。

#### 4.1.3 Drop Guard（安全恢复）

```rust
struct RouteGuard {
    original_gateway: IpAddr,
    tun_name: String,
}

impl Drop for RouteGuard {
    fn drop(&mut self) {
        // 进程退出（正常或崩溃）时自动恢复路由表
        restore_routes(&self.original_gateway);
        log::info!("Routes restored, TUN {} released", self.tun_name);
    }
}
```

### 4.2 用户态协议栈处理流程

```
TUN Device
    │ read() → Raw IP Packet (1500 bytes max)
    ▼
┌─────────────────────────────────────────────────┐
│  IP Header 解析                                   │
│  • src_ip, dst_ip, protocol (TCP=6, UDP=17)      │
│  • 如果 dst_ip 在 FakeDNS 段 → 查映射得真实域名  │
└────────────────┬────────────────────────────────┘
                 │
        ┌────────┴────────┐
        ▼                 ▼
   TCP Packet         UDP Packet
        │                 │
        ▼                 ▼
┌──────────────┐  ┌──────────────────────┐
│ smoltcp      │  │ UDP 直接提取 payload  │
│ TCP 状态机   │  │ (DNS / QUIC / 普通)   │
│              │  │                        │
│ SYN_RCVD →  │  │ 如果 dst_port=53:     │
│ ESTABLISHED  │  │   → FakeDNS Engine    │
│ → 提取字节流 │  │ 否则:                 │
│              │  │   → UDP 代理/直连     │
└──────┬───────┘  └──────────┬───────────┘
       │                     │
       ▼                     ▼
  路由判定 (Router)     路由判定 (Router)
       │                     │
  ┌────┴────┐          ┌─────┴────┐
  ▼         ▼          ▼          ▼
代理      直连       代理        直连
  │         │          │          │
  ▼         ▼          ▼          ▼
SOCKS5   Bypass     SOCKS5     Bypass
Client   (直发)    UDP Relay   (直发)
```

### 4.3 FakeDNS 机制

FakeDNS 是 TUN 模式的核心创新点。问题在于：TUN 层拿到的是 IP 包，只有目标 IP 地址，没有域名。但代理需要域名（用于 SNI、路由判定、远端 DNS 解析）。FakeDNS 解决这个问题：

```
                应用                    tun-adapter
                 │                           │
                 │─── DNS Query ────────────→│  1. 拦截 DNS 请求
                 │    "google.com A?"        │
                 │                           │  2. 不做真实解析
                 │                           │     分配一个假 IP: 198.18.1.42
                 │                           │     记录映射: 198.18.1.42 → google.com
                 │←── DNS Response ─────────│
                 │    "google.com → 198.18.1.42"
                 │                           │
                 │─── TCP SYN ──────────────→│  3. 应用连接 198.18.1.42:443
                 │    dst=198.18.1.42:443    │
                 │                           │  4. 查映射: 198.18.1.42 → google.com
                 │                           │     知道真实目标是 google.com:443
                 │                           │
                 │                           │  5. SOCKS5 CONNECT google.com:443
                 │                           │     → 发给 proxy-local
```

```rust
struct FakeDnsEngine {
    /// 假IP → 真实域名 的映射
    ip_to_domain: LruCache<Ipv4Addr, String>,
    /// 域名 → 假IP 的映射（反向查找，避免同域名分配不同假IP）
    domain_to_ip: LruCache<String, Ipv4Addr>,
    /// 下一个可分配的假 IP（198.18.0.0/15 段，约 13 万个地址循环使用）
    next_ip: Ipv4Addr,
}

impl FakeDnsEngine {
    /// 处理 DNS 查询，返回假 IP
    fn resolve(&mut self, domain: &str) -> Ipv4Addr {
        if let Some(&ip) = self.domain_to_ip.get(domain) {
            return ip; // 已分配过，复用
        }
        let fake_ip = self.allocate_next();
        self.ip_to_domain.put(fake_ip, domain.to_string());
        self.domain_to_ip.put(domain.to_string(), fake_ip);
        fake_ip
    }

    /// 根据假 IP 反查域名
    fn lookup(&self, ip: &Ipv4Addr) -> Option<&str> {
        self.ip_to_domain.get(ip).map(|s| s.as_str())
    }
}
```

### 4.4 路由引擎

```rust
enum RouteAction {
    Proxy,    // 走代理链路（SOCKS5 → proxy-local）
    Direct,   // 直连（bypass TUN，走物理网卡）
    Reject,   // 拒绝（黑洞，用于广告拦截）
}

struct Router {
    /// 规则优先级从高到低
    rules: Vec<Box<dyn Rule>>,
    /// 默认动作
    default_action: RouteAction,
}

trait Rule {
    fn matches(&self, conn_info: &ConnInfo) -> Option<RouteAction>;
}

struct ConnInfo {
    src_ip: IpAddr,
    dst_ip: IpAddr,
    dst_port: u16,
    domain: Option<String>,   // 从 FakeDNS 或 SNI 提取
    protocol: Protocol,       // TCP / UDP
    process_name: Option<String>, // 发起连接的进程名
}

// MVP 阶段规则（Phase TUN-1）
struct DomainRule { pattern: String, action: RouteAction }      // *.google.com → Proxy
struct GeoIpRule { country: String, action: RouteAction }       // CN → Direct
struct GeoSiteRule { category: String, action: RouteAction }    // category-ads → Reject
struct IpCidrRule { cidr: IpNet, action: RouteAction }          // 192.168.0.0/16 → Direct
struct PortRule { port: u16, action: RouteAction }              // 53 → Proxy (DNS)

// Phase TUN-2+ 规则（实现复杂度高）
struct ProcessRule { name: String, action: RouteAction }        // "Telegram" → Proxy
```

**进程级路由实现说明（Phase TUN-2+）**：

从 IP 包反查发起进程是进程级路由的核心难点。TUN 层拿到的是原始 IP 包，只有 (src_ip, src_port, dst_ip, dst_port) 四元组，需要额外手段关联到进程：

- **macOS**：调用 `proc_pidinfo` + `PROC_PIDLISTFDS` 遍历所有进程的 socket fd，匹配四元组。或使用 Network Extension 的 `NEFilterDataProvider` 获取进程信息（需签名 entitlement）。
- **Linux**：解析 `/proc/net/tcp` 和 `/proc/net/tcp6` 找到匹配四元组的 inode，再遍历 `/proc/*/fd/` 反查进程 PID。

两种方案都有性能开销（遍历进程表），建议实现时加入缓存（连接建立时查一次，后续复用）并设置超时淘汰。MVP 阶段先用域名/IP/GeoIP 规则即可覆盖绝大多数场景。

### 4.5 SOCKS5 对接层

tun-adapter 内置一个轻量 SOCKS5 客户端，将需要代理的流量通过标准 SOCKS5 协议发给 proxy-local：

```rust
async fn proxy_tcp_connection(
    domain: &str,
    port: u16,
    mut tcp_stream: SmolTcpStream, // 来自 smoltcp 的字节流
    socks5_addr: SocketAddr,       // 127.0.0.1:1080
) -> Result<()> {
    // 1. 连接 proxy-local 的 SOCKS5 端口
    let mut socks_stream = TcpStream::connect(socks5_addr).await?;

    // 2. SOCKS5 握手
    socks5_handshake(&mut socks_stream).await?;

    // 3. SOCKS5 CONNECT 请求（发送真实域名，而非 IP）
    socks5_connect(&mut socks_stream, domain, port).await?;

    // 4. 双向转发（zero-copy splice）
    tokio::io::copy_bidirectional(&mut tcp_stream, &mut socks_stream).await?;

    Ok(())
}
```

对 proxy-local 来说，这就是一个普通的 SOCKS5 客户端连接，与浏览器发来的请求没有任何区别。**Java 代码零改动。**

### 4.6 UDP 处理策略

UDP 代理比 TCP 复杂，因为 SOCKS5 的 UDP ASSOCIATE 实际可用性很差，且 UDP 数据报封装在 TCP 中传输会引入 Head-of-Line Blocking，对延迟敏感的应用（游戏、VoIP）体验很差。因此采用**分阶段策略**：

#### Phase TUN-1（MVP）：DNS 本地处理 + 非 DNS 直连

- **DNS UDP（dst_port=53）**：由 tun-adapter 的 FakeDNS 引擎本地处理，直接返回假 IP 响应，不需要转发到远端。对于需要真实解析的域名（如直连域名），tun-adapter 通过 DNS over HTTPS（DoH）或 DNS over TCP 向可信 DNS 服务器查询，避免 UDP 代理问题。
- **非 DNS UDP（QUIC、游戏等）**：通过路由规则直接放行（bypass TUN），走物理网卡直连。这意味着 MVP 阶段非 DNS 的 UDP 流量不经过代理。

这个策略的优势是 **Java 侧零改动**，且覆盖了最核心的需求（DNS 防泄漏 + TCP 全局代理）。

#### Phase TUN-2：UDP over QUIC 通道

当需要代理 UDP 流量时（如海外游戏加速），推荐 tun-adapter 直接与 proxy-remote 建立 QUIC 通道：

```
tun-adapter ──── QUIC (UDP) ────→ proxy-remote
                                    │
                                    ▼
                              DatagramChannel → 目标 UDP 服务
```

QUIC 天然基于 UDP，无 Head-of-Line Blocking，适合承载 UDP 数据报。proxy-remote 侧新增 QUIC 监听端口 + `DatagramChannel` 转发即可。

#### Phase TUN-3（可选）：扩展 ProxyMessage

如果需要 UDP 流量也经过完整的 Filter 链和集群容错，可在 `ProxyMessage.MessageType` 中新增 `UDP_DATA` 类型，通过现有 HTTP/2 通道传输：

```
UDP 数据报格式（复用 ProxyMessage）:
┌──────────────────────────────────────────────┐
│ type=UDP_DATA | host=target | port=target    │
│ data=<原始 UDP payload>                       │
└──────────────────────────────────────────────┘
```

此方案适合对延迟不敏感但需要完整代理能力的 UDP 场景（如 DNS over UDP 转发到远端解析）。

---

## 5. 与现有 Java 架构的对接

### 5.1 对接方式：标准 SOCKS5

```
┌──────────────┐     SOCKS5 over loopback      ┌─────────────────────┐
│ tun-adapter  │ ──── 127.0.0.1:1080 ────────→ │ proxy-local         │
│   (Rust)     │                                │ Socks5InitHandler   │
│              │                                │ Socks5ConnectHandler│
│              │                                │ RelayHandler        │
└──────────────┘                                └─────────────────────┘
```

proxy-local 已经有完整的 SOCKS5 实现（`Socks5InitHandler` → `Socks5ConnectHandler` → `RelayHandler`），tun-adapter 只是另一个 SOCKS5 客户端。

### 5.2 Java 侧需要的改动（极少）

| 改动 | 阶段 | 说明 | 复杂度 |
|------|------|------|--------|
| 无 | MVP | 核心代理链路零改动 | - |
| 可选：QUIC 监听 | Phase 2 | proxy-remote 新增 QUIC 端口，支持 UDP 数据报转发 | 中 |
| 可选：性能优化 | Phase 3 | proxy-local 对 loopback 连接跳过加密（已是本地通信） | 极低 |
| 可选：进程白名单 | Phase 3 | proxy-local 配置接受来自 tun-adapter 进程的连接（安全加固） | 极低 |

### 5.3 进程管理

推荐 proxy-local 作为主控进程，启动时通过 `ProcessBuilder` 拉起 tun-adapter：

```java
// ProxyLocalServer.java（可选增强）
public class TunManager {
    private Process tunProcess;

    public void startTunMode() {
        tunProcess = new ProcessBuilder("tun-adapter", "--config", "config/tun.toml")
                .redirectErrorStream(true)
                .start();
        // 监控进程存活，崩溃自动重启（Phase TUN-2 实现 watchdog）
    }

    public void stopTunMode() {
        tunProcess.destroy(); // tun-adapter 的 Drop Guard 会恢复路由
    }
}
```

或者两者完全独立部署，用户手动启动 tun-adapter（更灵活，适合高级用户）。

---

## 6. 数据流详解：一个 HTTPS 请求的完整路径

以用户在浏览器访问 `https://www.google.com` 为例：

```
Step 1: DNS 解析
  浏览器 → socket(AF_INET, SOCK_DGRAM) → sendto(8.8.8.8:53, "www.google.com A?")
  内核路由 → TUN 设备 → tun-adapter 读到 UDP 包
  tun-adapter: dst_port=53, 这是 DNS 请求
    → FakeDNS: 分配 198.18.5.37 → 记录 198.18.5.37=www.google.com
    → 构造 DNS Response: www.google.com → 198.18.5.37
    → 写回 TUN 设备 → 内核 → 浏览器收到 DNS 响应

Step 2: TCP 连接
  浏览器 → connect(198.18.5.37:443)
  内核 → TUN → tun-adapter 读到 TCP SYN 包 (dst=198.18.5.37:443)
  smoltcp: 完成 TCP 三次握手（用户态模拟）
  tun-adapter: 查 FakeIP 表 → 解析出真实域名 www.google.com
  tun-adapter → 连接 proxy-local SOCKS5: CONNECT www.google.com:443

Step 3: 数据透传
  浏览器 → TLS ClientHello → TUN → tun-adapter → smoltcp TCP 收到数据
  tun-adapter → 通过 SOCKS5 隧道 → proxy-local → proxy-remote → 目标网站
  目标网站响应 → proxy-remote → proxy-local → tun-adapter → smoltcp → TUN → 内核 → 浏览器
```

---

## 7. 接口契约：tun-adapter ↔ proxy-local

### 7.1 对接方式

tun-adapter 作为 SOCKS5 客户端，连接 proxy-local 的 SOCKS5 监听端口（已有能力，无需改动）。

每个 TCP 流（由 smoltcp 重组完成）对应一条到 proxy-local 的 SOCKS5 CONNECT 请求：

```
tun-adapter → proxy-local:1080
  SOCKS5 握手 → CONNECT www.google.com:443
  proxy-local → proxy-remote → 目标
  双向透传
```

### 7.2 UDP 处理（DNS 之外的 UDP）

对于非 DNS 的 UDP 流量（如 QUIC），分阶段处理（详见 4.6 节）：

- **MVP**：直接放行，不走代理（通过路由规则旁路）
- **Phase TUN-2**：tun-adapter 与 proxy-remote 建立 UDP over QUIC 通道
- **Phase TUN-3（可选）**：ProxyMessage 新增 `UDP_DATA` 类型，走完整代理链路

### 7.3 配置传递

tun-adapter 通过 TOML 配置文件启动（Rust 生态对 TOML 支持最好，且比 YAML 不易出缩进错误）。proxy-local 的 `proxy.yml` 增加 `tun` 段后，启动时自动生成此 TOML 配置：

```toml
[tun]
name = "utun7"                     # macOS: utunX, Linux: tunX
mtu = 1500
address = "198.18.0.1/15"          # TUN 设备地址（FakeIP 段）

[proxy]
socks5_addr = "127.0.0.1:1080"    # proxy-local 的 SOCKS5 端口
health_check_interval = 5          # 健康检查间隔（秒）

[fakeip]
range = "198.18.0.0/15"           # FakeIP 池范围（/15 约 13 万地址）
ttl = 1                            # DNS 响应 TTL（设为 1 避免系统缓存冲突）

[dns]
upstream = "https://1.1.1.1/dns-query"  # 直连域名的真实 DNS（DoH）
fallback = "https://8.8.8.8/dns-query"  # 备用 DoH

[routing]
default_action = "proxy"           # 默认动作：proxy / direct / reject

# 直连规则（不走代理）
[[routing.rules]]
type = "ip_cidr"
value = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8"]
action = "direct"

[[routing.rules]]
type = "domain_suffix"
value = ["cn", "local", "localhost"]
action = "direct"

[[routing.rules]]
type = "geoip"
value = ["CN"]
action = "direct"

[[routing.rules]]
type = "geosite"
value = ["category-ads-all"]
action = "reject"
```

---

## 8. 跨平台适配

### 8.1 macOS

| 组件 | 实现 |
|------|------|
| TUN 创建 | `socket(PF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL)` + `utun` |
| 路由设置 | `route add` 命令 |
| DNS 劫持 | 修改 `/etc/resolver/` 或使用 NetworkExtension |
| 权限 | Privileged Helper Tool（推荐）或 root |

**macOS 权限方案：Privileged Helper Tool（推荐）**

参考 Surge / ClashX Pro 的做法，通过 `SMJobBless` 注册一个 privileged helper 到 launchd，用户只需在安装时授权一次：

```
┌─────────────────────────┐         XPC          ┌──────────────────────────┐
│  tun-adapter (普通用户)  │ ──────────────────→ │  tun-helper (root)        │
│                          │                      │                            │
│  • 业务逻辑              │  请求：创建 TUN      │  • 创建 utun 设备          │
│  • smoltcp / FakeDNS     │  请求：设置路由      │  • 执行 route add/delete   │
│  • SOCKS5 Client         │  请求：恢复路由      │  • 修改 /etc/resolver/     │
└─────────────────────────┘                      └──────────────────────────┘
```

优势：主进程无需 root 权限运行，减少攻击面；helper 通过 launchd 管理，系统重启后自动可用；XPC 通信有签名验证，防止未授权进程调用。

MVP 阶段可先用 `sudo` 启动简化开发，Phase TUN-3 再实现 privileged helper。

### 8.2 Linux

| 组件 | 实现 |
|------|------|
| TUN 创建 | `open("/dev/net/tun")` + `ioctl(TUNSETIFF, IFF_TUN \| IFF_NO_PI)` |
| 路由设置 | `ip route add` + `ip rule` 策略路由 |
| DNS 劫持 | iptables REDIRECT 53 端口到 tun-adapter |
| 权限 | `CAP_NET_ADMIN` capability 或 root |

### 8.3 Windows（后续）

| 组件 | 实现 |
|------|------|
| TUN 创建 | WinTUN 驱动（wireguard-nt） |
| 路由设置 | `netsh interface` 或 Windows API |
| DNS 劫持 | 修改网卡 DNS 设置指向本地 |
| 权限 | 需要管理员权限 |

---

## 9. 实现计划

### Phase TUN-1：核心能力（MVP）

| 序号 | 任务 | 预估工时 | 说明 |
|------|------|----------|------|
| T1 | Rust 项目脚手架 + CI 集成 | 2h | cargo workspace，确保能交叉编译 macOS/Linux |
| T2 | TUN 设备创建与读写（macOS utun）+ Drop Guard | 4h | 先跑通"创建 → 路由 → 退出恢复"生命周期 |
| T3 | FakeIP DNS 服务器实现 | 4h | 拦截 DNS → 返回假 IP → 建立映射 |
| T4 | smoltcp 用户态 TCP 栈集成 | 6h | IP 包解析 + TCP/UDP 分流 + 字节流提取 |
| T5 | **smoltcp 性能基准测试** | 3h | iperf3 验证吞吐 ≥ 1Gbps；不达标则评估 lwIP 备选 |
| T6 | SOCKS5 客户端对接 proxy-local | 3h | 打通完整代理链路 |
| T7 | 路由回环防护（fwmark/排除路由） | 3h | 确保不死循环，含直连流量旁路 |
| T8 | 端到端集成测试 | 4h | curl → TUN → proxy-local → proxy-remote → 目标 |

**Phase TUN-1 合计：~29h**

> **关键里程碑**：T5 是 Go/No-Go 决策点。如果 smoltcp 在 iperf3 测试中吞吐低于 1Gbps 或延迟 P99 > 1ms，需评估是否切换到 lwIP（通过 `netstack-lwip` Rust binding）或参考 clash-rs 对 smoltcp 的 patch。

### Phase TUN-2：增强能力

| 序号 | 任务 | 预估工时 |
|------|------|----------|
| T9 | Linux TUN 适配（fwmark 策略路由） | 4h |
| T10 | 路由分流规则引擎（GeoIP/域名规则/直连列表） | 6h |
| T11 | 进程级路由（socket → PID 反查 + 缓存） | 5h |
| T12 | UDP over QUIC 通道（tun-adapter → proxy-remote） | 5h |
| T13 | 性能优化（零拷贝、batch read/write） | 4h |
| T14 | 进程管理（Java 侧启动/监控/重启 tun-adapter） | 3h |
| T15 | 统一配置管理（proxy.yml → 自动生成 tun.toml） | 2h |
| T16 | 健康检查与自动降级（TUN → 系统代理 fallback） | 3h |

**Phase TUN-2 合计：~32h**

### Phase TUN-3：产品化

| 序号 | 任务 | 预估工时 |
|------|------|----------|
| T17 | Windows WinTUN 适配 | 6h |
| T18 | GUI/CLI 统一入口（一键开启 TUN 模式） | 4h |
| T19 | macOS privileged helper（SMJobBless，一次授权永久生效） | 4h |
| T20 | Linux polkit / setcap 自动权限获取 | 2h |
| T21 | 连接状态可视化（活跃连接数、流量统计、延迟监控） | 4h |
| T22 | 异常恢复增强（watchdog + 自动重启 + DNS 缓存清理） | 3h |

**Phase TUN-3 合计：~23h**

---

## 10. 与现有 Java 架构的衔接点

### 10.1 不需要改动的部分

- proxy-local 的 SOCKS5/HTTP CONNECT 处理逻辑 → 完全复用
- proxy-local → proxy-remote 的完整链路 → 完全复用
- Filter 链、集群容错、负载均衡 → 完全复用
- 编解码、加密、心跳 → 完全复用
- proxy-remote 的 Outbound 出站 → 完全复用

### 10.2 Java 侧可能的小改动

| 改动点 | 阶段 | 原因 | 工作量 |
|--------|------|------|--------|
| 无 | MVP | 核心代理链路零改动，tun-adapter 只是另一个 SOCKS5 客户端 | - |
| `ProxyLocalServer` 增加进程管理 | Phase 2 | 启动/监控 tun-adapter 子进程 | 低 |
| 配置文件扩展 | Phase 2 | `proxy.yml` 增加 `tun` 段，启动时生成 `tun.toml` | 低 |
| proxy-remote 增加 QUIC 监听 | Phase 2 | UDP over QUIC 通道支持 | 中 |
| proxy-local 对 loopback 跳过加密 | Phase 3 | 性能优化（已是本地通信） | 极低 |

### 10.3 架构示意

```
┌─────────────────────────────────────────────────────────┐
│                    用户空间                               │
│                                                          │
│  ┌──────────────────────┐    ┌────────────────────────┐ │
│  │   tun-adapter (Rust)  │    │  proxy-local (Java)     │ │
│  │                        │    │                          │ │
│  │  TUN 读写              │    │  SOCKS5 Server           │ │
│  │  IP 包解析             │    │  HTTP CONNECT Server     │ │
│  │  smoltcp TCP 栈        │──→│  Filter Chain            │ │
│  │  FakeIP DNS            │    │  ClusterInvoker          │ │
│  │  SOCKS5 Client         │    │  ExchangeClient          │ │
│  └──────────────────────┘    └──────────┬─────────────┘ │
│                                           │               │
│                                           ▼               │
│                                ┌─────────────────────┐   │
│                                │  proxy-remote (Java)  │   │
│                                │  Outbound Connector   │   │
│                                └──────────┬────────────┘  │
└───────────────────────────────────────────┼───────────────┘
                                            │
                                            ▼
                                     目标网站 (互联网)
```

---

## 11. 技术风险与对策

| 风险 | 影响 | 概率 | 对策 |
|------|------|------|------|
| smoltcp TCP 重组性能不足 | 高流量场景丢包/延迟 | 中 | 基准测试验证；备选方案：lwIP C 库通过 FFI 调用 |
| TUN 设备权限问题影响用户体验 | 需要 root/管理员 | 高 | macOS 用 Authorization Services；Linux 用 setcap；考虑 setuid helper |
| 路由回环（代理流量重新进入 TUN） | 网络中断 | 高 | 策略路由 + mark 标记 + proxy-local 绑定指定网卡出口 |
| tun-adapter 崩溃导致网络不可用 | 严重 | 中 | ShutdownHook 恢复路由；watchdog 自动重启；fallback 直连模式 |
| FakeIP 与系统 DNS 缓存冲突 | 域名解析异常 | 低 | TTL 设为 1；程序退出时清理 DNS 缓存 |
| Rust 与 Java 进程间通信延迟 | 性能损耗 | 低 | SOCKS5 走 localhost 回环，延迟 < 0.1ms；后续可优化为 Unix Domain Socket |

---

## 12. 健康检查与自动降级

### 12.1 问题场景

TUN 模式下，如果 tun-adapter 或 proxy-local 任一进程异常，用户将完全断网（所有流量被路由到 TUN 但无法处理）。必须有自动降级机制保证"宁可不代理，也不能断网"。

### 12.2 健康检查机制

tun-adapter 内置一个轻量级健康检查循环，定期验证 proxy-local 可达性：

```rust
async fn health_check_loop(socks5_addr: SocketAddr, interval: Duration) {
    let mut consecutive_failures = 0;
    loop {
        tokio::time::sleep(interval).await;
        match TcpStream::connect(socks5_addr).await {
            Ok(mut stream) => {
                // 尝试 SOCKS5 握手验证服务可用
                if socks5_handshake(&mut stream).await.is_ok() {
                    consecutive_failures = 0;
                } else {
                    consecutive_failures += 1;
                }
            }
            Err(_) => {
                consecutive_failures += 1;
            }
        }
        if consecutive_failures >= 3 {
            tracing::warn!("proxy-local unreachable, triggering fallback");
            trigger_fallback_mode().await;
        }
    }
}
```

### 12.3 降级策略

| 触发条件 | 降级动作 | 恢复条件 |
|----------|----------|----------|
| proxy-local 连续 3 次健康检查失败 | 所有新连接走直连（bypass） | proxy-local 恢复后自动切回代理 |
| tun-adapter 自身即将崩溃（panic hook） | Drop Guard 恢复路由表 | 用户手动重启或 watchdog 自动拉起 |
| proxy-local 进程退出 | Java 侧 SystemProxyManager 恢复系统代理设置 | 重新启动 proxy-local |

### 12.4 Java 侧配合

proxy-local 已有 `SystemProxyManager`（支持 macOS/Linux/Windows），在 TUN 模式降级时可自动切回系统代理模式，保证用户至少有基本的代理能力（虽然不是全局透明的）。

---

## 13. 性能预估

基于 smoltcp + Rust 的用户态 TCP 栈方案，参考 sing-box / clash-rs 的性能数据：

| 指标 | 预估值 | 条件 |
|------|--------|------|
| 吞吐量 | ≥ 2 Gbps | 单核，MTU 1500，大包场景 |
| 单包处理延迟 | < 50μs | IP 解析 + TCP 重组 + SOCKS5 转发 |
| 内存占用 | < 30MB | 1000 并发 TCP 连接 |
| CPU 占用 | < 5% | 日常浏览（100 并发连接） |
| 并发连接数 | ≥ 10,000 | 受 FakeIP 池大小和 fd 限制 |

瓶颈不在 tun-adapter，而在 proxy-local ↔ proxy-remote 之间的加密传输和网络延迟。

---

## 14. 与竞品对比

| 特性 | 本方案 | Clash Premium | sing-box | Surge |
|------|--------|---------------|----------|-------|
| TUN 实现语言 | Rust | Go | Go | C/ObjC |
| TCP 栈 | smoltcp | gVisor netstack | gVisor netstack | lwIP |
| 代理核心语言 | Java (Netty) | Go | Go | C/ObjC |
| 分层解耦 | ✅ 独立进程 | ❌ 单体 | ❌ 单体 | ❌ 单体 |
| HTTP/2 多路复用 | ✅ | ❌ | ✅ | ✅ |
| 插件化 (SPI) | ✅ | ❌ | 部分 | ❌ |
| 集群容错 | ✅ | ❌ | ❌ | ❌ |
| 跨平台 | macOS/Linux/Windows | 全平台 | 全平台 | Apple only |

本方案的独特优势在于**分层解耦**：TUN 层极薄（Rust 做得最好的事），代理层功能丰富（Java/Netty 生态的优势）。两者通过标准协议对接，各自可独立演进和替换。

---

## 附录 A：关键依赖库

| 库 | 用途 | 许可证 |
|----|------|--------|
| [tun2](https://crates.io/crates/tun2) | 跨平台 TUN 设备操作 | MIT |
| [smoltcp](https://crates.io/crates/smoltcp) | 用户态 TCP/IP 协议栈 | 0BSD |
| [tokio](https://crates.io/crates/tokio) | 异步运行时 | MIT |
| [hickory-dns](https://crates.io/crates/hickory-dns) | DNS 解析/构造 | Apache-2.0 |
| [fast-socks5](https://crates.io/crates/fast-socks5) | SOCKS5 客户端 | MIT |

## 附录 B：参考项目

- [clash-rs](https://github.com/Watfaq/clash-rs)：Rust 实现的 Clash，TUN + smoltcp 方案
- [sing-box](https://github.com/SagerNet/sing-box)：Go 实现，TUN + gVisor netstack
- [tun2proxy](https://github.com/blechschmidt/tun2proxy)：Rust，TUN → SOCKS5/HTTP 代理
- [leaf](https://github.com/eycorsican/leaf)：Rust 代理框架，支持 TUN inbound
