package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL's shotgun (Shotgun), one per variant. Holding fire shoots whenever the gun is ready, which its
 * animations' ReadyGun event says; after a shot its heat sink glows, and cools once the animation releases the
 * heat. The Core Eject charges a core while alt fire is held (up to a second) and throws it on release; it can't
 * throw again until its reload is done. It decides when to shoot and throw; {@link ClientCombat} does the rest.
 */
final class Shotgun {
	enum Variant {
		CORE_EJECT
	}

	interface Actions {
		void shootShotgun(Variant variant);

		/** Shotgun.ShootSinks: a core thrown with {@code force} (0 to 60) of charge. */
		void throwCore(Variant variant, double force);
	}

	/** How fast the heat sink's glow fades once released, in alpha a second. */
	private static final double HEAT_FADE = 2.5;
	/** A core charges to this much force, at this much a second. */
	static final double MAX_FORCE = 60;
	/** Another core can't start charging until this long after the last. */
	private static final double CORE_COOLDOWN = 0.5;
	/** For a moment after the gun is drawn, alt fire held from before doesn't count. */
	private static final double EQUIP_GRACE = 0.25;
	private static final double FRESH_PRESS = 0.025;
	/** The gun shakes in the hand by up to this fraction of the force, in units. */
	private static final double JITTER = 1 / 3000.0;

	final Variant variant;
	private final UltraSounds.Loop chargeHum;
	private final RandomSource random = RandomSource.create();
	/** Set by the Equip and firing animations' ReadyGun event. */
	private boolean gunReady;
	/** The heat sink's glow (HeatSinkHeat's tint alpha). */
	private double heat;
	private boolean releasingHeat;
	private boolean charging;
	/** grenadeForce: the core's charge. */
	private double force;
	private double sinceLastCore = CORE_COOLDOWN;
	private double sinceEquipped;
	/** WeaponCharges.sinceAltFirePressed. */
	private double sinceAltFirePressed = 1;
	/** A core was thrown and the reload hasn't finished. */
	private boolean resettingCores;
	/** The meter shows empty and black until the reload finishes. */
	private boolean meterOverride;
	/** How far the gun shakes from its place this frame, in units. */
	private Vec3 jitter = Vec3.ZERO;

	Shotgun(Variant variant, UltraSounds sounds) {
		this.variant = variant;
		this.chargeHum = sounds.loop();
	}

	void update(double dt, Revolver.Input input, Actions actions) {
		this.sinceEquipped += dt;
		this.sinceLastCore += dt;
		this.sinceAltFirePressed = input.fire2Pressed ? 0 : this.sinceAltFirePressed + dt;
		if (input.fire1Held && this.gunReady) {
			this.gunReady = false;
			this.releasingHeat = false;
			this.heat = 1;
			actions.shootShotgun(this.variant);
		}
		boolean early = this.sinceEquipped < EQUIP_GRACE && this.sinceAltFirePressed > FRESH_PRESS;
		boolean alt = input.fire2Held && !early || input.fire2Pressed;
		if (alt && this.sinceEquipped < EQUIP_GRACE) {
			this.sinceEquipped = EQUIP_GRACE;
		}
		if (this.sinceLastCore > CORE_COOLDOWN && alt && !this.resettingCores) {
			this.charging = true;
			this.force = Math.min(this.force + MAX_FORCE * dt, MAX_FORCE);
			double shake = this.force * JITTER;
			this.jitter = new Vec3(spread(shake), spread(shake), spread(shake));
			this.chargeHum.update("shotgun_charge", 1, this.force / MAX_FORCE);
		}
		if (!alt && !early && this.charging) {
			this.charging = false;
			this.sinceLastCore = 0;
			this.jitter = Vec3.ZERO;
			this.chargeHum.stop();
			this.gunReady = false;
			this.resettingCores = true;
			this.meterOverride = true;
			this.releasingHeat = false;
			this.heat = 0;
			actions.throwCore(this.variant, this.force);
			this.force = 0;
		}
	}

	private double spread(double range) {
		return (this.random.nextDouble() * 2 - 1) * range;
	}

	/** The heat sink cools whether or not the gun is held. */
	void cool(double dt) {
		if (this.releasingHeat) {
			this.heat -= HEAT_FADE * dt;
		}
	}

	/** Drawn (Shotgun.OnEnable): its Equip animation readies it. */
	void equip() {
		this.gunReady = false;
		this.resettingCores = false;
		this.sinceEquipped = 0;
	}

	/** The ReadyGun animation event. */
	void readyGun() {
		this.gunReady = true;
		this.meterOverride = false;
		this.resettingCores = false;
	}

	/** The ReleaseHeat animation event. */
	void releaseHeat() {
		this.releasingHeat = true;
	}

	/** Put away (Shotgun.OnDisable): a charging core is dropped. */
	void holster() {
		this.gunReady = false;
		this.charging = false;
		this.force = 0;
		this.meterOverride = false;
		this.sinceEquipped = 0;
		this.jitter = Vec3.ZERO;
		this.chargeHum.stop();
	}

	double heat() {
		return this.heat;
	}

	Vec3 jitter() {
		return this.jitter;
	}

	/** The meter's value, 0 to 60: full, or the charge, or empty while a core's throw reloads. */
	double meter() {
		return this.meterOverride ? 0 : this.force > 0 ? this.force : MAX_FORCE;
	}

	/** How far the core is charged, 0 to 1: the meter reddens with it. */
	double charge() {
		return this.force / MAX_FORCE;
	}

	/** Whether a core's throw is reloading: the meter shows empty and black. */
	boolean reloadingCore() {
		return this.meterOverride;
	}

	/** Shotgun.ClickSound clicks differently while the meter isn't its plain color. */
	boolean meterTinted() {
		return this.meterOverride || this.force > 0;
	}
}
