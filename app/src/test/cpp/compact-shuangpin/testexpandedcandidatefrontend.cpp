/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#include <algorithm>
#include <string>
#include <vector>

#include <fcitx-utils/testing.h>
#include <fcitx-utils/standardpaths.h>

// Build the real frontend in one translation unit, as testandroidfrontend does.
#include "../../../main/cpp/androidfrontend/androidfrontend.cpp"

class TestCandidate : public fcitx::CandidateWord {
public:
    TestCandidate(std::string text, const CandidateWord *source = nullptr,
                  std::string comment = {}) : CandidateWord(fcitx::Text(std::move(text))) {
        setSourceCandidate(source);
        setComment(fcitx::Text(std::move(comment)));
        setSpaceBetweenComment(false);
    }

    void select(fcitx::InputContext *inputContext) const override {
        inputContext->commitString(text().toString());
    }
};

int main() {
    using namespace fcitx;
    const std::string addonBuild = TEST_CHINESE_ADDONS_BINARY_DIR;
    setupTestingEnvironment(addonBuild, {"bin"},
                            {addonBuild + "/test", addonBuild + "/im",
                             addonBuild + "/modules", TEST_CHINESE_ADDONS_SOURCE_DIR "/im",
                             StandardPaths::fcitxPath("pkgdatadir")});
    char arg0[] = "testexpandedcandidatefrontend";
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
        frontend.activateInputContext(24, "expanded-symbol-order");
        auto *ic = frontend.activeInputContext();
        FCITX_ASSERT(ic);
        ic->setCapabilityFlags(CapabilityFlag::Preedit);
        frontend.focusInputContext(true);
        instance.setCurrentInputMethod(ic, "pinyin", true);
        frontend.setCandidatePagingMode(0);
        const std::vector<std::string> texts = {
            "差哦不", "茶品", "差哦被", "差", "cha", "👎", "叉", "茶",
            "❌", "❎", "🍵", "iamb", "查"};
        const std::vector<int> sources = {-1, -1, -1, -1, -1, 4, -1, -1, 6, 6, 7, -1, -1};
        auto list = std::make_unique<CommonCandidateList>();
        std::vector<const CandidateWord *> words;
        for (size_t i = 0; i < texts.size(); ++i) {
            auto candidate = std::make_unique<TestCandidate>(
                    texts[i], sources[i] < 0 ? nullptr : words[sources[i]],
                    sources[i] == 6 ? "(叉)" : "");
            words.push_back(candidate.get());
            list->append(std::move(candidate));
        }
        list->setPageSize(8);
        ic->inputPanel().setCandidateList(std::move(list));

        const std::vector<int> expected = {4, 5, 6, 8, 9, 7, 10, 11, 12};
        const auto expanded = frontend.getExpandedCandidates(4, 64, 4);
        FCITX_ASSERT(expanded.size() == expected.size());
        std::vector<CandidateEntity> pages;
        for (int offset = 4; offset < 13; offset += 2) {
            const auto page = frontend.getExpandedCandidates(offset, 2, 4);
            pages.insert(pages.end(), page.begin(), page.end());
        }
        FCITX_ASSERT(pages.size() == expanded.size());
        for (size_t i = 0; i < expanded.size(); ++i) {
            FCITX_ASSERT(expanded[i].nativeIndex == expected[i]);
            FCITX_ASSERT(expanded[i].text == texts[expected[i]]);
            FCITX_ASSERT(pages[i].nativeIndex == expected[i]);
        }
        FCITX_ASSERT(expanded[3].comment == "(叉)");
        FCITX_ASSERT(expanded[3].displayComment == "");
        const auto visibleSources = frontend.getExpandedCandidates(8, 3, 8);
        FCITX_ASSERT(visibleSources[0].nativeIndex == 8);
        FCITX_ASSERT(visibleSources[1].nativeIndex == 9);
        FCITX_ASSERT(visibleSources[2].nativeIndex == 10);
        FCITX_ASSERT(visibleSources[2].displayComment == "(茶)");
        const auto excluded = frontend.getExpandedCandidates(4, 64, 4, {6});
        const std::vector<int> expectedExcluded = {4, 5, 6, 7, 10, 8, 9, 11, 12};
        for (size_t i = 0; i < excluded.size(); ++i) {
            FCITX_ASSERT(excluded[i].nativeIndex == expectedExcluded[i]);
        }
        // The display permutation and comment override leave native identity
        // and the snapshot checked by long-press actions unchanged.
        const auto native = frontend.getCandidates(0, 64);
        for (size_t i = 0; i < native.size(); ++i) FCITX_ASSERT(native[i].text == texts[i]);
        FCITX_ASSERT(native[8].comment == "(叉)");
        FCITX_ASSERT(!native[8].displayComment);
        std::vector<std::string> commits;
        frontend.setCommitStringCallback([&commits](const std::string &text, int, bool) {
            commits.push_back(text);
        });
        FCITX_ASSERT(frontend.selectCandidate(expanded[3].nativeIndex));
        FCITX_ASSERT(frontend.selectCandidate(expanded[4].nativeIndex));
        FCITX_ASSERT(frontend.selectCandidate(expanded[6].nativeIndex));
        FCITX_ASSERT(commits == std::vector<std::string>({"❌", "❎", "🍵"}));
        FCITX_ASSERT(frontend.getExpandedCandidates(-1, 4, 0).empty());
        FCITX_ASSERT(frontend.getExpandedCandidates(4, 0, 4).empty());

        // Two visible sources have interleaved detached choices, while a
        // third source is hidden. Whole source groups must stay contiguous.
        const std::vector<std::string> mixedTexts = {
            "叉", "茶", "羊", "❌", "🍵", "❎", "查", "🐑"};
        const std::vector<int> mixedSources = {-1, -1, -1, 0, 1, 0, -1, 2};
        auto mixedList = std::make_unique<CommonCandidateList>();
        std::vector<const CandidateWord *> mixedWords;
        for (size_t i = 0; i < mixedTexts.size(); ++i) {
            const int source = mixedSources[i];
            auto candidate = std::make_unique<TestCandidate>(
                    mixedTexts[i], source < 0 ? nullptr : mixedWords[source],
                    source < 0 ? "" : "(" + mixedTexts[source] + ")");
            mixedWords.push_back(candidate.get());
            mixedList->append(std::move(candidate));
        }
        mixedList->setPageSize(2);
        ic->inputPanel().setCandidateList(std::move(mixedList));
        const auto mixedNative = frontend.getCandidates(0, 64);
        const auto mixedExpanded = frontend.getExpandedCandidates(2, 64, 2);
        const std::vector<int> expectedMixed = {2, 7, 3, 5, 4, 6};
        FCITX_ASSERT(mixedExpanded.size() == expectedMixed.size());
        for (size_t i = 0; i < mixedExpanded.size(); ++i) {
            FCITX_ASSERT(mixedExpanded[i].nativeIndex == expectedMixed[i]);
            FCITX_ASSERT(mixedExpanded[i].text == mixedTexts[expectedMixed[i]]);
            FCITX_ASSERT(mixedExpanded[i].selectionActionId < 0);
        }
        FCITX_ASSERT(mixedExpanded[1].displayComment == "");
        FCITX_ASSERT(mixedExpanded[2].text == "❌");
        FCITX_ASSERT(mixedExpanded[3].text == "❎");
        FCITX_ASSERT(mixedExpanded[4].text == "🍵");
        for (const int batchSize : {1, 3}) {
            std::vector<CandidateEntity> mixedPages;
            for (int offset = 2; offset < 2 + static_cast<int>(expectedMixed.size()); offset += batchSize) {
                const auto page = frontend.getExpandedCandidates(offset, batchSize, 2);
                mixedPages.insert(mixedPages.end(), page.begin(), page.end());
            }
            FCITX_ASSERT(mixedPages.size() == mixedExpanded.size());
            for (size_t i = 0; i < mixedPages.size(); ++i) {
                FCITX_ASSERT(mixedPages[i].nativeIndex == mixedExpanded[i].nativeIndex);
                FCITX_ASSERT(mixedPages[i].text == mixedExpanded[i].text);
            }
        }
        const auto mixedNativeAfter = frontend.getCandidates(0, 64);
        FCITX_ASSERT(mixedNativeAfter.size() == mixedNative.size());
        for (size_t i = 0; i < mixedNative.size(); ++i) {
            FCITX_ASSERT(mixedNativeAfter[i].text == mixedNative[i].text);
            FCITX_ASSERT(mixedNativeAfter[i].comment == mixedNative[i].comment);
        }

        // Real pinyin-generated symbols also identify a live source in the
        // same list, not an inferred glyph or the symbol's dictionary index.
        instance.setCurrentInputMethod(ic, "pinyin", true);
        RawConfig symbolConfig;
        symbolConfig.setValueByPath("SymbolsEnabled", "True");
        symbolConfig.setValueByPath("FoldSymbolCandidates", "True");
        symbolConfig.setValueByPath("Prediction", "False");
        instance.inputMethodEngine(ic)->setConfigForInputMethod(
                *instance.inputMethodEntry(ic), symbolConfig);
        frontend.resetInputContext();
        for (const char key : std::string("cha")) {
            frontend.keyEvent(Key(static_cast<KeySym>(key)), false, 0);
        }
        const auto realList = ic->inputPanel().candidateList();
        FCITX_ASSERT(realList && realList->toBulk());
        FCITX_ASSERT(!ic->inputPanel().clientPreedit().empty());
        const auto *bulk = realList->toBulk();
        int symbols = 0;
        for (int i = 0; i < bulk->totalSize(); ++i) {
            const auto *source = bulk->candidateFromAll(i).sourceCandidate();
            if (!source) continue;
            symbols++;
            bool found = false;
            for (int j = 0; j < i; ++j) found = found || &bulk->candidateFromAll(j) == source;
            FCITX_ASSERT(found);
        }
        FCITX_ASSERT(symbols > 0);

        frontend.resetInputContext();
        for (const char key : std::string("kaishi")) {
            frontend.keyEvent(Key(static_cast<KeySym>(key)), false, 0);
        }
        const auto sourceVisible = frontend.getExpandedCandidates(1, 128, 1);
        const auto visibleHead = std::find_if(sourceVisible.begin(), sourceVisible.end(), [](const auto &candidate) {
            return candidate.text == "▶️";
        });
        FCITX_ASSERT(visibleHead != sourceVisible.end());
        FCITX_ASSERT(visibleHead->comment == "…");
        FCITX_ASSERT(visibleHead->displayComment == "");
        const auto actions = frontend.getCandidateActions(visibleHead->nativeIndex);
        FCITX_ASSERT(actions.size() == 1);
        FCITX_ASSERT(actions.front().text == "🔛");
        FCITX_ASSERT(actions.front().icon == "folded-symbol");
        const auto visibleAlternative = std::next(visibleHead);
        FCITX_ASSERT(visibleAlternative != sourceVisible.end());
        FCITX_ASSERT(visibleAlternative->text == "🔛");
        FCITX_ASSERT(visibleAlternative->nativeIndex == visibleHead->nativeIndex);
        FCITX_ASSERT(visibleAlternative->selectionActionId == actions.front().id);
        FCITX_ASSERT(visibleAlternative->actionSourceText == visibleHead->text);
        FCITX_ASSERT(visibleAlternative->displayComment == "");
        const auto rawFolded = frontend.getCandidates(0, 1024);
        FCITX_ASSERT(rawFolded[visibleHead->nativeIndex].comment == "…");
        FCITX_ASSERT(!rawFolded[visibleHead->nativeIndex].displayComment);
        FCITX_ASSERT(rawFolded[visibleHead->nativeIndex].selectionActionId < 0);

        const auto sourceHidden = frontend.getExpandedCandidates(0, 1024, 0);
        const auto hiddenHead = std::find_if(sourceHidden.begin(), sourceHidden.end(), [](const auto &candidate) {
            return candidate.text == "▶️" && candidate.selectionActionId < 0;
        });
        FCITX_ASSERT(hiddenHead != sourceHidden.end());
        FCITX_ASSERT(hiddenHead->displayComment == "");
        const auto alternative = std::next(hiddenHead);
        FCITX_ASSERT(alternative != sourceHidden.end());
        FCITX_ASSERT(alternative->text == "🔛");
        FCITX_ASSERT(alternative->nativeIndex == hiddenHead->nativeIndex);
        FCITX_ASSERT(alternative->selectionActionId == actions.front().id);
        FCITX_ASSERT(alternative->actionSourceText == hiddenHead->text);
        FCITX_ASSERT(alternative->comment == "…");
        FCITX_ASSERT(alternative->displayComment == "");
        const auto originalFolded = frontend.getCandidates(0, 1024);
        FCITX_ASSERT(originalFolded[hiddenHead->nativeIndex].comment == "…");
        FCITX_ASSERT(sourceHidden.size() == originalFolded.size() + 1);
        std::vector<CandidateEntity> foldedPages;
        for (int offset = 0; offset < static_cast<int>(sourceHidden.size()); offset += 2) {
            const auto page = frontend.getExpandedCandidates(offset, 2, 0);
            foldedPages.insert(foldedPages.end(), page.begin(), page.end());
        }
        FCITX_ASSERT(foldedPages.size() == sourceHidden.size());
        for (size_t i = 0; i < sourceHidden.size(); ++i) {
            FCITX_ASSERT(foldedPages[i].nativeIndex == sourceHidden[i].nativeIndex);
            FCITX_ASSERT(foldedPages[i].selectionActionId == sourceHidden[i].selectionActionId);
            FCITX_ASSERT(foldedPages[i].text == sourceHidden[i].text);
        }

        // A visible source can be separated from its folded head by another
        // item. Only expanded display receives the identifying source comment.
        auto *modifiable = ic->inputPanel().candidateList()->toModifiable();
        FCITX_ASSERT(modifiable);
        modifiable->insert(1, std::make_unique<TestCandidate>("中间"));
        const auto detachedNative = frontend.getCandidates(0, 1024);
        const auto detached = frontend.getExpandedCandidates(1, 1024, 1);
        const auto detachedHead = std::find_if(detached.begin(), detached.end(), [](const auto &candidate) {
            return candidate.text == "▶️" && candidate.selectionActionId < 0;
        });
        FCITX_ASSERT(detachedHead != detached.end());
        FCITX_ASSERT(detachedHead->nativeIndex == visibleHead->nativeIndex + 1);
        FCITX_ASSERT(detachedHead->comment == "…");
        const auto sourceComment = "(" + detachedNative.front().text + ")";
        FCITX_ASSERT(detachedHead->displayComment == sourceComment);
        const auto detachedAlternative = std::next(detachedHead);
        FCITX_ASSERT(detachedAlternative != detached.end());
        FCITX_ASSERT(detachedAlternative->text == "🔛");
        FCITX_ASSERT(detachedAlternative->nativeIndex == detachedHead->nativeIndex);
        FCITX_ASSERT(detachedAlternative->selectionActionId == actions.front().id);
        FCITX_ASSERT(detachedAlternative->actionSourceText == detachedHead->text);
        FCITX_ASSERT(detachedAlternative->displayComment == sourceComment);
        const auto detachedNativeAfter = frontend.getCandidates(0, 1024);
        FCITX_ASSERT(detachedNativeAfter.size() == detachedNative.size());
        for (size_t i = 0; i < detachedNative.size(); ++i) {
            FCITX_ASSERT(detachedNativeAfter[i].text == detachedNative[i].text);
            FCITX_ASSERT(detachedNativeAfter[i].comment == detachedNative[i].comment);
        }
        frontend.triggerCandidateAction(detachedAlternative->nativeIndex, detachedAlternative->selectionActionId, 0);
        FCITX_ASSERT(commits.back() == "🔛");

        // The best abbreviation can sit after a learned completion. A short
        // viewport must carry both without changing bulk rank or identity.
        instance.setCurrentInputMethod(ic, "shuangpin", true);
        RawConfig abbreviationConfig;
        abbreviationConfig.setValueByPath("ShuangpinProfile", "Xiaohe");
        abbreviationConfig.setValueByPath("PartialSpLength", "3");
        abbreviationConfig.setValueByPath("AbbreviationConfigMigrated", "True");
        abbreviationConfig.setValueByPath("Learning", "True");
        abbreviationConfig.setValueByPath("KeepCurrentContext", "False");
        abbreviationConfig.setValueByPath("Prediction", "False");
        abbreviationConfig.setValueByPath("SpellEnabled", "False");
        abbreviationConfig.setValueByPath("SymbolsEnabled", "False");
        abbreviationConfig.setValueByPath("ShuangpinInitialFuzzy", "False");
        instance.inputMethodEngine(ic)->setConfigForInputMethod(
            *instance.inputMethodEntry(ic), abbreviationConfig);
        const auto typeAbbreviation = [&]() {
            frontend.resetInputContext();
            for (const char key : std::string("wum"))
                frontend.keyEvent(Key(static_cast<KeySym>(key)), false, 0);
        };
        typeAbbreviation();
        const auto beforeLearning = frontend.getCandidates(0, 1024);
        const auto completion = std::ranges::find(beforeLearning, "雾霾", &CandidateEntity::text);
        FCITX_ASSERT(completion != beforeLearning.end());
        FCITX_ASSERT(frontend.selectCandidate(completion->nativeIndex));
        FCITX_ASSERT(commits.back() == "雾霾");
        typeAbbreviation();
        const auto abbreviationNative = frontend.getCandidates(0, 1024);
        FCITX_ASSERT(abbreviationNative.front().text == "雾霾");
        const auto best = std::ranges::find_if(abbreviationNative, &CandidateEntity::isBestAbbreviation);
        FCITX_ASSERT(best != abbreviationNative.end() && best->nativeIndex > 0);
        const auto abbreviationSnapshot = frontend.getCandidateViewportSnapshot(1);
        FCITX_ASSERT(abbreviationSnapshot.foldingEnabled);
        FCITX_ASSERT(abbreviationSnapshot.candidates.size() == 2);
        FCITX_ASSERT(abbreviationSnapshot.candidates.front().text == "雾霾");
        FCITX_ASSERT(abbreviationSnapshot.candidates.back().nativeIndex == best->nativeIndex);
        FCITX_ASSERT(abbreviationSnapshot.candidates.back().isBestAbbreviation);
        FCITX_ASSERT(frontend.getCandidates(0, 1024)[best->nativeIndex].text == best->text);
        FCITX_ASSERT(frontend.selectCandidate(best->nativeIndex));
        FCITX_ASSERT(commits.back() == best->text);
        delete ic;
        instance.exit();
    });
    instance.exec();
    return 0;
}
