package com.chandler.adaptivefps;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Runs a developer client with this plugin loaded, for checking behaviour that no unit test can
 * reach: which renderer is detected, what the refresh rate of the current monitor is, and whether
 * the target survives everything that pushes its own value back.
 * <p>
 * Point {@code -PrlHome=<dir>} at a scratch directory to keep a run out of your real RuneLite
 * profile. The plugin starts working at the login screen, so none of this needs an account.
 */
public class AdaptiveFpsPluginLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AdaptiveFpsPlugin.class);
		RuneLite.main(args);
	}
}
