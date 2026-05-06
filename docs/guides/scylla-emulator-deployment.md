# Deployment Guide — Scylla (MIB4) Emulator

How to build and deploy an AAOS app to the Scylla (MIB4) emulator.

> **Prerequisite:** The emulator must be set up and running. See [local-avd-setup.md](local-avd-setup.md) for initial setup, launch, and display attachment.

## System App vs User App

AAOS apps that need access to vehicle data or Car API signature-level permissions must run as **system-privileged apps**. Apps with no such requirements can be installed as ordinary user apps.

### System app (required for Car API / VHAL access)

A system app is one that is:
- Pre-installed in the system image (`/system/priv-app/`), **or**
- Installed over an existing system-app slot by a package signed with the **same platform key** as the image.

On the Scylla emulator (and MIB4 hardware), the APK must be signed with the AOSP test platform key (emulator) or the OEM platform key (hardware). The build convention plugin (`datacollector.android-app`) pre-configures this. With a correctly signed APK, `adb install -r` replaces or installs the app with full system privileges.

### User app (no Car API permissions)

Installing as a regular user app is possible but the app will be denied:

- Signature-level Car permissions (e.g. `android.car.permission.CAR_ENERGY`)
- Access to protected VHAL properties
- Car API bindings that require `priv-app` placement

Use user-app installation only for basic smoke tests that do not exercise vehicle data or Car APIs.

## Environment Setup

Set `JAVA_HOME` to Android Studio's bundled JDK (required for every terminal session):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Ensure the Android SDK location is configured. If `local.properties` does not exist at the repository root, create it:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

> `local.properties` is gitignored and must be created locally on each machine.

## Building

Variant names are formed from the `platform` × `datasource` flavor dimensions. For the Scylla emulator use the `mib4Mock` debug variant.

From the repository root:

```bash
./gradlew :app:assembleMib4MockDebug
```

The APK is written to:

```
app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk
```

### Build Variants

| Platform   | Datasource | Build Type | Gradle Task                  | Description                                   |
|------------|------------|------------|------------------------------|-----------------------------------------------|
| **mib4**   | **mock**   | debug      | `assembleMib4MockDebug`      | Emulator / CI — stub vehicle data (default)   |
| mib4       | real       | debug      | `assembleMib4RealDebug`      | MIB4 hardware with live VHAL / ASI / RSI      |
| nextgen    | mock       | debug      | `assembleNextgenMockDebug`   | Next-gen platform emulator                    |
| nextgen    | real       | release    | `assembleNextgenRealRelease` | Next-gen production build                     |

> The `mock` datasource provides in-process stub implementations of all vehicle signals. Use it for emulator development and CI. The `real` datasource connects to live VHAL and ASI/RSI framework services available only on MIB4 hardware.

## Platform Signing

The Data Collector requires **platform-level signing** to be granted system permissions at install time. Both debug and release builds are automatically signed with the AOSP test platform key via the `platform` signing config in the convention plugin (`datacollector.android-app`).

To override, copy `keystore.properties.template` to `keystore.properties` at the repository root and fill in your values.

Default (emulator) values:

- **Keystore:** `app/keystores/aosp-platform.jks`
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
  app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk
```

The SHA-256 of the signer certificate should match `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.

## Installing on the Emulator

With the emulator running and `adb` connected:

```bash
adb install -r app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk
```

The `-r` flag replaces an existing installation in-place. If the Scylla image ships a pre-installed version of your app, ensure the `versionCode` in `app/build.gradle.kts` is equal to or higher than the pre-installed version.

Alternatively, build and install in one step:

```bash
./gradlew :app:installMib4MockDebug
```

### Starting the App

If the app has a launcher Activity:

```bash
adb shell am start -n <app.package.id>/.<MainActivity>
```

If the app is a headless service with no launcher icon, start it explicitly:

```bash
adb shell am start-foreground-service -n <app.package.id>/.<ServiceClass>
```

Stop it:

```bash
adb shell am force-stop <app.package.id>
```

### Verifying the App

```bash
# Check the process is running
adb shell ps -A | grep <app.package.id>

# Check foreground service state (for headless services)
adb shell dumpsys activity services <app.package.id>

# Check installed package info and flags
adb shell dumpsys package <app.package.id> | grep -E "userId|pkgFlags"

# Live log output
adb logcat | grep <your-log-tag>
```

A platform-signed app will show `SYSTEM` in `pkgFlags`. A user-installed app will not.

## Troubleshooting

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`**: The APK is not signed with the platform key expected by the system image. Ensure the build uses the `platform` signing config from `aosp-platform.jks`.
- **`INSTALL_FAILED_VERSION_DOWNGRADE`**: The APK's `versionCode` is lower than the pre-installed version. Increase `versionCode` in `app/build.gradle.kts`.
- **`SDK location not found`**: Create `local.properties` at the repository root with `sdk.dir` (see Environment Setup above).
- **No emulator connected**: Run `adb devices` to check. See [local-avd-setup.md](local-avd-setup.md) for emulator launch instructions.
- **App has no vehicle data on emulator**: The `mock` datasource uses stub data — this is expected. Use the `real` datasource build on hardware with a running VHAL.
- **`SecurityException` in logs for Car API calls**: The APK is not recognised as a platform-signed app by the system. Verify the signing certificate matches (see Verifying APK Signature above).
