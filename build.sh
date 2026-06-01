#!/usr/bin/env bash
# F11AgingTool 一键编译脚本
# 用法: ./build.sh [clean|install]
#   clean  — 先 clean 再 assembleDebug
#   install — assembleDebug 后安装到已连接设备
#   无参数  — 快速 assembleDebug

set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="D:/jdk-17.0.14.7-hotspot"

case "${1:-}" in
  clean)
    echo "==> 清理并编译..."
    ./gradlew clean assembleDebug
    ;;
  install)
    echo "==> 编译并安装..."
    ./gradlew assembleDebug
    echo "==> 正在安装到设备..."
    ./gradlew installDebug
    ;;
  *)
    echo "==> 快速编译..."
    ./gradlew assembleDebug
    ;;
esac

echo "==> ✅ 完成"
