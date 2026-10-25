#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
swiftc "$repo_root/iosApp/CatchCheckNZ/Services/OfflineCache.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Models/FishingModels.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/RulesOffline.swift" \
  "$repo_root/iosApp/Tests/RulesOfflineRegression.swift" -o "$test_dir/rules-offline-tests"
"$test_dir/rules-offline-tests"
