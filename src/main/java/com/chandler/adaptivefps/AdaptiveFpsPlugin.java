package com.chandler.adaptivefps;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * The target is applied through {@link Client#setUnlockedFpsTarget(int)}, which is the same call
 * both the GPU plugin and 117 HD make. Nothing is written to either one's configuration, so their
 * "FPS target" settings stay exactly as the user left them and there is nothing to restore
 * afterwards. Whichever of the two is enabled is detected rather than configured -- RuneLite's
 * conflict handling guarantees it is never both.
 */
@Slf4j
@PluginDescriptor(
	name = "Adaptive FPS",
	description = "Keeps the FPS target just below the refresh rate of the monitor the client is on",
	tags = {"fps", "gsync", "g-sync", "freesync", "vrr", "monitor", "refresh", "gpu", "117hd", "hd",
		"renderer", "latency"}
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

	/** The renderer seen on the previous evaluation, so a switch between the two is noticed. */
	private Renderer activeRenderer;

	/**
	 * The last target announced to the user, so the same number is not reported twice. This is
	 * reporting state only -- the target itself is pushed again on every evaluation regardless.
	 */
	private int lastAppliedTarget = -1;

	private boolean handledVsync;
	private boolean handledUnlockFps;
	private boolean warnedAboutRenderer;

	/**
	 * Renderer settings as they were before "Fix vsync and Unlock FPS" overwrote them, keyed by
	 * {@code group.key}. An empty value means the key was unset, so restoring it means clearing it
	 * again rather than writing a value the user never chose.
	 * <p>
	 * This has to hold more than one renderer at a time. Fixing the GPU plugin and then switching to
	 * 117 HD leaves settings borrowed from both, and a single slot would silently drop the first.
	 * <p>
	 * Everything that touches this runs on the EDT. Concurrent anyway, because the cost is nothing and
	 * the alternative is trusting that assumption to hold for every caller forever.
	 */
	private final Map<String, String> borrowedSettings = new ConcurrentHashMap<>();

	@Provides
	AdaptiveFpsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AdaptiveFpsConfig.class);
	}

	@Override
	protected void startUp()
	{
		activeRenderer = null;
		lastAppliedTarget = -1;
		handledVsync = false;
		handledUnlockFps = false;
		warnedAboutRenderer = false;
		borrowedSettings.clear();
		log.info("Adaptive FPS started");
		// Deliberately no immediate evaluate() here. Both renderers create their GL context lazily and
		// bail out of startUp while the canvas is still invalid, so acting this early can land in a
		// window where their state is only half set up. 117 HD's startup does considerably more than
		// that again. The scheduled poll picks it up once the client is actually rendering.
	}

	@Override
	protected void shutDown()
	{
		// Anything we borrowed goes back first; those writes also make the renderer recompute its own
		// sync mode and target.
		restoreBorrowedSettings();

		Renderer renderer = resolveRenderer();
		if (renderer != null)
		{
			// The target was only ever set at runtime, so handing it back means working out what the
			// renderer itself would have set and putting that into effect now, rather than leaving the
			// user on our number until they next touch one of these settings.
			//
			// This has to be the renderer's whole expression, not just its FPS target. It passes 0
			// while it is syncing to the display, so handing back a target in that state would cap a
			// vsynced client at a number it never asked for. And it has to be the renderer that is
			// actually enabled -- reading the other one's settings would push a value belonging to a
			// renderer that is not even running.
			final int configured = configuredTarget(unlockFps(renderer), vsyncMode(renderer),
				fpsTarget(renderer));
			clientThread.invokeLater(() -> client.setUnlockedFpsTarget(configured));
			log.debug("Handed the FPS target back to {} for {}", configured, renderer.label());
		}

		activeRenderer = null;
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
		Renderer renderer = resolveRenderer();
		if (renderer != activeRenderer)
		{
			// Both of these are "have we already said something about this renderer", so switching to a
			// different one has to start them over. Done here rather than from a PluginChanged
			// subscriber: that event fires for every plugin in the client, so a subscriber would have to
			// repeat this same resolution just to filter it, and would miss a switch that happened
			// before this plugin started.
			activeRenderer = renderer;
			handledVsync = false;
			handledUnlockFps = false;
		}

		if (!rendererReady(renderer))
		{
			return;
		}

		// Checked before the early returns below. The user can change vsync, or the setting that lets
		// us fix it, at any moment -- tying this to a target change meant it went unnoticed until the
		// window happened to move between monitors.
		checkRendererState(renderer);

		if (!targetIsHonoured(renderer))
		{
			// The renderer passes 0 to setUnlockedFpsTarget whenever it is syncing to the display, so
			// setting a target here would just be overwritten. checkRendererState has already said so
			// in chat.
			return;
		}

		GraphicsDevice device = currentDevice();
		Integer refresh = refreshRateOf(device);
		if (refresh == null)
		{
			return;
		}

		int target = Math.max(config.minTarget(), refresh - headroomFor(refresh));

		// Applied every pass, not only when the number changes. There is no way to read the current
		// target back, so it cannot be checked before setting; and both renderers push their own value
		// back from an event subscriber whose ordering against this one is not defined, so nothing can
		// be built on winning that race. Writing one int every two seconds costs nothing and buys a
		// target that repairs itself whatever overwrote it.
		clientThread.invokeLater(() -> client.setUnlockedFpsTarget(target));

		if (target != lastAppliedTarget)
		{
			boolean firstApply = lastAppliedTarget == -1;
			lastAppliedTarget = target;
			log.info("Display {} at {}Hz -> FPS target {}", device.getIDstring(), refresh, target);

			if (config.chatFeedback() && !firstApply)
			{
				sendChat("Adaptive FPS: " + refresh + "Hz display, FPS target set to " + target + ".");
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (Renderer.isRendererGroup(event.getGroup()))
		{
			String key = event.getKey();
			if (KEY_FPS_TARGET.equals(key) || KEY_VSYNC_MODE.equals(key) || KEY_UNLOCK_FPS.equals(key))
			{
				// Both renderers re-run setupSyncMode on any of these, which pushes their own configured
				// target back into the client and discards ours. The poll would put it back anyway; this
				// only shortens the gap. Which group the event came from is not worth checking -- one
				// from the renderer that is disabled costs a single evaluation that changes nothing.
				SwingUtilities.invokeLater(this::evaluate);
			}
			return;
		}

		if (!AdaptiveFpsConfig.GROUP.equals(event.getGroup()) || !"applyGpuSettings".equals(event.getKey()))
		{
			return;
		}

		// Config events arrive on the event bus thread, while everything this touches belongs to the
		// EDT. Hop across before changing any of it rather than reaching in from here.
		final boolean applying = Boolean.parseBoolean(event.getNewValue());
		SwingUtilities.invokeLater(() ->
		{
			// Turning on "Fix vsync and Unlock FPS" has to be able to act on a condition already warned
			// about. Without clearing these, the warning that tells the user to enable the setting is
			// the very thing that stops it from ever taking effect that session.
			handledVsync = false;
			handledUnlockFps = false;

			if (applying)
			{
				evaluate();
			}
			else
			{
				restoreBorrowedSettings();
			}
		});
	}

	/**
	 * Hands back whatever we overwrote when "Fix vsync and Unlock FPS" was switched on. Only settings
	 * we actually changed are restored -- anything the user set themselves is left alone, and a key
	 * they had never set is cleared rather than written back with a value they never chose.
	 * <p>
	 * Writing to a renderer that is no longer running is safe: it ignores config events while it is
	 * disabled, so this is a plain settings write with nothing else attached.
	 * <p>
	 * When this runs from the config panel the handled flags are cleared by the caller, so putting
	 * vsync back the way it was will draw a fresh warning that the FPS target is inert again. That is
	 * the honest consequence of undoing the fix, and silently reverting to a broken state would be
	 * worse than saying so.
	 */
	private void restoreBorrowedSettings()
	{
		if (borrowedSettings.isEmpty())
		{
			return;
		}

		// Iteration order is unspecified, so with settings borrowed from both renderers the messages
		// can come out in either order. Each one names its renderer, so that still reads correctly.
		for (Map.Entry<String, String> entry : borrowedSettings.entrySet())
		{
			int dot = entry.getKey().indexOf('.');
			String group = entry.getKey().substring(0, dot);
			String key = entry.getKey().substring(dot + 1);
			String value = entry.getValue();
			String label = Renderer.forGroup(group).label();
			String setting = KEY_VSYNC_MODE.equals(key) ? "vsync mode" : "'Unlock FPS'";

			if (value.isEmpty())
			{
				configManager.unsetConfiguration(group, key);
				announce("restored " + label + "'s " + setting + " to its default.");
			}
			else
			{
				configManager.setConfiguration(group, key, value);
				announce("restored " + label + "'s " + setting + " to " + value + ".");
			}
		}

		borrowedSettings.clear();

		// Disabling this plugin can be the last thing that happens before the client exits, and the
		// periodic config flush may not come round again. Push these out now so the settings we
		// borrowed are given back on disk rather than only in memory.
		configManager.sendConfig();
	}

	/** True once a renderer is enabled and the client is actually rendering through it. */
	private boolean rendererReady(Renderer renderer)
	{
		if (renderer == null)
		{
			if (!warnedAboutRenderer)
			{
				warnedAboutRenderer = true;
				warn("no GPU renderer is enabled, so there is no FPS target to set."
					+ " Enable the GPU plugin or 117 HD.");
			}
			return false;
		}

		warnedAboutRenderer = false;
		GameState state = client.getGameState();
		return state != null && state.getState() >= GameState.LOGIN_SCREEN.getState();
	}

	/**
	 * Whichever renderer is currently enabled, or null if neither is.
	 * <p>
	 * Both renderer classes are present in the plugin list whether or not they are running, and at
	 * most one of them is enabled, so the enabled check belongs in the loop condition. Returning the
	 * enabled state of the first renderer found would answer for whichever of the two happened to be
	 * listed first.
	 */
	private Renderer resolveRenderer()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			Renderer renderer = Renderer.forPlugin(plugin);
			if (renderer != null && pluginManager.isPluginEnabled(plugin))
			{
				return renderer;
			}
		}

		return null;
	}

	private boolean unlockFps(Renderer renderer)
	{
		Boolean value = configManager.getConfiguration(renderer.group(), KEY_UNLOCK_FPS, boolean.class);
		return value == null ? renderer.defaultUnlockFps() : value;
	}

	private String vsyncMode(Renderer renderer)
	{
		String value = configManager.getConfiguration(renderer.group(), KEY_VSYNC_MODE);
		return value == null || value.isEmpty() ? renderer.defaultVsyncMode() : value;
	}

	private int fpsTarget(Renderer renderer)
	{
		Integer value = configManager.getConfiguration(renderer.group(), KEY_FPS_TARGET, int.class);
		return value == null ? Renderer.DEFAULT_FPS_TARGET : value;
	}

	/**
	 * Whether the renderer is in a state where it will actually let an FPS target take effect. Both
	 * of them pass 0 to setUnlockedFpsTarget whenever the swap interval is non-zero, and force the
	 * sync mode off entirely when FPS is not unlocked, so in either case a target is meaningless.
	 */
	private boolean targetIsHonoured(Renderer renderer)
	{
		return honoursTarget(unlockFps(renderer), vsyncMode(renderer));
	}

	/** Package-private so the rule both renderers implement can be pinned by a test. */
	static boolean honoursTarget(boolean unlockFps, String vsyncMode)
	{
		return unlockFps && Renderer.VSYNC_OFF.equals(vsyncMode);
	}

	/**
	 * The target a renderer sets for itself, which is its configured value only while it is not
	 * syncing to the display. Package-private so it can be pinned by a test.
	 */
	static int configuredTarget(boolean unlockFps, String vsyncMode, int fpsTarget)
	{
		return honoursTarget(unlockFps, vsyncMode) ? fpsTarget : 0;
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
	 * The renderer only honours an FPS target when it is not syncing to the display. If the user has
	 * vsync on, or has locked FPS, there is nothing for this plugin to do.
	 * <p>
	 * Each condition is handled at most once per renderer. Repeatedly forcing a setting the user has
	 * deliberately changed back would be worse than leaving it alone and saying so.
	 */
	private void checkRendererState(Renderer renderer)
	{
		String syncMode = vsyncMode(renderer);
		if (!Renderer.VSYNC_OFF.equals(syncMode) && !handledVsync)
		{
			handledVsync = true;
			if (config.applyGpuSettings())
			{
				borrow(renderer, KEY_VSYNC_MODE);
				configManager.setConfiguration(renderer.group(), KEY_VSYNC_MODE, Renderer.VSYNC_OFF);
				announce(renderer.label() + " vsync mode was " + syncMode
					+ "; set it to Off so the FPS target applies.");
			}
			else
			{
				warn(renderer.label() + " vsync mode is " + syncMode + ", so the FPS target is ignored."
					+ " Set it to Off, or enable \"Fix vsync and Unlock FPS\".");
			}
		}

		if (!unlockFps(renderer) && !handledUnlockFps)
		{
			handledUnlockFps = true;
			if (config.applyGpuSettings())
			{
				borrow(renderer, KEY_UNLOCK_FPS);
				configManager.setConfiguration(renderer.group(), KEY_UNLOCK_FPS, true);
				announce(renderer.label() + " 'Unlock FPS' was off, capping the client at 50 FPS;"
					+ " turned it on.");
			}
			else
			{
				warn(renderer.label() + " 'Unlock FPS' is off, so the client is capped at 50 FPS."
					+ " Turn it on, or enable \"Fix vsync and Unlock FPS\".");
			}
		}
	}

	/**
	 * Remembers a setting exactly as it stands before overwriting it, with an empty string standing
	 * for a key the user has never set. Nothing is recorded a second time, so re-applying the fix
	 * cannot overwrite the user's own value with the one we put there.
	 */
	private void borrow(Renderer renderer, String key)
	{
		String previous = configManager.getConfiguration(renderer.group(), key);
		borrowedSettings.putIfAbsent(renderer.group() + "." + key, previous == null ? "" : previous);
	}

	/**
	 * Refresh rate of the monitor the client canvas currently sits on, or null if it cannot be
	 * determined. Java reports this rounded to the nearest whole Hz, which is what we want -- a
	 * 174.963Hz panel comes back as 175.
	 */
	private Integer refreshRateOf(GraphicsDevice device)
	{
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
