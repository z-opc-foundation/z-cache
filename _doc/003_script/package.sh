#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

# 构建并安装全部 z-cache 模块，方便其他 Maven 项目直接引用。
mvn -f "${SCRIPT_DIR}/pom.xml" clean install -DskipTests
