package dev.ultracraft.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The parry flash (TimeController.ParryFlash): a half-transparent white screen, shown through the hitstop and
 * for 0.1 s of game time after it.
 */
public final class ScreenFlashes {
	public static final ScreenFlashes INSTANCE = new ScreenFlashes();
	private static final double PARRY_TIME = 0.1;
	/** The ParryFlash image's alpha. */
	private static final int PARRY_ALPHA = 125;

	private double parry;

	private ScreenFlashes() {
	}

	/** Game time runs the flash down, so it lasts through the hitstop. */
	public void update(double dt, boolean hitstop) {
		if (!hitstop) {
			this.parry = Math.max(this.parry - dt, 0);
		}
	}

	public void parry() {
		this.parry = PARRY_TIME;
	}

	public void draw(GuiGraphicsExtractor graphics) {
		if (this.parry > 0) {
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), PARRY_ALPHA << 24 | 0xFFFFFF);
		}
	}
}
