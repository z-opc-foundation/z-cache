#!/bin/bash

# z-cache-client 测试运行脚本

echo "========================================="
echo "Running z-cache-client tests"
echo "========================================="
echo ""

# 运行测试
cd "$(dirname "$0")"
mvn test -pl z-cache-client -DskipTests=false

# 检查测试结果
if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "All tests passed!"
    echo "========================================="
else
    echo ""
    echo "========================================="
    echo "Some tests failed!"
    echo "========================================="
    exit 1
fi
