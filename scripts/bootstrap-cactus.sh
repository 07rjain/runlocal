#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
destination="$project_root/third_party/cactus-android-arm64"
base='https://huggingface.co/Cactus-Compute/cactus/resolve/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4/android-arm64'
mkdir -p "$destination"
temporary="$(mktemp -d "$destination/.download.XXXXXX")"
trap 'rm -rf -- "$temporary"' EXIT

fetch_verified() {
    local name="$1" hash="$2"
    if [[ -f "$destination/$name" ]] && (cd "$destination" && printf '%s  %s\n' "$hash" "$name" | sha256sum --status -c -); then
        return
    fi
    curl --fail --silent --show-error --location --retry 2 --max-time 90 "$base/$name" -o "$temporary/$name"
    (cd "$temporary" && printf '%s  %s\n' "$hash" "$name" | sha256sum -c -)
    mv -f -- "$temporary/$name" "$destination/$name"
}

fetch_verified cactus.h 9be7eb25b1d89180495d04a0d2c27053f282dac85add50cacc48d73302819d9d
fetch_verified libcactus.a 67dbd7b856a3e9d8b0fbb68f0c59592e7572cf859238ba7f3074f74b6fa9a5da
