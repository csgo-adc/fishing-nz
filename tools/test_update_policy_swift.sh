#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
swiftc "$repo_root/iosApp/CatchCheckNZ/Models/UpdatePolicy.swift" \
  "$repo_root/iosApp/Tests/UpdatePolicyRegression.swift" -o "$test_dir/update-policy-tests"
"$test_dir/update-policy-tests"
