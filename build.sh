#!/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

OUT="$HERE/out"
STAGE="$OUT/ruby-analysis-probe"

echo "==> jdk       $JH"
echo "==> rubymine  $RM"
echo "==> version   $VERSION"

rm -rf "$OUT"
mkdir -p "$OUT/bootclasses" "$OUT/pluginclasses" "$STAGE/lib" "$STAGE/boot"

echo "==> compiling boot jar (ProbeState + ProbePatch, JDK-only)"
"$JH/bin/javac" --release 21 -nowarn \
  -d "$OUT/bootclasses" \
  "$HERE"/boot/rocks/rdil/rubyprobe/*.java
"$JH/bin/jar" --create --file "$OUT/ruby-probe-boot.jar" -C "$OUT/bootclasses" .

echo "==> building compile classpath from RubyMine"
CP=""
for j in "$RM"/lib/*.jar; do CP="$CP:$j"; done
for j in "$RM"/plugins/ruby/lib/*.jar "$RM"/plugins/ruby/lib/modules/*.jar; do CP="$CP:$j"; done
CP="$CP:$LIBS:$OUT/ruby-probe-boot.jar"
CP="${CP#:}"

echo "==> compiling plugin classes"
"$JH/bin/javac" --release 21 -nowarn \
  -cp "$CP" \
  -d "$OUT/pluginclasses" \
  "$HERE"/plugin/rocks/rdil/rubyprobe/*.java

echo "==> assembling plugin"
# The boot jar is embedded as a RESOURCE inside the plugin jar (nested jars are not on the plugin
# classpath, so ProbeState/ProbePatch stay bootstrap-only) and is also shipped in boot/ as a
# fallback. IntelliJ's PathClassLoader does not give a usable CodeSource location, so path derivation
# alone is not dependable -- the embedded resource is the primary lookup.
mkdir -p "$OUT/jarres/boot"
cp "$OUT/ruby-probe-boot.jar" "$OUT/jarres/boot/"
"$JH/bin/jar" --create --file "$STAGE/lib/ruby-analysis-probe.jar" \
  -C "$OUT/pluginclasses" . \
  -C "$HERE/resources" . \
  -C "$OUT/jarres" .
cp "$LIBS_DIR/byte-buddy-$BB.jar" "$LIBS_DIR/byte-buddy-agent-$BB.jar" "$STAGE/lib/"
cp "$OUT/ruby-probe-boot.jar" "$STAGE/boot/"

echo "==> verifying layout"
python3 - "$STAGE/lib/ruby-analysis-probe.jar" <<'PY'
import sys, zipfile, io
outer = zipfile.ZipFile(sys.argv[1])
names = outer.namelist()
assert 'boot/ruby-probe-boot.jar' in names, "boot jar not embedded"
inner = zipfile.ZipFile(io.BytesIO(outer.read('boot/ruby-probe-boot.jar')))
for cls in ('ProbeState', 'ProbePatch'):
    entry = 'rocks/rdil/rubyprobe/%s.class' % cls
    assert entry in inner.namelist(), (cls, inner.namelist())
    # Must NOT be reachable as a plugin-classpath class: exactly one copy, on bootstrap, or the
    # instrumented Ruby code and the tool window would see different statics.
    assert entry not in names, "%s leaked onto plugin classpath" % cls
assert not any('AncestorsAdvice.class' in n for n in names), "stale sink advice still shipped"
print("    embedded boot jar ok; ProbeState/ProbePatch bootstrap-only")
PY

echo "==> zipping"
ZIP="ruby-analysis-probe-$VERSION.zip"
(cd "$OUT" && rm -f "$ZIP" && zip -qr "$ZIP" "ruby-analysis-probe")

echo
echo "built: $OUT/$ZIP"
find "$STAGE" -type f | sed "s|$OUT/||" | sort
echo
echo "install: RubyMine > Settings > Plugins > gear > Install Plugin from Disk..."
