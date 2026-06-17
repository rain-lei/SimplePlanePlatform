# SimplePlane 桌面客户端 — 自动更新功能实施计划

> 目标：实现应用启动时自动检查 GitHub Release 中的新版本，提示用户并完成静默更新，
> 无需手动维护安装包列表，无需轮询服务器。
>
> 技术方案：**Tauri 2.x 官方 Updater 插件** + **GitHub Release 作为分发端点**
>
> 前置条件：当前项目已使用 `tauri-apps/tauri-action@v0` 发布 Release，CI 流水线已就绪。

---

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        GitHub Release                            │
│                                                                 │
│  v0.2.0/                                                        │
│    ├── SimplePlane_0.2.0_aarch64.dmg        (macOS 安装包)       │
│    ├── SimplePlane_0.2.0_aarch64.dmg.sig    (签名文件)           │
│    ├── SimplePlane_0.2.0_x64-setup.exe      (Windows 安装包)     │
│    ├── SimplePlane_0.2.0_x64-setup.exe.sig  (签名文件)           │
│    └── latest.json                          (版本清单,自动生成)   │
└───────────────────────────────┬─────────────────────────────────┘
                                │
                    HTTPS GET /latest.json
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   SimplePlane Desktop App                        │
│                                                                 │
│  启动 → tauri-plugin-updater 检查端点                            │
│       → 比对 version 字段 vs 本地 tauri.conf.json version        │
│       → 发现新版本 → 通知前端展示更新 UI                          │
│       → 用户确认 → 下载 + 验证签名 → 替换文件 → 重启             │
└─────────────────────────────────────────────────────────────────┘
```

`latest.json` 由 `tauri-apps/tauri-action` 在 CI 构建时**自动生成并上传到 Release Assets**，
格式示例：

```json
{
  "version": "0.2.0",
  "notes": "Bug fixes and performance improvements",
  "pub_date": "2026-06-17T10:00:00Z",
  "platforms": {
    "darwin-aarch64": {
      "signature": "dW50cnVzdGVkIGNvbW1lbnQ...",
      "url": "https://github.com/<owner>/SimplePlanePlatform/releases/download/v0.2.0/SimplePlane_0.2.0_aarch64.app.tar.gz"
    },
    "windows-x86_64": {
      "signature": "dW50cnVzdGVkIGNvbW1lbnQ...",
      "url": "https://github.com/<owner>/SimplePlanePlatform/releases/download/v0.2.0/SimplePlane_0.2.0_x64-setup.nsis.zip"
    }
  }
}
```

**你不需要自己维护这个文件**，CI 会自动处理。

---

## 实施步骤

### 步骤一：生成签名密钥对

Tauri Updater **强制要求签名验证**，必须先生成密钥。

#### 1.1 执行命令

```bash
# 在项目根目录执行
cargo tauri signer generate -w ~/.tauri/simpleplane.key
```

执行后会输出：
- **私钥文件**：`~/.tauri/simpleplane.key`（严格保密，不要提交到 Git）
- **公钥字符串**：一行 Base64 编码的公钥（会打印到终端，形如 `dW50cnVzdGVkIGNvbW1lbnQ...`）

还会要求设置一个密码（password），用于保护私钥。

#### 1.2 妥善保管

| 内容 | 存放位置 | 备注 |
| --- | --- | --- |
| 公钥（pubkey） | 写入 `tauri.conf.json` 的 `plugins.updater.pubkey` | 可公开 |
| 私钥文件内容 | GitHub Repo → Settings → Secrets → `TAURI_SIGNING_PRIVATE_KEY` | 绝不外泄 |
| 私钥密码 | GitHub Repo → Settings → `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | 绝不外泄 |

---

### 步骤二：添加 Rust 依赖

#### 2.1 修改 `desktop/src-tauri/Cargo.toml`

在 `[dependencies]` 段落添加：

```toml
tauri-plugin-updater = "2"
```

完整的 dependencies 部分将变为：

```toml
[dependencies]
tauri = { version = "2", features = ["tray-icon"] }
tauri-plugin-shell = "2"
tauri-plugin-updater = "2"          # <-- 新增
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["full"] }
log = "0.4"
env_logger = "0.11"
serde_yaml = "0.9"
toml = "0.8"
dirs = "5"
```

#### 2.2 添加 Tauri capability 权限

修改 `desktop/src-tauri/capabilities/default.json`，在 `permissions` 数组中增加 updater 权限：

```json
{
  "$schema": "../gen/schemas/desktop-schema.json",
  "identifier": "default",
  "description": "Capability for the main window",
  "windows": ["main"],
  "permissions": [
    "core:default",
    "shell:allow-open",
    "updater:default"
  ]
}
```

> 如果当前 `default.json` 还没有 `permissions` 数组，需要按上面的格式创建完整内容。

---

### 步骤三：修改 `tauri.conf.json` 配置

#### 3.1 在 `plugins` 中加入 updater 配置

将 `desktop/src-tauri/tauri.conf.json` 的 `plugins` 段改为：

```json
"plugins": {
  "shell": {
    "open": true
  },
  "updater": {
    "endpoints": [
      "https://github.com/<你的GitHub用户名>/SimplePlanePlatform/releases/latest/download/latest.json"
    ],
    "pubkey": "<步骤一生成的公钥字符串粘贴到这里>"
  }
}
```

**注意事项：**
- `<你的GitHub用户名>` 替换为实际的 GitHub owner（个人用户名或组织名）
- `pubkey` 是步骤一中 `cargo tauri signer generate` 输出的那行公钥
- 端点 URL 使用 GitHub 的 "latest release" 固定地址，会自动重定向到最新 Release

#### 3.2 版本号管理

每次发新版时，`tauri.conf.json` 中的 `"version"` 字段必须递增（语义化版本）：

```json
"version": "0.2.0"
```

Updater 通过比对 `latest.json` 中的 version 和本地 `tauri.conf.json` 中的 version 来判断是否需要更新。

---

### 步骤四：注册 Updater 插件（Rust 后端）

#### 4.1 修改 `desktop/src-tauri/src/main.rs`

在 `tauri::Builder` 链中注册 updater 插件：

```rust
fn main() {
    env_logger::init();

    let app_state = Arc::new(Mutex::new(AppState::default()));

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_updater::Builder::new().build())  // <-- 新增
        .manage(app_state)
        .invoke_handler(tauri::generate_handler![
            // ... 现有 commands 不变
        ])
        // ... 其余代码不变
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

需要在文件顶部确认 `use` 没问题（`tauri_plugin_updater` 不需要额外 use，注册即可）。

---

### 步骤五：前端实现更新检查 UI

#### 5.1 在 `desktop/web/app.js` 中添加更新检查逻辑

在 `init()` 函数末尾加入更新检查调用：

```javascript
async function init() {
    // ... 现有初始化代码 ...
    
    // 启动后延迟 3 秒检查更新（避免阻塞启动体验）
    setTimeout(checkForUpdates, 3000);
}
```

添加更新检查函数：

```javascript
// ============================================================
// Auto Updater
// ============================================================
async function checkForUpdates() {
    try {
        const { check } = window.__TAURI__.updater;
        const update = await check();
        if (update) {
            showUpdateDialog(update);
        }
    } catch (e) {
        // 静默失败：网络不通、端点不可达等都不应打扰用户
        console.log('Update check skipped:', e);
    }
}

function showUpdateDialog(update) {
    // 创建更新提示 UI
    const dialog = document.createElement('div');
    dialog.id = 'updateDialog';
    dialog.className = 'update-dialog';
    dialog.innerHTML = `
        <div class="update-dialog-content">
            <h3>发现新版本 v${update.version}</h3>
            <p>${update.body || '包含性能优化和问题修复。'}</p>
            <div class="update-dialog-actions">
                <button id="btnUpdateNow" class="btn btn-primary">立即更新</button>
                <button id="btnUpdateLater" class="btn btn-secondary">稍后提醒</button>
            </div>
            <div id="updateProgress" class="update-progress" hidden>
                <div class="progress-bar">
                    <div class="progress-fill" id="updateProgressFill"></div>
                </div>
                <span id="updateProgressText">下载中...</span>
            </div>
        </div>
    `;
    document.body.appendChild(dialog);

    document.getElementById('btnUpdateNow').addEventListener('click', async () => {
        const progressEl = document.getElementById('updateProgress');
        const fillEl = document.getElementById('updateProgressFill');
        const textEl = document.getElementById('updateProgressText');
        progressEl.hidden = false;

        try {
            let totalLength = 0;
            let downloadedLength = 0;

            await update.downloadAndInstall((event) => {
                if (event.event === 'Started') {
                    totalLength = event.data.contentLength || 0;
                    textEl.textContent = '开始下载...';
                } else if (event.event === 'Progress') {
                    downloadedLength += event.data.chunkLength;
                    if (totalLength > 0) {
                        const percent = Math.round((downloadedLength / totalLength) * 100);
                        fillEl.style.width = percent + '%';
                        textEl.textContent = `下载中 ${percent}%`;
                    }
                } else if (event.event === 'Finished') {
                    fillEl.style.width = '100%';
                    textEl.textContent = '下载完成，准备安装...';
                }
            });

            // 安装完成后重启应用
            const { relaunch } = window.__TAURI__.process;
            await relaunch();
        } catch (e) {
            textEl.textContent = `更新失败: ${e}`;
        }
    });

    document.getElementById('btnUpdateLater').addEventListener('click', () => {
        dialog.remove();
    });
}
```

#### 5.2 添加更新对话框样式

在 `desktop/web/style.css` 末尾追加：

```css
/* ============================================================
   Auto Updater Dialog
   ============================================================ */
.update-dialog {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0, 0, 0, 0.5);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 9999;
    animation: fadeIn 0.2s ease;
}

.update-dialog-content {
    background: var(--bg-primary, #1a1a2e);
    border: 1px solid var(--border-color, #333);
    border-radius: 12px;
    padding: 24px 32px;
    max-width: 420px;
    width: 90%;
    box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
}

.update-dialog-content h3 {
    margin: 0 0 12px 0;
    font-size: 18px;
    color: var(--text-primary, #fff);
}

.update-dialog-content p {
    margin: 0 0 20px 0;
    color: var(--text-secondary, #aaa);
    font-size: 14px;
    line-height: 1.5;
}

.update-dialog-actions {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
}

.update-progress {
    margin-top: 16px;
}

.progress-bar {
    height: 6px;
    background: var(--bg-secondary, #2a2a3e);
    border-radius: 3px;
    overflow: hidden;
    margin-bottom: 8px;
}

.progress-fill {
    height: 100%;
    width: 0%;
    background: linear-gradient(90deg, #4f46e5, #7c3aed);
    border-radius: 3px;
    transition: width 0.3s ease;
}

#updateProgressText {
    font-size: 12px;
    color: var(--text-secondary, #aaa);
}
```

#### 5.3 关于 `withGlobalTauri` 模式

你的 `tauri.conf.json` 已配置了 `"withGlobalTauri": true`，所以 updater API 会挂载到全局 `window.__TAURI__` 对象上，前端可以直接使用，不需要 npm 安装任何包。

---

### 步骤六：修改 GitHub Actions 发布流水线

#### 6.1 添加 Secrets

到 GitHub 仓库的 Settings → Secrets and variables → Actions 中添加：

| Secret 名称 | 值 |
| --- | --- |
| `TAURI_SIGNING_PRIVATE_KEY` | 步骤一生成的私钥文件的完整内容 |
| `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | 步骤一设置的密钥密码 |

#### 6.2 修改 `.github/workflows/release.yml`

在 `Build & Release Tauri Desktop App` 步骤的 `env` 中补充签名相关变量：

```yaml
      # Step 5b: Build + Release (tag push only)
      - name: Build & Release Tauri Desktop App
        if: startsWith(github.ref, 'refs/tags/')
        uses: tauri-apps/tauri-action@v0
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAURI_SIGNING_PRIVATE_KEY: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY }}
          TAURI_SIGNING_PRIVATE_KEY_PASSWORD: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY_PASSWORD }}
        with:
          projectPath: desktop
          tagName: ${{ github.ref_name }}
          releaseName: 'SimplePlane ${{ github.ref_name }}'
          releaseBody: 'See the assets to download and install this version.'
          releaseDraft: false
          prerelease: false
          args: --target ${{ matrix.target }}
```

`tauri-apps/tauri-action` 检测到这两个环境变量后，会自动：
1. 对安装包进行签名（生成 `.sig` 文件）
2. 生成 `latest.json` 并上传到 Release Assets

#### 6.3 关于 `releaseDraft` 选项

当前你的 release.yml 设置为 `releaseDraft: true`。**建议改为 `false`**，因为：

- `latest.json` 端点使用的是 `/releases/latest/download/` 路径
- 只有**正式发布**（非 Draft）的 Release 才会被这个路径匹配到
- 如果保留 Draft，用户端永远无法通过该 URL 获取到 `latest.json`

如果你想保留"先审核再发布"的流程，有两个替代方案：

**方案 A：** 改用自定义端点（如一个静态文件服务），CI 发布后手动/自动把 `latest.json` 推到该端点

**方案 B（推荐）：** 直接设 `releaseDraft: false`，CI 完成即自动发布。如果担心出问题，利用 `prerelease: true` + 正式发布前测试的工作流。

---

### 步骤七：版本发布流程（日常操作）

完成以上集成后，每次发新版只需：

```bash
# 1. 修改版本号
#    desktop/src-tauri/tauri.conf.json: "version": "0.2.0"
#    desktop/src-tauri/Cargo.toml:      version = "0.2.0"

# 2. 提交
git add .
git commit -m "release: v0.2.0"

# 3. 打 tag 并推送
git tag v0.2.0
git push origin main --tags
```

CI 会自动完成：构建 → 签名 → 生成 `latest.json` → 上传到 GitHub Release。

用户侧的应用下次启动时就会检测到新版本并弹出更新提示。

---

## 进阶配置（可选）

### A. 定时检查（长期后台运行时）

如果希望应用在后台长期运行时也能发现更新（而非仅启动时检查一次），在前端加定时器：

```javascript
// 每 4 小时检查一次更新
setInterval(checkForUpdates, 4 * 60 * 60 * 1000);
```

### B. 手动检查更新按钮

在设置页面加一个"检查更新"按钮，调用同一个 `checkForUpdates()` 函数：

```html
<button onclick="App.checkForUpdates()">检查更新</button>
```

并在 `app.js` 的 return 对象中暴露该方法。

### C. 国内加速（GitHub 访问慢时）

配置多个 endpoints 作为 fallback：

```json
"updater": {
  "endpoints": [
    "https://github.com/<owner>/SimplePlanePlatform/releases/latest/download/latest.json",
    "https://your-cdn.example.com/simpleplane/latest.json"
  ],
  "pubkey": "..."
}
```

第二个端点可以是自己的 CDN/OSS，利用 GitHub Actions 在 Release 完成后自动同步 `latest.json` 到国内 CDN。

### D. 更新频道（稳定版 / 测试版）

如果以后想区分 stable 和 beta 频道，可以维护两个 endpoint URL。
Beta 版指向 prerelease 的 `latest.json`，在应用设置界面让用户切换频道。

### E. 强制更新

如果某个版本有严重安全漏洞，需要强制更新：在 `latest.json` 的 `notes` 中加入约定标记（如 `[FORCE]`），前端检测到后隐藏"稍后提醒"按钮。

---

## 文件修改清单总览

| 文件路径 | 操作 | 内容摘要 |
| --- | --- | --- |
| `desktop/src-tauri/Cargo.toml` | 修改 | 添加 `tauri-plugin-updater = "2"` 依赖 |
| `desktop/src-tauri/tauri.conf.json` | 修改 | plugins 中增加 updater 配置（endpoints + pubkey） |
| `desktop/src-tauri/capabilities/default.json` | 修改 | permissions 中增加 `"updater:default"` |
| `desktop/src-tauri/src/main.rs` | 修改 | 注册 `.plugin(tauri_plugin_updater::Builder::new().build())` |
| `desktop/web/app.js` | 修改 | 添加 `checkForUpdates()` + `showUpdateDialog()` 函数 |
| `desktop/web/style.css` | 修改 | 添加 `.update-dialog` 相关样式 |
| `.github/workflows/release.yml` | 修改 | 添加 `TAURI_SIGNING_PRIVATE_KEY` 等环境变量，改 `releaseDraft: false` |
| GitHub Repo Settings | 操作 | 添加两个 Actions Secrets |

---

## 常见问题

### Q: 需要自己维护安装包列表吗？

**不需要。** `tauri-apps/tauri-action` 会在每次 Release 构建时自动生成 `latest.json`，包含所有平台的下载 URL 和签名。你只需保证版本号递增即可。

### Q: 是不是要轮询？

**不是传统意义的轮询。** 应用启动时发一个 HTTPS GET 到 `latest.json`（几百字节的 JSON），比对版本号。这不是"每隔几个小时轮询服务器"那种重量级方案。当然你也可以加定时器做后台周期检查（见进阶配置 A），但即使这样，请求量也极小。

### Q: 用户网络不通（没开代理访问不了 GitHub）怎么办？

Updater 会静默失败，不影响应用正常使用。下次网络通了再启动就能检测到。或者配国内 CDN 镜像端点（见进阶配置 C）。

### Q: macOS Gatekeeper / Windows SmartScreen 会拦截更新吗？

更新包与初次安装包使用相同签名密钥。用户信任过一次后，后续更新不会再触发安全警告。但前提是你做了代码签名（Apple Developer ID / Windows Authenticode）。内测阶段如果没签名，首次安装需要手动放行，之后的更新走 Updater 不经过系统验证。

### Q: 更新过程中断电/断网？

Tauri Updater 使用原子替换——先下载完整新包到临时目录并验证签名，通过后才替换旧文件。下载中断不影响当前版本。

### Q: 和 macOS 的 `.dmg` 安装方式兼容吗？

macOS 上 Tauri Updater 的更新格式是 `.app.tar.gz`（不是 `.dmg`）。`tauri-apps/tauri-action` 会同时生成 `.dmg`（给首次安装用）和 `.app.tar.gz` + `.sig`（给 Updater 用）。首次用 `.dmg` 安装，后续通过 Updater 增量更新，不需要再下载 `.dmg`。

---

## 时间估算

| 步骤 | 预估耗时 | 备注 |
| --- | --- | --- |
| 步骤一：生成密钥 | 5 分钟 | 一条命令 + 记录 |
| 步骤二：Rust 依赖 + 权限 | 10 分钟 | 改 Cargo.toml + default.json |
| 步骤三：tauri.conf.json | 10 分钟 | 粘贴公钥、配 endpoint |
| 步骤四：注册插件 | 5 分钟 | main.rs 加一行 |
| 步骤五：前端 UI | 30 分钟 | JS + CSS |
| 步骤六：CI 流水线 | 15 分钟 | 加 Secrets + 改 yml |
| 步骤七：首次发布验证 | 30 分钟 | 打 tag → 等 CI → 验证 |
| **总计** | **约 1.5~2 小时** | |

---

## 验证清单

完成所有步骤后，按以下流程验证自动更新是否正常工作：

- [ ] 本地 `cd desktop && cargo tauri build` 能编译通过（确认依赖和配置无误）
- [ ] 本地构建产物中包含 `.sig` 签名文件
- [ ] 推送 `v0.2.0` tag 后，CI 成功产出 Release Assets（含 `.sig` 和 `latest.json`）
- [ ] 浏览器访问 `https://github.com/<owner>/SimplePlanePlatform/releases/latest/download/latest.json` 返回正确 JSON
- [ ] 安装旧版本（v0.1.0）的应用
- [ ] 启动旧版，3 秒后弹出"发现新版本 v0.2.0"更新对话框
- [ ] 点击"立即更新"，进度条正常推进
- [ ] 下载完成后应用自动重启，版本号变为 v0.2.0
- [ ] 再次启动不再提示更新（已是最新版）
