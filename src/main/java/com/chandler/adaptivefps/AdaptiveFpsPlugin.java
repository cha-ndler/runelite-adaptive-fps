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
import net.runelite.client.task.Schedule;

/**
 * Keeps the GPU plugin's FPS target just below the refresh rate of whichever monitor the client
 * window is currently on.
 * <p>
 * On a variable refresh rate display (G-Sync / FreeSync) paired with V-Sync, the frame rate has to
 * stay a few frames under the panel's refresh rate. Go over it and the display leaves its VRR window
 * and falls back to V-Sync, which queues a frame and adds latency. A single fixed FPS target cannot
 * satisfy two monitors with different refresh rates, so dragging the client between them silently
 * puts you on the wrong side of that boundary.
 * <p>
 * The GPU plugin already re-applies its target live when the {@code fpsTarget} config key changes,
 * so this plugin simply keeps that key pointed at the right number for the current monitor.
 */
@Slf4j
@PluginDescriptor(
	name = "Adaptive FPS",
	description = "Keeps the FPS target just below the refresh rate of the monitor the client is on",
	tags = {"fps", "gsync", "g-sync", "freesync", "vrr", "monitor", "refresh", "gpu", "latency"}
)
public class AdaptiveFpsPlugin extends Plugin
{
	private static final String GPU_GROUP = "gpu";
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
	private AdaptiveFpsConfig config;

	/** FPS target owned by the GPU plugin before we first touched it, so we can hand it back. */
	private Integer originalTarget;

	private String lastDeviceId;
	private int lastAppliedTarget = -1;
	private boolean handledVsync;
	private boolean handledUnlockFps;
	private boolean warnedAboutGpuPlugin;

	/**
	 * GPU plugin settings as they were before "Fix GPU plugin settings" overwrote them. Null means
	 * we never touched that setting and so have nothing to give back.
	 */
	private String previousVsyncMode;
	private Boolean previousUnlockFps;

	@Provides
	AdaptiveFpsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AdaptiveFpsConfig.class);
	}

	@Override
	protected void startUp()
	{
		originalTarget = null;
		lastDeviceId = null;
		lastAppliedTarget = -1;
		handledVsync = false;
		handledUnlockFps = false;
		previousVsyncMode = null;
		previousUnlockFps = null;
		log.info("Adaptive FPS started");
		// Deliberately no immediate evaluate() here. The GPU plugin creates its GL context lazily
		// and bails out of startUp while the canvas is still invalid, so writing gpu.fpsTarget this
		// early can land in a window where GpuPlugin.setupSyncMode dereferences a null awtContext.
		// The scheduled poll picks it up once the client is actually rendering.
	}

	@Override
	protected void shutDown()
	{
		if (config.restoreOnStop() && originalTarget != null)
		{
			configManager.setConfiguration(GPU_GROUP, KEY_FPS_TARGET, originalTarget);
			log.debug("Restored {}.{} to {}", GPU_GROUP, KEY_FPS_TARGET, originalTarget);
		}
		originalTarget = null;
	}

	@Schedule(period = 2, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void poll()
	{
		// Reading a component's GraphicsConfiguration touches AWT state, so do it on the EDT.
		SwingUtilities.invokeLater(this::evaluate);
	}

	private void evaluate()
	{
		if (!gpuRendererReady())
		{
			return;
		}

		// Checked before the early return below. The user can change vsync, or the setting that
		// lets us fix it, at any moment -- tying this to a target change meant it went unnoticed
		// until the window happened to move between monitors.
		checkGpuPluginState();

		Integer refresh = currentRefreshRate();
		if (refresh == null)
		{
			return;
		}

		int target = Math.max(config.minTarget(), refresh - headroomFor(refresh));
		String deviceId = currentDeviceId();

		boolean movedMonitor = deviceId != null && !deviceId.equals(lastDeviceId);
		if (!movedMonitor && target == lastAppliedTarget)
		{
			return;
		}

		if (originalTarget == null)
		{
			originalTarget = configManager.getConfiguration(GPU_GROUP, KEY_FPS_TARGET, int.class);
		}

		configManager.setConfiguration(GPU_GROUP, KEY_FPS_TARGET, target);

		boolean firstApply = lastAppliedTarget == -1;
		lastDeviceId = deviceId;
		lastAppliedTarget = target;

		log.info("Display {} at {}Hz -> gpu.fpsTarget {}", deviceId, refresh, target);

		if (config.chatFeedback() && !firstApply)
		{
			sendChat("Adaptive FPS: " + refresh + "Hz display, FPS target set to " + target + ".");
		}
	}

	/**
	 * Turning on "Fix GPU plugin settings" has to be able to act on a condition already warned
	 * about. Without clearing the flags, the warning that tells the user to enable the setting is
	 * the very thing that stops it from ever taking effect that session.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!AdaptiveFpsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (!"applyGpuSettings".equals(event.getKey()))
		{
			return;
		}

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
	 * Hands back whatever we overwrote when the setting was switched on. Only settings we actually
	 * changed are restored -- anything the user set themselves is left alone.
	 * <p>
	 * The handled flags are cleared by the caller, so putting vsync back the way it was will draw a
	 * fresh warning that the FPS target is inert again. That is the honest consequence of undoing
	 * the fix, and silently reverting to a broken state would be worse than saying so.
	 */
	private void restoreGpuSettings()
	{
		if (previousVsyncMode != null)
		{
			configManager.setConfiguration(GPU_GROUP, KEY_VSYNC_MODE, previousVsyncMode);
			announce("restored the GPU plugin's vsync mode to " + previousVsyncMode + ".");
			previousVsyncMode = null;
		}

		if (previousUnlockFps != null)
		{
			configManager.setConfiguration(GPU_GROUP, KEY_UNLOCK_FPS, previousUnlockFps);
			announce("restored the GPU plugin's 'Unlock FPS' to " + previousUnlockFps + ".");
			previousUnlockFps = null;
		}
	}

	/**
	 * True once the GPU plugin is enabled and the client is actually rendering through it.
	 * <p>
	 * {@code GpuPlugin.startUp} bails out with a null {@code awtContext} while the canvas is still
	 * invalid and retries on a later tick. Writing {@code gpu.fpsTarget} inside that window makes
	 * its {@code onConfigChanged} handler run {@code setupSyncMode}, which dereferences that null
	 * and throws on the client thread. Waiting for the login screen is a reliable proxy for the GL
	 * context existing, because the GPU plugin is what draws it.
	 */
	private boolean gpuRendererReady()
	{
		Boolean enabled = configManager.getConfiguration("runelite", "gpuplugin", boolean.class);
		if (enabled == null || !enabled)
		{
			if (!warnedAboutGpuPlugin)
			{
				warnedAboutGpuPlugin = true;
				warn("the GPU plugin is not enabled, so there is no FPS target to set.");
			}
			return false;
		}

		GameState state = client.getGameState();
		return state != null && state.getState() >= GameState.LOGIN_SCREEN.getState();
	}

	/**
	 * How far below the refresh rate to sit. A fixed gap does not travel well across a wide range
	 * of refresh rates: 3 FPS is a comfortable margin at 175Hz but only 0.6% at 500Hz, which frame
	 * pacing jitter can eat. Scaling by one frame per 100Hz gives 172 on a 175Hz panel and 495 on
	 * a 500Hz one, matching the usual per-panel recommendations.
	 */
	private int headroomFor(int refresh)
	{
		int headroom = config.headroom();
		if (config.scaleHeadroom())
		{
			headroom = Math.max(headroom, (int) Math.ceil(refresh / 100.0));
		}
		return headroom;
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
			// Some drivers and virtual displays do not report a rate. Leave the target alone
			// rather than guessing at it.
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
	 * The GPU plugin only honours fpsTarget when it is not syncing to the display -- see
	 * GpuPlugin, which passes 0 to setUnlockedFpsTarget whenever the swap interval is non-zero.
	 * If the user has vsync on, or has locked FPS, our writes are inert.
	 * <p>
	 * Each condition is handled at most once per start. Repeatedly forcing a setting the user has
	 * deliberately changed back would be worse than leaving it alone and saying so.
	 */
	private void checkGpuPluginState()
	{
		String vsyncMode = configManager.getConfiguration(GPU_GROUP, KEY_VSYNC_MODE);
		if (vsyncMode != null && !"OFF".equalsIgnoreCase(vsyncMode) && !handledVsync)
		{
			handledVsync = true;
			if (config.applyGpuSettings())
			{
				previousVsyncMode = vsyncMode;
				configManager.setConfiguration(GPU_GROUP, KEY_VSYNC_MODE, "OFF");
				announce("GPU plugin vsync mode was " + vsyncMode + "; set it to Off so the FPS target applies.");
			}
			else
			{
				warn("GPU plugin vsync mode is " + vsyncMode + ", so the FPS target is ignored."
					+ " Set it to Off, or enable \"Fix GPU plugin settings\".");
			}
		}

		Boolean unlockFps = configManager.getConfiguration(GPU_GROUP, KEY_UNLOCK_FPS, boolean.class);
		if (unlockFps != null && !unlockFps && !handledUnlockFps)
		{
			handledUnlockFps = true;
			if (config.applyGpuSettings())
			{
				previousUnlockFps = unlockFps;
				configManager.setConfiguration(GPU_GROUP, KEY_UNLOCK_FPS, true);
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
