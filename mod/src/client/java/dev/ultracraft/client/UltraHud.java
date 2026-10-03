package dev.ultracraft.client;

import dev.ultracraft.Ultracraft;
import dev.ultracraft.client.movement.UltraMovement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * ULTRAKILL's stamina takes the hunger bar's place while ULTRAKILL movement drives the player (it has no
 * hunger). The speedometer and debug line sit in the top left.
 */
final class UltraHud {
	private static final int BAR_WIDTH = 25;
	private static final int BAR_GAP = 3;
	private static final int FULL = 0xFF29B6F6;
	private static final int CHARGING = 0xFFB0B0B0;
	private static final int EMPTY = 0xFF1A1A1A;

	private UltraHud() {
	}

	static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, food -> (graphics, deltaTracker) -> {
			UltraMovement movement = UltraController.INSTANCE.movement();
			if (movement != null) {
				drawStamina(graphics, movement.stamina());
			} else {
				food.extractRenderState(graphics, deltaTracker);
			}
		});
		HudElementRegistry.addLast(Ultracraft.id("speedometer"), (graphics, deltaTracker) -> drawSpeedometer(graphics));
	}

	private static void drawStamina(GuiGraphicsExtractor graphics, double stamina) {
		int right = graphics.guiWidth() / 2 + 91;
		int top = graphics.guiHeight() - 39 + 2;
		int left = right - (BAR_WIDTH * 3 + BAR_GAP * 2);
		for (int i = 0; i < 3; i++) {
			int x = left + i * (BAR_WIDTH + BAR_GAP);
			double fill = Math.clamp((stamina - i * 100) / 100, 0, 1);
			graphics.fill(x - 1, top - 1, x + BAR_WIDTH + 1, top + 6, 0xFF000000);
			graphics.fill(x, top, x + BAR_WIDTH, top + 5, EMPTY);
			graphics.fill(x, top, x + (int)Math.round(BAR_WIDTH * fill), top + 5, fill >= 1 ? FULL : CHARGING);
		}
	}

	private static void drawSpeedometer(GuiGraphicsExtractor graphics) {
		UltraConfig config = UltraConfig.get();
		UltraMovement movement = UltraController.INSTANCE.movement();
		Minecraft minecraft = Minecraft.getInstance();
		if (movement == null) {
			return;
		}
		int y = 4;
		if (config.speedometer) {
			graphics.text(minecraft.font, String.format("SPEED: %.2f u/s", movement.speed()), 4, y, 0xFFFFFFFF);
			y += 10;
		}
		if (config.debug) {
			graphics.text(minecraft.font, movement.debugState(), 4, y, 0xFFA0A0A0);
		}
	}
}
