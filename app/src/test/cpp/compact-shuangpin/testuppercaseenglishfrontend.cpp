/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <algorithm>
#include <chrono>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <fcitx-utils/standardpaths.h>
#include <fcitx-utils/testing.h>
#include <fcitx/inputmethodgroup.h>
#include <fcitx/inputmethodmanager.h>
#include <string>
#include <vector>

#include "../../../main/cpp/androidfrontend/androidfrontend.cpp"

int main() {
  using namespace fcitx;
  const std::string addonBuild = TEST_CHINESE_ADDONS_BINARY_DIR;
  const auto phraseRoot = std::filesystem::temp_directory_path() /
                          ("fcitx-uppercase-english-" +
                           std::to_string(std::chrono::steady_clock::now()
                                              .time_since_epoch()
                                              .count()));
  std::filesystem::create_directories(phraseRoot / "pinyin");
  {
    std::ofstream phrases(phraseRoot / "pinyin/customphrase");
    FCITX_ASSERT(phrases.good());
    phrases << "DNS,1=DNS 服务器\n"
               "DNS,2=DNS 服务器\n"
               "DNA,1=DNA\n"
               "jiance,1=检测\n"
               "DNS,-1=禁用的短语\n";
  }
  setupTestingEnvironment(addonBuild, {"bin"},
                          {phraseRoot.string(), addonBuild + "/test",
                           addonBuild + "/im",
                           addonBuild + "/modules",
                           TEST_CHINESE_ADDONS_SOURCE_DIR "/im",
                           StandardPaths::fcitxPath("pkgdatadir")});
  FCITX_ASSERT(StandardPaths::global().locate(
                   StandardPathsType::PkgData, "pinyin/customphrase") ==
               phraseRoot / "pinyin/customphrase");
  char arg0[] = "testuppercaseenglishfrontend";
  char arg1[] = "--disable=all";
  char arg2[] =
      "--enable=pinyin,pinyinhelper,punctuation,spell,quickphrase,testim";
  char *argv[] = {arg0, arg1, arg2};
  Instance instance(FCITX_ARRAY_SIZE(argv), argv);
  instance.addonManager().registerDefaultLoader(nullptr);
  instance.eventDispatcher().schedule([&instance]() {
    FCITX_ASSERT(instance.addonManager().addon("pinyin", true));
    auto group = instance.inputMethodManager().currentGroup();
    group.inputMethodList().clear();
    for (const auto *name : {"keyboard-us", "pinyin", "shuangpin"}) {
      group.inputMethodList().push_back(InputMethodGroupItem(name));
    }
    group.setDefaultInputMethod("");
    instance.inputMethodManager().setGroup(std::move(group));
    AndroidFrontend frontend(&instance);
    std::vector<std::string> commits;
    std::vector<KeySym> forwarded;
    int candidateNotifications = 0;
    frontend.setCandidateListCallback(
        [&candidateNotifications](const auto &, int) {
          ++candidateNotifications;
        });
    frontend.setKeyEventCallback(
        [&forwarded](int sym, uint32_t, uint32_t, bool release, int) {
          if (!release)
            forwarded.push_back(static_cast<KeySym>(sym));
        });
    frontend.setCommitStringCallback(
        [&commits](const std::string &text, int, bool) {
          commits.push_back(text);
        });
    frontend.activateInputContext(26, "uppercase-english-host-test");
    auto *ic = frontend.activeInputContext();
    FCITX_ASSERT(ic);
    ic->setCapabilityFlags(CapabilityFlags(CapabilityFlag::Preedit) |
                           CapabilityFlag::Sensitive);
    frontend.focusInputContext(true);
    instance.setCurrentInputMethod(ic, "shuangpin", true);
    frontend.setCandidatePagingMode(0);
    const auto press = [&frontend](KeySym sym) {
      frontend.keyEvent(Key(sym, KeyState::Virtual), false, 0);
      frontend.keyEvent(Key(sym, KeyState::Virtual), true, 0);
    };
    const auto type = [&press](const std::string &text) {
      for (const auto letter : text)
        press(static_cast<KeySym>(letter));
    };
    const auto assertEnglish = [&frontend, ic,
                                &commits](const std::string &text) {
      FCITX_ASSERT(commits.empty())
          << "Premature commit while composing " << text;
      FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == text)
          << "Expected " << text << ", observed "
          << ic->inputPanel().clientPreedit().toString();
      const auto candidates = frontend.getCandidates(0, 256);
      FCITX_ASSERT(!candidates.empty()) << "No English candidates for " << text;
      FCITX_ASSERT(candidates.front().text == text);
      for (const auto &candidate : candidates) {
        FCITX_ASSERT(candidate.comment.empty())
            << candidate.text << ": " << candidate.comment;
        FCITX_ASSERT(std::all_of(candidate.text.begin(), candidate.text.end(),
                                 [](unsigned char c) { return c < 128; }))
            << candidate.text;
      }
    };
    for (const bool compact : {false, true}) {
      for (const bool fullPinyin : {false, true}) {
        RawConfig config;
        config.setValueByPath("ShuangpinProfile", "Xiaohe");
        config.setValueByPath("PreeditMode", "Composing pinyin");
        config.setValueByPath("PinyinInPreedit", fullPinyin ? "True" : "False");
        config.setValueByPath("PinyinAnnotation", "Always");
        config.setValueByPath("SpellEnabled", "True");
        config.setValueByPath("Prediction", "False");
        instance.inputMethodEngine(ic)->setConfigForInputMethod(
            *instance.inputMethodEntry(ic), config);
        frontend.resetInputContext();
        press(static_cast<KeySym>(compact ? 0x0200e101 : 0x0200e100));
        std::string prefix;
        for (const auto letter : std::string("Will")) {
          press(static_cast<KeySym>(letter));
          prefix += letter;
          assertEnglish(prefix);
          FCITX_ASSERT(instance.inputMethodEntry(ic)->uniqueName() ==
                       "shuangpin");
        }
        for (int i = 0; i < 4; ++i)
          press(FcitxKey_BackSpace);
        FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
        if (compact) {
          for (int index : {16, 5, 10, 14})
            press(static_cast<KeySym>(0x0200e000 + index));
        } else {
          type("nihc");
        }
        const auto chinese = frontend.getCandidates(0, 256);
        FCITX_ASSERT(
            std::any_of(chinese.begin(), chinese.end(),
                        [](const auto &c) { return c.text == "你好"; }));
        FCITX_ASSERT(commits.empty());
        frontend.resetInputContext();
        type("WILL");
        assertEnglish("WILL");
        frontend.resetInputContext();
        type("Hel");
        assertEnglish("Hel");
        const auto completions = frontend.getCandidates(0, 256);
        const auto completion = std::find_if(
            completions.begin(), completions.end(), [](const auto &c) {
              if (c.text.size() <= 3)
                return false;
              std::string prefix = c.text.substr(0, 3);
              std::transform(prefix.begin(), prefix.end(), prefix.begin(),
                             [](unsigned char c) { return std::tolower(c); });
              return prefix == "hel";
            });
        FCITX_ASSERT(completion != completions.end())
            << "Missing English completion for Hel";
        FCITX_ASSERT(frontend.selectCandidate(
            static_cast<int>(std::distance(completions.begin(), completion))));
        FCITX_ASSERT(commits == std::vector<std::string>{completion->text});
        FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
        commits.clear();
      }
    }
    // Highlight changes in a normal Chinese composition must immediately
    // switch between full pinyin and the original English encoding.
    RawConfig config;
    config.setValueByPath("ShuangpinProfile", "Xiaohe");
    config.setValueByPath("PreeditMode", "Composing pinyin");
    config.setValueByPath("PinyinInPreedit", "True");
    config.setValueByPath("SpellEnabled", "True");
    config.setValueByPath("Prediction", "False");
    config.setValueByPath("NextCandidate/0", "Tab");
    config.setValueByPath("PrevCandidate/0", "Shift+Tab");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), config);
    frontend.resetInputContext();
    press(static_cast<KeySym>(0x0200e100));
    type("will");
    const auto mixed = frontend.getCandidates(0, 256);
    const auto english =
        std::find_if(mixed.begin(), mixed.end(),
                     [](const auto &c) { return c.text == "will"; });
    FCITX_ASSERT(english != mixed.end());
    const auto englishIndex =
        static_cast<int>(std::distance(mixed.begin(), english));
    FCITX_ASSERT(englishIndex > 0);
    const auto snapshot = frontend.getCandidateViewportSnapshot(256);
    const auto notificationsBeforeHighlight = candidateNotifications;
    FCITX_ASSERT(frontend.setCandidateCursor(englishIndex));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "will");
    FCITX_ASSERT(frontend.getCandidateViewportSnapshot(256).revision ==
                 snapshot.revision);
    FCITX_ASSERT(candidateNotifications == notificationsBeforeHighlight)
        << "Highlight must not republish the candidate list";
    FCITX_ASSERT(frontend.setCandidateCursor(0));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() != "will");
    FCITX_ASSERT(frontend.getCandidateViewportSnapshot(256).revision ==
                 snapshot.revision);
    FCITX_ASSERT(candidateNotifications == notificationsBeforeHighlight);
    FCITX_ASSERT(frontend.setCandidateCursor(englishIndex - 1));
    frontend.keyEvent(Key(FcitxKey_Tab), false, 0);
    FCITX_ASSERT(
        ic->inputPanel().candidateList()->toBulkCursor()->globalCursorIndex() ==
        englishIndex)
        << "English index " << englishIndex << ", observed "
        << ic->inputPanel()
               .candidateList()
               ->toBulkCursor()
               ->globalCursorIndex();
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "will")
        << ic->inputPanel().clientPreedit().toString();
    FCITX_ASSERT(commits.empty());
    FCITX_ASSERT(frontend.setCandidateCursor(1));
    frontend.keyEvent(Key(FcitxKey_Tab, KeyState::Shift), false, 0);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() != "will");
    FCITX_ASSERT(commits.empty());

    // Preserve the selected UTF-8 prefix and character cursor while a
    // compact popup letter moves the composition to the literal buffer.
    for (const bool cursorAtBeginning : {false, true}) {
      config.setValueByPath("PreeditCursorPositionAtBeginning",
                            cursorAtBeginning ? "True" : "False");
      instance.inputMethodEngine(ic)->setConfigForInputMethod(
          *instance.inputMethodEntry(ic), config);
      frontend.resetInputContext();
      press(static_cast<KeySym>(0x0200e101));
      type("nihc");
      const auto candidates = frontend.getCandidates(0, 256);
      const auto ni =
          std::find_if(candidates.begin(), candidates.end(),
                       [](const auto &c) { return c.text == "你"; });
      FCITX_ASSERT(ni != candidates.end());
      FCITX_ASSERT(frontend.selectCandidate(
          static_cast<int>(std::distance(candidates.begin(), ni))));
      FCITX_ASSERT(commits.empty());
      frontend.repositionCursor(1);
      press(static_cast<KeySym>(0x0200e400 + ('i' - 'a')));
      const auto &preedit = ic->inputPanel().clientPreedit();
      FCITX_ASSERT(preedit.toString() == "你ihc") << preedit.toString();
      if (cursorAtBeginning) {
        FCITX_ASSERT(preedit.stringAt(0) == "你i");
        FCITX_ASSERT(preedit.stringAt(1) == "hc");
        FCITX_ASSERT(preedit.cursor() == 0);
      } else {
        FCITX_ASSERT(preedit.cursor() == 4);
      }
      FCITX_ASSERT(commits.empty());
      press(FcitxKey_BackSpace);
      FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "你hc");
      FCITX_ASSERT(commits.empty());
      frontend.repositionCursor(3);
      forwarded.clear();
      press(FcitxKey_period);
      FCITX_ASSERT(commits == std::vector<std::string>{"你hc"}) << commits;
      FCITX_ASSERT(forwarded == std::vector<KeySym>{FcitxKey_period});
      commits.clear();
    }
    config.setValueByPath("PreeditCursorPositionAtBeginning", "False");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), config);
    frontend.resetInputContext();
    press(static_cast<KeySym>(0x0200e101));
    type("I'm");
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "I'm");
    press(FcitxKey_Return);
    FCITX_ASSERT(commits == std::vector<std::string>{"I'm"}) << commits;
    commits.clear();
    frontend.resetInputContext();
    type("X-1");
    assertEnglish("X-1");
    press(FcitxKey_Return);
    FCITX_ASSERT(commits == std::vector<std::string>{"X-1"}) << commits;
    commits.clear();
    frontend.resetInputContext();
    for (int index : {16, 5, 10, 14})
      press(static_cast<KeySym>(0x0200e000 + index));
    frontend.repositionCursor(2);
    press(static_cast<KeySym>(0x0200e400 + ('i' - 'a')));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() ==
                 "[bn][io]ih[xc]")
        << ic->inputPanel().clientPreedit().toString();
    press(FcitxKey_BackSpace);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() ==
                 "[bn][io]h[xc]")
        << ic->inputPanel().clientPreedit().toString();
    press(FcitxKey_Return);
    FCITX_ASSERT(commits ==
                 std::vector<std::string>{"[bn][io]h[xc]"})
        << commits;
    commits.clear();
    frontend.resetInputContext();
    for (int index : {16, 5, 10, 14})
      press(static_cast<KeySym>(0x0200e000 + index));
    press(static_cast<KeySym>(0x0200e400 + 26 + ('W' - 'A')));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "B I H XW");
    FCITX_ASSERT(commits.empty());
    press(FcitxKey_Return);
    FCITX_ASSERT(commits == std::vector<std::string>{"B I H XW"}) << commits;
    commits.clear();

    // Exercise actual compact-key events, including a word whose matching
    // letters are the second members of WE and IO. A highlighted completion
    // displays only the prefix corresponding to the entered key positions.
    const auto groupKey = [&press](int index) {
      press(static_cast<KeySym>(0x0200e000 + index));
    };
    const auto popupLetter = [&press](char letter) {
      const int offset = letter >= 'A' && letter <= 'Z'
                             ? 26 + letter - 'A'
                             : letter - 'a';
      press(static_cast<KeySym>(0x0200e400 + offset));
    };
    for (const auto &prefix : {std::string("h"), std::string("he"),
                               std::string("yo"), std::string("YO"),
                               std::string("W"), std::string("Wi")}) {
      frontend.resetInputContext();
      press(static_cast<KeySym>(0x0200e101));
      for (const auto letter : prefix)
        popupLetter(letter);
      FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == prefix);
      FCITX_ASSERT(commits.empty()) << "Popup letters committed " << prefix;
      const auto candidates = frontend.getCandidates(0, 256);
      FCITX_ASSERT(std::any_of(
          candidates.begin(), candidates.end(), [&prefix](const auto &candidate) {
            return candidate.text.size() > prefix.size() &&
                   candidate.text.starts_with(prefix);
          })) << "Missing English completion for popup prefix " << prefix;
      FCITX_ASSERT(std::all_of(candidates.begin(), candidates.end(),
                               [](const auto &candidate) {
                                 return candidate.comment.empty();
                               }));
    }
    const auto highlightEnglish = [&frontend, &instance, ic, &commits](
                                      const std::string &word, size_t length) {
      const auto candidates = frontend.getCandidates(0, 256);
      const auto candidate =
          std::find_if(candidates.begin(), candidates.end(),
                       [&word](const auto &c) { return c.text == word; });
      FCITX_ASSERT(candidate != candidates.end()) << "Missing " << word;
      for (const auto &c : candidates) {
        FCITX_ASSERT(c.comment.empty()) << c.text << ": " << c.comment;
        FCITX_ASSERT(std::all_of(c.text.begin(), c.text.end(),
                                 [](unsigned char ch) { return ch < 128; }));
      }
      const auto index =
          static_cast<int>(std::distance(candidates.begin(), candidate));
      FCITX_ASSERT(frontend.setCandidateCursor(index));
      FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() ==
                   word.substr(0, length))
          << ic->inputPanel().clientPreedit().toString();
      FCITX_ASSERT(commits.empty());
      FCITX_ASSERT(instance.inputMethodEntry(ic)->uniqueName() == "shuangpin");
      return index;
    };
    frontend.resetInputContext();
    press(FcitxKey_W);
    groupKey(5);  // IO
    groupKey(12); // L;
    highlightEnglish("Will", 3);
    groupKey(12);
    highlightEnglish("Will", 4);
    press(FcitxKey_BackSpace);
    highlightEnglish("Will", 3);
    groupKey(12);
    highlightEnglish("Will", 4);
    frontend.repositionCursor(2);
    press(FcitxKey_BackSpace);
    groupKey(1); // replace IO with WE at its original position
    highlightEnglish("Well", 4);
    frontend.repositionCursor(2);
    press(FcitxKey_Delete);
    groupKey(12);
    highlightEnglish("Well", 4);
    frontend.resetInputContext();
    press(static_cast<KeySym>(0x0200e400 + 26 + ('W' - 'A')));
    press(static_cast<KeySym>(0x0200e400 + ('i' - 'a')));
    groupKey(12);
    groupKey(12);
    highlightEnglish("Will", 4);
    for (int i = 0; i < 4; ++i)
      press(FcitxKey_BackSpace);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
    for (int index : {16, 5, 10, 14})
      groupKey(index);
    const auto restored = frontend.getCandidates(0, 256);
    FCITX_ASSERT(std::any_of(restored.begin(), restored.end(),
                             [](const auto &c) { return c.text == "你好"; }));
    FCITX_ASSERT(commits.empty());
    frontend.resetInputContext();
    press(FcitxKey_H);
    for (int index : {1, 12, 12, 5})
      groupKey(index);
    const auto hello = highlightEnglish("Hello", 5);
    FCITX_ASSERT(frontend.selectCandidate(hello));
    FCITX_ASSERT(commits == std::vector<std::string>{"Hello"});
    FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
    commits.clear();

    // A failed prediction displays every ambiguous group; a click between
    // those expanded labels still selects the corresponding input position.
    frontend.resetInputContext();
    press(FcitxKey_W);
    groupKey(16); // BN
    groupKey(16);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "W[bn][bn]");
    frontend.repositionCursor(3); // inside the first [bn] label
    press(static_cast<KeySym>(0x0200e400 + ('x' - 'a')));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "Wx[bn][bn]");
    press(FcitxKey_BackSpace);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "W[bn][bn]");
    frontend.repositionCursor(5);
    press(static_cast<KeySym>(0x0200e400 + ('x' - 'a')));
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "W[bn]x[bn]");
    press(FcitxKey_BackSpace);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "W[bn][bn]");
    press(FcitxKey_Return);
    FCITX_ASSERT(commits == std::vector<std::string>{"W[bn][bn]"});
    commits.clear();
    frontend.resetInputContext();
    press(FcitxKey_W);
    groupKey(16);
    forwarded.clear();
    press(FcitxKey_period);
    FCITX_ASSERT(commits == std::vector<std::string>{"W[bn]"});
    FCITX_ASSERT(forwarded == std::vector<KeySym>{FcitxKey_period});

    // Uppercase English composes with general custom phrases from the user
    // phrase source. Duplicate outputs collapse, disabled entries stay hidden,
    // and selecting the mixed-script phrase commits its complete value.
    frontend.resetInputContext();
    commits.clear();
    press(static_cast<KeySym>(0x0200e101));
    const auto dnsPrefix = [&frontend](const std::string &prefix) {
      const auto candidates = frontend.getCandidates(0, 256);
      FCITX_ASSERT(std::any_of(candidates.begin(), candidates.end(),
                               [](const auto &candidate) {
                                 return candidate.text == "DNS 服务器";
                               })) << "Mixed phrase was missing for prefix " << prefix;
    };
    const auto checkDnsPrefixes = [&frontend, &press, &popupLetter, &dnsPrefix, ic](
                                     const std::string &prefix) {
      frontend.resetInputContext();
      press(static_cast<KeySym>(0x0200e101));
      for (size_t i = 0; i < prefix.size(); ++i) {
        popupLetter(prefix[i]);
        FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() ==
                     prefix.substr(0, i + 1));
        dnsPrefix(prefix.substr(0, i + 1));
      }
    };
    checkDnsPrefixes("DNS");
    checkDnsPrefixes("dns");
    frontend.resetInputContext();
    press(static_cast<KeySym>(0x0200e101));
    for (const auto letter : std::string("DNS"))
      popupLetter(letter);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().toString() == "DNS");
    FCITX_ASSERT(commits.empty());
    const auto phraseCandidates = frontend.getCandidates(0, 256);
    const auto mixedPhrase =
        std::find_if(phraseCandidates.begin(), phraseCandidates.end(),
                     [](const auto &candidate) {
                       return candidate.text == "DNS 服务器";
                     });
    FCITX_ASSERT(mixedPhrase != phraseCandidates.end())
        << "Uppercase English input skipped the configured mixed phrase";
    FCITX_ASSERT(std::count_if(phraseCandidates.begin(), phraseCandidates.end(),
                               [](const auto &candidate) {
                                 return candidate.text == "DNS 服务器";
                               }) == 1)
        << "The custom phrase was not deduplicated";
    FCITX_ASSERT(std::none_of(phraseCandidates.begin(), phraseCandidates.end(),
                              [](const auto &candidate) {
                                return candidate.text == "禁用的短语";
                              }))
        << "A disabled custom phrase was offered";
    const auto mixedPhraseIndex = static_cast<int>(
        std::distance(phraseCandidates.begin(), mixedPhrase));
    FCITX_ASSERT(frontend.selectCandidate(mixedPhraseIndex));
    FCITX_ASSERT(commits == std::vector<std::string>{"DNS 服务器"})
        << commits;
    FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
    commits.clear();

    // Committing an English candidate seeds prediction context. A following
    // Chinese commit becomes a next-word prediction, not a completion.
    config.setValueByPath("Prediction", "True");
    instance.inputMethodEngine(ic)->setConfigForInputMethod(
        *instance.inputMethodEntry(ic), config);
    ic->setCapabilityFlags(CapabilityFlags(CapabilityFlag::Preedit));
    frontend.resetInputContext();
    press(static_cast<KeySym>(0x0200e101));
    for (const auto letter : std::string("DNA"))
      popupLetter(letter);
    const auto dnaCandidates = frontend.getCandidates(0, 256);
    FCITX_ASSERT(std::none_of(dnaCandidates.begin(), dnaCandidates.end(),
                              [](const auto &candidate) {
                                return candidate.text == "检测";
                              }))
        << "A learned prediction was offered as an English completion";
    const auto dna = std::find_if(dnaCandidates.begin(), dnaCandidates.end(),
                                  [](const auto &candidate) {
                                    return candidate.text == "DNA";
                                  });
    FCITX_ASSERT(dna != dnaCandidates.end()) << "DNA candidate is missing";
    FCITX_ASSERT(frontend.selectCandidate(static_cast<int>(
        std::distance(dnaCandidates.begin(), dna))));
    FCITX_ASSERT(commits == std::vector<std::string>{"DNA"}) << commits;
    commits.clear();

    press(static_cast<KeySym>(0x0200e100));
    type("jiance");
    const auto chineseCandidates = frontend.getCandidates(0, 256);
    const auto detection =
        std::find_if(chineseCandidates.begin(), chineseCandidates.end(),
                     [](const auto &candidate) {
                       return candidate.text == "检测";
                     });
    FCITX_ASSERT(detection != chineseCandidates.end())
        << "The Chinese word for the learning sequence is missing";
    FCITX_ASSERT(frontend.selectCandidate(static_cast<int>(
        std::distance(chineseCandidates.begin(), detection))));
    FCITX_ASSERT(commits == std::vector<std::string>{"检测"}) << commits;
    commits.clear();

    frontend.resetInputContext();
    press(static_cast<KeySym>(0x0200e101));
    for (const auto letter : std::string("DNA"))
      popupLetter(letter);
    const auto completions = frontend.getCandidates(0, 256);
    FCITX_ASSERT(std::none_of(completions.begin(), completions.end(),
                              [](const auto &candidate) {
                                return candidate.text == "检测";
                              }))
        << "The learned prediction leaked into English completions";
    const auto learnedEnglish =
        std::find_if(completions.begin(), completions.end(),
                     [](const auto &candidate) {
                       return candidate.text == "DNA";
                     });
    FCITX_ASSERT(learnedEnglish != completions.end());
    FCITX_ASSERT(frontend.selectCandidate(static_cast<int>(
        std::distance(completions.begin(), learnedEnglish))));
    FCITX_ASSERT(commits == std::vector<std::string>{"DNA"}) << commits;
    const auto predictions = frontend.getCandidates(0, 256);
    FCITX_ASSERT(ic->inputPanel().clientPreedit().empty());
    std::string predictionNames;
    for (const auto &candidate : predictions) {
      predictionNames += candidate.text + "|";
    }
    FCITX_ASSERT(std::count_if(predictions.begin(), predictions.end(),
                              [](const auto &candidate) {
                                return candidate.text == "检测";
                              }) == 1)
        << "The learned Chinese next-word prediction was not offered after DNA: "
        << predictionNames;
    commits.clear();
    delete ic;
    instance.exit();
  });
  instance.exec();
  std::filesystem::remove_all(phraseRoot);
  return 0;
}
