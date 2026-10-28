# Airplane Mode Recovery (this fork)

Based on **SFA 1.14.3** + root airplane toggle when URL-test fails.

## Behaviour

- A **service-level watchdog** (`AirplaneModeRecovery`, started/stopped by `BoxService`)
  listens to the sing-box groups stream. It does **not** depend on the app UI, so it keeps
  working with the screen off or the app in the background.
- Watches **`urltest`** groups only (they are the ones that run periodic tests; note that
  `urltest` groups are never "selectable" in libbox).
- Failure = every outbound of every `urltest` group has `urlTestDelay <= 0` or `>= 10000`.
  Any healthy outbound resets the failure timer.
- The groups stream is opened with a **5 s tick** (the UI keeps its 1 s). The threshold is
  measured in **time**, so it does not depend on the tick: failures must last **threshold − 1
  seconds** (default 5 → 4 s; this is exactly what 1 Hz snapshots used to mean). Then the watchdog:
  1. checks root is available (otherwise backs off for one cooldown),
  2. **re-runs the URL test** of the watched groups, waits for a stream snapshot taken after it
     (at most one tick + 1.5 s) and aborts if any outbound now succeeds (avoids toggling on a
     stale/empty test history),
  3. toggles airplane mode via root and **always switches it back off** (verified, with retries).
- Toggle commands (each step is verified by reading `airplane_mode_on`):
  - Android 11+: `cmd connectivity airplane-mode enable|disable`
  - fallback / older Android:
    ```bash
    settings put global airplane_mode_on 1
    am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
    # ~1.75s
    settings put global airplane_mode_on 0
    am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
    ```
- Guards:
  - **Startup grace** of 20 s (test history is empty right after the service starts); it ends
    early as soon as one URL test succeeds.
  - **Cooldown** default **8** seconds (setting range **1–900**), measured from the end of a
    toggle. Effective minimum is 8 s so the modem can re-register.
  - **No backoff**: the failure threshold and the cooldown are used exactly as entered, for every
    attempt (8 → 8 → 8 → 8 s with the default). Nothing doubles, so the watchdog reacts at the same
    speed however long an outage lasts. Trade-off: if the real problem is not the radio (server
    down, blocked), the modem is cycled once per cooldown until a URL test passes again; raise the
    cooldown if that costs too much battery.
  - **Doze**: nothing is evaluated while the device is in deep idle (the core is paused and no
    URL test runs, so snapshots carry no news).
  - Nothing happens while the phone is already in airplane mode.
  - If the feature is switched on/off in settings, the watcher connects/disconnects at once
    (no service restart needed).

## Active only while the service is on

The watcher runs only while the sing-box core is up and the service is switched on
(`BoxService.serviceWanted`):

- Started after the core is up (not if the user already pressed stop while it was starting);
  stopped on user stop, core teardown (start retries), `serviceStop()` (core closed) and
  `onDestroy`. After a `serviceStop()` it resumes with a fresh startup grace once the core is
  reloaded.
- A recovery that is **in flight** (probe or toggle) is cancelled when the service stops or the
  feature is switched off, and `runRecovery` re-checks that the service is still on before asking
  for root and before the toggle. If the cancel lands in the middle of a toggle, airplane mode is
  still switched back off (`NonCancellable`).
- With the service off: no groups stream, no URL-test probes, no root calls, no airplane toggle.

## Dashboard indicator

The home dashboard has a **Watchdog** card (local dashboard only, always shown, also with the
service off), styled like the other dashboard cards:

| Row | Values |
|---|---|
| Auto restart | **On** (switch on, service running) · **On · standby** (switch on, service off) · **Off** |
| Airplane recovery | same, plus **On · needs root** when the switch is on but root is not granted |
| Root access | **Granted** · **Not granted** · **Checking…** · **Not checked** (same automatic check as above) |

The two switches are read again each time the dashboard returns to the foreground.

## Settings (Service screen)

- Enable airplane recovery (turning it on also asks for root right away)
- **Root access** indicator: *Granted* / *Not granted* / *Checking…* / *Not checked yet*. It is
  refreshed **automatically every time the app is opened** (`MainActivity.onStart` →
  `RootClient.refreshRootState()`), so there is no "Recheck" any more. A live root shell is only
  confirmed (never closed, the watchdogs may be using it); a missing or non-root shell is reopened,
  which also picks up a grant given in the root manager since last time (and shows the root
  manager's prompt the first time). A **Check** button is kept only while root is not granted, to
  re-ask at once without reopening the app.
- Failure threshold (1–20, default 5): about this many seconds of total failure
- Cooldown seconds (1–900, default 8)
- **Apply** button: the two numbers are only saved when you press Apply (or the keyboard's Done
  key). Invalid values are flagged and Apply stays disabled. The running watcher picks the new
  values up immediately through the settings listener (`AirplaneModeRecovery.applyConfig`): the
  threshold is read on every update and a cooldown that is already running is recomputed with the
  new length. No service restart needed.

The screen shrinks above the keyboard (`imePadding`) and scrolls the focused field into view, so the
keyboard no longer covers the number fields.

## Config requirement

Wrap your proxy in a `urltest` group, e.g. interval `30s`, `final` = that group.
A `selector` group on its own is **not** watched (it has no periodic test results).

## Power / CPU / RAM profile

Where the cost is, and the knob for each part:

| Part | Cost | Knob |
|---|---|---|
| Groups stream (always on while enabled) | one snapshot per tick: Go walks all outbounds, JNI proxy objects, GC | `WATCH_INTERVAL_MS` (5 s). Healthy snapshots stop iterating at the first passing outbound |
| URL tests (the real network cost) | N requests per round per group | the config's `interval`, `tolerance` and the **number of outbounds in the urltest group** |
| Probe before a toggle | one forced round, up to 12 s | only runs once a failure lasted the threshold |
| Airplane toggle | modem re-registration (the expensive one) | cooldown (constant, no backoff) |
| Root shell | one resident `su` process after first use | none (shared with the other root features) |

Detection latency ≈ time until a test round records the failure (**the config's `interval`**,
usually the dominant term) + threshold − 1 s + tick (≤ 5 s) + probe (≤ 12 s) + snapshot wait
(≤ 6.5 s). Lower `interval` = faster reaction but more test traffic; a smaller urltest group is
the cheapest way to cut cost without losing reaction time.

## Debugging

`adb logcat -s AirplaneRecovery` shows: `watching url-test groups (tick 5s)`,
`config applied: threshold=N cooldown=Ms` (after pressing Apply),
`every url test is failing, recovery in Ns if it persists`,
`executing airplane toggle: ... (attempt N since last good test)`, `airplane toggle finished`,
`url test passing again, attempt counter reset`, and the reasons for any skip
(`root not available`, `connection recovered before toggle`, ...).

## Build

- ABI: **arm64-v8a only**
- CI downloads `libbox.aar` 1.14.3 automatically
- Requires root on device for the feature to act

## Survives service death

See `AUTO_RESTART.md`: the service is restarted by `START_STICKY` / a system alarm, and starting the
service starts this watchdog again.
