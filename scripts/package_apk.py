#!/usr/bin/env python3
"""Verify a normally installable, standalone phone APK before sharing it."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile


def checked(command):
    return subprocess.check_output([str(arg) for arg in command], text=True, stderr=subprocess.STDOUT)


def certificate(apk, build_tools):
    output = checked([build_tools / "apksigner", "verify", "--print-certs", apk])
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", output, re.MULTILINE)
    if len(signers) != 1:
        raise ValueError("Expected one verified APK signer.")
    return signers[0]


def verify_update_permissions(badging):
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.MULTILINE))
    if "android.permission.ACCESS_NETWORK_STATE" not in permissions:
        raise ValueError("Missing ACCESS_NETWORK_STATE: scheduling network-constrained update jobs crashes on Android 14+.")


def elf_alignments(name, data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"Unexpected native library format: {name}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    alignments = []
    for i in range(count):
        kind, _, file_offset, virtual_address, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + i * entry_size)
        if kind == 1:
            if alignment < 16384 or file_offset % 16384 != virtual_address % 16384:
                raise ValueError(f"Native library does not support 16 KB pages: {name}")
            alignments.append(alignment)
    if not alignments:
        raise ValueError(f"No loadable native segments: {name}")
    return alignments


def python_native_libraries(archive):
    """Chaquopy extracts ELF files from asset archives; APK lib/ checks alone miss these."""
    libraries = {}
    for name in archive.namelist():
        if not name.startswith("assets/chaquopy/"):
            continue
        data = archive.read(name)
        if data.startswith(b"\x7fELF"):
            libraries[name] = elf_alignments(name, data)
        elif name.endswith(".imy") and zipfile.is_zipfile(io.BytesIO(data)):
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                for member in nested.namelist():
                    # ELF libraries may have version suffixes, e.g. libopenblas.so.0.
                    if ".so" in member:
                        payload = nested.read(member)
                        if payload.startswith(b"\x7fELF"):
                            key = f"{name}!/{member}"
                            libraries[key] = elf_alignments(key, payload)
    return libraries


def verify_apk(apk, build_tools, previous=None):
    manifest = checked([build_tools / "aapt2", "dump", "xmltree", apk, "--file", "AndroidManifest.xml"])
    for attribute in ("testOnly", "isSplitRequired"):
        for line in manifest.splitlines():
            if re.search(rf":{attribute}\(", line) and not re.search(r"=(false|0x00000000)\s*$", line):
                reason = "test-only" if attribute == "testOnly" else "requires split APKs"
                raise ValueError(f"Refusing to distribute this APK: {reason}. Build with -Prunlocal.abis=arm64-v8a, without Android Studio's injected ABI property.")
    if re.search(r"^\s*A: split=", manifest, re.MULTILINE):
        raise ValueError("This is a split APK, not a standalone app.")
    badging = checked([build_tools / "aapt2", "dump", "badging", apk])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not package or package[1] != "app.pocket.local" or "application-label:'RunLocal'" not in badging:
        raise ValueError("Unexpected app identity or label.")
    verify_update_permissions(badging)
    if "native-code: 'arm64-v8a'" not in badging:
        raise ValueError("This is not the standalone ARM64 phone build.")
    signer = certificate(apk, build_tools)
    if previous and certificate(previous, build_tools) != signer:
        raise ValueError("Signing certificate changed; this cannot update the previously shared app.")
    checked([build_tools / "zipalign", "-c", "-P", "16", "4", apk])
    libraries = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not (name.startswith("lib/") and name.endswith(".so")):
                continue
            libraries[name] = elf_alignments(name, archive.read(name))
        libraries.update(python_native_libraries(archive))
    if "lib/arm64-v8a/libpocket_diffusion.so" not in libraries:
        raise ValueError("Diffusion library is missing.")
    return dict(package=package[1], versionCode=int(package[2]), versionName=package[3],
                testOnly=False, standalone=True, certificateSha256=signer,
                nativeLibraries=libraries, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    parser.add_argument("--build-tools", type=Path, default=sdk / "build-tools/36.0.0")
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    report = verify_apk(args.apk, args.build_tools, args.previous)
    if args.output_dir:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        filename = f"runlocal-{report['versionName']}-arm64.apk"
        for name in (filename, "runlocal-android-arm64.apk", "pocket-android-arm64.apk"):
            target = args.output_dir / name
            if target.resolve() != args.apk.resolve():
                shutil.copy2(args.apk, target)
        (args.output_dir / "SHA256SUMS").write_text("".join(f"{report['sha256']}  {name}\n" for name in (filename, "runlocal-android-arm64.apk", "pocket-android-arm64.apk")))
        (args.output_dir / "INSTALLATION-CHECKS.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
