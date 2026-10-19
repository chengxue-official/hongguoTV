#!/usr/bin/env bash
# 在 JVM 上编译并验证 LanInputServer（生产源码原文，不修改）
# 目的：无真机/模拟器环境下，对 TV 端局域网服务的 HTTP 行为做协议级验证。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.9.10-hotspot"
JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"

GC=/c/Users/Administrator/.gradle/caches/modules-2/files-2.1
find_jar() { find "$1" -name "$2" 2>/dev/null | head -1; }

KC=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21" "*.jar")
STDLIB=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-stdlib/1.9.24" "*.jar")
CORO=$(find_jar "$GC/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4" "*.jar")
TROVE=$(find_jar "$GC/org.jetbrains.intellij.deps/trove4j/1.0.20200330" "*.jar")
SCRIPT=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-script-runtime/1.9.24" "*.jar")
REFLECT=$(find_jar "$GC/org.jetbrains.kotlin/kotlin-reflect/1.9.22" "*.jar")
ANNOT=$(find_jar "$GC/org.jetbrains/annotations/23.0.0" "*.jar")

w() { cygpath -w "$1"; }

COMPILER_CP="$(w "$KC");$(w "$STDLIB");$(w "$CORO");$(w "$TROVE")"
[ -n "$SCRIPT" ] && COMPILER_CP="$COMPILER_CP;$(w "$SCRIPT")"
[ -n "$REFLECT" ] && COMPILER_CP="$COMPILER_CP;$(w "$REFLECT")"
[ -n "$ANNOT" ] && COMPILER_CP="$COMPILER_CP;$(w "$ANNOT")"

mkdir -p out/stubs out/kotlin out/probe

echo "== 1/4 编译 Android 平台桩 + AppLog 桩 =="
find stubs -name "*.java" > out/stubs.list
"$JAVAC" -encoding UTF-8 -d out/stubs @out/stubs.list

echo "== 2/4 编译生产源码 LanInputServer.kt（原文未改）=="
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 21 -nowarn \
  -d out/kotlin -classpath "$(w "$STDLIB");$(w "$HERE/out/stubs")" \
  src/com/houg/tv/net/LanInputServer.kt 2>&1 | grep -av "^warning:" || true
test -f out/kotlin/com/houg/tv/net/LanInputServer.class || { echo "Kotlin 编译失败"; exit 1; }

echo "== 3/4 编译测试驱动 =="
"$JAVAC" -encoding UTF-8 -d out/probe \
  -classpath "$(w "$STDLIB");$(w "$HERE/out/kotlin");$(w "$HERE/out/stubs")" \
  LanInputProbe.java

echo "== 4/4 运行协议级验证 =="
"$JAVA" -Dfile.encoding=UTF-8 \
  -classpath "$(w "$STDLIB");$(w "$HERE/out/kotlin");$(w "$HERE/out/stubs");$(w "$HERE/out/probe")" \
  LanInputProbe
