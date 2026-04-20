# Local AVD Setup for MIB4 Emulator (ARM64 macOS)

Guide for setting up the Scylla (MIB4) Android Automotive emulator on Apple Silicon Macs using CLI tools.

## Prerequisites

- **Android Studio** installed (provides the SDK and emulator binaries)
- **Homebrew** installed ([https://brew.sh](https://brew.sh))
- **Scylla ARM64 system image** ZIP (provided by Aptiv/OEM)

Install required tools:

```bash
brew install scrcpy
```

Ensure `adb`, `emulator`, and (optionally) `avdmanager` are accessible from your PATH:

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$HOME/Library/Android/sdk/cmdline-tools/latest/bin"
```

Add this line to `~/.zshrc` to make it permanent.

> **Note:** `adb` may also be installed via Homebrew (`brew install android-platform-tools`). Either source works.
>
> **Note:** The `cmdline-tools` path is only needed if you want to use `avdmanager`. If the "Android SDK Command-line Tools" package is not installed, you can install it via Android Studio's SDK Manager (Settings > SDK Tools > Android SDK Command-line Tools) or skip `avdmanager` entirely and create the AVD manually (see Step 2b).

## 1. Import the Scylla System Image

Extract the provided Scylla ARM64 system image into the Android SDK `system-images` directory:

```bash
mkdir -p ~/Library/Android/sdk/system-images/android-35/scylla_avd_arm64
unzip -o scylla_avd_arm64.zip -d ~/Library/Android/sdk/system-images/android-35/scylla_avd_arm64/
```

> **Important:** The ZIP contains a nested `scylla_avd_arm64/arm64-v8a/` directory. After extraction, move the contents up one level so the path is correct:

```bash
mv ~/Library/Android/sdk/system-images/android-35/scylla_avd_arm64/scylla_avd_arm64/arm64-v8a \
   ~/Library/Android/sdk/system-images/android-35/scylla_avd_arm64/arm64-v8a
rm -rf ~/Library/Android/sdk/system-images/android-35/scylla_avd_arm64/scylla_avd_arm64 \
       ~/Library/Android/sdk/system-images/android-35/scylla_avd_arm64/__MACOSX
```

After extraction, the directory structure should look like:

```
~/Library/Android/sdk/system-images/android-35/scylla_avd_arm64/arm64-v8a/
├── VerifiedBootParams.textproto
├── advancedFeatures.ini
├── build.prop
├── create_avd_config.sh
├── data/
├── encryptionkey.img
├── kernel-ranchu
├── package.xml
├── ramdisk.img
├── run_local_avd.sh
├── source.properties
├── system.img
└── vendor.img
```

Verify the image is recognized (requires `avdmanager` — skip if not installed, see Step 2b):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
avdmanager list target
```

You should see `android-35` in the list of available targets.

## 2. Create the AVD

### Option A: Using `avdmanager` (requires Android SDK Command-line Tools)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
avdmanager create avd \
  -n "Scylla_AVD" \
  -k "system-images;android-35;scylla_avd_arm64;arm64-v8a" \
  -d "automotive_1024p_landscape" \
  --force <<< "no"
```

### Option B: Manual AVD creation (no `avdmanager` needed)

If `avdmanager` is not installed, create the AVD by writing the config files directly:

```bash
mkdir -p ~/.android/avd/Scylla_AVD.avd
```

Create the AVD pointer file:

```bash
cat > ~/.android/avd/Scylla_AVD.ini << EOF
avd.ini.encoding=UTF-8
path=$HOME/.android/avd/Scylla_AVD.avd
path.rel=avd/Scylla_AVD.avd
target=android-35
EOF
```

Then proceed to Step 3 to write the `config.ini`.

Verify the emulator sees the AVD:

```bash
emulator -list-avds
# Expected: Scylla_AVD
```

## 3. Configure the AVD

Edit the AVD `config.ini` to match the Scylla display configuration:

```bash
cat > ~/.android/avd/Scylla_AVD.avd/config.ini << 'EOF'
AvdId=Scylla_AVD
PlayStore.enabled=false
abi.type=arm64-v8a
avd.ini.displayname=Scylla_AVD
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.chosenSnapshotFile=
fastboot.forceChosenSnapshotBoot=no
fastboot.forceColdBoot=no
fastboot.forceFastBoot=yes
hw.accelerometer=yes
hw.arc=false
hw.audioInput=yes
hw.battery=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.dPad=no
hw.device.hash2=MD5:7f15940e63ba2649e523243f9f583126
hw.device.manufacturer=aptiv
hw.device.name=hawk
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.gyroscope=yes
hw.initialOrientation=landscape
hw.keyboard=yes
hw.lcd.density=160
hw.lcd.height=720
hw.lcd.width=1920
hw.mainKeys=no
hw.ramSize=3584
hw.sdCard=yes
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=system-images/android-35/scylla_avd_arm64/arm64-v8a/
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=no
skin.dynamic=yes
skin.name=1920x720
skin.path=1920x720
tag.display=Automotive
tag.displaynames=Automotive
tag.id=android-automotive
tag.ids=android-automotive
target=android-35
vm.heapSize=80
EOF
```

Key settings:
- **`hw.lcd`**: 1920x720 @ 160dpi — primary (Center Screen) display
- **`hw.gpu.mode=host`**: uses the Mac's native Metal GPU (required for stable rendering)
- **`hw.ramSize=3584`**: 3.5GB RAM for multi-display support
- **No `hw.display*` entries**: the Scylla guest image manages secondary displays internally via its own HWC

## 4. Launch the Emulator (Headless)

The Scylla HWC composites all 4 displays into the emulator's built-in window, which causes rendering artifacts. Use headless mode and attach to individual displays via `scrcpy` instead.

Ensure the adb daemon is running before launching the emulator:

```bash
adb start-server
```

Launch the emulator:

```bash
~/Library/Android/sdk/emulator/emulator \
  -avd Scylla_AVD \
  -no-snapshot-load \
  -no-window \
  -gpu host &
```

> On first launch or after config changes, add `-wipe-data` to clear cached display state.

Wait for the device to boot:

```bash
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 5; done
echo "Boot completed"
```

## 5. Dismiss the OEM Setup Wizard

On first boot (or after `-wipe-data`), the Scylla image launches the OEM setup wizard (`de.eso.setupwizard`) on the center display. A "Mark network" dialog (`de.eso.phone` HomeNetworkTagActivity) also appears on top. Together they block visual access to the launcher and can interfere with app deployment workflows.

Dismiss both and mark setup as complete:

```bash
# 1. Dismiss the "Mark network" dialog
adb shell am force-stop --user 10 de.eso.phone

# 2. Dismiss the OEM setup wizard
adb shell am force-stop --user 10 de.eso.setupwizard

# 3. Disable the setup wizard so it does not re-launch
adb shell pm disable-user --user 10 de.eso.setupwizard

# 4. Mark setup as completed (order matters — set these AFTER force-stop)
adb shell settings put secure user_setup_complete 1
adb shell settings put secure setup_wizard_has_run 1
```

These settings persist across normal reboots. You only need to run this once after a `-wipe-data` launch.

> **Why this is needed:** The Scylla image ships with `device_provisioned=1` and `user_setup_complete=1` in its factory defaults, but `setup_wizard_has_run` is unset. The OEM setup wizard launches regardless and presents a multi-step consent flow (Privacy → Speech → Open-Source Disclaimer → Finish). Until dismissed, it overlays the center display and blocks interaction with apps.

> **New users:** If you create additional users (e.g. `adb shell pm create-user "Dev"`), the setup wizard will launch for each new user when switched to. Repeat the steps above with the appropriate `--user <id>`.

## 6. Attach to Displays via scrcpy

The Scylla system image creates 4 physical displays. Use `scrcpy` to open a separate window for each:

```bash
scrcpy --display-id=0 --window-title="Center Screen"       --window-width=1920 --window-height=720  &
scrcpy --display-id=1 --window-title="Passenger Screen"     --window-width=1920 --window-height=1080 &
scrcpy --display-id=2 --window-title="Rear-Passenger Screen" --window-width=1920 --window-height=720  &
scrcpy --display-id=3 --window-title="Instrument Cluster"   --window-width=1280 --window-height=768  &
```

### Display Mapping

| Display ID | Resolution  | DPI | Role                  |
| ---------- | ----------- | --- | --------------------- |
| 0          | 1920 x 720  | 160 | Center Screen         |
| 1          | 1920 x 1080 | 160 | Passenger Screen      |
| 2          | 1920 x 720  | 160 | Rear-Passenger Screen |
| 3          | 1280 x 768  | 160 | Instrument Cluster    |

> If multiple emulators are running, specify which one to target: `scrcpy -s emulator-5554 --display-id=0 ...`

## 7. Deploy and Run the App

See [scylla-emulator-deployment.md](scylla-emulator-deployment.md) for building, signing, installing, and launching the Sport Apps on the emulator.

## Shutdown

```bash
adb emu kill
```

Or to force-kill:

```bash
pkill -f "emulator.*Scylla_AVD"
```

## Troubleshooting

- **scrcpy not found**: ensure `brew install scrcpy` was run and `adb` is on your PATH
- **`avdmanager` not found**: install "Android SDK Command-line Tools" via Android Studio's SDK Manager (Settings > SDK Tools), or skip `avdmanager` and use the manual AVD creation method (Step 2b)
- **`avdmanager` JDK error**: set `JAVA_HOME` to Android Studio's bundled JDK:
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- **`Unable to connect to adb daemon on port: 5037`**: the adb server wasn't running when the emulator started. Run `adb start-server` before launching the emulator
- **ZIP extracts with nested directory**: the Scylla ZIP contains `scylla_avd_arm64/arm64-v8a/` inside. After unzipping, move `arm64-v8a/` up one level (see Step 1)
- **Display 0 shows overlapping content in emulator window**: this is a known compositor bug with the Scylla HWC — use headless mode + scrcpy as described above
- **Setup wizard / "Mark network" dialog blocking the screen after `-wipe-data`**: the OEM setup wizard launches on first boot. Follow [Step 5](#5-dismiss-the-oem-setup-wizard) to dismiss it programmatically
- **Wrong display resolution**: run with `-wipe-data` once to clear cached display config
- **`FATAL: Running multiple emulators with the same AVD`**: kill stale processes and remove lock files:
  ```bash
  pkill -9 -f qemu; rm -f ~/.android/avd/Scylla_AVD.avd/*.lock
  ```
