/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

#include <algorithm>
#include <array>
#include <fcitx-utils/eventdispatcher.h>
#include <fcitx-utils/standardpaths.h>
#include <fcitx-utils/testing.h>
#include <fcitx/inputmethodgroup.h>
#include <fcitx/inputmethodmanager.h>
#include <string>
#include <vector>

// Compile the real frontend in one translation unit: helper-types.h currently
// defines PagedCandidateEntity::Empty alongside its declarations.
#include "../../../main/cpp/androidfrontend/androidfrontend.cpp"

int main() {
  using namespace fcitx;
  const std::string addonBuild = TEST_CHINESE_ADDONS_BINARY_DIR;
  setupTestingEnvironment(addonBuild, {"bin"},
                          {addonBuild + "/test", addonBuild + "/im",
                           addonBuild + "/modules",
                           StandardPaths::fcitxPath("pkgdatadir")});
  char arg0[] = "testandroidfrontend";
  char arg1[] = "--disable=all";
  char arg2[] = "--enable=pinyin,pinyinhelper,punctuation,spell,quickphrase,testim";
  char *argv[] = {arg0, arg1, arg2};
  Instance instance(FCITX_ARRAY_SIZE(argv), argv);
  instance.addonManager().registerDefaultLoader(nullptr);
  instance.eventDispatcher().schedule([&instance]() {
    FCITX_ASSERT(instance.addonManager().addon("pinyin", true));
    auto group = instance.inputMethodManager().currentGroup();
    group.inputMethodList().clear();
    group.inputMethodList().push_back(InputMethodGroupItem("keyboard-us"));
    group.inputMethodList().push_back(InputMethodGroupItem("pinyin"));
    group.inputMethodList().push_back(InputMethodGroupItem("shuangpin"));
    group.setDefaultInputMethod("");
    instance.inputMethodManager().setGroup(std::move(group));

    AndroidFrontend frontend(&instance);
    std::vector<std::string> commits;
    int forwardedKeys = 0;
    frontend.setCommitStringCallback(
        [&commits](const std::string &text, int, bool) {
          commits.push_back(text);
        });
    frontend.setKeyEventCallback([&forwardedKeys](int, uint32_t, uint32_t, bool,
                                                  int) { ++forwardedKeys; });
    frontend.activateInputContext(18, "compact-shuangpin-host-test");
    auto *ic = frontend.activeInputContext();
    FCITX_ASSERT(ic);
    ic->setCapabilityFlags(CapabilityFlag::Preedit);
    frontend.focusInputContext(true);
    instance.setCurrentInputMethod(ic, "shuangpin", true);
    RawConfig config;
    config.setValueByPath("ShuangpinProfile", "Xiaohe");
    config.setValueByPath("Prediction", "False");
    config.setValueByPath("PreeditMode", "Composing pinyin");
    config.setValueByPath("PinyinInPreedit", "False");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), config);
    frontend.resetInputContext();

    const auto sendCompact = [&frontend](KeySym sym) {
      FCITX_ASSERT(Key::keySymToUnicode(sym) == 0);
      const Key key(sym, KeyState::Virtual);
      frontend.keyEvent(key, false, 0);
      frontend.keyEvent(key, true, 0);
    };
    sendCompact(static_cast<KeySym>(0x0200e101));
    for (int index : {16, 5, 10, 14}) {
      sendCompact(static_cast<KeySym>(0x0200e000 + index));
    }
    const auto candidates = frontend.getCandidates(0, 128);
    const auto nihao = std::find_if(candidates.begin(), candidates.end(),
                                    [](const CandidateEntity &candidate) {
                                      return candidate.text == "你好";
                                    });
    FCITX_ASSERT(nihao != candidates.end());
    FCITX_ASSERT(frontend.selectCandidate(
        static_cast<int>(std::distance(candidates.begin(), nihao))));
    FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
    FCITX_ASSERT(forwardedKeys == 0);

    // Syllables confirm successive input positions, including positions with
    // the same compact code. Fetch each action from the current list.
    const auto typeNiHao = [&sendCompact]() {
      for (int index : {16, 5, 10, 14}) {
        sendCompact(static_cast<KeySym>(0x0200e000 + index));
      }
    };
    const auto syllables = [ic]() {
      auto list = ic->inputPanel().candidateList();
      FCITX_ASSERT(list && list->toTabbed());
      std::vector<CandidateAction> result;
      for (const auto &action : list->toTabbed()->tabActions()) {
        if (action.id() >= 0) result.push_back(action);
      }
      return result;
    };
    const auto confirm = [&frontend, &syllables](const std::string &text) {
      const auto actions = syllables();
      auto action = std::find_if(actions.begin(), actions.end(),
                                [&text](const auto &item) {
                                  return item.text() == text;
                                });
      FCITX_ASSERT(action != actions.end());
      FCITX_ASSERT(!action->isCheckable());
      frontend.triggerCandidateListTabAction(action->id());
    };
    typeNiHao();
    confirm("bi");
    auto second = syllables();
    FCITX_ASSERT(std::none_of(second.begin(), second.end(), [](const auto &a) {
      return a.text() == "bi" || a.text() == "ni";
    }));
    confirm("hua");
    FCITX_ASSERT(syllables().empty());
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "bi hx")
        << ic->inputPanel().clientPreedit().toString();
    FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
    frontend.keyEvent(Key(FcitxKey_BackSpace, KeyState::Virtual), false, 0);
    FCITX_ASSERT(!syllables().empty());
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString().starts_with("bi "));
    frontend.keyEvent(Key(FcitxKey_BackSpace, KeyState::Virtual), false, 0);
    auto first = syllables();
    FCITX_ASSERT(std::any_of(first.begin(), first.end(), [](const auto &a) {
      return a.text() == "bi";
    }));
    frontend.resetInputContext();
    for (int index : {16, 5, 16, 5}) {
      sendCompact(static_cast<KeySym>(0x0200e000 + index));
    }
    confirm("bi");
    confirm("ni");
    FCITX_ASSERT(syllables().empty());
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "bi ni");
    frontend.resetInputContext();
    // An invalid two-key pair still offers the parser's first single key.
    sendCompact(static_cast<KeySym>(0x0200e001)); // WE
    sendCompact(static_cast<KeySym>(0x0200e006)); // P
    confirm("w");
    confirm("p");
    FCITX_ASSERT(syllables().empty());
    frontend.resetInputContext();

    // A queued layout update must not send compact keys to a different IM.
    instance.setCurrentInputMethod(ic, "pinyin", true);
    FCITX_ASSERT(instance.inputMethodEntry(ic)->uniqueName() == "pinyin");
    sendCompact(static_cast<KeySym>(0x0200e101));
    sendCompact(static_cast<KeySym>(0x0200e010));
    instance.setCurrentInputMethod(ic, "keyboard-us", true);
    FCITX_ASSERT(instance.inputMethodEntry(ic)->uniqueName() == "keyboard-us");
    sendCompact(static_cast<KeySym>(0x0200e101));
    sendCompact(static_cast<KeySym>(0x0200e010));
    FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
    FCITX_ASSERT(forwardedKeys == 0);

    // Ordinary application input still reaches the frontend callback.
    frontend.keyEvent(Key(FcitxKey_x, KeyState::Virtual), false, 0);
    FCITX_ASSERT(forwardedKeys == 1);
    delete ic;
    instance.exit();
  });
  instance.exec();
  return 0;
}
