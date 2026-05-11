# Data Collection — Open TODOs

Consolidated task list for the DataCollector service.
Last updated: 2026-05-11

---


## 1. Broken Collectors new features (emitting nothing / wrong data)

Before Weekend Drive

- make sure operatorName in SignalStrenght cellular contains a string an not only ""

- [ ] CarWatchdogManager collector — monitor system health: unresponsive services, resource overuse notifications, I/O overuse stats. Guaranteed API. --> Do we get app crashes? 

- [ ] CPUCollector — `/proc/stat` read fails with EACCES (SELinux denies even system-priv on AAOS 15). Needs alternative: `dumpsys cpuinfo`, `top -bn1`, or own-process `/proc/self/stat`.

- [ ] Network usage reiteration — verify tethering vs WiFi vs cellular separation. Is down/up counted per interface? Is tethering traffic separable if possible? Can we calculate how much tethered devices consume of the car's internet? Connectivity_SignalStrength — verify it covers WiFi, cellular, AND tethering signal strength if possible? But according to our discussions, not all is possible? 

- [ ] Location provider collector — `adb shell dumpsys location` shows which packages are registered for location updates, provider, interval. Proves causal links (e.g. Mapbox → FLP). Collect periodically or on-demand.

- VHAL Changes

During weekend drive
- Test Tethering separation in real car: It's NOT definitively impossible on a real car with cellular. The BPF forwarding stats (mBpfStatsMap) in dumpsys tethering are only populated when traffic is actually being forwarded through an upstream. On your WiFi-only car, there's no NAT forwarding to measure (clients on the hotspot are local-only or go through WiFi which merges). On a car with cellular as upstream, the BPF tethering offload should populate those forwarding counters — they'd represent exactly the internet-forwarded portion. I updated the comment to be more nuanced about this. Also, READ_NETWORK_USAGE_HISTORY + platform signature might allow calling the hidden NetworkStatsManager.querySummaryForDevice() with the tethering interface type. This is worth revisiting once you have a cellular-equipped car to test on.


After weekend drive
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



---

## 2. Architecture / Cross-Cutting (G-series)

Before Weekend Drive
- [ ] G4: Multi-user coverage — ensure all collectors query data for user 0 AND user 10. PackageCollector and NetworkStatsCollector done; apply pattern to others.
- [ ] File savings toggle — ability to turn JSONL file writing on/off at runtime (e.g. via adb system property).
- [ ] File size limiter — verify the log rotation / size cap works on emulator so storage doesn't fill up.

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



