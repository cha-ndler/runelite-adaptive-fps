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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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
	private boolean warnedAboutVsync;
	private boolean warnedAboutUnlockFps;

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
		warnedAboutVsync = false;
		warnedAboutUnlockFps = false;
		SwingUtilities.invokeLater(this::evaluate);
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
		Integer refresh = currentRefreshRate();
		if (refresh == null)
		{
			return;
		}

		int target = Math.max(config.minTarget(), refresh - config.headroom());
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

		log.debug("Display {} at {}Hz -> fpsTarget {}", deviceId, refresh, target);

		if (config.chatFeedback() && !firstApply)
		{
			sendChat("Adaptive FPS: " + refresh + "Hz display, FPS target set to " + target + ".");
		}

		checkGpuPluginState();
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
	 * If the user has vsync on, or has locked FPS, our writes are inert and they should know.
	 */
	private void checkGpuPluginState()
	{
		String vsyncMode = configManager.getConfiguration(GPU_GROUP, KEY_VSYNC_MODE);
		if (vsyncMode != null && !"OFF".equalsIgnoreCase(vsyncMode) && !warnedAboutVsync)
		{
			warnedAboutVsync = true;
			sendChat("Adaptive FPS: GPU plugin vsync mode is " + vsyncMode
				+ ", so the FPS target is ignored. Set it to Off for this plugin to take effect.");
		}

		Boolean unlockFps = configManager.getConfiguration(GPU_GROUP, KEY_UNLOCK_FPS, boolean.class);
		if (unlockFps != null && !unlockFps && !warnedAboutUnlockFps)
		{
			warnedAboutUnlockFps = true;
			sendChat("Adaptive FPS: GPU plugin 'Unlock FPS' is off, so the client is capped at 50 FPS.");
		}
	}

	private void sendChat(String message)
	{
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.CONSOLE, "", message, null));
	}
}
