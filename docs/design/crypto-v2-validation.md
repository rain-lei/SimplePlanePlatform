# 加解密改造验证报告

验证日期：2026-10-20。范围：Java `proxy-crypto`、Cipher 的 SPI 生命周期、HTTP/2 / HTTP/3 加解密边界、Rust `plane-core` 的套件选择与出站互通。协议定义见 [crypto-v2.md](crypto-v2.md)。

## 改造结果与架构边界

新增 `chacha20-poly1305-v2` 和 `aes-gcm-v2`，算法使用完整标准 AEAD 实现。共享的记录层负责 HKDF-SHA256 派生、salt、单调序号、方向分离、认证头、长度与每个派生密钥的使用上限。`Cipher.encrypt/decrypt` 签名、ProxyMessage 编码和外层四字节分帧保留；传输模块不知道 v2 内部字段。

SPI 原有单例 API 保留，新增独立实例 API，仅 Cipher 的加载点改用它；HTTP/2、HTTP/3 每条 stream 一个 Cipher。Rust 同一 stream 内 clone 共享密码状态，跨 stream 生成新实例。必要的端点身份由 `CipherConfig.serverSide` 传入，不引用 Netty 类型。修复了不同配置重复初始化同一个密码单例的风险，并统一配置字符串的 UTF-8 编码。

互通测试还发现旧 Rust 出站报文的 `streamId` 恒为 0，多路连接会命中服务端同一个逻辑会话；DATA 也错误地携带控制请求 ID。只在 Rust 出站构造处补上独立 streamId、DATA requestId=0，并在 DISCONNECT 中保持相同 streamId。Java 服务端的会话架构没有为此修改。

其他必要边界修复：HTTP/2 密文长度上限与结束时半包检查；HTTP/3 输入关闭时不接受残留半个密文记录；Rust 密文长度及 EOF 残包检查。URL 的日志字符串对 `cipherKey` 脱敏，实际配置值保持不变。

旧 `chacha20` 仅保留兼容，启动会告警；它仍然有已确认的密码结构缺陷。旧 AES-GCM、AES-CTR-HMAC 保留线上格式，通过独立参考向量与真实链路回归，本轮没有证据支持把它们一并判定为错误并重写。所有旧套件均不会被 v2 自动探测或回退。

## 测试环境与工具

| 项目 | 环境 / 实现 |
|---|---|
| Java 最终全量回归 | 本机 WSL Ubuntu 22.04，OpenJDK 17.0.18，Maven 3.9.11；源码目标 Java 8 |
| Java 密码库 | Bouncy Castle 1.70 高层 ChaCha20Poly1305；JCA AES/GCM/NoPadding |
| Rust | WSL 原生 Linux stable；chacha20poly1305 0.10.1、aes-gcm 0.10.3、hkdf 0.12.4，Cargo.lock 固定依赖 |
| 独立参考向量 | Python cryptography 50.0.2 的 OpenSSL 后端；生成器不调用 Java/Rust 实现 |
| 远程验证 | 用户指定的 54.172.101.190；测试服务只监听回环，原 9090 服务保留 |
| 传输完整性 | Java SHA-256 对比；Rust 逐字节比较；不要求 TCP/HTTP 帧与记录一一对应 |

Windows 上运行全套 HTTP/3 测试遇到 `netty_quiche42_windows_x86_64` 原生库访问违规；没有计作通过。最终 QUIC / TLS / HTTP/3 结果来自 Linux 的真实 UDP 测试。Windows 原生 Java 另用于跨网络互通探测。

## Java 全量结果

命令（仓库根目录）：

```bash
mvn -B -pl proxy-remote -am package '-Dtest=!BaiduProxyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| proxy-common | 16 | 0 | 0 | 0 |
| proxy-crypto | 39 | 0 | 0 | 1 |
| proxy-transport-netty | 20 | 0 | 0 | 0 |
| proxy-transport-http3 | 14 | 0 | 0 | 0 |
| proxy-remote | 28 | 0 | 0 | 0 |
| 合计 | **117** | **0** | **0** | **1** |

即 116 项执行通过。跳过项是会改写旧向量文件的生成器，现已要求显式设置 `-Dcrypto.generateLegacyVectors=true` 才运行，避免每次测试随机覆盖基准；旧向量重放正常执行。依赖外部百度站点的 BaiduProxyTest 被命令显式排除，不属于确定性加密验收。

重点覆盖：

- RFC 8439 §2.8.2 的完整 ChaCha20-Poly1305 已知答案、RFC 5869 HKDF、NIST AES-GCM 向量；独立 OpenSSL AES-CTR-HMAC 旧格式向量。
- 40 组固定 v2 记录：2 套算法 × 2 个发送方向 × 10 条连续记录。Java/Rust 分别校验派生密钥、实际加密完整字节与实际解密结果。
- 对记录中每个字节逐一翻转，以及对所有截断长度尝试解密；所有篡改拒绝。失败后合法原记录仍可被接收，证明接收状态没有提前推进。
- 错误密钥、旧格式输入、方向反射、乱序、重复、同 stream 混入其他 salt、非法密钥长度、重复初始化、超限明文、计数器和字节上限。
- Java 8 线程并发生成 256 条记录，排序后逐条解密，序号不重复；不同密钥与不同实例互不覆盖。
- 空数据、1/15/16/17/31/32/33/63/64/65 字节、16 KiB 前后、2 MiB；空明文在 v2 中仍生成带认证标签的记录。
- 真实 HTTP/2 Handler 的逐字节拆分、7 字节拆分、16 KiB 拆分、多记录合帧及 1 MiB 大消息；非法超长和截断结束关闭 stream；认证失败没有明文输出。
- 真实 HTTP/3 UDP/TLS 的新旧套件往返、CONNECT 后 DATA、51 条并行 stream；真实 proxy-remote 到 TCP echo 的 `2 MiB + 1` 单条记录往返，覆盖 QUIC 与 AEAD 记录的多次切分。
- 原有 TCP 出站建立、失败连接、DISCONNECT、并发会话、服务关闭清理分别在 none / v2 ChaCha / v2 AES-GCM 下复用执行。

## Rust 全量结果

```bash
cd plane-core
cargo test --all-targets --locked
cargo clippy --all-targets --locked -- -D warnings
cargo fmt --all -- --check
```

完整库 69 项通过，0 失败；1 个需要外部服务的测试默认 ignored，已通过下述真实服务脚本显式执行。Clippy 零警告，格式检查通过。没有为 Windows 构建而改动 Android / Unix 的业务架构，完整库在 Linux 上验证。

除共享固定向量外，覆盖 Rust 的逐字节篡改、截断、长度边界、双向收发、错误密钥、乱序、反射、混入其他 stream、并发计数器、密钥使用上限。另验证 clone 共享序号而 fresh_session 使用新 salt；不支持的名称直接报错。代码接线已将 JNI 配置的 cipher 传到调度器与密码模块，本报告不把原生库测试冒充为真实 Android 设备上的 JNI/VPN 验收。

## 真实进程互通矩阵

入口：

```bash
bash scripts/crypto/run-local-interop.sh
```

脚本创建仅监听回环的真实 ProxyRemoteServer、独立 TCP echo 目标及客户端，使用随机逻辑 streamId，交错传输后清理自己创建的进程。测试密钥只用于回环实例；不是部署密钥。

| 客户端 | 套件 | stream 数 | 每次验证的数据字节数 | 结果 |
|---|---|---:|---:|---|
| Java | chacha20-poly1305-v2 | 4 | 8,388,608 | SHA-256 一致 |
| Java | aes-gcm-v2 | 4 | 8,388,608 | SHA-256 一致 |
| Java | 旧 chacha20 | 4 | 8,388,608 | SHA-256 一致 |
| Java | 旧 aes-gcm | 4 | 8,388,608 | SHA-256 一致 |
| Java | 旧 aes-ctr-hmac | 4 | 8,388,608 | SHA-256 一致 |
| Rust | chacha20-poly1305-v2 | 4 | 8,389,120 | 逐字节一致 |
| Rust | aes-gcm-v2 | 4 | 8,389,120 | 逐字节一致 |
| Rust | 旧 chacha20 | 4 | 8,389,120 | 逐字节一致 |

Rust 使用 128 × 16,385 字节/stream，刻意跨过 HTTP/2 默认 16 KiB 边界。Java 使用每 stream 2 MiB 并交错发送。两个新套件还分别通过真实服务端错误密钥及旧套件连接拒绝测试，不能静默降级。Java 其他两个旧算法未添加到 Rust，这不是本次互通范围。

相同 Java 五套件矩阵也在远程机器的隔离回环实例执行，每套件 4 stream、8 MiB，全部 hash 一致。远程路径与本地路径是不同验证项，回环结果不冒充广域网吞吐或稳定性数据。

## 广域网与在线服务

使用 SSH 端口转发访问远程回环测试实例，不新增公网监听端口。广域网存在显著等待，初始 8 MiB / 大量停等轮次未在测试截止时间内完成；没有将这些运行记为成功，也没有据此降低认证要求或改动生产探活参数。最终广域网验收使用 4 stream、约 1 MiB；大数据与拆分由上面的本地及远程回环验证覆盖。

最终跨网络使用 Windows 原生 SSH 转发，仅绑定 WSL 虚拟网卡地址；Java 使用 Windows Corretto 17，Rust 使用 WSL 原生运行时。SSH 隧道发生过连接重置，恢复通道后重新验证；连接拒绝或重置的运行没有计入成功结果。

| 本地客户端 | 远程套件 | stream 数 | 验证字节数 | 结果 |
|---|---|---:|---:|---|
| Java | chacha20-poly1305-v2 | 4 | 1,048,576 | SHA-256 一致 |
| Rust | chacha20-poly1305-v2 | 4 | 1,048,608 | 逐字节一致 |
| Java | aes-gcm-v2 | 4 | 1,048,576 | SHA-256 一致 |
| Rust | aes-gcm-v2 | 4 | 1,048,608 | 逐字节一致 |

Rust 样本为每 stream 8 条、每条 32,769 字节，新 ChaCha 与 AES-GCM 的测试执行时间分别约 66.46 秒和 61.23 秒。这里包含真实网络与 SSH 等待，不是密码算法性能基准，不能用来推算服务端加解密吞吐。

另在远程以最新客户端连接**原 9090 HTTP/3 旧 chacha20 服务**，完成 1 stream、262,144 字节 TCP echo，SHA-256 一致。测试直接读取现有配置但不打印密钥，没有替换线上包或修改其配置。原服务进程 PID 为 2380668，启动时间保持 `2026-10-20 02:45:32`（远程系统时间）。测试专用回环实例、echo 和 SSH 隧道在验收后关闭，保留原服务。

最终 Linux 服务包 SHA-256：

```text
370bc05af8cefb4d2d4a28664090ff188ae785386e85eaabc1084ccc0b410c10
```

本次没有把原 9090 切换为 v2。因此，“新实现已验证”和“现有部署已消除旧 ChaCha 风险”是不同状态；本报告只确认前者。后续上线时两端必须同步选择新套件和满足要求的新密钥。

## 重现与持续回归

- 固定向量：`docs/design/crypto-v2-vectors.tsv`、`crypto-legacy-ctr-vector.tsv`。通过 `scripts/crypto/generate-vectors.py` 人工生成，不在常规测试中覆盖。
- 默认端到端脚本运行新套件和所有 Java 旧套件；`CRYPTO_SKIP_RUST=1` 供只安装 Java 的隔离机器运行。
- `remote-fixture.sh` 管理测试专用实例；`test-remote-over-ssh.sh` 提供 opt-in 远程验证。SSH 私钥不写入源码或报告，临时副本权限 600 并由退出 trap 删除。
- android-core CI 已启用此前占位的 interop job：运行真实 Java / HTTP2 / HTTP3 回归并打包服务端，再执行 Java/Rust 矩阵。向量、密码模块与相关传输代码变更会触发。CI 本身须在提交后由托管环境执行，本报告不把本地执行冒充为远程 CI 通过。

## 安全结论的边界

本次确认 v2 的密码算法符合已知答案向量，两种语言与独立参考实现一致，认证失败不会发布对应明文，旧通信格式不会因原地修改算法而被破坏。这是工程回归与标准符合性验证，不是第三方完整密码协议审计。

v2 的流内序号校验不能阻止新 stream 首条历史请求的跨会话重放；预共享密钥记录层也不提供前向保密。这两项没有通过不完整的新握手临时实现，使用已验证的 TLS/QUIC 传输或另行设计认证握手解决。迁移期间保留的旧 chacha20 依然不安全；线上切换必须客户端与服务端同步，认证失败不回退。
