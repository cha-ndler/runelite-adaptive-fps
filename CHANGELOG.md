# Changelog

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
- Displays that do not report a refresh rate are left alone rather than guessed at.
- The GPU plugin's "FPS target" setting keeps showing your configured value
  rather than the one in effect. That is the trade for never writing to it.
