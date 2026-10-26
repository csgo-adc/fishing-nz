#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
# CatchLog.swift needs the catch log store, which holds the conditions lookup and its sources.
swiftc -enable-bare-slash-regex "$repo_root/iosApp/CatchCheckNZ/Models/FishingModels.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Models/MoonPhase.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Models/CatchLog.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/LinzTideSource.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/OfflineCache.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/FishingScoringService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/PlaceConditionsService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/WeatherForecastService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/CatchLogStore.swift" \
  "$repo_root/iosApp/Tests/AccountProfileRegression.swift" -o "$test_dir/account-profile-tests"
"$test_dir/account-profile-tests"
