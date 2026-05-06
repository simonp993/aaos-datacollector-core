# Local AVD Setup for HCP3 (Android 13 AAOS, ARM64 macOS)

Guide for setting up a vanilla Android Automotive OS 13 emulator on Apple Silicon Macs as the closest available substitute for HCP3 (`hcp3_aaos13`) hardware.

> **Limitation:** No OEM system image exists for HCP3. This guide uses the stock Google AAOS Android 13 image. It provides a valid AAOS environment for testing app logic, service lifecycle, and Car API interactions, but does not replicate HCP3-specific display layout, VHAL properties, or OEM framework extensions.

## Prerequisites

- **Android Studio** installed (provides the SDK and emulator binaries)
- **Homebrew** installed ([https://brew.sh](https://brew.sh))

Install `scrcpy` for display mirroring (optional for a headless service, useful for inspecting system state):

```bash
brew install scrcpy
```

Ensure `adb` and `emulator` are on your PATH:

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$HOME/Library/Android/sdk/cmdline-tools/latest/bin"
```

Add this line to `~/.zshrc` to make it permanent.

## 1. Install the Android 13 AAOS System Image

Open Android Studio → **Settings → SDK Manager → SDK Platforms**. Check **Show Package Details** and install:

```
Android 13 ("Tiramisu") API 33
  └── Android Automotive with Google APIs, ARM 64 v8a
```

> **Note on image variants:** The "Android Automotive without Play Store" AOSP image exists only for x86_64 on API 33. On ARM64, only the "Android Automotive with Google APIs" (Play Store) variant is published by Google. This is fine for our purposes — both images ship the AAOS `CarService`, VHAL emulator, and the full standard set of Car permissions. The presence of Google Play Services makes no difference when testing a system-privileged headless service.

To confirm which exact package is available on your machine:

```bash
$HOME/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list \
  | grep "android-33.*automotive.*arm64"
```

Alternatively, install directly from the command line:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
$HOME/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager \
  "system-images;android-33;android-automotive-playstore;arm64-v8a"
```

Verify the image was installed:

```bash
$HOME/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list_installed \
  | grep "android-33.*automotive"
```

> If the `sdkmanager` command above reports the package as `android-automotive` instead of `android-automotive-playstore`, use that identifier throughout Steps 2 and 3 instead.

## 2. Create the AVD

### Option A: Using `avdmanager`

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
avdmanager create avd \
  -n "HCP3_AVD" \
  -k "system-images;android-33;android-automotive-playstore;arm64-v8a" \
  -d "automotive_1024p_landscape" \
  --force <<< "no"
```

Verify the AVD was created:

```bash
emulator -list-avds
# Expected: HCP3_AVD
```

### Option B: Manual AVD creation

If `avdmanager` is not installed, create the config files directly:

```bash
mkdir -p ~/.android/avd/HCP3_AVD.avd
```

Create the AVD pointer file:

```bash
cat > ~/.android/avd/HCP3_AVD.ini << EOF
avd.ini.encoding=UTF-8
path=$HOME/.android/avd/HCP3_AVD.avd
path.rel=avd/HCP3_AVD.avd
target=android-33
EOF
```

Then proceed to Step 3 to write `config.ini`.

## 3. Configure the AVD

> **Why this step is needed here but not for Scylla/MIB4:** A vanilla AOSP SDK image ships with no AVD hardware profile of its own — `avdmanager` creates a minimal `config.ini` that often lacks display and GPU settings needed for stable AAOS boot. You need to overwrite it with the values below.
>
> The Scylla/MIB4 OEM system image ZIP ships its own `create_avd_config.sh` and `run_local_avd.sh` scripts that generate the correct `config.ini` for you, tuned to the MIB4 display layout and hardware features. The `mib4-avd-setup.md` guide writes those settings manually to make each option explicit, but in practice the Scylla scripts handle it automatically.
>
> If you ever obtain an OEM Android 13 image for HCP3, check whether it ships similar scripts before overwriting its config.

Write the AVD hardware configuration:

```bash
cat > ~/.android/avd/HCP3_AVD.avd/config.ini << 'EOF'
AvdId=HCP3_AVD
PlayStore.enabled=false
abi.type=arm64-v8a
avd.ini.displayname=HCP3_AVD (AAOS 13)
avd.ini.encoding=UTF-8
disk.dataPartition.size=4G
fastboot.chosenSnapshotFile=
fastboot.forceChosenSnapshotBoot=no
fastboot.forceColdBoot=no
fastboot.forceFastBoot=yes
hw.accelerometer=yes
hw.arc=false
hw.audioInput=yes
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.dPad=no
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.gyroscope=yes
hw.initialOrientation=landscape
hw.keyboard=yes
hw.lcd.density=160
hw.lcd.height=768
hw.lcd.width=1024
hw.mainKeys=no
hw.ramSize=2048
hw.sdCard=no
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=system-images/android-33/android-automotive-playstore/arm64-v8a/
runtime.network.latency=none
runtime.network.speed=full
showDeviceFrame=no
skin.dynamic=yes
skin.name=1024x768
skin.path=1024x768
tag.display=Automotive
tag.displaynames=Automotive
tag.id=android-automotive
tag.ids=android-automotive
target=android-33
vm.heapSize=80
EOF
```

Key settings:
- **`hw.lcd`**: 1024×768 @ 160 dpi — standard AAOS automotive profile
- **`hw.gpu.mode=host`**: uses the Mac's native Metal GPU (required for stable rendering)
- **`hw.ramSize=2048`**: 2 GB — sufficient for a single display AAOS image

> To approximate HCP3's display resolution more closely, change `hw.lcd.width`/`hw.lcd.height` once you know the actual HCP3 screen dimensions.

## 4. Launch the Emulator

Ensure the adb daemon is running before launch:

```bash
adb start-server
```

### Standard launch (headless, read-only system)

```bash
~/Library/Android/sdk/emulator/emulator \
  -avd HCP3_AVD \
  -no-snapshot-load \
  -no-window \
  -gpu host &
```

### Launch with writable system partition

Required when deploying Data Collector as a **system priv-app** (the only supported deployment mode). The `-writable-system` flag enables `adb remount`, which makes `/system` writable so the APK and permissions XML can be pushed:

```bash
~/Library/Android/sdk/emulator/emulator \
  -avd HCP3_AVD \
  -writable-system \
  -no-snapshot-load \
  -no-window \
  -gpu host &
```

> Add `-wipe-data` on first launch or after `config.ini` changes to clear cached display state. This also resets the writable system overlay, so you will need to re-push the APK and permissions XML after a wipe.

Wait for the device to finish booting:

```bash
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 5; done
echo "Boot completed"
```

### Attach a Display Window (Optional)

```bash
scrcpy --display-id=0 --window-title="HCP3 AVD" --window-width=1024 --window-height=768 &
```

## 5. Known Differences from Real HCP3 Hardware

| Area                        | Vanilla AAOS 13 Emulator                                    | Real HCP3 (`hcp3_aaos13`)                         |
|-----------------------------|-------------------------------------------------------------|----------------------------------------------------|
| VHAL properties             | Google reference VHAL with a small default property set     | Full OEM VHAL with HCP3-specific properties        |
| Car permissions             | Standard AOSP Car permissions                               | May include OEM-extended permissions               |
| Display layout              | Single 1024×768 display                                     | OEM multi-display configuration                   |
| ASI / RSI signals           | Not available                                               | OEM vehicle signal bus (if supported on HCP3)     |
| Platform signing            | AOSP test key (`aosp-platform.jks`)                         | OEM production platform key                        |
| CarService extensions       | AOSP reference implementation                               | OEM-customised CarService                         |
| Network, audio, sensors     | Emulated                                                    | Hardware-backed                                    |

Use the emulator to validate: service lifecycle, Hilt injection, coroutine/Flow plumbing, mock-datasource data collection, and logcat telemetry output. Test vehicle data collection and Car API permission grants on real HCP3 hardware.

## 6. Deploy and Run

See [hcp3-deployment.md](hcp3-deployment.md) for building, installing, and starting the Data Collector on this AVD and on real HCP3 hardware.

## Shutdown

```bash
adb emu kill
```

Or force-kill:

```bash
pkill -f "emulator.*HCP3_AVD"
```

## Troubleshooting

- **`avdmanager` not found**: install "Android SDK Command-line Tools" via Android Studio's SDK Manager (Settings → SDK Tools), or use Option B manual creation.
- **`avdmanager` JDK error**: set `JAVA_HOME` to Android Studio's bundled JDK (see above).
- **`Unable to connect to adb daemon on port: 5037`**: run `adb start-server` before launching the emulator.
- **System image not found by `sdkmanager`**: ensure you are using `cmdline-tools/latest/bin/sdkmanager`, not an outdated version.
- **Slow boot on Apple Silicon**: ensure `-gpu host` is set in the launch command. Software rendering causes extremely slow boot on AAOS images.
- **`FATAL: Running multiple emulators with the same AVD`**: kill stale processes and remove lock files:
  ```bash
  pkill -9 -f qemu; rm -f ~/.android/avd/HCP3_AVD.avd/*.lock
  ```

---

## Windows (WSL)

The following notes cover running this guide on Windows with WSL. The ARM64/macOS steps above remain the primary guide; these notes describe only what differs.

### System image — use x86_64 for best performance

On Intel/AMD hardware, use the x86_64 image instead of arm64. It runs natively via Hyper-V with no CPU translation and boots significantly faster:

```bash
"/mnt/c/Users/$USER/AppData/Local/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat" \
  "system-images;android-33;android-automotive-playstore;x86_64"
```

For `avdmanager` in Step 2, replace the `-k` flag:

```bash
# Option A with x86_64:
avdmanager.bat create avd \
  -n "HCP3_AVD" \
  -k "system-images;android-33;android-automotive-playstore;x86_64" \
  -d "automotive_1024p_landscape" \
  --force <<< "no"
```

For `config.ini` in Step 3, change two values:

```ini
abi.type=x86_64
hw.cpu.arch=x86_64
image.sysdir.1=system-images/android-33/android-automotive-playstore/x86_64/
```

All other `config.ini` values remain the same.

### SDK and tool paths in WSL

```bash
export ANDROID_HOME="/mnt/c/Users/$USER/AppData/Local/Android/Sdk"
export JAVA_HOME="/mnt/c/Program Files/Android/Android Studio/jbr"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin"
```

Heredoc syntax (`cat > file << 'EOF'`) works as-is in WSL bash.

### Launch the emulator from Windows

The emulator must run as a Windows process to use Hyper-V hardware acceleration. Open a Windows terminal (PowerShell or Git Bash) and run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -avd HCP3_AVD `
  -no-snapshot-load `
  -no-window `
  -gpu host
```

Or from WSL directly:

```bash
"/mnt/c/Users/$USER/AppData/Local/Android/Sdk/emulator/emulator.exe" \
  -avd HCP3_AVD -no-snapshot-load -no-window -gpu host &
```

> `-gpu host` on Windows uses Hyper-V WHPX (Windows Hypervisor Platform). HAXM and Hyper-V conflict — use one or the other. Hyper-V is recommended for Windows 11. To verify Hyper-V is active: run `systeminfo` in an elevated cmd and look for `Hyper-V Requirements: A hypervisor has been detected`.

### adb from WSL

`adb` commands work from WSL once the Windows emulator is running. If `adb devices` shows nothing, start the Windows adb server first:

```bash
"/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe" start-server
```

### scrcpy

Install on Windows (not inside WSL):

```powershell
winget install Genymobile.scrcpy
```

Run `scrcpy` commands from a Windows terminal.

### Kill / lock file cleanup

```bash
taskkill.exe /F /IM qemu-system-x86_64.exe 2>/dev/null
rm -f ~/.android/avd/HCP3_AVD.avd/*.lock
```
