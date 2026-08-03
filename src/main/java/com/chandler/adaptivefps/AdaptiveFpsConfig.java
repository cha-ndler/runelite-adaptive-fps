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
		keyName = "headroomMode",
		name = "Headroom",
		description = "How far below the refresh rate to cap. Automatic scales with the panel and suits any"
			+ " refresh rate; Fixed uses the number below.",
		position = 1
	)
	default HeadroomMode headroomMode()
	{
		return HeadroomMode.AUTOMATIC;
	}

	@ConfigItem(
		keyName = "fixedHeadroom",
		name = "Fixed headroom",
		description = "Frames to stay below the refresh rate. Ignored unless Headroom is set to Fixed.",
		position = 2
	)
	@Range(min = 1, max = 100)
	default int fixedHeadroom()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "minTarget",
		name = "Minimum target",
		description = "Never cap below this, whatever the display reports.",
		position = 3
	)
	@Range(min = 1, max = 999)
	default int minTarget()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "applyGpuSettings",
		name = "Fix GPU plugin settings",
		description = "Turn the GPU plugin's vsync off and Unlock FPS on, which the FPS target needs to work"
			+ " at all. Switching this back off restores them.",
		position = 4
	)
	default boolean applyGpuSettings()
	{
		return false;
	}

	@ConfigItem(
		keyName = "restoreOnStop",
		name = "Restore target on stop",
		description = "Hand the FPS target back when this plugin is disabled.",
		position = 5
	)
	default boolean restoreOnStop()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatFeedback",
		name = "Announce in chat",
		description = "Print a chat message when the target changes.",
		position = 6
	)
	default boolean chatFeedback()
	{
		return true;
	}
}
