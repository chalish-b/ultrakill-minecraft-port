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
import java.util.function.IntPredicate;

/**
 * The first-person viewmodels: the held revolver, shotgun, nailgun, railcannon or rocket launcher, the current arm (the Feedbacker or the
 * Knuckleblaster) and the Whiplash's arm, each played by its own Animator. The arm is always there; its idle pose
 * keeps it below the screen until it punches or flips a coin. The Whiplash's arm shows from a throw until it has
 * caught its hook again.
 */
public final class FirstPersonWeapons {
	public static final FirstPersonWeapons INSTANCE = new FirstPersonWeapons();
	/** ULTRAKILL draws weapons with a camera of its own (the HUD camera), 90 degrees unless zoomed. */
	private static final double WEAPON_FOV = 90;
	/** Minecraft's camera for hands (Camera.BASE_HUD_FOV). */
	private static final double HUD_FOV = 70;
	private static final String WHIPLASH = "whiplash";
	/** Unity's Color.red, Color.yellow and Color.black. */
	private static final float[] RED = {1, 0, 0, 1};
	private static final float[] YELLOW = {1, 0.92156863F, 0.015686275F, 1};
	private static final float[] BLACK = {0, 0, 0, 1};
	private static final float[] GRAY = {0.5F, 0.5F, 0.5F, 1};

	/** The shotgun's heat sink glow (HeatSinkHeat). */
	private static final String HEAT_SINK = "HeatSinkHeat";
	/** HeatSinkHeat's tint; its Legacy Particles/Additive shader adds twice the tint, by its alpha. */
	private static final float[] HEAT_TINT = {1, 0.24F, 0};
	/** The slab revolver's fan the hammer flash (Revolver.quickTwirlEffect). */
	private static final String QUICK_TWIRL = "quick_twirl";
	/** The Sawed-On's chainsaw and its blade, and the bone the chainsaw sits on. */
	private static final String CHAINSAW = "ChainsawLit";
	private static final String CHAINSAW_BLADE = "ChainsawLitBlade";
	private static final String CHAINSAW_BONE = "Chainsaw";
	/** The worn-out chainsaw's Vibrate: how far it shakes from its place and how fast, in its own units. */
	private static final float SAW_SHAKE = 10;
	private static final float SAW_SHAKE_SPEED = 1500;

	public enum Weapon {
		REVOLVER_PIERCER("revolver_piercer"),
		REVOLVER_MARKSMAN("revolver_marksman"),
		REVOLVER_SHARPSHOOTER("revolver_sharpshooter"),
		REVOLVER_SLAB_PIERCER("revolver_slab_piercer"),
		REVOLVER_SLAB_MARKSMAN("revolver_slab_marksman"),
		REVOLVER_SLAB_SHARPSHOOTER("revolver_slab_sharpshooter"),
		SHOTGUN_CORE_EJECT("shotgun_core_eject"),
		SHOTGUN_PUMP_CHARGE("shotgun_pump_charge"),
		SHOTGUN_SAWED_ON("shotgun_sawed_on"),
		NAILGUN_ATTRACTOR("nailgun_attractor"),
		NAILGUN_OVERHEAT("nailgun_overheat"),
		NAILGUN_JUMPSTART("nailgun_jumpstart"),
		RAILCANNON_ELECTRIC("railcannon_electric"),
		RAILCANNON_SCREWDRIVER("railcannon_screwdriver"),
		RAILCANNON_MALICIOUS("railcannon_malicious"),
		ROCKET_LAUNCHER_FREEZEFRAME("rocket_launcher_freezeframe"),
		ROCKET_LAUNCHER_SRS("rocket_launcher_srs"),
		ROCKET_LAUNCHER_FIRESTARTER("rocket_launcher_firestarter");

		final String model;

		Weapon(String model) {
			this.model = model;
		}

		boolean revolver() {
			return this.model.startsWith("revolver_");
		}

		boolean railcannon() {
			return this.model.startsWith("railcannon_");
		}

		boolean nailgun() {
			return this.model.startsWith("nailgun_");
		}

		boolean rocketLauncher() {
			return this.model.startsWith("rocket_launcher_");
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

	/** The shotgun meter fill's color: its variant's (reddened by some amount), or red, black or gray. */
	public enum MeterTint {
		VARIANT,
		RED,
		BLACK,
		GRAY
	}

	/**
	 * The Sawed-On's chainsaw.
	 *
	 * @param attached whether it's on the gun, as it is once charged
	 * @param sawing whether it's revving
	 * @param broken whether it's worn out
	 * @param bladeScroll how far the blade's texture has scrolled across
	 */
	public record SawState(boolean attached, boolean sawing, boolean broken, double bladeScroll) {
		public static final SawState NONE = new SawState(false, false, false, 0);
	}

	/**
	 * What the held shotgun shows.
	 *
	 * @param heat its heat sink's glow, 1 after a shot and fading once the animation releases the heat
	 * @param meter its meter's Slider value, 0 to 60
	 * @param reddening how far a {@link MeterTint#VARIANT} meter has reddened, 0 to 1
	 * @param jitter how far the gun shakes from its place, in units
	 */
	/**
	 * What the rocket launcher's dial shows (RocketLauncher.Update), how the gun shakes, and the Firestarter's spray.
	 *
	 * @param timer how full the dial is, 0 to 1
	 * @param reddening how far its colors have turned red, 0 to 1
	 * @param dimmed whether its colors show at half their opacity
	 * @param gray whether its colors are gray instead of the variant's
	 * @param jitter how far the gun shakes from its place, in units
	 * @param napalmFlash the scale of the Firestarter's muzzle flash
	 * @param spraying whether the Firestarter sprays, its muzzle flash giving off particles
	 */
	public record RocketLauncherState(double timer, double reddening, boolean dimmed, boolean gray, Vec3 jitter, double napalmFlash, boolean spraying) {
		public static final RocketLauncherState NONE = new RocketLauncherState(1, 0, false, false, Vec3.ZERO, 0, false);
	}

	public record ShotgunState(double heat, double meter, MeterTint tint, double reddening, Vec3 jitter, SawState saw) {
		public static final ShotgunState NONE = new ShotgunState(0, 60, MeterTint.VARIANT, 0, Vec3.ZERO, SawState.NONE);
	}

	/**
	 * What the held nailgun shows.
	 *
	 * @param barrelAngle degrees its barrels have spun, each about its own axis (Spin)
	 * @param heat how much its barrels glow (Nailgun.SetHeat), 0 to 1
	 * @param heatMeter the Overheat's heat meter (its Slider's value), 0 to 1
	 * @param heatMeterBackground the heat meter's background color (Nailgun's sliderBg)
	 * @param charges how full each heat sink or magnet charge shows, 0 to 1
	 * @param chargesFull whether each shows full, in the variant's color rather than the empty gray
	 * @param ammo the Attractor's nails
	 * @param layerWeight how much its Animator's second layer holds the gun steady
	 * @param steam whether the Overheat's barrels let off steam
	 * @param zap the JumpStart's screen
	 */
	public record NailgunState(
		double barrelAngle, double heat, double heatMeter, float[] heatMeterBackground, double[] charges, boolean[] chargesFull, int ammo,
		float layerWeight, boolean steam, ZapHud zap
	) {
		public static final NailgunState NONE = new NailgunState(0, 0, 0, new float[]{0, 0, 0, 0}, new double[0], new boolean[0], 0, 0, false, ZapHud.NONE);
	}

	/**
	 * What the JumpStart's screen shows.
	 *
	 * @param recharging whether the recharge overlay is up, and how far round its meter has filled
	 * @param status the status text and its color, or the variant's color if {@code statusVariant}
	 * @param distanceMeter the distance meter's value, 0 to 1
	 * @param zapMeterShown whether the zap meter shows, and its value, 0 to 1
	 * @param warning whether the warning X shows, and its color
	 */
	public record ZapHud(
		boolean recharging, double rechargeFill, String status, float[] statusColor, boolean statusVariant, double distanceMeter, boolean zapMeterShown,
		double zapMeter, boolean warning, float[] warningColor
	) {
		public static final ZapHud NONE = new ZapHud(false, 0, "", new float[]{1, 1, 1, 1}, false, 0, false, 0, false, new float[]{1, 1, 1, 1});
	}

	/** The shotgun's meter Slider: 0 to 60, filling from the right, its fill 10 units wider than its share. */
	private static final double METER_MAX = 60;
	private static final float METER_FILL_EXTRA = 10;
	/** The color a shotgun's meter reddens towards as it charges. */
	private static final float[] METER_CHARGED = {1, 0.25F, 0.25F, 1};

	/** Clip events from the held weapon and from the arm, and the held revolver's cylinder clicking into place. */
	public interface Events {
		void weaponEvent(Weapon weapon, String function, float floatParameter);

		void armEvent(String function);

		void whiplashEvent(String function);

		void cylinderClicked();
	}

	/**
	 * A railcannon's charge pip (RailCannonPip): pushed out of place while the charge is below its level, and
	 * spinning in place once it's reached.
	 */
	private static final class Pip {
		/** It spins this many degrees a second about its own up axis. */
		private static final float SPIN = -2400;
		/** It moves towards where it's going by this share of the way, every second. */
		private static final float EASE = 50;

		final int bone;
		final double chargeLevel;
		final Vector3f origPos;
		final Vector3f targetPos;
		final Quaternionf origRot;
		final Vector3f pos;
		final Quaternionf rot;

		Pip(JsonObject pip, Pose rest) {
			this.bone = pip.get("bone").getAsInt();
			this.chargeLevel = pip.get("chargeLevel").getAsDouble();
			JsonArray push = pip.getAsJsonArray("pushAmount");
			this.origPos = new Vector3f(rest.px(this.bone), rest.py(this.bone), rest.pz(this.bone));
			this.targetPos = new Vector3f(push.get(0).getAsFloat(), push.get(1).getAsFloat(), push.get(2).getAsFloat()).mul(0.01F).add(this.origPos);
			this.origRot = new Quaternionf(rest.qx(this.bone), rest.qy(this.bone), rest.qz(this.bone), rest.qw(this.bone));
			this.pos = new Vector3f(this.origPos);
			this.rot = new Quaternionf(this.origRot);
		}

		/** RailCannonPip.LateUpdate, then onto the pose. */
		void update(double dt, double charge, Pose pose) {
			boolean charged = charge >= this.chargeLevel;
			Vector3f target = charged ? this.origPos : this.targetPos;
			Vector3f toward = new Vector3f(target).sub(this.pos);
			float step = (float)(toward.length() * EASE * dt);
			this.pos.set(toward.length() <= step ? target : this.pos.add(toward.normalize(step)));
			if (charged) {
				this.rot.rotateY((float)Math.toRadians(SPIN * dt));
			} else {
				this.rot.set(this.origRot);
			}
			pose.setPosition(this.bone, this.pos.x, this.pos.y, this.pos.z);
			pose.setRotation(this.bone, this.rot.x, this.rot.y, this.rot.z, this.rot.w);
		}
	}

	/** A model with the Animator that plays it, and the ports of the scripts that draw on it. */
	private static final class Viewmodel {
		final UltraModel model;
		final Animator animator;
		final Pose pose;
		final @Nullable RevolverCylinder cylinder;
		final List<Pip> pips = new ArrayList<>();
		/** A railcannon's FullCharge particle systems, which only run at full charge. */
		final List<ViewmodelParticles> fullChargeParticles = new ArrayList<>();
		/** A nailgun's heat steam. */
		final List<ViewmodelParticles> steamParticles = new ArrayList<>();
		/** The Firestarter's spray, off its muzzle flash, and its particles' start color. */
		final List<ViewmodelParticles> napalmParticles = new ArrayList<>();
		final List<float[]> napalmParticleColors = new ArrayList<>();

		Viewmodel(UltraModel model, Animator animator) {
			this.model = model;
			this.animator = animator;
			this.pose = model.rest().copy();
			JsonObject cylinder = model.script("cylinder");
			this.cylinder = cylinder == null ? null : new RevolverCylinder(cylinder, model.rest());
			for (JsonElement pip : model.scripts("railcannonPips")) {
				this.pips.add(new Pip(pip.getAsJsonObject(), model.rest()));
			}
			JsonObject railcannon = model.script("railcannon");
			if (railcannon != null) {
				int fullCharge = railcannon.get("fullCharge").getAsInt();
				for (JsonElement system : model.particles()) {
					int bone = system.getAsJsonObject().get("bone").getAsInt();
					while (bone >= 0 && bone != fullCharge) {
						bone = model.parent(bone);
					}
					if (bone == fullCharge) {
						this.fullChargeParticles.add(new ViewmodelParticles(system.getAsJsonObject(), model.rest().sx(system.getAsJsonObject().get("bone").getAsInt())));
					}
				}
			}
			if (model.script("nailgun") != null) {
				for (JsonElement system : model.particles()) {
					this.steamParticles.add(new ViewmodelParticles(system.getAsJsonObject(), model.rest().sx(system.getAsJsonObject().get("bone").getAsInt())));
				}
			}
			JsonObject rocketLauncher = model.script("rocketLauncher");
			if (rocketLauncher != null && rocketLauncher.has("napalmMuzzleFlashParticles")) {
				int napalm = rocketLauncher.get("napalmMuzzleFlashParticles").getAsInt();
				for (JsonElement element : model.particles()) {
					JsonObject system = element.getAsJsonObject();
					if (system.get("bone").getAsInt() == napalm) {
						this.napalmParticles.add(new ViewmodelParticles(system, model.rest().sx(napalm)));
						this.napalmParticleColors.add(color(system.getAsJsonObject("color").getAsJsonArray("color")));
					}
				}
			}
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
	private @Nullable Viewmodel whiplashModel;
	private boolean whiplashMissing;
	/** FistControl's current arm: the only one enabled. */
	private FistType currentArm = FistType.STANDARD;
	private @Nullable Weapon held;
	private RevolverState state = RevolverState.NONE;
	private ShotgunState shotgun = ShotgunState.NONE;
	private NailgunState nailgun = NailgunState.NONE;
	/** The arm's parent (FistControl's "Punch"), turned towards what it last hit. */
	private final Quaternionf armRotation = new Quaternionf();
	private boolean returnToOrigRot;
	/** HookArm's model is shown. */
	private boolean whiplashShown;
	/** HookArm.hand: where the wire leaves the hand, as HookArm places it. */
	private final Vector3f wireStart = new Vector3f();
	/** The hook out of the hand: where it is in the world and which way it points (HookArm.LateUpdate), or null while it's in the hand. */
	private @Nullable Vec3 hookPoint;
	private Vec3 hookDirection = new Vec3(0, 0, 1);
	/** The worn-out chainsaw's shake: where it is from its place, and where it's heading. */
	private final Vector3f sawShake = new Vector3f();
	private final Vector3f sawShakeTarget = new Vector3f();
	/** The railcannons' charge, 0 to 5 (WeaponCharges.raicharge). */
	private double railcannonCharge = 5;
	/** What the rocket launcher's dial shows. */
	private RocketLauncherState rocketLauncher = RocketLauncherState.NONE;
	/** How long the Firestarter's muzzle flash sprite has been spinning (seconds). */
	private double napalmSpin;
	/** The slab revolver's quick twirl flash, while it's out. */
	private @Nullable QuickTwirl quickTwirl;
	/** The HUD camera's field of view (degrees), and whether a zoom narrows it (CameraController.Zoom). */
	private double weaponFov = WEAPON_FOV;
	private boolean zooming;

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

	private @Nullable Viewmodel whiplash() {
		Viewmodel viewmodel = this.whiplashModel;
		if (viewmodel == null && !this.whiplashMissing) {
			viewmodel = Viewmodel.load(WHIPLASH);
			this.whiplashModel = viewmodel;
			this.whiplashMissing = viewmodel == null;
			if (viewmodel != null) {
				int hand = viewmodel.model.script("hookArm").get("hand").getAsInt();
				this.wireStart.set(viewmodel.model.rest().px(hand), viewmodel.model.rest().py(hand), viewmodel.model.rest().pz(hand));
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

	/** HookArm's Animator. */
	public @Nullable Animator whiplashAnimator() {
		Viewmodel viewmodel = whiplash();
		return viewmodel == null ? null : viewmodel.animator;
	}

	/** HookArm's model.SetActive. */
	public void showWhiplash(boolean shown) {
		this.whiplashShown = shown;
	}

	/** HookArm sets its hand's local position: where the wire leaves the hand. */
	public void setWireStart(float x, float y, float z) {
		this.wireStart.set(x, y, z);
	}

	/** HookArm.LateUpdate: the hook out of the hand, at {@code point} pointing along {@code direction}; null puts it back in the hand. */
	public void placeHook(@Nullable Vec3 point, Vec3 direction) {
		this.hookPoint = point;
		this.hookDirection = direction;
	}

	/**
	 * Punch.CancelAttack, and FistControl's rotation reset, as the Whiplash is thrown: the arm's Animator starts over
	 * and the arm faces ahead again.
	 */
	public void cancelPunch() {
		Animator animator = armAnimator();
		if (animator != null) {
			animator.reset();
		}
		this.armRotation.identity();
		this.returnToOrigRot = false;
	}

	/**
	 * Where the wire leaves the Whiplash's hand, in the world: the point on the hand as it's drawn, at its depth.
	 * Viewmodels are drawn through a camera of their own, so this is the world point that shows in the same place.
	 */
	public @Nullable Vec3 wireStartWorld() {
		Viewmodel viewmodel = whiplash();
		int bone = viewmodel == null ? -1 : viewmodel.model.script("hookArm").get("hand").getAsInt();
		if (bone < 0) {
			return null;
		}
		return drawnAt(viewmodel.model.boneMatrices(viewmodel.pose, this.armRotation)[bone].getTranslation(new Vector3f()));
	}

	/** The world point that shows where a point of a viewmodel (camera space, units) is drawn, at its depth. */
	private Vec3 drawnAt(Vector3f point) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		float spread = drawnSpread();
		point.mul(spread, spread, 1).mul((float)PlayerShape.BLOCKS_PER_UNIT);
		Vector3f world = new Quaternionf(camera.rotation()).transform(point);
		return camera.position().add(world.x, world.y, world.z);
	}

	/**
	 * How much wider the viewmodels show than they would in the world at the same depth: what sizes across the view
	 * are scaled by to show in the world as the HUD camera shows them.
	 */
	private float drawnSpread() {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		return (float)(fovScale() * Math.tan(Math.toRadians(camera.getFov() / 2)) / Math.tan(Math.toRadians(HUD_FOV / 2)));
	}

	/**
	 * The hook out of the hand, drawn in the world where HookArm put it (the hand's hook mesh goes where its bone is
	 * placed, pointing along the throw), lit by where it is.
	 */
	public void submitHook(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camera, int light) {
		Viewmodel viewmodel = whiplash();
		if (viewmodel == null || !this.whiplashShown || this.hookPoint == null) {
			return;
		}
		JsonObject script = viewmodel.model.script("hookArm");
		int hook = script.get("hook").getAsInt();
		String hookModel = script.get("hookModel").getAsString();
		Matrix4f[] world = viewmodel.model.boneMatrices(viewmodel.pose, this.armRotation);
		// Transform.up = direction: the rotation from up to it, in Unity's axes; the hook keeps its scale.
		Vector3f direction = unity(new Vector3f((float)this.hookDirection.x, (float)this.hookDirection.y, (float)this.hookDirection.z));
		Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), direction);
		Vector3f scale = world[hook].getScale(new Vector3f());
		Matrix4f placed = new Matrix4f().scaling(1, 1, -1).rotate(rotation).scale(scale);
		Matrix4f toPlaced = placed.mul(new Matrix4f(world[hook]).invert());
		Matrix4f[] moved = new Matrix4f[world.length];
		for (int i = 0; i < world.length; i++) {
			moved[i] = new Matrix4f(toPlaced).mul(world[i]);
		}
		float units = (float)PlayerShape.BLOCKS_PER_UNIT;
		poseStack.pushPose();
		poseStack.translate(this.hookPoint.x - camera.x, this.hookPoint.y - camera.y, this.hookPoint.z - camera.z);
		poseStack.scale(units, units, units);
		viewmodel.model.submitObjects(poseStack, collector, light, moved, hookModel::equals);
		poseStack.popPose();
	}

	/** A weapon drawn: its object is enabled, so its Animator starts over (the revolver's PickUp). */
	public void equip(Weapon weapon) {
		Animator animator = animator(weapon);
		if (animator != null) {
			animator.reset();
		}
		Viewmodel viewmodel = weapon(weapon);
		if (viewmodel != null) {
			viewmodel.fullChargeParticles.forEach(ViewmodelParticles::stop);
			viewmodel.steamParticles.forEach(ViewmodelParticles::stop);
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

	/**
	 * The held weapon's bone {@code bone} as it's posed now: its matrix in camera space (Minecraft's axes, units),
	 * scale included.
	 */
	public @Nullable Matrix4f weaponBoneMatrix(Weapon weapon, int bone) {
		Viewmodel viewmodel = weapon(weapon);
		return viewmodel == null ? null : viewmodel.model.boneMatrices(viewmodel.pose, new Quaternionf())[bone];
	}

	/** RevolverCylinder.DoTurn, after a shot. */
	public void turnCylinder(Weapon weapon) {
		Viewmodel viewmodel = weapon(weapon);
		if (viewmodel != null && viewmodel.cylinder != null) {
			viewmodel.cylinder.turn();
		}
	}

	/**
	 * Revolver.InstaClick's QuickTwirlEffect: spawned at the cylinder, turned as the revolver is, and carried along
	 * with the cylinder from then on.
	 */
	public void quickTwirl(Weapon weapon) {
		Viewmodel viewmodel = weapon(weapon);
		if (viewmodel == null || viewmodel.cylinder == null) {
			return;
		}
		Matrix4f[] world = viewmodel.model.boneMatrices(viewmodel.pose, new Quaternionf());
		Matrix4f cylinder = world[viewmodel.cylinder.bone()];
		Matrix4f spawned = new Matrix4f(world[0]).setTranslation(cylinder.getTranslation(new Vector3f()));
		this.quickTwirl = new QuickTwirl(weapon, new Matrix4f(cylinder).invert().mul(spawned));
	}

	/**
	 * QuickTwirlEffect's sprite, relative to the cylinder it's parented to: its ScaleTransform shrinks it from its
	 * prefab scale to nothing at 100 units of scale a second.
	 */
	private static final class QuickTwirl {
		private static final double SHRINK_SPEED = 100;
		final Weapon weapon;
		final Matrix4f fromCylinder;
		double age;

		QuickTwirl(Weapon weapon, Matrix4f fromCylinder) {
			this.weapon = weapon;
			this.fromCylinder = fromCylinder;
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

	/** How much bigger the HUD camera draws the viewmodels than Minecraft's hand camera would. */
	private float fovScale() {
		return (float)(Math.tan(Math.toRadians(HUD_FOV / 2)) / Math.tan(Math.toRadians(this.weaponFov / 2)));
	}

	/** CameraController.Zoom and StopZoom: the HUD camera narrows to half the player's field of view. */
	public void zoom(boolean zooming) {
		this.zooming = zooming;
	}

	/**
	 * Runs the held weapon's and the arm's Animators, and the scripts that move them after.
	 *
	 * @param railcannonCharge the railcannons' charge, 0 to 5
	 * @param rocketLauncher what the rocket launcher's dial shows
	 */
	public void update(
		double dt, @Nullable Weapon held, RevolverState state, ShotgunState shotgun, NailgunState nailgun, double railcannonCharge, RocketLauncherState rocketLauncher,
		Events events
	) {
		this.held = held;
		this.state = state;
		this.shotgun = shotgun;
		this.nailgun = nailgun;
		this.railcannonCharge = railcannonCharge;
		this.rocketLauncher = rocketLauncher;
		if (this.quickTwirl != null) {
			this.quickTwirl.age += dt;
			if (this.quickTwirl.weapon != held) {
				this.quickTwirl = null;
			}
		}
		double fovTarget = this.zooming ? Minecraft.getInstance().options.fov().get() / 2.0 : WEAPON_FOV;
		double fovStep = 300 * dt;
		this.weaponFov = Math.abs(fovTarget - this.weaponFov) <= fovStep ? fovTarget : this.weaponFov + Math.signum(fovTarget - this.weaponFov) * fovStep;
		if (held != null) {
			Viewmodel weapon = weapon(held);
			if (weapon != null) {
				if (held.revolver()) {
					weapon.animator.setBool("Spinning", state.spinning());
					weapon.animator.setFloat("TwirlSpeed", (float)state.twirlSpeed());
				} else if (held.nailgun()) {
					weapon.animator.setLayerWeight(1, nailgun.layerWeight());
				} else if (!held.railcannon() && !held.rocketLauncher()) {
					weapon.animator.setBool("Sawing", shotgun.saw().sawing());
				}
				weapon.update(dt, (function, floatParameter) -> events.weaponEvent(held, function, floatParameter));
				twirl(weapon, state.twirlAngle());
				if (held.nailgun()) {
					spinBarrels(weapon, nailgun.barrelAngle());
					for (ViewmodelParticles system : weapon.steamParticles) {
						system.update(dt, nailgun.steam());
					}
				}
				for (Pip pip : weapon.pips) {
					pip.update(dt, railcannonCharge, weapon.pose);
				}
				for (ViewmodelParticles system : weapon.fullChargeParticles) {
					if (railcannonCharge >= 5) {
						system.update(dt);
					} else {
						system.stop();
					}
				}
				napalmFlash(dt, weapon, rocketLauncher);
				shakeSaw(dt, weapon, shotgun.saw().broken());
				if (weapon.cylinder != null && weapon.cylinder.update(dt, state.cylinderSpin(), weapon.pose)) {
					events.cylinderClicked();
				}
			}
		}
		Viewmodel arm = arm();
		if (arm != null) {
			arm.update(dt, (function, floatParameter) -> {
				armEvent(function);
				events.armEvent(function);
			});
		}
		Viewmodel whiplash = whiplash();
		if (whiplash != null) {
			whiplash.update(dt, (function, floatParameter) -> events.whiplashEvent(function));
			int hand = whiplash.model.script("hookArm").get("hand").getAsInt();
			whiplash.pose.setPosition(hand, this.wireStart.x, this.wireStart.y, this.wireStart.z);
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

	/**
	 * The Firestarter's muzzle flash at its scale, its sprite spinning (Spin), and its particles coming off it while
	 * it sprays.
	 */
	private void napalmFlash(double dt, Viewmodel weapon, RocketLauncherState state) {
		JsonObject script = weapon.model.script("rocketLauncher");
		if (script == null || !script.has("napalmMuzzleFlash")) {
			return;
		}
		float scale = (float)state.napalmFlash();
		weapon.pose.setScale(script.get("napalmMuzzleFlash").getAsInt(), scale, scale, scale);
		this.napalmSpin += dt;
		for (JsonElement element : weapon.model.scripts("spins")) {
			JsonObject spin = element.getAsJsonObject();
			int bone = spin.get("bone").getAsInt();
			JsonArray axis = spin.getAsJsonArray("axis");
			Vector3f direction = new Vector3f(axis.get(0).getAsFloat(), axis.get(1).getAsFloat(), axis.get(2).getAsFloat()).normalize();
			Quaternionf rotation = new Quaternionf(weapon.model.rest().qx(bone), weapon.model.rest().qy(bone), weapon.model.rest().qz(bone), weapon.model.rest().qw(bone))
				.rotateAxis((float)Math.toRadians(spin.get("speed").getAsDouble() * this.napalmSpin % 360), direction);
			weapon.pose.setRotation(bone, rotation.x, rotation.y, rotation.z, rotation.w);
		}
		for (ViewmodelParticles system : weapon.napalmParticles) {
			system.update(dt, state.spraying());
		}
	}

	/** Spin on the nailgun's barrels: each turned about its own axis by how far they've spun. */
	private static void spinBarrels(Viewmodel weapon, double angle) {
		for (JsonElement element : weapon.model.scripts("spins")) {
			JsonObject spin = element.getAsJsonObject();
			int bone = spin.get("bone").getAsInt();
			JsonArray axis = spin.getAsJsonArray("axis");
			Vector3f direction = new Vector3f(axis.get(0).getAsFloat(), axis.get(1).getAsFloat(), axis.get(2).getAsFloat()).normalize();
			Quaternionf rotation = new Quaternionf(weapon.pose.qx(bone), weapon.pose.qy(bone), weapon.pose.qz(bone), weapon.pose.qw(bone))
				.rotateAxis((float)Math.toRadians(angle % 360), direction);
			weapon.pose.setRotation(bone, rotation.x, rotation.y, rotation.z, rotation.w);
		}
	}

	/**
	 * Revolver.LateUpdate turns the sharpshooter's twirl bone on top of its animation, adding the angle to its Euler
	 * angles: to z (its forward axis) on the standard revolver, and taking it from x on the slab.
	 */
	private static void twirl(Viewmodel weapon, double angle) {
		JsonObject revolver = weapon.model.script("revolver");
		if (revolver == null || angle == 0 || !revolver.has("twirlBone") || revolver.get("twirlBone").isJsonNull()) {
			return;
		}
		int bone = revolver.get("twirlBone").getAsInt();
		Quaternionf rotation = new Quaternionf(weapon.pose.qx(bone), weapon.pose.qy(bone), weapon.pose.qz(bone), weapon.pose.qw(bone));
		if (revolver.get("altVersion").getAsBoolean()) {
			// Quaternion.Euler: about z, then x, then y, as rotationYXZ composes them.
			Vector3f euler = rotation.getEulerAnglesYXZ(new Vector3f());
			rotation.rotationYXZ(euler.y, euler.x - (float)Math.toRadians(angle), euler.z);
		} else {
			rotation.rotateZ((float)Math.toRadians(angle));
		}
		weapon.pose.setRotation(bone, rotation.x, rotation.y, rotation.z, rotation.w);
	}

	/** Vibrate on the worn-out chainsaw: it darts between random points around its place. */
	private void shakeSaw(double dt, Viewmodel weapon, boolean broken) {
		int bone = weapon.model.bone(CHAINSAW_BONE);
		if (bone < 0) {
			return;
		}
		if (!broken) {
			this.sawShake.zero();
			this.sawShakeTarget.zero();
			return;
		}
		if (this.sawShake.equals(this.sawShakeTarget)) {
			Vector3f target;
			do {
				target = new Vector3f(this.random.nextFloat() * 2 - 1, this.random.nextFloat() * 2 - 1, this.random.nextFloat() * 2 - 1);
			} while (target.lengthSquared() > 1);
			this.sawShakeTarget.set(target.mul(SAW_SHAKE));
		} else {
			Vector3f toward = new Vector3f(this.sawShakeTarget).sub(this.sawShake);
			float step = (float)(SAW_SHAKE_SPEED * dt);
			this.sawShake.set(toward.length() <= step ? this.sawShakeTarget : this.sawShake.add(toward.normalize(step)));
		}
		Pose rest = weapon.model.rest();
		weapon.pose.setPosition(bone, rest.px(bone) + this.sawShake.x, rest.py(bone) + this.sawShake.y, rest.pz(bone) + this.sawShake.z);
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
		poseStack.scale(fovScale(), fovScale(), 1);
		Viewmodel arm = arm();
		if (arm != null) {
			arm.model.submit(poseStack, collector, light, arm.model.boneMatrices(arm.pose, this.armRotation));
		}
		Viewmodel whiplash = whiplash();
		if (whiplash != null && this.whiplashShown) {
			// The hook is drawn in the world while it's out of the hand.
			String hookModel = whiplash.model.script("hookArm").get("hookModel").getAsString();
			whiplash.model.submitObjects(poseStack, collector, light, whiplash.model.boneMatrices(whiplash.pose, this.armRotation),
				name -> this.hookPoint == null || !name.equals(hookModel));
		}
		Viewmodel weapon = this.held == null ? null : weapon(this.held);
		if (weapon != null && this.held.revolver()) {
			submitRevolver(poseStack, collector, light, weapon);
		} else if (weapon != null && this.held.railcannon()) {
			submitRailcannon(poseStack, collector, light, weapon);
		} else if (weapon != null && this.held.nailgun()) {
			submitNailgun(poseStack, collector, light, weapon);
		} else if (weapon != null && this.held.rocketLauncher()) {
			submitRocketLauncher(poseStack, collector, light, weapon);
		} else if (weapon != null) {
			submitShotgun(poseStack, collector, light, weapon);
		}
		poseStack.popPose();
		return this.held != null;
	}

	/**
	 * The shotgun, with its heat sink glowing after a shot, and its meter. Charging shakes it. The Sawed-On's
	 * chainsaw is on it while charged; its blade blurs and runs while revving, and it looks worn out once worn.
	 */
	private void submitShotgun(PoseStack poseStack, SubmitNodeCollector collector, int light, Viewmodel weapon) {
		Vec3 jitter = this.shotgun.jitter();
		poseStack.pushPose();
		poseStack.translate(jitter.x, jitter.y, -jitter.z);
		Matrix4f[] world = weapon.model.boneMatrices(weapon.pose, new Quaternionf());
		weapon.model.submit(poseStack, collector, light, world, Set.of(HEAT_SINK, CHAINSAW, CHAINSAW_BLADE));
		JsonObject script = weapon.model.script("shotgun");
		SawState saw = this.shotgun.saw();
		if (saw.attached() && script != null && script.has("brokenTexture")) {
			Identifier broken = Identifier.parse(script.get("brokenTexture").getAsString());
			Identifier blur = Identifier.parse(script.get("bladeMotionTexture").getAsString());
			weapon.model.submitMaterial(poseStack, collector, world, CHAINSAW, texture -> RenderTypes.entityCutout(saw.broken() ? broken : texture), 0xFFFFFFFF, light);
			weapon.model.submitMaterial(poseStack, collector, world, CHAINSAW_BLADE, texture -> RenderTypes.entityCutout(saw.sawing() ? blur : texture), 0xFFFFFFFF, light, (float)saw.bladeScroll());
		}
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
					color = meterColor(variationColor(weapon.model.script("shotgun")));
					image = meterFill(image);
				}
				ModelOverlays.image(poseStack, collector, RenderTypes.text(image.texture()), world[canvas.bone()], image, color, image.fillAmount(), i);
			}
		}
		poseStack.popPose();
	}

	/**
	 * The railcannon (Railcannon.SetMaterialIntensity): recharging, its body glows by the charge and each pip lights
	 * up in turn; fully charged, everything glows, the gun trembles, and what's on its FullCharge object shows (its
	 * sprite, arcs in the variant's color, the Screwdriver's drill tip).
	 */
	private void submitRailcannon(PoseStack poseStack, SubmitNodeCollector collector, int light, Viewmodel weapon) {
		JsonObject script = weapon.model.script("railcannon");
		if (script == null) {
			return;
		}
		double charge = this.railcannonCharge;
		boolean full = charge >= 5;
		poseStack.pushPose();
		if (full) {
			float jitter = 0.005F;
			poseStack.translate(this.random.nextFloat() * 2 * jitter - jitter, this.random.nextFloat() * 2 * jitter - jitter, this.random.nextFloat() * 2 * jitter - jitter);
		}
		float[] color = variationColor(script);
		String body = script.get("body").getAsString();
		List<String> pips = new ArrayList<>();
		for (JsonElement pip : script.getAsJsonArray("pips")) {
			pips.add(pip.getAsString());
		}
		Matrix4f[] world = weapon.model.boneMatrices(weapon.pose, new Quaternionf());
		int fullChargeBone = script.get("fullCharge").getAsInt();
		IntPredicate shown = bone -> full || !descends(weapon.model, bone, fullChargeBone);
		weapon.model.submitGlowing(poseStack, collector, light, world, shown, object -> {
			double intensity;
			if (full) {
				intensity = 1;
			} else if (object.equals(body)) {
				intensity = charge / 5;
			} else if (pips.contains(object)) {
				int i = pips.indexOf(object);
				intensity = charge > i + 1 ? 1 : Math.max(0, (charge - i) % 1);
			} else {
				return null;
			}
			float glow = (float)intensity;
			return 0xFF000000 | channel(color[0] * glow) << 16 | channel(color[1] * glow) << 8 | channel(color[2] * glow);
		});
		if (full) {
			int fullCharge = script.get("fullCharge").getAsInt();
			for (UltraModel.Sprite sprite : weapon.model.sprites()) {
				if (weapon.model.parent(sprite.bone()) == fullCharge) {
					ModelOverlays.sprite(poseStack, collector, RenderTypes.text(sprite.texture()), world[sprite.bone()], sprite.rect(), sprite.color());
				}
			}
			for (ViewmodelParticles system : weapon.fullChargeParticles) {
				system.submit(poseStack, collector, world[system.bone], color);
			}
		}
		poseStack.popPose();
	}

	/** The nailgun's barrels' heat glow. */
	private static final String BARREL_HEAT = "BarrelHeat";
	/** The steam's start color, which its material's tint (0.5, doubled) leaves as it is. */
	private static final float[] STEAM_COLOR = {0.35849059F, 0.35849059F, 0.35849059F, 1};

	/**
	 * The nailgun, its barrels glowing with their heat (BarrelHeat's color, its alpha the heat, adds its orange), and
	 * its screen: the Overheat's heat meter over a background going from gray to orange, its heat sinks and the
	 * Attractor's magnet charges filling up gray and showing in the variant's color once full, the Attractor's nail
	 * count, the JumpStart's status, distance meter, zap meter, warning X and recharge overlay, and the Overheat's
	 * steam.
	 */
	private void submitNailgun(PoseStack poseStack, SubmitNodeCollector collector, int light, Viewmodel weapon) {
		JsonObject script = weapon.model.script("nailgun");
		if (script == null) {
			return;
		}
		NailgunState state = this.nailgun;
		Matrix4f[] world = weapon.model.boneMatrices(weapon.pose, new Quaternionf());
		weapon.model.submit(poseStack, collector, light, world, Set.of(BARREL_HEAT));
		float heat = (float)Math.clamp(state.heat(), 0, 1);
		if (heat > 0) {
			int color = 0xFF000000 | channel(heat) << 16 | channel(heat) << 8 | channel(heat);
			weapon.model.submitMaterial(poseStack, collector, world, BARREL_HEAT, texture -> RenderTypes.energySwirl(texture, 0, 0), color, LightCoordsUtil.FULL_BRIGHT);
		}
		float[] variation = variationColor(script);
		float[] empty = color(script.getAsJsonArray("emptyColor"));
		JsonArray charges = script.getAsJsonArray("heatSinkImages");
		boolean jumpstart = script.has("zapperAttach");
		ZapHud zap = state.zap();
		JsonArray sliders = weapon.model.scripts("sliders");
		for (int c = 0; c < weapon.model.canvases().size(); c++) {
			UltraModel.Canvas canvas = weapon.model.canvases().get(c);
			// Each image's color and fill, then CopyColor gives its images their targets' colors.
			float[][] colors = new float[canvas.images().size()][];
			float[] fills = new float[colors.length];
			boolean[] hidden = new boolean[colors.length];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = canvas.images().get(i).color();
				fills[i] = canvas.images().get(i).fillAmount();
				for (int k = 0; k < charges.size() && k < state.charges().length; k++) {
					if (at(charges.get(k), c, i)) {
						fills[i] = (float)state.charges()[k];
						colors[i] = state.chargesFull()[k] ? variation : empty;
					}
				}
				if (!jumpstart && !sliders.isEmpty() && at(sliders.get(0).getAsJsonObject().get("background"), c, i)) {
					colors[i] = state.heatMeterBackground();
				}
				if (jumpstart) {
					hidden[i] = !zap.recharging() && within(script.getAsJsonObject("rechargingOverlay").getAsJsonArray("images"), c, i)
						|| !zap.zapMeterShown() && within(script.getAsJsonObject("zapMeterParts").getAsJsonArray("images"), c, i)
						|| !zap.warning() && at(script.get("warningX"), c, i);
					if (at(script.get("rechargingMeter"), c, i)) {
						fills[i] = (float)zap.rechargeFill();
					}
					if (at(script.get("warningX"), c, i)) {
						colors[i] = zap.warningColor();
					}
				}
			}
			for (JsonElement element : weapon.model.scripts("copyColors")) {
				JsonArray image = element.getAsJsonObject().getAsJsonArray("image");
				JsonArray target = element.getAsJsonObject().getAsJsonArray("target");
				if (image.get(0).getAsInt() == c && target.get(0).getAsInt() == c) {
					colors[image.get(1).getAsInt()] = colors[target.get(1).getAsInt()];
				}
			}
			for (int i = 0; i < colors.length; i++) {
				if (hidden[i]) {
					continue;
				}
				UltraModel.Image image = canvas.images().get(i);
				for (JsonElement element : sliders) {
					JsonObject slider = element.getAsJsonObject();
					double value = !jumpstart ? state.heatMeter() : at(slider.get("fill"), script.getAsJsonArray("distanceMeter")) ? zap.distanceMeter() : zap.zapMeter();
					if (at(slider.get("fill"), c, i)) {
						image = sliderFill(image, slider, value);
					}
					if (slider.has("handle") && at(slider.get("handle"), c, i)) {
						image = sliderHandle(image, slider, value);
					}
				}
				ModelOverlays.image(poseStack, collector, RenderTypes.text(image.texture()), world[canvas.bone()], image, colors[i], fills[i], i);
			}
			for (int t = 0; t < canvas.texts().size(); t++) {
				UltraModel.Text text = canvas.texts().get(t);
				String content = at(script.get("ammoText"), c, t) ? Integer.toString(state.ammo()) : text.text();
				if (jumpstart && !zap.recharging() && within(script.getAsJsonObject("rechargingOverlay").getAsJsonArray("texts"), c, t)) {
					continue;
				}
				if (jumpstart && at(script.get("statusText"), c, t)) {
					content = zap.status();
					text = text.withColor(zap.statusVariant() ? variation : zap.statusColor());
				}
				ModelOverlays.text(poseStack, collector, world[canvas.bone()], text, content, colors.length + t);
			}
		}
		for (ViewmodelParticles system : weapon.steamParticles) {
			system.submit(poseStack, collector, world[system.bone], STEAM_COLOR);
		}
	}

	/**
	 * The rocket launcher, and its dial (RocketLauncher.Update): its rings and quarter marks in the variant's color
	 * (turned toward red, or dimmed, as the dial says), its inner ring filled as far as the dial shows, and its clock
	 * hand turned to match, a full turn back from upright when it's empty. Charging shakes it.
	 */
	private void submitRocketLauncher(PoseStack poseStack, SubmitNodeCollector collector, int light, Viewmodel weapon) {
		Vec3 jitter = this.rocketLauncher.jitter();
		poseStack.pushPose();
		poseStack.translate(jitter.x, jitter.y, -jitter.z);
		Matrix4f[] world = weapon.model.boneMatrices(weapon.pose, new Quaternionf());
		weapon.model.submit(poseStack, collector, light, world);
		JsonObject script = weapon.model.script("rocketLauncher");
		if (script == null) {
			poseStack.popPose();
			return;
		}
		float[] base = variationColor(script);
		float red = (float)this.rocketLauncher.reddening();
		float[] variation = {base[0] + (1 - base[0]) * red, base[1] * (1 - red), base[2] * (1 - red)};
		if (this.rocketLauncher.gray()) {
			variation = GRAY;
		}
		float opacity = this.rocketLauncher.dimmed() ? 0.5F : 1;
		float timer = (float)Math.clamp(this.rocketLauncher.timer(), 0, 1);
		JsonObject arm = script.getAsJsonObject("timerArm");
		float[] pivot = floats(arm.getAsJsonArray("pivot"));
		for (int c = 0; c < weapon.model.canvases().size(); c++) {
			UltraModel.Canvas canvas = weapon.model.canvases().get(c);
			Matrix4f matrix = world[canvas.bone()];
			Matrix4f turned = new Matrix4f(matrix).translate(pivot[0], pivot[1], 0)
				.rotateZ((float)Math.toRadians(360 * (1 - timer))).translate(-pivot[0], -pivot[1], 0);
			for (int i = 0; i < canvas.images().size(); i++) {
				UltraModel.Image image = canvas.images().get(i);
				float[] color = image.color();
				if (within(script.getAsJsonArray("variationColorables"), c, i)) {
					color = new float[]{variation[0], variation[1], variation[2], image.color()[3] * opacity};
				}
				float fill = at(script.get("timerMeter"), c, i) ? timer : image.fillAmount();
				Matrix4f placed = within(arm.getAsJsonArray("images"), c, i) ? turned : matrix;
				ModelOverlays.image(poseStack, collector, RenderTypes.text(image.texture()), placed, image, color, fill, i);
			}
		}
		if (script.has("napalmMuzzleFlash") && this.rocketLauncher.napalmFlash() > 0) {
			for (UltraModel.Sprite sprite : weapon.model.sprites()) {
				ModelOverlays.sprite(poseStack, collector, RenderTypes.text(sprite.texture()), world[sprite.bone()], sprite.rect(), sprite.color());
			}
		}
		for (int i = 0; i < weapon.napalmParticles.size(); i++) {
			ViewmodelParticles system = weapon.napalmParticles.get(i);
			system.submit(poseStack, collector, world[system.bone], weapon.napalmParticleColors.get(i));
		}
		poseStack.popPose();
	}

	/** Whether {@code place} is {@code canvas}'s {@code index}th. */
	private static boolean at(JsonElement place, JsonArray other) {
		return at(place, other.get(0).getAsInt(), other.get(1).getAsInt());
	}

	/** Whether one of {@code places} is {@code canvas}'s {@code index}th. */
	private static boolean within(JsonArray places, int canvas, int index) {
		for (JsonElement place : places) {
			if (at(place, canvas, index)) {
				return true;
			}
		}
		return false;
	}

	/** A Slider's handle at {@code value}: its middle that far across its slide area, in the slider's direction, keeping its size. */
	private static UltraModel.Image sliderHandle(UltraModel.Image handle, JsonObject slider, double value) {
		float[] r = handle.rect();
		float[] area = floats(slider.getAsJsonArray("handleArea"));
		int direction = slider.get("direction").getAsInt();
		float t = (float)Math.clamp(value, 0, 1);
		if (direction >= 2) {
			float half = (r[3] - r[1]) / 2;
			float y = direction == 2 ? area[1] + (area[3] - area[1]) * t : area[3] - (area[3] - area[1]) * t;
			return handle.withRect(new float[]{r[0], y - half, r[2], y + half});
		}
		float half = (r[2] - r[0]) / 2;
		float x = direction == 0 ? area[0] + (area[2] - area[0]) * t : area[2] - (area[2] - area[0]) * t;
		return handle.withRect(new float[]{x - half, r[1], x + half, r[3]});
	}

	private static float[] floats(JsonArray array) {
		float[] values = new float[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).getAsFloat();
		}
		return values;
	}

	/** Whether {@code place} (a canvas and an index on it, or null) is {@code canvas}'s {@code index}th. */
	private static boolean at(@Nullable JsonElement place, int canvas, int index) {
		if (place == null || place.isJsonNull()) {
			return false;
		}
		JsonArray pair = place.getAsJsonArray();
		return pair.get(0).getAsInt() == canvas && pair.get(1).getAsInt() == index;
	}

	/**
	 * A Slider's fill at {@code value}: its anchors run from the start of the fill area to {@code value} of the way
	 * across it, in the slider's direction, and it keeps its extra size.
	 */
	private static UltraModel.Image sliderFill(UltraModel.Image full, JsonObject slider, double value) {
		float[] r = full.rect();
		int direction = slider.get("direction").getAsInt();
		boolean vertical = direction >= 2;
		float more = slider.getAsJsonArray("fillExtra").get(vertical ? 1 : 0).getAsFloat();
		float size = vertical ? r[3] - r[1] : r[2] - r[0];
		float length = (size - more) * (float)Math.clamp(value, 0, 1) + more;
		return switch (direction) {
			case 1 -> full.withRect(new float[]{r[2] - length, r[1], r[2], r[3]});
			case 2 -> full.withRect(new float[]{r[0], r[1], r[2], r[1] + length});
			case 3 -> full.withRect(new float[]{r[0], r[3] - length, r[2], r[3]});
			default -> full.withRect(new float[]{r[0], r[1], r[0] + length, r[3]});
		};
	}

	private static float[] color(JsonArray color) {
		return new float[]{color.get(0).getAsFloat(), color.get(1).getAsFloat(), color.get(2).getAsFloat(), color.get(3).getAsFloat()};
	}

	/**
	 * A point in the world and a rotation there (as Minecraft draws a prefab put on it), and how much bigger a
	 * prefab put there is drawn to show as the HUD camera would show it.
	 */
	public record Placement(Vec3 position, Quaternionf rotation, float scale) {
	}

	/** Where the nailgun's {@code point}th shoot point shows in the world as it's posed now, and which way it faces there. */
	public @Nullable Placement nailgunShootPoint(Weapon weapon, int point) {
		Viewmodel viewmodel = weapon(weapon);
		JsonObject script = viewmodel == null ? null : viewmodel.model.script("nailgun");
		if (script == null) {
			return null;
		}
		JsonArray points = script.getAsJsonArray("shootPoints");
		Matrix4f matrix = viewmodel.model.boneMatrices(viewmodel.pose, new Quaternionf())[points.get(point % points.size()).getAsInt()];
		Quaternionf camera = new Quaternionf(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
		return new Placement(drawnAt(matrix.getTranslation(new Vector3f())), camera.mul(facing(matrix)), drawnSpread());
	}

	/**
	 * Where the rocket launcher's Shootpoint is in the world, the gun hanging off the camera at its own size, and
	 * which way it faces there.
	 */
	public @Nullable Placement rocketLauncherShootPoint(Weapon weapon) {
		Viewmodel viewmodel = weapon(weapon);
		JsonObject script = viewmodel == null ? null : viewmodel.model.script("rocketLauncher");
		if (script == null) {
			return null;
		}
		Matrix4f matrix = viewmodel.model.boneMatrices(viewmodel.pose, new Quaternionf())[script.get("shootPoint").getAsInt()];
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Quaternionf rotation = new Quaternionf(camera.rotation());
		Vector3f offset = rotation.transform(matrix.getTranslation(new Vector3f()).mul((float)PlayerShape.BLOCKS_PER_UNIT));
		return new Placement(camera.position().add(offset.x, offset.y, offset.z), rotation.mul(facing(matrix)), 1);
	}

	/** The rotation a prefab put on a bone of this matrix takes, mirrored into Minecraft's axes as the prefabs are drawn. */
	private static Quaternionf facing(Matrix4f matrix) {
		return new Matrix3f(
			matrix.getColumn(0, new Vector3f()).normalize(), matrix.getColumn(1, new Vector3f()).normalize(), matrix.getColumn(2, new Vector3f()).normalize()
		).mul(new Matrix3f().scaling(1, 1, -1)).getNormalizedRotation(new Quaternionf());
	}

	/** Whether {@code bone} is {@code ancestor} or under it. */
	private static boolean descends(UltraModel model, int bone, int ancestor) {
		for (int b = bone; b >= 0; b = model.parent(b)) {
			if (b == ancestor) {
				return true;
			}
		}
		return false;
	}

	/** Where the railcannon's Shootpoint shows in the world, as it's posed now. */
	public @Nullable Vec3 railcannonShootPoint(Weapon weapon) {
		Viewmodel viewmodel = weapon(weapon);
		JsonObject script = viewmodel == null ? null : viewmodel.model.script("railcannon");
		if (script == null) {
			return null;
		}
		Matrix4f matrix = viewmodel.model.boneMatrices(viewmodel.pose, new Quaternionf())[script.get("shootPoint").getAsInt()];
		return drawnAt(matrix.getTranslation(new Vector3f()));
	}

	/** Shotgun.UpdateMeter's color. */
	private float[] meterColor(float[] variation) {
		return switch (this.shotgun.tint()) {
			case RED -> RED;
			case BLACK -> BLACK;
			case GRAY -> GRAY;
			case VARIANT -> {
				float t = (float)Math.clamp(this.shotgun.reddening(), 0, 1);
				float[] color = new float[4];
				for (int c = 0; c < 4; c++) {
					color[c] = variation[c] + (METER_CHARGED[c] - variation[c]) * t;
				}
				yield color;
			}
		};
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
		submitQuickTwirl(poseStack, collector, weapon, world);
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
			if (quad.name().equals(revolver.get("screen").getAsString())) {
				ModelOverlays.quad(poseStack, collector, RenderTypes.text(screen), world[quad.bone()], color);
			} else if (quad.name().equals("ChargeEffect") && quad.texture() != null && s.pierceShotCharge() > 0) {
				float scale = (float)(s.pierceShotCharge() * 0.02);
				Matrix4f matrix = new Matrix4f(world[quad.bone()]).scale(scale);
				ModelOverlays.quad(poseStack, collector, RenderTypes.entityTranslucentEmissive(quad.texture()), matrix, new float[]{1, 1, 1, 1});
			}
		}
	}

	private void submitQuickTwirl(PoseStack poseStack, SubmitNodeCollector collector, Viewmodel weapon, Matrix4f[] world) {
		UltraModel model = UltraModel.get(QUICK_TWIRL);
		if (this.quickTwirl == null || weapon.cylinder == null || model == null || model.sprites().isEmpty()) {
			return;
		}
		UltraModel.Sprite sprite = model.sprites().getFirst();
		int bone = sprite.bone();
		Pose rest = model.rest();
		Vector3f full = new Vector3f(rest.sx(bone), rest.sy(bone), rest.sz(bone));
		float shrunk = (float)Math.max(1 - this.quickTwirl.age * QuickTwirl.SHRINK_SPEED / full.length(), 0);
		if (shrunk <= 0) {
			return;
		}
		Matrix4f matrix = new Matrix4f(world[weapon.cylinder.bone()]).mul(this.quickTwirl.fromCylinder)
			.translate(rest.px(bone), rest.py(bone), rest.pz(bone))
			.rotate(new Quaternionf(rest.qx(bone), rest.qy(bone), rest.qz(bone), rest.qw(bone)))
			.scale(full.mul(shrunk));
		ModelOverlays.sprite(poseStack, collector, RenderTypes.beaconBeam(sprite.texture(), true), matrix, sprite.rect(), sprite.color());
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
	 * shot (the slab sharpshooter's one for all three), filling as it recharges, red (the marksman's) or gray (the
	 * sharpshooter's) until full, then in the variant's color.
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
		boolean slabSharpshooter = revolver.get("altVersion").getAsBoolean() && revolver.get("variation").getAsInt() == 2;
		for (int c = 0; c < model.canvases().size(); c++) {
			UltraModel.Canvas canvas = model.canvases().get(c);
			Matrix4f matrix = world[canvas.bone()];
			for (int i = 0; i < canvas.images().size(); i++) {
				UltraModel.Image image = canvas.images().get(i);
				float[] color = image.color();
				float fill = image.fillAmount();
				for (int p = 0; p < panels.size(); p++) {
					if (panels.get(p)[0] == c && panels.get(p)[1] == i) {
						fill = slabSharpshooter ? (float)(this.state.coinCharge() / 300) : (float)(this.state.coinCharge() / 100 - p);
						color = fill < 1 ? charging : variation;
					}
				}
				ModelOverlays.image(poseStack, collector, RenderTypes.text(image.texture()), matrix, image, color, fill, i);
			}
		}
	}

	/** ColorBlindSettings' color for the weapon's variation, as its script names it. */
	private static float[] variationColor(JsonObject weapon) {
		JsonArray color = weapon.getAsJsonArray("variationColor");
		return new float[]{color.get(0).getAsFloat(), color.get(1).getAsFloat(), color.get(2).getAsFloat(), 1};
	}
}
