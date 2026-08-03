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
		description = "How far below the refresh rate to cap. Automatic scales with the panel; Fixed uses"
			+ " the number below.",
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

	/**
	 * The only setting here that changes anything outside this plugin, so it is the only one that
	 * asks first. RuneLite renders {@code warning} as a Yes/No dialog that defaults to No and drops
	 * the change if declined.
	 * <p>
	 * The key still reads {@code applyGpuSettings} now that it covers 117 HD as well. Renaming it
	 * would quietly switch every user who had opted in back off and leave the old key behind on disk,
	 * which is a poor trade for a tidier name nobody sees.
	 */
	@ConfigItem(
		keyName = "applyGpuSettings",
		name = "Fix vsync and Unlock FPS",
		description = "Turns your renderer's vsync off and Unlock FPS on, which the FPS target needs to"
			+ " work. Untick to put them back.",
		warning = "Changes two of your renderer's settings: vsync to Off, Unlock FPS on. On 117 HD both"
			+ " differ from its defaults, so expect a visible change. Unticking restores them.",
		position = 4
	)
	default boolean applyGpuSettings()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chatFeedback",
		name = "Announce in chat",
		description = "Print a chat message when the target changes.",
		position = 5
	)
	default boolean chatFeedback()
	{
		return true;
	}
}
