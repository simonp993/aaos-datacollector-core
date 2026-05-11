# Data Collection — Open TODOs

Consolidated task list for the DataCollector service.
Last updated: 2026-05-11

---


## 1. Broken Collectors new features (emitting nothing / wrong data)

Before Weekend Drive
- [ ] Bluetooth collector — usage, signal strength, connected devices, profiles active.
- [ ] AssistantCollector — signal schema is off and it doesn't work. Fix schema + emission.
- [ ] C9: TelephonyCollector — seems incorrect, no trigger field. Fix trigger logic.
- [ ] LocationCollector - Test if working
- [ ] G12: Display on/off action — detect and emit when displays are turned on/off/standby for ALL displays (center, cluster, passenger, rear). Standby state (e.g. music cover) should be distinct from OFF.
- [ ] Network usage reiteration — verify tethering vs WiFi vs cellular separation. Is down/up counted per interface? Is tethering traffic separable? Can we calculate how much tethered devices consume of the car's internet?
- [ ] Connectivity_SignalStrength — verify it covers WiFi, cellular, AND tethering signal strength.
- [ ] Tethering traffic collector — traffic between connected device ↔ car, number of tethered devices, how much internet they consume through the car's cellular.
- [ ] Location provider collector — `adb shell dumpsys location` shows which packages are registered for location updates, provider, interval. Proves causal links (e.g. Mapbox → FLP). Collect periodically or on-demand.
- [ ] Navigation fix — two signals issue, needs investigation.
- [ ] CarUserManager collector — emit events on user profile lifecycle: driver switch, guest session start/stop, user creation/removal. Guaranteed API on all AAOS builds.
- [ ] CarWatchdogManager collector — monitor system health: unresponsive services, resource overuse notifications, I/O overuse stats. Guaranteed API.
- [ ] CPUCollector — `/proc/stat` read fails with EACCES (SELinux denies even system-priv on AAOS 15). Needs alternative: `dumpsys cpuinfo`, `top -bn1`, or own-process `/proc/self/stat`.

After weekend drive
- [ ] C8: TimeChangeCollector — manual user time change shows `trigger="system"` (wrong), missing `previous` value.


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



