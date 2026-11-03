#!/bin/sh
# Quick Kotlin syntax check before pushing (no Android SDK here, so only syntax errors are reported;
# real compile errors show up in CI). Usage: tools/kcheck.sh $(find app/src -name "*.kt")
LIB=$(ls -d /opt/gradle-*/lib "$HOME"/.gradle/wrapper/dists/*/*/gradle-*/lib 2>/dev/null | head -1)
[ -n "$LIB" ] || { echo "No Gradle install found (its lib folder carries the Kotlin compiler)."; exit 1; }
CP=$(ls "$LIB"/kotlin-compiler-embeddable-*.jar "$LIB"/kotlin-stdlib-[0-9]*.jar "$LIB"/kotlin-reflect-*.jar \
  "$LIB"/kotlin-script-runtime-*.jar "$LIB"/kotlinx-coroutines-core-jvm-*.jar "$LIB"/annotations-*.jar \
  "$LIB"/kotlin-daemon-embeddable-*.jar "$LIB"/trove4j-*.jar 2>/dev/null | tr '\n' ':')
OUT=$(mktemp -d)
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -d "$OUT" "$@" 2>&1 \
  | grep -iE "error: .*(expecting|syntax error|unclosed)|^exception:" | head -30
rm -rf "$OUT"
echo done
