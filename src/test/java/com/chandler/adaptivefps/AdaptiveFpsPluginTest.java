package com.chandler.adaptivefps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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

	/** Slack in absolute time is what actually absorbs frame pacing jitter; it should hold ~0.3ms. */
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

	private static int automaticHeadroomOf(int refresh)
	{
		return AdaptiveFpsPlugin.automaticHeadroom(refresh);
	}
}
