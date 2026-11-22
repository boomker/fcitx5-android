/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
#include <fcitx/addonfactory.h>
#include <fcitx/addonmanager.h>
#include <fcitx/candidatelist.h>
#include <fcitx/focusgroup.h>
#include <fcitx/inputcontextmanager.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/inputmethodmanager.h>
#include <fcitx/inputpanel.h>
#include <fcitx/statusarea.h>
#include <fcitx-utils/event.h>

#include "androidfrontend.h"
#include "expandedcandidateorder.h"
#include <atomic>
#include <stdexcept>
#include <string_view>
#include <unordered_map>
#include <unordered_set>

FCITX_ADDON_DECLARE_FUNCTION(PinyinEngine, candidateViewportPolicyEnabled,
                             bool(fcitx::InputContext *))
FCITX_ADDON_DECLARE_FUNCTION(PinyinEngine, candidateViewportGeneration,
                             std::uint64_t(fcitx::InputContext *))
FCITX_ADDON_DECLARE_FUNCTION(PinyinEngine, setCandidateViewport,
                             bool(fcitx::InputContext *, int,
                                  const std::vector<const fcitx::CandidateWord *> &,
                                  const fcitx::CandidateWord *, bool))

namespace fcitx {

namespace {
std::atomic<std::int64_t> candidateRevisionSequence{0};

class CandidateSelectionGuard {
public:
    explicit CandidateSelectionGuard(bool &selectingCandidate)
            : selectingCandidate_(selectingCandidate) {
        selectingCandidate_ = true;
    }

    ~CandidateSelectionGuard() {
        selectingCandidate_ = false;
    }

private:
    bool &selectingCandidate_;
};

// Sets a bool flag for the lifetime of the guard, restoring the previous value.
class ScopedFlag {
public:
    ScopedFlag(bool &flag, bool value) : flag_(flag), previous_(flag) {
        flag_ = value;
    }

    ~ScopedFlag() {
        flag_ = previous_;
    }

private:
    bool &flag_;
    bool previous_;
};

bool isReturnKey(const Key &key) {
    switch (key.sym()) {
    case FcitxKey_Return:
    case FcitxKey_KP_Enter:
    case FcitxKey_ISO_Enter:
        return true;
    default:
        return false;
    }
}
} // namespace

class AndroidInputContext : public InputContextV2 {
    std::int64_t candidateRevision_ = 0;
    std::weak_ptr<CandidateList> revisionCandidateList_;
    std::uint64_t viewportGeneration_ = 0;
    std::weak_ptr<CandidateList> viewportCandidateList_;
    int viewportCandidateTotal_ = -1;

public:
    AndroidInputContext(AndroidFrontend *frontend,
                        InputContextManager &inputContextManager,
                        int uid,
                        const std::string &pkgName)
            : InputContextV2(inputContextManager, pkgName),
              frontend_(frontend),
              uid_(uid) {
        created();
    }

    ~AndroidInputContext() override {
        frontend_->notifyDeletingInputContext(this);
        frontend_->releaseInputContext(uid_);
        destroy();
    }

    [[nodiscard]] const char *frontend() const override { return "androidfrontend"; }

    void commitStringImpl(const std::string &text) override {
        // A pending client preedit means this commit replaces the composition shown
        // in the editor, which is what the Kotlin layer treats as "from composition".
        frontend_->commitString(text, -1, !inputPanel().clientPreedit().empty());
    }

    void commitStringWithCursorImpl(const std::string &text, size_t cursor) override {
        frontend_->commitString(text, static_cast<int>(cursor),
                                !inputPanel().clientPreedit().empty());
    }

    void forwardKeyImpl(const ForwardKeyEvent &key) override {
        frontend_->forwardKey(key.rawKey(), key.isRelease());
    }

    void deleteSurroundingTextImpl(int offset, unsigned int size) override {
        const int before = -offset;
        const int after = offset + static_cast<int>(size);
        if (before < 0 || after < 0) {
            FCITX_WARN() << "Invalid deleteSurrounding request: offset=" << offset << ", size="
                         << size;
            return;
        }
        frontend_->deleteSurrounding(before, after);
    }

    void updatePreeditImpl() override {
        frontend_->updateClientPreedit(filterText(inputPanel().clientPreedit()));
    }

    void updateInputPanel() {
        const InputPanel &ip = inputPanel();
        if (ip.empty()) {
          frontend_->updateInputPanel(/*preedit=*/{},
                                      /*auxUp=*/ip.overlayMessage(),
                                      /*auxDown=*/{},
                                      /*tabs=*/{});
          return;
        }
        std::vector<CandidateActionEntity> tabs;
        auto list = ip.candidateList();
        if (list) {
            auto tabbed = ip.candidateList()->toTabbed();
            if (tabbed) {
                auto tabActions = tabbed->tabActions();
                for (const auto &a: tabActions) {
                    tabs.emplace_back(a);
                }
            }
        }
        frontend_->updateInputPanel(
                filterText(ip.preedit()),
                filterText(ip.auxUp()),
                filterText(ip.auxDown()),
                tabs
        );
    }

    void updateCandidatesBulk() {
        candidateRevision_ = ++candidateRevisionSequence;
        revisionCandidateList_ = inputPanel().candidateList();
        viewportGeneration_ = currentViewportGeneration().value_or(0);
        std::vector<CandidateEntity> candidates;
        int total = 0;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const auto &bulk = list->toBulk();
            if (bulk) {
                total = bulk->totalSize();
                // limit candidate count to 16 (for paging)
                const int limit = total < 0 ? 16 : std::min(total, 16);
                for (int i = 0; i < limit; i++) {
                    try {
                        // maybe unnecessary; I don't see anywhere using `CandidateWord::setPlaceHolder`
                        // if (candidate.isPlaceHolder()) continue;
                        candidates.emplace_back(candidateEntity(bulk->candidateFromAll(i)));
                    } catch (const std::invalid_argument &e) {
                        FCITX_WARN() << "updateCandidatesBulk(): " << e.what();
                        total = static_cast<int>(candidates.size());
                        break;
                    }
                }
            } else {
                total = list->size();
                for (int i = 0; i < total; i++) {
                    candidates.emplace_back(candidateEntity(list->candidate(i)));
                }
            }
        }
        frontend_->updateCandidateList(candidates, total);
    }

    void updateCandidatesPaged() {
        candidateRevision_ = ++candidateRevisionSequence;
        revisionCandidateList_ = inputPanel().candidateList();
        viewportGeneration_ = currentViewportGeneration().value_or(0);
        const auto &list = inputPanel().candidateList();
        if (!list) {
            frontend_->updatePagedCandidate(PagedCandidateEntity::Empty);
            return;
        }
        int cursorIndex = list->cursorIndex();
        CandidateLayoutHint layoutHint = list->layoutHint();
        bool hasPrev = false;
        bool hasNext = false;
        const auto &pageable = list->toPageable();
        if (pageable) {
            hasPrev = pageable->hasPrev();
            hasNext = pageable->hasNext();
        }
        int size = list->size();
        std::unordered_map<const CandidateWord *, int> nativeIndices;
        if (usesCandidateViewportPolicy()) {
            const auto *bulk = list->toBulk();
            const int total = bulk ? bulk->totalSize() : -1;
            for (int i = 0; i < total; ++i) {
                nativeIndices.emplace(&bulk->candidateFromAll(i), i);
            }
        }
        std::vector<CandidateEntity> candidates;
        candidates.reserve(size);
        for (int i = 0; i < size; i++) {
            const auto &word = list->candidate(i);
            auto candidate = candidateEntityWithLabel(list->label(i), word);
            const auto index = nativeIndices.find(&word);
            if (index != nativeIndices.end()) candidate.nativeIndex = index->second;
            candidates.push_back(std::move(candidate));
        }
        PagedCandidateEntity paged(candidates, cursorIndex, layoutHint, hasPrev, hasNext);
        frontend_->updatePagedCandidate(paged);
    }

    bool selectCandidateBulk(int idx) {
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return false;
        }
        const auto &bulk = list->toBulk();
        try {
            if (bulk) {
                bulk->candidateFromAll(idx).select(this);
            } else {
                list->candidate(idx).select(this);
            }
        } catch (const std::invalid_argument &e) {
            FCITX_WARN() << "selectCandidate index out of range";
            return false;
        }
        return true;
    }

    bool selectCandidatePaged(int idx) {
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return false;
        }
        try {
            if (idx >= list->size()) {
                const auto &bulk = list->toBulk();
                if (!bulk) {
                    FCITX_WARN() << "selectCandidate index out of range";
                    return false;
                }
                bulk->candidateFromAll(idx).select(this);
            } else {
                list->candidate(idx).select(this);
            }
        } catch (const std::invalid_argument &e) {
            FCITX_WARN() << "selectCandidate index out of range";
            return false;
        }
        return true;
    }

    std::vector<CandidateEntity> getCandidates(const int offset, const int limit) {
        std::vector<CandidateEntity> candidates;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const int last = offset + limit;
            const auto &bulk = list->toBulk();
            if (bulk) {
                const int totalSize = bulk->totalSize();
                const int end = totalSize < 0 ? last : std::min(totalSize, last);
                for (int i = offset; i < end; i++) {
                    try {
                        auto candidate = candidateEntity(bulk->candidateFromAll(i));
                        candidate.nativeIndex = i;
                        candidates.push_back(std::move(candidate));
                    } catch (const std::invalid_argument &e) {
                        break;
                    }
                }
            } else {
                const int end = std::min(list->size(), last);
                for (int i = offset; i < end; i++) {
                    auto candidate = candidateEntityWithLabel(list->label(i), list->candidate(i));
                    candidate.nativeIndex = i;
                    candidates.push_back(std::move(candidate));
                }
            }
        }
        return candidates;
    }

    AddonInstance *candidateViewportAddon() {
        const auto *entry = frontend_->instance()->inputMethodEntry(this);
        if (!entry || entry->addon() != "pinyin") return nullptr;
        return frontend_->instance()->addonManager().addon("pinyin", true);
    }

    bool usesCandidateViewportPolicy() {
        auto *addon = candidateViewportAddon();
        if (!addon) return false;
        try {
            return addon->call<IPinyinEngine::candidateViewportPolicyEnabled>(this) &&
                   currentViewportGeneration().has_value();
        } catch (const std::runtime_error &error) {
            // Older optional Pinyin addons do not expose this presentation API.
            if (std::string_view(error.what()) == IPinyinEngine::candidateViewportPolicyEnabled::Name::data()) {
                return false;
            }
            throw;
        }
    }

    std::optional<std::uint64_t> currentViewportGeneration() {
        auto *addon = candidateViewportAddon();
        if (!addon) return std::nullopt;
        try {
            return addon->call<IPinyinEngine::candidateViewportGeneration>(this);
        } catch (const std::runtime_error &error) {
            if (std::string_view(error.what()) == IPinyinEngine::candidateViewportGeneration::Name::data()) {
                return std::nullopt;
            }
            throw;
        }
    }

    void synchronizeCandidateRevision() {
        const auto list = inputPanel().candidateList();
        const auto generation = currentViewportGeneration().value_or(0);
        if (candidateRevision_ == 0 || revisionCandidateList_.lock() != list ||
            viewportGeneration_ != generation) {
            candidateRevision_ = ++candidateRevisionSequence;
            revisionCandidateList_ = list;
            viewportGeneration_ = generation;
        }
    }

    CandidateViewportEntity getCandidateViewportSnapshot(int limit) {
        CandidateViewportEntity snapshot;
        synchronizeCandidateRevision();
        const auto list = inputPanel().candidateList();
        snapshot.revision = candidateRevision_;
        viewportCandidateList_ = list;
        viewportCandidateTotal_ = -1;
        if (!list || limit <= 0) return snapshot;
        const auto *bulk = list->toBulk();
        snapshot.total = bulk ? bulk->totalSize() : list->size();
        viewportCandidateTotal_ = snapshot.total;
        snapshot.foldingEnabled = bulk && snapshot.total >= 0 && usesCandidateViewportPolicy();
        if (!snapshot.foldingEnabled) {
            snapshot.candidates = getCandidates(0, limit);
            for (size_t i = 0; i < snapshot.candidates.size(); ++i) {
                snapshot.candidates[i].nativeIndex = static_cast<int>(i);
            }
            return snapshot;
        }

        std::vector<const CandidateWord *> words;
        std::unordered_map<const CandidateWord *, int> indices;
        words.reserve(snapshot.total);
        for (int i = 0; i < snapshot.total; ++i) {
            const auto *word = &bulk->candidateFromAll(i);
            words.push_back(word);
            indices.emplace(word, i);
        }
        std::unordered_map<const CandidateWord *, int> sources;
        std::unordered_map<const CandidateWord *, int> counts;
        int orphanSource = snapshot.total;
        const auto *actionable = list->toActionable();
        for (const auto *word : words) {
            const auto *source = word->sourceCandidate();
            if (!source) continue;
            if (!sources.contains(source)) {
                const auto index = indices.find(source);
                sources.emplace(source, index == indices.end() ? orphanSource++ : index->second);
            }
            counts[source]++;
            if (actionable) {
                for (const auto &action : actionable->candidateActions(*word)) {
                    if (action.icon() == "folded-symbol") counts[source]++;
                }
            }
        }
        const int end = std::min(limit, snapshot.total);
        snapshot.candidates.reserve(end);
        for (int i = 0; i < end; ++i) {
            auto candidate = candidateEntity(*words[i]);
            candidate.nativeIndex = i;
            if (const auto *source = words[i]->sourceCandidate()) {
                candidate.symbolSourceId = sources.at(source);
                candidate.symbolCount = counts.at(source);
            }
            snapshot.candidates.push_back(std::move(candidate));
        }
        // Folded/deferred symbol groups can expand the ordinary prefix. Keep
        // the protected abbreviation fetchable without discarding its native ID.
        for (int i = end; i < snapshot.total; ++i) {
            if (!words[i]->isBestAbbreviation()) continue;
            auto candidate = candidateEntity(*words[i]);
            candidate.nativeIndex = i;
            snapshot.candidates.push_back(std::move(candidate));
            break;
        }
        return snapshot;
    }

    bool setCandidateViewportIfCurrent(std::int64_t revision, int firstPageSize,
                                      const std::vector<int> &firstPageIndices, int firstSymbolIndex) {
        synchronizeCandidateRevision();
        const auto list = inputPanel().candidateList();
        const auto snapshotList = viewportCandidateList_.lock();
        const auto *bulk = list ? list->toBulk() : nullptr;
        auto *addon = candidateViewportAddon();
        if (revision != candidateRevision_ || !snapshotList || snapshotList != list ||
            !bulk || bulk->totalSize() != viewportCandidateTotal_ || !addon ||
            !usesCandidateViewportPolicy()) return false;
        const int total = bulk->totalSize();
        const bool automatic = firstPageSize == 0;
        if (automatic) {
            if (!firstPageIndices.empty() || firstSymbolIndex != -2) return false;
        } else if (firstPageSize < 1 || firstPageSize > total || firstPageIndices.empty() ||
                   firstPageIndices.size() > static_cast<size_t>(firstPageSize) || firstSymbolIndex < -1) {
            return false;
        }
        std::vector<const CandidateWord *> firstPage;
        std::unordered_set<int> seen;
        int symbols = 0;
        for (const int index : firstPageIndices) {
            if (index < 0 || index >= total || !seen.insert(index).second) return false;
            const auto *word = &bulk->candidateFromAll(index);
            firstPage.push_back(word);
            if (word->sourceCandidate()) symbols++;
        }
        const CandidateWord *symbolSource = nullptr;
        if (firstSymbolIndex >= 0) {
            if (firstSymbolIndex >= total || !seen.contains(firstSymbolIndex) || symbols != 1) return false;
            symbolSource = bulk->candidateFromAll(firstSymbolIndex).sourceCandidate();
            if (!symbolSource) return false;
        } else if (!automatic && symbols != 0) {
            return false;
        }
        const bool accepted = addon->call<IPinyinEngine::setCandidateViewport>(
                this, firstPageSize, firstPage, symbolSource, automatic);
        synchronizeCandidateRevision();
        return accepted;
    }

    std::vector<CandidateEntity> getExpandedCandidates(
            int offset, int limit, int firstHiddenIndex,
            const std::vector<int> &excludedIndices) {
        if (offset < firstHiddenIndex || firstHiddenIndex < 0 || limit <= 0) return {};
        const auto &list = inputPanel().candidateList();
        if (!list) return {};
        const auto *bulk = list->toBulk();
        const int total = bulk ? bulk->totalSize() : -1;
        const auto *entry = frontend_->instance()->inputMethodEntry(this);
        if (total < 0 || !entry || entry->addon() != "pinyin") {
            auto candidates = getCandidates(offset, limit);
            for (size_t i = 0; i < candidates.size(); ++i) {
                candidates[i].nativeIndex = offset + static_cast<int>(i);
            }
            return candidates;
        }

        std::vector<const CandidateWord *> words;
        std::unordered_map<const CandidateWord *, int> indices;
        words.reserve(total);
        indices.reserve(total);
        for (int i = 0; i < total; ++i) {
            const auto *candidate = &bulk->candidateFromAll(i);
            words.push_back(candidate);
            indices.emplace(candidate, i);
        }
        std::vector<int> sources(total, -1);
        for (int i = 0; i < total; ++i) {
            const auto source = indices.find(words[i]->sourceCandidate());
            if (source != indices.end()) sources[i] = source->second;
        }
        const auto order = expandedCandidateOrder(sources, firstHiddenIndex, excludedIndices);
        const size_t start = static_cast<size_t>(offset - firstHiddenIndex);
        std::vector<CandidateEntity> projected;
        projected.reserve(order.size());
        const auto *actionable = list->toActionable();
        for (size_t i = 0; i < order.size(); ++i) {
            const int index = order[i];
            auto candidate = candidateEntity(*words[index]);
            candidate.nativeIndex = index;
            const int source = sources[index];
            std::vector<CandidateAction> foldedActions;
            if (source >= 0 && source < index && sources[source] < 0) {
                const bool sourceExcluded =
                        std::find(excludedIndices.begin(), excludedIndices.end(), source) != excludedIndices.end();
                const bool sourceHidden = source >= firstHiddenIndex && !sourceExcluded;
                // Every item in this presentation is beyond the actual first
                // page, even when its source still fits in the horizontal bar.
                if (candidate.comment == "…" && actionable) {
                    auto actions = actionable->candidateActions(*words[index]);
                    for (auto &action : actions) {
                        if (action.icon() == "folded-symbol") foldedActions.push_back(std::move(action));
                    }
                }
                if (!foldedActions.empty()) candidate.displayComment = "";
                if (sourceHidden && (candidate.comment != "…" || !foldedActions.empty())) {
                    candidate.displayComment = "";
                } else if (!sourceHidden && (candidate.comment.empty() || !foldedActions.empty()) &&
                           (sourceExcluded ||
                            firstHiddenIndex + static_cast<int>(projected.size()) != source + 1)) {
                    candidate.displayComment = "(" + filterString(words[source]->text()) + ")";
                    candidate.spaceBetweenComment = false;
                }
            }
            projected.push_back(candidate);
            for (const auto &action : foldedActions) {
                auto alternative = candidate;
                alternative.text = action.text();
                alternative.selectionActionId = action.id();
                alternative.actionSourceText = candidate.text;
                projected.push_back(std::move(alternative));
            }
        }
        // Folded alternatives add presentation positions, not engine indices.
        // Slice after expanding them so later pages cannot drop tail candidates.
        const size_t end = std::min(projected.size(), start + static_cast<size_t>(limit));
        std::vector<CandidateEntity> candidates;
        if (start >= end) return candidates;
        candidates.reserve(end - start);
        for (size_t i = start; i < end; ++i) candidates.push_back(std::move(projected[i]));
        return candidates;
    }

    std::vector<CandidateActionEntity> getCandidateAction(const int idx) {
        std::vector<CandidateActionEntity> actions;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const auto &actionable = list->toActionable();
            if (actionable) {
                if (usesCandidateViewportPolicy() || idx >= list->size()) {
                    const auto &bulk = list->toBulk();
                    if (bulk) {
                        try {
                            const auto &c = bulk->candidateFromAll(idx);
                            for (const auto &a: actionable->candidateActions(c)) {
                                actions.emplace_back(a);
                            }
                        } catch (const std::exception &e) {
                            FCITX_WARN() << "getCandidateAction(" << idx << ") failed:" << e.what();
                        }
                    }
                } else {
                    const auto &c = list->candidate(idx);
                    for (const auto &a: actionable->candidateActions(c)) {
                        actions.emplace_back(a);
                    }
                }
            }
        }
        return actions;
    }

    void triggerCandidateAction(const int idx, const int actionIdx, const int isOnFirstPage) {
        const auto &list = inputPanel().candidateList();
        if (!list) return;
        const auto &actionable = list->toActionable();
        if (!actionable) return;
        const auto trigger = [&](const CandidateWord &candidate) {
            if (auto *contextual = dynamic_cast<ContextualActionableCandidateList *>(actionable)) {
                CandidateActionContext context;
                if (isOnFirstPage == 0 || isOnFirstPage == 1) {
                    context.isOnFirstPage = isOnFirstPage == 1;
                }
                contextual->triggerAction(candidate, actionIdx, context);
            } else {
                actionable->triggerAction(candidate, actionIdx);
            }
        };
        if (usesCandidateViewportPolicy() || idx >= list->size()) {
            const auto &bulk = list->toBulk();
            if (bulk) {
                try {
                    const auto &c = bulk->candidateFromAll(idx);
                    trigger(c);
                } catch (const std::exception &e) {
                    FCITX_WARN() << "triggerCandidateAction(" << idx << ") failed:" << e.what();
                }
            }
        } else {
            const auto &c = list->candidate(idx);
            trigger(c);
        }
    }

    void triggerTabAction(const int idx) {
        const auto &list = inputPanel().candidateList();
        if (!list) return;
        const auto &tabbed = list->toTabbed();
        if (!tabbed) return;
        try {
            tabbed->triggerTabAction(idx);
        } catch (const std::exception &e) {
            FCITX_WARN() << "triggerTabAction(" << idx << ") failed:" << e.what();
        }
    }

    void offsetCandidatePage(int delta) {
        if (delta == 0) {
            return;
        }
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return;
        }
        const auto &pageable = list->toPageable();
        if (!pageable) {
            return;
        }
        if (delta > 0 && pageable->hasNext()) {
            pageable->next();
            updateUserInterface(UserInterfaceComponent::InputPanel);
        } else if (delta < 0 && pageable->hasPrev()) {
            pageable->prev();
            updateUserInterface(UserInterfaceComponent::InputPanel);
        }
    }

private:
    AndroidFrontend *frontend_;
    int uid_;

    inline Text filterText(const Text &orig) {
        return frontend_->instance()->outputFilter(this, orig);
    }

    inline std::string filterString(const Text &orig) {
        return filterText(orig).toString();
    }

    CandidateEntity candidateEntity(const CandidateWord &candidate) {
        auto entity = CandidateEntity("", filterString(candidate.text()),
                                      filterString(candidate.comment()), candidate.spaceBetweenComment(), candidate.isFallback(), candidate.isDeferredFallback());
        entity.isAbbreviation = candidate.isAbbreviation();
        entity.isBestAbbreviation = candidate.isBestAbbreviation();
        return entity;
    }

    CandidateEntity candidateEntityWithLabel(const Text &label, const CandidateWord &candidate) {
        auto entity = candidateEntity(candidate);
        entity.label = filterString(label);
        return entity;
    }
};

AndroidFrontend::AndroidFrontend(Instance *instance)
        : instance_(instance),
          focusGroup_("android", instance->inputContextManager()),
          activeIC_(nullptr),
          icCache_(),
          eventHandlers_(),
          pagingMode_(0),
          selectingCandidate_(false),
          committingViaReturn_(false) {
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextInputMethodActivated,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputMethodActivatedEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                imChangeCallback(makeInputMethodStatus(activeIC_));
            }
    ));
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextSwitchInputMethod,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputContextSwitchInputMethodEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                const auto &reason = static_cast<std::underlying_type<InputMethodSwitchedReason>::type>(e.reason());
                const std::string &oldIM = e.oldInputMethod();
                switchInputMethodCallback(reason, oldIM);
            }
    ));
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextFlushUI,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputContextFlushUIEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                switch (e.component()) {
                    case UserInterfaceComponent::InputPanel: {
                        activeIC_->updateInputPanel();
                        if (pagingMode_ == 0) {
                            activeIC_->updateCandidatesBulk();
                        } else {
                            activeIC_->updateCandidatesPaged();
                        }
                        break;
                    }
                    case UserInterfaceComponent::StatusArea: {
                        statusAreaUpdateCallback(makeStatusAreaActions(activeIC_), makeInputMethodStatus(activeIC_));
                        break;
                    }
                }
            }
    ));
}

void AndroidFrontend::keyEvent(const Key &key, bool isRelease, const int timestamp) {
    if (!activeIC_) return;
    const auto sym = key.sym();
    const bool compactKey = (sym >= 0x0200e000 && sym <= 0x0200e011) ||
                            sym == 0x0200e100 || sym == 0x0200e101 ||
                            (sym >= 0x0200e200 && sym <= 0x0200e2ff) ||
                            (sym >= 0x0200e300 && sym <= 0x0200e31a);
    if (compactKey) {
        // Check the actual native IM, since Kotlin's IM update can be queued
        // behind a key. Protocol keys never fall through as application input.
        const auto *entry = instance_->inputMethodEntry(activeIC_);
        if (!key.isVirtual() || !entry || entry->addon() != "pinyin" ||
            (entry->uniqueName() != "shuangpin" &&
             entry->uniqueName().compare(0, 10, "shuangpin-") != 0)) {
            return;
        }
        if (sym >= 0x0200e200 && sym <= 0x0200e2ff) {
            // Configuration must reach Pinyin even while a temporary addon
            // mode owns key routing. It only stores the desired partition;
            // a later mode/group event activates it through ordinary routing.
            if (auto *engine = instance_->inputMethodEngine(activeIC_)) {
                KeyEvent configuration(activeIC_, key, isRelease);
                engine->keyEvent(*entry, configuration);
            }
            return;
        }
    }
    KeyEvent keyEvent(activeIC_, key, isRelease);
    // A Return key (optionally with Ctrl/Shift) can make the active engine commit
    // the preedit as-is; flag commits emitted during that key so the Kotlin layer
    // pads Chinese/English just like it does for a candidate selection.
    ScopedFlag returnGuard(committingViaReturn_, !isRelease && isReturnKey(key));
    activeIC_->keyEvent(keyEvent);
    if (!keyEvent.accepted() && !compactKey) {
        auto sym = key.sym();
        keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, timestamp);
    }
}

void AndroidFrontend::forwardKey(const Key &key, bool isRelease) {
    auto sym = key.sym();
    keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, -1);
}

void AndroidFrontend::commitString(const std::string &str, const int cursor,
                                   const bool replacesPendingPreedit) {
    // Whether to pad the commit with spaces is decided in the Kotlin layer, which
    // owns the "insert space between Chinese and English" preference. Here we only
    // report whether this commit finalizes the composition — a candidate selection
    // or a Return-committed preedit — i.e. the cases that layer should pad.
    // Picking a candidate with a number key on a hardware keyboard is handled inside
    // the engine and sets neither flag below, but the commit still replaces the
    // client preedit in the editor, which the input context reports explicitly.
    commitStringCallback(str, cursor,
                         selectingCandidate_ || committingViaReturn_ ||
                             replacesPendingPreedit);
}

void AndroidFrontend::updateCandidateList(const std::vector<CandidateEntity> &candidates, const int size) {
    candidateListCallback(candidates, size);
}

void AndroidFrontend::updateClientPreedit(const Text &clientPreedit) {
    preeditCallback(clientPreedit);
}

void AndroidFrontend::updateInputPanel(const Text &preedit, const Text &auxUp, const Text &auxDown, const std::vector<CandidateActionEntity> &tabs) {
    inputPanelCallback(preedit, auxUp, auxDown, tabs);
}

void AndroidFrontend::releaseInputContext(const int uid) {
    icCache_.release(uid);
}

void AndroidFrontend::notifyDeletingInputContext(AndroidInputContext *ic) {
    if (activeIC_ != ic) {
        return;
    }
    activeIC_ = nullptr;
}

bool AndroidFrontend::selectCandidate(int idx) {
    if (!activeIC_) return false;
    CandidateSelectionGuard guard(selectingCandidate_);
    if (pagingMode_ && !activeIC_->usesCandidateViewportPolicy()) {
        return activeIC_->selectCandidatePaged(idx);
    } else {
        return activeIC_->selectCandidateBulk(idx);
    }
}

bool AndroidFrontend::setCandidateCursor(int idx) {
    if (!activeIC_ || idx < 0) return false;
    const auto &list = activeIC_->inputPanel().candidateList();
    if (!list) return false;
    try {
        // Normalized viewports use bulk identities even for a sparse page.
        if (pagingMode_ && !activeIC_->usesCandidateViewportPolicy() && idx < list->size()) {
            auto *cursor = list->toCursorModifiable();
            if (!cursor) return false;
            cursor->setCursorIndex(idx);
        } else {
            auto *bulk = list->toBulk();
            auto *cursor = list->toBulkCursor();
            if (!bulk || !cursor || (bulk->totalSize() >= 0 && idx >= bulk->totalSize())) return false;
            bulk->candidateFromAll(idx);
            cursor->setGlobalCursorIndex(idx);
        }
    } catch (const std::invalid_argument &) {
        return false;
    }
    // The following key consumes this cursor. Publishing an intermediate UI
    // update would reset Android's local highlight before that key commits it.
    return true;
}

std::vector<CandidateActionEntity> AndroidFrontend::getCandidateActions(const int idx) {
    if (!activeIC_) return {};
    return activeIC_->getCandidateAction(idx);
}

void AndroidFrontend::triggerCandidateAction(const int idx, const int actionIdx, const int isOnFirstPage) {
    if (!activeIC_) return;
    activeIC_->triggerCandidateAction(idx, actionIdx, isOnFirstPage);
}

void AndroidFrontend::triggerTabAction(const int idx) {
    if (!activeIC_) return;
    activeIC_->triggerTabAction(idx);
}

bool AndroidFrontend::isInputPanelEmpty() {
    if (!activeIC_) return true;
    return activeIC_->inputPanel().empty();
}

void AndroidFrontend::resetInputContext() {
    if (!activeIC_) return;
    activeIC_->reset();
}

void AndroidFrontend::repositionCursor(int position) {
    if (!activeIC_) return;
    InvokeActionEvent event(InvokeActionEvent::Action::LeftClick, position, activeIC_);
    activeIC_->invokeAction(event);
}

void AndroidFrontend::focusInputContext(bool focus) {
    if (!activeIC_) return;
    if (focus) {
        activeIC_->focusIn();
    } else {
        activeIC_->focusOut();
    }
}

void AndroidFrontend::activateInputContext(const int uid, const std::string &pkgName) {
    auto *ptr = icCache_.find(uid);
    if (ptr) {
        activeIC_ = dynamic_cast<AndroidInputContext *>(ptr->get());
    } else {
        auto *ic = new AndroidInputContext(this, instance_->inputContextManager(), uid, pkgName);
        activeIC_ = ic;
        icCache_.insert(uid, ic);
        ic->setFocusGroup(&focusGroup_);
    }
}

InputContext *AndroidFrontend::activeInputContext() const {
    return activeIC_;
}

void AndroidFrontend::deactivateInputContext(const int uid) {
    auto *ptr = icCache_.find(uid);
    if (!ptr) return;
    auto *ic = ptr->get();
    // Only release the context that is being deactivated. A late deactivation
    // for a context that is no longer active (an IME window unbinding after
    // another context was activated) must not drop the current context's focus
    // or leave the frontend without an active context, which discards keys.
    if (focusGroup_.focusedInputContext() == ic) {
        focusGroup_.setFocusedInputContext(nullptr);
    }
    if (activeIC_ == ic) {
        activeIC_ = nullptr;
    }
}

void AndroidFrontend::setCapabilityFlags(uint64_t flag) {
    if (!activeIC_) return;
    activeIC_->setCapabilityFlags(CapabilityFlags(flag));
}

void AndroidFrontend::setCandidateListCallback(const CandidateListCallback &callback) {
    candidateListCallback = callback;
}

std::vector<CandidateEntity> AndroidFrontend::getCandidates(const int offset, const int limit) {
    if (!activeIC_) return {};
    return activeIC_->getCandidates(offset, limit);
}

std::vector<CandidateEntity> AndroidFrontend::getExpandedCandidates(
        int offset, int limit, int firstHiddenIndex,
        const std::vector<int> &excludedIndices) {
    if (!activeIC_) return {};
    return activeIC_->getExpandedCandidates(offset, limit, firstHiddenIndex, excludedIndices);
}

CandidateViewportEntity AndroidFrontend::getCandidateViewportSnapshot(int limit) {
    if (!activeIC_) return {};
    return activeIC_->getCandidateViewportSnapshot(limit);
}

bool AndroidFrontend::setCandidateViewportIfCurrent(
        std::int64_t revision, int firstPageSize, const std::vector<int> &firstPageIndices, int firstSymbolIndex) {
    if (!activeIC_) return false;
    return activeIC_->setCandidateViewportIfCurrent(revision, firstPageSize, firstPageIndices, firstSymbolIndex);
}

void AndroidFrontend::deleteSurrounding(const int before, const int after) {
    deleteSurroundingCallback(before, after);
}

void AndroidFrontend::showToast(const std::string &s) {
    toastCallback(s);
}

void AndroidFrontend::setCandidatePagingMode(const int mode) {
    pagingMode_ = mode;
    if (!activeIC_) {
        return;
    }
    if (mode == 0) {
        activeIC_->updateCandidatesBulk();
    } else {
        activeIC_->updateCandidatesPaged();
    }
}

void AndroidFrontend::updatePagedCandidate(const PagedCandidateEntity &paged) {
    pagedCandidateCallback(paged);
}

void AndroidFrontend::offsetCandidatePage(int delta) {
    if (!activeIC_) return;
    activeIC_->offsetCandidatePage(delta);
}

void AndroidFrontend::triggerCandidateListTabAction(int id) {
    if (!activeIC_) return;
    activeIC_->triggerTabAction(id);
}

void AndroidFrontend::setCommitStringCallback(const CommitStringCallback &callback) {
    commitStringCallback = callback;
}

void AndroidFrontend::setPreeditCallback(const ClientPreeditCallback &callback) {
    preeditCallback = callback;
}

void AndroidFrontend::setInputPanelCallback(const InputPanelCallback &callback) {
    inputPanelCallback = callback;
}

void AndroidFrontend::setKeyEventCallback(const KeyEventCallback &callback) {
    keyEventCallback = callback;
}

void AndroidFrontend::setInputMethodChangeCallback(const InputMethodChangeCallback &callback) {
    imChangeCallback = callback;
}

void AndroidFrontend::setStatusAreaUpdateCallback(const StatusAreaUpdateCallback &callback) {
    statusAreaUpdateCallback = callback;
}

void AndroidFrontend::setDeleteSurroundingCallback(const DeleteSurroundingCallback &callback) {
    deleteSurroundingCallback = callback;
}

void AndroidFrontend::setToastCallback(const ToastCallback &callback) {
    toastCallback = callback;
}

void AndroidFrontend::setPagedCandidateCallback(const PagedCandidateCallback &callback) {
    pagedCandidateCallback = callback;
}

void AndroidFrontend::setSwitchInputMethodCallback(const SwitchInputMethodCallback &callback) {
    switchInputMethodCallback = callback;
}

InputMethodStatus AndroidFrontend::makeInputMethodStatus(InputContext *ic) {
    auto *entry = instance_->inputMethodEntry(ic);
    auto *engine = instance_->inputMethodEngine(ic);
    return {entry, engine, ic};
}

std::vector<ActionEntity> AndroidFrontend::makeStatusAreaActions(fcitx::InputContext *ic) {
    auto actions = std::vector<ActionEntity>();
    for (auto group: {fcitx::StatusGroup::BeforeInputMethod,
                      fcitx::StatusGroup::InputMethod,
                      fcitx::StatusGroup::AfterInputMethod}) {
        for (auto act: ic->statusArea().actions(group)) {
            actions.emplace_back(act, ic);
        }
    }
    return actions;
}

class AndroidFrontendFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new AndroidFrontend(manager->instance());
    }
};

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidFrontendFactory)
