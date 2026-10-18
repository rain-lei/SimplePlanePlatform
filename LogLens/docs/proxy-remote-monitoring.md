# Proxy Remote 连接监控

连接监控由 LogLens Agent 提供。SimplePlane 的业务源码不需要改动；部署时只更新 LogLens 产物、服务端 JVM 启动参数和 LogLens YAML。

## 运行要求

LogLens runtime 使用 Java 17 API，监控运行需要 Java 17 或更高版本。Agent 外层 premain 以 Java 8 字节码编译，因此服务端若运行在 Java 8，默认模式会输出 `[LogLens] COMPATIBILITY_ERROR` 并禁用监控，服务本身继续启动；使用 `strict=true` 时按严格初始化策略终止启动。SimplePlane 编译目标为 Java 8 不代表监控 Agent 能在 Java 8 JVM 运行。

## 配置

在服务端 LogLens 配置中启用遥测，并给日志/遥测选择独立会话目录。示例：

```yaml
schemaVersion: 1
application: simpleplane-proxy
output:
  directory: ./loglens-data
  structured:
    enabled: true
web:
  enabled: true
  host: 127.0.0.1
  port: 9527
proxyRemote:
  enabled: true
  sampleIntervalMillis: 1000
  maxTrackedConnections: 10000
  maxTrackedSessions: 50000
```

将现有 Java 服务启动命令追加 Agent 参数：

```text
-javaagent:/path/to/loglens-agent.jar=config=/path/to/loglens.yaml
```

服务启动后在本机打开 `http://127.0.0.1:9527/`，切换到“连接”视图。默认配置只接受 loopback 请求。需要远程查看时，通过 SSH/VPN 隧道访问该本地端口，不要把 Web 端口直接暴露到公网。

## 展示与边界

面板显示入站 socket 地址、HTTP/2 或 HTTP/3、Stream ID、目标 host/port、会话状态、上下行累计字节以及会话/连接事件；可按地址、传输、目标、状态和时间范围筛选，并从 Stream ID 搜索对应日志文本。活动连接与历史事件分别查询，日志与遥测共享 LogLens 会话目录、有限队列和实时 SSE。

代理 payload 不被读取、解密、保存或作为事件传递。socket 地址和 Stream ID 只是网络连接标识，不代表用户账号。关闭原因只反映 Agent 可观察到的会话/通道状态；它不保证包含远端应用层错误详情。

监控配置默认关闭。活动跟踪有连接数、会话数上限；上限或遥测队列满时递增 drop 指标，不能把无丢失作为承诺。Agent 初始化遵循已有 fallback/strict 策略。
