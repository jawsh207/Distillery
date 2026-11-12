#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds Distillery's ONNX Runtime from source: version 1.23.2 (the one
# Moonshine's library is linked against), cut down to the operators in
# third_party/onnxruntime/distillery-operators.config.
#
#   --minimal_build extended custom_ops
#       Loads only models in ONNX Runtime's own format, which is all Moonshine
#       uses, and leaves out the ONNX parser and the graph optimisers. The same
#       kind of build Moonshine makes for its own Android library.
#   --include_ops_by_config   only the kernels Distillery's models need
#   --disable_ml_ops          no classical machine-learning operators
#   --compile_no_warning_as_error
#       ONNX Runtime 1.23 warns about an unused constant in minimal builds.
#
# NNAPI and XNNPACK are left out: Moonshine runs everything on the CPU.
#
#   tools/onnxruntime/build.sh <android-arm64-v8a|android-x86_64|linux-x86_64> <out-dir>
#
# Needs ANDROID_HOME (with NDK $ANDROID_NDK_VERSION) for the Android targets.
set -euo pipefail

TARGET="$1"
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ORT_VERSION="${ORT_VERSION:-1.23.2}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-28.2.13676358}"
CONFIG="${REPO}/third_party/onnxruntime/distillery-operators.config"
WORK="${ORT_WORK:-${HOME}/ort}"
SRC="${WORK}/onnxruntime"

[ -f "${CONFIG}" ] || { echo "No operator config at ${CONFIG}" >&2; exit 1; }

if [ ! -d "${SRC}/.git" ]; then
    mkdir -p "${WORK}"
    git clone --recursive --depth 1 --shallow-submodules --branch "v${ORT_VERSION}" \
        https://github.com/microsoft/onnxruntime.git "${SRC}"
fi

common=(
    --config Release
    --build_shared_lib
    --minimal_build extended custom_ops
    --include_ops_by_config "${CONFIG}"
    --disable_ml_ops
    --compile_no_warning_as_error
    --skip_tests
    --parallel
)

case "${TARGET}" in
    android-*)
        ABI="${TARGET#android-}"
        NDK="${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}"
        [ -d "${NDK}" ] || { echo "NDK ${ANDROID_NDK_VERSION} not found at ${NDK}" >&2; exit 1; }
        BUILD="${WORK}/build-${TARGET}"
        (cd "${SRC}" && ./build.sh --build_dir "${BUILD}" "${common[@]}" \
            --android --android_abi "${ABI}" --android_api 26 \
            --android_sdk_path "${ANDROID_HOME}" --android_ndk_path "${NDK}")
        "${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
            "${BUILD}/Release/libonnxruntime.so" -o "${OUT}/libonnxruntime.so"
        # Android 15 and later run on phones with 16 KB memory pages.
        align=$(readelf -lW "${OUT}/libonnxruntime.so" | awk '$1 == "LOAD" { print $NF }' | sort -u)
        for a in ${align}; do
            [ $((a)) -ge 16384 ] || { echo "LOAD segment aligned to ${a}, not 16 KB" >&2; exit 1; }
        done
        ;;
    linux-x86_64)
        # Only for the smoke test, which runs Moonshine's Linux library against it.
        BUILD="${WORK}/build-${TARGET}"
        (cd "${SRC}" && ./build.sh --build_dir "${BUILD}" "${common[@]}")
        strip --strip-unneeded "${BUILD}/Release/libonnxruntime.so.${ORT_VERSION}" -o "${OUT}/libonnxruntime.so"
        ;;
    *)
        echo "Unknown target ${TARGET}" >&2
        exit 1
        ;;
esac

ls -l "${OUT}/libonnxruntime.so"
# Moonshine's library asks for these exact symbol versions.
for symbol in OrtGetApiBase OrtSessionOptionsAppendExecutionProvider_CPU; do
    readelf --dyn-syms -W "${OUT}/libonnxruntime.so" | grep -q "${symbol}@@VERS_${ORT_VERSION}" \
        || { echo "${symbol}@@VERS_${ORT_VERSION} isn't exported" >&2; exit 1; }
done
