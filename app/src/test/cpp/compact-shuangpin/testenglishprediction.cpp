/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#include <cstdint>
#include <fcitx-utils/log.h>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "englishprediction.h"

namespace {
std::string
dictionary(const std::vector<std::pair<std::string, uint16_t>> &words) {
  std::string bytes = "FSCD0000";
  for (unsigned shift = 0; shift < 32; shift += 8)
    bytes += static_cast<char>(words.size() >> shift);
  for (const auto &[word, frequency] : words) {
    bytes += static_cast<char>(frequency);
    bytes += static_cast<char>(frequency >> 8);
    bytes += word;
    bytes += char(0);
  }
  return bytes;
}

std::vector<std::string>
words(const std::vector<fcitx::EnglishPrediction> &matches) {
  std::vector<std::string> result;
  for (const auto &match : matches)
    result.push_back(match.word);
  return result;
}
} // namespace

int main() {
  fcitx::EnglishPredictor predictor;
  const auto data = dictionary({{"willow", 60000},
                                {"Will", 100},
                                {"well", 200},
                                {"hello", 300},
                                {"he", 1},
                                {"willful", 400}});
  std::istringstream valid(data);
  FCITX_ASSERT(predictor.load(valid));
  // Short and low-frequency words belong to the full dictionary too.
  FCITX_ASSERT(words(predictor.match({"h", "we"}, 10)) ==
               std::vector<std::string>({"he", "hello"}));
  FCITX_ASSERT(words(predictor.match({"h", "we", "l", "l", "io"}, 10)) ==
               std::vector<std::string>({"hello"}));
  FCITX_ASSERT(words(predictor.match({"w", "io", "l", "l"}, 10)) ==
               std::vector<std::string>({"will", "willow", "willful"}));
  FCITX_ASSERT(words(predictor.match({"w", "we", "l", "l"}, 10)) ==
               std::vector<std::string>({"well"}));
  FCITX_ASSERT(words(predictor.match({"w", "i", "l", "l"}, 1)) ==
               std::vector<std::string>({"will"}));
  FCITX_ASSERT(predictor.match({}, 10).empty());
  FCITX_ASSERT(predictor.match({"w", ""}, 10).empty());
  FCITX_ASSERT(predictor.match({"w"}, 0).empty());
  FCITX_ASSERT(predictor.match({"q", "z"}, 10).empty());

  // Failed reloads cannot retain a stale dictionary or partially parsed words.
  std::vector<std::string> invalid = {"",
                                      "FSCD9999",
                                      data.substr(0, 11),
                                      data.substr(0, data.size() - 1),
                                      data + "unexpected trailing data",
                                      dictionary({{"", 12}})};
  auto impossibleCount = dictionary({});
  impossibleCount[8] = char(255);
  invalid.push_back(impossibleCount);
  for (const auto &bytes : invalid) {
    std::istringstream input(bytes);
    FCITX_ASSERT(!predictor.load(input));
    FCITX_ASSERT(predictor.match({"w"}, 10).empty());
    std::istringstream restore(data);
    FCITX_ASSERT(predictor.load(restore));
  }
  return 0;
}
