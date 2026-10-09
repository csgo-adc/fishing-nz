#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
swiftc "$repo_root/iosApp/CatchCheckNZ/Models/FishingModels.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Models/SharedWindow.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/ShareLinkService.swift" \
  "$repo_root/iosApp/Tests/SharedWindowRegression.swift" -o "$test_dir/shared-window-tests"
"$test_dir/shared-window-tests" "$@"
