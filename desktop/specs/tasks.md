# 桌面安装包 — 子任务文档（Tasks）

| 属性 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 特性名称 | SimplePlanePlatform 桌面客户端安装包 |
| 作者 | zhanghonghao |
| 状态 | Draft（待评审） |
| 开发模式 | SDD：requirements → design → **tasks** |
| 上游文档 | desktop/specs/requirements.md、desktop/specs/design.md |

> 本文是 SDD 第三份，把设计拆成**可独立执行、可验收**的最小单元。
> 每个 Task 标注：关联需求（REQ）、关联设计（D-x/§x）、产出文件、技术要求、验收标准。
> 执行顺序遵循阶段依赖；同阶段内可并行。

---

## 全局约束（所有 Task 通用）

- **G-1**：不修改 proxy-local 的打包方式与 main class（`com.proxy.local.ProxyLocalServer`），仅复用 fat-jar。〔C-1〕
- **G-2**：不修改 tun-adapter 源码，仅消费其编译产物。〔C-2〕
- **G-3**：所有新增文件置于 `desktop/` 下，不污染既有模块目录结构。
- **G-4**：从 server.js 迁移的行为，须以 `dashboard/test/` 既有用例为回归基准，不得回归。〔REQ-NFR-005〕
- **G-5**：禁止跨平台交叉编译；平台相关产物由各自 CI runner 原生生成。〔C-3〕

---

## 阶段 0：脚手架与运行时打包（地基）

### Task 0.1 初始化 Tauri 项目骨架
- **关联**：REQ-002 / D-1
- **产出**：`desktop/src-tauri/`（Cargo 项目）、`desktop/tauri.conf.json`、最小可启动空窗口。
- **技术要求**：Tauri 2.x；窗口标题、图标占位；本地可 `tauri dev` 启动空窗口。
- **验收**：执行 dev 命令能弹出一个空白桌面窗口，无报错。

### Task 0.2 生成精简 JRE 脚本
- **关联**：REQ-001 / D-2 / REQ-NFR-002
- **产出**：`desktop/scripts/build-jre.sh`（mac）、`desktop/scripts/build-jre.ps1`（win）。
- **技术要求**：用 JDK 17 `jdeps` 推导 fat-jar 依赖模块 → `jlink` 生成最小 JRE 到 `desktop/src-tauri/resources/jre/`；classpath 模式运行 Java 8 jar。
- **验收**：脚本产出的 `jre/bin/java -version` 可执行；用该 JRE 能 `java -jar proxy-local.jar` 正常启动代理。

### Task 0.3 资源收集脚本
- **关联**：REQ-001 / D-4 / §5
- **产出**：`desktop/scripts/collect-resources.sh` / `.ps1`，把 fat-jar、tun-adapter[.exe]、(Windows) wintun.dll、web 静态资源、配置模板汇集到 `desktop/src-tauri/resources/`。
- **技术要求**：平台分支处理二进制后缀；幂等可重复执行。
- **验收**：执行后 `resources/` 目录结构与设计 §5 一致，文件齐全。

---

## 阶段 1：去 Node 化 — Rust 后端编排（核心）

### Task 1.1 进程拉起与停止
- **关联**：REQ-003 / D-3 / D-4 / §4
- **产出**：Tauri command `connect(mode)` / `disconnect()`；用自带 JRE spawn fat-jar。
- **技术要求**：sidecar 路径解析按平台区分；disconnect 必须 kill 全部子进程。
- **验收**：connect 后 java 进程存在且代理端口监听；disconnect 后进程消失、无残留。〔REQ-006 验收3〕

### Task 1.2 端口探测与状态聚合
- **关联**：REQ-006 / D-3 / §4
- **产出**：command `status()`，复刻 server.js 的 `isPortListening` + 进程存活聚合。
- **技术要求**：行为与既有逻辑等价；进程异常退出时状态置"异常"。
- **验收**：手动 kill 后台进程后，status 返回"异常/未运行"，不再返回"已连接"。〔REQ-006 验收2〕

### Task 1.3 系统代理设置/还原
- **关联**：REQ-004 / D-5(系统代理) / §4
- **产出**：连接时设置 OS 系统代理指向本地端口，断开/退出时还原。
- **技术要求**：mac 用 networksetup / win 用注册表或 API；运行期不要求提权。〔REQ-004 验收3〕
- **验收**：connect 后系统代理生效；disconnect/退出后系统代理还原到先前值。

### Task 1.4 配置读写与模板
- **关联**：REQ-007 / §4 / §5
- **产出**：command `get_config()` / `save_config()`；内置默认配置模板。
- **技术要求**：写入用户可写配置目录；重启后持久；提供可直接连的示例模板。
- **验收**：保存后重启仍生效；首次运行存在可用默认模板。

### Task 1.5 前端 UI 接入
- **关联**：REQ-002 / REQ-003 / D-3
- **产出**：WebView 内嵌 dashboard 的 Web UI，通过 IPC 调用上述 command。
- **技术要求**：连接开关、状态显示、连接中过渡态、配置界面。〔REQ-003 验收3〕
- **验收**：点击连接/断开按钮，UI 状态随真实进程状态变化。

---

## 阶段 2：打包与多平台产出

### Task 2.1 Tauri 资源/ sidecar 配置
- **关联**：REQ-001 / D-4 / §5
- **产出**：`tauri.conf.json` 中配置 resources 与 sidecar、应用图标、标识。
- **验收**：`tauri build` 后安装包内含 `jre/`、fat-jar、tun-adapter，路径可被后端解析。

### Task 2.2 macOS 安装包
- **关联**：REQ-002 / REQ-NFR-001 / D-6
- **产出**：`.dmg`（在 macos runner 原生构建）。
- **验收**：在**无 Java/无 Node** 的 mac 上安装后可启动并在系统代理模式联网。〔REQ-001/002/004〕

### Task 2.3 Windows 安装包
- **关联**：REQ-002 / REQ-NFR-001 / D-6
- **产出**：`.msi` 或 nsis `.exe`（在 windows runner 原生构建）。
- **验收**：在**无 Java/无 Node** 的 Windows 上安装后可启动并在系统代理模式联网。〔REQ-001/002/004〕

### Task 2.4 release CI 流水线
- **关联**：REQ-NFR-003 / REQ-NFR-001 / D-6 / §6
- **产出**：`.github/workflows/release.yml`，tag 触发、matrix(mac+win)、自动构建并上传安装包。
- **技术要求**：每 runner 内置 JDK17+Rust，跑 build-jre / collect-resources / tauri build。
- **验收**：推送 `v*.*.*` tag 后，自动产出两平台安装包并附到 release。

---

## 阶段 3：TUN 进阶模式（P1）

### Task 3.1 TUN 拉起与提权
- **关联**：REQ-005 / D-5(TUN) / §7
- **产出**：TUN 模式下拉起 tun-adapter；mac 一次性提权、win 用 wintun.dll + manifest 提权。
- **技术要求**：保留 server.js 的 sudo 错误分类语义。〔G-4〕
- **验收**：mac 能创建 utun、win 能用 WinTUN 建虚拟网卡；全局流量经代理。

### Task 3.2 提权失败优雅降级
- **关联**：REQ-005 验收3 / §7
- **产出**：授权被拒/驱动缺失时提示并回落系统代理模式。
- **验收**：拒绝授权后软件不崩溃，仍可用系统代理模式。

---

## 阶段 4：验证与签名（收尾）

### Task 4.1 编排行为回归测试
- **关联**：REQ-NFR-005 / G-4
- **产出**：以 `dashboard/test/` 为基准，校验迁移后端口探测/错误分类/状态聚合行为等价。
- **验收**：等价行为全部通过，无回归。

### Task 4.2 干净机器端到端验收
- **关联**：REQ-001 / Definition of Done
- **产出**：在无 Java/无 Node 的 mac 与 win 各做一次完整安装→连接→联网验收记录。
- **验收**：覆盖 DoD 第 1、2 条。

### Task 4.3 代码签名/公证（可选）
- **关联**：REQ-NFR-004 / §6
- **产出**：有证书时在 CI 接入 mac 公证 / win 签名；无证书时输出放行引导文档。
- **验收**：签名包可在目标系统无安全拦截直接运行，或提供清晰放行步骤。

---

## 阶段依赖图

```
阶段0(地基) ──► 阶段1(Rust后端) ──► 阶段2(打包/CI) ──► 阶段4(验证/签名)
                                  └─► 阶段3(TUN, P1, 可并行于阶段4前)
```

---

## Task → 需求 覆盖校验

| 需求 | 被哪些 Task 覆盖 |
| --- | --- |
| REQ-001 | 0.2, 0.3, 1.1, 2.1, 2.2, 2.3, 4.2 |
| REQ-002 | 0.1, 1.5, 2.2, 2.3 |
| REQ-003 | 1.1, 1.5 |
| REQ-004 | 1.3, 2.2, 2.3 |
| REQ-005 | 3.1, 3.2 |
| REQ-006 | 1.1, 1.2 |
| REQ-007 | 1.4 |
| REQ-NFR-001 | 2.2, 2.3, 2.4 |
| REQ-NFR-002 | 0.2 |
| REQ-NFR-003 | 2.4 |
| REQ-NFR-004 | 4.3 |
| REQ-NFR-005 | 1.1, 1.2, 4.1 |
