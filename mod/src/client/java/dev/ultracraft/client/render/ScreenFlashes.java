package dev.ultracraft.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Full-screen flashes: white for a parry (TimeController.ParryFlash), green for healing (hpFlash). */
public final class ScreenFlashes {
	public static final ScreenFlashes INSTANCE = new ScreenFlashes();
	private static final double PARRY_TIME = 0.1;
	private static final double HEAL_TIME = 0.25;

	private double parry;
	private double heal;

	private ScreenFlashes() {
	}

	/** Wall-clock time: the parry flash lasts through the hitstop. */
	public void update(double dt) {
		this.parry = Math.max(this.parry - dt, 0);
		this.heal = Math.max(this.heal - dt, 0);
	}

	public void parry() {
		this.parry = PARRY_TIME;
	}

	public void heal() {
		this.heal = HEAL_TIME;
	}

	public void draw(GuiGraphicsExtractor graphics) {
		if (this.heal > 0) {
			int alpha = (int)(this.heal / HEAL_TIME * 70);
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | 0x33FF55);
		}
		if (this.parry > 0) {
			int alpha = (int)(this.parry / PARRY_TIME * 110);
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | 0xFFFFFF);
		}
	}
}
