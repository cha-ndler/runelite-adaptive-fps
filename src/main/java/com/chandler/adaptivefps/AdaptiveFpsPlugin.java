package com.chandler.adaptivefps;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.time.temporal.ChronoUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.GpuPluginConfig;
import net.runelite.client.task.Schedule;

/**
 * Keeps the client's FPS target just below the refresh rate of whichever monitor the client window
 * is currently on.
 * <p>
 * On a variable refresh rate display (G-Sync / FreeSync) paired with V-Sync, the frame rate has to
 * stay a few frames under the panel's refresh rate. Go over it and the display leaves its VRR window
 * and falls back to V-Sync, which queues a frame and adds latency. A single fixed FPS target cannot
 * satisfy two monitors with different refresh rates, so dragging the client between them silently
 * puts you on the wrong side of that boundary.
 * <p>
 * The target is applied through {@link Client#setUnlockedFpsTarget(int)}, which is the same call the
 * GPU plugin makes. Nothing is written to the GPU plugin's configuration, so its "FPS target"
 * setting stays exactly as the user left it and there is nothing to restore afterwards.
 */
@Slf4j
@PluginDescriptor(
	name = "Adaptive FPS",
	description = "Keeps the FPS target just below the refresh rate of the monitor the client is on",
	tags = {"fps", "gsync", "g-sync", "freesync", "vrr", "monitor", "refresh", "gpu", "latency"}
)
public class AdaptiveFpsPlugin extends Plugin
{
	private static final String KEY_FPS_TARGET = "fpsTarget";
	private static final String KEY_VSYNC_MODE = "vsyncMode";
	private static final String KEY_UNLOCK_FPS = "unlockFps";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private AdaptiveFpsConfig config;

	/** Typed view of the GPU plugin's settings, so its keys are checked at compile time. */
	private GpuPluginConfig gpuConfig;

	private String lastDeviceId;
	private int lastAppliedTarget = -1;
	private boolean handledVsync;
	private boolean handledUnlockFps;
	private boolean warnedAboutGpuPlugin;

	/**
	 * Set when something has invalidated the target we last pushed, so the next evaluation applies it
	 * again even though the number itself has not changed.
	 */
	private volatile boolean forceReapply;

	/**
	 * GPU plugin settings as they were before "Fix GPU plugin settings" overwrote them. Null means we
	 * never touched that setting and so have nothing to give back.
	 */
	private GpuPluginConfig.SyncMode previousVsyncMode;
	private Boolean previousUnlockFps;

	@Provides
	AdaptiveFpsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AdaptiveFpsConfig.class);
	}

	@Override
	protected void startUp()
	{
		gpuConfig = configManager.getConfig(GpuPluginConfig.class);
		lastDeviceId = null;
		lastAppliedTarget = -1;
		handledVsync = false;
		handledUnlockFps = false;
		warnedAboutGpuPlugin = false;
		previousVsyncMode = null;
		previousUnlockFps = null;
		forceReapply = true;
		log.info("Adaptive FPS started");
		// Deliberately no immediate evaluate() here. The GPU plugin creates its GL context lazily and
		// bails out of startUp while the canvas is still invalid, so acting this early can land in a
		// window where its state is only half set up. The scheduled poll picks it up once the client
		// is actually rendering.
	}

	@Override
	protected void shutDown()
	{
		// Anything we borrowed from the GPU plugin goes back first; that write also makes the GPU
		// plugin recompute its own sync mode and target.
		restoreGpuSettings();

		if (gpuConfig != null)
		{
			// The target was only ever set at runtime, so handing it back is just a matter of putting
			// the GPU plugin's own configured value back into effect. Nothing of ours was persisted,
			// so nothing can be left behind on disk.
			final int configured = gpuConfig.fpsTarget();
			clientThread.invokeLater(() -> client.setUnlockedFpsTarget(configured));
		}

		lastDeviceId = null;
		lastAppliedTarget = -1;
		log.info("Adaptive FPS stopped");
	}

	@Schedule(period = 2, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void poll()
	{
		// Reading a component's GraphicsConfiguration touches AWT state, so do it on the EDT.
		SwingUtilities.invokeLater(this::evaluate);
	}

	private void evaluate()
	{
		if (!gpuPluginReady())
		{
			return;
		}

		// Checked before the early returns below. The user can change vsync, or the setting that lets
		// us fix it, at any moment -- tying this to a target change meant it went unnoticed until the
		// window happened to move between monitors.
		checkGpuPluginState();

		if (!targetIsHonoured())
		{
			// The GPU plugin passes 0 to setUnlockedFpsTarget whenever it is syncing to the display,
			// so setting a target here would just be overwritten. checkGpuPluginState has already
			// said so in chat.
			return;
		}

		Integer refresh = currentRefreshRate();
		if (refresh == null)
		{
			return;
		}

		int target = Math.max(config.minTarget(), refresh - headroomFor(refresh));
		String deviceId = currentDeviceId();

		boolean movedMonitor = deviceId != null && !deviceId.equals(lastDeviceId);
		boolean changed = target != lastAppliedTarget;
		if (!forceReapply && !movedMonitor && !changed)
		{
			return;
		}

		boolean firstApply = lastAppliedTarget == -1;
		forceReapply = false;
		lastDeviceId = deviceId;
		lastAppliedTarget = target;

		clientThread.invokeLater(() -> client.setUnlockedFpsTarget(target));

		if (changed)
		{
			log.info("Display {} at {}Hz -> FPS target {}", deviceId, refresh, target);
		}
		else
		{
			log.debug("Re-applied FPS target {} after the GPU plugin reset it", target);
		}

		if (config.chatFeedback() && changed && !firstApply)
		{
			sendChat("Adaptive FPS: " + refresh + "Hz display, FPS target set to " + target + ".");
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GpuPluginConfig.GROUP.equals(event.getGroup()))
		{
			String key = event.getKey();
			if (KEY_FPS_TARGET.equals(key) || KEY_VSYNC_MODE.equals(key) || KEY_UNLOCK_FPS.equals(key))
			{
				// The GPU plugin re-runs setupSyncMode on any of these, which pushes its own configured
				// target back into the client and discards ours. Re-assert it rather than leaving the
				// wrong cap in place until the next poll.
				forceReapply = true;
				SwingUtilities.invokeLater(this::evaluate);
			}
			return;
		}

		if (!AdaptiveFpsConfig.GROUP.equals(event.getGroup()) || !"applyGpuSettings".equals(event.getKey()))
		{
			return;
		}

		// Turning on "Fix GPU plugin settings" has to be able to act on a condition already warned
		// about. Without clearing these, the warning that tells the user to enable the setting is the
		// very thing that stops it from ever taking effect that session.
		handledVsync = false;
		handledUnlockFps = false;

		if (Boolean.parseBoolean(event.getNewValue()))
		{
			SwingUtilities.invokeLater(this::evaluate);
		}
		else
		{
			restoreGpuSettings();
		}
	}

	/**
	 * Hands back whatever we overwrote when "Fix GPU plugin settings" was switched on. Only settings
	 * we actually changed are restored -- anything the user set themselves is left alone.
	 * <p>
	 * When this runs from the config panel the handled flags are cleared by the caller, so putting
	 * vsync back the way it was will draw a fresh warning that the FPS target is inert again. That is
	 * the honest consequence of undoing the fix, and silently reverting to a broken state would be
	 * worse than saying so.
	 */
	private void restoreGpuSettings()
	{
		boolean restored = false;

		if (previousVsyncMode != null)
		{
			configManager.setConfiguration(GpuPluginConfig.GROUP, KEY_VSYNC_MODE, previousVsyncMode);
			announce("restored the GPU plugin's vsync mode to " + previousVsyncMode + ".");
			previousVsyncMode = null;
			restored = true;
		}

		if (previousUnlockFps != null)
		{
			configManager.setConfiguration(GpuPluginConfig.GROUP, KEY_UNLOCK_FPS, previousUnlockFps);
			announce("restored the GPU plugin's 'Unlock FPS' to " + previousUnlockFps + ".");
			previousUnlockFps = null;
			restored = true;
		}

		if (restored)
		{
			// Disabling this plugin can be the last thing that happens before the client exits, and the
			// periodic config flush may not come round again. Push these out now so the settings we
			// borrowed are given back on disk rather than only in memory.
			configManager.sendConfig();
		}
	}

	/** True once the GPU plugin is enabled and the client is actually rendering through it. */
	private boolean gpuPluginReady()
	{
		if (!gpuPluginEnabled())
		{
			if (!warnedAboutGpuPlugin)
			{
				warnedAboutGpuPlugin = true;
				warn("the GPU plugin is not enabled, so there is no FPS target to set.");
			}
			return false;
		}

		warnedAboutGpuPlugin = false;
		GameState state = client.getGameState();
		return state != null && state.getState() >= GameState.LOGIN_SCREEN.getState();
	}

	private boolean gpuPluginEnabled()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof GpuPlugin)
			{
				return pluginManager.isPluginEnabled(plugin);
			}
		}
		return false;
	}

	/**
	 * Whether the GPU plugin is in a state where it will actually let an FPS target take effect. It
	 * passes 0 to setUnlockedFpsTarget whenever the swap interval is non-zero, and forces the sync
	 * mode off entirely when FPS is not unlocked, so in either case a target is meaningless.
	 */
	private boolean targetIsHonoured()
	{
		return gpuConfig.unlockFps() && gpuConfig.syncMode() == GpuPluginConfig.SyncMode.OFF;
	}

	/**
	 * How far below the refresh rate to sit.
	 * <p>
	 * A flat number does not travel across refresh rates. Three frames is 0.85ms of slack at 60Hz but
	 * 0.012ms at 500Hz, and frame pacing jitter is an absolute time rather than a share of the refresh
	 * interval, so the fast panel is left with no margin at all.
	 * <p>
	 * {@code refresh^2 / 3600} is what NVIDIA's own limiter uses -- it reproduces the documented -1 FPS
	 * at 60Hz and -16 FPS at 240Hz exactly -- and is equivalent to holding a constant ~0.3ms frametime
	 * margin at every refresh rate, which is the quantity that actually matters.
	 */
	private int headroomFor(int refresh)
	{
		if (config.headroomMode() == HeadroomMode.FIXED)
		{
			return config.fixedHeadroom();
		}

		return automaticHeadroom(refresh);
	}

	/** Package-private so the published headroom table can be pinned by a test. */
	static int automaticHeadroom(int refresh)
	{
		// At least one frame, so the target can never land on the refresh rate itself.
		return Math.max(1, (int) Math.round(refresh * refresh / 3600.0));
	}

	/**
	 * Refresh rate of the monitor the client canvas currently sits on, or null if it cannot be
	 * determined. Java reports this rounded to the nearest whole Hz, which is what we want -- a
	 * 174.963Hz panel comes back as 175.
	 */
	private Integer currentRefreshRate()
	{
		GraphicsDevice device = currentDevice();
		if (device == null)
		{
			return null;
		}

		DisplayMode mode = device.getDisplayMode();
		if (mode == null)
		{
			return null;
		}

		int refresh = mode.getRefreshRate();
		if (refresh == DisplayMode.REFRESH_RATE_UNKNOWN || refresh <= 0)
		{
			// Some drivers and virtual displays do not report a rate. Leave the target alone rather
			// than guessing at it.
			return null;
		}

		return refresh;
	}

	private GraphicsDevice currentDevice()
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return null;
		}

		GraphicsConfiguration gc = canvas.getGraphicsConfiguration();
		return gc == null ? null : gc.getDevice();
	}

	private String currentDeviceId()
	{
		GraphicsDevice device = currentDevice();
		return device == null ? null : device.getIDstring();
	}

	/**
	 * The GPU plugin only honours an FPS target when it is not syncing to the display. If the user has
	 * vsync on, or has locked FPS, there is nothing for this plugin to do.
	 * <p>
	 * Each condition is handled at most once per start. Repeatedly forcing a setting the user has
	 * deliberately changed back would be worse than leaving it alone and saying so.
	 */
	private void checkGpuPluginState()
	{
		GpuPluginConfig.SyncMode syncMode = gpuConfig.syncMode();
		if (syncMode != GpuPluginConfig.SyncMode.OFF && !handledVsync)
		{
			handledVsync = true;
			if (config.applyGpuSettings())
			{
				previousVsyncMode = syncMode;
				configManager.setConfiguration(GpuPluginConfig.GROUP, KEY_VSYNC_MODE,
					GpuPluginConfig.SyncMode.OFF);
				announce("GPU plugin vsync mode was " + syncMode + "; set it to Off so the FPS target applies.");
			}
			else
			{
				warn("GPU plugin vsync mode is " + syncMode + ", so the FPS target is ignored."
					+ " Set it to Off, or enable \"Fix GPU plugin settings\".");
			}
		}

		if (!gpuConfig.unlockFps() && !handledUnlockFps)
		{
			handledUnlockFps = true;
			if (config.applyGpuSettings())
			{
				previousUnlockFps = false;
				configManager.setConfiguration(GpuPluginConfig.GROUP, KEY_UNLOCK_FPS, true);
				announce("GPU plugin 'Unlock FPS' was off, capping the client at 50 FPS; turned it on.");
			}
			else
			{
				warn("GPU plugin 'Unlock FPS' is off, so the client is capped at 50 FPS."
					+ " Turn it on, or enable \"Fix GPU plugin settings\".");
			}
		}
	}

	/** Something was changed on the user's behalf, so it needs to be discoverable afterwards. */
	private void announce(String message)
	{
		log.info(message);
		sendChat("Adaptive FPS: " + message);
	}

	/**
	 * Warnings have to reach the log as well as chat. Chat is not rendered at the login screen, and
	 * these fire precisely when the plugin is inert, so a user in that state would otherwise get no
	 * signal in either place and simply see nothing happen.
	 */
	private void warn(String message)
	{
		log.warn(message);
		sendChat("Adaptive FPS: " + message);
	}

	private void sendChat(String message)
	{
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", message, null));
	}
}
