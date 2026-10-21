# Android 真机联网及 HTTP/3 延迟修复记录

## 已确认的原因

用户使用本地旧 APK，手机节点配置未修改；可用的电脑节点为
`54.172.101.190:9090`，协议 HTTP/3（QUIC），加密 chacha20。

检查时本地 APK 为 2026-07-13 19:08 生成的 `0.1.0` / versionCode 1，
默认节点为 `54.234.196.30:9090`，核心只实现 HTTP/2。修改地址不足以让该包连接 HTTP/3 服务。
旧包还早于当天分支中的 TCP 字节流完整性修复。

旧客户端在创建 TUN 后就报告“已连接”，未确认远程代理可用。HTTP/2 层也未验证
HTTP 状态码和加密 CONNECT_RESPONSE。故障节点的握手会阻塞调度循环，连直连流量也受影响。

## 本次修改

- 在 `android/client-mvp` 基础上创建本地 `fix/android-device-connectivity` 分支。
  HTTP/3 实现移植自 `upstream/feature/android-client-productionize` 的
  `48a8f02`，保留现有分流、多节点、统计、导入导出功能及 TCP 字节流修复。
- 节点可选择 HTTP/2 或 HTTP/3；协议、SNI 和证书随 JSON 配置保存，YAML 保留协议及证书字段。
- 新默认节点使用 `54.172.101.190:9090` / HTTP/3 / chacha20。
  仅自动迁移地址、端口、密钥、协议等均为原始默认值的单节点配置，保留自定义配置。
- HTTP/3 UDP socket 先执行 Android protect / 物理网络绑定，再发起 QUIC。
  使用与仓库桌面端相同的部署证书；其他节点使用公共根证书或导入的证书。
  正常证书链验证失败时，仅允许显式配置证书的精确匹配，以兼容当前部署使用 CA 证书作服务证书的情况。
- 两种协议都验证加密 CONNECT_RESPONSE 后才报告连接成功，保留与确认消息合并到达的数据。
  增加连接超时，异步建立代理流，避免故障节点阻塞直连和停止信号。
- 界面区分“VPN 已启动，代理连接尚未验证”、成功和失败，并显示核心错误。
- 当前原生核心只转发 IPv4；移除无法处理的 IPv6 TUN 路由，使用 Android 的 IPv4 回退。
- 应用版本改为 `0.1.1-http3-fix` / versionCode 2，生成包含三种 ABI 的本地调试签名 APK。

## 0.1.1 验证

- WSL/Linux 核心单元与集成测试：84 通过，1 项外网测试默认忽略。
  新增 HTTP 状态/加密 CONNECT 拒绝、握手超时、直连不被阻塞、QUIC socket protect、
  不受信任证书拒绝、65 条连续 HTTP/3 流和分片数据完整性回归。
- 单独运行外网测试：使用本机桌面端保存的节点凭据，通过 HTTP/3 核心和实际双向转发循环，
  `example.com:80` 与 `example.com:443` 均返回 `HTTP/1.1 200 OK`；HTTPS 目标证书正常验证。
  凭据未输出到日志。
- Android 配置测试：5 通过，覆盖 JSON/YAML 协议与证书保留、旧默认值迁移、自定义配置保留。
- Clippy 检查通过；仅在命令中豁免新工具链对原有 `fetch_update` 和
  `chunks_exact` 的提示，未修改这些无关代码。
- 三种 ABI 均完成 release 优化原生编译；APK 内各 `.so` 的 SHA-256 与最终构建输出一致。
  Debug APK 签名验证通过，签名证书与检查时的本地旧 APK 相同，可覆盖安装。

产物：`android-app/app/build/outputs/apk/debug/SimplePlane-0.1.1-http3-fix-20261021.apk`，
35,764,082 字节（34.11 MiB），Android 7.0+，arm64-v8a / armeabi-v7a / x86_64。

SHA-256：`BDD84E005404571C3E493B9F5A1AF08F34C88E89156A0621491A7DBD3A204D6A`。

## 真机发现的延迟问题与 0.1.2 修复

随后 ADB 成功连接 Redmi K70 Pro（Android 16 / API 36，arm64，4 KiB 页）。
允许 USB 安装后，已安装 0.1.1，并通过真机 JNI 加载测试（1 项）。用户手动允许 VPN，
确认网页能够打开，但网速很慢。日志确认 socket protect、物理网络绑定、QUIC、
FakeDNS 和 TUN 数据转发正常，实际使用移动网络。

进一步定位到两个共同放大延迟的问题：

- 连接缓存的互斥锁一直持有到目标 CONNECT 应答返回。一个不可达目标会让其他目标排队。
  日志中的后台连接约 6 秒后被拒绝，原来的故障切换又重试一次，排队接近 12 秒。
- 目标被拒绝也会清空共享 HTTP/3 连接。最后一个 `SendRequest` 被释放时，h3 主动关闭
  整个连接，其他正在使用的流同时报 `Connection closed by client`。流级重置还会错误地
  将共享连接标为死亡。

0.1.2 / versionCode 3 的修改：

- 每个节点单独缓存连接，只在创建共享连接时持锁；各目标使用克隆的发送句柄并发建连。
- 用 `TargetRejected` 区分已收到代理应答但目标不可达的情况，保留健康的共享连接，
  不重复尝试同一个目标，也不因此将整个 VPN 标成断开。
- 活跃 HTTP/3 流持有发送器和 QUIC endpoint，缓存替换不会提前关闭其他流。
- 只有连接驱动退出才通知所有流失效；流级错误不再使其他连接一起失败。
  健康状态广播保留最终值，便于晚订阅者读取。

核心测试：88 通过，1 项外网测试默认忽略；Clippy（全部目标）通过。
Android 配置测试 5 项通过。三种 ABI 的 release 原生库均重新编译；APK 内各库与构建输出
哈希一致，APK 签名验证通过，签名与 0.1.1 相同。ADB 覆盖安装成功，并确认手机上的
versionName 为 `0.1.2-http3-fix`、versionCode 为 3。
新增回归覆盖慢目标不阻塞其他目标、目标拒绝不替换缓存、释放连接缓存后活跃流继续收发、
单流重置不损坏共享连接，以及关闭状态对晚订阅者可见。前两类连接生命周期问题在修复前
可由测试复现。

最新产物：`android-app/app/build/outputs/apk/debug/SimplePlane-0.1.2-http3-fix-20261021.apk`，
27,667,098 字节（26.39 MiB），Android 7.0+，arm64-v8a / armeabi-v7a / x86_64，调试签名。
SHA-256：`A183479E04EE1DA4320477C72C42DCC3B08A9F76EB8807DBB04654F063DB3CB1`。

### 手机请求计时

`plane-core/examples/android_network_probe.rs` 编译成 Android 可执行文件，以 shell UID
运行。该 UID 属于 VPN 路由范围，应用自己的 UID 被排除，因此该探测确实经过手机 TUN。
DNS 返回 `198.18.0.85`，应用日志也确认四个 `example.com` 请求均走代理。
探测并发发出 1 个 HTTP 和 3 个 HTTPS 请求，验证 HTTPS 目标证书，只记录状态、字节数和耗时。

0.1.1 修复前基线（同一手机、同一节点、移动网络）：

| 请求 | DNS 耗时 | TLS 完成时间（自开始） | 总耗时 | 结果 |
| --- | ---: | ---: | ---: | --- |
| HTTP | 6 ms | — | 8579 ms | 200，928 字节 |
| HTTPS 1 | 8 ms | 9151 ms | 9462 ms | 200，928 字节 |
| HTTPS 2 | 12 ms | 9762 ms | 10072 ms | 200，928 字节 |
| HTTPS 3 | 12 ms | 10338 ms | 10654 ms | 200，928 字节 |

同一时段日志显示后台目标被拒绝、共享连接被关闭、网页请求排队，支持上述故障定位。

0.1.2 覆盖安装后，用户手动重连 VPN。同一手机、同一节点、同一移动网络下再次并发请求：

| 请求 | DNS 耗时 | TLS 完成时间（自开始） | 总耗时 | 结果 |
| --- | ---: | ---: | ---: | --- |
| HTTP | 6 ms | — | 1208 ms | 200，928 字节 |
| HTTPS 1 | 11 ms | 940 ms | 1243 ms | 200，928 字节 |
| HTTPS 2 | 9 ms | 1007 ms | 1370 ms | 200，928 字节 |
| HTTPS 3 | 11 ms | 1282 ms | 1584 ms | 200，928 字节 |

复测 DNS 返回 `198.18.0.22`，应用日志确认请求经过 TUN 和代理。新进程日志中，后台目标
两次被拒绝，但 `Connection closed by client` 和连接驱动失败均为 0；正常网页请求继续工作。
用户随后通过浏览器确认：“明显变快，使用正常”。

这两批小请求的耗时由 8.6～10.7 秒降至 1.2～1.6 秒。这是一次修复前后的请求延迟对比，
不代表下载带宽或所有网络下的长期性能。测试工具可按需重新编译、推送运行：

```text
cd plane-core
cargo ndk -t arm64-v8a -P 24 build --release --example android_network_probe --locked
adb push target/aarch64-linux-android/release/examples/android_network_probe /data/local/tmp/simpleplane-network-probe
adb shell chmod 755 /data/local/tmp/simpleplane-network-probe
adb shell /data/local/tmp/simpleplane-network-probe
```

运行前确认 VPN 已开启，shell UID 在 VPN 路由范围内；以 FakeDNS 地址和应用日志核对流量路径。

Wi-Fi/移动网络切换、长时间运行和吞吐量尚未验证。
