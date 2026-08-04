package com.chandler.adaptivefps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.plugins.gpu.GpuPluginConfig;
import org.junit.Test;

public class AdaptiveFpsPluginTest
{
	/**
	 * The two figures NVIDIA's own limiter is documented as using. The formula exists to reproduce
	 * these, so if they ever stop matching the justification in the README has gone with them.
	 */
	@Test
	public void reproducesThePublishedNvidiaDataPoints()
	{
		assertEquals(1, AdaptiveFpsPlugin.automaticHeadroom(60));
		assertEquals(16, AdaptiveFpsPlugin.automaticHeadroom(240));
	}

	/** The table printed in the README, so documentation and behaviour cannot drift apart. */
	@Test
	public void matchesTheDocumentedTable()
	{
		assertEquals(1, AdaptiveFpsPlugin.automaticHeadroom(60));
		assertEquals(2, AdaptiveFpsPlugin.automaticHeadroom(75));
		assertEquals(3, AdaptiveFpsPlugin.automaticHeadroom(100));
		assertEquals(4, AdaptiveFpsPlugin.automaticHeadroom(120));
		assertEquals(6, AdaptiveFpsPlugin.automaticHeadroom(144));
		assertEquals(9, AdaptiveFpsPlugin.automaticHeadroom(175));
		assertEquals(16, AdaptiveFpsPlugin.automaticHeadroom(240));
		assertEquals(36, AdaptiveFpsPlugin.automaticHeadroom(360));
		assertEquals(69, AdaptiveFpsPlugin.automaticHeadroom(500));
	}

	/**
	 * A target equal to the refresh rate is the one outcome that defeats the point of the plugin, so
	 * the headroom has to stay at a frame or more however slow the panel is.
	 */
	@Test
	public void neverLandsOnTheRefreshRateItself()
	{
		for (int refresh = 1; refresh <= 600; refresh++)
		{
			assertTrue("headroom must be at least one frame at " + refresh + "Hz",
				automaticHeadroomOf(refresh) >= 1);
		}
	}

	/**
	 * Slack in absolute time is what actually absorbs frame pacing jitter; it should hold ~0.3ms.
	 * <p>
	 * 75Hz is deliberately not in this list. It wants 1.56 frames of headroom and can only be given a
	 * whole one, so it overshoots to 0.37ms. That is the rounding erring towards more margin than
	 * asked for, which is safe -- but it does not belong inside a band this narrow.
	 */
	@Test
	public void holdsAConstantFrametimeMargin()
	{
		for (int refresh : new int[]{60, 100, 120, 144, 175, 240, 360, 500})
		{
			int target = refresh - automaticHeadroomOf(refresh);
			double marginMs = (1000.0 / target) - (1000.0 / refresh);
			assertTrue("margin at " + refresh + "Hz was " + marginMs + "ms",
				marginMs > 0.25 && marginMs < 0.35);
		}
	}

	/**
	 * The rule both renderers implement in setupSyncMode: a target only survives when FPS is unlocked
	 * and nothing is syncing to the display. Anything this gets wrong shows up as the plugin fighting
	 * the renderer over the frame cap.
	 */
	@Test
	public void honoursATargetOnlyWithFpsUnlockedAndVsyncOff()
	{
		assertTrue(AdaptiveFpsPlugin.honoursTarget(true, "OFF"));
		assertFalse(AdaptiveFpsPlugin.honoursTarget(true, "ON"));
		assertFalse(AdaptiveFpsPlugin.honoursTarget(true, "ADAPTIVE"));
		assertFalse(AdaptiveFpsPlugin.honoursTarget(false, "OFF"));

		// A sync mode neither renderer has today has to read as "syncing", not as "off". Also pins
		// that the comparison is exact: RuneLite stores these by name(), so they are always upper case.
		assertFalse(AdaptiveFpsPlugin.honoursTarget(true, "FASTSYNC"));
		assertFalse(AdaptiveFpsPlugin.honoursTarget(true, "off"));
	}

	/**
	 * What shutdown hands back. Returning the configured target while the renderer is syncing would
	 * cap a vsynced client at a number the user never asked for and did not have before.
	 */
	@Test
	public void handsBackZeroWheneverTheRendererWould()
	{
		assertEquals(144, AdaptiveFpsPlugin.configuredTarget(true, "OFF", 144));
		assertEquals(0, AdaptiveFpsPlugin.configuredTarget(true, "ON", 144));
		assertEquals(0, AdaptiveFpsPlugin.configuredTarget(true, "ADAPTIVE", 144));
		assertEquals(0, AdaptiveFpsPlugin.configuredTarget(false, "OFF", 144));
	}

	/**
	 * A 117 HD user on a 75Hz panel, reported against 1.0.0 back when only the GPU plugin was
	 * detected. Each of the three states they passed through has to produce a different outcome, and
	 * only the last of them is this plugin doing anything at all.
	 */
	@Test
	public void walksTheReported117HdOn75HzCase()
	{
		// Stock 117 HD. Its own setupSyncMode forces the sync mode off whenever FPS is locked, so the
		// vsync setting is not what holds these users at 50 -- the client's own cap is, and no FPS
		// target of any value lifts it.
		assertFalse(AdaptiveFpsPlugin.honoursTarget(Renderer.HD.defaultUnlockFps(),
			Renderer.HD.defaultVsyncMode()));

		// Unlocking FPS by hand and leaving vsync alone, which is where the report stopped. Adaptive
		// vsync pins the frame rate to the refresh rate exactly, which is the 75 that was seen.
		assertFalse(AdaptiveFpsPlugin.honoursTarget(true, "ADAPTIVE"));
		assertEquals(0, AdaptiveFpsPlugin.configuredTarget(true, "ADAPTIVE", 60));

		// Both settings fixed, which is what the checkbox is for. Only now does a target survive.
		assertTrue(AdaptiveFpsPlugin.honoursTarget(true, "OFF"));
		assertEquals(73, 75 - AdaptiveFpsPlugin.automaticHeadroom(75));
	}

	/** Config groups decide which events are reacted to and where settings are read and written. */
	@Test
	public void mapsConfigGroupsToRenderers()
	{
		assertSame(Renderer.GPU, Renderer.forGroup("gpu"));
		assertSame(Renderer.HD, Renderer.forGroup("hd"));
		assertNull(Renderer.forGroup(AdaptiveFpsConfig.GROUP));

		assertTrue(Renderer.isRendererGroup("gpu"));
		assertTrue(Renderer.isRendererGroup("hd"));
		assertFalse(Renderer.isRendererGroup(AdaptiveFpsConfig.GROUP));

		assertSame(Renderer.HD, Renderer.forClassName("rs117.hd.HdPlugin"));
		assertNull(Renderer.forClassName("rs117.hd.HdPluginConfig"));
	}

	/**
	 * 117 HD is a Plugin Hub plugin, so nothing here can be checked against it at build time the way
	 * the GPU plugin's can be below. These are transcribed from 117 HD 1.5.2, and this test only stops
	 * them being changed by accident.
	 */
	@Test
	public void pinsTheTranscribed117HdDefaults()
	{
		assertEquals("hd", Renderer.HD.group());
		assertFalse(Renderer.HD.defaultUnlockFps());
		assertEquals("ADAPTIVE", Renderer.HD.defaultVsyncMode());

		// The GPU plugin's own defaults, for contrast: a default install already honours a target,
		// where a default 117 HD install fails both conditions.
		assertTrue(Renderer.GPU.defaultUnlockFps());
		assertEquals("OFF", Renderer.GPU.defaultVsyncMode());
	}

	/**
	 * The plugin reads and writes the GPU plugin's settings by key name rather than through its config
	 * interface, so an upstream rename would otherwise go unnoticed until someone reported that
	 * nothing happens. 117 HD uses these same three key names, so this covers both.
	 */
	@Test
	public void pinsTheGpuPluginsKeyNames() throws NoSuchMethodException
	{
		assertEquals("gpu", GpuPluginConfig.GROUP);
		assertEquals("unlockFps", keyNameOf("unlockFps"));
		assertEquals("vsyncMode", keyNameOf("syncMode"));
		assertEquals("fpsTarget", keyNameOf("fpsTarget"));
	}

	private static String keyNameOf(String method) throws NoSuchMethodException
	{
		return GpuPluginConfig.class.getMethod(method).getAnnotation(ConfigItem.class).keyName();
	}

	private static int automaticHeadroomOf(int refresh)
	{
		return AdaptiveFpsPlugin.automaticHeadroom(refresh);
	}
}
