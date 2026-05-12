# Data Collection — Open TODOs

Consolidated task list for the DataCollector service.
Last updated: 2026-05-11

---


## 1. Broken Collectors new features (emitting nothing / wrong data)

Before Weekend Drive
- Multi-user coverage — ensure all collectors query data for user 0 AND user 10, 11, 12, 14, etc. The current system has for example different ids than the previous one: 
Users:
        UserInfo{0:Driver:813} running
        UserInfo{10:Julius Cäsar:412}
        UserInfo{11:#defaultUser:412}
        UserInfo{13:G3_V_ECE:412} running
When analyzing all collectors, do you see a possible issue in that regard with any of them? 
- delete the app and logs
- check if we get the software version? Check if we get anything about jokerkey rsi? 

- Change the frequency of Display_StateSnapshot and Display_BrightnessSnapshot, they both are send every minute it seems and not only at startup
- How is the VHAL collection currently implemented? On change, frequency, batched? The goal is to have some onchange and some with frequency. E.g. fanspeed is onchange, but speed is frequency every 5s. 
- See if App_ExitDetected works
- Audio muting does not work.
- File size limiter — verify the log rotation / size cap works on emulator so storage doesn't fill up. Or implement a delete mechanism, that deletes files that are older than 7 days for example. What would you suggest?

MITTWOCH
- [ ] Location provider collector — `adb shell dumpsys location` shows which packages are registered for location updates, provider, interval. Proves causal links (e.g. Mapbox → FLP). Collect periodically or on-demand.

- Now lets reconsider Network again: 
Is 5G differentiation possible? Key 5G Signal Metrics:
RSRP (Reference Signal Received Power - dBm): Measures the signal strength from a single cell base station. This is the most crucial metric for gauging coverage.
SINR (Signal to Interference plus Noise Ratio - dB): Measures the clarity of the signal compared to background noise and interference. A higher SINR determines higher modulation (like 1024-QAM) and faster speeds.
RSRQ (Reference Signal Received Quality - dB): Indicates the overall quality of the received signal, heavily influenced by network load and interference.
RSSI (Received Signal Strength Indicator - dBm): The total received power, including desired signal, interference, and noise
make sure operatorName in SignalStrenght cellular contains a string an not only "" (I know it is empty when on wifi, but please chck, that it is no bug)

- Full analysis of logs ai based, how can this be set up? 

During weekend drive
- Test Tethering separation in real car: It's NOT definitively impossible on a real car with cellular. The BPF forwarding stats (mBpfStatsMap) in dumpsys tethering are only populated when traffic is actually being forwarded through an upstream. On your WiFi-only car, there's no NAT forwarding to measure (clients on the hotspot are local-only or go through WiFi which merges). On a car with cellular as upstream, the BPF tethering offload should populate those forwarding counters — they'd represent exactly the internet-forwarded portion. I updated the comment to be more nuanced about this. Also, READ_NETWORK_USAGE_HISTORY + platform signature might allow calling the hidden NetworkStatsManager.querySummaryForDevice() with the tethering interface type. This is worth revisiting once you have a cellular-equipped car to test on.



After weekend drive
- Can I get all the buttons there are? 
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



---

## 2. Architecture / Cross-Cutting (G-series)


After Weekend Drive
- [ ] G7: Make events smaller — zip
- [ ] G8: Dev vs prod flavour — verbose signals (per-frame, per-touch) only in dev or reduced frequency in prod.
- [ ] G9: Payload documentation — add comments to every collector explaining fields, units, and when event fires.
- [ ] G10: Auto-document action names — generate registry/table of all action name strings (annotation processor or build-time script).
- [ ] G15: Add unit, system, and E2E tests.
- [ ] G3a: Emit-at-startup for infrequent signals — CarInfo, audio_snapshot, and other rarely-changing signals should emit once at boot so we always have a baseline. Requires centralized startup sequencing to avoid burst.
- [ ] G3b: Event correlation — add shared session/correlation ID so events from the same drive session can be linked across collectors.

---

## 3. New Collectors / Features


After Weekend Drive
- [ ] Audio channel config — collect stereo/surround/channel configuration.
- [ ] Projection Manager — investigate if CarProjectionManager data is usable/collectible.
- [ ] Kombi warnings — instrument cluster warning display state (BEM warnings?).
- [ ] App startup times (G13) — time-to-first-frame per app. Blocked — discuss with Mathieu.
- [ ] System properties collector (`getprop`) — snapshot key ro.* and persist.* properties at boot (Android version, build fingerprint, boot reason, AAOS extensions version). One-shot at startup + on-demand.
clean up documentation



