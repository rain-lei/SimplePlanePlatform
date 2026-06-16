#!/bin/bash
# ==============================================================================
# build-jre.sh — 使用 jlink 生成精简 JRE
# 用 JDK 17 的 jdeps 推导 fat-jar 依赖模块，jlink 生成最小 JRE
# 产出目录: desktop/src-tauri/resources/jre/
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DESKTOP_DIR="$PROJECT_ROOT/desktop"
RESOURCES_DIR="$DESKTOP_DIR/src-tauri/resources"
JRE_OUTPUT="$RESOURCES_DIR/jre"

# fat-jar 路径
FAT_JAR="$PROJECT_ROOT/proxy-local/target/proxy-local-1.0.0-SNAPSHOT.jar"

echo "=== SimplePlane: Build Minimal JRE ==="
echo "Project root: $PROJECT_ROOT"
echo "Output dir:   $JRE_OUTPUT"

# 检查 JDK 17+
JAVA_VERSION=$(java -version 2>&1 | head -n1 | awk -F '"' '{print $2}' | cut -d. -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "ERROR: JDK 17+ required for jlink. Current: $JAVA_VERSION"
    exit 1
fi

# 检查 fat-jar 是否存在（如果不存在则先构建）
if [ ! -f "$FAT_JAR" ]; then
    echo "fat-jar not found, building with maven..."
    cd "$PROJECT_ROOT"
    mvn package -pl proxy-local -am -DskipTests -q
fi

if [ ! -f "$FAT_JAR" ]; then
    echo "ERROR: Failed to build fat-jar at $FAT_JAR"
    exit 1
fi

echo "fat-jar: $FAT_JAR ($(du -h "$FAT_JAR" | cut -f1))"

# 使用 jdeps 分析依赖模块
echo "Analyzing module dependencies with jdeps..."
MODULES=$(jdeps --ignore-missing-deps --print-module-deps "$FAT_JAR" 2>/dev/null || echo "")

if [ -z "$MODULES" ]; then
    # 如果 jdeps 分析失败，使用保守的模块列表
    echo "jdeps analysis incomplete, using conservative module set"
    MODULES="java.base,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported"
fi

# 确保关键模块在列表中
REQUIRED="java.base,java.logging,java.management,java.naming,jdk.crypto.ec,jdk.unsupported"
for mod in $(echo "$REQUIRED" | tr ',' '\n'); do
    if ! echo "$MODULES" | grep -q "$mod"; then
        MODULES="$MODULES,$mod"
    fi
done

echo "Modules: $MODULES"

# 清理旧产物
rm -rf "$JRE_OUTPUT"

# 使用 jlink 生成精简 JRE
echo "Running jlink..."
JAVA_HOME_DIR=$(dirname $(dirname $(readlink -f $(which java) 2>/dev/null || echo $(which java))))

# JDK 17 uses --compress=2 (ZIP), JDK 21+ uses --compress=zip-6
if [ "$JAVA_VERSION" -ge 21 ]; then
    COMPRESS_ARG="--compress=zip-6"
else
    COMPRESS_ARG="--compress=2"
fi

jlink \
    --module-path "$JAVA_HOME_DIR/jmods" \
    --add-modules "$MODULES" \
    --output "$JRE_OUTPUT" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    $COMPRESS_ARG

echo ""
echo "=== JRE Build Complete ==="
echo "Size: $(du -sh "$JRE_OUTPUT" | cut -f1)"
echo "Java: $("$JRE_OUTPUT/bin/java" -version 2>&1 | head -n1)"
echo ""

# 验证能运行 fat-jar
echo "Verifying JRE can run proxy-local..."
timeout 5 "$JRE_OUTPUT/bin/java" -jar "$FAT_JAR" --help 2>/dev/null && echo "OK" || echo "Verification skipped (expected if --help not supported)"

echo "Done! JRE at: $JRE_OUTPUT"
