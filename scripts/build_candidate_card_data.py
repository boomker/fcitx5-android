#!/usr/bin/env python3
"""Build a compact candidate-card table from pinned source data."""

from __future__ import annotations

import argparse
import hashlib
import zipfile
from pathlib import Path

VARIANTS = {
    9: "亻", 18: "刂", 61: "忄", 64: "扌", 66: "攵", 85: "氵", 86: "灬",
    87: "爫", 93: "牜", 94: "犭", 96: "王", 113: "礻", 122: "罒罓",
    125: "耂", 130: "月", 140: "艹", 145: "衤", 146: "覀", 162: "辶",
    163: "阝", 170: "阝",
}
EXPECTED = {
    "chaizi": "c341a99fc6a4a4f3169279c451ca55057a22603a5d7a520094378f65e817ad26",
    "radicals": "826f83be25cd18fb8a5015a514704504e1982e840ea14d058bf583e1cc620c83",
}


def lines(path: Path, digest: str | None = None):
    if digest:
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != digest:
            raise SystemExit(f"{path}: SHA-256 {actual}, expected {digest}")
    return path.read_text(encoding="utf-8").splitlines()


def codepoint(field: str) -> int | None:
    if not field.startswith("U+"):
        return None
    try:
        value = int(field[2:], 16)
    except ValueError:
        return None
    return value if 0 <= value <= 0x10FFFF else None


def build(unihan_zip: Path, radicals_path: Path, chaizi_path: Path, orders_path: Path, output: Path):
    radical_of: dict[int, str] = {}
    total_strokes: dict[int, int] = {}
    with zipfile.ZipFile(unihan_zip) as archive, archive.open("Unihan_IRGSources.txt") as raw:
        for line in raw.read().decode("utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) < 3 or fields[1] not in {"kRSUnicode", "kTotalStrokes"}:
                continue
            cp = codepoint(fields[0])
            if cp is None:
                continue
            value = fields[2].split()[0]
            if fields[1] == "kRSUnicode":
                radical_of[cp] = value.split(".", 1)[0]
            else:
                try:
                    count = int(value)
                except ValueError:
                    continue
                if count > 0:
                    total_strokes[cp] = count

    radical_chars: dict[str, str] = {}
    for line in lines(radicals_path, EXPECTED["radicals"]):
        body = line.split("#", 1)[0].strip()
        if not body:
            continue
        fields = [item.strip() for item in body.split(";")]
        if len(fields) == 3:
            cp = codepoint("U+" + fields[2])
            if cp is not None:
                radical_chars[fields[0]] = chr(cp)

    ways: dict[int, list[list[str]]] = {}
    for line in lines(chaizi_path, EXPECTED["chaizi"]):
        fields = line.split("\t")
        char = fields[0].strip()
        if len(char) != 1 or len(fields) < 2:
            continue
        known = ways.setdefault(ord(char), [])
        for field in fields[1:]:
            parts = [part for part in field.strip().split(" ") if part]
            if len(parts) < 2 or any(len(part) != 1 or part == char for part in parts):
                continue
            if parts not in known:
                known.append(parts)
        if not known:
            ways.pop(ord(char), None)

    orders: dict[int, list[str]] = {}
    for line in lines(orders_path):
        if not line or line.startswith("#"):
            continue
        fields = line.rstrip("\r").split("\t")
        char = fields[0]
        if len(char) == 1 and len(fields) > 1:
            shapes = [shape for shape in fields[1].split(" ") if shape]
            if shapes:
                orders[ord(char)] = shapes

    rows = []
    for cp, radical in radical_of.items():
        number = radical.rstrip("'")
        try:
            radical_number = int(number)
        except ValueError:
            continue
        own = radical_chars.get(radical) or radical_chars.get(number)
        if own is None:
            continue
        variants = {own} if radical.endswith("'") else set(VARIANTS.get(radical_number, ""))
        all_ways = ways.get(cp, [])
        way = next((candidate for candidate in all_ways if any(part in variants for part in candidate)), None)
        if way is None and all_ways:
            way = all_ways[0]
        shown_radical = next((part for part in (way or []) if part in variants), own)
        order = orders.get(cp, [])
        count = len(order) or total_strokes.get(cp, 0)
        rows.append((cp, shown_radical, count, " ".join(way or []), " ".join(order)))

    rows.sort(key=lambda row: row[0])
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as target:
        target.write("# Unicode 17.0.0 Unihan/CJKRadicals + kfcd/chaizi CC BY 3.0 + maintained stroke-order.tsv.\n")
        for cp, radical, count, parts, order in rows:
            target.write(f"{chr(cp)}\t{radical}\t{count}\t{parts}\t{order}\n")
    print(f"wrote {len(rows)} character records to {output}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--unihan", required=True, type=Path)
    parser.add_argument("--radicals", required=True, type=Path)
    parser.add_argument("--chaizi", required=True, type=Path)
    parser.add_argument("--orders", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build(args.unihan, args.radicals, args.chaizi, args.orders, args.output)


if __name__ == "__main__":
    main()
