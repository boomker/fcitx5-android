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
#include "../../../../../lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/im/pinyin/pinyinconfigmigration.h"

// Compile the real frontend in one translation unit: helper-types.h currently
// defines PagedCandidateEntity::Empty alongside its declarations.
#include "../../../main/cpp/androidfrontend/androidfrontend.cpp"

int main() {
  using namespace fcitx;
  for (const bool enabled : {false, true}) {
    RawConfig legacy;
    legacy.setValueByPath("PartialSpLength", "3");
    legacy.setValueByPath("Fuzzy/PartialSp", enabled ? "True" : "False");
    migrateAbbreviationConfig(legacy);
    FCITX_ASSERT(legacy.get("PartialSpLength")->value() == (enabled ? "3" : "0"));
    FCITX_ASSERT(!legacy.get("Fuzzy/PartialSp"));
    migrateAbbreviationConfig(legacy);
    FCITX_ASSERT(legacy.get("PartialSpLength")->value() == (enabled ? "3" : "0"));
  }
  RawConfig legacyPinyin;
  legacyPinyin.setValueByPath("PartialSpLength", "4");
  legacyPinyin.setValueByPath("Fuzzy/PartialSp", "False");
  migrateAbbreviationConfig(legacyPinyin, true);
  FCITX_ASSERT(legacyPinyin.get("PartialSpLength")->value() == "2");
  legacyPinyin.setValueByPath("PartialSpLength", "0");
  migrateAbbreviationConfig(legacyPinyin, true);
  FCITX_ASSERT(legacyPinyin.get("PartialSpLength")->value() == "0");
  RawConfig defaultPinyin;
  migrateAbbreviationConfig(defaultPinyin, true);
  FCITX_ASSERT(defaultPinyin.get("PartialSpLength")->value() == "2");
  RawConfig implicitDisabled;
  migrateAbbreviationConfig(implicitDisabled);
  FCITX_ASSERT(!implicitDisabled.get("PartialSpLength"));
  implicitDisabled.setValueByPath("PartialSpLength", "4");
  migrateAbbreviationConfig(implicitDisabled);
  FCITX_ASSERT(implicitDisabled.get("PartialSpLength")->value() == "0");
  implicitDisabled.setValueByPath("AbbreviationConfigMigrated", "True");
  implicitDisabled.setValueByPath("PartialSpLength", "4");
  migrateAbbreviationConfig(implicitDisabled);
  FCITX_ASSERT(implicitDisabled.get("PartialSpLength")->value() == "4");
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
    const auto currentConfig = [&instance, ic]() {
      RawConfig result;
      instance.inputMethodEngine(ic)->getConfigForInputMethod(
          *instance.inputMethodEntry(ic))->save(result);
      return result;
    };
    FCITX_ASSERT(currentConfig().get("Fuzzy/NG_GN")->value() == "False");
    FCITX_ASSERT(currentConfig().get("CompactTypoDefaultApplied")->value() == "True");
    FCITX_ASSERT(!currentConfig().get("Fuzzy/PartialSp"));
    RawConfig explicitTypo;
    explicitTypo.setValueByPath("Fuzzy/NG_GN", "True");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), explicitTypo);
    sendCompact(static_cast<KeySym>(0x0200e100));
    sendCompact(static_cast<KeySym>(0x0200e101));
    FCITX_ASSERT(currentConfig().get("Fuzzy/NG_GN")->value() == "True");
    explicitTypo.setValueByPath("Fuzzy/NG_GN", "False");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), explicitTypo);
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

    // Directional letters constrain one physical-key position without
    // switching profiles, starting English, or committing the earlier input.
    const auto exactLetter = [&sendCompact](char letter) {
      sendCompact(static_cast<KeySym>(0x0200e500 + letter - 'a'));
    };
    const auto hasCandidate = [&frontend](const std::string &text) {
      const auto all = frontend.getCandidates(0, 256);
      return std::any_of(all.begin(), all.end(), [&text](const auto &candidate) {
        return candidate.text == text;
      });
    };
    exactLetter('n');
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "n");
    sendCompact(static_cast<KeySym>(0x0200e005)); // IO
    FCITX_ASSERT(hasCandidate("你"));
    FCITX_ASSERT(!hasCandidate("比"));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "ni");
    frontend.keyEvent(Key(FcitxKey_Left, KeyState::Virtual), false, 0);
    frontend.keyEvent(Key(FcitxKey_BackSpace, KeyState::Virtual), false, 0);
    exactLetter('b');
    frontend.keyEvent(Key(FcitxKey_End, KeyState::Virtual), false, 0);
    FCITX_ASSERT(hasCandidate("比"));
    FCITX_ASSERT(!hasCandidate("你"));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "bi");
    frontend.resetInputContext();
    sendCompact(static_cast<KeySym>(0x0200e010)); // BN
    exactLetter('i');
    FCITX_ASSERT(hasCandidate("比"));
    FCITX_ASSERT(hasCandidate("你"));
    frontend.keyEvent(Key(FcitxKey_BackSpace, KeyState::Virtual), false, 0);
    exactLetter('o');
    FCITX_ASSERT(hasCandidate("波"));
    FCITX_ASSERT(!hasCandidate("你"));
    frontend.resetInputContext();
    exactLetter('n');
    sendCompact(static_cast<KeySym>(0x0200e005)); // IO
    exactLetter('h');
    sendCompact(static_cast<KeySym>(0x0200e00e)); // XC
    FCITX_ASSERT(hasCandidate("你好"));
    FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
    frontend.resetInputContext();
    sendCompact(static_cast<KeySym>(0x0200e010)); // BN
    sendCompact(static_cast<KeySym>(0x0200e005)); // IO
    confirm("ni");
    exactLetter('h');
    sendCompact(static_cast<KeySym>(0x0200e00e)); // XC
    FCITX_ASSERT(hasCandidate("你好"));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString().starts_with("ni "));
    FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
    frontend.resetInputContext();
    // A pending English prefix continues in English when the layout returns
    // to compact and receives the same lowercase directional protocol.
    frontend.keyEvent(Key(FcitxKey_W, KeyState::Virtual), false, 0);
    exactLetter('i');
    exactLetter('l');
    exactLetter('l');
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "Will");
    FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
    frontend.resetInputContext();

    // Popup English may follow a composing-code prefix. Its group metadata
    // describes only the English suffix, not absolute buffer positions.
    // Deleting the popup letter and then its prefix must not erase past that
    // shorter metadata vector or leave the original prefix on screen.
    for (const bool exactPrefix : {false, true}) {
      if (exactPrefix) {
        exactLetter('n');
      } else {
        sendCompact(static_cast<KeySym>(0x0200e010)); // BN
      }
      sendCompact(static_cast<KeySym>(0x0200e005)); // IO
      const char popupLetter = exactPrefix ? 'w' : 'W';
      const auto popupOffset = exactPrefix ? 'w' - 'a' : 26 + 'W' - 'A';
      sendCompact(static_cast<KeySym>(0x0200e400 + popupOffset));
      auto expected = ic->inputPanel().clientPreedit().toString();
      FCITX_ASSERT(expected.size() > 1 && expected.back() == popupLetter);
      while (!expected.empty()) {
        expected.pop_back();
        frontend.keyEvent(Key(FcitxKey_BackSpace, KeyState::Virtual), false, 0);
        FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == expected)
            << "exactPrefix=" << exactPrefix << ": "
            << ic->inputPanel().clientPreedit().toString();
        FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
      }
      frontend.resetInputContext();
    }

    // Every directional letter is editable even when this scheme cannot
    // start a Chinese syllable with it (notably Xiaohe i/u/v).
    for (const auto *profile : {"Xiaohe", "MS", "Ziranma", "ABC", "Ziguang",
                                "Zhongwenzhixing", "PinyinJiajia", "GB Standard"}) {
      config.setValueByPath("ShuangpinProfile", profile);
      instance.inputMethodEngine(ic)->setConfigForInputMethod(
          *instance.inputMethodEntry(ic), config);
      FCITX_ASSERT(currentConfig().get("ShuangpinProfile")->value() == profile)
          << "Expected " << profile << ", observed "
          << currentConfig().get("ShuangpinProfile")->value();
      frontend.resetInputContext();
      sendCompact(static_cast<KeySym>(0x0200e101));
      for (char letter = 'a'; letter <= 'z'; ++letter) {
        exactLetter(letter);
        FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() ==
                     std::string(1, letter)) << profile << ": " << letter;
        frontend.keyEvent(Key(FcitxKey_BackSpace, KeyState::Virtual), false, 0);
        FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
        FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
        frontend.resetInputContext();
      }
    }
    config.setValueByPath("ShuangpinProfile", "Xiaohe");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), config);
    frontend.resetInputContext();

    std::vector<KeySym> compactProtocol;
    for (int index = 0; index < 26; ++index) {
      compactProtocol.push_back(static_cast<KeySym>(0x0200e500 + index));
    }
    for (int index = 0; index < 18; ++index) {
      compactProtocol.push_back(static_cast<KeySym>(0x0200e000 + index));
    }
    for (uint32_t sym : {0x0200e100, 0x0200e101, 0x0200e200,
                         0x0200e201, 0x0200e202}) {
      compactProtocol.push_back(static_cast<KeySym>(sym));
    }
    for (int index = 0; index < 27; ++index) {
      compactProtocol.push_back(static_cast<KeySym>(0x0200e210 + index));
      compactProtocol.push_back(static_cast<KeySym>(0x0200e300 + index));
    }
    // A physical, modified, or release-only protocol event cannot type or
    // open a configuration transaction, and cannot reach application code.
    for (const auto sym : compactProtocol) {
      frontend.keyEvent(Key(sym), false, 0);
      frontend.keyEvent(Key(sym, KeyStates(KeyState::Virtual) | KeyState::Ctrl),
                        false, 0);
      frontend.keyEvent(Key(sym, KeyState::Virtual), true, 0);
    }
    FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
    FCITX_ASSERT(commits == std::vector<std::string>{"你好"});
    FCITX_ASSERT(forwardedKeys == 0);

    // Send the new transaction through the real Android frontend. WQ keeps
    // its configured character order, while all profile syllables still decode.
    const std::vector<std::string> groups = {
        "e", "wq", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk",
        "l;", "z", "xc", "v", "bn", "m"};
    const auto configureGroups = [&sendCompact](
                                      const std::vector<std::string> &layout) {
      sendCompact(static_cast<KeySym>(0x0200e200));
      for (const auto &letters : layout) {
        for (const char letter : letters) {
          sendCompact(static_cast<KeySym>(0x0200e210 +
                                            (letter == ';' ? 26 : letter - 'a')));
        }
        sendCompact(static_cast<KeySym>(0x0200e201));
      }
      sendCompact(static_cast<KeySym>(0x0200e202));
      sendCompact(static_cast<KeySym>(0x0200e101));
    };
    configureGroups(groups);
    for (const char representative : std::string("bihx")) {
      sendCompact(static_cast<KeySym>(0x0200e300 + representative - 'a'));
    }
    const auto customCandidates = frontend.getCandidates(0, 128);
    const auto customNihao = std::find_if(
        customCandidates.begin(), customCandidates.end(),
        [](const CandidateEntity &candidate) { return candidate.text == "你好"; });
    FCITX_ASSERT(customNihao != customCandidates.end());
    FCITX_ASSERT(frontend.selectCandidate(
        static_cast<int>(std::distance(customCandidates.begin(), customNihao))));
    sendCompact(static_cast<KeySym>(0x0200e300 + ('w' - 'a')));
    frontend.keyEvent(Key(FcitxKey_Return, KeyState::Virtual), false, 0);
    std::vector<std::string> expectedCommits = {"你好", "你好", "w"};
    FCITX_ASSERT(commits == expectedCommits);
    FCITX_ASSERT(forwardedKeys == 0);

    // Layout notifications can arrive while an addon owns the key pipeline.
    // The next grouped input must use the requested layout after it exits.
    sendCompact(static_cast<KeySym>(0x0200e100));
    config.setValueByPath("QuickPhraseKey", "Control+semicolon");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), config);
    frontend.keyEvent(
        Key(FcitxKey_semicolon, KeyStates(KeyState::Ctrl) | KeyState::Virtual),
        false, 0);
    frontend.keyEvent(Key(FcitxKey_x, KeyState::Virtual), false, 0);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "x");
    const std::vector<std::string> factoryGroups = {
        "q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk",
        "l;", "z", "xc", "v", "bn", "m"};
    configureGroups(factoryGroups);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "x");
    frontend.keyEvent(Key(FcitxKey_Return, KeyState::Virtual), false, 0);
    expectedCommits.push_back("x");
    FCITX_ASSERT(commits == expectedCommits);
    sendCompact(static_cast<KeySym>(0x0200e300 + ('w' - 'a')));
    frontend.keyEvent(Key(FcitxKey_Return, KeyState::Virtual), false, 0);
    expectedCommits.push_back("w");
    FCITX_ASSERT(commits == expectedCommits) << commits;

    // A queued layout update must not send compact keys to a different IM.
    for (const std::string ime : {"pinyin", "keyboard-us"}) {
      instance.setCurrentInputMethod(ic, ime, true);
      FCITX_ASSERT(instance.inputMethodEntry(ic)->uniqueName() == ime);
      for (const auto sym : compactProtocol) sendCompact(sym);
      FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
    }
    FCITX_ASSERT(commits == expectedCommits);
    FCITX_ASSERT(forwardedKeys == 0);

    // Android's highlight is local. Synchronize its exact candidate before
    // Return without using navigation bindings, selecting text, or learning.
    instance.setCurrentInputMethod(ic, "shuangpin", true);
    config.setValueByPath("NextCandidate/0", "Control+n");
    config.setValueByPath("PrevCandidate/0", "Control+p");
    for (const bool fullPinyin : {false, true}) {
      config.setValueByPath("PinyinInPreedit", fullPinyin ? "True" : "False");
      instance.inputMethodEngine(ic)->setConfigForInputMethod(
          *instance.inputMethodEntry(ic), config);
      for (const int pagingMode : {0, 1}) {
        frontend.resetInputContext();
        frontend.setCandidatePagingMode(pagingMode);
        sendCompact(static_cast<KeySym>(0x0200e101));
        typeNiHao();
        const auto all = frontend.getCandidates(0, 256);
        const auto bihua = std::find_if(all.begin(), all.end(), [](const auto &candidate) {
          return candidate.text == "壁画";
        });
        FCITX_ASSERT(bihua != all.end());
        const int absoluteIndex = static_cast<int>(std::distance(all.begin(), bihua));
        FCITX_ASSERT(absoluteIndex > 0);
        const auto list = ic->inputPanel().candidateList();
        const int pageSize = list->size();
        const int pageBefore = list->toPageable()->currentPage();
        int visibleIndex = absoluteIndex;
        if (pagingMode) {
          list->toPageable()->setPage(absoluteIndex / pageSize);
          visibleIndex = absoluteIndex % pageSize;
        }
        FCITX_ASSERT(!frontend.setCandidateCursor(-1));
        FCITX_ASSERT(!frontend.setCandidateCursor(100000));
        FCITX_ASSERT(frontend.call<IAndroidFrontend::setCandidateCursor>(visibleIndex));
        FCITX_ASSERT(list->toBulkCursor()->globalCursorIndex() == absoluteIndex);
        if (!pagingMode) FCITX_ASSERT(list->toPageable()->currentPage() == pageBefore);
        FCITX_ASSERT(commits == expectedCommits);
        frontend.keyEvent(Key(FcitxKey_Return, KeyState::Virtual), false, 0);
        expectedCommits.push_back(fullPinyin ? "bihua" : "bihx");
        FCITX_ASSERT(commits == expectedCommits) << commits;
        FCITX_ASSERT(!frontend.setCandidateCursor(0));
      }
    }
    // Raw Return must restore exact letters even when no complete pair
    // can be decoded; internal projection aliases must never be committed.
    for (const auto &text : {std::string("ni"), std::string("wp")}) {
      frontend.resetInputContext();
      sendCompact(static_cast<KeySym>(0x0200e101));
      for (char letter : text) exactLetter(letter);
      FCITX_ASSERT(commits == expectedCommits);
      frontend.keyEvent(Key(FcitxKey_Return, KeyState::Virtual), false, 0);
      expectedCommits.push_back(text);
      FCITX_ASSERT(commits == expectedCommits) << commits;
    }
    config.setValueByPath("PinyinInPreedit", "False");
    for (const auto *profile : {"Xiaohe", "MS", "Ziranma", "ABC", "Ziguang",
                                "Zhongwenzhixing", "PinyinJiajia", "GB Standard"}) {
      config.setValueByPath("ShuangpinProfile", profile);
      instance.inputMethodEngine(ic)->setConfigForInputMethod(
          *instance.inputMethodEntry(ic), config);
      frontend.resetInputContext();
      sendCompact(static_cast<KeySym>(0x0200e101));
      for (char letter = 'a'; letter <= 'z'; ++letter) {
        exactLetter(letter);
        FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() ==
                     std::string(1, letter)) << profile << ": " << letter;
        FCITX_ASSERT(commits == expectedCommits);
        frontend.keyEvent(Key(FcitxKey_Return, KeyState::Virtual), false, 0);
        expectedCommits.emplace_back(1, letter);
        FCITX_ASSERT(commits == expectedCommits) << profile << ": " << letter;
        FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
      }
    }
    FCITX_ASSERT(forwardedKeys == 0);
    instance.setCurrentInputMethod(ic, "keyboard-us", true);

    // Ordinary application input still reaches the frontend callback.
    frontend.keyEvent(Key(FcitxKey_x, KeyState::Virtual), false, 0);
    FCITX_ASSERT(forwardedKeys == 1);
    delete ic;
    instance.exit();
  });
  instance.exec();
  return 0;
}
