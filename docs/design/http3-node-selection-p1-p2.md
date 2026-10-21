# HTTP/3 多节点手动切换：实现与验证

本文对应 [改造方案](node-selection-and-failover-plan.md) 的 P1、P2。范围是 Java local 的 HTTP/3 代理链路、Web Dashboard 与桌面端；HTTP/2 继续沿用原有选择逻辑。自动故障切换、节点熔断与恢复探测属于 P3。

HTTP/3 每个节点固定一条 QUIC 连接，配置要求 `connectionsPerNode: 1`。仅按需连接当前所选节点，不预建备用节点连接；节点内部不再进行连接池管理或负载均衡。

## 使用

Dashboard 或桌面端可以添加多个 HTTP/3 节点，在节点卡片上勾选“使用此节点”，然后保存。切换会关闭原节点的代理流及客户端连接，应用需要刷新或重连；直连流保持不变。

Java 运行时配置示例（证书路径和密钥必须替换为自己的配置）：

```yaml
localPort: 1080
connectionsPerNode: 1
timeoutMs: 8000
systemProxy:
  enabled: false
remoteServers:
  - id: node-a
    name: 主节点
    host: node-a.example.com
    port: 9090
    transport: http3
    cipher: aes-gcm-v2
    cipherKey: "0123456789abcdef0123456789abcdef"
    http3:
      serverName: node-a.example.com
      caFile: /path/to/trusted-ca.pem
  - id: node-b
    name: 备用节点
    host: node-b.example.com
    port: 9090
    transport: http3
    cipher: aes-gcm-v2
    cipherKey: "0123456789abcdef0123456789abcdef"
    http3:
      serverName: node-b.example.com
      caFile: /path/to/trusted-ca.pem
nodeSelection:
  mode: manual
  selectedNodeId: node-a
```

密钥示例仅用于说明字段格式，不应作为实际部署密钥。加密套件及密钥须与对应 remote 保持一致，本次没有更改帧格式或加解密协议。

命令行必须显式提供外部配置路径：

```sh
java -jar proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar /path/to/proxy.yml
```

Dashboard、桌面端和仓库启动脚本会传入配置路径。无参数启动仍可读取 classpath 默认资源，但该资源不能热更新。旧的单节点 HTTP/3 配置在加载时迁移为手动模式，缺省逻辑 ID 为 `legacy-node`；迁移不会主动重写原文件。已有多节点配置需显式提供唯一 ID 和手动选择配置。

桌面端保存 `nodes` 和 `selected_node_id`，同时保留所选节点的旧 `remote` 镜像供既有代码读取；生成 `proxy-java.yml` 时输出全部节点及选择信息。导入 YAML 时保留已有节点 ID、名称和 HTTP/3 参数。

## 运行规则

- CONNECT 选择具体 Invoker；DATA、DISCONNECT 仅使用原绑定，不重新负载均衡，也不重放 DATA。
- 绑定仅保存流对应的 Invoker 和建流是否完成。连接断开时清理所属请求和流；重连后由应用重新建流，不接续旧流。
- 手动模式不会使用备用节点兜底。所选节点暂时不可达仍可应用选择，面板显示等待建连或节点不可用，实际建流失败会返回错误。
- 改变所选节点、当前节点连接参数或建流超时会释放旧资源；仅更改备用节点、名称或不参与 HTTP/3 选择的负载均衡字段，不关闭当前健康流。
- CONNECT 在协调线程中建立传输，最多容纳 128 个待完成建流请求。排队、传输握手和控制请求共享建流预算；取消后不再后台建流。
- 入口 HTTP CONNECT、SOCKS5 成功响应入队后才放行早到的下行数据。早到下行最多缓存 1 MiB/1024 帧，入口建流阶段缓存同样有大小限制。
- 本次没有加入自动重试策略、熔断或主动探测；单次建流失败向应用报错，由应用重连。

## 配置应用与状态

配置以临时文件原子替换；Java 每 500 ms 比较内容摘要，合并重复变更，并且校验后才提交。非法节点 ID、密钥、证书、传输参数或不受支持的模式不会替换现有运行时配置。

状态位于 `<外部配置路径>.status.json`，包含进程实例 ID、心跳、保存/应用摘要、当前节点、连接状态、活跃绑定数和应用错误，不包含密钥。界面区别“已保存”和“已应用”；状态超过 10 秒未更新视为过期。启动新进程前清除旧状态文件，正常停机发布停止状态。

本地监听端口、入口协议开关、系统代理和路由规则变更需要重启代理，不能通过节点热切换生效。节点参数仍可热更新。TUN 启动时生成包含全部候选节点地址的直连豁免配置；TUN 运行时改变节点地址列表须先停止 TUN，重新配置路由后启动。手动启动 TUN 时同样需要保证所有候选节点 IP 已豁免。

## 验证入口

确定性 Java 回归（包含依赖模块和本地真实网络测试，排除依赖外部百度站点的测试）：

```sh
mvn -B -pl proxy-local -am test '-Dtest=*Test,!BaiduProxyTest' -Dsurefire.failIfNoSpecifiedTests=false
node --test dashboard/test/*.test.js
python scripts/test-node-selection-ui.py
```

`test-node-selection-ui.py` 使用本机 Chrome/Chromium（可通过 `CHROME_BIN` 指定）；运行真实页面 JavaScript，模拟 Tauri IPC/HTTP API，检查双节点恢复、选择与保存、TLS/路由参数保留、删除当前节点后的选择回退，并输出页面截图至 `.build/node-selection-ui/`。它不替代 Tauri 原生编译。

关键验收测试：

| 测试 | 验证范围 |
|---|---|
| `StickyClusterInvokerTest` | 固定连接、DATA 不改投、重复/失败 CONNECT 清理、迟到成功不复活 |
| `ConnectionOwnershipTest` | 多连接共享注册表的精确清理、响应来源校验、早到数据等待握手 |
| `NodeSelectionConfigTest` | 旧配置迁移、无效配置拒绝、备用节点编辑与手动选择应用 |
| `Http3ExchangeIntegrationTest` | 两个独立 QUIC 客户端共享注册表；关闭 A 后 B 正常；三种加密套件隔离 |
| `Http3ManualSwitchIntegrationTest` | 浏览器 TCP → local → 两个真实 HTTP/3 remote → 目标 TCP；A 切 B、备用节点编辑不打断当前流、非法配置保留旧流、128 KiB 分片字节比对、普通 HTTP 首包、远端 session/Future 回收 |
| 同一全链路测试的独立进程用例 | 从 Java main 启动 local，验证状态文件、配置热加载、HTTP CONNECT/SOCKS5 与提前业务字节 |
| Dashboard `node-selection.test.js` | 节点校验、YAML 保留、原子替换、状态过期与候选节点 TUN 豁免 |
| Rust `config::node_tests` | 桌面端旧配置迁移、多节点 YAML/密钥转义、错误选择拒绝、导入 ID 与证书参数保留 |

桌面应用的原生编译与 Rust 配置测试交给 GitHub 的桌面构建流程，本地页面冒烟不代表原生应用已编译成功。验证使用独立回环节点、临时证书和临时配置，测试配置关闭系统代理。

## 本次验收记录

- Java reactor `verify` 与 JAR 打包成功：192 个用例，189 个通过，3 个按原有条件跳过，无失败；排除了依赖外部百度站点的 `BaiduProxyTest`。
- 显式开启 HTTP/3 生命周期诊断：10 类关闭、取消及失败场景各循环 8 次，结束后客户端流、远端流、session 和目标连接均回到 0。对应参数为 `-Dhttp3.lifecycle.probe=true -Dhttp3.lifecycle.limit=4 -Dhttp3.lifecycle.cycles=8`。
- Dashboard 的 29 个 Node 测试全部通过；桌面和 Dashboard 的真实页面 JavaScript 浏览器冒烟通过，覆盖多节点选择、保存、TLS 参数及 v2 加密套件保留。
- 打包 JAR 冒烟通过：HTTP/3 传输可加载，不可达节点在预算内返回 502，原子替换配置完成 A → B 切换，进程实例不变。构建后可运行 `python scripts/smoke-http3-node-selection.py` 复现。
- 桌面 Rust 配置测试已接入 GitHub release 工作流；原生编译及这些 Rust 测试的结果以 GitHub 实际执行结果为准，本地未宣称通过。

本地验收日志位于 `.build/node-selection-final.log`、`.build/node-selection-dashboard.log` 和 `.build/node-selection-jar-smoke.log`。P3 的自动切换、熔断和探测不在本次验收范围内。

单连接简化后重新执行完整 Java `verify`，仍为 192 个用例、0 失败、0 错误、3 个条件跳过，包含三种加密套件及独立进程的真实切换链路，10 类生命周期检查各完成 8 次。新增断言拒绝多连接配置，并确认无关负载均衡字段不触发连接重建。Dashboard 29 个测试和两个页面的浏览器冒烟也重新通过。对应日志为 `.build/http3-single-connection-verify.log` 和 `.build/http3-single-connection-dashboard.log`。

进一步精简：移除连接 UUID、物理连接代次、绑定诊断元数据和切换代次判断；保留 `StickyClusterInvoker` 的 ClusterInvoker 接口、SPI 及流绑定；当前 HTTP/3 只向其提供所选节点的一个 Invoker。配置应用和建连由同一个协调线程按顺序执行。保留配置监听、旧连接及流的清理、超时取消和浏览器握手前的数据暂存。

这轮精简后完整 Java `verify` 再次通过：192 个用例，0 失败、0 错误、3 个条件跳过，10 类 HTTP/3 生命周期各循环 8 次并完成资源回收，真实三套加密及独立进程切换用例全部通过。日志为 `.build/http3-simplified-verify.log`。

CI 随后发现旧节点 session 回收的关闭时序问题：切换时 DISCONNECT 发出后立即关闭传输，正常浏览器断开又存在重复关闭路径。修复为切换时最多等待 2 秒的 DISCONNECT 确认后关闭传输（超时仍允许切换）；协调器只取消未完成 CONNECT，已建立流由 RelayHandler 断开。新增等待确认的单元测试，并在真实切换测试中要求发布 B 状态时 A 的 session 已清零。修复后本地完整 `verify` 为 193 个用例、0 失败、0 错误、3 个条件跳过。

进一步核查 CI 日志确认：清理时构造的 DISCONNECT 使用空目标地址，在客户端域名限流 Filter 中被拒绝，导致通知没有发到远端。绑定现在保留原目标地址与端口用于构造断开请求；回归测试经过生产实际的客户端 Filter 链，确保断开通知确实发出。没有恢复连接 UUID、代次或诊断元数据。最终回归日志为 `.build/http3-ci-fix-verify.log`。
