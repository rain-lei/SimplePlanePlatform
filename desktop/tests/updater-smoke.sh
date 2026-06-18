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
