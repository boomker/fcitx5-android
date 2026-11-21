/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#include <cstdlib>
#include <iostream>
#include <numeric>
#include <vector>

#include "../../../main/cpp/androidfrontend/expandedcandidateorder.h"

static void require(bool condition) {
    if (!condition) {
        std::cerr << "Expanded candidate order regression failed\n";
        std::abort();
    }
}

static void expectOrder(const std::vector<int> &sources, int firstHiddenIndex,
                        const std::vector<int> &expected, const char *label) {
    const auto actual = fcitx::expandedCandidateOrder(sources, firstHiddenIndex);
    if (actual == expected) return;
    std::cerr << label << "\n  actual: ";
    for (const int index : actual) std::cerr << index << ' ';
    std::cerr << "\n  expected: ";
    for (const int index : expected) std::cerr << index << ' ';
    std::cerr << '\n';
    std::abort();
}

int main() {
    using fcitx::expandedCandidateOrder;
    // 差哦不 茶品 差哦被 差 cha 👎 叉 茶 ❌ ❎ 🍵 iamb 查
    const std::vector<int> screenshot = {
        -1, -1, -1, -1, -1, 4, -1, -1, 6, 6, 7, -1, -1};
    require(expandedCandidateOrder(screenshot, 4) ==
            std::vector<int>({4, 5, 6, 8, 9, 7, 10, 11, 12}));
    // Visible X/tea/X choices remain one complete X group at its first slot,
    // while the hidden source still keeps its own child immediately after it.
    const std::vector<int> mixed = {-1, -1, -1, 0, 1, 0, -1, 2};
    expectOrder(mixed, 2, {2, 7, 3, 5, 4, 6}, "Interleaved visible sources must stay contiguous");
    expectOrder({-1, -1, 0, -1, 0, 1, -1}, 2, {2, 4, 3, 5, 6},
                "An ordinary candidate cannot split a visible-source symbol group");
    expectOrder(screenshot, 8, {8, 9, 10, 11, 12},
                "A second X choice cannot be moved behind tea");
    const std::vector<int> excluded = {6};
    require(expandedCandidateOrder(screenshot, 4, excluded) ==
            std::vector<int>({4, 5, 6, 7, 10, 8, 9, 11, 12}));
    require(expandedCandidateOrder({}, 0).empty());
    require(expandedCandidateOrder(screenshot, 100).empty());
    require(expandedCandidateOrder(screenshot, -1) ==
            expandedCandidateOrder(screenshot, 0));
    // Self, forward, missing and nested sources are not usable associations.
    require(expandedCandidateOrder(std::vector<int>{0, 3, 99, -1, 1}, 0) ==
            std::vector<int>({0, 1, 2, 3, 4}));

    // A long detached run crosses ordinary page sizes. Slice the complete
    // permutation so roots and symbols cannot be separated by a fetch boundary.
    std::vector<int> longRun(75, -1);
    for (int i = 5; i < 70; ++i) longRun[i] = 2;
    const auto order = expandedCandidateOrder(longRun, 2);
    require(order.front() == 2);
    require(order[1] == 5 && order[55] == 59 && order[56] == 60);
    std::vector<int> paged;
    for (size_t offset = 0; offset < order.size(); offset += 7) {
        const auto end = std::min(offset + 7, order.size());
        paged.insert(paged.end(), order.begin() + offset, order.begin() + end);
    }
    require(paged == order);

    for (size_t total = 0; total < 80; ++total) {
        std::vector<int> sources(total, -1);
        for (size_t i = 3; i < total; ++i) {
            if (i % 3 != 0) sources[i] = static_cast<int>(i % 3);
        }
        for (int start = 0; start <= static_cast<int>(total) + 1; ++start) {
            auto actual = expandedCandidateOrder(sources, start);
            std::sort(actual.begin(), actual.end());
            std::vector<int> expected(total - std::min<size_t>(start, total));
            std::iota(expected.begin(), expected.end(), std::min<size_t>(start, total));
            require(actual == expected);
        }
    }
    return 0;
}
