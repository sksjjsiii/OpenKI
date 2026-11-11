#!/usr/bin/env bash
set -euo pipefail

# Prepares bundled proot binaries and a minimal Debian arm64 rootfs tarball
# for the Andoco Flutter app.
#
# Requirements: curl, xz, tar, (optionally docker or debootstrap for custom rootfs)
#
# Usage: ./scripts/fetch-proot-and-rootfs.sh

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$ROOT_DIR/assets"
JNI_DIR="$ROOT_DIR/android/app/src/main/jniLibs/arm64-v8a"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$ASSETS_DIR" "$JNI_DIR"

echo "==> Downloading proot (Termux aarch64)..."
# Termux packages are plain .deb in their apt repo
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_aarch64.deb"
PROOT_LOADER_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0-1_aarch64.deb"

curl -fsSL "$PROOT_DEB_URL" -o "$TMP_DIR/proot.deb"
mkdir -p "$TMP_DIR/proot_extract"
cd "$TMP_DIR/proot_extract"
ar x "$TMP_DIR/proot.deb"
tar xf data.tar.xz

# Locate binaries
PROOT_BIN=$(find . -name 'proot' -type f ! -name '*.so*' | head -n1)
LOADER_BIN=$(find . -name 'loader*' -o -name 'libproot-loader*' 2>/dev/null | head -n1 || true)

if [ -z "$PROOT_BIN" ]; then
  echo "Failed to locate proot binary in package" >&2
  exit 1
fi

cp "$PROOT_BIN" "$JNI_DIR/libproot.so"
chmod 755 "$JNI_DIR/libproot.so"

if [ -n "$LOADER_BIN" ]; then
  cp "$LOADER_BIN" "$JNI_DIR/libproot-loader.so"
  chmod 755 "$JNI_DIR/libproot-loader.so"
else
  echo "Warning: proot loader not found in package; proot may still work without it on some setups."
fi

# Also grab libtalloc if proot depends on it
TALLOC_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.2_aarch64.deb"
if curl -fsSL "$TALLOC_URL" -o "$TMP_DIR/libtalloc.deb"; then
  mkdir -p "$TMP_DIR/talloc_extract"
  (cd "$TMP_DIR/talloc_extract" && ar x "$TMP_DIR/libtalloc.deb" && tar xf data.tar.xz)
  TALLOC_SO=$(find "$TMP_DIR/talloc_extract" -name 'libtalloc.so*' | head -n1 || true)
  if [ -n "$TALLOC_SO" ]; then
    cp "$TALLOC_SO" "$JNI_DIR/libtalloc.so.2" || true
  fi
fi

echo "[2/4] Creating minimal Debian arm64 rootfs..."
ROOTFS_DIR="$TMP_DIR/rootfs"
mkdir -p "$ROOTFS_DIR"

if command -v debootstrap >/dev/null 2>&1; then
  echo "Using debootstrap..."
  sudo debootstrap --arch=arm64 --variant=minbase bookworm "$ROOTFS_DIR" http://deb.debian.org/debian
else
  echo "debootstrap not found, using docker to export Debian arm64 rootfs..."
  if ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: Need either debootstrap or docker to build rootfs." >&2
    echo "Install docker or run: sudo apt-get install debootstrap qemu-user-static" >&2
    exit 1
  fi
  docker run --rm --platform linux/arm64 -v "$ROOTFS_DIR:/out" debian:bookworm-slim \
    bash -c 'apt-get update && apt-get install -y --no-install-recommends ca-certificates && cp -a /bin /etc /home /lib /lib64 /media /mnt /opt /root /run /sbin /srv /tmp /usr /var /out/ 2>/dev/null || true; mkdir -p /out/dev /out/proc /out/sys'
fi

echo "[3/4] Packing rootfs..."
tar -C "$ROOTFS_DIR" -cJf "$ASSETS_DIR/debian-rootfs.tar.xz" .

echo "[4/4] Done."
ls -lh "$ASSETS_DIR/debian-rootfs.tar.xz" "$JNI_DIR"/libproot*.so 2>/dev/null || true
