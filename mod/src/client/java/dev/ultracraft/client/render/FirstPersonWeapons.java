package dev.ultracraft.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.combat.FistType;
import dev.ultracraft.client.render.animation.Animator;
import dev.ultracraft.client.render.animation.Pose;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
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
import java.util.Set;

/**
 * The first-person viewmodels: the held revolver or shotgun and the current arm (the Feedbacker or the
 * Knuckleblaster), each played by its own Animator. The arm is always there; its idle pose keeps it below the
 * screen until it punches or flips a coin.
 */
public final class FirstPersonWeapons {
	public static final FirstPersonWeapons INSTANCE = new FirstPersonWeapons();
	/** ULTRAKILL draws weapons with a 90 degree camera and Minecraft draws hands with a 70 degree one. */
	private static final float FOV_SCALE = (float)(Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(45)));
	/** Unity's Color.red and Color.yellow. */
	private static final float[] RED = {1, 0, 0, 1};
	private static final float[] YELLOW = {1, 0.92156863F, 0.015686275F, 1};
	private static final float[] GRAY = {0.5F, 0.5F, 0.5F, 1};

	/** The shotgun's heat sink glow (HeatSinkHeat). */
	private static final String HEAT_SINK = "HeatSinkHeat";
	/** HeatSinkHeat's tint; its Legacy Particles/Additive shader adds twice the tint, by its alpha. */
	private static final float[] HEAT_TINT = {1, 0.24F, 0};

	public enum Weapon {
		REVOLVER_PIERCER("revolver_piercer"),
		REVOLVER_MARKSMAN("revolver_marksman"),
		REVOLVER_SHARPSHOOTER("revolver_sharpshooter"),
		SHOTGUN_CORE_EJECT("shotgun_core_eject");

		final String model;

		Weapon(String model) {
			this.model = model;
		}

		boolean revolver() {
			return this != SHOTGUN_CORE_EJECT;
		}
	}

	/**
	 * What the held revolver's screen, charge glow, cylinder and twirl show (Revolver's charges, 0 to 100).
	 *
	 * @param coinCharge the marksman's coins (0 to 400) or the sharpshooter's shots (0 to 300)
	 * @param cylinderSpin how fast the charge spins the cylinder (RevolverCylinder.spinSpeed)
	 * @param spinning whether the sharpshooter twirls (the Animator's Spinning)
	 * @param twirlSpeed the Animator's TwirlSpeed
	 * @param twirlAngle degrees the sharpshooter has turned in the hand
	 * @param twirlSpriteAlpha how visible the spin blur is
	 */
	public record RevolverState(
		double pierceShotCharge, double pierceCharge, boolean pierceReady, double coinCharge, double cylinderSpin,
		boolean spinning, double twirlSpeed, double twirlAngle, double twirlSpriteAlpha
	) {
		public static final RevolverState NONE = new RevolverState(0, 100, true, 400, 0, false, 0, 0, 0);
	}

	/**
	 * What the held shotgun shows.
	 *
	 * @param heat its heat sink's glow, 1 after a shot and fading once the animation releases the heat
	 * @param meter its meter's Slider value, 0 to 60
	 * @param charge how far a core is charged, 0 to 1: the meter reddens with it
	 * @param reloadingCore the meter is black while a core's throw reloads
	 * @param jitter how far the gun shakes from its place, in units
	 */
	public record ShotgunState(double heat, double meter, double charge, boolean reloadingCore, Vec3 jitter) {
		public static final ShotgunState NONE = new ShotgunState(0, 60, 0, false, Vec3.ZERO);
	}

	/** The shotgun's meter Slider: 0 to 60, filling from the right, its fill 10 units wider than its share. */
	private static final double METER_MAX = 60;
	private static final float METER_FILL_EXTRA = 10;
	/** The color a charging core's meter reddens towards. */
	private static final float[] METER_CHARGED = {1, 0.25F, 0.25F, 1};

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
	private final Map<FistType, Viewmodel> arms = new EnumMap<>(FistType.class);
	/** FistControl's current arm: the only one enabled. */
	private FistType currentArm = FistType.STANDARD;
	private @Nullable Weapon held;
	private RevolverState state = RevolverState.NONE;
	private ShotgunState shotgun = ShotgunState.NONE;
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
		Viewmodel viewmodel = this.arms.get(this.currentArm);
		if (viewmodel == null) {
			viewmodel = Viewmodel.load(this.currentArm.arm);
			if (viewmodel != null) {
				this.arms.put(this.currentArm, viewmodel);
			}
		}
		return viewmodel;
	}

	/** The held weapon's Animator. */
	public @Nullable Animator animator(Weapon weapon) {
		Viewmodel viewmodel = weapon(weapon);
		return viewmodel == null ? null : viewmodel.animator;
	}

	/** The current arm's Animator. */
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
	 * FistControl.ArmChange: the other arm is enabled, so its Animator starts over, and Punch.OnEnable's
	 * ReadyToPunch brings the arm's parent back from where the last punch turned it.
	 */
	public void changeArm(FistType arm) {
		if (arm == this.currentArm) {
			return;
		}
		this.currentArm = arm;
		Animator animator = armAnimator();
		if (animator != null) {
			animator.reset();
		}
		this.returnToOrigRot = true;
	}

	/**
	 * A bone of the current arm in camera space (Minecraft's axes, units): where it is, and where its own right,
	 * up and forward point, each of length 1.
	 */
	public record Frame(Vector3f position, Vector3f right, Vector3f up, Vector3f forward) {
	}

	/** The current arm's bone with this name, as it's posed now. */
	public @Nullable Frame armBone(String name) {
		return frame(arm(), this.armRotation, name);
	}

	/** The held weapon's bone with this name, as it's posed now. */
	public @Nullable Frame weaponBone(Weapon weapon, String name) {
		return frame(weapon(weapon), new Quaternionf(), name);
	}

	private static @Nullable Frame frame(@Nullable Viewmodel viewmodel, Quaternionf rotation, String name) {
		int bone = viewmodel == null ? -1 : viewmodel.model.bone(name);
		if (bone < 0) {
			return null;
		}
		Matrix4f matrix = viewmodel.model.boneMatrices(viewmodel.pose, rotation)[bone];
		return new Frame(
			matrix.getTranslation(new Vector3f()),
			matrix.getColumn(0, new Vector3f()).normalize(),
			matrix.getColumn(1, new Vector3f()).normalize(),
			matrix.getColumn(2, new Vector3f()).normalize()
		);
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
		Viewmodel arm = arm();
		Vector3f origin = arm == null ? new Vector3f() : arm.model.origin();
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
	public void update(double dt, @Nullable Weapon held, RevolverState state, ShotgunState shotgun, Events events) {
		this.held = held;
		this.state = state;
		this.shotgun = shotgun;
		if (held != null) {
			Viewmodel weapon = weapon(held);
			if (weapon != null) {
				if (held.revolver()) {
					weapon.animator.setBool("Spinning", state.spinning());
					weapon.animator.setFloat("TwirlSpeed", (float)state.twirlSpeed());
				}
				weapon.update(dt, function -> events.weaponEvent(held, function));
				twirl(weapon, state.twirlAngle());
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

	/** Revolver.LateUpdate turns the sharpshooter's twirl bone about its forward axis, on top of its animation. */
	private static void twirl(Viewmodel weapon, double angle) {
		JsonObject revolver = weapon.model.script("revolver");
		if (revolver == null || angle == 0 || !revolver.has("twirlBone") || revolver.get("twirlBone").isJsonNull()) {
			return;
		}
		int bone = revolver.get("twirlBone").getAsInt();
		Quaternionf rotation = new Quaternionf(weapon.pose.qx(bone), weapon.pose.qy(bone), weapon.pose.qz(bone), weapon.pose.qw(bone))
			.rotateZ((float)Math.toRadians(angle));
		weapon.pose.setRotation(bone, rotation.x, rotation.y, rotation.z, rotation.w);
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
		if (weapon != null && this.held.revolver()) {
			submitRevolver(poseStack, collector, light, weapon);
		} else if (weapon != null) {
			submitShotgun(poseStack, collector, light, weapon);
		}
		poseStack.popPose();
		return this.held != null;
	}

	/** The shotgun, with its heat sink glowing after a shot, and its meter. Charging a core shakes it. */
	private void submitShotgun(PoseStack poseStack, SubmitNodeCollector collector, int light, Viewmodel weapon) {
		Vec3 jitter = this.shotgun.jitter();
		poseStack.pushPose();
		poseStack.translate(jitter.x, jitter.y, -jitter.z);
		Matrix4f[] world = weapon.model.boneMatrices(weapon.pose, new Quaternionf());
		weapon.model.submit(poseStack, collector, light, world, Set.of(HEAT_SINK));
		float heat = (float)Math.clamp(this.shotgun.heat(), 0, 1);
		if (heat > 0) {
			int color = 0xFF000000 | channel(2 * HEAT_TINT[0] * heat) << 16 | channel(2 * HEAT_TINT[1] * heat) << 8 | channel(2 * HEAT_TINT[2] * heat);
			weapon.model.submitMaterial(poseStack, collector, world, HEAT_SINK, texture -> RenderTypes.energySwirl(texture, 0, 0), color, LightCoordsUtil.FULL_BRIGHT);
		}
		for (UltraModel.Canvas canvas : weapon.model.canvases()) {
			for (int i = 0; i < canvas.images().size(); i++) {
				UltraModel.Image image = canvas.images().get(i);
				float[] color = image.color();
				if (image.name().equals("Fill")) {
					color = meterColor(image.color());
					image = meterFill(image);
				}
				ModelOverlays.image(poseStack, collector, RenderTypes.text(image.texture()), world[canvas.bone()], image, color, image.fillAmount(), i);
			}
		}
		poseStack.popPose();
	}

	/** Shotgun.UpdateMeter's color: the variant's, reddening with a core's charge, or black while its throw reloads. */
	private float[] meterColor(float[] variation) {
		if (this.shotgun.reloadingCore()) {
			return new float[]{0, 0, 0, 1};
		}
		float t = (float)this.shotgun.charge();
		float[] color = new float[4];
		for (int c = 0; c < 4; c++) {
			color[c] = variation[c] + (METER_CHARGED[c] - variation[c]) * t;
		}
		return color;
	}

	/** The Slider's fill at the meter's value: it keeps its right edge and spans its share of the width. */
	private UltraModel.Image meterFill(UltraModel.Image full) {
		float[] r = full.rect();
		float share = (float)(this.shotgun.meter() / METER_MAX);
		float width = (r[2] - r[0] - METER_FILL_EXTRA) * share + METER_FILL_EXTRA;
		return full.withRect(new float[]{r[2] - width, r[1], r[2], r[3]});
	}

	private static int channel(float value) {
		return Math.round(Math.clamp(value, 0, 1) * 255);
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
			submitTwirlSprite(poseStack, collector, weapon.model, world, revolver);
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

	/** The sharpshooter's spin blur, fading in with the charge. */
	private void submitTwirlSprite(PoseStack poseStack, SubmitNodeCollector collector, UltraModel model, Matrix4f[] world, JsonObject revolver) {
		JsonElement name = revolver.get("twirlSprite");
		if (name == null || name.isJsonNull() || this.state.twirlSpriteAlpha() <= 0) {
			return;
		}
		for (UltraModel.Sprite sprite : model.sprites()) {
			if (sprite.name().equals(name.getAsString())) {
				float[] color = {1, 1, 1, (float)this.state.twirlSpriteAlpha()};
				ModelOverlays.sprite(poseStack, collector, RenderTypes.text(sprite.texture()), world[sprite.bone()], sprite.rect(), color);
			}
		}
	}

	/**
	 * The marksman's coin panels and the sharpshooter's charge meters (Revolver.CheckCoinCharges): one per coin or
	 * shot, filling as it recharges, red (the marksman's) or gray (the sharpshooter's) until full, then in the
	 * variant's color.
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
		float[] charging = revolver.get("variation").getAsInt() == 1 ? RED : GRAY;
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
						color = fill < 1 ? charging : variation;
					}
				}
				ModelOverlays.image(poseStack, collector, RenderTypes.text(image.texture()), matrix, image, color, fill, i);
			}
		}
	}

	private static float[] variationColor(JsonObject revolver) {
		JsonArray color = revolver.getAsJsonArray("variationColor");
		return new float[]{color.get(0).getAsFloat(), color.get(1).getAsFloat(), color.get(2).getAsFloat(), 1};
	}
}
