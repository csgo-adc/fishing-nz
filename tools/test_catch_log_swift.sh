#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
# CatchLogStore.swift also holds the conditions lookup, which needs the place-conditions and tide sources.
swiftc -enable-bare-slash-regex "$repo_root/iosApp/CatchCheckNZ/Models/FishingModels.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Models/MoonPhase.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Models/CatchLog.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/LinzTideSource.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/FishingScoringService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/PlaceConditionsService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/WeatherForecastService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/CatchLogStore.swift" \
  "$repo_root/iosApp/Tests/CatchLogRegression.swift" -o "$test_dir/catch-log-tests"
"$test_dir/catch-log-tests" "$repo_root"
