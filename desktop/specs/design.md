# 桌面安装包 — 设计文档（Design）

| 属性 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 特性名称 | SimplePlanePlatform 桌面客户端安装包 |
| 作者 | zhanghonghao |
| 状态 | Draft（待评审） |
| 开发模式 | SDD：requirements → **design** → tasks |
| 上游文档 | desktop/specs/requirements.md |
| 下游文档 | desktop/specs/tasks.md |

> 本文是 SDD 第二份，定义 **How（怎么做）**。每个设计决策末尾用 `〔满足 REQ-xxx〕` 标注追溯。
> 不涉及具体逐行实现，那属于 tasks.md。

---

## 1. 设计目标

把"三语言混合 + 需手动配环境"的项目，封装成"双击即用、零运行时依赖"的桌面客户端。
核心策略：**保留各模块语言不变，用 Tauri 壳统一封装并自带运行时**，避免大规模重写。

---

## 2. 整体架构

### 2.1 分层视图

```
┌─────────────────────────────────────────────────────────┐
│                  Tauri 桌面应用（desktop/）               │
│  ┌───────────────┐         ┌──────────────────────────┐  │
│  │  WebView 前端  │ ←IPC→  │   Rust 后端（src-tauri）  │  │
│  │ 复用 dashboard │         │  进程编排 / 代理设置 /     │  │
│  │  的 Web UI     │         │  状态聚合（替代 server.js）│  │
│  └───────────────┘         └────────────┬─────────────┘  │
└──────────────────────────────────────────┼───────────────┘
                                            │ spawn sidecar
                  ┌─────────────────────────┼─────────────────────────┐
                  ▼                         ▼                          ▼
        ┌──────────────────┐   ┌────────────────────┐   ┌────────────────────┐
        │  自带精简 JRE     │   │  proxy-local fat-jar │   │  tun-adapter 二进制 │
        │ (jlink, ~40MB)   │ → │ ProxyLocalServer     │   │ (Rust, 原生)        │
        └──────────────────┘   └────────────────────┘   └────────────────────┘
```

关键转变：原来 **Node 进程（server.js）** 负责的「拉起 java / tun、探测端口、设代理、聚合状态」
全部由 **Tauri 的 Rust 后端**承担，从而**彻底去掉 Node 运行时依赖**。〔满足 REQ-001〕

### 2.2 模块职责

| 组件 | 语言 | 职责 | 来源 |
| --- | --- | --- | --- |
| WebView 前端 | HTML/JS | 连接开关、状态、配置界面 | 复用 dashboard 现有页面 |
| Tauri 后端 | Rust | 进程编排、系统代理设置、状态聚合、IPC command | 重写自 server.js 逻辑 |
| proxy-local | Java 8 | 代理数据面 | 现有 fat-jar，不改 |
| 精简 JRE | — | 运行 fat-jar 的运行时 | jlink 生成，随包分发 |
| tun-adapter | Rust | TUN 设备适配 | 现有二进制，不改 |

---

## 3. 关键设计决策

### D-1 桌面壳：Tauri 2.x
- **决策**：用 Tauri 而非 Electron。Tauri 用系统 WebView，自身体积极小，且后端是 Rust，
  天然适合做特权进程编排。〔满足 REQ-002、REQ-NFR-002〕
- **取舍**：放弃 Electron（自带 Chromium，体积大、且仍需打包 Node）。

### D-2 Java 运行时：jlink 精简 JRE
- **决策**：用 JDK 17 的 `jdeps` 推导依赖模块，`jlink` 生成最小 JRE（~40MB），随包分发。
  运行时以 **classpath 模式**执行 Java 8 fat-jar（向后兼容，不要求业务模块化）。
  〔满足 REQ-001、约束 C-1/C-4、REQ-NFR-002〕
- **取舍**：不打包完整 JRE（太大），不要求用户装 Java（违背目标）。

### D-3 去 Node 化：用 Rust 后端替代 server.js
- **决策**：把 `dashboard/server.js` 的编排逻辑用 Tauri Rust command 重写；前端页面静态资源
  内嵌进 WebView。Node 仅作为开发期工具，**不进入分发包**。〔满足 REQ-001、REQ-006〕
- **取舍**：迁移有工作量，但换来彻底去掉一个运行时。迁移须以 `dashboard/test/` 为回归基准。
  〔满足 REQ-NFR-005〕

### D-4 sidecar 进程分发
- **决策**：精简 JRE、fat-jar、tun-adapter 均作为 Tauri **sidecar / 资源**随包分发，
  运行时用 Tauri shell 能力 `spawn`。路径解析按平台区分（Windows 二进制带 `.exe`）。
  〔满足 REQ-001、REQ-003、REQ-NFR-001〕

### D-5 两种代理模式分阶段
- **系统代理模式（首版）**：Rust 后端调用 OS API 设置系统代理指向本地端口，断开/退出时还原。
  无需提权、无需驱动。〔满足 REQ-004〕
- **TUN 模式（进阶）**：macOS 走 utun + 一次性提权；Windows 随包分发 `wintun.dll` + manifest 提权。
  失败优雅降级到系统代理模式。〔满足 REQ-005〕

### D-6 多平台原生构建
- **决策**：不做交叉编译。CI 用 `macos-latest` 与 `windows-latest` 各自原生构建并产出
  `.dmg` / `.msi`（或 nsis `.exe`）。〔满足 REQ-NFR-001、REQ-NFR-003、约束 C-3〕

---

## 4. 进程编排设计（替代 server.js）

Rust 后端暴露给前端的 IPC command（与现有 server.js HTTP 接口语义对齐）：

| command | 行为 | 对应 server.js 逻辑 | 追溯 |
| --- | --- | --- | --- |
| `connect(mode)` | 拉起 proxy-local（系统代理或 TUN）并设代理 | spawn java + 设代理 | REQ-003、REQ-004 |
| `disconnect()` | 停止子进程、还原系统代理 | kill + 还原 | REQ-003、REQ-004、REQ-006 |
| `status()` | 端口监听探测 + 进程存活聚合 | isPortListening 聚合 | REQ-006 |
| `get_config()` / `save_config()` | 读写用户配置目录 | 配置读写 | REQ-007 |

行为细节须保留（作为回归基准，REQ-NFR-005）：
- 端口监听探测（对照 `isPortListening`）。
- TUN 启动 sudo 错误分类（无权限 / 被拒绝 / 二进制缺失）→ 映射到 D-5 的优雅降级。
- 退出时清理全部子进程，杜绝僵尸进程（REQ-006 验收 3）。

---

## 5. 安装包结构（macOS / Windows）

```
SimplePlanePlatform.app / 安装目录
├── 客户端可执行（Tauri 壳）
├── resources/
│   ├── jre/                 ← jlink 精简 JRE（D-2）
│   ├── proxy-local.jar      ← fat-jar（C-1）
│   ├── tun-adapter[.exe]    ← Rust 二进制（C-2）
│   ├── wintun.dll           ← 仅 Windows（D-5）
│   └── web/                 ← 内嵌 Web UI（D-3）
└── 配置模板（默认示例配置，REQ-007 验收 3）
```
〔满足 REQ-001、REQ-002、REQ-007、REQ-NFR-001〕

---

## 6. CI 设计（release.yml）

- 触发：推送 `v*.*.*` tag。
- matrix：`macos-latest` + `windows-latest`，各自原生构建。
- 步骤（每个 runner）：
  1. 装 JDK 17 → `mvn package` 产出 fat-jar；`jdeps`+`jlink` 生成精简 JRE。
  2. 装 Rust → `cargo build --release` 产出 tun-adapter。
  3. 收集资源到 `desktop/src-tauri/resources/`（脚本 `collect-resources`）。
  4. `tauri build` 产出安装包。
  5. 上传安装包为 release 资产。
〔满足 REQ-NFR-001、REQ-NFR-003、约束 C-3〕

签名/公证作为可选步骤（有证书时启用），内测允许未签名 + 放行引导。〔满足 REQ-NFR-004〕

---

## 7. 错误处理与降级

| 场景 | 处理 | 追溯 |
| --- | --- | --- |
| 自带 JRE 启动失败 | 报错并提示重装，不回退到系统 Java | REQ-001 |
| TUN 提权被拒 / 驱动缺失 | 提示并降级到系统代理模式，不崩溃 | REQ-005 验收 3 |
| 子进程异常退出 | status 置为"异常"，不显示"已连接" | REQ-006 验收 2 |
| 配置无效 | 校验并提示，回落到内置模板 | REQ-007 |

---

## 8. 需求→设计 追溯矩阵

| 需求 | 由哪些设计满足 |
| --- | --- |
| REQ-001 零运行时依赖 | D-1, D-2, D-3, D-4, §5 |
| REQ-002 双击安装启动 | D-1, §5 |
| REQ-003 一键连接/断开 | D-4, §4 |
| REQ-004 系统代理模式 | D-5(系统代理), §4 |
| REQ-005 TUN 模式 | D-5(TUN), §7 |
| REQ-006 编排与状态 | D-3, §4, §7 |
| REQ-007 基础配置 | §4, §5, §7 |
| REQ-NFR-001 多平台一致 | D-4, D-6, §5, §6 |
| REQ-NFR-002 体积 | D-1, D-2 |
| REQ-NFR-003 自动化构建 | D-6, §6 |
| REQ-NFR-004 安全分发 | §6 |
| REQ-NFR-005 行为不回归 | D-3, §4 |
