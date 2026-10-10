package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import dev.ultracraft.client.render.FirstPersonWeapons.MeterTint;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * ULTRAKILL's shotgun (Shotgun), one per variant. Holding fire shoots whenever the gun is ready, which its
 * animations' ReadyGun event says; after a shot its heat sink glows, and cools once the animation releases the
 * heat. The Core Eject charges a core while alt fire is held (up to a second) and throws it on release; it can't
 * throw again until its reload is done. The Pump Charge pumps with alt fire, up to three times: each pump makes the
 * next shot wider, and a third makes it an explosion, which the meter warns of with a beep. The Sawed-On revs its
 * chainsaw ({@link MountedSaw}) while alt fire is held, once the chainsaw has recharged. It decides when to shoot,
 * throw, pump and saw; {@link ClientCombat} does the rest.
 */
final class Shotgun {
	enum Variant {
		CORE_EJECT,
		PUMP_CHARGE,
		SAWED_ON
	}

	interface Actions extends MountedSaw.Actions {
		/** Shotgun.Shoot, with the Pump Charge's {@code pumps} (0 to 3). */
		void shootShotgun(Variant variant, int pumps);

		/** Shotgun.ShootSinks: a core thrown with {@code force} (0 to 60) of charge. */
		void throwCore(Variant variant, double force);

		/** Shotgun.Pump: the Pump Charge is pumped once more. */
		void pump(Variant variant);
	}

	/**
	 * WeaponCharges' shotgun charges, which the shotguns and the Jackhammers share: the chainsaw's, and the
	 * Jackhammer's core, overheats and medium hits.
	 */
	static final class Charges {
		/** shoSawCharge: the chainsaw's charge, 0 to 1. */
		double sawCharge = 1;
		/** shoSawResetTimer: until a chainsaw's hits are forgotten. */
		double sawHitsReset;
		/** shoAltNadeCharge: the Jackhammer's core, 0 to 1. */
		double coreCharge = 1;
		/** shoAltYellows: the Jackhammer's medium hits in a row, and (shoAltYellowsTimer) until they're forgotten. */
		int yellows;
		double yellowsTimer;
		/** shoaltcooldowns: by variant, seconds a Jackhammer stays overheated. */
		final double[] overheat = new double[Variant.values().length];

		/**
		 * WeaponCharges.Charge: the chainsaw recharges in four seconds (eight while {@code sawsOut} thrown chainsaws
		 * are still about), the Jackhammer's core in two, and the timers run out.
		 */
		void recharge(double dt, int sawsOut) {
			this.coreCharge = Math.min(this.coreCharge + CORE_RECHARGE * dt, 1);
			this.sawCharge = Math.min(this.sawCharge + SAW_RECHARGE * (sawsOut == 0 ? 1 : 0.5) * dt, 1);
			if (this.yellowsTimer > 0) {
				this.yellowsTimer = Math.max(this.yellowsTimer - dt, 0);
				if (this.yellowsTimer <= 0) {
					this.yellows = 0;
				}
			} else {
				this.yellows = 0;
			}
			this.sawHitsReset = Math.max(this.sawHitsReset - dt, 0);
			for (int i = 0; i < this.overheat.length; i++) {
				this.overheat[i] = Math.max(this.overheat[i] - dt, 0);
			}
		}
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
	/** The Pump Charge's pumps go up to this; the shot after the last is an explosion. */
	static final int MAX_PUMPS = 3;
	/** Fully pumped, the meter beeps this many times a second, red then black. */
	static final double BEEP_RATE = 5;
	/** WeaponCharges: the chainsaw recharges this much a second, from 0 to 1, and half that while one is out. */
	private static final double SAW_RECHARGE = 0.25;
	/** WeaponCharges: the Jackhammer's core recharges this much a second, from 0 to 1. */
	private static final double CORE_RECHARGE = 0.5;

	final Variant variant;
	private final Charges charges;
	private final UltraSounds sounds;
	private final UltraSounds.Loop chargeHum;
	/** The Sawed-On's chainsaw. */
	final @Nullable MountedSaw saw;
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
	/** primaryCharge: the Pump Charge's pumps. */
	private int pumps;
	/** Counts down from 1 to the next warning beep. */
	private double timeToBeep;
	/** The meter fill's color: the variant's, reddened this much, or red or black. */
	private MeterTint tint = MeterTint.VARIANT;
	private double reddening;

	Shotgun(Variant variant, FirstPersonWeapons.Weapon weapon, Charges charges, UltraSounds sounds) {
		this.variant = variant;
		this.charges = charges;
		this.sounds = sounds;
		this.chargeHum = sounds.loop();
		this.saw = variant == Variant.SAWED_ON ? new MountedSaw(weapon, charges, sounds) : null;
	}

	void update(double dt, Revolver.Input input, Actions actions) {
		this.sinceEquipped += dt;
		this.sinceLastCore += dt;
		this.sinceAltFirePressed = input.fire2Pressed ? 0 : this.sinceAltFirePressed + dt;
		if (input.fire1Held && this.gunReady) {
			this.gunReady = false;
			this.releasingHeat = false;
			this.heat = 1;
			actions.shootShotgun(this.variant, this.pumps);
			this.pumps = 0;
		}
		boolean early = this.sinceEquipped < EQUIP_GRACE && this.sinceAltFirePressed > FRESH_PRESS;
		boolean alt = input.fire2Held && !early || input.fire2Pressed;
		if (alt && this.sinceEquipped < EQUIP_GRACE) {
			this.sinceEquipped = EQUIP_GRACE;
		}
		if (alt && this.variant == Variant.PUMP_CHARGE && this.gunReady) {
			this.gunReady = false;
			actions.pump(this.variant);
			this.pumps = Math.min(this.pumps + 1, MAX_PUMPS);
		}
		boolean canCharge = this.variant == Variant.CORE_EJECT && !this.resettingCores
			|| this.saw != null && this.saw.charged() && this.gunReady;
		if (this.sinceLastCore > CORE_COOLDOWN && alt && canCharge) {
			if (this.saw != null) {
				this.saw.rev(dt);
				double shake = this.saw.jitter();
				this.jitter = new Vec3(spread(shake), spread(shake), spread(shake));
			} else {
				this.charging = true;
				this.force = Math.min(this.force + MAX_FORCE * dt, MAX_FORCE);
				double shake = this.force * JITTER;
				this.jitter = new Vec3(spread(shake), spread(shake), spread(shake));
				this.chargeHum.update("shotgun_charge", 1, this.force / MAX_FORCE);
			}
		}
		if (!alt && !early && (this.saw != null ? this.saw.revving() : this.charging)) {
			this.sinceLastCore = 0;
			this.jitter = Vec3.ZERO;
			if (this.saw != null) {
				shootSaw(actions, true);
			} else {
				this.charging = false;
				this.chargeHum.stop();
				this.gunReady = false;
				this.resettingCores = true;
				this.meterOverride = true;
				this.releasingHeat = false;
				this.heat = 0;
				this.tint = MeterTint.BLACK;
				actions.throwCore(this.variant, this.force);
				this.force = 0;
			}
		}
		if (this.saw != null) {
			switch (this.saw.wear()) {
				case BROKE -> {
					shootSaw(actions, false);
					actions.sawBreaks(this.saw, 0);
				}
				case WORN -> actions.sawBreaks(this.saw, 1);
				case NONE -> {
				}
			}
			this.saw.scroll(dt);
		}
		updateMeter(dt);
	}

	/** Shotgun.ShootSaw: the chainsaw leaves the gun, which is ready again straight away. */
	private void shootSaw(Actions actions, boolean thrown) {
		this.gunReady = true;
		this.jitter = Vec3.ZERO;
		this.releasingHeat = false;
		actions.shootSaw(this.saw, this.saw.comeOff(thrown), thrown);
	}

	/** Shotgun.UpdateMeter: the meter's color. */
	private void updateMeter(double dt) {
		if (this.variant == Variant.PUMP_CHARGE) {
			this.timeToBeep = Math.max(this.timeToBeep - BEEP_RATE * dt, 0);
			if (this.pumps == MAX_PUMPS) {
				if (this.timeToBeep == 0) {
					this.timeToBeep = 1;
					this.sounds.play("shotgun_warning_beep", 1, this.sounds.randomPitch(0.95F, 1.05F));
					this.tint = MeterTint.RED;
				} else if (this.timeToBeep < 0.5) {
					this.tint = MeterTint.BLACK;
				}
			} else {
				this.tint = MeterTint.VARIANT;
				this.reddening = this.pumps / 2.0;
			}
		} else if (!this.meterOverride) {
			if (this.saw != null) {
				this.saw.reattach();
			}
			double force = this.saw != null ? this.saw.force() : this.force;
			boolean grayed = this.saw != null && force == 0 && !this.saw.charged();
			this.tint = grayed ? MeterTint.GRAY : MeterTint.VARIANT;
			this.reddening = force / MAX_FORCE;
		}
	}

	private double spread(double range) {
		return (this.random.nextDouble() * 2 - 1) * range;
	}

	/** Whether or not the gun is held, the heat sink cools. */
	void cool(double dt) {
		if (this.releasingHeat) {
			this.heat -= HEAT_FADE * dt;
		}
	}

	/**
	 * Drawn (Shotgun.OnEnable): its Equip animation readies it. A charged chainsaw is on the gun, and a worn-out
	 * one is mended once its hits' time is up.
	 */
	void equip() {
		this.gunReady = false;
		this.resettingCores = false;
		this.sinceEquipped = 0;
		if (this.saw != null) {
			this.saw.equip();
		}
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

	/** Put away (Shotgun.OnDisable): a charging core is dropped, a revving chainsaw stops, and the pumps are let go. */
	void holster() {
		if (this.saw != null) {
			this.saw.holster();
		}
		this.gunReady = false;
		this.charging = false;
		this.force = 0;
		this.pumps = 0;
		this.tint = MeterTint.VARIANT;
		this.reddening = 0;
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

	int pumps() {
		return this.pumps;
	}

	/**
	 * The meter's value, 0 to 60. The Core Eject's is full, or the charge, or empty while a core's throw
	 * reloads; the Pump Charge's fills a third with each pump; the Sawed-On's shows the chainsaw's recharge, or
	 * the rev's charge.
	 */
	double meter() {
		if (this.variant == Variant.PUMP_CHARGE) {
			return this.pumps * MAX_FORCE / MAX_PUMPS;
		}
		if (this.saw != null) {
			return this.saw.force() > 0 ? this.saw.force() : this.charges.sawCharge * MAX_FORCE;
		}
		return this.meterOverride ? 0 : this.force > 0 ? this.force : MAX_FORCE;
	}

	MeterTint tint() {
		return this.tint;
	}

	/** How far the meter has reddened from the variant's color, 0 to 1. */
	double reddening() {
		return this.reddening;
	}

	/** Shotgun.ClickSound clicks differently while the meter isn't its plain color. */
	boolean meterTinted() {
		return this.tint != MeterTint.VARIANT || this.reddening > 0;
	}
}
