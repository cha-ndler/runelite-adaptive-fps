# Adaptive FPS

Keeps the RuneLite GPU plugin's FPS target just below the refresh rate of whichever monitor the
client window is currently on.

![The client moved from a 500Hz monitor to a 175Hz one, with the plugin announcing the new FPS target in chat](docs/demo.gif)

*Dragging the client between a 500Hz and a 175Hz display. The target follows the window, and the
change is announced in game chat.*

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
`gpu.fpsTarget` a little below it. Moving the client between monitors re-targets it within a couple
of seconds, with no restart.

## How far below the refresh rate

A flat number does not travel across refresh rates. Three frames is 0.85ms of slack at 60Hz but
0.012ms at 500Hz — and frame pacing jitter is an absolute time, not a share of the refresh interval,
so the fast panel ends up with no real margin at all.

The default instead uses `refresh - refresh² / 3600`, which is what NVIDIA's own limiter does: it
reproduces the documented **-1 FPS at 60Hz** and **-16 FPS at 240Hz** exactly. That formula is
equivalent to holding a constant **~0.3ms frametime margin** at every refresh rate, which is the
quantity that actually matters.

| Refresh | Headroom | Target | Frametime margin |
| --- | --- | --- | --- |
| 60Hz | 1 | 59 | 0.28 ms |
| 100Hz | 3 | 97 | 0.31 ms |
| 120Hz | 4 | 116 | 0.29 ms |
| 144Hz | 6 | 138 | 0.30 ms |
| 175Hz | 9 | 166 | 0.31 ms |
| 240Hz | 16 | 224 | 0.30 ms |
| 360Hz | 36 | 324 | 0.31 ms |
| 500Hz | 69 | 431 | 0.32 ms |

Set **Headroom** to `Fixed` if you would rather pick the number yourself.

### Where those numbers come from

Blur Busters' [G-SYNC 101](https://blurbusters.com/gsync/gsync101-input-lag-tests-and-settings/) is
the standard reference for why the cap has to sit below the maximum refresh rate at all: once the
frame rate reaches it, there is nothing left for the variable refresh rate to track, and the display
reverts to V-Sync behaviour or tearing. It recommends a minimum of 3 FPS below refresh, and notes
that a larger margin is wanted as refresh rates climb.

The scaling itself comes from what NVIDIA's own limiter does. In the
[G-SYNC 101 discussion thread](https://forums.blurbusters.com/viewtopic.php?t=3441), jorimt — the
article's author — describes the automatic limit applied by Low Latency Mode Ultra / Reflex as
using **-1 FPS at 60Hz** and **-16 FPS at 240Hz**, scaling with refresh rate to absorb frametime
variance.

Both of those points are reproduced exactly by `refresh² / 3600`:

- 60² / 3600 = **1**
- 240² / 3600 = **16**

which is why this plugin uses that expression rather than a hand-picked curve.

One caveat worth stating plainly: the two published data points are 60Hz and 240Hz, so anything
faster is an **extrapolation**. It is a well-behaved one — the constant ~0.3ms frametime margin
holds across the whole range rather than diverging — but a 500Hz target of 431 is inferred, not
measured. `Fixed` is there for anyone who would rather not rely on that.

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
| Headroom | Automatic | How far below the refresh rate to cap. Automatic suits any panel; Fixed uses the number below |
| Fixed headroom | 3 | Frames below the refresh rate. Ignored unless Headroom is Fixed |
| Minimum target | 30 | Never cap below this, whatever the display reports |
| Fix GPU plugin settings | off | Turns the GPU plugin's vsync off and Unlock FPS on, which the target needs to work. Switching it back off restores them |
| Restore target on stop | on | Hands `gpu.fpsTarget` back when the plugin is disabled. Not reliable on full client exit — see Status |
| Announce in chat | on | Chat message when the target changes |

## Building

```
./gradlew build
```
