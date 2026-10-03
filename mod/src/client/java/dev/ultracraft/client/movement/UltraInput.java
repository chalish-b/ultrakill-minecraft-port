package dev.ultracraft.client.movement;

import dev.ultracraft.client.UltraKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

/** One frame of ULTRAKILL's input actions: held state plus this frame's presses and releases. */
public final class UltraInput {
	/** Strafe (right is positive) and forward, normalized like Unity's digital 2D composite. */
	double moveX;
	double moveY;
	boolean jumpPressed;
	boolean jumpHeld;
	boolean slidePressed;
	boolean slideHeld;
	boolean slideReleased;
	boolean dashPressed;

	public void sample(Options options) {
		this.moveX = axis(options.keyRight, options.keyLeft);
		this.moveY = axis(options.keyUp, options.keyDown);
		if (this.moveX != 0 && this.moveY != 0) {
			this.moveX *= Math.sqrt(0.5);
			this.moveY *= Math.sqrt(0.5);
		}

		this.jumpPressed = consumeClicks(options.keyJump);
		this.jumpHeld = options.keyJump.isDown();

		boolean slideWasHeld = this.slideHeld;
		this.slidePressed = consumeClicks(UltraKeys.SLIDE);
		this.slideHeld = UltraKeys.SLIDE.isDown();
		this.slideReleased = (slideWasHeld || this.slidePressed) && !this.slideHeld;

		this.dashPressed = consumeClicks(UltraKeys.DASH);
	}

	/** Drops presses made before ULTRAKILL movement took over. */
	public void discard(Options options) {
		consumeClicks(options.keyJump);
		consumeClicks(UltraKeys.SLIDE);
		consumeClicks(UltraKeys.DASH);
		this.slideHeld = UltraKeys.SLIDE.isDown();
	}

	private static double axis(KeyMapping positive, KeyMapping negative) {
		return (positive.isDown() ? 1 : 0) - (negative.isDown() ? 1 : 0);
	}

	private static boolean consumeClicks(KeyMapping key) {
		boolean clicked = false;
		while (key.consumeClick()) {
			clicked = true;
		}
		return clicked;
	}
}
