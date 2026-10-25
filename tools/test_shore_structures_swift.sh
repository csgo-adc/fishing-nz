#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
swiftc "$repo_root/iosApp/CatchCheckNZ/Models/FishingModels.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/LinzTideSource.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/OfflineCache.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/FishingScoringService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/ShoreStructures.swift" \
  "$repo_root/iosApp/Tests/ShoreStructuresRegression.swift" -o "$test_dir/shore-structure-tests"
"$test_dir/shore-structure-tests" "$repo_root"
