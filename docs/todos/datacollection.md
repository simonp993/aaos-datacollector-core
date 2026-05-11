# Data Collection — Open TODOs

Consolidated task list for the DataCollector service.
Last updated: 2026-05-11

---

## 1. Broken Collectors (emitting nothing / wrong data)

- [ ] C2: DriveStateCollector — emits nothing. Fix event emission logic.
- [ ] C5: CarInfoCollector — empty metadata, no trigger. Fix.
- [ ] C7: MediaPlaybackCollector — weird/malformed payloads. Investigate and fix schema.
- [ ] C8: TimeChangeCollector — manual user time change shows `trigger="system"` (wrong), missing `previous` value.
- [ ] C9: TelephonyCollector — seems incorrect, no trigger field. Fix trigger logic.
- [ ] AssistantCollector — signal schema is off and it doesn't work. Fix schema + emission.
- [ ] CPUCollector — not working, not showing in performance dashboard. Investigate.

---

## 2. Architecture / Cross-Cutting (G-series)

- [ ] G3a: Emit-at-startup for infrequent signals — CarInfo, audio_snapshot, and other rarely-changing signals should emit once at boot so we always have a baseline. Requires centralized startup sequencing to avoid burst.
- [ ] G3b: Event correlation — add shared session/correlation ID so events from the same drive session can be linked across collectors.
- [ ] G12: Display on/off action — detect and emit when displays are turned on/off/standby for ALL displays (center, cluster, passenger, rear). Standby state (e.g. music cover) should be distinct from OFF.
- [ ] G4: Multi-user coverage — ensure all collectors query data for user 0 AND user 10. PackageCollector and NetworkStatsCollector done; apply pattern to others.
- [ ] File savings toggle — ability to turn JSONL file writing on/off at runtime (e.g. via system property or intent).
- [ ] File size limiter — verify the log rotation / size cap works on emulator so storage doesn't fill up.
- [ ] G6: Power impact analysis — profile CPU/battery impact. Identify hot collectors, tune intervals.
- [ ] G7: Make events smaller — once structure stable, minimize payload (shorter keys, omit nulls).
- [ ] G8: Dev vs prod flavour — verbose signals (per-frame, per-touch) only in dev or reduced frequency in prod.
- [ ] G9: Payload documentation — add comments to every collector explaining fields, units, and when event fires.
- [ ] G10: Auto-document action names — generate registry/table of all action name strings (annotation processor or build-time script).
- [ ] G11: Combine HeartbeatCollector + SystemCollector? Evaluate if they can merge.
- [ ] G14: FrameRate schema — use efficient encoding (deltas/RLE) if data volume is a concern.
- [ ] G15: Add unit, system, and E2E tests.

---

## 3. New Collectors / Features

- [ ] Bluetooth collector — usage, signal strength, connected devices, profiles active.
- [ ] Network usage reiteration — verify tethering vs WiFi vs cellular separation. Is down/up counted per interface? Is tethering traffic separable? Can we calculate how much tethered devices consume of the car's internet?
- [ ] Connectivity_SignalStrength — verify it covers WiFi, cellular, AND tethering signal strength.
- [ ] Tethering traffic collector — traffic between connected device ↔ car, number of tethered devices, how much internet they consume through the car's cellular.
- [ ] Location provider collector — `adb shell dumpsys location` shows which packages are registered for location updates, provider, interval. Proves causal links (e.g. Mapbox → FLP). Collect periodically or on-demand.
- [ ] FLP/Mapbox correlation chart — dashboard time-series: FLP traffic rate alongside Mapbox traffic rate to show correlation.
- [ ] Display standby/off per display — currently no AppFocusChange when passenger display goes to standby (music cover) or off. Add state transitions for all displays.
- [ ] Navigation fix — two signals issue, needs investigation.
- [ ] Audio channel config — collect stereo/surround/channel configuration.
- [ ] Projection Manager — investigate if CarProjectionManager data is usable/collectible.
- [ ] Kombi warnings — instrument cluster warning display state (BEM warnings?).
- [ ] App startup times (G13) — time-to-first-frame per app. Blocked — discuss with Mathieu.

---

## 4. Needs Tuning

- [ ] C10: SensorBatteryCollector — polling too fast. Reduce interval or switch to on-change.
- [ ] C11: FrameRateCollector — verify display state changes are logged, check payload schema.

---

## 5. Verify on Real Device

- [ ] C12: AudioCollector — works on emulator (mute button not functional on emu).
- [ ] C13: AppLifecycleCollector — works on emulator.
- [ ] C14: NetworkStatsCollector — works on emulator (multi-user verified).
- [ ] C15: TouchInputCollector — works on emulator.
- [ ] C16: MemoryCollector — needs testing. `adb shell am send-trim-memory com.porsche.aaos.platform.telemetry RUNNING_MODERATE`

---

## 6. Completed (Reference)

- [x] G1: Homogeneous payload structure — see `docs/guides/signal-structure-guidelines.md`
- [x] G2: Consistent timestamps — all use epochMillis (Long)
- [x] G5: Stagger cyclic collectors — deterministic offsets (2–9s)
- [x] C1: ConnectivityCollector — fixed, renamed bandwidth fields
- [x] C3: PackageCollector — rewritten: multi-user, chunked (30/chunk)
- [x] C4: ProcessCollector — rewritten but DISABLED (too noisy for fleet)
- [x] C6: VehiclePropertyCollector — batched 60s flush, includes vendor props
- [x] C17: StorageCollector — usagePercent trimmed to 2dp
- [x] C18: PowerStateCollector — boot + state transitions
- [x] C19: NavigationCollector — focus changes with source type
