#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
javac -encoding UTF-8 -d "$BUILD" \
  "$ROOT/app/src/main/java/ge/koda/bmw/protocol/EnetProtocol.java" \
  "$ROOT/app/src/main/java/ge/koda/bmw/net/EnetClient.java" \
  "$ROOT/app/src/main/java/ge/koda/bmw/net/EcuScanner.java" \
  "$ROOT/tests/EnetProtocolTests.java"
java -cp "$BUILD" EnetProtocolTests
