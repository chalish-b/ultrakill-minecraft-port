package dev.ultracraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The first-person viewmodels: the held revolver and the Feedbacker arm, posed as ULTRAKILL holds them, with
 * simple motion for equipping, recoil, charging and punching.
 */
public final class FirstPersonWeapons {
	public static final FirstPersonWeapons INSTANCE = new FirstPersonWeapons();
	/** ULTRAKILL draws weapons with a 90 degree camera and Minecraft draws hands with a 70 degree one. */
	private static final float FOV_SCALE = (float)(Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(45)));
	private static final double EQUIP_TIME = 0.2;
	private static final double PUNCH_TIME = 0.3;

	public enum Weapon {
		REVOLVER_PIERCER("revolver_piercer"),
		REVOLVER_MARKSMAN("revolver_marksman");

		final String model;

		Weapon(String model) {
			this.model = model;
		}
	}

	private final RandomSource random = RandomSource.create();
	private @Nullable Weapon held;
	private double equip = 1;
	private double recoil;
	private double punch = 1;
	private double charge;

	private FirstPersonWeapons() {
	}

	/**
	 * @param charge how far the held weapon is charged, 0 to 100, for the shake that goes with it
	 */
	public void update(double dt, @Nullable Weapon held, double charge) {
		this.held = held;
		this.charge = charge;
		this.equip = Math.min(this.equip + dt / EQUIP_TIME, 1);
		this.recoil *= Math.exp(-10 * dt);
		this.punch = Math.min(this.punch + dt / PUNCH_TIME, 1);
	}

	public void equip() {
		this.equip = 0;
	}

	public void recoil(boolean charged) {
		this.recoil = charged ? 2 : 1;
	}

	public void punch() {
		this.punch = 0;
	}

	/**
	 * Draws the viewmodels into Minecraft's first-person hand pass, in camera space.
	 *
	 * @return whether they replace Minecraft's hands and held item
	 */
	public boolean submit(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		boolean punching = this.punch < 1;
		if (this.held == null && !punching) {
			return false;
		}
		poseStack.pushPose();
		poseStack.scale(FOV_SCALE, FOV_SCALE, 1);
		if (punching) {
			submitArm(poseStack, collector, light);
		}
		if (this.held != null) {
			submitGun(poseStack, collector, light, this.held);
		}
		poseStack.popPose();
		return this.held != null;
	}

	private void submitGun(PoseStack poseStack, SubmitNodeCollector collector, int light, Weapon weapon) {
		UltraModel model = UltraModel.get(weapon.model);
		if (model == null) {
			return;
		}
		Vec3 pivot = model.center();
		double shown = 1 - Math.pow(1 - this.equip, 3);
		poseStack.pushPose();
		poseStack.translate(0, -0.6 * (1 - shown) + 0.04 * this.recoil, 0.12 * this.recoil);
		if (this.charge > 0) {
			double shake = this.charge / 250 * 0.05;
			poseStack.translate(this.random.nextDouble() * 2 * shake - shake, this.random.nextDouble() * 2 * shake - shake, this.random.nextDouble() * 2 * shake - shake);
		}
		poseStack.translate(pivot.x, pivot.y, pivot.z);
		poseStack.rotate(Axis.XP.rotationDegrees((float)(6 * this.recoil - 30 * (1 - shown))));
		poseStack.translate(-pivot.x, -pivot.y, -pivot.z);
		model.submit(poseStack, collector, light);
		poseStack.popPose();
	}

	/** The arm comes up from below the screen, jabs, and drops back down. */
	private void submitArm(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		UltraModel model = UltraModel.get("feedbacker");
		if (model == null) {
			return;
		}
		double out = Math.sin(Math.PI * this.punch);
		poseStack.pushPose();
		poseStack.translate(0, -0.6 * (1 - out), 0.4 * (1 - out));
		model.submit(poseStack, collector, light);
		poseStack.popPose();
	}
}
