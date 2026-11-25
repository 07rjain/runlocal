#!/usr/bin/env bash
set -euo pipefail
project_root="$1"
ndk="$2"
abi="$3"
output="$4"
case "$abi" in
    arm64-v8a) target=aarch64-linux-android; compiler=aarch64-linux-android31-clang ;;
    x86_64) target=x86_64-linux-android; compiler=x86_64-linux-android31-clang ;;
    *) exit 1 ;;
esac
toolchain="$(find "$ndk/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -1)"
variable="CARGO_TARGET_${target^^}_LINKER"
export "${variable//-/_}=$toolchain/bin/$compiler"
export RUSTFLAGS="--cfg polyg2p_portable -C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_TARGET_DIR="$output/cargo"
cargo build --locked --release --manifest-path "$project_root/blue-phonemizer/Cargo.toml" --target "$target"
mkdir -p "$output/jni/$abi"
cp "$CARGO_TARGET_DIR/$target/release/libpocket_blue.so" "$output/jni/$abi/"
