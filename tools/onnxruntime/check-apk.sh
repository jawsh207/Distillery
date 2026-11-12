#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Checks that an APK carries the ONNX Runtime it should: Distillery's own build
# (the checksums in third_party/onnxruntime/libraries.properties) once there
# is one, otherwise the stock library, never Moonshine's cut-down copy, which
# can't run Kokoro.
#
#   tools/onnxruntime/check-apk.sh app.apk
set -euo pipefail
APK="$1"
PROPS="$(dirname "$0")/../../third_party/onnxruntime/libraries.properties"
found=0
for abi in arm64-v8a x86_64; do
    unzip -l "$APK" "lib/$abi/libonnxruntime.so" >/dev/null 2>&1 || continue
    found=1
    sha=$(unzip -p "$APK" "lib/$abi/libonnxruntime.so" | sha256sum | cut -d' ' -f1)
    size=$(unzip -p "$APK" "lib/$abi/libonnxruntime.so" | wc -c)
    if [ -f "$PROPS" ]; then
        want=$(grep "^sha256.$abi=" "$PROPS" | cut -d= -f2)
        [ "$sha" = "$want" ] || { echo "::error title=ONNX Runtime::$APK $abi isn't Distillery's ONNX Runtime ($sha, $size bytes)"; exit 1; }
        echo "$APK $abi: Distillery's ONNX Runtime, $size bytes"
    else
        [ "$size" -gt 12000000 ] || { echo "::error title=ONNX Runtime::$APK $abi isn't the full ONNX Runtime ($size bytes)"; exit 1; }
        echo "$APK $abi: stock ONNX Runtime, $size bytes"
    fi
done
[ "$found" = 1 ] || { echo "::error title=ONNX Runtime::$APK has no ONNX Runtime"; exit 1; }
