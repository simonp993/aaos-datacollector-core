# Deployment Guide — HCP3 (Android 13 AAOS)

How to build and deploy the Data Collector service to an HCP3 (`hcp3_aaos13`) target — either the vanilla Android 13 AAOS emulator or real HCP3 hardware.

> **Emulator prerequisite:** The HCP3 AVD must be set up and running. See [hcp3-avd-setup.md](hcp3-avd-setup.md).

## Build Variant Situation

The project currently defines two platform flavors: `mib4` and `nextgen`. Neither is a direct match for HCP3. Until a dedicated `hcp3` flavor is added:

- **Emulator and CI**: use `mib4Mock` — it produces a valid AAOS APK with stub vehicle data and no dependency on MIB4-specific framework JARs.
- **Real HCP3 hardware**: the `real` datasource variant (`mib4Real`) links against MIB4-specific ASI/RSI JARs that are not present on HCP3. It will build but will crash at runtime when the ASI/RSI service is absent. Use `mib4Mock` on real HCP3 hardware too, or add a dedicated `hcp3` platform flavor before enabling the real datasource.

> **Adding an `hcp3` flavor**: add `create("hcp3") { dimension = "platform" }` to the `productFlavors` block in the convention plugin (`build-logic/src/main/kotlin/datacollector.android-app.gradle.kts`), then add the corresponding `hcp3Mock` / `hcp3Real` DI modules in `app/src/`. This is the correct path for production HCP3 support.

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
./gradlew :app:assembleMib4MockDebug
```

APK output:

```
app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk
```

> `minSdk = 33` means the APK is compatible with Android 13. No source changes are required to run on HCP3 with mock data.

## System App vs User App

The Data Collector needs **platform-level signing** to hold Car API permissions. The options differ between emulator and real hardware.

### On the vanilla AAOS 13 emulator

The emulator image uses the standard AOSP test platform key. The build is pre-signed with this key (`app/keystores/aosp-platform.jks`), so `adb install -r` installs the APK as a platform-signed app with full Car API access.

### On real HCP3 hardware

The OEM production platform key is required for a full system-privileged installation. Without it, two options exist:

| Option | How | Limitations |
|--------|-----|-------------|
| **User app** | `adb install` with debug-key APK | No signature-level Car permissions; no VHAL/CarService access |
| **ADB push to `/system/priv-app/`** | Requires `adb root` + remounted `/system` | Full permissions; invasive; typically only possible on engineering builds |

Start with user-app installation to verify the service starts and produces logcat output, then escalate to platform-signed installation once you have access to an engineering build or the OEM key.

## Installing on the Emulator

```bash
adb install -r app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk
```

Or build and install in one step:

```bash
./gradlew :app:installMib4MockDebug
```

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

### User app installation (no OEM key)

```bash
adb install -r app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk
```

This installs the APK as a regular user app. Car API calls requiring signature-level permissions will be denied at runtime (expect `SecurityException` in logcat). The service lifecycle and mock data collection will still function.

Start and verify the service the same way as the emulator (see above).

### System app installation via ADB push (engineering builds only)

If the HCP3 engineering build allows `adb root` and a writable `/system`:

```bash
adb root
adb remount
adb push app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk \
  /system/priv-app/DataCollector/DataCollector.apk
adb shell chmod 644 /system/priv-app/DataCollector/DataCollector.apk
adb reboot
```

After reboot, the app is installed as a priv-app and granted signature-level permissions on first boot.

> This approach requires the APK to be signed with a key trusted by the HCP3 system image. The AOSP test key (`aosp-platform.jks`) may be trusted on internal engineering builds. Verify with the platform team.

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
