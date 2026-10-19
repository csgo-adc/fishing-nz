#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
swiftc "$repo_root/iosApp/CatchCheckNZ/Models/MoonPhase.swift" \
  "$repo_root/iosApp/Tests/MoonPhaseRegression.swift" -o "$test_dir/moon-phase-tests"
"$test_dir/moon-phase-tests"
