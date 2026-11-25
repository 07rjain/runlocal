#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d "$root/third_party/.adapters-XXXXXX")"
trap 'rm -rf "$scratch"' EXIT

download() {
    local url="$1" digest="$2" output="$3"
    if [[ -f "$output" ]] && printf '%s  %s\n' "$digest" "$output" | sha256sum --check --status; then
        return
    fi
    curl --fail --location --silent --show-error --retry 2 "$url" -o "$scratch/download"
    printf '%s  %s\n' "$digest" "$scratch/download" | sha256sum --check --status
    mv "$scratch/download" "$output"
}

mkdir -p "$root/third_party/android-adapters"
download 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-static-link-onnxruntime-2.0.3.aar' \
    7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4 "$root/third_party/android-adapters/sherpa.aar"
download 'https://jitpack.io/com/github/adaptech-cz/tesseract4android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar' \
    bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a "$root/third_party/android-adapters/tesseract.aar"

llama_commit=0cac1220e35a25fcfbe58e115ee2282a2de76142
llama_path="$root/third_party/llama.cpp"
if [[ ! -f "$llama_path/.runlocal-revision" ]] || [[ "$(cat "$llama_path/.runlocal-revision")" != "$llama_commit" ]]; then
    if [[ -e "$llama_path" ]]; then
        echo "Move the existing llama.cpp directory aside before changing its revision." >&2
        exit 1
    fi
    download "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/$llama_commit" \
        cc79fd9bbe83c9d2fe407ac41eacca3d189083ececdc9f5f075f77c8e6e6e0f2 "$scratch/llama.tar.gz"
    mkdir "$scratch/llama"
    tar xzf "$scratch/llama.tar.gz" --strip-components=1 -C "$scratch/llama"
    printf '%s\n' "$llama_commit" > "$scratch/llama/.runlocal-revision"
    mv "$scratch/llama" "$llama_path"
fi

poly_commit=cbb613499adcda2d3039861c7d347c902e60fbd0
poly_path="$root/third_party/polyg2p"
if [[ ! -f "$poly_path/.runlocal-revision" ]] || [[ "$(cat "$poly_path/.runlocal-revision")" != "$poly_commit" ]]; then
    if [[ -e "$poly_path" ]]; then
        echo "Move the existing polyg2p directory aside before changing its revision." >&2
        exit 1
    fi
    download "https://codeload.github.com/maxmelichov/polyg2p/tar.gz/$poly_commit" \
        d4acfdfd4b90dac2100b37113efbef0e16c2aa2fb9212e5a76f0539aebd2b4d2 "$scratch/poly.tar.gz"
    mkdir "$scratch/poly"
    tar xzf "$scratch/poly.tar.gz" --strip-components=1 -C "$scratch/poly"
    printf '%s\n' "$poly_commit" > "$scratch/poly/.runlocal-revision"
    mv "$scratch/poly" "$poly_path"
fi
# GitHub Android CI and local builds use the Rust standard library for the supported Android ABIs.
rustup target add aarch64-linux-android x86_64-linux-android
