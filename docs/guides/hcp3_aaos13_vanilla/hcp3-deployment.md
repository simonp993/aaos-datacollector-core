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

### Step 1 — Convert `platform.zip` to a JKS keystore

The zip contains two files: `platform.pk8` (PKCS#8 private key) and `platform.x509.pem` (certificate).

```bash
# Extract the zip
unzip platform.zip -d /tmp/hcp3-key

# Convert PKCS#8 private key + certificate to a PKCS#12 bundle
openssl pkcs12 -export \
  -inkey /tmp/hcp3-key/platform.pk8 \
  -in /tmp/hcp3-key/platform.x509.pem \
  -out /tmp/hcp3-platform.p12 \
  -name platform \
  -passout pass:android

# Import the PKCS#12 bundle into a JKS keystore
keytool -importkeystore \
  -srckeystore /tmp/hcp3-platform.p12 \
  -srcstoretype PKCS12 \
  -srcstorepass android \
  -destkeystore app/keystores/hcp3-platform.jks \
  -deststoretype JKS \
  -deststorepass android \
  -destkeypass android \
  -alias platform
```

> `app/keystores/hcp3-platform.jks` is gitignored. Do not commit it.

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

The Data Collector needs **platform-level signing** to hold Car API permissions. The options differ between emulator and real hardware.

### On the vanilla AAOS 13 emulator (`hcp3MockDebug`)

The vanilla emulator (`sdk_gcar_arm64`) runs as `userdebug` with `adb root` available. The `hcp3` flavor signs with `aosp-platform.jks`. After `adb install`, force-grant the signature-level permissions via root shell:

```bash
adb root
# Grant INTERACT_ACROSS_USERS (needed for FLAG_SINGLE_USER on the service)
adb shell pm grant com.porsche.aaos.platform.telemetry android.permission.INTERACT_ACROSS_USERS
adb shell pm grant com.porsche.aaos.platform.telemetry android.permission.INTERACT_ACROSS_USERS_FULL
```

The Car API permissions (`android.car.permission.*`) must be granted the same way:

```bash
for perm in CAR_SPEED CAR_ENERGY CAR_INFO CAR_DRIVING_STATE CAR_MEDIA CAR_POWERTRAIN; do
  adb shell pm grant com.porsche.aaos.platform.telemetry "android.car.permission.$perm"
done
```

### On real HCP3 hardware (`hcp3HwMockDebug` / `hcp3HwRealRelease`)

The `hcp3Hw` APK is signed with the OEM platform key from `platform.zip` (see Platform Key Setup above). On an engineering build with `adb root` access:

| Option | How | When to use |
|--------|-----|-------------|
| **`adb install`** | Direct install with OEM-signed APK | Engineering builds with `ro.debuggable=1` |
| **ADB push to `/system/priv-app/`** | `adb root` + `adb remount` | Full privileges, survives reboot |

## Installing on the Emulator

```bash
adb -s emulator-5556 install -r app/build/outputs/apk/hcp3Mock/debug/app-hcp3-mock-debug.apk
```

Or build and install in one step:

```bash
./gradlew :app:installHcp3MockDebug
```

After install, run the permission grants shown in System App vs User App above.

### Starting the Service

```bash
adb shell am start-foreground-service \
  -n com.porsche.aaos.platform.telemetry/.DataCollectorService
```

Stop it:

```bash
adb shell am force-stop com.porsche.aaos.platform.telemetry
```

### Verifying the Service

```bash
# Check the process is running
adb shell ps -A | grep telemetry

# Check foreground service state
adb shell dumpsys activity services com.porsche.aaos.platform.telemetry

# Live telemetry log
adb logcat -s DataCollector:*
```

## Installing on Real HCP3 Hardware

Connect the car's head unit via USB and verify `adb` sees the device:

```bash
adb devices
# Expected: <serial>    device
```

If multiple devices are connected, add `-s <serial>` to all subsequent commands.

### Direct install (engineering builds with `adb root`)

```bash
adb install -r app/build/outputs/apk/hcp3HwMock/debug/app-hcp3Hw-mock-debug.apk
```

The `hcp3Hw` APK is signed with the OEM platform key, so signature-level permissions are granted automatically by the package manager.

### System app installation via ADB push (full privileges, survives reboot)

If the HCP3 engineering build allows `adb root` and a writable `/system`:

```bash
adb root
adb remount
adb push app/build/outputs/apk/hcp3HwMock/debug/app-hcp3Hw-mock-debug.apk \
  /system/priv-app/DataCollector/DataCollector.apk
adb shell chmod 644 /system/priv-app/DataCollector/DataCollector.apk
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

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`**: A different signing key was used for a previous install. Uninstall first: `adb uninstall com.porsche.aaos.platform.telemetry`, then reinstall.
- **`SecurityException` for Car API calls**: the APK is running as a user app without Car permissions. See System App vs User App above.
- **Service crashes on startup on real HCP3**: likely caused by `mib4Real` datasource JARs being absent on HCP3. Switch to `mib4Mock` build variant.
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
./gradlew :app:assembleMib4MockDebug
```

### Install and start the app

All `adb` commands are identical. Ensure the Windows emulator is running first (see [hcp3-avd-setup.md](hcp3-avd-setup.md) Windows section). For real HCP3 hardware connected via USB, Windows usually handles the USB driver; `adb devices` should work from WSL once the driver is installed.

If `adb` cannot see the device from WSL, start the Windows adb server:

```bash
"/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe" start-server
```

Then all subsequent `adb` commands from WSL will route through the same server.
