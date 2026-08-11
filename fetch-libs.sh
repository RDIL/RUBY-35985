#!/bin/bash
# Downloads the two ByteBuddy jars into libs/ and verifies them against pinned SHA-256 digests.
#
# They are ~9.3MB and are not committed. The digests below are those of the jars this plugin was
# developed and tested against; a mismatch fails the script rather than warning, because these jars
# get loaded into the IDE's own JVM with instrumentation privileges.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BB=1.17.5
BASE="https://repo1.maven.org/maven2/net/bytebuddy"

SHA_BYTE_BUDDY=71568c9f8396677219f650268fbf6493ded484edcdbdf2dae6129ca5be81e8db
SHA_BYTE_BUDDY_AGENT=c5b9334ad82e632f6af60df22bbbdbbb62cee04877f4f43c38ba04aed9bd9901

mkdir -p "$HERE/libs"

fetch() {
  local artifact="$1" want="$2"
  local jar="$HERE/libs/$artifact-$BB.jar"

  if [ -f "$jar" ]; then
    local have
    have="$(shasum -a 256 "$jar" | awk '{print $1}')"
    if [ "$have" = "$want" ]; then
      echo "ok       $artifact-$BB.jar (already present)"
      return
    fi
    echo "warning  $artifact-$BB.jar digest mismatch, re-downloading" >&2
  fi

  echo "fetching $artifact-$BB.jar"
  curl -fsSL --retry 3 -o "$jar.part" "$BASE/$artifact/$BB/$artifact-$BB.jar"

  local have
  have="$(shasum -a 256 "$jar.part" | awk '{print $1}')"
  if [ "$have" != "$want" ]; then
    rm -f "$jar.part"
    echo "error    $artifact-$BB.jar digest mismatch" >&2
    echo "         expected $want" >&2
    echo "         got      $have" >&2
    exit 1
  fi
  mv "$jar.part" "$jar"
  echo "ok       $artifact-$BB.jar verified"
}

fetch byte-buddy "$SHA_BYTE_BUDDY"
fetch byte-buddy-agent "$SHA_BYTE_BUDDY_AGENT"

echo
echo "libs ready; now run ./build.sh"
