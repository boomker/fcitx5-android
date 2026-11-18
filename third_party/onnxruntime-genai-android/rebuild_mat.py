"""
Rebuild the telemetry SDK used by GenAI 0.17.0 with 16 KB ELF alignment.

Rebuild (maintainer only; see prebuilt/README.md for the official input):

    python3 rebuild_mat.py --rebuild --input <official .aar> --output <aligned .aar> \
        --sdk <android sdk> --zipalign <zipalign>

Verify the committed AAR (no network, no NDK/CMake):

    python3 rebuild_mat.py --verify --output <aligned .aar>

Gradle publishes the AAR in prebuilt/ after checking its SHA-256 and the
16 KB ELF LOAD alignment of the native libraries for both supported ABIs.
"""


import argparse
import hashlib
import json
import os
import platform
import shutil
import struct
import subprocess
import time
import urllib.request
import zipfile
from pathlib import Path

SDK_VERSION = "3.10.267.1"
# The source version is pinned by GenAI v0.17.0/cmake/deps.txt.
SDK_SHA256 = "1b24aad80106357e7757c6a40ebd3fd5921bad09d8215104f3d7b098b131bfc0"
SDK_URL = f"https://codeload.github.com/microsoft/cpp_client_telemetry/zip/refs/tags/v{SDK_VERSION}"
UPSTREAM_AAR_SHA256 = "f8b28a9ce448d97e0d5ab9614eae018a4d994d739de84e9fc5f9ff3b6673dd50"
ABIS = ("arm64-v8a", "x86_64")
LIBRARIES = ("libmat.so", "libonnxruntime-genai.so", "libonnxruntime-genai-jni.so")
NATIVE_SOURCE = Path(__file__).resolve().parent / "mat-native"


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def fetch_sdk(work):
    archive = work / f"cpp_client_telemetry-{SDK_VERSION}.zip"
    if not archive.is_file() or digest(archive) != SDK_SHA256:
        for attempt in range(3):
            try:
                with urllib.request.urlopen(SDK_URL, timeout=60) as response:
                    data = response.read()
                if hashlib.sha256(data).hexdigest() != SDK_SHA256:
                    raise RuntimeError("Telemetry source archive checksum mismatch")
                archive.write_bytes(data)
                break
            except (OSError, RuntimeError):
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
    check_alignment_bytes(path.read_bytes(), path)


def check_alignment_bytes(data, name):
    if data[:6] != b"\x7fELF\x02\x01":
        raise RuntimeError(f"Expected a little-endian ELF64 library: {name}")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    loads = 0
    for index in range(header[10]):
        segment = struct.unpack_from("<IIQQQQQQ", data, header[5] + index * header[9])
        if segment[0] == 1:
            loads += 1
            if segment[7] < 16384 or segment[2] % 16384 != segment[3] % 16384:
                raise RuntimeError(f"ELF LOAD segment is not 16 KB aligned: {name}")
    if not loads:
        raise RuntimeError(f"ELF has no LOAD segments: {name}")


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


def sdk_paths(args):
    sdk = args.sdk.resolve()
    suffix = ".exe" if platform.system() == "Windows" else ""
    ndk = sdk / "ndk" / args.ndk_version
    # Any CMake 3.22+ under the SDK works for the standalone telemetry build.
    installed = []
    for candidate in (sdk / "cmake").iterdir():
        if not candidate.is_dir() or not (candidate / "bin" / f"cmake{suffix}").is_file():
            continue
        try:
            version = tuple(int(part) for part in candidate.name.split("."))
        except ValueError:
            continue
        if version >= (3, 22):
            installed.append((version, candidate / "bin"))
    if not installed:
        raise RuntimeError("Android SDK CMake 3.22 or newer is required")
    cmake_dir = max(installed)[1]
    hosts = {"Windows": "windows-x86_64", "Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}
    nm = ndk / "toolchains/llvm/prebuilt" / hosts[platform.system()] / "bin" / f"llvm-nm{suffix}"
    # The publish-only --verify path never builds, so its tools may be absent.
    for tool in (cmake_dir / f"cmake{suffix}", cmake_dir / f"ninja{suffix}", nm,
                 args.zipalign, ndk / "source.properties"):
        if not tool.is_file():
            raise RuntimeError(f"Required Android SDK tool is missing: {tool}")
    return ndk, cmake_dir, nm, suffix


def verify(args):
    """Check the artifact checksum and the native libraries for both ABIs."""
    marker_path = args.output.with_name(args.output.name + ".alignment.json")
    for required in (args.output, marker_path):
        if not required.is_file():
            raise RuntimeError(f"Required prebuilt file is missing: {required}")
    marker = json.loads(marker_path.read_text(encoding="utf-8"))
    if marker.get("sha256") != digest(args.output):
        raise RuntimeError(f"Rebuilt AAR does not match its marker: {args.output}")
    if set(marker.get("abis", {})) != set(ABIS):
        raise RuntimeError(f"Alignment marker must report {', '.join(ABIS)}: {marker_path}")
    with zipfile.ZipFile(args.output) as aar:
        libraries = {name for name in aar.namelist() if name.startswith("jni/") and name.endswith(".so")}
        required = {f"jni/{abi}/{library}" for abi in ABIS for library in LIBRARIES}
        if missing := required - libraries:
            raise RuntimeError(f"Rebuilt AAR is missing native libraries: {sorted(missing)}")
        for library in sorted(libraries):
            check_alignment_bytes(aar.read(library), library)
    print(f"Verified SHA-256 and 16 KB ELF alignment for {', '.join(ABIS)}")


def rebuild(args):
    args.input = args.input.resolve()
    args.output = args.output.resolve()
    if args.input == args.output or digest(args.input) != UPSTREAM_AAR_SHA256:
        raise RuntimeError(f"Expected the unmodified Microsoft GenAI 0.17.0 AAR as input: {args.input}")
    ndk, cmake_dir, nm, suffix = sdk_paths(args)
    cmake = cmake_dir / f"cmake{suffix}"
    ninja = cmake_dir / f"ninja{suffix}"
    work = NATIVE_SOURCE.parent / "build" / "mat-rebuild"
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
            stripped = build / "libmat.so"
            strip = nm.with_name(f"llvm-strip{suffix}")
            subprocess.run([str(strip), "--strip-debug", "-o", str(stripped), str(rebuilt)], check=True)
            rebuilt = stripped
            check_alignment(rebuilt)
            report[abi] = verify_symbols(nm, original_mat, rebuilt, [p for p in libraries if p != original_mat])
            rebuilt_libraries[f"jni/{abi}/libmat.so"] = rebuilt
        repacked = work / "repacked.aar"
        with zipfile.ZipFile(repacked, "w") as output:
            for entry in original.infolist():
                data = (rebuilt_libraries[entry.filename].read_bytes()
                        if entry.filename in rebuilt_libraries else original.read(entry))
                output.writestr(entry, data)
    aligned = work / "aligned.aar"
    subprocess.run([str(args.zipalign), "-f", "-P", "16", "4", str(repacked), str(aligned)], check=True)
    with zipfile.ZipFile(args.input) as original, zipfile.ZipFile(aligned) as output:
        if original.namelist() != output.namelist():
            raise RuntimeError("Rebuilt AAR entry list differs from the official artifact")
        for entry in original.namelist():
            if entry not in rebuilt_libraries and original.read(entry) != output.read(entry):
                raise RuntimeError(f"Non-telemetry artifact changed: {entry}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(aligned, args.output)
    args.output.with_name(args.output.name + ".alignment.json").write_text(
        json.dumps({"sha256": digest(args.output), "abis": report}, indent=2) + "\n", encoding="utf-8")
    verify(args)
    print("Rebuilt 16 KB telemetry AAR")
    print(json.dumps(report, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description="16 KB telemetry AAR for ONNX Runtime GenAI")
    parser.add_argument("--output", type=Path, required=True,
                        help="the committed AAR; its marker is <output>.alignment.json")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--verify", action="store_true", help="re-hash and re-check the committed AAR")
    mode.add_argument("--rebuild", action="store_true", help="rebuild libmat.so from the official AAR")
    parser.add_argument("--input", type=Path, help="official AAR (--rebuild only)")
    parser.add_argument("--sdk", type=Path, help="Android SDK root (--rebuild only)")
    parser.add_argument("--ndk-version", default="28.0.13004108")
    parser.add_argument("--zipalign", type=Path, help="zipalign binary (--rebuild only)")
    args = parser.parse_args()
    if args.rebuild:
        if args.input is None:
            parser.error("--rebuild requires --input")
        if args.sdk is None or args.zipalign is None:
            parser.error("--rebuild requires --sdk and --zipalign")
        rebuild(args)
    else:
        verify(args)


if __name__ == "__main__":
    main()
