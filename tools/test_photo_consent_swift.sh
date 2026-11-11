#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
swiftc "$repo_root/iosApp/CatchCheckNZ/Models/PhotoConsentText.swift" \
  "$repo_root/iosApp/Tests/PhotoConsentRegression.swift" -o "$test_dir/photo-consent-tests"
"$test_dir/photo-consent-tests" "$@"
