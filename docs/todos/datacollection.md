# Data Collection — Open TODOs

Consolidated task list for the DataCollector service.


## Before Weekend Drive
- what are uids? Can these uids be mapped to unique package names? 
{
  "schema": ["uid", "user", "packages", "networkType", "apn", "rxBytes", "txBytes"],
  "apps": [
    [10151, 0, ["de.porsche.datacollector.agent"], "ethernet", "apn2_oem", 87616, 46033],
    [1310124, 13, ["com.mapbox.porsche"], "ethernet", "apn2_oem", 44385, 22412]
  ]
}

- check files produced, and delete again for fresh start

- somehow handle that not to much data can fill up the storage (zip, delete, etc.)

- Audio muting is not sending a signal, audio volume changes do. 

- Get button press jokerkey: 
RSI Jokerkey — The jokerkey data flows through ESO's RsiJokerKeyConnectorImpl which is part of the CarInterface app (de.eso.porsche.carinterface). It uses the RSI path /Jokerkey$/actions and logs:
JokerKeyFunctionRegistration events (which functions are registered to which key)
JokerKeyAssignment (current mapping: JOKER_KEY_1=IntRecuOffOn, JOKER_KEY_2=ChangeSource)
Button press states (e.g. state=PRESSED)
What we already capture indirectly: Our AppLifecycleCollector picks up the JokerKeyPopupActivity as a focus change when the popup appears — so we see when the jokerkey was long pressed to change the assignment, but not which key or which function was triggered.
To get actual jokerkey press events, we'd need to subscribe to the RSI resource /Jokerkey$/actions via the vehicle-connectivity module. That signal is not in our current RSI subscription list. Want me to check what RSI signals we currently subscribe to, and look into adding the jokerkey one?

- See if App_ExitDetected works






## During weekend drive
- Test Tethering separation in real car: It's NOT definitively impossible on a real car with cellular. The BPF forwarding stats (mBpfStatsMap) in dumpsys tethering are only populated when traffic is actually being forwarded through an upstream. On your WiFi-only car, there's no NAT forwarding to measure (clients on the hotspot are local-only or go through WiFi which merges). On a car with cellular as upstream, the BPF tethering offload should populate those forwarding counters — they'd represent exactly the internet-forwarded portion. I updated the comment to be more nuanced about this. Also, READ_NETWORK_USAGE_HISTORY + platform signature might allow calling the hidden NetworkStatsManager.querySummaryForDevice() with the tethering interface type. This is worth revisiting once you have a cellular-equipped car to test on.
- Full analysis of logs ai based, how can this be set up? 




## After Weekend Drive
- map uid of network to pids? 
- rewrite the architecture, so that every user can query its content. To avoid dumpsys polling and get real-time callbacks, part of the data collection
logic should run in the active user context (user-13). This could be achieved by:
A bound service component running as the current user that queries
CarAppFocusManager, LocationManager, etc. on behalf of the user-0 host service
Communication between user-0 host and user-13 satellite via AIDL or ContentProvider
This would eliminate the need for dumpsys parsing and enable instant event delivery.
But why is it working for things like media collector or others? 
- make sure that not all send at the same 60s
- Now lets reconsider Network again: 
Is 5G differentiation possible? Key 5G Signal Metrics:
RSRP (Reference Signal Received Power - dBm): Measures the signal strength from a single cell base station. This is the most crucial metric for gauging coverage.
SINR (Signal to Interference plus Noise Ratio - dB): Measures the clarity of the signal compared to background noise and interference. A higher SINR determines higher modulation (like 1024-QAM) and faster speeds.
RSRQ (Reference Signal Received Quality - dB): Indicates the overall quality of the received signal, heavily influenced by network load and interference.
RSSI (Received Signal Strength Indicator - dBm): The total received power, including desired signal, interference, and noise
make sure operatorName in SignalStrenght cellular contains a string an not only "" (I know it is empty when on wifi, but please chck, that it is no bug)
- Can I get all the buttons there are? 
- Add listener for location and voice listener (drop down)
- save the package list and only send differences at startup (including a message no differences or empty list if nothing changed)
-  Is the bluetooth connection also collecting for other users but 14? so would 10, 11, 12, or so work out of the box 
- [ ] CarUserManager collector — emit events on user profile lifecycle: driver switch, guest session start/stop, user creation/removal. Guaranteed API on all AAOS builds.
- [ ] C9: TelephonyCollector — seems incorrect, no trigger field. Fix trigger logic.I have my phone connected and am starting a call when you say it.- [ ] LocationCollector - Test if working (e.g. sometimes display on off, other weird stuff)
- are vhal changes sent correctly? 
- [ ] AssistantCollector — signal schema is off and it doesn't work. Fix schema + emission.
- when a new song is played (becauese old one is done) there are two events Media_PositionJumped and Media_TrackChanged, in this case (when Media_TrackChanged is send) we want no postion jumped. 
- [ ] C8: TimeChangeCollector — manual user time change shows `trigger="system"` (wrong), missing `previous` value.
- Iterate on the trigger, they are not correct always
- When switching from carplay to bluetooth I get no new Bluetooth device list, even though my phone was connected = false in the lsat payload and that must have changed when switching from carplay to bluetooth. 
- HUD turn on off and brightness not working
- Integrate the storage usage shown in settings: Music and audio, Other apps, Files, System
- Create a list of all actions and what it sends that stays automatically up to date
- File savings toggle — ability to turn JSONL file writing on/off at runtime (e.g. via adb system property). -> ADB
- look at https://perfetto.dev/
- [ ] G7: Make events smaller — zip
- [ ] G8: Dev vs prod flavour — verbose signals (per-frame, per-touch) only in dev or reduced frequency in prod.
- [ ] G9: Payload documentation — add comments to every collector explaining fields, units, and when event fires.
- [ ] G10: Auto-document action names — generate registry/table of all action name strings (annotation processor or build-time script).
- [ ] G15: Add unit, system, and E2E tests.
- [ ] G3a: Emit-at-startup for infrequent signals — CarInfo, audio_snapshot, and other rarely-changing signals should emit once at boot so we always have a baseline. Requires centralized startup sequencing to avoid burst.
- [ ] G3b: Event correlation — add shared session/correlation ID so events from the same drive session can be linked across collectors.
- [ ] Audio channel config — collect stereo/surround/channel configuration.
- [ ] Projection Manager — investigate if CarProjectionManager data is usable/collectible.
- [ ] Kombi warnings — instrument cluster warning display state (BEM warnings?).
- [ ] App startup times (G13) — time-to-first-frame per app. Blocked — discuss with Mathieu.
- [ ] System properties collector (`getprop`) — snapshot key ro.* and persist.* properties at boot (Android version, build fingerprint, boot reason, AAOS extensions version). One-shot at startup + on-demand.
clean up documentation







Cross-User Compatibility Summary

- check this: Q1: Your app is headless but it's not a core system service. User 0 on AAOS runs things like system_server, SurfaceFlinger, CarService — services that must exist before any human user logs in. Your telemetry app is a user-installed (or profile-installed) privileged app — it's signed with the platform key for permissions, but it's deployed per-profile.
The reason it works on user 14 specifically: your app collects data in the context of that driver session. If you ran it as user 0, you'd lose the ability to see user-14-specific things (app lifecycle for that user's apps, media sessions, focus changes, etc.). Running as user 14 and borrowing user 0's context only for hardware access (GPS, VHAL) gives you the best of both worlds.
It still captures cross-user events because the system APIs you use (Car API, LocationManager via user-0 context, etc.) expose system-wide hardware state regardless of which user queries them.

Risk	Collectors
None (20)	AppLifecycle, Location, AppExit, Bluetooth, DisplayState, FrameRate, IoCollector, UserState, SelfMonitor, SensorBattery, SystemMemory, SystemCpu, Storage, Package, Process, TouchInput, CarInfo, DriveState, VehicleProperty, PowerState
Low (7)	Audio, Memory, Connectivity, Telephony, Assistant, Navigation, TimeChange, NetworkStats
Medium (1)	MediaPlaybackCollector
The only real concern: MediaPlaybackCollector
It actively re-registers listeners on ACTION_USER_SWITCHED. During the switch there's a brief gap where media events could be missed, and it clears previousPlaybackState/previousMetadata on switch — so the first post-switch event won't have a previous diff.

Everything else is fine because:
Vehicle/hardware collectors (VHAL, GPS, sensors, display, power) — system-wide hardware bus, no user dependency
AppLifecycle, AppExit, Package, Process — already use cross-user APIs (getAllRootTaskInfos, getHistoricalProcessExitReasons per user, getInstalledPackagesAsUser, dumpsys)
Bluetooth — uses registerReceiverAsUser(ALL) explicitly
Network/Connectivity — hardware-level, system-wide
TimeChange — uses plain registerReceiver but time changes are system-wide broadcasts, so it still works
The codebase is well-architected for multi-user — discoverUserIds(), createContextAsUser(), and registerReceiverForAllUsers patterns are used consistently where needed.