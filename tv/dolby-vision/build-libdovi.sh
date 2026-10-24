#!/usr/bin/env bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
source_dir="$root/native/dovi-compat/source"
dovi_sha=26ed61ff36f946313889a09c0d2da202e9ceb97a
if [[ ! -d "$source_dir/.git" ]]; then
    mkdir -p "$source_dir"
    git -C "$source_dir" init
    git -C "$source_dir" remote add origin https://github.com/quietvoid/dovi_tool.git
    git -C "$source_dir" fetch --depth=1 origin "$dovi_sha"
    git -C "$source_dir" checkout --detach FETCH_HEAD
fi
test "$(git -C "$source_dir" rev-parse HEAD)" = "$dovi_sha"
export CARGO_TARGET_DIR="$root/native/dovi-compat/target"
export RUSTFLAGS='-C panic=abort'
cd "$source_dir"
cargo rustc --locked --release --manifest-path dolby_vision/Cargo.toml \
    --features capi --crate-type staticlib
mkdir -p "$root/native/dovi-compat/lib/host"
cp "$CARGO_TARGET_DIR/release/libdolby_vision.a" "$root/native/dovi-compat/lib/host/"
cp "$root/tv/dolby-vision/reference_fel.rs" dolby_vision/examples/nova_fel_reference.rs
cargo run --locked --release --manifest-path dolby_vision/Cargo.toml \
    --example nova_fel_reference -- assets/tests/fel_to_81.bin ../fel-profile81.bin
python3 "$root/tv/dolby-vision/test_conversion.py" "$root"
if [[ "${1:-}" == --host-only ]]; then exit 0; fi
: "${ANDROID_NDK_HOME:?Android NDK must be configured}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for spec in \
    'armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi' \
    'arm64-v8a aarch64-linux-android aarch64-linux-android' \
    'x86 i686-linux-android i686-linux-android' \
    'x86_64 x86_64-linux-android x86_64-linux-android'; do
    read -r abi target clang_target <<< "$spec"
    rustup target add "$target"
    linker="$toolchain/${clang_target}21-clang"
    test -x "$linker"
    RUSTFLAGS="-C panic=abort -C linker=$linker" \
        cargo rustc --locked --release --manifest-path dolby_vision/Cargo.toml \
        --features capi --target "$target" --crate-type staticlib
    mkdir -p "$root/native/dovi-compat/lib/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/libdolby_vision.a" "$root/native/dovi-compat/lib/$abi/"
done
