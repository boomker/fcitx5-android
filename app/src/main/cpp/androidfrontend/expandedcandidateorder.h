/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#ifndef FCITX5_ANDROID_EXPANDEDCANDIDATEORDER_H
#define FCITX5_ANDROID_EXPANDEDCANDIDATEORDER_H

#include <algorithm>
#include <span>
#include <vector>

namespace fcitx {

// Return original engine indices, leaving the visible prefix untouched. A
// symbol follows its source when that source is in the expanded window.
inline std::vector<int>
expandedCandidateOrder(std::span<const int> sources, int firstHiddenIndex,
                       std::span<const int> excludedIndices = {}) {
    const int total = static_cast<int>(sources.size());
    const int start = std::clamp(firstHiddenIndex, 0, total);
    std::vector<bool> excluded(total, false);
    for (const int index : excludedIndices) {
        if (index >= 0 && index < total) excluded[index] = true;
    }

    std::vector<int> sourceIndices(total, -1);
    std::vector<std::vector<int>> attached(total), visibleGroups(total);
    for (int index = start; index < total; ++index) {
        const int source = sources[index];
        // Sources precede their children and are ordinary candidates. Invalid
        // associations retain their engine order instead of losing an item.
        if (source < 0 || source >= index || sources[source] >= 0) continue;
        sourceIndices[index] = source;
        if (source >= start && !excluded[source]) {
            attached[source].push_back(index);
        } else {
            visibleGroups[source].push_back(index);
        }
    }

    std::vector<int> order;
    order.reserve(total - start);
    std::vector<bool> emitted(total, false);
    for (int index = start; index < total; ++index) {
        const int source = sourceIndices[index];
        if (source >= start && !excluded[source]) continue;
        if (source >= 0) {
            // Keep each deferred source's complete run at its first symbol,
            // including when another source or ordinary candidate separates it.
            if (!emitted[source]) {
                order.insert(order.end(), visibleGroups[source].begin(),
                             visibleGroups[source].end());
                emitted[source] = true;
            }
            continue;
        }
        order.push_back(index);
        order.insert(order.end(), attached[index].begin(), attached[index].end());
    }
    return order;
}

} // namespace fcitx

#endif // FCITX5_ANDROID_EXPANDEDCANDIDATEORDER_H
