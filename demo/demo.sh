#!/bin/bash

# ================================================================
# 配置区
# 功能：设置基准测试路径、配置文件和故障参数
# ================================================================


# 1. 设置 BenchmarkSQL 可执行脚本所在的绝对路径
BENCHMARK_RUN_PATH="/home/baibh/benchmarksql-5.0/run"
# BENCHBASE_RUN_PATH="/home/baibh/benchbase-1.0.0/run"

# 2. 配置文件定义
PROP_FILE="opengauss.properties"

# 3. 配置故障注入参数
INJECT_DELAY=1    # 故障注入的时间点(minute)
FAULT_DURATION=1  # 故障运行的时长(minute)
# FAULT_PARAMS=$1   # 从命令行参数获取故障参数
FAULT_PARAMS="$*"

# 4. 获取故障注入jar
CHAOS_PROPS="../resources/chaos.properties"
CLI_NAME=$(grep "^cli.name=" "$CHAOS_PROPS" | cut -d'=' -f2 | tr -d '\r' | xargs)
CLI_VER=$(grep "^cli.version=" "$CHAOS_PROPS" | cut -d'=' -f2 | tr -d '\r' | xargs)
JAR_PATH="../target/${CLI_NAME}-${CLI_VER}.jar"


# ================================================================
# 逻辑处理区
# ================================================================


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
        mkdir -p "$DEMO_DIR/results/$DB_TYPE"
        
        # 切换至 BenchmarkSQL 运行目录
        LOCAL_RESULTS_DIR="$DEMO_DIR/results/$DB_TYPE/$(date +%Y-%m-%d_%H-%M-%S)"
        echo "[INFO] 压测结果将输出至: $LOCAL_RESULTS_DIR"

        # 创建临时配置文件，强制指定绝对路径输出
        TEMP_PROP="$DEMO_DIR/temp_run.properties"
        sed "s|^resultDirectory=.*|resultDirectory=$LOCAL_RESULTS_DIR|" "$PROP_PATH" > "$TEMP_PROP"
        
        # 检查可执行脚本
        if [ ! -f "$BENCHMARK_RUN_PATH/runBenchmark.sh" ]; then
            echo "错误：在 $BENCHMARK_RUN_PATH 下找不到 runBenchmark.sh"
            rm -f "$TEMP_PROP"
            exit 1
        fi

        # 核心执行流程
        # 1.执行 BenchmarkSQL 压测脚本，传入配置文件路径
        (
            cd "$BENCHMARK_RUN_PATH" || exit 1
            ./runBenchmark.sh "$TEMP_PROP"
        ) &
        BENCH_PID=$!

        # 2.等待到注入时间点
        echo "[INFO] 压测已启动，等待 ${INJECT_DELAY} 分钟后注入故障..."
        sleep $((INJECT_DELAY * 60))

        # 3.注入故障
        if [ -n "$FAULT_PARAMS" ]; then
            echo "[INFO] >>> 正在注入故障: $FAULT_PARAMS"
            java -jar "$JAR_PATH" "$DB_TYPE" $FAULT_PARAMS -duration $((FAULT_DURATION * 60 * 1000))
        else
            echo "[WARN] 未检测到故障参数，仅运行基准压测。"
        fi

        # 4. 等待后台压测结束
        echo "[INFO] 故障注入环节结束..."
        wait "$BENCH_PID"

        # 执行完成执行生成图像的脚本
        if [ -f "$BENCHMARK_RUN_PATH/generateReport.sh" ]; then
            echo "[INFO] 压测完成，正在生成图表..."
            (
                cd "$BENCHMARK_RUN_PATH" || exit 1
                ./generateReport.sh "$LOCAL_RESULTS_DIR"
            )
            echo "[INFO] 图表已生成，保存在 $LOCAL_RESULTS_DIR"
        fi

        # 清理临时文件
        rm -f "$TEMP_PROP"
        ;;

    mysql)
        echo "[INFO] 语系匹配成功，正在通过 Benchbase 启动压测..."
        exit 1
        ;;

    *)
        echo "错误：暂不支持的数据库类型 '$DB_TYPE'，请检查全局配置。"
        exit 1
        ;;
esac