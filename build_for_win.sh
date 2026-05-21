#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
COMMAND="${1:-build}"
JAR_NAME="${2:-DBChaos-0.0.1}"

TARGET_DIR="$PROJECT_ROOT/target/win-build"
CLASSES_DIR="$TARGET_DIR/classes"
FAT_JAR_DIR="$TARGET_DIR/fat-jar"
OUTPUT_JAR="$PROJECT_ROOT/target/${JAR_NAME}.jar"
ROOT_COPY_JAR="$PROJECT_ROOT/${JAR_NAME}.jar"

require_tool() {
    local tool="$1"
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: missing required tool: $tool"
        exit 1
    fi
}

prepare_dirs() {
    rm -rf "$CLASSES_DIR" "$FAT_JAR_DIR"
    mkdir -p "$CLASSES_DIR" "$FAT_JAR_DIR" "$(dirname "$OUTPUT_JAR")"
}

compile_sources() {
    mapfile -t JAVA_SOURCES < <(find "$PROJECT_ROOT/src" -name "*.java" | sort)
    if [[ ${#JAVA_SOURCES[@]} -eq 0 ]]; then
        echo "ERROR: no Java sources found under src/"
        exit 1
    fi

    javac -encoding UTF-8 -cp "$PROJECT_ROOT/lib/*" -d "$CLASSES_DIR" "${JAVA_SOURCES[@]}"
    cp -R "$PROJECT_ROOT/resources/." "$CLASSES_DIR/"
}

expand_dependencies() {
    cp -R "$CLASSES_DIR/." "$FAT_JAR_DIR/"

    shopt -s nullglob
    for dep_jar in "$PROJECT_ROOT"/lib/*.jar; do
        (
            cd "$FAT_JAR_DIR"
            jar xf "$dep_jar"
        )
    done
    shopt -u nullglob

    if [[ -d "$FAT_JAR_DIR/META-INF" ]]; then
        find "$FAT_JAR_DIR/META-INF" -type f \( -name "*.SF" -o -name "*.DSA" -o -name "*.RSA" \) -delete
    fi
}

package_jar() {
    rm -f "$OUTPUT_JAR" "$ROOT_COPY_JAR"
    (
        cd "$FAT_JAR_DIR"
        jar cfe "$OUTPUT_JAR" chaos.Main .
    )
    cp "$OUTPUT_JAR" "$ROOT_COPY_JAR"
}

preview_help() {
    java -cp "$CLASSES_DIR;$PROJECT_ROOT/resources;$PROJECT_ROOT/lib/*" chaos.Main --help
}

build() {
    prepare_dirs
    compile_sources
    expand_dependencies
    package_jar
    echo "Build completed."
    echo "Output jar: $OUTPUT_JAR"
    echo "Root copy : $ROOT_COPY_JAR"
}

show_usage() {
    cat <<'EOF'
Usage:
  ./build_for_win.sh build [jar_name]
  ./build_for_win.sh preview-help
  ./build_for_win.sh build-and-help [jar_name]
EOF
}

require_tool javac
require_tool jar

case "$COMMAND" in
    build)
        build
        ;;
    preview-help)
        prepare_dirs
        compile_sources
        preview_help
        ;;
    build-and-help)
        build
        java -jar "$OUTPUT_JAR" --help
        ;;
    help|-h|--help)
        show_usage
        ;;
    *)
        echo "ERROR: unknown command: $COMMAND"
        show_usage
        exit 1
        ;;
esac
