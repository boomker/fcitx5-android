#!/usr/bin/bash

FCITX5_RIME_REPO="${FCITX5_RIME_REPO:-https://github.com/fcitx/fcitx5-rime.git}"
PREBUILT_REPO="${PREBUILT_REPO:-https://github.com/boomker/f5a-prebuilt.git}"
PREBUILDER_REPO="${PREBUILDER_REPO:-https://github.com/boomker/f5a-prebuilder.git}"

# update fcitx5-rime
echo "updating fcitx5-rime from ${FCITX5_RIME_REPO}"
pushd plugin/rime/src/main/cpp/fcitx5-rime
git remote add gh "${FCITX5_RIME_REPO}" || git remote set-url gh "${FCITX5_RIME_REPO}"
git fetch -v gh master
git checkout gh/master
popd
# sed -i 's|/fcitx/|/fxliang/|g' plugin/rime/licenses/libraries/fcitx5-rime.json

# apply fcitx5 patch from fcitx5-rime
echo "applying fcitx5 patch"
pushd lib/fcitx5/src/main/cpp/fcitx5
# reset to clean state first
git checkout -- .
git apply ../../../../../../plugin/rime/src/main/cpp/fcitx5-rime/fcitx5-alt-trigger-v4point1.patch || echo "fcitx5 patch already applied or failed"
popd

# update prebuilt
echo "updating prebuilt from ${PREBUILT_REPO}"
echo "prebuilt producer repo is ${PREBUILDER_REPO}"
pushd lib/fcitx5/src/main/cpp/prebuilt
git remote add gh "${PREBUILT_REPO}" || git remote set-url gh "${PREBUILT_REPO}"
git fetch -v gh master
git checkout gh/master
popd
