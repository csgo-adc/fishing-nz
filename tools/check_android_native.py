#!/usr/bin/env python3
"""Verify 64-bit coverage and 16 KB ELF LOAD alignment in an Android AAB/APK.

This checks packaged binaries, not runtime behavior. Also run the app on a
16 KB Android device/emulator and verify generated APK ZIP alignment.
"""
import argparse
import struct
import sys
import zipfile
from pathlib import Path


def load_alignments(data: bytes) -> list[int]:
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF binary")
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        offset = struct.unpack_from(endian + "Q", data, 32)[0]
        entry_size, count = struct.unpack_from(endian + "HH", data, 54)
        header = endian + "IIQQQQQQ"
    elif data[4] == 1:
        offset = struct.unpack_from(endian + "I", data, 28)[0]
        entry_size, count = struct.unpack_from(endian + "HH", data, 42)
        header = endian + "IIIIIIII"
    else:
        raise ValueError("unsupported ELF class")
    return [entry[-1] for i in range(count)
            if (entry := struct.unpack_from(header, data, offset + i * entry_size))[0] == 1]


def check(path: Path) -> bool:
    errors = []
    libraries = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.namelist():
            parts = entry.split("/")
            if not entry.endswith(".so") or "lib" not in parts:
                continue
            index = parts.index("lib")
            abi, name = parts[index + 1], "/".join(parts[index + 2:])
            module = "/".join(parts[:index])
            libraries.setdefault(abi, set()).add((module, name))
            if abi in {"arm64-v8a", "x86_64"}:
                alignments = load_alignments(archive.read(entry))
                if not alignments or any(alignment < 16384 for alignment in alignments):
                    errors.append(f"{entry}: LOAD alignment {alignments}, requires >= 16384")
    for narrow, wide in [("armeabi-v7a", "arm64-v8a"), ("x86", "x86_64")]:
        for module, name in sorted(libraries.get(narrow, set()) - libraries.get(wide, set())):
            errors.append(f"{module}/{name}: {narrow} has no matching {wide} binary")
    for abi, names in sorted(libraries.items()):
        print(f"{abi}: {len(names)} native libraries")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return False
    print("PASS: packaged 64-bit coverage and 16 KB ELF alignment")
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    sys.exit(0 if check(args.archive) else 1)
