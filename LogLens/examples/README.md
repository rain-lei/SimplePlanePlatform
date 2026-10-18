# 示例运行

所有构建均使用工作区 `scripts/build.ps1` 的 IDEA 工具链与 E 盘缓存。

普通 Logback 示例：`examples/logback-demo`；打包 Agent 的双模式验证执行 `python scripts/verify-agent.py`，无需改业务日志调用。

`custom-plugin` 注册 `example-order-tag` 节点工厂。把构建 JAR 放到 YAML 的 pluginsDirectory，在 pipeline 中将目标节点 provider 改为 `example-order-tag`，config 可设 `tag: order-focus`。它只标记已提取 orderId 的记录，仍同步调用一次 continuation。

`custom-chain-plugin` 注册完整链提供者；把它的 JAR 放到外部插件目录后，启动时替换配置链，使用提取、模板、关联和高亮内置节点。该示例不折叠，最终输出仍由运行时负责。不得同时加载多个整链 provider；冲突会导致初始化失败。

`duplicate-chain-plugin` 仅用于启动冲突验收：与 `custom-chain-plugin` 同时加载时，普通模式应诊断并保留应用原日志，STRICT 应终止启动。执行 `python scripts/verify-plugin-conflict.py` 验证两种行为。

两种插件都以 provided 依赖 API，不把 API class 打进插件。增删 JAR 或改 YAML 后必须重启应用，运行时不热加载。
