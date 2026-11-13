# Andoco

Flutter Android app (`com.andoco.app`, arm64-v8a) that runs [OpenCode](https://opencode.ai) inside a minimal Debian environment using `proot`.

## Architecture

- **Flutter UI**: `WebView` (`webview_flutter`) showing OpenCode web UI + read-only terminal (`xterm`).
- **Kotlin service**: `OpencodeService` foreground service (`dataSync`) manages extraction, bootstrap, and the `proot`/OpenCode process.
- **Bridge**: `MethodChannel("opencode/control")` and `EventChannel("opencode/logs")`.

## Bundled assets

- `android/app/src/main/jniLibs/arm64-v8a/libproot.so` — Termux proot (arm64)
- `android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so` — proot loader
- `android/app/src/main/jniLibs/arm64-v8a/libtalloc.so.2`, `libandroid-shmem.so` — proot runtime deps
- `assets/debian-rootfs.tar.xz` — minimal Debian arm64 rootfs (compressed)

## Runtime flow

1. On first run, `debian-rootfs.tar.xz` is extracted from assets to `filesDir/rootfs`.
2. If `filesDir/rootfs/.bootstrapped` is missing, `proot` runs:
   - `apt-get update`
   - `apt-get install -y --no-install-recommends nodejs npm curl git ca-certificates`
   - `npm install -g opencode-termux` (with fallback to `opencode-ai` and proot-safe dpkg/perl repair)
   - creates `/.bootstrapped`
3. `proot` starts OpenCode:
   - `opencode web --port 4096 --hostname 127.0.0.1 --print-logs --log-level INFO --pure`
4. Process output is streamed to the Flutter terminal via `EventChannel`.
5. When port `4096` is reachable, the WebView loads `http://127.0.0.1:4096`.

## Build

Requirements: Flutter (stable), Android SDK/NDK, JDK 17.

```bash
flutter pub get
flutter build apk --release --target-platform android-arm64
```

Output: `build/app/outputs/flutter-apk/app-release.apk`

## Fetching/refreshing proot and rootfs

`scripts/fetch-proot-and-rootfs.sh` downloads Termux proot binaries and builds a minimal Debian arm64 rootfs (requires `docker` or `podman`, `curl`, `xz`, `dpkg-deb`):

```bash
bash scripts/fetch-proot-and-rootfs.sh
```

### Extracting a minimal Debian arm64 rootfs from Termux (proot-distro)

If you need to generate `assets/debian-rootfs.tar.xz` manually on an arm64 device using Termux:

1. Install Termux and proot-distro:
   ```bash
   pkg update
   pkg install proot-distro tar xz-utils
   ```
2. Install Debian:
   ```bash
   proot-distro install debian
   ```
   (proot-distro downloads an arm64 rootfs tarball and extracts it under `$PREFIX/var/lib/proot-distro/installed-rootfs/debian`.)
3. (Optional but recommended for smaller size) clean caches inside the rootfs:
   ```bash
   proot-distro login debian -- apt-get clean
   rm -rf $PREFIX/var/lib/proot-distro/installed-rootfs/debian/var/lib/apt/lists/*
   rm -rf $PREFIX/var/lib/proot-distro/installed-rootfs/debian/var/cache/apt/*
   ```
4. Create the compressed archive:
   ```bash
   cd $PREFIX/var/lib/proot-distro/installed-rootfs/debian
   tar -cJf ~/debian-rootfs.tar.xz .
   ```
5. Copy `~/debian-rootfs.tar.xz` to this project as `assets/debian-rootfs.tar.xz` (e.g. via `adb push`, shared storage, or git-lfs as appropriate). Keep it minimal to stay near the ~30MB APK target.

Notes:
- Use the **arm64/aarch64** rootfs (run on an arm64 device or ensure the installed rootfs is aarch64).
- `opencode` and its Node.js dependencies are installed at first app bootstrap inside proot (not in the bundled rootfs) to keep the asset small.

## Android config

- `minSdk 26`, `targetSdk 34`, `abiFilters ["arm64-v8a"]`
- `extractNativeLibs=true`, `usesCleartextTraffic=true`, localhost cleartext allowed via `network_security_config.xml`
- Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`
- `packaging { jniLibs { useLegacyPackaging = false } }`
