package dev.ultracraft.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The white full-screen flash of a parry (TimeController.ParryFlash). */
public final class ScreenFlashes {
	public static final ScreenFlashes INSTANCE = new ScreenFlashes();
	private static final double PARRY_TIME = 0.1;

	private double parry;

	private ScreenFlashes() {
	}

	/** Wall-clock time: the parry flash lasts through the hitstop. */
	public void update(double dt) {
		this.parry = Math.max(this.parry - dt, 0);
	}

	public void parry() {
		this.parry = PARRY_TIME;
	}

	public void draw(GuiGraphicsExtractor graphics) {
		if (this.parry > 0) {
			int alpha = (int)(this.parry / PARRY_TIME * 110);
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | 0xFFFFFF);
		}
	}
}
