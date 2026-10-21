# 加密套件 v2：规范、迁移与验收

## 范围

修复旧 `chacha20` 的 counter=0 密钥流重叠问题。新增 `chacha20-poly1305-v2` 与 `aes-gcm-v2` SPI 实现；现有 `Cipher.encrypt/decrypt` 和外层四字节长度分帧不变。算法使用完整的 AEAD 实现，不手工组合 ChaCha20 和 Poly1305。

`chacha20`、`aes-gcm`、`aes-ctr-hmac` 保留原有线上格式，仅用于明确配置的兼容连接。新旧名称不可互换，不自动探测或降级。已有在线服务不因本次开发直接切换；新套件必须两端一起配置。旧 ChaCha 不应继续用于安全敏感连接。

## 记录格式

所有整数为大端。外层仍是 `[u32 record_length][record]`，与 HTTP/2、HTTP/3 DATA 帧边界无关。

| record 偏移 | 长度 | 含义 |
|---|---:|---|
| 0 | 4 | ASCII `SPC2`（协议版本） |
| 4 | 1 | suite：1 = RFC 8439 ChaCha20-Poly1305，2 = AES-256-GCM |
| 5 | 1 | direction：0 = 客户端发送，1 = 服务端发送 |
| 6 | 32 | 当前发送方向的随机 salt，每个 stream 独立生成 |
| 38 | 8 | 从 0 开始的记录序号 |
| 46 | 4 | 明文长度 |
| 50 | 可变 | AEAD ciphertext 后附 16 字节 tag |

前 50 字节完整作为 AAD。nonce 为 `00000000 || u64(sequence)`，不在同一派生密钥下随机碰撞。密钥为 HKDF-SHA256(master_key, salt, info)，info 为 ASCII `simpleplane/aead/v2/` 后接 suite 与 direction 两字节。master_key 必须恰为 32 字节的高熵共享密钥；不把 SHA-256 当作口令 KDF。salt 由系统 CSPRNG 生成，跨 stream 派生密钥重复概率由 256 位 salt 控制。

单条明文上限为 8 MiB + 1024 字节。每个方向最多发送 2^32 条记录、累计 64 GiB 明文；到限后失败并关闭该 stream，必须建立新 stream。发送序号即使加密失败也不得回退。每个 Cipher 实例有独立收发状态；接收端只在认证成功后固定对端 salt 并推进序号，拒绝当前 stream 内重复、跳号、乱序和方向反射。

这是预共享密钥记录层，不引入新的握手、业务层依赖或算法协商。**它不提供前向保密，也不解决新 stream 首条历史请求的跨会话重放**；防止这类重放需使用已验证的 TLS/QUIC 传输或另行设计经过认证的挑战握手，不把本次修复包装为完整安全协议。外层长度字段由传输层限制上限；内层长度由 AEAD 认证，两者必须一致，截断结束必须报错。

## 实例与配置

保留 SPI 默认单例行为供无状态组件使用，增加独立实例创建入口，Cipher 的生产加载点使用独立实例。每个 HTTP/2、HTTP/3 stream 一个 Cipher，编码和解码共享该实例的不同方向状态；Rust 同一 stream 内 clone 共享状态，不同 stream 创建 fresh session。serverSide 仅为密码模块配置，传输层不理解套件内部格式。

配置示例（双方填写相同的真实随机密钥，不使用文档示例作生产密钥）：

```yaml
cipher: chacha20-poly1305-v2 # 或 aes-gcm-v2
cipherKey: <恰好32字节的随机ASCII密钥>
```

用密码学安全的随机生成器产生密钥，例如 Python `secrets.token_hex(16)` 生成 32 字符（128 位熵）的 ASCII 配置值。高强度部署可用完整 256 位随机密钥的 ASCII 编码并在密钥配置入口解码；本次保留既有字符串配置语义。密钥不入库、不记录到测试报告。

## 验收要求

已执行的测试数据、环境、广域网与旧服务回归结果见 [crypto-v2-validation.md](crypto-v2-validation.md)。

1. RFC 8439 ChaCha20-Poly1305、RFC 5869 HKDF、NIST AES-GCM 已知答案向量；AES-CTR/HMAC 验证既有算法及格式，不因写法不同直接判错。
2. 独立参考实现生成固定的 v2 记录向量，Java/Rust 分别重放并双向解密；覆盖两个 suite、两个方向、空数据和分块边界。
3. 错密钥、所有头字段、salt、ciphertext、tag 篡改与截断必须拒绝；认证失败不推进接收状态；旧记录不能被 v2 接受。
4. 独立 stream/多密钥实例隔离、并发 nonce 唯一、clone 状态、计数器及字节上限测试。
5. 对真实 Handler 做逐字节拆分、多个记录合帧、16 KiB 边界、大数据、非法长度与不完整 endStream 测试。
6. 本地真实服务和远程隔离实例分别完成 Java/Rust 双向传输、并行 stream、载荷 hash 验证；远程原 9090 服务保留，并验证旧协议仍可通信。测试凭据与私钥不进入仓库。

## 标准依据

- [RFC 8439](https://www.rfc-editor.org/rfc/rfc8439.html)：标准 ChaCha20-Poly1305 AEAD。
- [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869.html)：HKDF。
- [NIST SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final)：AES-GCM 及 IV 唯一性要求。
