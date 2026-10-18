# Proxy Remote 连接监控与部署

LogLens Agent 将 Proxy Remote 连接遥测与服务日志采集合并到同一个 JVM、会话存储和本地 Web 面板中。SimplePlane 业务源码无需改动；Agent 通过 Byte Buddy 在运行时观察代理服务类。

## 观测内容

- 物理入站连接的 peer 地址、HTTP/2 或 HTTP/3 类型、建立和断开事件。
- 逻辑会话的 Stream ID、目标 host/port、connecting/active/closed 状态和失败关闭事件。
- OutboundSession.forward(byte[]) 的上行长度与 writeBack(byte[]) 的下行长度。运行时按配置周期写入累计字节快照，并在会话结束时写入最终值。
- 如果目标 JVM 使用受支持的 Logback，Logback 适配器还会采集日志快照。连接/Stream ID 可用于面板中检索相关日志；没有可验证关联字段时不会伪造关联。

连接遥测使用强类型 TelemetryEvent，不进入日志处理器折叠链。它与日志共用 Agent 生命周期、有界队列、会话目录、HTTP/SSE 服务和 UI。

## Java 版本与兼容性

LogLens runtime 使用 Java 17 API，目标服务 JVM 必须为 Java 17 或更高版本。Agent 外层入口以 Java 8 字节码编译；Java 8 JVM 会按兼容性策略禁用监控并让服务继续启动，除非配置了 strict 模式。SimplePlane 服务本身以 Java 8 目标编译，不代表 Agent 能在 Java 8 runtime 上运行。

Java 17 和 21 有实际 Agent 兼容性验证。匹配器针对 Proxy Remote 的类名和方法签名；更换服务端版本后应确认目标类仍存在，并执行实际启动检查。

## 配置

启用结构化存储、loopback Web 服务和 Proxy Remote 遥测：

```yaml
schemaVersion: 1
application: simpleplane-proxy
output:
  directory: ./loglens-data
  structured:
    enabled: true
proxyRemote:
  enabled: true
  sampleIntervalMillis: 1000
  maxTrackedConnections: 10000
  maxTrackedSessions: 50000
web:
  enabled: true
  host: 127.0.0.1
  port: 9527
```

日志和遥测共享该 output.directory 下的 LogLens 会话。配置中的活动连接/会话上限和采样间隔可按机器资源调整；所有配置改动都需要重启 Agent 所在 JVM。

## systemd 部署

构建发布包后，将 dist/loglens-agent.jar 与配置文件放在目标主机可读的固定目录。Agent 参数必须注入**实际加载 Proxy Remote 服务类的 JVM**。systemd 可使用服务级 drop-in：

```ini
[Service]
Environment="JAVA_TOOL_OPTIONS=-javaagent:/opt/loglens/loglens-agent.jar=config=/opt/loglens/loglens.yaml"
```

如果该服务已设置 JAVA_TOOL_OPTIONS，应合并已有参数，不能覆盖掉原值。启用或更新 drop-in 后：

```bash
sudo systemctl daemon-reload
sudo systemctl restart proxy-remote.service
sudo systemctl is-active proxy-remote.service
```

重启会终止已有代理连接并造成短暂不可用。切换后应确认服务 active、面板由该服务 JVM 持有、/api/health 中 accepting 与 outputHealthy 均为 true。若 Agent 初始化使服务无法正常启动，移除 LogLens drop-in、执行 daemon-reload 并重启，可恢复原服务启动方式。

本次实际部署的文件位置如下；主机地址和 SSH 私钥不写入仓库：

| 用途 | 路径 |
|---|---|
| Agent 与配置 | /home/ec2-user/loglens-monitoring/loglens-agent.jar、/home/ec2-user/loglens-monitoring/proxyremote.yaml |
| 日志与遥测数据 | /home/ec2-user/loglens-data |
| systemd 覆盖 | /etc/systemd/system/proxy-remote.service.d/loglens.conf |
| systemd 单元 | proxy-remote.service |

部署时服务运行在 Corretto 17.0.20。最终健康检查确认面板监听进程 PID 与 proxy-remote.service 的 MainPID 一致。脱敏结果见 [部署验证证据](sdd/evidence/proxyremote-deployment.json)。

## 本地访问

Web 服务绑定 127.0.0.1:9527，不要在防火墙或反向代理中将它公开到公网。在本地终端建立 SSH 隧道：

```powershell
ssh -i <key.pem> -N -L 9527:127.0.0.1:9527 ec2-user@<server>
```

然后打开 http://127.0.0.1:9527/ 并选择“连接”视图。面板会校验 Host、Origin 和本地端口，因此 SSH 隧道两端端口应一致；若本地端口冲突，需同时修改服务端 web.port 和隧道映射端口。保持 SSH 进程运行即可维持隧道。

## 数据边界与限制

代理 payload 不会被 Agent 读取、解密、保存或传给后台线程。字节统计只读取 byte[] 长度。socket peer、Stream ID 和目标地址是网络元数据，不是账号身份。关闭原因只反映 Agent 可观察到的会话/通道状态，不保证包含远端应用层错误细节。

物理连接和逻辑会话跟踪均有容量上限；遥测与日志共用有限队列。达到跟踪上限或队列满时会递增 drop 指标，遥测不能承诺零丢失。活动快照只包含当前仍跟踪的连接/会话；历史页面查询已落盘事件。没有活动连接时，连接列表为空属于正常状态。

配置默认关闭 Proxy Remote 监控。Agent 初始化遵循 fallback/strict 策略；监控线程和 Web 面板随目标 JVM 生命周期启动与停止。
