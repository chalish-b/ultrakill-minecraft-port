package dev.ultracraft.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.render.animation.Animator;
import dev.ultracraft.client.render.animation.Pose;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The first-person viewmodels: the held revolver and the Feedbacker arm, each played by its own Animator. The
 * arm is always there; its idle pose keeps it below the screen until it punches or flips a coin.
 */
public final class FirstPersonWeapons {
	public static final FirstPersonWeapons INSTANCE = new FirstPersonWeapons();
	/** ULTRAKILL draws weapons with a 90 degree camera and Minecraft draws hands with a 70 degree one. */
	private static final float FOV_SCALE = (float)(Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(45)));
	/** Unity's Color.red and Color.yellow. */
	private static final float[] RED = {1, 0, 0, 1};
	private static final float[] YELLOW = {1, 0.92156863F, 0.015686275F, 1};

	public enum Weapon {
		REVOLVER_PIERCER("revolver_piercer"),
		REVOLVER_MARKSMAN("revolver_marksman");

		final String model;

		Weapon(String model) {
			this.model = model;
		}
	}

	/**
	 * What the held revolver's screen, charge glow and cylinder show (Revolver's charges, 0 to 100).
	 *
	 * @param coinCharge the marksman's coins, 0 to 400
	 * @param cylinderSpin how fast the charge spins the cylinder (RevolverCylinder.spinSpeed)
	 */
	public record RevolverState(double pierceShotCharge, double pierceCharge, boolean pierceReady, double coinCharge, double cylinderSpin) {
		public static final RevolverState NONE = new RevolverState(0, 100, true, 400, 0);
	}

	/** Clip events from the held weapon and from the arm, and the held revolver's cylinder clicking into place. */
	public interface Events {
		void weaponEvent(Weapon weapon, String function);

		void armEvent(String function);

		void cylinderClicked();
	}

	/** A model with the Animator that plays it, and the ports of the scripts that draw on it. */
	private static final class Viewmodel {
		final UltraModel model;
		final Animator animator;
		final Pose pose;
		final @Nullable RevolverCylinder cylinder;

		Viewmodel(UltraModel model, Animator animator) {
			this.model = model;
			this.animator = animator;
			this.pose = model.rest().copy();
			JsonObject cylinder = model.script("cylinder");
			this.cylinder = cylinder == null ? null : new RevolverCylinder(cylinder, model.rest());
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
	private RevolverState state = RevolverState.NONE;
	/** The arm's parent (FistControl's "Punch"), turned towards what it last hit. */
	private final Quaternionf armRotation = new Quaternionf();
	private boolean returnToOrigRot;

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

	/** RevolverCylinder.DoTurn, after a shot. */
	public void turnCylinder(Weapon weapon) {
		Viewmodel viewmodel = weapon(weapon);
		if (viewmodel != null && viewmodel.cylinder != null) {
			viewmodel.cylinder.turn();
		}
	}

	/**
	 * Punch.PunchSuccess: the arm turns towards the point it hit, at most 45 degrees about each axis, and comes
	 * back once the punch is over.
	 */
	public void punchSuccess(Vec3 point) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Quaternionf toCamera = new Quaternionf(camera.rotation()).conjugate();
		Vector3f target = unity(toCamera.transform(point.subtract(camera.position()).toVector3f())).div((float)PlayerShape.BLOCKS_PER_UNIT);
		Vector3f up = unity(toCamera.transform(new Vector3f(0, 1, 0)));
		UltraModel model = UltraModel.get("feedbacker");
		Vector3f origin = model == null ? new Vector3f() : model.origin();
		lookRotation(target.sub(origin), up, this.armRotation);
		if (RevolverCylinder.angle(this.armRotation, new Quaternionf()) > 45) {
			Vector3f euler = this.armRotation.getEulerAnglesYXZ(new Vector3f());
			float limit = (float)Math.toRadians(45);
			this.armRotation.rotationYXZ(Math.clamp(euler.y, -limit, limit), Math.clamp(euler.x, -limit, limit), Math.clamp(euler.z, -limit, limit));
		}
	}

	/** Minecraft's camera space (-z forward) as Unity's (z forward). */
	private static Vector3f unity(Vector3f v) {
		return v.set(v.x, v.y, -v.z);
	}

	/** Quaternion.LookRotation. */
	private static void lookRotation(Vector3f forward, Vector3f up, Quaternionf out) {
		Vector3f z = new Vector3f(forward).normalize();
		Vector3f x = new Vector3f(up).cross(z).normalize();
		Vector3f y = new Vector3f(z).cross(x);
		new Matrix3f(x, y, z).getNormalizedRotation(out);
	}

	/**
	 * Runs the held weapon's and the arm's Animators, and the scripts that move them after.
	 */
	public void update(double dt, @Nullable Weapon held, RevolverState state, Events events) {
		this.held = held;
		this.state = state;
		if (held != null) {
			Viewmodel weapon = weapon(held);
			if (weapon != null) {
				weapon.update(dt, function -> events.weaponEvent(held, function));
				if (weapon.cylinder != null && weapon.cylinder.update(dt, state.cylinderSpin(), weapon.pose)) {
					events.cylinderClicked();
				}
			}
		}
		Viewmodel arm = arm();
		if (arm != null) {
			arm.update(dt, function -> {
				armEvent(function);
				events.armEvent(function);
			});
		}
		if (this.returnToOrigRot) {
			Quaternionf identity = new Quaternionf();
			float angle = RevolverCylinder.angle(this.armRotation, identity);
			RevolverCylinder.rotateTowards(this.armRotation, identity, (float)((angle * 5 + 5) * dt * 5));
			if (RevolverCylinder.angle(this.armRotation, identity) == 0) {
				this.returnToOrigRot = false;
			}
		}
	}

	/** Punch's ActiveStart, ActiveEnd (ResetFistRotation) and ReadyToPunch, for the arm's rotation. */
	private void armEvent(String function) {
		switch (function) {
			case "ActiveStart" -> this.returnToOrigRot = false;
			case "ActiveEnd", "ReadyToPunch" -> this.returnToOrigRot = true;
			default -> {
			}
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
			arm.model.submit(poseStack, collector, light, arm.model.boneMatrices(arm.pose, this.armRotation));
		}
		Viewmodel weapon = this.held == null ? null : weapon(this.held);
		if (weapon != null) {
			submitRevolver(poseStack, collector, light, weapon);
		}
		poseStack.popPose();
		return this.held != null;
	}

	private void submitRevolver(PoseStack poseStack, SubmitNodeCollector collector, int light, Viewmodel weapon) {
		poseStack.pushPose();
		double charge = this.state.pierceShotCharge();
		if (charge > 0) {
			// Revolver.Update shakes the gun while the piercer charges.
			double shake = charge / 250 * 0.05;
			poseStack.translate(this.random.nextDouble() * 2 * shake - shake, this.random.nextDouble() * 2 * shake - shake, this.random.nextDouble() * 2 * shake - shake);
		}
		Matrix4f[] world = weapon.model.boneMatrices(weapon.pose, new Quaternionf());
		weapon.model.submit(poseStack, collector, light, world);
		JsonObject revolver = weapon.model.script("revolver");
		if (revolver != null) {
			submitScreen(poseStack, collector, weapon.model, world, revolver);
			submitCoinPanels(poseStack, collector, weapon.model, world, revolver);
		}
		poseStack.popPose();
	}

	/**
	 * The piercer's Monitor (Revolver.Update): the battery refills red, then yellow, then in the variant's color,
	 * and shows the charge of a shot being charged. The ChargeEffect at the barrel grows with that charge.
	 */
	private void submitScreen(PoseStack poseStack, SubmitNodeCollector collector, UltraModel model, Matrix4f[] world, JsonObject revolver) {
		JsonArray charges = revolver.getAsJsonArray("batteryCharges");
		if (charges.isEmpty()) {
			return;
		}
		RevolverState s = this.state;
		String texture;
		float[] color;
		if (!s.pierceReady()) {
			texture = s.pierceCharge() < 50 ? "batteryLow" : s.pierceCharge() < 100 ? "batteryMid" : "batteryFull";
		} else if (s.pierceShotCharge() != 0) {
			texture = null;
		} else {
			texture = "batteryFull";
		}
		color = s.pierceCharge() < 50 ? RED : s.pierceCharge() < 100 ? YELLOW : variationColor(revolver);
		Identifier screen = texture != null
			? Identifier.parse(revolver.get(texture).getAsString())
			: Identifier.parse(charges.get(s.pierceShotCharge() < 50 ? 0 : s.pierceShotCharge() < 100 ? 1 : 2).getAsString());
		for (UltraModel.Quad quad : model.quads()) {
			if (quad.name().equals("Monitor")) {
				ModelOverlays.quad(poseStack, collector, RenderTypes.text(screen), world[quad.bone()], color);
			} else if (quad.name().equals("ChargeEffect") && quad.texture() != null && s.pierceShotCharge() > 0) {
				float scale = (float)(s.pierceShotCharge() * 0.02);
				Matrix4f matrix = new Matrix4f(world[quad.bone()]).scale(scale);
				ModelOverlays.quad(poseStack, collector, RenderTypes.entityTranslucentEmissive(quad.texture()), matrix, new float[]{1, 1, 1, 1});
			}
		}
	}

	/**
	 * The marksman's coin panels (Revolver.CheckCoinCharges): one per coin, filling as it recharges, red until
	 * full and then in the variant's color.
	 */
	private void submitCoinPanels(PoseStack poseStack, SubmitNodeCollector collector, UltraModel model, Matrix4f[] world, JsonObject revolver) {
		List<int[]> panels = new ArrayList<>();
		for (JsonElement element : revolver.getAsJsonArray("coinPanels")) {
			JsonArray panel = element.getAsJsonArray();
			panels.add(new int[]{panel.get(0).getAsInt(), panel.get(1).getAsInt()});
		}
		if (panels.isEmpty()) {
			return;
		}
		float[] variation = variationColor(revolver);
		for (int c = 0; c < model.canvases().size(); c++) {
			UltraModel.Canvas canvas = model.canvases().get(c);
			Matrix4f matrix = world[canvas.bone()];
			for (int i = 0; i < canvas.images().size(); i++) {
				UltraModel.Image image = canvas.images().get(i);
				float[] color = image.color();
				float fill = image.fillAmount();
				for (int p = 0; p < panels.size(); p++) {
					if (panels.get(p)[0] == c && panels.get(p)[1] == i) {
						fill = (float)(this.state.coinCharge() / 100 - p);
						color = fill < 1 ? RED : variation;
					}
				}
				// Children draw over their parents, as Unity's UI orders them.
				var type = i == 0 ? RenderTypes.text(image.texture()) : RenderTypes.textPolygonOffset(image.texture());
				ModelOverlays.image(poseStack, collector, type, matrix, image, color, fill);
			}
		}
	}

	private static float[] variationColor(JsonObject revolver) {
		JsonArray color = revolver.getAsJsonArray("variationColor");
		return new float[]{color.get(0).getAsFloat(), color.get(1).getAsFloat(), color.get(2).getAsFloat(), 1};
	}
}
