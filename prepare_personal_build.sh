#!/usr/bin/bash

set -e  # Exit on error

FCITX5_RIME_REPO="${FCITX5_RIME_REPO:-https://github.com/fcitx/fcitx5-rime.git}"
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
RIME_SCHEMA_NAME_PATCH="${PROJECT_ROOT}/plugin/rime/fcitx5-rime-full-schema-name.patch"
RIME_PREEDIT_LABEL_PATCH="${PROJECT_ROOT}/plugin/rime/fcitx5-rime-preedit-cursor-label.patch"
FCITX5_ALT_TRIGGER_PATCH="${RIME_DIR}/fcitx5-alt-trigger-v4point1.patch"

# update fcitx5-rime
echo "updating fcitx5-rime from ${FCITX5_RIME_REPO}"
git -C "${RIME_DIR}" remote add gh "${FCITX5_RIME_REPO}" 2>/dev/null || \
    git -C "${RIME_DIR}" remote set-url gh "${FCITX5_RIME_REPO}"
git -C "${RIME_DIR}" fetch -v gh master
git -C "${RIME_DIR}" checkout gh/master
# apply patches for fcitx5-rime
echo "applying fcitx5-rime patches"
git -C "${RIME_DIR}" apply --ignore-whitespace "${RIME_SCHEMA_NAME_PATCH}" || \
    echo "schema name patch already applied or failed"
git -C "${RIME_DIR}" apply --ignore-whitespace "${RIME_PREEDIT_LABEL_PATCH}" || \
    echo "preedit cursor label patch already applied or failed"
# sed -i 's|/fcitx/|/fxliang/|g' plugin/rime/licenses/libraries/fcitx5-rime.json

# apply fcitx5 patch from fcitx5-rime
echo "applying fcitx5 patch"
git -C "${FCITX5_DIR}" checkout -- .
git -C "${FCITX5_DIR}" apply "${FCITX5_ALT_TRIGGER_PATCH}" || \
    echo "fcitx5 patch already applied or failed"

# update prebuilt
echo "updating prebuilt from ${PREBUILT_REPO}"
echo "prebuilt producer repo is ${PREBUILDER_REPO}"
git -C "${PREBUILT_DIR}" remote add gh "${PREBUILT_REPO}" 2>/dev/null || \
    git -C "${PREBUILT_DIR}" remote set-url gh "${PREBUILT_REPO}"
git -C "${PREBUILT_DIR}" fetch -v gh master
git -C "${PREBUILT_DIR}" checkout gh/master
