# Deployment Guide — Scylla (MIB4) Emulator

How to build and deploy an AAOS app to the Scylla (MIB4) emulator.

> **Prerequisite:** The emulator must be set up and running. See [mib4-avd-setup.md](mib4-avd-setup.md) for initial setup, launch, and display attachment.

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

### Why Signing Alone Is Sufficient (No Remount Needed)

When an APK is signed with the same key as the system image, Android **automatically grants** all permissions declared with `protectionLevel="signature"` — regardless of install location. This means:

- **No `/system/priv-app/` needed** — a user-installed APK signed with the platform key gets the same signature-level permissions as a pre-installed system app.
- **No `adb remount` needed** — the system partition stays read-only.
- **No bootloader unlock needed** — works even on locked bootloaders (like the Scylla emulator).

Permissions auto-granted by platform signature include:
- `DUMP` — required for `ApplicationStartInfo` of other apps
- `INTERACT_ACROSS_USERS_FULL` — required for cross-user activity monitoring
- `PACKAGE_USAGE_STATS` — required for usage statistics
- `READ_LOGS` — required for system-wide logcat access

Verify granted permissions after install:

```bash
adb shell pm dump com.porsche.aaos.platform.telemetry | grep -E "DUMP|READ_LOGS|PACKAGE_USAGE|INTERACT_ACROSS"
```

All should show `granted=true`.

### When You Still Need Remount / priv-app

The signing approach does **not** cover:
- `android:sharedUserId="android.uid.system"` — running as UID 1000 requires priv-app placement
- Surviving factory resets — user-installed apps are wiped
- `privileged`-only permissions that lack the `signature` flag (very rare)

### Emulator vs Hardware

| | Scylla Emulator | MIB4 Hardware |
|---|---|---|
| Platform key | `aosp-platform.jks` (vanilla AOSP test key) | `aosp-platform.jks` (same vanilla AOSP key) |
| Install method | `adb install -r` | `adb install -r` |
| Remount possible | No (locked bootloader) | Yes (dev units have unlocked BL) |
| Remount required | No | No |
| Signature permissions | Auto-granted | Auto-granted |

Both targets use the same AOSP test platform key and the same deployment command. The build output is directly installable on either target.

### Hardware Remount Fallback

If you need priv-app placement on real MIB4 hardware (e.g., for `sharedUserId` or persistence across factory resets):

```bash
# 1. Remount system partition (requires unlocked bootloader)
adb root
adb remount

# 2. Push to priv-app
adb shell mkdir -p /system/priv-app/DataCollector
adb push app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk \
    /system/priv-app/DataCollector/DataCollector.apk

# 3. Add permissions whitelist (only if needed for privileged-only perms)
adb shell "cat > /system/etc/permissions/privapp-permissions-datacollector.xml << 'EOF'
<permissions>
    <privapp-permissions package=\"com.porsche.aaos.platform.telemetry\">
        <permission name=\"android.permission.DUMP\"/>
        <permission name=\"android.permission.PACKAGE_USAGE_STATS\"/>
        <permission name=\"android.permission.READ_LOGS\"/>
        <permission name=\"android.permission.INTERACT_ACROSS_USERS_FULL\"/>
    </privapp-permissions>
</permissions>
EOF"

# 4. Reboot to pick up the new priv-app
adb reboot
```

> **Note:** This is NOT required for normal development. Use `adb install -r` with the platform-signed APK instead. The remount approach is only needed for production-like deployment testing.

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

### Real MIB4 Hardware Deployment (Critical)

Deploying to real MIB4 hardware differs significantly from the emulator due to the AAOS multi-user architecture and the `singleUser="true"` service attribute.

#### Architecture: Why User 0 Matters

The `DataCollectorService` is declared with `android:singleUser="true"` in the manifest. This means:

- The service **always runs in user 0's process**, regardless of which user triggers it.
- If the app is **not installed for user 0**, the service silently fails to start — no crash, no error in logcat.
- `context.filesDir` inside the service resolves to `/data/user/0/com.porsche.aaos.platform.telemetry/files/`.

MIB4 hardware typically has multiple concurrent users:

| User ID | Name | Role |
|---------|------|------|
| 0 | Driver | System user (always running) |
| 11 | #defaultUser | Headless background user |
| 13+ | G3_V_* | Active foreground driver profile |

The **active foreground user** (e.g., user 13) is NOT user 0. If you install only for the active user, the service cannot start.

#### Common Pitfall: `--user` Flag

```bash
# ❌ WRONG — installs only for user 13, service in user 0 cannot start
adb install --user 13 -r app.apk

# ❌ WRONG — same problem if ADB defaults to current user
adb install --user $(adb shell am get-current-user) -r app.apk

# ✅ CORRECT — installs for ALL users including user 0
adb install -r app.apk
```

**Rule: Never use `--user` when installing this app.** The bare `adb install -r` installs for all users.

#### Complete Deployment Procedure

```bash
PKG="com.porsche.aaos.platform.telemetry"
SER="172.16.250.248:5555"  # Replace with your car's IP

# 1) Connect to the car
adb connect "$SER"
adb -s "$SER" devices  # Verify "device" status

# 2) Build the real-datasource APK
./gradlew :app:assembleMib4RealDebug

# 3) Install for ALL users (no --user flag!)
adb -s "$SER" install -r app/build/outputs/apk/mib4Real/debug/app-mib4-real-debug.apk

# 4) Verify user 0 has the package
adb -s "$SER" shell "pm list packages --user 0 | grep $PKG"
# Expected: package:com.porsche.aaos.platform.telemetry

# 5) Increase logcat buffer (MIB4 default is tiny, rotates in seconds)
adb -s "$SER" logcat -G 16M

# 6) Start the service via DebugStartActivity from the active user
CURRENT_USER=$(adb -s "$SER" shell am get-current-user | tr -d '\r')
adb -s "$SER" shell "am start --user $CURRENT_USER \
  -a com.porsche.aaos.platform.telemetry.action.START_SERVICE_ACTIVITY_DEBUG \
  -n $PKG/.DebugStartActivity"

# 7) Verify the service is running (two PIDs expected: user 13 activity + user 0 service)
adb -s "$SER" shell "pidof $PKG"
```

#### Why `shell` Cannot Start the Service Directly

- `am broadcast -a android.intent.action.BOOT_COMPLETED` → `SecurityException` (protected broadcast)
- `am start-foreground-service` → permission denial (Android 14+ FGS restrictions from shell UID 2000)
- The only reliable shell-based trigger is the `DebugStartActivity` which runs inside the app process and calls `DataCollectorService.start(context)` internally.

On a real boot (power cycle), the `BootReceiver` receives `BOOT_COMPLETED` from the system and starts the service automatically.

#### Verification

```bash
PKG="com.porsche.aaos.platform.telemetry"
SER="172.16.250.248:5555"

# Check service is running in user 0
SVC_PID=$(adb -s "$SER" shell pidof "$PKG" | awk '{print $NF}')
adb -s "$SER" logcat -t 500 --pid="$SVC_PID" | grep "\[DataCollector:"

# Verify JSONL telemetry file output (app-private, requires run-as)
adb -s "$SER" shell "run-as $PKG ls -la files/telemetry-logs/"

# Read telemetry events
adb -s "$SER" shell "run-as $PKG cat files/telemetry-logs/telemetry_*.jsonl" | head -5

# Check for specific events
adb -s "$SER" shell "run-as $PKG cat files/telemetry-logs/telemetry_*.jsonl" | grep "System_Info"
adb -s "$SER" shell "run-as $PKG cat files/telemetry-logs/telemetry_*.jsonl" | grep "Vehicle_Info"

# Verify permissions are granted
adb -s "$SER" shell "pm dump $PKG" | grep -E "DUMP|READ_LOGS|PACKAGE_USAGE|INTERACT_ACROSS" | grep granted
```

#### Updating the App (Hot Redeploy)

When pushing a new build to an already-deployed car:

```bash
# 1) Build
./gradlew :app:assembleMib4RealDebug

# 2) Reinstall (for all users)
adb -s "$SER" install -r app/build/outputs/apk/mib4Real/debug/app-mib4-real-debug.apk

# 3) Force-stop and restart
adb -s "$SER" shell "am force-stop $PKG"
CURRENT_USER=$(adb -s "$SER" shell am get-current-user | tr -d '\r')
adb -s "$SER" shell "am start --user $CURRENT_USER \
  -a com.porsche.aaos.platform.telemetry.action.START_SERVICE_ACTIVITY_DEBUG \
  -n $PKG/.DebugStartActivity"
```

> **Note:** A new JSONL file is created on each service restart (timestamped filename). Previous files are retained up to the 1 GB storage cap.

#### Troubleshooting: Service Not Starting

| Symptom | Cause | Fix |
|---------|-------|-----|
| No PID for package | App not installed for user 0 | Reinstall without `--user` flag |
| Only one PID (activity process) | Service didn't start in user 0 | Check `pm list packages --user 0` |
| Service starts but no JSONL files | File path issue (shouldn't happen now) | Check `run-as ... ls files/telemetry-logs/` |
| Logcat shows nothing | Buffer rotated | Increase with `logcat -G 16M` |
| `Collector X failed` in logs | Runtime error in collector | Check stack trace with `--pid` filter |

#### Logcat Tips for MIB4

The MIB4 hardware has an extremely chatty system (binder monitors, vendor services) that fills the default logcat buffer in seconds.

```bash
# Increase buffer to 16MB (persists until reboot)
adb -s "$SER" logcat -G 16M

# Filter by app PID (most reliable)
SVC_PID=$(adb -s "$SER" shell pidof "$PKG" | awk '{print $NF}')
adb -s "$SER" logcat --pid="$SVC_PID" | grep "\[DataCollector:"

# Our log tag format: [DataCollector:ComponentName]
# Examples: [DataCollector:LogTelemetry], [DataCollector:CarInfoCollector]
adb -s "$SER" logcat -t 1000 --pid="$SVC_PID" | grep "LogTelemetry"
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
- **No emulator connected**: Run `adb devices` to check. See [mib4-avd-setup.md](mib4-avd-setup.md) for emulator launch instructions.
- **App has no vehicle data on emulator**: The `mock` datasource uses stub data — this is expected. Use the `real` datasource build on hardware with a running VHAL.
- **`SecurityException` in logs for Car API calls**: The APK is not recognised as a platform-signed app by the system. Verify the signing certificate matches (see Verifying APK Signature above).
- **Installed but service never starts on hardware**: The app must be installed for **user 0** (not just the active user). Reinstall without `--user` flag: `adb install -r <apk>`. Then start via `DebugStartActivity` from the active user. See "Real MIB4 Hardware Deployment" section above.
- **Logcat empty / logs disappear immediately**: MIB4 default logcat buffer is tiny. Increase with `adb logcat -G 16M`.
- **`ClassCastException: Integer[] cannot be cast to int[]`**: VHAL returns boxed arrays for some properties. Ensure array properties use safe conversion (see `readIntArrayProperty` in `CarInfoCollector`).

---

## Windows (WSL)

Build and install commands run without changes inside WSL. The only differences are paths and the `JAVA_HOME` value.

### Environment setup in WSL

```bash
export JAVA_HOME="/mnt/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="/mnt/c/Users/$USER/AppData/Local/Android/Sdk"
```

The repository can live on either the WSL filesystem or the Windows filesystem (`/mnt/c/...`). For build performance, keep it on the WSL filesystem.

### Build

No changes. Gradle runs inside WSL using the WSL `JAVA_HOME`:

```bash
export JAVA_HOME="/mnt/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleMib4MockDebug
```

### Install and start

`adb` commands work from WSL once the emulator is running as a Windows process (see [mib4-avd-setup.md](mib4-avd-setup.md) Windows section). All `adb install`, `adb shell am`, and `adb logcat` commands are identical.

### Verify APK signature

Replace the macOS path with the Windows SDK path in WSL:

```bash
"/mnt/c/Users/$USER/AppData/Local/Android/Sdk/build-tools/$(ls /mnt/c/Users/$USER/AppData/Local/Android/Sdk/build-tools/ | tail -1)/apksigner.bat" \
  verify --print-certs app/build/outputs/apk/mib4Mock/debug/app-mib4-mock-debug.apk
```
