# Collector Behavior and Limitations

Runtime observations and known limitations of each collector, documented to help developers understand what is and isn't captured.

---

## AppLifecycleCollector

Monitors app activity transitions (resumed/paused) across all AAOS users and displays.

### How It Works

- Uses `IActivityTaskManager.getAllRootTaskInfos()` via reflection (platform-signed `@SystemApi`).
- Polls every 500ms, comparing each task's `topActivity` against the last known value per `taskId`.
- Emits `AppLifecycle_Resumed` and `AppLifecycle_Paused` telemetry events with `package`, `class`, and `displayId`.
- Covers **all users** (user 0 system services + user 10 foreground apps) and **all displays** (center, instrument cluster, passenger, rear-passenger, virtual).

### What It Detects

| Signal | Detected? | Details |
| --- | --- | --- |
| Activity launched / brought to front | Yes | Any `topActivity` change per task — touch, voice, intent, system-initiated |
| Activity replaced by another in same task | Yes | New `topActivity` triggers Paused (old) + Resumed (new) |
| User 0 system activities | Yes | `getAllRootTaskInfos()` returns tasks for all users |
| User 10 foreground activities | Yes | Same API, cross-user |
| Display source | Yes | `displayId` included in every event payload |

### What It Does NOT Detect

| Signal | Why |
| --- | --- |
| **Fragment navigation within an Activity** | Only `topActivity` (the Activity class) is observed. Fragment swaps inside an Activity are invisible to the task API. |
| **Background services** | `getAllRootTaskInfos()` returns activity task stacks only, not bound/started services. |
| **Non-top activities in a task's back stack** | Only the topmost Activity per task is read. Activities below it are not reported. |
| **Task destruction / app kill** | When a task disappears from the list, no explicit "stopped" event is emitted. The task simply stops appearing in subsequent polls. |

### Fragment Navigation Blind Spot (Car Settings Example)

AAOS Car Settings (`com.android.car.settings`) uses a **dual-pane, single-Activity architecture**:

```
Task (displayId=0):
  → CidPlaceholderActivity    ← topActivity (right content pane, RESUMED)
  → HomepageActivity           ← below (left navigation menu)
```

When a user taps a section in Settings:

- **Some sections launch a dedicated sub-Activity** (e.g., `CarSettingActivities$NetworkAndInternetActivity`, `$BluetoothSettingsActivity`, `$UnitsSettingsActivity`). These replace `CidPlaceholderActivity` as `topActivity` and **are detected** by AppLifecycleCollector.

- **Other sections perform a Fragment swap** inside `CidPlaceholderActivity` (e.g., Privacy, Security, Accounts, Apps, Date & Time). The Activity class stays the same, so **these are not detected**.

This is not specific to Settings — any app that uses single-Activity + Fragment navigation (which is common in modern Android) will exhibit the same behavior. Only actual Activity transitions are visible to the task stack API.

#### Car Settings Sections with Dedicated Activities

Detected (launches a new Activity):

- Network & Internet, Bluetooth, Wi-Fi, Display, Sound, Storage, Notifications, About, Units, Language, Accessibility, Location, Mobile Network, Text-to-Speech, Profiles

Not detected (Fragment swap within `CidPlaceholderActivity`):

- Privacy, Security, Accounts, Date & Time, and other sections that render inline

> This distinction is an AOSP architectural decision in `com.android.car.settings`, not an artifact of mock vs. real data sources.

### Future Improvements

| Improvement | Approach | Complexity |
| --- | --- | --- |
| Event-driven instead of polling | Register `ITaskStackListener` via `ActivityTaskManager` (requires compiling against hidden API stubs in `app/libs/stubs-classes/`) | Medium |
| Fragment-level navigation | `AccessibilityService` observing `TYPE_WINDOW_CONTENT_CHANGED` events across all apps | Medium |
| Background service tracking | `ActivityManager.getRunningServices()` / `getRunningAppProcesses()` — deprecated but functional for system apps | Low |
| Task destruction events | Diff current `taskId` set vs. previous poll; missing IDs = killed tasks | Low |
