# Deployment Guide — HCP3 (Android 13 AAOS)

How to build and deploy the Data Collector service to an HCP3 (`hcp3_aaos13`) target — either the vanilla Android 13 AAOS emulator or real HCP3 hardware.

> **Emulator prerequisite:** The HCP3 AVD must be set up and running. See [hcp3-avd-setup.md](hcp3-avd-setup.md).

## Build Variants

The project defines four platform flavors in the `platform` dimension:

| Platform flavor | Target | Signing key |
|---|---|---|
| `mib4` | MIB4 AAOS 15 — Scylla emulator + Aptiv dev hardware | `aosp-platform.jks` (AOSP generic) |
| `hcp3` | HCP3 AAOS 13 vanilla emulator | `aosp-platform.jks` (AOSP generic) |
| `hcp3Hw` | HCP3 dev hardware | `hcp3-platform.jks` (OEM key from `platform.zip`) |

Combined with the `datasource` dimension (`mock` / `real`), the HCP3 build variants are:

| Variant | Use case |
|---|---|
| `hcp3MockDebug` | Development and CI — vanilla emulator, stub vehicle data |
| `hcp3RealDebug` | Debug on real HCP3 hardware with live vehicle data |
| `hcp3HwMockDebug` | Development on real HCP3 hardware, stub vehicle data |
| `hcp3HwRealRelease` | Production deployment to real HCP3 hardware |

> **Note on the `real` datasource on HCP3**: The `real` datasource links against MIB4 ASI/RSI JARs that are absent on HCP3. The `real` variants will build but crash at runtime on HCP3 until an `hcp3`-specific vehicle-connectivity implementation is added.

## Environment Setup

Set `JAVA_HOME` to Android Studio's bundled JDK (required for every terminal session):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Ensure `local.properties` exists at the repository root:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

## Building

From the repository root:

```bash
# Vanilla emulator (mock data)
./gradlew :app:assembleHcp3MockDebug

# Real HCP3 hardware (mock data, signed with HCP3 OEM key)
./gradlew :app:assembleHcp3HwMockDebug
```

APK outputs:

```
app/build/outputs/apk/hcp3Mock/debug/app-hcp3-mock-debug.apk
app/build/outputs/apk/hcp3HwMock/debug/app-hcp3Hw-mock-debug.apk
```

> `minSdk = 33` means the APK is compatible with Android 13. No source changes are required to run on HCP3 with mock data.

## Platform Key Setup (hcp3Hw variants only)

The `hcp3Hw` variants are signed with the HCP3 OEM platform key. To configure it:

### Step 1 — Obtain the raw key files

The OEM key consists of two files that must be placed in `app/keystores/` (both are gitignored):

| File | Description |
|---|---|
| `app/keystores/hcp3-platform.pk8` | PKCS#8 DER-encoded private key |
| `app/keystores/hcp3-platform.x509.pem` | X.509 certificate |

These may be distributed as a `platform.zip` archive. Extract them and place them as named above:

```bash
unzip platform.zip -d /tmp/hcp3-key
cp /tmp/hcp3-key/platform.pk8   app/keystores/hcp3-platform.pk8
cp /tmp/hcp3-key/platform.x509.pem app/keystores/hcp3-platform.x509.pem
```

### Step 2 — Convert to a JKS keystore

```bash
# Convert PKCS#8 private key + certificate to a PKCS#12 bundle
openssl pkcs12 -export \
  -inkey app/keystores/hcp3-platform.pk8 \
  -in    app/keystores/hcp3-platform.x509.pem \
  -out   /tmp/hcp3-platform.p12 \
  -name platform \
  -passout pass:android

# Import the PKCS#12 bundle into a JKS keystore
keytool -importkeystore \
  -srckeystore   /tmp/hcp3-platform.p12 \
  -srcstoretype  PKCS12 \
  -srcstorepass  android \
  -destkeystore  app/keystores/hcp3-platform.jks \
  -deststoretype JKS \
  -deststorepass android \
  -destkeypass   android \
  -alias platform
```

> `app/keystores/hcp3-platform.jks`, `hcp3-platform.pk8`, and `hcp3-platform.x509.pem` are all gitignored. Do not commit them.

### Step 2 — Configure `keystore.properties`

Copy the template and fill in the HCP3 key section:

```bash
cp keystore.properties.template keystore.properties
```

Edit `keystore.properties` and set:

```properties
hcp3.storeFile=app/keystores/hcp3-platform.jks
hcp3.storePassword=android
hcp3.keyAlias=platform
hcp3.keyPassword=android
```

Once configured, `./gradlew :app:assembleHcp3HwMockDebug` will sign with the OEM key.

## System App vs User App

The Data Collector declares `FLAG_SINGLE_USER` on its service and requires privileged Car API permissions.

### Platform Signing — No Remount Needed

When the APK is signed with the same platform key as the system image, Android **automatically grants** all `signature`-level permissions at install time — regardless of install location. This means:

- **No `/system/priv-app/` needed** — `adb install -r` is sufficient
- **No `adb remount` needed** — system partition stays read-only
- **No bootloader unlock needed** — works on locked devices

Permissions auto-granted by platform signature include:
- `DUMP` — `ApplicationStartInfo` for other apps
- `INTERACT_ACROSS_USERS_FULL` — cross-user activity monitoring
- `PACKAGE_USAGE_STATS` — usage statistics
- `READ_LOGS` — system-wide logcat access

Verify after install:

```bash
adb shell pm dump com.porsche.aaos.platform.telemetry | grep -E "DUMP|READ_LOGS|PACKAGE_USAGE|INTERACT_ACROSS"
```

All should show `granted=true`.

### When You Still Need Remount / priv-app

The signing approach does **not** cover:
- `android:sharedUserId="android.uid.system"` — running as UID 1000 requires priv-app placement
- Surviving factory resets — user-installed apps are wiped on reset
- `privileged`-only permissions that lack the `signature` flag (rare)

### Deployment Summary

| Target | Key | Install | Remount? |
|---|---|---|---|
| HCP3 vanilla emulator | `aosp-platform.jks` | `adb install -r` | No |
| HCP3 real hardware | `hcp3-platform.jks` | `adb install -r` | No |

Both use `adb install -r` with the correctly-signed APK. The remount + priv-app approach is a fallback for cases listed above.

### Remount Fallback (priv-app placement)

If priv-app placement is needed (see above), use the steps below.

#### On the vanilla AAOS 13 emulator (`hcp3MockDebug`)

Push the APK to `/system/priv-app/` using `adb remount`. This requires the emulator to be started with `-writable-system` (see [hcp3-avd-setup.md](hcp3-avd-setup.md)). A companion `privapp-permissions` XML must also be pushed to declare every privileged permission; a missing entry causes `system_server` to crash on boot.

#### On real HCP3 hardware (`hcp3HwMockDebug` / `hcp3HwRealRelease`)

The `hcp3Hw` APK is signed with the OEM platform key from `platform.zip` (see Platform Key Setup above). On an engineering build with `adb root` access:

```bash
adb root
adb remount
adb shell mkdir -p /system/priv-app/DataCollector
adb push app/build/outputs/apk/hcp3HwMock/debug/app-hcp3Hw-mock-debug.apk \
    /system/priv-app/DataCollector/DataCollector.apk
adb push docs/guides/hcp3_aaos13_vanilla/privapp-permissions-datacollector.xml \
    /system/etc/permissions/privapp-permissions-datacollector.xml
adb reboot
```

> **Note:** This fallback is NOT required for normal development. Use `adb install -r` with the platform-signed APK instead.

## Installing on the Emulator

With the emulator running and `adb` connected:

```bash
adb -s emulator-5556 install -r app/build/outputs/apk/hcp3Mock/debug/app-hcp3-mock-debug.apk
```

The APK is signed with `aosp-platform.jks` which matches the vanilla AAOS 13 emulator's platform key. All signature-level permissions are auto-granted at install time. No `adb remount` or `-writable-system` is needed.

Alternatively, build and install in one step:

```bash
./gradlew :app:installHcp3MockDebug
```

### Starting the Service

```bash
adb -s emulator-5556 shell am start-foreground-service \
  -n com.porsche.aaos.platform.telemetry/.DataCollectorService
```

Stop it:

```bash
adb -s emulator-5556 shell am force-stop com.porsche.aaos.platform.telemetry
```

### Verifying the Service

```bash
# Check the process is running
adb -s emulator-5556 shell ps -A | grep telemetry

# Check foreground service state
adb -s emulator-5556 shell dumpsys activity services com.porsche.aaos.platform.telemetry

# Live telemetry log
adb -s emulator-5556 logcat -s DataCollector:*
```

### Updating the APK

For subsequent deploys, just reinstall and restart:

```bash
./gradlew :app:assembleHcp3MockDebug
adb -s emulator-5556 install -r app/build/outputs/apk/hcp3Mock/debug/app-hcp3-mock-debug.apk
adb -s emulator-5556 shell am force-stop com.porsche.aaos.platform.telemetry
adb -s emulator-5556 shell am start-foreground-service \
  -n com.porsche.aaos.platform.telemetry/.DataCollectorService
```

### Emulator priv-app Fallback (Rarely Needed)

If you need priv-app placement on the emulator (e.g., for `sharedUserId` or testing `privileged`-only permissions), you must start the emulator with `-writable-system` (see [hcp3-avd-setup.md](hcp3-avd-setup.md)):

```bash
adb -s emulator-5556 root
adb -s emulator-5556 remount
```

Expected output: `remount succeeded`. If you see `remount failed`, the emulator was not started with `-writable-system`.

```bash
APK=app/build/outputs/apk/hcp3Mock/debug/app-hcp3-mock-debug.apk
adb -s emulator-5556 shell mkdir -p /system/priv-app/DataCollector
adb -s emulator-5556 push "$APK" /system/priv-app/DataCollector/DataCollector.apk
adb -s emulator-5556 push \
  docs/guides/hcp3_aaos13_vanilla/privapp-permissions-datacollector.xml \
  /system/etc/permissions/privapp-permissions-datacollector.xml
adb -s emulator-5556 shell pm uninstall com.porsche.aaos.platform.telemetry 2>/dev/null || true
adb -s emulator-5556 reboot
```

Wait for boot:

```bash
adb -s emulator-5556 wait-for-device shell \
  'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done; echo "Boot complete"'
```

> **Note:** This fallback is NOT required for normal development. Use `adb install -r` with the platform-signed APK.

### Known Collector Limitations on Vanilla AAOS 13 Emulator

The following collectors degrade gracefully on the vanilla SDK image — they log a warning and stop their thread without crashing the service:

| Collector | Status | Reason |
|---|---|---|
| `TouchInputCollector` | Disabled | `InputManager.monitorGestureInput` is an internal API absent in the SDK image |
| `MediaPlaybackCollector` | Partial | Cross-user session API (`getActiveSessionsForUser`) not available; falls back to 0 active sessions |

All other collectors (`VehiclePropertyCollector`, `NetworkStatsCollector`, `AudioCollector`, etc.) function normally.

## Installing on Real HCP3 Hardware

Connect the car's head unit via USB and verify `adb` sees the device:

```bash
adb devices
# Expected: <serial>    device
```

If multiple devices are connected, add `-s <serial>` to all subsequent commands.

### Direct install (recommended)

```bash
adb install -r app/build/outputs/apk/hcp3HwMock/debug/app-hcp3Hw-mock-debug.apk
```

The `hcp3Hw` APK is signed with the OEM platform key (`hcp3-platform.jks`), so signature-level permissions are granted automatically by the package manager. No `adb root` or `adb remount` required.

Verify permissions are granted:

```bash
adb shell pm dump com.porsche.aaos.platform.telemetry | grep -E "DUMP|INTERACT_ACROSS|PACKAGE_USAGE"
```

### System app installation via ADB push (fallback — survives factory reset)

Only needed if the app must persist across factory resets or requires `sharedUserId`. On an engineering build with `adb root` access:

```bash
adb root
adb remount
adb shell mkdir -p /system/priv-app/DataCollector
adb push app/build/outputs/apk/hcp3HwMock/debug/app-hcp3Hw-mock-debug.apk \
  /system/priv-app/DataCollector/DataCollector.apk
adb shell chmod 644 /system/priv-app/DataCollector/DataCollector.apk
adb push docs/guides/hcp3_aaos13_vanilla/privapp-permissions-datacollector.xml \
  /system/etc/permissions/privapp-permissions-datacollector.xml
adb reboot
```

After reboot, the service starts automatically on boot and holds all privileged Car API permissions.

## Verifying Platform Signing

To check whether the installed APK is recognised as a platform app:

```bash
adb shell dumpsys package com.porsche.aaos.platform.telemetry | grep -E "userId|flags|pkgFlags"
```

A platform-signed app will show `SYSTEM` in `pkgFlags`. A user-installed app will not.

## Troubleshooting

- **`SecurityException: Component requests FLAG_SINGLE_USER but app does not hold INTERACT_ACROSS_USERS`**: The APK is not signed with the platform key. Verify the signing certificate matches the system image's platform cert (see Verifying Platform Signing). If using the vanilla emulator, ensure the build uses `aosp-platform.jks`. If on hardware, ensure `hcp3-platform.jks` is configured.
- **`remount failed` / `Permission denied`**: The emulator was not started with `-writable-system`. Kill it and relaunch with that flag (see [hcp3-avd-setup.md](hcp3-avd-setup.md)).
- **`IllegalStateException: Signature|privileged permissions not in privapp-permissions allowlist`**: The `privapp-permissions-datacollector.xml` is missing or incomplete. Push the updated file from `docs/guides/hcp3_aaos13_vanilla/privapp-permissions-datacollector.xml` and reboot. If you recently added a permission to `AndroidManifest.xml`, add it to the XML first.
- **Emulator boot is stuck / boot animation runs for 3+ minutes after first priv-app push**: normal — PackageManager rescans all apps. Wait it out. If it never completes, check logcat for a `FATAL EXCEPTION IN SYSTEM PROCESS` which indicates the permissions XML is missing or malformed.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`**: A different signing key was used for a previous install. Uninstall first: `adb -s emulator-5556 uninstall com.porsche.aaos.platform.telemetry`, then re-push.
- **`SecurityException` for Car API calls on real hardware**: the APK is running as a user app without Car permissions. Push to `/system/priv-app/` as described above.
- **Service crashes on startup on real HCP3**: likely caused by `mib4Real` datasource JARs being absent on HCP3. Switch to the `hcp3Mock` build variant.
- **`adb: error: failed to get feature set`**: USB debugging is not enabled on the head unit. Enable it in the HCP3 developer settings or contact the platform team.
- **`SDK location not found`**: create `local.properties` at the repository root with `sdk.dir` (see Environment Setup above).
- **No emulator connected**: run `adb devices`. See [hcp3-avd-setup.md](hcp3-avd-setup.md) for emulator launch instructions.

---

## Windows (WSL)

Build and install commands work from WSL without modification. Differences are limited to paths and one `local.properties` consideration.

### Environment setup in WSL

```bash
export JAVA_HOME="/mnt/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="/mnt/c/Users/$USER/AppData/Local/Android/Sdk"
```

### local.properties path

If the repository lives on the Windows filesystem (`/mnt/c/...`), `sdk.dir` must use the Windows path format:

```properties
# local.properties (repo on Windows filesystem)
sdk.dir=C:\\Users\\<YourUser>\\AppData\\Local\\Android\\Sdk
```

If the repository lives on the WSL filesystem, use the Linux path as normal:

```properties
# local.properties (repo on WSL filesystem)
sdk.dir=/mnt/c/Users/<YourUser>/AppData/Local/Android/Sdk
```

> For best Gradle build performance, keep the repository on the WSL filesystem, not on `/mnt/c`.

### Build

```bash
export JAVA_HOME="/mnt/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleHcp3MockDebug
```

### Install and start the app

All `adb` commands are identical. Ensure the Windows emulator is running first (see [hcp3-avd-setup.md](hcp3-avd-setup.md) Windows section). For real HCP3 hardware connected via USB, Windows usually handles the USB driver; `adb devices` should work from WSL once the driver is installed.

If `adb` cannot see the device from WSL, start the Windows adb server:

```bash
"/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe" start-server
```

Then all subsequent `adb` commands from WSL will route through the same server.
