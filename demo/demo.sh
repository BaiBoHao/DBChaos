#!/bin/bash

# ================================================================
# DBChaos Demo
# 功能：验证 DBChaos 的韧性故障画像注入
# ================================================================

# 1. 配置区：设置 BenchmarkSQL 可执行脚本所在的绝对路径
BENCHMARK_RUN_PATH="/home/baibh/benchmarksql-5.0/run"
# BENCHBASE_RUN_PATH="/home/baibh/benchbase-1.0.0/run"


# 2. 配置文件定义
PROP_FILE="opengauss.properties"
echo "------------------------------------------------"
echo ">>> DBChaos Demo 验证"
echo "------------------------------------------------"


# 校验配置文件和 BenchmarkSQL 路径
DEMO_DIR=$(cd "$(dirname "$0")"; pwd)
PROP_PATH="$DEMO_DIR/$PROP_FILE"
if [ ! -f "$PROP_PATH" ]; then
    echo "错误：在 $DEMO_DIR 下找不到配置文件 $PROP_FILE"
    exit 1
fi
if [ ! -d "$BENCHMARK_RUN_PATH" ]; then
    echo "错误：配置的 BenchmarkSQL 路径不存在：$BENCHMARK_RUN_PATH"
    echo "请编辑 demo.sh 脚本，填入正确的 run 目录路径。"
    exit 1
fi

# 定义全局数据库配置文件路径
GLOBAL_DB_PROP="../resources/db.properties"
if [ ! -f "$GLOBAL_DB_PROP" ]; then
    echo "错误：找不到全局配置文件 $GLOBAL_DB_PROP"
    exit 1
fi
# 提取数据库类型并清洗空格与回车符
DB_TYPE=$(grep "^type=" "$GLOBAL_DB_PROP" | cut -d'=' -f2 | tr -d '\r' | xargs)
echo "[INFO] 全局配置检测到数据库类型: $DB_TYPE"


# 根据不同type的运行基准测试
case "$DB_TYPE" in
    opengauss|postgresql)
        echo "[INFO] 正在通过 BenchmarkSQL 启动压测..."
        
        # 切换至 BenchmarkSQL 运行目录
        cd "$BENCHMARK_RUN_PATH" || exit 1

        echo "[INFO] 生成Demo验证结果目录..."
        mkdir -p ../results/"$DB_TYPE"/"$DB_TYPE"_$(date +%Y-%m-%d_%H-%M-%S)
        
        # 检查可执行脚本
        if [ ! -f "./runBenchmark.sh" ]; then
            echo "错误：在 $BENCHMARK_RUN_PATH 下找不到 runBenchmark.sh"
            exit 1
        fi

        ./runBenchmark.sh "$PROP_PATH"
        ;;

    mysql)
        echo "[INFO] 语系匹配成功，正在通过 Benchbase 启动压测..."
        
        # 校验 Benchbase 路径
        if [ ! -d "$BENCHBASE_RUN_PATH" ]; then
            echo "错误：配置的 Benchbase 路径不存在：$BENCHBASE_RUN_PATH"
            exit 1
        fi

        cd "$BENCHBASE_RUN_PATH" || exit 1
        
        # 假设 Benchbase 执行脚本为 benchbase.sh
        if [ ! -f "./benchbase.sh" ]; then
            echo "错误：在 $BENCHBASE_RUN_PATH 下找不到 benchbase.sh"
            exit 1
        fi

        ./benchbase.sh -b tpcc -c "$PROP_PATH" --execute=true
        ;;

    *)
        echo "错误：暂不支持的数据库类型 '$DB_TYPE'，请检查全局配置。"
        exit 1
        ;;
esac