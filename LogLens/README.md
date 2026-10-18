# LogLens：Java 本地日志工作台

通过 Java Agent 与 Byte Buddy 接入日志，使用 Disruptor 缓冲事件，通过 SPI 定制责任链，由本地 YAML 驱动规则。处理结果按等级保存，并在本地网页中展示。

当前已有运行实现和阶段性验证，正在按 SDD 完成第一版验收。LogLens 为暂定名称；完整交付尚未验收通过。

- [详细架构设计](docs/loglens-architecture.md)

设计接入方式：

```bash
java -javaagent:loglens-agent.jar=config=./loglens.yaml -jar app.jar
```

本仓库的开发流程与证据见 [需求规格](docs/sdd/requirements.md)、[设计契约](docs/sdd/design.md)、[任务追踪](docs/sdd/tasks.md)、[验证记录](docs/sdd/verification.md)。

使用 IDEA 配套工具构建（PowerShell；脚本默认 IDEA 位于 D 盘，可通过 IdeaHome 参数修改）：

```powershell
& ./scripts/build.ps1 -MavenArguments @('verify')
& ./scripts/package-release.ps1
```

脚本将 Maven 仓库、JVM 临时目录和用户目录定向到工作区 `.build/`；模块产物在各自 `target/`。项目必须放在非 C 盘。Python 脚本不依赖第三方包。

接入应用时使用 `dist/loglens-agent.jar`，配置路径相对启动目录；输出和插件目录相对 YAML。示例配置为 [loglens.yaml](loglens.yaml)，默认 observe，网页默认 `127.0.0.1:9527`。改 YAML 或插件后必须重启应用。仅声明 Maven 依赖不会启动 Agent。

`dist/loglens-api.jar` 可用于编译外部 SPI 插件；release 脚本会统一生成 Agent、Viewer 和 API 三个公开 JAR，并检查入口类与 premain manifest。打包命令跳过测试，发布前应先运行 `verify`。

历史查看与导出：

```powershell
java -jar dist/loglens-viewer.jar '<session-directory>' 9527 '<scratch-directory>'
java -jar dist/loglens-viewer.jar export '<session-directory>' '<report.html>' '<scratch-directory>'
```

scratch 和导出文件应位于原会话外。开发环境运行 Java 时也应显式设置 `-Djava.io.tmpdir`、`-Duser.home`、`-Dorg.sqlite.tmpdir` 到 E 盘；使用 IDEA 自带 `jbr/bin/java.exe`，避免 PATH 上的旧 Java。

普通 Java 与 Spring Boot 的可重复 Agent 验证：

```powershell
& ./scripts/build.ps1 -MavenArguments @('package','-pl','examples/logback-demo,examples/springboot-demo,examples/custom-plugin,examples/custom-chain-plugin','-am')
python scripts/verify-agent.py
python scripts/verify-boot.py
```

固定种子三模式性能对照（默认每个模式 100,000 条；结果和原始输出写入 `.build/performance/`）：

```powershell
& ./scripts/package-release.ps1
python scripts/benchmark-modes.py
```

验证脚本默认使用 IDEA JBR 21，也可通过 `LOGLENS_JAVA` 指向 Java 17 运行 Agent 与 Spring Boot 实测；已验证 Corretto 17.0.14、Logback 1.5.18 和 Spring Boot 3.4.4。不能据此推断其他 Java/框架版本兼容。浏览器 UI、性能、故障矩阵与最终逐需求审计仍在进行；以验证记录中的实际证据为准。SPI 示例见 [examples/README.md](examples/README.md)。
