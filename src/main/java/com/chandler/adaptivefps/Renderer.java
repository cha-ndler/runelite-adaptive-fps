package com.chandler.adaptivefps;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.plugins.gpu.GpuPluginConfig;

/**
 * The GPU renderers this plugin knows how to drive.
 * <p>
 * Both of them decide the frame cap the same way. Each runs a {@code setupSyncMode} that ends in
 * {@code client.setUnlockedFpsTarget(swapInterval == 0 ? fpsTarget : 0)}, forces its sync mode off
 * when FPS is not unlocked, and re-runs that method whenever {@code fpsTarget}, {@code unlockFps}
 * or {@code vsyncMode} changes. They even use the same three key names, so the only things that
 * actually differ are the config group, what to call the thing in chat, and two defaults.
 * <p>
 * Only one can ever be enabled. 117 HD declares {@code conflicts = "GPU"}, and RuneLite matches
 * conflicts in both directions, so starting either one disables and stops the other.
 * <p>
 * 117 HD is a Plugin Hub plugin, so there is nothing to compile against: it is matched by class
 * name and its settings are read as raw strings. Its defaults are transcribed here rather than
 * inherited, which is the one thing in this file that can rot. If they ever change upstream the
 * cost is a spurious warning, or one redundant write when the user has opted into the fix -- the
 * values are only ever used as a fallback for a key the user has never set.
 */
enum Renderer
{
	GPU(GpuPluginConfig.GROUP, "the GPU plugin", true, "OFF"),

	/** Defaults verified against 117 HD 1.5.2, and upstream RLHD at the Hub-pinned 35e89ff. */
	HD("hd", "117 HD", false, "ADAPTIVE");

	/** Class name of the 117 HD plugin, which cannot be referenced any more directly than this. */
	private static final String HD_PLUGIN_CLASS = "rs117.hd.HdPlugin";

	/** Both renderers ship the same default, so there is nothing to vary per constant. */
	static final int DEFAULT_FPS_TARGET = 60;

	/**
	 * The sync mode that lets an FPS target through. Both renderers persist {@code SyncMode} by
	 * {@code name()}, so comparing the stored string means neither enum's declaration order matters.
	 */
	static final String VSYNC_OFF = "OFF";

	private final String group;
	private final String label;
	private final boolean defaultUnlockFps;
	private final String defaultVsyncMode;

	Renderer(String group, String label, boolean defaultUnlockFps, String defaultVsyncMode)
	{
		this.group = group;
		this.label = label;
		this.defaultUnlockFps = defaultUnlockFps;
		this.defaultVsyncMode = defaultVsyncMode;
	}

	String group()
	{
		return group;
	}

	/** Carries its own article, so it can be dropped into a sentence without a special case. */
	String label()
	{
		return label;
	}

	boolean defaultUnlockFps()
	{
		return defaultUnlockFps;
	}

	String defaultVsyncMode()
	{
		return defaultVsyncMode;
	}

	/**
	 * Which renderer a plugin is, or null if it is not one. The asymmetry is deliberate: the core
	 * GPU plugin is on the classpath, so an upstream move or rename should break this build rather
	 * than quietly stop matching.
	 */
	static Renderer forPlugin(Plugin plugin)
	{
		return plugin instanceof GpuPlugin ? GPU : forClassName(plugin.getClass().getName());
	}

	static Renderer forClassName(String className)
	{
		return HD_PLUGIN_CLASS.equals(className) ? HD : null;
	}

	static Renderer forGroup(String group)
	{
		for (Renderer renderer : values())
		{
			if (renderer.group.equals(group))
			{
				return renderer;
			}
		}

		return null;
	}

	static boolean isRendererGroup(String group)
	{
		return forGroup(group) != null;
	}
}
