# BLE Debug Log — Design

**Date:** 2026-06-29
**Status:** Approved
**Context:** Issue #4 follow-up. The eBike does not connect on Karoo; only the Karoo's
native "audio source" advertising is visible (it shows even without the plugin installed),
so our solicitation advertising may be failing or blocked. `adb logcat` is awkward on a
Karoo, so we need on-device diagnostics.

## Goal

An activatable, on-device BLE debug log so users (and issue reporters) can see what the
BLE layer is doing and share it — without adb.

## Components

### `BleDebugLog` (new, `de.dxmedia.bosch.ldi.ble`)
In-memory ring buffer of the most recent log lines (cap: 300).
- `@Volatile var enabled: Boolean` — default `false`. When `false`, `add` only forwards to logcat.
- `i(msg)`, `w(msg)`, `e(msg, t: Throwable? = null)` — forward to `android.util.Log` with tag
  `BleManager` AND, when `enabled`, append a timestamped line to the buffer.
- `entries: StateFlow<List<String>>` — live snapshot for the UI.
- `clear()`.
- Thread-safe: appends are `synchronized`; the buffer is published via `MutableStateFlow`.
- Line format: `mm:ss.SSS L message` (relative wall-clock via `System.currentTimeMillis`,
  level letter I/W/E). Throwables appended as a short class + message line.

### `BleManager` rewiring
Replace the existing `Log.i/w/e(TAG, …)` calls with `BleDebugLog.i/w/e(…)`. Pure mechanical
swap — no control-flow change. This captures the already-rich events: advertising
started / `FAILED: <reason>`, "LE advertising not supported", `BLUETOOTH_ADVERTISE` not
granted, GATT-server connect, service discovery, MTU, CCCD, disconnects, watchdog.

### `DebugSettings` (new, `de.dxmedia.bosch.ldi.util`)
Thin wrapper over the existing plain prefs file `app_prefs` (same file `LocaleHelper` uses).
- `isBleDebugEnabled(context): Boolean` (key `ble_debug_enabled`, default false)
- `setBleDebugEnabled(context, Boolean)`
Loaded into `BleDebugLog.enabled` at startup of both `MainActivity.onCreate` and
`BoschLiveDataService.onCreate`.

### `BleLogScreen` (new, route `ble_log`)
Scrollable, monospace, small-font list of `BleDebugLog.entries` (collected live), newest at
bottom, auto-stick to bottom. TopBar with back nav + actions: **Clear**, **Copy** (to
clipboard), **Share** (`ACTION_SEND`, `text/plain`). Copy/Share wrapped in `runCatching` so a
Karoo without a share target never crashes. Screenshot/read is the expected primary path.

### Settings + navigation
- `SettingsScreen`: a `Switch` "Enable BLE debug" (bound to `DebugSettings`, flips
  `BleDebugLog.enabled` immediately) and a row "View BLE log" → navigates to `ble_log`.
- `MainActivity` NavHost: add `composable("ble_log") { BleLogScreen(...) }`; pass
  `onNavigateBleLog` into `SettingsScreen`.

## Data flow
BleManager event → `BleDebugLog.add` (buffer only if `enabled`; always logcat) →
`MutableStateFlow` → `BleLogScreen` `collectAsState` → list. Copy/Share read the snapshot.

## Error handling
Buffer bounded (drop oldest beyond 300). Clipboard/share in `runCatching`. Toggle/pref reads
default to false on any failure.

## Testing
Unit test `BleDebugLog` (pure JVM, `isReturnDefaultValues` stubs `Log`):
- disabled → buffer stays empty; enabled → captures.
- ring buffer caps at 300 (oldest dropped).
- `clear()` empties.

## Out of scope (YAGNI)
No level-filter UI, no file export, no remote upload, no log persistence across process death.
