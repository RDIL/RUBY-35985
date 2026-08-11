#!/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

OUT="$HERE/out"
BOOT="$OUT/ruby-probe-boot.jar"
PLUGIN_JAR="$OUT/ruby-analysis-probe/lib/ruby-analysis-probe.jar"

[ -f "$BOOT" ] || { echo "run ./build.sh first"; exit 1; }

mkdir -p "$OUT/testclasses"
echo "==> compiling tests"
"$JH/bin/javac" --release 21 -nowarn \
  -cp "$OUT/pluginclasses:$BOOT:$LIBS" \
  -d "$OUT/testclasses" "$HERE"/smoke/*.java

fail=0
run() {
  local name="$1"; shift
  echo
  echo "================ $name ================"
  "$JH/bin/java" -XX:+EnableDynamicAgentLoading "$@" \
    || { echo "!! $name FAILED"; fail=1; }
}

# PatchTest is the one that matters: it drives the real advice through real ByteBuddy weaving against
# stand-ins carrying RubyMine's exact signatures, and asserts the cycle runs away again with the
# patch switched off -- so a pass cannot come from the stand-in terminating on its own.
run PatchTest \
  -cp "$OUT/pluginclasses:$BOOT:$LIBS:$OUT/testclasses" \
  -Drubyprobe.pkg=PatchTest -Drubyprobe.stallSeconds=3600 PatchTest "$BOOT"

# The stack sampler matches frames by package prefix, so each test points it at its own stand-ins.
run SmokeTest \
  -cp "$OUT/pluginclasses:$BOOT:$LIBS:$OUT/testclasses" \
  -Drubyprobe.pkg=SmokeTest -Drubyprobe.stallSeconds=3600 SmokeTest "$BOOT"

# Deliberately the built JAR and nothing else: this is the boot-jar-location regression test, so the
# boot jar must NOT be on the classpath and the IntelliJ classes must be absent.
run InstallerTest \
  -cp "$PLUGIN_JAR:$LIBS:$OUT/testclasses" \
  -Drubyprobe.stallSeconds=3600 InstallerTest

echo
if [ "$fail" = 0 ]; then
  echo "ALL TESTS PASSED"
else
  echo "TESTS FAILED"
  exit 1
fi
