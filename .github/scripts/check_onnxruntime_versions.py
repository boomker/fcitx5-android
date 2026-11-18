#!/usr/bin/env python3
"""Fail the scheduled check when an official stable ONNX Runtime release is newer."""

from __future__ import annotations

import os
import json
import re
import sys
import tomllib
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STABLE_VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
RUNTIME_METADATA = (
    "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/"
    "onnxruntime-android/maven-metadata.xml"
)
GENAI_METADATA = (
    "https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntimegenai/index.json"
)


def stable_key(version: str) -> tuple[int, int, int] | None:
    match = STABLE_VERSION.fullmatch(version)
    return tuple(map(int, match.groups())) if match else None


def latest_runtime_version() -> str:
    with urllib.request.urlopen(RUNTIME_METADATA, timeout=30) as response:
        root = ET.fromstring(response.read())
    versions = [node.text for node in root.findall("./versioning/versions/version")]
    stable = [version for version in versions if version and stable_key(version)]
    if not stable:
        raise RuntimeError("Maven Central returned no stable Android ONNX Runtime versions")
    return max(stable, key=stable_key)


def latest_genai_version() -> str:
    with urllib.request.urlopen(GENAI_METADATA, timeout=30) as response:
        versions = json.loads(response.read())["versions"]
    stable = [version for version in versions if stable_key(version)]
    if not stable:
        raise RuntimeError("NuGet returned no stable ONNX Runtime GenAI versions")
    return max(stable, key=stable_key)


def main() -> int:
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
    libraries = catalog["libraries"]
    runtime = libraries["onnxruntime-android"]["version"]
    genai_pinned = libraries["onnxruntime-genai-android"]["version"]
    genai = genai_pinned.split("-", maxsplit=1)[0]
    module = (ROOT / "third_party/onnxruntime-genai-android/build.gradle.kts").read_text()
    if f'version = "{genai_pinned}"' not in module:
        raise RuntimeError("GenAI's Gradle project version and the version catalog differ")

    official = {
        "ONNX Runtime Android": (runtime, latest_runtime_version()),
        "ONNX Runtime GenAI Android": (genai, latest_genai_version()),
    }
    outdated = []
    lines = ["## ONNX Runtime stable version check", ""]
    for name, (pinned, latest) in official.items():
        current = stable_key(pinned)
        available = stable_key(latest)
        if current is None:
            raise RuntimeError(f"Invalid pinned stable version for {name}: {pinned}")
        is_outdated = available is not None and current < available
        status = "UPDATE AVAILABLE" if is_outdated else "up to date"
        lines.append(f"- {name}: pinned {pinned}, official stable {latest} - {status}.")
        if is_outdated:
            outdated.append((name, pinned, latest))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    if outdated:
        for name, pinned, latest in outdated:
            print(f"::error title={name} update available::{pinned} is behind official stable {latest}")
        print("Update both catalogs and the verified GenAI prebuilt to clear this check.")
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"::error::{error}", file=sys.stderr)
        raise SystemExit(1) from None
