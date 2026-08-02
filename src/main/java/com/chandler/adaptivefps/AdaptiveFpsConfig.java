package com.chandler.adaptivefps;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(AdaptiveFpsConfig.GROUP)
public interface AdaptiveFpsConfig extends Config
{
	String GROUP = "adaptivefps";

	@ConfigItem(
		keyName = "headroom",
		name = "Headroom below refresh",
		description = "How many FPS below the monitor's refresh rate to target. Staying a few frames under the"
			+ " refresh rate is what keeps a variable refresh rate display inside its VRR window instead of"
			+ " falling back to V-Sync.",
		position = 1
	)
	@Range(min = 0, max = 30)
	default int headroom()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "scaleHeadroom",
		name = "Scale headroom with refresh",
		description = "Widen the gap on faster panels. Frame pacing jitter grows with refresh rate, so a gap"
			+ " that is comfortable at 175Hz is too tight at 500Hz. When on, the gap used is the larger of the"
			+ " headroom above and one frame per 100Hz -- which gives 172 on a 175Hz panel and 495 on a 500Hz"
			+ " one.",
		position = 2
	)
	default boolean scaleHeadroom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minTarget",
		name = "Minimum target",
		description = "Never set the FPS target below this, as a guard against a display reporting a nonsense"
			+ " refresh rate.",
		position = 2
	)
	@Range(min = 1, max = 999)
	default int minTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "applyGpuSettings",
		name = "Fix GPU plugin settings",
		description = "Turn the GPU plugin's Vsync mode off and Unlock FPS on, which it needs before it will"
			+ " honour an FPS target at all. Applied once when this plugin starts -- if you change them back"
			+ " later it will leave them alone rather than fight you -- and not undone when this plugin stops.",
		position = 4
	)
	default boolean applyGpuSettings()
	{
		return false;
	}

	@ConfigItem(
		keyName = "restoreOnStop",
		name = "Restore target on stop",
		description = "Put the GPU plugin's FPS target back to whatever it was before this plugin first changed"
			+ " it, when this plugin is disabled.",
		position = 3
	)
	default boolean restoreOnStop()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatFeedback",
		name = "Announce changes in chat",
		description = "Print a game chat message whenever the FPS target changes because the client moved to a"
			+ " different monitor.",
		position = 4
	)
	default boolean chatFeedback()
	{
		return true;
	}
}
