"""Rebuild the same telemetry SDK used by GenAI 0.17.0 with 16 KB ELF alignment."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import time
import urllib.request
import zipfile


SDK_VERSION = "3.10.267.1"
# Pinned by ONNX Runtime GenAI v0.17.0/cmake/deps.txt.
SDK_SHA1 = "9d29169b8e51157d44a24a0d6cf6847260ea2efc"
SDK_URL = f"https://github.com/microsoft/cpp_client_telemetry/archive/refs/tags/v{SDK_VERSION}.zip"
ABIS = ("arm64-v8a", "x86_64")
NATIVE_SOURCE = Path(__file__).resolve().parent / "mat-native"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch_sdk(work):
    archive = work / f"cpp_client_telemetry-{SDK_VERSION}.zip"
    if not archive.is_file() or hashlib.sha1(archive.read_bytes()).hexdigest() != SDK_SHA1:
        for attempt in range(3):
            try:
                with urllib.request.urlopen(SDK_URL, timeout=60) as response:
                    data = response.read()
                if hashlib.sha1(data).hexdigest() != SDK_SHA1:
                    raise RuntimeError("Telemetry source archive checksum mismatch")
                archive.write_bytes(data)
                break
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(2)
    source = work / f"cpp_client_telemetry-{SDK_VERSION}"
    if not source.is_dir():
        with zipfile.ZipFile(archive) as zipped:
            for entry in zipped.infolist():
                destination = (work / entry.filename).resolve()
                if not destination.is_relative_to(work.resolve()):
                    raise RuntimeError("Invalid telemetry archive path")
            zipped.extractall(work)
    return source


def check_alignment(path):
    data = path.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01":
        raise RuntimeError(f"Expected a little-endian ELF64 library: {path}")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    loads = 0
    for index in range(header[10]):
        segment = struct.unpack_from("<IIQQQQQQ", data, header[5] + index * header[9])
        if segment[0] == 1:
            loads += 1
            if segment[7] < 16384 or segment[2] % 16384 != segment[3] % 16384:
                raise RuntimeError(f"ELF LOAD segment is not 16 KB aligned: {path}")
    if not loads:
        raise RuntimeError(f"ELF has no LOAD segments: {path}")


def symbols(nm, path, defined):
    output = subprocess.check_output(
        [str(nm), "--dynamic", "--defined-only" if defined else "--undefined-only", str(path)],
        text=True,
    )
    return {line.split()[-1] for line in output.splitlines() if line.strip()}


def verify_symbols(nm, original, rebuilt, other_libraries):
    old = symbols(nm, original, True)
    new = symbols(nm, rebuilt, True)
    missing = old - new
    sdk_missing = {name for name in missing if not name.startswith("_ZNSt6__ndk1")}
    if sdk_missing:
        raise RuntimeError(f"Telemetry SDK exports were lost: {sorted(sdk_missing)}")
    for library in other_libraries:
        required = symbols(nm, library, False) & old
        absent = required - new
        if absent:
            raise RuntimeError(f"{library.name} imports missing telemetry symbols: {sorted(absent)}")
    # Toolchain upgrades can change unused, statically bundled libc++ internals.
    # Record every difference rather than treating it as a telemetry API change.
    return {"original_exports": len(old), "rebuilt_exports": len(new), "unused_export_differences": sorted(missing)}


def rebuild(args):
    args.input = args.input.resolve()
    args.output = args.output.resolve()
    sdk = args.sdk.resolve()
    ndk = sdk / "ndk" / args.ndk_version
    cmake_dir = sdk / "cmake" / args.cmake_version / "bin"
    executable_suffix = ".exe" if platform.system() == "Windows" else ""
    # CI also uses SDK CMake 3.22.1; the standalone telemetry build needs only 3.22.
    if not (cmake_dir / f"cmake{executable_suffix}").is_file():
        installed = [p for p in (sdk / "cmake").iterdir()
                     if p.is_dir() and tuple(int(v) for v in p.name.split(".")) >= (3, 22)
                     and (p / "bin" / f"cmake{executable_suffix}").is_file()]
        if not installed:
            raise RuntimeError("Android SDK CMake 3.22 or newer is required")
        cmake_dir = max(installed, key=lambda p: tuple(int(v) for v in p.name.split("."))) / "bin"
    cmake = cmake_dir / f"cmake{executable_suffix}"
    ninja = cmake_dir / f"ninja{executable_suffix}"
    hosts = {"Windows": "windows-x86_64", "Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}
    nm = ndk / "toolchains/llvm/prebuilt" / hosts[platform.system()] / "bin" / f"llvm-nm{executable_suffix}"
    for tool in (cmake, ninja, nm, args.zipalign):
        if not tool.is_file():
            raise RuntimeError(f"Required Android SDK tool is missing: {tool}")
    fingerprint = hashlib.sha256(
        (digest(args.input) + args.ndk_version + cmake_dir.parent.name + digest(ndk / "source.properties")
         + digest(Path(__file__)) + "".join(digest(p) for p in sorted(NATIVE_SOURCE.iterdir()))).encode()
    ).hexdigest()
    marker = args.output.with_name(args.output.name + ".alignment.json")
    if args.output.is_file() and marker.is_file():
        cached = json.loads(marker.read_text())
        if cached.get("fingerprint") == fingerprint and cached.get("sha256") == digest(args.output):
            print("Verified cached 16 KB telemetry AAR")
            return
    work = args.output.parent / "mat-build"
    work.mkdir(parents=True, exist_ok=True)
    source = fetch_sdk(work)
    original_dir = work / "original"
    rebuilt_libraries = {}
    report = {}
    with zipfile.ZipFile(args.input) as original:
        for abi in ABIS:
            libraries = []
            for entry in original.namelist():
                if entry.startswith(f"jni/{abi}/") and entry.endswith(".so"):
                    extracted = original_dir / abi / Path(entry).name
                    extracted.parent.mkdir(parents=True, exist_ok=True)
                    extracted.write_bytes(original.read(entry))
                    libraries.append(extracted)
            original_mat = original_dir / abi / "libmat.so"
            build = work / abi
            subprocess.run([
                str(cmake), "-S", str(NATIVE_SOURCE), "-B", str(build), "-G", "Ninja",
                f"-DCMAKE_MAKE_PROGRAM={ninja}",
                f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
                f"-DMATSDK_SOURCE_DIR={source}", f"-DANDROID_ABI={abi}",
                "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
            ], check=True)
            subprocess.run([str(cmake), "--build", str(build), "--target", "mat", "--parallel",
                            str(min(os.cpu_count() or 2, 6))], check=True)
            rebuilt = build / "matsdk/lib/libmat.so"
            check_alignment(rebuilt)
            report[abi] = verify_symbols(nm, original_mat, rebuilt, [p for p in libraries if p != original_mat])
            rebuilt_libraries[f"jni/{abi}/libmat.so"] = rebuilt
        repacked = work / "repacked.aar"
        with zipfile.ZipFile(repacked, "w") as output:
            for entry in original.infolist():
                data = rebuilt_libraries[entry.filename].read_bytes() if entry.filename in rebuilt_libraries else original.read(entry)
                output.writestr(entry, data)
    aligned = work / "aligned.aar"
    subprocess.run([str(args.zipalign), "-f", "-P", "16", "4", str(repacked), str(aligned)], check=True)
    with zipfile.ZipFile(args.input) as original, zipfile.ZipFile(aligned) as output:
        if original.namelist() != output.namelist():
            raise RuntimeError("Rebuilt AAR entry list differs from the official artifact")
        for entry in original.namelist():
            if entry not in rebuilt_libraries and original.read(entry) != output.read(entry):
                raise RuntimeError(f"Non-telemetry artifact changed: {entry}")
    shutil.copyfile(aligned, args.output)
    marker.write_text(json.dumps({"fingerprint": fingerprint, "sha256": digest(args.output), "abis": report}, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--ndk-version", default="28.0.13004108")
    parser.add_argument("--cmake-version", default="3.31.6")
    parser.add_argument("--zipalign", type=Path, required=True)
    rebuild(parser.parse_args())
