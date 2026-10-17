#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Fcitx5 for Android Contributors
# SPDX-License-Identifier: LGPL-2.1-or-later
#
# Rebuild libime's zh_CN KenLM model (zh_CN.lm) and its prediction trie
# (zh_CN.lm.predict) from an ARPA language model, then optionally install
# them into the prebuilt data submodule that gets packaged into the APK.
#
# The ARPA is the one libime ships/uses, published at
#   https://download.fcitx-im.org/data/lm_sc.arpa-YYYYMMDD.tar.zst
# and referenced by lib/libime/src/main/cpp/libime/data/CMakeLists.txt.
#
# Usage:
#   scripts/libime-lm/build-zh-cn-lm.sh --arpa lm_sc.arpa [--bits 8] \
#       [--outdir DIR] [--install]
#
# Options:
#   --arpa FILE     ARPA file, or the .tar.zst package downloaded above.
#   --bits N        KenLM `-q` quantization bits (default 8; shipped is 4).
#   --outdir DIR    Where to write the results (default: <repo>/build/libime-lm).
#   --install       Copy zh_CN.lm / zh_CN.lm.predict into
#                   lib/fcitx5/src/main/cpp/prebuilt/libime/data/.
#   --work DIR      Scratch dir for the kenlm build (default: <outdir>/work).
#   -h, --help      Show this help.
#
# NOTE: libime hardcodes lm::ngram::QuantArrayTrieModel, so the model MUST be
# built as a quantized array trie: `<bits> -a 22 trie`. An unquantized `trie`
# or `probing` binary is rejected at load time. libime is also compiled with
# KENLM_MAX_ORDER=3, so the ARPA must be a trigram model.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT_DIR="${REPO_ROOT}/scripts/libime-lm"

BITS=8
ARPA=""
OUTDIR="${REPO_ROOT}/build/libime-lm"
WORK=""
INSTALL=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --arpa) ARPA="$2"; shift 2 ;;
        --bits) BITS="$2"; shift 2 ;;
        --outdir) OUTDIR="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --install) INSTALL=1; shift ;;
        -h|--help) sed -n '6,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

[[ -n "${ARPA}" ]] || { echo "error: --arpa is required" >&2; exit 2; }
[[ -n "${WORK}" ]] || WORK="${OUTDIR}/work"
mkdir -p "${OUTDIR}" "${WORK}"

KENLM_SRC="${REPO_ROOT}/lib/libime/src/main/cpp/libime/src/libime/core/kenlm"
LIBIME_SRC="${REPO_ROOT}/lib/libime/src/main/cpp/libime/src"
FCITX_UTILS_PARENT="${REPO_ROOT}/lib/fcitx5/src/main/cpp/fcitx5/src/lib"

[[ -d "${KENLM_SRC}" ]] || { echo "error: kenlm source not found at ${KENLM_SRC}; run 'git submodule update --init' first" >&2; exit 1; }
[[ -d "${LIBIME_SRC}/libime/core" ]] || { echo "error: libime source not found at ${LIBIME_SRC}" >&2; exit 1; }
[[ -f "${FCITX_UTILS_PARENT}/fcitx-utils/macros.h" ]] || { echo "error: fcitx-utils headers not found under ${FCITX_UTILS_PARENT}" >&2; exit 1; }

# 1. Extract the ARPA if the tarball was given.
if [[ "${ARPA}" != *.arpa ]]; then
    echo "==> extracting ARPA from ${ARPA}"
    unzstd -c "${ARPA}" | tar -xf - -C "${WORK}"
    ARPA="$(find "${WORK}" -maxdepth 1 -name '*.arpa' | head -1)"
    [[ -n "${ARPA}" ]] || { echo "error: no .arpa found in the archive" >&2; exit 1; }
fi
[[ -f "${ARPA}" ]] || { echo "error: ${ARPA} not found" >&2; exit 1; }

# 2. Build KenLM's build_binary (must use the same KENLM_MAX_ORDER as libime).
KENLM_BUILD="${WORK}/kenlm-build"
if [[ ! -x "${KENLM_BUILD}/bin/build_binary" ]]; then
    echo "==> building kenlm tools in ${KENLM_BUILD}"
    cmake_args=(-S "${KENLM_SRC}" -B "${KENLM_BUILD}"
        -DCMAKE_BUILD_TYPE=Release -DKENLM_MAX_ORDER=3 -DBUILD_TESTING=OFF)
    if [[ -n "${CMAKE_POLICY_VERSION_MINIMUM:-}" ]]; then
        cmake_args+=("-DCMAKE_POLICY_VERSION_MINIMUM=${CMAKE_POLICY_VERSION_MINIMUM}")
    fi
    cmake "${cmake_args[@]}" >/dev/null
    cmake --build "${KENLM_BUILD}" -j"$(getconf _NPROCESSORS_ONLN)" --target build_binary >/dev/null
fi

# 3. Build the standalone prediction builder against libime's DATrie.
echo "==> building predict_tool"
mkdir -p "${WORK}/stub/libime/core"
cat > "${WORK}/stub/libime/core/libimecore_export.h" <<'EOF'
#ifndef _LIBIMECORE_EXPORT_H_
#define _LIBIMECORE_EXPORT_H_
#define LIBIMECORE_EXPORT
#define LIBIMECORE_DEPRECATED
#endif
EOF

INCLUDES=(-I"${KENLM_SRC}" -I"${LIBIME_SRC}" -I"${WORK}/stub" -I"${FCITX_UTILS_PARENT}")
LIBS=(-L"${KENLM_BUILD}/lib" -lz -lzstd -llzma -lbz2 -lboost_program_options)
if command -v brew >/dev/null 2>&1; then
    BREW_PREFIX="$(brew --prefix)"
    INCLUDES+=(-I"${BREW_PREFIX}/include")
    LIBS=(-L"${BREW_PREFIX}/lib" "${LIBS[@]}")
fi
if [[ -n "${BOOST_INCLUDE:-}" ]]; then INCLUDES+=(-I"${BOOST_INCLUDE}"); fi
if [[ -n "${BOOST_LIB:-}" ]]; then LIBS=(-L"${BOOST_LIB}" "${LIBS[@]}"); fi

c++ -std=c++20 -O2 -DKENLM_MAX_ORDER=3 "${INCLUDES[@]}" \
    "${SCRIPT_DIR}/predict_tool.cc" "${LIBIME_SRC}/libime/core/datrie.cpp" \
    "${KENLM_BUILD}"/lib/libkenlm.a "${KENLM_BUILD}"/lib/libkenlm_util.a \
    "${LIBS[@]}" \
    -o "${WORK}/predict_tool"

# 4. Build the model and the prediction trie.
LM="${OUTDIR}/zh_CN.lm"
PREDICT="${OUTDIR}/zh_CN.lm.predict"
echo "==> build_binary -s -a 22 -q ${BITS} trie"
"${KENLM_BUILD}/bin/build_binary" -s -a 22 -q "${BITS}" trie "${ARPA}" "${LM}"
echo "==> prediction trie"
"${WORK}/predict_tool" build "${LM}" "${ARPA}" "${PREDICT}"

# 5. Report.
echo
echo "==> results (q${BITS})"
ls -l "${LM}" "${PREDICT}"
if command -v shasum >/dev/null 2>&1; then shasum -a 256 "${LM}" "${PREDICT}"; fi

if [[ "${INSTALL}" == 1 ]]; then
    DEST="${REPO_ROOT}/lib/fcitx5/src/main/cpp/prebuilt/libime/data"
    [[ -d "${DEST}" ]] || { echo "error: ${DEST} not found" >&2; exit 1; }
    echo "==> installing into ${DEST}"
    cp "${LM}" "${DEST}/zh_CN.lm"
    cp "${PREDICT}" "${DEST}/zh_CN.lm.predict"
    ls -l "${DEST}/zh_CN.lm" "${DEST}/zh_CN.lm.predict"
    echo "remember to rebuild the APK (./gradlew assembleRelease -PbuildABI=arm64-v8a)"
fi
