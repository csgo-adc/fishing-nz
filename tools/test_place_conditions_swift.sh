#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
swiftc "$repo_root/iosApp/CatchCheckNZ/Models/FishingModels.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/LinzTideSource.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/FishingScoringService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/PlaceConditionsService.swift" \
  "$repo_root/iosApp/CatchCheckNZ/Services/WeatherForecastService.swift" \
  "$repo_root/iosApp/Tests/PlaceConditionsRegression.swift" -o "$test_dir/place-condition-tests"
"$test_dir/place-condition-tests" "$repo_root" "$@"
