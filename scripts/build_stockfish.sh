#!/usr/bin/env bash
# Builds the official Stockfish engine for Android with the NDK and fetches the
# NNUE network. Results:
#   app/src/main/jniLibs/<abi>/libstockfish*.so   (UCI executables)
#   app/src/main/assets/nnue/<net>.nnue           (evaluation network)
#
# The engine is a regular executable named lib*.so so that Android extracts it
# into the app's nativeLibraryDir, the only location apps may execute from.
#
# Env: ANDROID_NDK_HOME (or ANDROID_HOME + NDK_VERSION), SF_TAG (default sf_19)
set -euo pipefail

SF_TAG="${SF_TAG:-sf_19}"
NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/third_party/stockfish-src"
JNI="$ROOT/app/src/main/jniLibs"
ASSETS="$ROOT/app/src/main/assets/nnue"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
    ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
  else
    echo "ANDROID_NDK_HOME is not set (NDK $NDK_VERSION required)" >&2
    exit 1
  fi
fi
case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) HOST_TAG=linux-x86_64 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found: $TOOLCHAIN" >&2; exit 1; }
export PATH="$TOOLCHAIN:$PATH"

# ---------------------------------------------------------------- sources
if [ ! -f "$SRC/src/Makefile" ]; then
  echo ">> Cloning Stockfish $SF_TAG"
  rm -rf "$SRC"
  git clone --depth 1 --branch "$SF_TAG" https://github.com/official-stockfish/Stockfish.git "$SRC"
fi

NET="$(grep '#define EvalFileDefaultName' "$SRC/src/evaluate.h" | sed 's/.*\(nn-[a-z0-9]\{12\}\.nnue\).*/\1/')"
echo ">> Stockfish $SF_TAG, network $NET"

# ---------------------------------------------------------------- network
sha_ok() { [ -f "$1" ] && [ "nn-$(sha256sum "$1" | cut -c1-12).nnue" = "$(basename "$1")" ]; }
mkdir -p "$ASSETS"
if ! sha_ok "$ASSETS/$NET"; then
  for url in \
    "https://tests.stockfishchess.org/api/nn/$NET" \
    "https://data.stockfishchess.org/nn/$NET" \
    "https://media.githubusercontent.com/media/official-stockfish/networks/master/$NET"; do
    echo ">> Downloading $url"
    if curl -fsSL --retry 3 --max-time 600 -o "$ASSETS/$NET.part" "$url"; then
      mv "$ASSETS/$NET.part" "$ASSETS/$NET"
      sha_ok "$ASSETS/$NET" && break
      echo "   checksum mismatch, trying next mirror" >&2
    fi
    rm -f "$ASSETS/$NET" "$ASSETS/$NET.part"
  done
  sha_ok "$ASSETS/$NET" || { echo "Failed to download $NET" >&2; exit 1; }
fi
# Remove stale networks from older engine versions.
find "$ASSETS" -name '*.nnue' ! -name "$NET" -delete
# The Makefile's `net` target looks for the file next to the sources.
cp -f "$ASSETS/$NET" "$SRC/src/$NET"

# ---------------------------------------------------------------- engine
# ARCH:ABI:output name. arm64 gets two builds; the app picks the dot-product
# variant at runtime when the CPU supports it (asimddp).
TARGETS=(
  "armv8-dotprod:arm64-v8a:libstockfish_dotprod.so"
  "armv8:arm64-v8a:libstockfish.so"
  "armv7-neon:armeabi-v7a:libstockfish.so"
  "x86-64-sse41-popcnt:x86_64:libstockfish.so"
)

for t in "${TARGETS[@]}"; do
  IFS=: read -r arch abi out <<<"$t"
  echo ">> Building $arch for $abi"
  make -C "$SRC/src" clean >/dev/null
  make -C "$SRC/src" -j"$JOBS" build ARCH="$arch" COMP=ndk \
    EXTRACXXFLAGS="-DNNUE_EMBEDDING_OFF" >/dev/null
  mkdir -p "$JNI/$abi"
  llvm-strip "$SRC/src/stockfish" -o "$JNI/$abi/$out"
  chmod 755 "$JNI/$abi/$out"
done
make -C "$SRC/src" clean >/dev/null
echo "$SF_TAG" > "$ASSETS/VERSION"
echo ">> Done"
ls -la "$JNI"/*/ "$ASSETS"
