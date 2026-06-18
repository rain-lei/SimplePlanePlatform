# 自动更新功能 — 子任务分解与测试方案

> 本文档是 `auto-updater-plan.md` 的配套执行文档，将计划拆解为可逐一验收的子任务，
> 并为每个环节设计了完整的测试策略。

---

## 任务总览

```
Task 1: 密钥生成与 Secrets 配置
Task 2: Rust 后端集成（依赖 + 插件注册 + 权限）
Task 3: 前端 UI 开发（检查逻辑 + 更新对话框 + 样式）
Task 4: CI 流水线改造（签名 + latest.json 生成）
Task 5: 端到端集成测试与发布验证
```

---

## Task 1: 密钥生成与 Secrets 配置

### 1.1 子步骤

| # | 操作 | 产出 | 完成标志 |
|---|------|------|----------|
| 1.1.1 | 安装/确认 tauri-cli 版本 ≥ 2.0 | `cargo tauri --version` 输出 ≥ 2.0 | ✓ 版本号正确 |
| 1.1.2 | 执行 `cargo tauri signer generate -w ~/.tauri/simpleplane.key` | 生成私钥文件 + 终端打印公钥 | ✓ 文件存在且公钥非空 |
| 1.1.3 | 记录公钥字符串（后续写入 tauri.conf.json） | 文本备份 | ✓ 已安全记录 |
| 1.1.4 | 将私钥文件内容添加到 GitHub Secrets: `TAURI_SIGNING_PRIVATE_KEY` | Repo Settings 页面可见 | ✓ Secret 已创建 |
| 1.1.5 | 将密码添加到 GitHub Secrets: `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | Repo Settings 页面可见 | ✓ Secret 已创建 |

### 1.2 测试

| 测试项 | 方法 | 预期结果 |
|--------|------|----------|
| 私钥文件完整性 | `cat ~/.tauri/simpleplane.key \| wc -c`，确认字节数 > 0 | 文件非空 |
| 公钥格式验证 | 公钥应以 `dW50cnVzdGVkIGNvbW1lbnQ` 开头（minisign 格式 Base64） | 格式正确 |
| Secrets 可访问性 | 在 GitHub Actions 中用 `echo "${#TAURI_SIGNING_PRIVATE_KEY}"` 打印长度（不打印内容） | 长度 > 0 |
| 私钥不泄露 | 确认 `.gitignore` 中有 `*.key` 或 `~/.tauri/` 不在仓库中 | git status 无泄露 |

---

## Task 2: Rust 后端集成

### 2.1 子步骤

| # | 操作 | 文件 | 完成标志 |
|---|------|------|----------|
| 2.1.1 | `Cargo.toml` 添加 `tauri-plugin-updater = "2"` | `desktop/src-tauri/Cargo.toml` | ✓ 依赖行存在 |
| 2.1.2 | `main.rs` 注册 updater 插件 | `desktop/src-tauri/src/main.rs` | ✓ `.plugin(tauri_plugin_updater::Builder::new().build())` 存在 |
| 2.1.3 | `capabilities/default.json` 添加 `"updater:default"` 权限 | `desktop/src-tauri/capabilities/default.json` | ✓ 权限项存在 |
| 2.1.4 | `tauri.conf.json` 添加 updater 配置 | `desktop/src-tauri/tauri.conf.json` | ✓ plugins.updater 段存在 |
| 2.1.5 | 执行 `cargo check` 确认编译通过 | 终端输出 | ✓ 无 error |

### 2.2 测试

| 测试项 | 方法 | 预期结果 |
|--------|------|----------|
| 编译检查 | `cd desktop/src-tauri && cargo check` | 零 error（warning 可接受） |
| 完整构建 | `cd desktop && cargo tauri build` | 产出 `.app` 或 `.exe` |
| 插件加载验证 | 启动构建产物，查看 stdout/stderr 无 updater panic | 无崩溃日志 |
| 权限生效 | 构建后检查生成的 `acl-manifests.json` 中包含 updater 相关条目 | updater 权限已注入 |
| 配置解析 | 故意写错 pubkey 为空字符串，启动时应有 warning 日志（不崩溃） | 优雅降级 |

### 2.3 详细代码变更

**`desktop/src-tauri/Cargo.toml` 变更：**
```diff
 [dependencies]
 tauri = { version = "2", features = ["tray-icon"] }
 tauri-plugin-shell = "2"
+tauri-plugin-updater = "2"
 serde = { version = "1", features = ["derive"] }
```

**`desktop/src-tauri/src/main.rs` 变更：**
```diff
     tauri::Builder::default()
         .plugin(tauri_plugin_shell::init())
+        .plugin(tauri_plugin_updater::Builder::new().build())
         .manage(app_state)
```

**`desktop/src-tauri/capabilities/default.json` 变更：**
```diff
   "permissions": [
     "core:default",
-    "shell:allow-open"
+    "shell:allow-open",
+    "updater:default"
   ]
```

**`desktop/src-tauri/tauri.conf.json` 变更：**
```diff
   "plugins": {
     "shell": {
       "open": true
-    }
+    },
+    "updater": {
+      "endpoints": [
+        "https://github.com/zhh293/SimplePlanePlatform/releases/latest/download/latest.json"
+      ],
+      "pubkey": "<YOUR_PUBLIC_KEY_HERE>"
+    }
   }
```

---

## Task 3: 前端 UI 开发

### 3.1 子步骤

| # | 操作 | 文件 | 完成标志 |
|---|------|------|----------|
| 3.1.1 | 在 `init()` 末尾添加 `setTimeout(checkForUpdates, 3000)` | `desktop/web/app.js` | ✓ 调用存在 |
| 3.1.2 | 实现 `checkForUpdates()` 函数 | `desktop/web/app.js` | ✓ 函数定义存在 |
| 3.1.3 | 实现 `showUpdateDialog(update)` 函数 | `desktop/web/app.js` | ✓ 函数定义存在 |
| 3.1.4 | 在 return 对象中暴露 `checkForUpdates` 供手动调用 | `desktop/web/app.js` | ✓ 已暴露 |
| 3.1.5 | 添加 `.update-dialog` 系列 CSS 样式 | `desktop/web/style.css` | ✓ 样式存在 |
| 3.1.6 | （可选）在设置页面添加"检查更新"按钮 | `desktop/web/index.html` | ✓ 按钮存在 |

### 3.2 测试

#### 3.2.1 单元测试（纯逻辑，不依赖 Tauri 环境）

| 测试项 | 方法 | 预期结果 |
|--------|------|----------|
| `showUpdateDialog` DOM 创建 | 在浏览器 DevTools 中 mock `update` 对象调用 `showUpdateDialog({version:'0.2.0', body:'test'})` | 页面出现遮罩层 + 对话框 |
| 对话框关闭 | 点击"稍后提醒" | 对话框 DOM 被移除 |
| 进度条初始状态 | 对话框出现时 `#updateProgress` 应为 `hidden` | 进度区域不可见 |
| XSS 防护 | mock `update.body = '<script>alert(1)</script>'`，检查是否被转义 | 无脚本执行 |

#### 3.2.2 集成测试（需要 Tauri dev 环境）

| 测试项 | 方法 | 预期结果 |
|--------|------|----------|
| API 可访问性 | `cargo tauri dev` 启动后，DevTools 中执行 `typeof window.__TAURI__.updater` | 不是 `'undefined'` |
| 无更新时静默 | 当前版本 = 最新版本，启动后 3 秒内无弹框 | 无 UI 干扰 |
| 网络失败时静默 | 断网或 endpoint 不可达，启动后 3 秒内无报错弹框 | console 仅 log 级别输出 |
| 手动触发 | DevTools 执行 `App.checkForUpdates()`，当 endpoint 返回新版本 | 弹出更新对话框 |

#### 3.2.3 UI/样式测试

| 测试项 | 方法 | 预期结果 |
|--------|------|----------|
| 对话框居中 | 窗口不同尺寸下（最小 900×600、最大全屏）检查对话框位置 | 始终居中 |
| 深色主题适配 | 确认 CSS 变量 fallback 在无主题时使用默认深色值 | 视觉正常 |
| 进度条动画 | 模拟 Progress 事件从 0→100% | 进度条平滑增长 |
| 响应式 | 窗口宽度 < 500px 时对话框不超出边界 | 宽度自适应 |
| 遮罩层交互 | 点击遮罩层（对话框外部）不关闭对话框 | 必须点按钮操作 |

---

## Task 4: CI 流水线改造

### 4.1 子步骤

| # | 操作 | 文件/位置 | 完成标志 |
|---|------|-----------|----------|
| 4.1.1 | 添加 `TAURI_SIGNING_PRIVATE_KEY` Secret | GitHub Repo Settings | ✓ 已添加 |
| 4.1.2 | 添加 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` Secret | GitHub Repo Settings | ✓ 已添加 |
| 4.1.3 | release.yml 的 tauri-action step 添加签名环境变量 | `.github/workflows/release.yml` | ✓ env 段已更新 |
| 4.1.4 | 修改 `releaseDraft: true` → `releaseDraft: false` | `.github/workflows/release.yml` | ✓ 已修改 |
| 4.1.5 | （可选）添加 updater 端点可达性检查 step | `.github/workflows/release.yml` | ✓ 验证 step 存在 |

### 4.2 测试

| 测试项 | 方法 | 预期结果 |
|--------|------|----------|
| CI 构建通过 | 推 tag `v0.2.0-test`，观察 Actions 运行 | 所有 matrix job 绿色 |
| 签名文件生成 | 查看 Release Assets 是否有 `.sig` 文件 | 每个安装包旁有对应 `.sig` |
| `latest.json` 生成 | Release Assets 中是否有 `latest.json` | 文件存在 |
| `latest.json` 格式验证 | 下载并检查 JSON 结构 | 包含 version / platforms / signature / url |
| `latest.json` URL 可达 | `curl -sL https://github.com/zhh293/SimplePlanePlatform/releases/latest/download/latest.json` | 返回 200 + 有效 JSON |
| 签名匹配 | 手动下载安装包 + `.sig`，用公钥验证 | `tauri signer verify` 通过 |
| 多平台产出 | macOS 和 Windows 的 Assets 均齐全 | 两个平台各有安装包 + sig |

### 4.3 详细 YAML 变更

```diff
       # Step 5b: Build + Release (tag push only)
       - name: Build & Release Tauri Desktop App
         if: startsWith(github.ref, 'refs/tags/')
         uses: tauri-apps/tauri-action@v0
         env:
           GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
+          TAURI_SIGNING_PRIVATE_KEY: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY }}
+          TAURI_SIGNING_PRIVATE_KEY_PASSWORD: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY_PASSWORD }}
         with:
           projectPath: desktop
           tagName: ${{ github.ref_name }}
           releaseName: 'SimplePlane ${{ github.ref_name }}'
           releaseBody: 'See the assets to download and install this version.'
-          releaseDraft: true
+          releaseDraft: false
           prerelease: false
           args: --target ${{ matrix.target }}
+
+      # Step 6: Verify updater endpoint (post-release sanity check)
+      - name: Verify latest.json is accessible
+        if: startsWith(github.ref, 'refs/tags/')
+        run: |
+          sleep 10  # 等待 GitHub CDN 刷新
+          STATUS=$(curl -sL -o /dev/null -w "%{http_code}" \
+            "https://github.com/zhh293/SimplePlanePlatform/releases/latest/download/latest.json")
+          echo "latest.json HTTP status: $STATUS"
+          if [ "$STATUS" != "200" ]; then
+            echo "::warning::latest.json not yet accessible (status=$STATUS), CDN may need time to propagate"
+          fi
```

---

## Task 5: 端到端集成测试与发布验证

这是最关键的测试环节，覆盖从"用户装了旧版"到"自动更新到新版"的完整链路。

### 5.1 测试环境准备

| 环境 | 要求 | 用途 |
|------|------|------|
| macOS 测试机 | macOS 12+ / Apple Silicon 或 Intel | 测试 macOS 更新流程 |
| Windows 测试机 | Windows 10/11 x64 | 测试 Windows 更新流程 |
| GitHub 仓库 | 已配好 Secrets | CI 构建与发布 |
| 网络环境 | 能访问 GitHub（或使用代理） | 下载更新包 |

### 5.2 完整 E2E 测试流程

#### Phase A: 首版发布（建立基线）

```bash
# 1. 确保 tauri.conf.json version = "0.1.0"
# 2. 提交所有改动
git add . && git commit -m "feat: integrate auto-updater"

# 3. 打第一个支持 updater 的 tag
git tag v0.1.0
git push origin main --tags

# 4. 等 CI 完成，下载并安装 v0.1.0 到两台测试机
```

#### Phase B: 新版发布（触发更新）

```bash
# 1. 修改版本号
#    tauri.conf.json: "version": "0.2.0"
#    Cargo.toml: version = "0.2.0"

# 2. 做一个可感知的变更（如改窗口标题加上版本号）

# 3. 提交并发布
git add . && git commit -m "release: v0.2.0"
git tag v0.2.0
git push origin main --tags

# 4. 等 CI 完成
```

#### Phase C: 在测试机上验证更新

| 步骤 | 操作 | 预期结果 | ✓ |
|------|------|----------|---|
| C1 | 启动已安装的 v0.1.0 应用 | 正常启动 | |
| C2 | 等待 3~5 秒 | 弹出"发现新版本 v0.2.0"对话框 | |
| C3 | 检查对话框显示的 release notes | 与 releaseBody 或 tag message 一致 | |
| C4 | 点击"立即更新" | 进度条开始推进 | |
| C5 | 观察下载进度 | 百分比从 0% 增长到 100% | |
| C6 | 下载完成 | 提示"下载完成，准备安装..." | |
| C7 | 应用自动重启 | 窗口关闭后自动重新打开 | |
| C8 | 验证新版本 | 标题/关于页显示 v0.2.0 | |
| C9 | 再次启动应用 | 不再弹出更新提示 | |

### 5.3 异常场景测试（关键！）

#### 5.3.1 网络异常

| 测试场景 | 操作方法 | 预期行为 |
|----------|----------|----------|
| 完全无网络 | 关闭 Wi-Fi / 拔网线后启动应用 | 静默跳过，不弹任何错误框，应用正常使用 |
| 网络超时 | 用防火墙规则 block `github.com`（macOS: `sudo pfctl`，Windows: 防火墙规则） | 超时后静默失败，不影响使用 |
| 下载中断网 | 点"立即更新"，进度到 50% 时断网 | 进度条停止，显示错误信息，应用不崩溃 |
| DNS 解析失败 | 修改 `/etc/hosts` 让 `github.com` 指向无效 IP | 同"完全无网络"的预期 |
| 代理环境 | 设置系统代理为有效/无效 SOCKS5 | 有效代理时能更新，无效代理时静默失败 |

#### 5.3.2 签名验证

| 测试场景 | 操作方法 | 预期行为 |
|----------|----------|----------|
| 签名正确 | 正常流程 | 更新成功 |
| 签名被篡改 | 手动修改 `latest.json` 中的 signature 为乱码，托管到本地 HTTP server，改 endpoint 指向它 | 更新失败，提示签名验证错误 |
| 安装包被篡改 | 下载安装包后修改几字节，替换到本地 HTTP server | 签名验证失败，拒绝安装 |
| 公钥不匹配 | 在 tauri.conf.json 中替换为另一个公钥 | 所有更新均签名失败 |

**本地签名验证测试方法：**
```bash
# 下载安装包和签名
curl -LO https://github.com/zhh293/SimplePlanePlatform/releases/download/v0.2.0/SimplePlane_0.2.0_aarch64.app.tar.gz
curl -LO https://github.com/zhh293/SimplePlanePlatform/releases/download/v0.2.0/SimplePlane_0.2.0_aarch64.app.tar.gz.sig

# 用公钥验证（如果 tauri-cli 支持 verify 子命令）
cargo tauri signer verify \
  --pubkey "<你的公钥>" \
  --signature SimplePlane_0.2.0_aarch64.app.tar.gz.sig \
  SimplePlane_0.2.0_aarch64.app.tar.gz
```

#### 5.3.3 版本号边界

| 测试场景 | 条件 | 预期行为 |
|----------|------|----------|
| 相同版本 | 本地 0.2.0，远端 0.2.0 | 不弹更新 |
| 本地更高 | 本地 0.3.0，远端 0.2.0 | 不弹更新（不会降级） |
| 跨大版本 | 本地 0.1.0，远端 1.0.0 | 正常弹出更新 |
| 预发版本号 | 本地 0.2.0-beta.1，远端 0.2.0 | 正常弹出更新 |
| 远端版本无效 | `latest.json` 中 version = "" 或 "abc" | 静默忽略，不崩溃 |

#### 5.3.4 用户交互

| 测试场景 | 操作 | 预期行为 |
|----------|------|----------|
| 点"稍后提醒" | 点击按钮 | 对话框消失，应用正常使用 |
| 多次启动 | 连续重启应用（每次都点"稍后"） | 每次启动仍会提示（无"已忽略"状态） |
| 更新过程中关闭窗口 | 正在下载时点窗口关闭按钮 | 下载中止，下次启动重新提示 |
| 窗口最小化后恢复 | 对话框弹出后最小化再恢复 | 对话框仍在 |

#### 5.3.5 系统兼容性

| 测试场景 | 环境 | 预期行为 |
|----------|------|----------|
| macOS Gatekeeper | 未签名包首次打开后更新 | 更新成功（Updater 不走 Gatekeeper） |
| macOS 权限 | `/Applications/SimplePlane.app` 是否需要管理员权限覆盖 | Updater 处理权限提升 |
| Windows UAC | 安装在 Program Files 时更新 | NSIS 安装模式可能需要 UAC 确认 |
| Windows Defender | 扫描更新包 | 不被误报为恶意软件 |
| 磁盘空间不足 | 模拟磁盘满（创建大文件占满空间） | 下载失败，显示友好错误信息 |

#### 5.3.6 并发与竞态

| 测试场景 | 操作 | 预期行为 |
|----------|------|----------|
| 重复触发检查 | 快速多次调用 `App.checkForUpdates()` | 只弹出一个对话框（去重） |
| 后台更新 + 手动检查 | 启动后自动检查的同时手动触发 | 不会弹两个对话框 |
| 更新过程中再次检查 | 正在下载时再调 `checkForUpdates()` | 忽略或提示"正在更新中" |

### 5.4 性能测试

| 测试项 | 测量方法 | 可接受阈值 |
|--------|----------|------------|
| 检查耗时 | 在 `checkForUpdates` 前后打时间戳 | < 5 秒（正常网络），超时后静默放弃 |
| 启动性能影响 | 对比有/无 updater 插件的冷启动时间 | 差异 < 500ms |
| 内存占用 | 任务管理器对比更新对话框出现前后 | 增量 < 10MB |
| 下载速度 | 观察进度条推进速率 | 与浏览器直接下载同 URL 速率相当 |

### 5.5 回归测试

更新功能集成后，确保原有功能不受影响：

| 测试项 | 操作 | 预期结果 |
|--------|------|----------|
| 代理连接 | 一键代理 / TUN 模式连接 | 正常工作，无变化 |
| 配置保存 | 修改配置并保存 | 正常持久化 |
| 预设管理 | 新增/应用/删除预设 | 功能正常 |
| 日志查看 | 切换到日志页面 | 实时日志正常滚动 |
| 窗口事件 | 关闭窗口时进程清理 | 不残留后台进程 |
| 托盘图标 | 系统托盘图标交互 | 正常工作 |

---

## 测试工具与辅助脚本

### 本地 Mock Updater 端点

为了在不发布真实 Release 的情况下测试更新 UI，可以用本地 HTTP server 模拟：

```bash
# 创建 mock latest.json
cat > /tmp/latest.json << 'EOF'
{
  "version": "99.0.0",
  "notes": "这是一个测试更新，用于验证更新 UI。",
  "pub_date": "2026-06-18T00:00:00Z",
  "platforms": {
    "darwin-aarch64": {
      "signature": "dW50cnVzdGVkIGNvbW1lbnQ6dGVzdAp0cnVzdGVkIGNvbW1lbnQ6dGVzdA==",
      "url": "http://localhost:8888/SimplePlane_99.0.0.app.tar.gz"
    },
    "windows-x86_64": {
      "signature": "dW50cnVzdGVkIGNvbW1lbnQ6dGVzdAp0cnVzdGVkIGNvbW1lbnQ6dGVzdA==",
      "url": "http://localhost:8888/SimplePlane_99.0.0_x64-setup.nsis.zip"
    }
  }
}
EOF

# 启动本地 HTTP server
cd /tmp && python3 -m http.server 8888
```

然后临时修改 `tauri.conf.json` 的 endpoint 为 `http://localhost:8888/latest.json` 来测试 UI 流程。

> 注意：签名验证会失败（因为 mock 的签名是假的），但可以验证到"弹出对话框 → 开始下载"这一步。
> 要测试完整流程需要真实签名的包。

### 自动化测试脚本

创建 `desktop/tests/updater-smoke.sh`（用于 CI 中的冒烟测试）：

```bash
#!/bin/bash
# 自动更新配置冒烟测试
# 验证配置文件中 updater 相关配置的正确性

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TAURI_DIR="$SCRIPT_DIR/../src-tauri"

echo "=== Updater Configuration Smoke Test ==="

# 1. 检查 Cargo.toml 依赖
echo -n "[1/6] Checking Cargo.toml for tauri-plugin-updater... "
if grep -q 'tauri-plugin-updater' "$TAURI_DIR/Cargo.toml"; then
    echo "PASS"
else
    echo "FAIL: tauri-plugin-updater not found in Cargo.toml"
    exit 1
fi

# 2. 检查 main.rs 插件注册
echo -n "[2/6] Checking main.rs for plugin registration... "
if grep -q 'tauri_plugin_updater' "$TAURI_DIR/src/main.rs"; then
    echo "PASS"
else
    echo "FAIL: tauri_plugin_updater not registered in main.rs"
    exit 1
fi

# 3. 检查 capabilities 权限
echo -n "[3/6] Checking capabilities for updater:default... "
if grep -q 'updater:default' "$TAURI_DIR/capabilities/default.json"; then
    echo "PASS"
else
    echo "FAIL: updater:default not in capabilities"
    exit 1
fi

# 4. 检查 tauri.conf.json updater 配置
echo -n "[4/6] Checking tauri.conf.json for updater config... "
if python3 -c "
import json, sys
with open('$TAURI_DIR/tauri.conf.json') as f:
    conf = json.load(f)
updater = conf.get('plugins', {}).get('updater', {})
assert 'endpoints' in updater, 'missing endpoints'
assert len(updater['endpoints']) > 0, 'empty endpoints'
assert 'pubkey' in updater, 'missing pubkey'
assert len(updater['pubkey']) > 10, 'pubkey too short'
print('PASS')
" 2>/dev/null; then
    :
else
    echo "FAIL: updater config incomplete in tauri.conf.json"
    exit 1
fi

# 5. 检查 endpoint URL 格式
echo -n "[5/6] Checking endpoint URL format... "
ENDPOINT=$(python3 -c "
import json
with open('$TAURI_DIR/tauri.conf.json') as f:
    conf = json.load(f)
print(conf['plugins']['updater']['endpoints'][0])
")
if echo "$ENDPOINT" | grep -qE '^https://.*latest\.json$'; then
    echo "PASS ($ENDPOINT)"
else
    echo "FAIL: endpoint URL format invalid: $ENDPOINT"
    exit 1
fi

# 6. 检查 app.js 中 updater 函数
echo -n "[6/6] Checking app.js for checkForUpdates... "
if grep -q 'checkForUpdates' "$SCRIPT_DIR/../web/app.js"; then
    echo "PASS"
else
    echo "FAIL: checkForUpdates not found in app.js"
    exit 1
fi

echo ""
echo "=== All checks passed! ==="
```

---

## 测试执行记录模板

| 测试日期 | 测试人 | 平台 | 测试版本 | 结果 | 备注 |
|----------|--------|------|----------|------|------|
| | | macOS arm64 | v0.1.0 → v0.2.0 | | |
| | | Windows x64 | v0.1.0 → v0.2.0 | | |
| | | macOS（无网络） | v0.1.0 | | |
| | | Windows（无网络） | v0.1.0 | | |
| | | macOS（篡改签名） | v0.1.0 | | |

---

## 任务依赖关系

```
Task 1 (密钥生成)
  │
  ├──→ Task 2 (Rust 后端) ──→ Task 3 (前端 UI) ──┐
  │                                                 │
  └──→ Task 4 (CI 流水线) ─────────────────────────┘
                                                    │
                                                    ▼
                                          Task 5 (E2E 测试)
```

- Task 1 是所有任务的前置（需要公钥写配置，需要私钥配 Secrets）
- Task 2 和 Task 4 可以并行（一个改代码，一个改 CI）
- Task 3 依赖 Task 2（前端需要后端 updater 插件提供 API）
- Task 5 依赖所有其他任务完成

---

## 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| GitHub API 限流 | 频繁检查可能触发 rate limit | 设置合理间隔（启动时一次），不做秒级轮询 |
| 国内访问 GitHub 慢 | 用户可能超时 | 后续配 CDN 镜像端点（进阶配置 C） |
| 更新后配置丢失 | 用户自定义配置被覆盖 | Tauri Updater 只替换 app bundle，不动用户数据目录（`~/.config/simpleplane/`） |
| 公钥泄露 | 无安全风险（公钥本来就公开） | 无需特殊处理 |
| 私钥泄露 | 攻击者可伪造更新包 | 立即 revoke → 重新生成密钥 → 发强制更新 |
| CI 构建失败 | 用户收不到更新 | CI 加 notification（Slack/邮件），手动介入 |
