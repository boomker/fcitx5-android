#!/usr/bin/bash

set -e  # Exit on error

FCITX5_RIME_REPO="${FCITX5_RIME_REPO:-https://github.com/boomker/fcitx5-rime.git}"
PREBUILT_REPO="${PREBUILT_REPO:-https://github.com/boomker/f5a-prebuilt.git}"
PREBUILDER_REPO="${PREBUILDER_REPO:-https://github.com/boomker/f5a-prebuilder.git}"

# Get script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}"

# Define all paths relative to project root
RIME_DIR="${PROJECT_ROOT}/plugin/rime/src/main/cpp/fcitx5-rime"
FCITX5_DIR="${PROJECT_ROOT}/lib/fcitx5/src/main/cpp/fcitx5"
PREBUILT_DIR="${PROJECT_ROOT}/lib/fcitx5/src/main/cpp/prebuilt"

# Patch files
FCITX5_GLOBAL_OPTIONS_UI_PATCH="${PROJECT_ROOT}/lib/fcitx5/fcitx5-global-options-ui.patch"
FCITX5_INSERT_SPACE_ZH_EN_PATCH="${PROJECT_ROOT}/lib/fcitx5/fcitx5-insert-space-zh-en.patch"
# RIME_PREEDIT_LABEL_PATCH="${PROJECT_ROOT}/plugin/rime/fcitx5-rime-preedit-cursor-label.patch"

apply_patch() {
    local repository="$1"
    local patch_file="$2"
    local patch_name="$3"

    if git -C "${repository}" apply --check --ignore-whitespace "${patch_file}"; then
        git -C "${repository}" apply --ignore-whitespace "${patch_file}"
        echo "✓ ${patch_name} patch applied successfully"
    elif git -C "${repository}" apply --reverse --check --ignore-whitespace "${patch_file}"; then
        echo "✓ ${patch_name} patch already applied"
    else
        echo "✗ ${patch_name} patch does not apply" >&2
        return 1
    fi
}

# update fcitx5-rime
echo "updating fcitx5-rime from ${FCITX5_RIME_REPO}"
git -C "${RIME_DIR}" remote add gh "${FCITX5_RIME_REPO}" 2>/dev/null || \
    git -C "${RIME_DIR}" remote set-url gh "${FCITX5_RIME_REPO}"
git -C "${RIME_DIR}" fetch -v gh master
git -C "${RIME_DIR}" checkout gh/master
# apply_patch "${RIME_DIR}" "${RIME_PREEDIT_LABEL_PATCH}" "preedit cursor label"

# apply fcitx5 patches
echo "applying fcitx5 patches"
git -C "${FCITX5_DIR}" checkout -- .
apply_patch "${FCITX5_DIR}" "${FCITX5_GLOBAL_OPTIONS_UI_PATCH}" "global-options-ui"
apply_patch "${FCITX5_DIR}" "${FCITX5_INSERT_SPACE_ZH_EN_PATCH}" "insert-space-zh-en"

# update prebuilt
echo "updating prebuilt from ${PREBUILT_REPO}"
echo "prebuilt producer repo is ${PREBUILDER_REPO}"
git -C "${PREBUILT_DIR}" remote add gh "${PREBUILT_REPO}" 2>/dev/null || \
    git -C "${PREBUILT_DIR}" remote set-url gh "${PREBUILT_REPO}"
git -C "${PREBUILT_DIR}" fetch -v gh master
git -C "${PREBUILT_DIR}" checkout gh/master
