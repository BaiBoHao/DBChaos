#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_DIR="$PROJECT_ROOT/target/linux-check"
CLASSES_DIR="$TARGET_DIR/classes"
CLASS_PATH="$CLASSES_DIR:$PROJECT_ROOT/resources:$PROJECT_ROOT/lib/*"

mkdir -p "$CLASSES_DIR"
find "$CLASSES_DIR" -type f -delete

mapfile -t JAVA_SOURCES < <(find "$PROJECT_ROOT/src" -name "*.java" | sort)

javac -encoding UTF-8 -cp "$PROJECT_ROOT/lib/*" -d "$CLASSES_DIR" "${JAVA_SOURCES[@]}"
cp -R "$PROJECT_ROOT/resources/." "$CLASSES_DIR/"

java -cp "$CLASS_PATH" chaos.tools.DbConnectionProbe "$@"
