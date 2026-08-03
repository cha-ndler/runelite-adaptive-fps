# Changelog

## 1.1.0

Adds 117 HD support. Whichever renderer is enabled is now detected automatically,
and both are driven the same way.

### Added

- **117 HD is supported.** It uses the same three config keys as the core GPU
  plugin (`fpsTarget`, `unlockFps`, `vsyncMode`) and the same rule for when a
  target is honoured, so it needs no separate handling and no new setting — the
  renderer is detected, not selected. RuneLite treats the two as mutually
  exclusive, so exactly one is ever active.
- The vsync and Unlock FPS warnings, and the opt-in fix, now name whichever
  renderer you are running. Worth knowing: 117 HD defaults to `Unlock FPS` off
  and `Vsync Mode: Adaptive`, so a stock install fails both conditions where a
  stock GPU plugin install passes them.

### Fixed

- **Disabling the plugin while 117 HD was active overwrote your frame cap.**
  Shutdown restored the *core GPU plugin's* stored FPS target without checking
  which renderer was running, so simply enabling and then disabling Adaptive FPS
  left a 117 HD user capped at a number belonging to a plugin that was not even
  enabled, until 117 HD next recomputed its own.
- **Disabling the plugin while vsync was on capped the client at the stored FPS
  target.** Shutdown handed back that target unconditionally, but a renderer
  syncing to the display sets `0`, not its target. Affected the GPU plugin too.
- Restoring a borrowed setting the user had never set wrote an explicit value
  rather than clearing the key. Rare on the GPU plugin; the normal case on
  117 HD, where both defaults differ from what the fix sets.
- Settings borrowed from one renderer are no longer lost when switching to the
  other with the fix enabled. Both are given back.

### Changed

- The target is re-applied on every poll rather than only when it changes, so it
  recovers from anything that overwrites it. Both renderers push their own value
  back from an event handler whose ordering against this plugin's is undefined,
  and the client offers no way to read the current target back, so this is the
  only way to be certain. Chat and log messages still appear only on a change.

## 1.0.0

Keeps RuneLite's FPS target just below the refresh rate of whichever monitor the
client window is on, so a variable refresh rate display stays inside its VRR
window instead of falling back to V-Sync. A single fixed target cannot serve two
monitors with different refresh rates, and dragging the client between them
otherwise puts you on the wrong side of that boundary with no indication.

### Features

- **Follows the window.** Reads the refresh rate of the monitor the client canvas
  is on and re-targets within one poll interval when it moves, with no restart.
- **Headroom that travels across refresh rates.** The default uses
  `refresh - refresh² / 3600`, matching NVIDIA's own limiter and holding a
  constant ~0.3ms frametime margin whether the panel is 60Hz or 500Hz. A fixed
  number is available if you would rather choose it yourself.
- **Leaves your settings alone.** The target is applied with
  `Client.setUnlockedFpsTarget`, the same call the GPU plugin makes. The GPU
  plugin's own "FPS target" setting is never written, so nothing is persisted and
  nothing needs restoring.
- **Says when it cannot help.** The GPU plugin discards any FPS target while
  vsync is on or Unlock FPS is off. Both are reported in chat and the log rather
  than failing silently.
- **Optionally fixes them for you.** An opt-in setting, off by default and behind
  a confirmation dialog, turns vsync off and Unlock FPS on. Whatever it
  overwrote is restored when you switch it back off or disable the plugin.

### Notes

- Drives the core GPU plugin. 117HD has its own FPS target and is not handled.
  *(Resolved in 1.1.0.)*
- Displays that do not report a refresh rate are left alone rather than guessed at.
- The GPU plugin's "FPS target" setting keeps showing your configured value
  rather than the one in effect. That is the trade for never writing to it.
