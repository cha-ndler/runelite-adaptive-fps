# Adaptive FPS

Keeps the RuneLite GPU plugin's FPS target just below the refresh rate of whichever monitor the
client window is currently on.

## The problem

On a variable refresh rate display (G-Sync / FreeSync) used with V-Sync, the frame rate has to stay a
few frames *under* the panel's refresh rate. Cross it and the display leaves its VRR window and falls
back to V-Sync, which queues a frame and adds latency — up to a full frame period.

A single fixed FPS target cannot satisfy two monitors with different refresh rates, and the wider
the gap between them the worse it gets. On a 500Hz + 175Hz pair, a target tuned for the fast panel
(495) sits 320 frames *over* the 175Hz panel's ceiling. Drag the client from one to the other and
you silently land on the wrong side of that boundary, with no indication anything changed.

## What it does

Every two seconds it reads the refresh rate of the monitor the client canvas sits on and writes
`gpu.fpsTarget` to `refresh - headroom` (default 3). Moving the client between monitors re-targets it
within a couple of seconds, with no restart.

| Monitor | Reported refresh | Resulting target |
| --- | --- | --- |
| 500Hz | 500Hz | 495 |
| 240Hz | 240Hz | 237 |
| 175Hz (174.963 actual) | 175Hz | 172 |

The gap widens on faster panels. A fixed 3 FPS margin is comfortable at 175Hz but is only 0.6% at
500Hz, which frame pacing jitter can eat, so by default the gap scales at one frame per 100Hz.

## Why it writes to the GPU plugin instead of limiting frames itself

`GpuPlugin.onConfigChanged` already watches `unlockFps`, `vsyncMode` and `fpsTarget` and re-applies
the target live. Rather than adding a second, competing frame limiter, this plugin just keeps the
existing one pointed at the right number. That keeps the behaviour identical to setting the value by
hand in the GPU plugin's settings.

## Requirements

The GPU plugin must have:

- **Unlock FPS** on — otherwise the client is capped at 50 FPS and the target is irrelevant.
- **Vsync mode: Off** — `GpuPlugin` passes `0` to `setUnlockedFpsTarget` whenever the swap interval is
  non-zero, so `fpsTarget` is ignored entirely when vsync is on or adaptive.

The plugin detects both of these and says so in chat rather than failing silently.

Note that `Vsync mode: On` is itself partly monitor-adaptive — it locks presentation to the current
display's refresh. It is not a substitute here, because it locks *at* the refresh rate rather than
below it, which is the wrong side of the VRR boundary.

## Verified behaviour

Three assumptions were checked empirically on a 240Hz + 175Hz setup rather than assumed:

1. **Java reports both refresh rates correctly.** `GraphicsDevice.getDisplayMode().getRefreshRate()`
   returns 240 and 175. The 175Hz panel is actually 174.963Hz; Java rounds it to 175, which is the
   number we want.
2. **AWT updates the canvas `GraphicsConfiguration` when the window moves.** A canvas in a frame moved
   between monitors reports the new device and its refresh rate — it does not cache the original.
3. **`gpu.fpsTarget` applies live.** Confirmed by reading `GpuPlugin.onConfigChanged` upstream.

Displays that report `REFRESH_RATE_UNKNOWN` (some drivers, virtual displays, remote sessions) are
left alone rather than guessed at.

## Status

Proof of concept, exercised against a real client on a 500Hz + 175Hz pair:

```
Adaptive FPS started
Display \Display1 at 500Hz -> gpu.fpsTarget 495
Display \Display0 at 175Hz -> gpu.fpsTarget 172     (client dragged to the 175Hz panel)
Display \Display1 at 500Hz -> gpu.fpsTarget 495     (dragged back)
```

Retargeting works in both directions within one poll interval, with no exceptions raised.

Known limitations:

- **Restore on stop does not survive client exit.** Disabling the plugin during a session restores
  `gpu.fpsTarget`, but on a full client shutdown the restore write loses a race with RuneLite's
  final config flush, so the last applied target persists instead. Harmless in practice, since the
  persisted value is the right one for whichever monitor you were last on, but it does not do what
  the setting name implies in that case.
- Inert while the GPU plugin's vsync mode is anything but Off, because `GpuPlugin` ignores
  `fpsTarget` entirely when syncing to the display. The plugin reports this in chat rather than
  failing silently, but it cannot fix it for you.
- Only drives the core GPU plugin. 117HD has its own `hd.fpsTarget` and is not handled.

Nothing in the RuneLite Plugin Hub does this today — all 2209 plugin manifests were checked. The
natural long-term home for this is the core GPU plugin itself, which already owns both `fpsTarget`
and `vsyncMode`; a config option there ("target current display refresh minus N") would cover every
user without a second plugin. This repository exists to prove the approach before proposing that
upstream.

## Configuration

| Setting | Default | Purpose |
| --- | --- | --- |
| Headroom below refresh | 3 | Minimum frames to stay under the refresh rate |
| Scale headroom with refresh | on | Widen the gap on faster panels, one frame per 100Hz |
| Minimum target | 60 | Floor, guarding against a nonsense reported refresh rate |
| Fix GPU plugin settings | off | Set the GPU plugin's Vsync mode to Off and Unlock FPS on, instead of only warning that they make the target inert. Applied once per start, and not undone on stop |
| Restore target on stop | on | Hands `gpu.fpsTarget` back to its previous value when the plugin is disabled. Does **not** reliably apply when the whole client exits — see Status |
| Announce changes in chat | on | Prints a message when the target changes |

## Building

```
./gradlew build
```
