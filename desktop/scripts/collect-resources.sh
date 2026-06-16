#!/bin/bash
# ==============================================================================
# collect-resources.sh — 收集所有运行时资源到 Tauri resources 目录
# 将 fat-jar、tun-adapter、web UI、配置模板 汇集到 desktop/src-tauri/resources/
# 幂等：可重复执行
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DESKTOP_DIR="$PROJECT_ROOT/desktop"
RESOURCES_DIR="$DESKTOP_DIR/src-tauri/resources"

echo "=== SimplePlane: Collect Resources ==="
echo "Project root: $PROJECT_ROOT"
echo "Resources:    $RESOURCES_DIR"

# 清理并重建 resources 目录（保留 jre/ 如果已存在）
mkdir -p "$RESOURCES_DIR"

# 1. 复制 fat-jar（必需）
FAT_JAR="$PROJECT_ROOT/proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar"
if [ -f "$FAT_JAR" ]; then
    cp "$FAT_JAR" "$RESOURCES_DIR/proxy-local.jar"
    echo "[OK] proxy-local.jar ($(du -h "$RESOURCES_DIR/proxy-local.jar" | cut -f1))"
else
    echo "[ERROR] proxy-local.jar not found at $FAT_JAR"
    echo "        Run: mvn package -pl proxy-local -am -DskipTests"
    exit 1
fi

# 2. 复制 tun-adapter 二进制
# 支持两种路径：直接 release 或带 target triple（CI 中 --target 指定时）
TUN_BIN="$PROJECT_ROOT/tun-adapter/target/release/tun-adapter"
if [ ! -f "$TUN_BIN" ]; then
    # 尝试带 target triple 的路径（CI 中 cargo build --release --target xxx）
    for candidate in "$PROJECT_ROOT"/tun-adapter/target/*/release/tun-adapter; do
        if [ -f "$candidate" ]; then
            TUN_BIN="$candidate"
            break
        fi
    done
fi
if [ -f "$TUN_BIN" ]; then
    cp "$TUN_BIN" "$RESOURCES_DIR/tun-adapter"
    chmod +x "$RESOURCES_DIR/tun-adapter"
    echo "[OK] tun-adapter ($(du -h "$RESOURCES_DIR/tun-adapter" | cut -f1))"
else
    echo "[WARN] tun-adapter not found (non-fatal, TUN mode won't work)"
fi

# 3. 复制 Web UI 静态资源
WEB_SRC="$DESKTOP_DIR/web"
WEB_DEST="$RESOURCES_DIR/web"
if [ -d "$WEB_SRC" ]; then
    rm -rf "$WEB_DEST"
    cp -r "$WEB_SRC" "$WEB_DEST"
    echo "[OK] web/ (dashboard UI)"
else
    echo "[WARN] Web UI not found at $WEB_SRC"
fi

# 4. 复制默认配置模板
CONFIG_TEMPLATE_DIR="$RESOURCES_DIR/config-templates"
mkdir -p "$CONFIG_TEMPLATE_DIR"

# proxy.yml 模板
PROXY_YML="$PROJECT_ROOT/proxy-local/src/main/resources/proxy.yml"
if [ -f "$PROXY_YML" ]; then
    cp "$PROXY_YML" "$CONFIG_TEMPLATE_DIR/proxy.yml"
    echo "[OK] config-templates/proxy.yml"
else
    echo "[WARN] proxy.yml template not found"
fi

# tun.toml 模板
TUN_TOML="$PROJECT_ROOT/tun-adapter/config/tun.toml"
if [ -f "$TUN_TOML" ]; then
    cp "$TUN_TOML" "$CONFIG_TEMPLATE_DIR/tun.toml"
    echo "[OK] config-templates/tun.toml"
else
    echo "[WARN] tun.toml template not found"
fi

# 5. Windows 特有：wintun.dll（仅在 Windows 或存在时复制）
WINTUN_DLL="$PROJECT_ROOT/tun-adapter/wintun.dll"
if [ -f "$WINTUN_DLL" ]; then
    cp "$WINTUN_DLL" "$RESOURCES_DIR/wintun.dll"
    echo "[OK] wintun.dll"
fi

echo ""
echo "=== Resources Summary ==="
echo "Directory: $RESOURCES_DIR"
ls -la "$RESOURCES_DIR/" 2>/dev/null || true
echo ""

# 验证关键文件
MISSING=0
[ ! -f "$RESOURCES_DIR/proxy-local.jar" ] && echo "MISSING: proxy-local.jar" && MISSING=1
[ ! -d "$RESOURCES_DIR/jre" ] && echo "MISSING: jre/ (run build-jre.sh first)" && MISSING=1

if [ $MISSING -eq 1 ]; then
    echo ""
    echo "WARNING: Some resources are missing. Build may not produce a fully functional package."
else
    echo "All critical resources present!"
fi

echo "Done!"
