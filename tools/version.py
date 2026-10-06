#!/usr/bin/env python3
"""Keep project version metadata synchronized using only the Python standard library."""

import argparse
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "version.properties"


def read_version():
    values = dict(
        line.split("=", 1)
        for line in VERSION_FILE.read_text().splitlines()
        if line and not line.startswith("#")
    )
    name = values["versionName"]
    code = int(values["versionCode"])
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", name) or code < 1:
        raise ValueError("Expected a major.minor.patch version and a positive build code")
    return name, code


def replace(text, pattern, value, expected):
    updated, count = re.subn(pattern, lambda match: match[1] + value + match[2], text)
    if count != expected:
        raise ValueError(f"Expected {expected} version fields for {pattern!r}; found {count}")
    return updated


def metadata(name, code):
    """Prepare every edit before writing, and reject unexpected file layouts."""
    updates = {VERSION_FILE: f"versionName={name}\nversionCode={code}\n"}
    for relative in ("web/package.json", "web/package-lock.json"):
        path = ROOT / relative
        data = json.loads(path.read_text())
        data["version"] = name
        if relative.endswith("package-lock.json"):
            data["packages"][""]["version"] = name
        updates[path] = json.dumps(data, indent=2) + "\n"

    path = ROOT / "backend/pyproject.toml"
    updates[path] = replace(path.read_text(), r'(?m)^(version = ")[^"]+("$)', name, 1)

    path = ROOT / "iosApp/CatchCheckNZ.xcodeproj/project.pbxproj"
    text = replace(path.read_text(), r"(CURRENT_PROJECT_VERSION = )[^;]+(;)", str(code), 2)
    updates[path] = replace(text, r"(MARKETING_VERSION = )[^;]+(;)", name, 2)

    path = ROOT / "README.md"
    updates[path] = replace(
        path.read_text(),
        r"(Current project version: \*\*)[^*]+(\*\*)",
        f"{name} (build code {code})",
        1,
    )
    return updates


def check(name, code):
    mismatches = [
        str(path.relative_to(ROOT))
        for path, expected in metadata(name, code).items()
        if path.read_text() != expected
    ]
    if mismatches:
        raise ValueError("Version metadata is out of sync: " + ", ".join(mismatches))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("check", help="Verify that all version metadata agrees")
    subcommands.add_parser("sync", help="Repair metadata from version.properties without bumping")
    bump = subcommands.add_parser("bump", help="Increase version and build code for a new change")
    bump.add_argument("--part", choices=("patch", "minor", "major"), default="patch")
    args = parser.parse_args()

    try:
        name, code = read_version()
        if args.command == "check":
            check(name, code)
        else:
            if args.command == "bump":
                check(name, code)
                major, minor, patch = map(int, name.split("."))
                if args.part == "major":
                    major, minor, patch = major + 1, 0, 0
                elif args.part == "minor":
                    minor, patch = minor + 1, 0
                else:
                    patch += 1
                name, code = f"{major}.{minor}.{patch}", code + 1
            for path, expected in metadata(name, code).items():
                if path.read_text() != expected:
                    path.write_text(expected)
        print(f"{name} (build code {code}) — {args.command} complete")
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"Version error: {error}\n")


if __name__ == "__main__":
    main()
