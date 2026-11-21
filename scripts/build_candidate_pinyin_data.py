#!/usr/bin/env python3
"""Build the offline candidate-card phrase readings from a pinned dictionary."""

import argparse
import hashlib
from pathlib import Path

SOURCE_SHA256 = "5d4d86b1337bb2fca95bc5135564a821bfdb40597481ee3e3871bf52d4668622"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="pinyin.txt from mozillazg/phrase-pinyin-data")
    parser.add_argument("output", type=Path, help="path for the generated phrase-pinyin.tsv")
    args = parser.parse_args()

    data = args.source.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != SOURCE_SHA256:
        parser.error(f"{args.source}: SHA-256 {digest}, expected {SOURCE_SHA256}")

    readings = {}
    for line in data.decode("utf-8").splitlines():
        body = line.partition("#")[0].strip()
        phrase, separator, pinyin = body.partition(":")
        syllables = pinyin.split()
        if separator and 2 <= len(phrase) <= 10 and len(phrase) == len(syllables):
            readings.setdefault(phrase, " ".join(syllables))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        for phrase in sorted(readings, key=str.encode):
            output.write(f"{phrase}\t{readings[phrase]}\n")
    print(f"wrote {len(readings)} phrase readings to {args.output}")


if __name__ == "__main__":
    main()
