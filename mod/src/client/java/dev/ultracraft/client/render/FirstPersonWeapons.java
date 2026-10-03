package dev.ultracraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.client.render.animation.Animator;
import dev.ultracraft.client.render.animation.Pose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The first-person viewmodels: the held revolver and the Feedbacker arm, each played by its own Animator. The
 * arm is always there; its idle pose keeps it below the screen until it punches or flips a coin.
 */
public final class FirstPersonWeapons {
	public static final FirstPersonWeapons INSTANCE = new FirstPersonWeapons();
	/** ULTRAKILL draws weapons with a 90 degree camera and Minecraft draws hands with a 70 degree one. */
	private static final float FOV_SCALE = (float)(Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(45)));

	public enum Weapon {
		REVOLVER_PIERCER("revolver_piercer"),
		REVOLVER_MARKSMAN("revolver_marksman");

		final String model;

		Weapon(String model) {
			this.model = model;
		}
	}

	/** Clip events from the held weapon and from the arm. */
	public interface Events {
		void weaponEvent(Weapon weapon, String function);

		void armEvent(String function);
	}

	/** A model with the Animator that plays it. */
	private static final class Viewmodel {
		final UltraModel model;
		final Animator animator;
		final Pose pose;

		Viewmodel(UltraModel model, Animator animator) {
			this.model = model;
			this.animator = animator;
			this.pose = model.rest().copy();
		}

		static @Nullable Viewmodel load(String name) {
			UltraModel model = UltraModel.get(name);
			Animator animator = model == null ? null : model.animator();
			return animator == null ? null : new Viewmodel(model, animator);
		}

		void update(double dt, Animator.Events events) {
			this.animator.update(dt, events);
			this.animator.pose(this.pose);
		}
	}

	private final RandomSource random = RandomSource.create();
	private final Map<Weapon, Viewmodel> weapons = new EnumMap<>(Weapon.class);
	private @Nullable Viewmodel arm;
	private @Nullable Weapon held;
	private double charge;

	private FirstPersonWeapons() {
	}

	private @Nullable Viewmodel weapon(Weapon weapon) {
		Viewmodel viewmodel = this.weapons.get(weapon);
		if (viewmodel == null) {
			viewmodel = Viewmodel.load(weapon.model);
			if (viewmodel != null) {
				this.weapons.put(weapon, viewmodel);
			}
		}
		return viewmodel;
	}

	private @Nullable Viewmodel arm() {
		if (this.arm == null) {
			this.arm = Viewmodel.load("feedbacker");
		}
		return this.arm;
	}

	/** The held weapon's Animator. */
	public @Nullable Animator animator(Weapon weapon) {
		Viewmodel viewmodel = weapon(weapon);
		return viewmodel == null ? null : viewmodel.animator;
	}

	/** The Feedbacker's Animator. */
	public @Nullable Animator armAnimator() {
		Viewmodel viewmodel = arm();
		return viewmodel == null ? null : viewmodel.animator;
	}

	/** A weapon drawn: its object is enabled, so its Animator starts over (the revolver's PickUp). */
	public void equip(Weapon weapon) {
		Animator animator = animator(weapon);
		if (animator != null) {
			animator.reset();
		}
	}

	/**
	 * Runs the held weapon's and the arm's Animators.
	 *
	 * @param charge how far the held weapon is charged, 0 to 100, for the shake that goes with it
	 */
	public void update(double dt, @Nullable Weapon held, double charge, Events events) {
		this.held = held;
		this.charge = charge;
		if (held != null) {
			Viewmodel weapon = weapon(held);
			if (weapon != null) {
				weapon.update(dt, function -> events.weaponEvent(held, function));
			}
		}
		Viewmodel arm = arm();
		if (arm != null) {
			arm.update(dt, events::armEvent);
		}
	}

	/**
	 * Draws the viewmodels into Minecraft's first-person hand pass, in camera space.
	 *
	 * @return whether they replace Minecraft's hands and held item
	 */
	public boolean submit(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		poseStack.pushPose();
		poseStack.scale(FOV_SCALE, FOV_SCALE, 1);
		Viewmodel arm = arm();
		if (arm != null) {
			arm.model.submit(poseStack, collector, light, arm.pose);
		}
		Viewmodel weapon = this.held == null ? null : weapon(this.held);
		if (weapon != null) {
			submitGun(poseStack, collector, light, weapon);
		}
		poseStack.popPose();
		return this.held != null;
	}

	/** Revolver.Update shakes the gun while it charges. */
	private void submitGun(PoseStack poseStack, SubmitNodeCollector collector, int light, Viewmodel weapon) {
		poseStack.pushPose();
		if (this.charge > 0) {
			double shake = this.charge / 250 * 0.05;
			poseStack.translate(this.random.nextDouble() * 2 * shake - shake, this.random.nextDouble() * 2 * shake - shake, this.random.nextDouble() * 2 * shake - shake);
		}
		weapon.model.submit(poseStack, collector, light, weapon.pose);
		poseStack.popPose();
	}
}
