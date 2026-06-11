#!/usr/bin/env bash
#
# start-remote-test.sh —— 启动一个本地 Java proxy-remote 实例，供 Rust 出站层
# 做协议互通测试（见任务文档 Task Q2 / A5）。
#
# A1 阶段为占位骨架：仅定位现有 proxy-remote 模块并给出启动提示，
# 真正的可重复互通 harness（固定 cipher/密钥、回环 echo target、清理逻辑）在 Q2 落地。
#
# 用法：
#   scripts/start-remote-test.sh        # 提示如何启动（A1 占位）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "[start-remote-test] 仓库根目录: ${REPO_ROOT}"
echo "[start-remote-test] 这是 A1 占位脚本。完整的协议互通 harness 将在 Task Q2 实现："
echo "  - 以固定 cipher=chacha20-poly1305 + 固定密钥启动 proxy-remote"
echo "  - 背后挂一个本地 echo TCP server 作为公网目标"
echo "  - 测试结束清理进程"
echo ""
echo "当前可手动启动 proxy-remote（参考 docs/启动说明.md）："
echo "  cd ${REPO_ROOT} && mvn -pl proxy-remote -am exec:java"
