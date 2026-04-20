# Deployment Guide

How to build and deploy Sport Apps to the Scylla (MIB4) emulator.

> **Prerequisite:** The emulator must be set up and running. See [local-avd-setup.md](local-avd-setup.md) for initial setup, launch, and display attachment.

## Environment Setup

Set `JAVA_HOME` to Android Studio's bundled JDK (required for every terminal session):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Ensure the Android SDK location is configured. If `source/local.properties` does not exist, create it:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > source/local.properties
```

> `local.properties` is gitignored and must be created locally on each machine.

## Building

From the repository root:

```bash
cd source
./gradlew :app:assembleDevMockDebug
```

The APK is written to:

```
source/app/build/outputs/apk/devMock/debug/app-dev-mock-debug.apk
```

### Build Variants

| Stage       | Datasource | Build Type | Description                          |
|-------------|------------|------------|--------------------------------------|
| **dev**     | **mock**   | debug      | Development with mock data (default) |
| dev         | real       | debug      | Development with real vehicle signals |
| production  | real       | release    | Production build                     |

Use the variant name in Gradle task capitalization, e.g. `assembleDevRealDebug`, `assembleProductionRealRelease`.

## Platform Signing

The Scylla emulator image ships a pre-installed `com.porsche.sport.chrono` system app signed with the **standard AOSP test platform key**. To install our build over it, the APK must be signed with the same key.

This is already configured in the build. The convention plugin (`datacollector.android-app`) signs both debug and release builds using the AOSP test key by default. To override, copy `source/keystore.properties.template` to `source/keystore.properties` and fill in your values.

Default (emulator) values:

- **Keystore:** `source/app/keystores/aosp-platform.jks`
- **Key alias:** `platform`
- **Passwords:** `android` / `android`

The keystore contains the well-known AOSP test platform certificate:

| Property    | Value                                                              |
|-------------|--------------------------------------------------------------------|
| Owner       | `CN=Android, OU=Android, O=Android, L=Mountain View, ST=California, C=US` |
| SHA-1       | `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`   |
| Valid until | 2035-09-02                                                         |

> **Important:** This is the publicly known AOSP **test** key. It is appropriate for emulator development only. Production builds for real MIB4 hardware will use the OEM platform key provided separately.

### Verifying APK Signature

```bash
$HOME/Library/Android/sdk/build-tools/$(ls $HOME/Library/Android/sdk/build-tools/ | tail -1)/apksigner verify --print-certs \
  source/app/build/outputs/apk/devMock/debug/app-dev-mock-debug.apk
```

The SHA-256 digest should match `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.

## Installing on the Emulator

With the emulator running and `adb` connected:

```bash
adb install -r source/app/build/outputs/apk/devMock/debug/app-dev-mock-debug.apk
```

The `-r` flag replaces the existing system app in-place. This works because:
1. The APK is signed with the same AOSP platform key as the pre-installed system app
2. The `versionCode` (300000) is higher than the system app's (255004)

Alternatively, build and install in one step:

```bash
cd source
./gradlew :app:installDevMockDebug
```

### Launching

```bash
adb shell am start -n com.porsche.sport.chrono/.MainActivity
```

## Enabling the Legacy Sport Chrono App

> This section applies to the **pre-installed legacy** Sport Chrono app on the Scylla image, not to the new build. It is included here for reference when testing against the original system app.

The legacy app's launcher activity is disabled by default. It enables itself when the VHAL property `PORSCHE_DIAG_MENU_DISPLAY_SPORTHMI` (ID `557850989` / `0x2140216d`) is set to `1`.

### Set the VHAL Property

```bash
adb shell cmd car_service set-property-value 557850989 0 1
```

Verify:

```bash
adb shell cmd car_service get-property-value 557850989
# Expected: Value: 1
```

The legacy app process auto-starts during boot. Once running, its reactive Flow subscription detects `SPORTHMI=1` and calls `PackageManager.setComponentEnabledSetting` to enable `MainActivity`. The Sport Chrono icon then appears in the launcher.

> **Note:** The VHAL property does not persist across emulator restarts. It must be re-set after each reboot. The `inject-vhal-event` command can also be used: `adb shell cmd car_service inject-vhal-event 557850989 1`

> **Note:** The current foreground Android user on the Scylla image is user **10** (not user 0). The legacy app is only installed for user 10, so `--user 10` is required for service/activity commands targeting it.

### Verification

```bash
# Check the app process is running
adb shell ps -A | grep sport.chrono

# Check MainActivity is enabled
adb shell dumpsys package com.porsche.sport.chrono | grep -A2 "enabledComponents:"
```

## Troubleshooting

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`**: The APK is not signed with the AOSP platform key. Ensure the build uses the `platform` signing config from `aosp-platform.jks`.
- **`INSTALL_FAILED_VERSION_DOWNGRADE`**: The APK's `versionCode` is lower than the installed app's (255004). Increase `versionCode` in `source/app/build.gradle.kts`.
- **`SDK location not found`**: Create `source/local.properties` with `sdk.dir` (see Environment Setup above).
- **No emulator connected**: Run `adb devices` to check. See [local-avd-setup.md](local-avd-setup.md) for emulator launch instructions.
