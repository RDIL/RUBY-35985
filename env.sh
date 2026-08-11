#!/bin/bash
# Sourced by build.sh / test.sh. Locates a JDK and a RubyMine install, or fails with something
# actionable. Both are machine-specific and neither is committed, so everything that varies per
# checkout lives here.
#
# Override either explicitly:
#   JAVA_HOME=/path/to/jdk RUBYMINE_HOME=/path/to/RubyMine.app/Contents ./build.sh

BB=1.17.5

die() { echo "error: $*" >&2; exit 1; }

# ---------------------------------------------------------------------- JDK
# Needs 21+. RubyMine 2026.2 runs on JBR 25, and the boot jar is appended to the bootstrap
# classloader, so its class file version must not exceed what the IDE's JVM accepts.
if [ -n "${JAVA_HOME:-}" ]; then
  JH="$JAVA_HOME"
else
  JH=""
  # Prefer a JetBrains runtime if one is around: it is what the IDE itself runs.
  for c in "$HOME"/Library/Java/JavaVirtualMachines/jbrsdk*/Contents/Home \
           "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [ -x "$c/bin/javac" ] || continue
    JH="$c"
  done
  if [ -z "$JH" ] && [ -x /usr/libexec/java_home ]; then
    JH="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"
  fi
fi
[ -n "$JH" ] && [ -x "$JH/bin/javac" ] \
  || die "no JDK found. Set JAVA_HOME to a JDK 21 or newer."

JV="$("$JH/bin/javac" -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
[ "${JV:-0}" -ge 21 ] 2>/dev/null \
  || die "javac at $JH is version ${JV:-unknown}; 21 or newer required."

# ----------------------------------------------------------------- RubyMine
# The plugin compiles against the IDE's own jars, which are not redistributable and not committed.
if [ -n "${RUBYMINE_HOME:-}" ]; then
  RM="$RUBYMINE_HOME"
else
  RM=""
  for c in "$HOME/Applications/RubyMine.app/Contents" \
           "/Applications/RubyMine.app/Contents" \
           "$HOME/Applications/JetBrains Toolbox/RubyMine.app/Contents"; do
    [ -d "$c/plugins/ruby/lib" ] || continue
    RM="$c"
    break
  done
fi
[ -n "$RM" ] && [ -d "$RM/plugins/ruby/lib" ] \
  || die "RubyMine not found. Set RUBYMINE_HOME=/path/to/RubyMine.app/Contents"

# ---------------------------------------------------------------- ByteBuddy
LIBS_DIR="$HERE/libs"
if [ ! -f "$LIBS_DIR/byte-buddy-$BB.jar" ] || [ ! -f "$LIBS_DIR/byte-buddy-agent-$BB.jar" ]; then
  die "ByteBuddy $BB missing from libs/. Run ./fetch-libs.sh"
fi
LIBS="$LIBS_DIR/byte-buddy-$BB.jar:$LIBS_DIR/byte-buddy-agent-$BB.jar"

# ------------------------------------------------------------------ version
# Single source of truth: plugin.xml.
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HERE/resources/META-INF/plugin.xml" \
  | head -1)"
[ -n "$VERSION" ] || die "could not read <version> from resources/META-INF/plugin.xml"
