# libime zh_CN language model tooling

Rebuild the Chinese KenLM language model that the built-in Pinyin / Table
engines use (`zh_CN.lm`) and its prediction trie (`zh_CN.lm.predict`).

## Where the model lives

| Path | Role |
| --- | --- |
| `lib/fcitx5/src/main/cpp/prebuilt/libime/data/zh_CN.lm` | source of truth (submodule `boomker/f5a-prebuilt`) |
| `lib/fcitx5/src/main/cpp/prebuilt/libime/data/zh_CN.lm.predict` | ditto |
| `app/src/main/assets/usr/share/libime/` | build output, copied there by `app/src/main/cpp/CMakeLists.txt` |

Only the submodule copy is tracked; the `app/src/main/assets` one is regenerated
on every build.

## Input

The ARPA that libime itself uses, published by the fcitx-im project:

```sh
curl -LO https://download.fcitx-im.org/data/lm_sc.arpa-20260629.tar.zst
```

`lib/libime/src/main/cpp/libime/data/CMakeLists.txt` pins the version and its
SHA-256; keep them in sync if you bump the ARPA. The archive is 74 MiB and
contains a 187 MiB trigram ARPA (`ngram 1=270714, 2=4789953, 3=2189434`).

## Usage

```sh
# build and inspect (writes to build/libime-lm/ by default)
scripts/libime-lm/build-zh-cn-lm.sh --arpa lm_sc.arpa-20260629.tar.zst --bits 8

# rebuild and drop into the prebuilt submodule
scripts/libime-lm/build-zh-cn-lm.sh --arpa lm_sc.arpa-20260629.tar.zst --bits 8 --install
```

The script builds KenLM's `build_binary` from the vendored sources
(`lib/libime/src/main/cpp/libime/src/libime/core/kenlm`), compiles
`predict_tool.cc` (a standalone reimplementation of libime's
`libime_prediction`, so no libime host build is needed), then produces both
files. Requires cmake, a C++20 compiler, Boost headers and zstd.

## Hard constraints

libime is not a general KenLM loader:

* `StaticLanguageModelFile` hardcodes `lm::ngram::QuantArrayTrieModel`, so the
  model **must** be a quantized array trie (`-q N -a 22 trie`). A plain `trie`
  or `probing` binary is rejected at load time with
  `The binary file was built for ... but the inference code is trying to load ...`.
* `KENLM_MAX_ORDER=3` is set in
  `lib/libime/src/main/cpp/libime/src/libime/core/CMakeLists.txt`, and
  `StateSize` in `languagemodel.h` only fits a trigram state. A 4-gram model
  needs `KENLM_MAX_ORDER=4` plus a larger `StateSize`, and a rebuild.

## Quantization size/quality trade-off

Measured on this repo's ARPA (`build_binary -s -a 22 -q N trie`):

| bits | size | vs. unquantized `\|dlog10 p\|` (mean / p90) | perplexity* |
| --- | --- | --- | --- |
| 4 (previously shipped) | 33.1 MiB | 0.0510 / 0.1157 | 350.47 |
| 8 (**shipped now**) | 38.7 MiB | 0.0037 / 0.0070 | 348.92 |
| unquantized `trie` | 71.6 MiB | — | 348.74 |

\* on 15,273 tokens sampled from the ARPA itself, so it measures quantization
fidelity rather than generalization.

The prediction trie reacts more strongly than perplexity suggests, because
`libime_prediction` filters entries with a hard score threshold: 4-bit rounding
silently dropped 7.3% of them (100,029 entries at `-q 4`, 107,289 at `-q 8`).

## Verifying the shipped model

```sh
# rebuild the APK, then compare what is actually inside it
unzip -p app/build/outputs/apk/release/*.apk \
    assets/usr/share/libime/zh_CN.lm | shasum -a 256
```
