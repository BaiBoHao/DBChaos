#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DB_PROBE_SCRIPT="$SCRIPT_DIR/check_db_connection.sh"
DEFAULT_JAR="$PROJECT_ROOT/target/DBChaos-0.0.1.jar"
JAR_PATH="$DEFAULT_JAR"
CHECK_DEMO=false
FORWARD_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jar)
            JAR_PATH="$2"
            shift 2
            ;;
        --with-demo)
            CHECK_DEMO=true
            shift
            ;;
        *)
            FORWARD_ARGS+=("$1")
            shift
            ;;
    esac
done

printf 'DBChaos Preflight Check
'
printf '  ProjectRoot : %s
' "$PROJECT_ROOT"
printf '  JarPath     : %s
' "$JAR_PATH"

if ! command -v java >/dev/null 2>&1; then
    echo 'ERROR: missing java in PATH'
    exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
    echo 'ERROR: missing javac in PATH'
    exit 1
fi

if [[ ! -f "$PROJECT_ROOT/resources/db.properties" ]]; then
    echo 'ERROR: missing resources/db.properties'
    exit 1
fi

if [[ ! -x "$DB_PROBE_SCRIPT" ]]; then
    echo 'ERROR: missing executable database probe script'
    echo "  expected: $DB_PROBE_SCRIPT"
    exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
    echo 'ERROR: missing DBChaos jar'
    echo "  expected: $JAR_PATH"
    echo '  build first: ./build_for_linux.sh DBChaos'
    exit 1
fi

echo '[1/3] Database connection probe'
"$DB_PROBE_SCRIPT" "${FORWARD_ARGS[@]}"

echo '[2/3] DBChaos jar check'
java -jar "$JAR_PATH" --help >/dev/null
printf '  OK: jar can start and print help
'

if [[ "$CHECK_DEMO" == true ]]; then
    echo '[3/3] Demo prerequisite check'
    DEMO_SCRIPT="$PROJECT_ROOT/demo/demo.sh"
    DEMO_README="$PROJECT_ROOT/demo/README.md"
    BENCHMARK_RUN_PATH=$(grep '^BENCHMARK_RUN_PATH=' "$DEMO_SCRIPT" | cut -d'=' -f2- | tr -d '"')

    if [[ ! -f "$DEMO_SCRIPT" ]]; then
        echo 'ERROR: missing demo/demo.sh'
        exit 1
    fi
    if [[ ! -f "$DEMO_README" ]]; then
        echo 'ERROR: missing demo/README.md'
        exit 1
    fi
    if [[ ! -d "$BENCHMARK_RUN_PATH" ]]; then
        echo 'ERROR: BenchmarkSQL run path does not exist'
        echo "  path: $BENCHMARK_RUN_PATH"
        exit 1
    fi
    if [[ ! -f "$BENCHMARK_RUN_PATH/runBenchmark.sh" ]]; then
        echo 'ERROR: runBenchmark.sh not found under BenchmarkSQL path'
        echo "  path: $BENCHMARK_RUN_PATH"
        exit 1
    fi
    printf '  OK: demo prerequisites look complete
'
fi

echo 'Preflight check passed.'
