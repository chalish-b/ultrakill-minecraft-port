package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons.MeterTint;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL's shotgun (Shotgun), one per variant. Holding fire shoots whenever the gun is ready, which its
 * animations' ReadyGun event says; after a shot its heat sink glows, and cools once the animation releases the
 * heat. The Core Eject charges a core while alt fire is held (up to a second) and throws it on release; it can't
 * throw again until its reload is done. The Pump Charge pumps with alt fire, up to three times: each pump makes the
 * next shot wider, and a third makes it an explosion, which the meter warns of with a beep. The Sawed-On revs its
 * chainsaw while alt fire is held, once the chainsaw has recharged; enemies in front of it take hits, and after
 * enough of those that don't kill, the chainsaw wears out and then breaks off. It decides when to shoot, throw,
 * pump and saw; {@link ClientCombat} does the rest.
 */
final class Shotgun {
	enum Variant {
		CORE_EJECT,
		PUMP_CHARGE,
		SAWED_ON
	}

	interface Actions {
		/** Shotgun.Shoot, with the Pump Charge's {@code pumps} (0 to 3). */
		void shootShotgun(Variant variant, int pumps);

		/** Shotgun.ShootSinks: a core thrown with {@code force} (0 to 60) of charge. */
		void throwCore(Variant variant, double force);

		/** Shotgun.Pump: the Pump Charge is pumped once more. */
		void pump(Variant variant);

		/**
		 * Shotgun.ShootSaw: the chainsaw comes off the gun, thrown with {@code force} (0 to 60) of charge or, if not
		 * {@code thrown}, broken off.
		 */
		void shootSaw(Variant variant, double force, boolean thrown);

		/** The chainsaw wears out or breaks: its break effect at the blade, and a camera shake of {@code shake}. */
		void sawBreaks(Variant variant, double shake);
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
	private static final double BEEP_RATE = 5;
	/** Revving, the Sawed-On shakes by up to this fraction of the force, in units. */
	private static final double SAW_JITTER = 1 / 12000.0;
	/** WeaponCharges: the chainsaw recharges this much a second, from 0 to 1. */
	private static final double SAW_RECHARGE = 0.25;
	/** Hits that don't kill wear the chainsaw out past this many, and break it past this many. */
	private static final int SAW_WEAR_HITS = 40;
	private static final int SAW_BREAK_HITS = 60;
	/** The chainsaw's hits are forgotten once it's been put away (or let go) this long. */
	private static final double SAW_HITS_RESET = 3;
	/** Worn out, the rev wanders between pitches this far either side of 1, at this much a second. */
	private static final double BROKEN_PITCH_RANGE = 0.33;
	private static final double BROKEN_PITCH_SPEED = 2;

	final Variant variant;
	private final UltraSounds sounds;
	private final UltraSounds.Loop chargeHum;
	private final UltraSounds.Loop sawRev;
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
	/** WeaponCharges.shoSawCharge: the chainsaw's charge, 0 to 1. */
	private double sawCharge = 1;
	/** WeaponCharges.shoSawResetTimer. */
	private double sawHitsReset;
	/** The chainsaw is on the gun (its attach point is active). */
	private boolean sawAttached = true;
	/** chainsawNonlethalHits: the revving chainsaw's hits that didn't kill. */
	private int sawHits;
	/** chainsawBroken: the chainsaw is worn out. */
	private boolean sawBroken;
	private double revPitch;
	private double brokenPitchTarget = 1;
	/** How far the blade's texture has scrolled across. */
	private double bladeScroll;

	Shotgun(Variant variant, UltraSounds sounds) {
		this.variant = variant;
		this.sounds = sounds;
		this.chargeHum = sounds.loop();
		this.sawRev = sounds.once();
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
			|| this.variant == Variant.SAWED_ON && this.sawCharge >= 1 && this.gunReady;
		if (this.sinceLastCore > CORE_COOLDOWN && alt && canCharge) {
			if (this.variant == Variant.SAWED_ON && !this.charging) {
				startSawing();
			}
			this.charging = true;
			this.force = Math.min(this.force + MAX_FORCE * dt, MAX_FORCE);
			double shake = this.force * (this.variant == Variant.SAWED_ON ? SAW_JITTER : JITTER);
			this.jitter = new Vec3(spread(shake), spread(shake), spread(shake));
			if (this.variant == Variant.SAWED_ON) {
				rev(dt);
			} else {
				this.chargeHum.update("shotgun_charge", 1, this.force / MAX_FORCE);
			}
		}
		if (!alt && !early && this.charging) {
			this.charging = false;
			this.sinceLastCore = 0;
			this.jitter = Vec3.ZERO;
			if (this.variant == Variant.SAWED_ON) {
				this.sawCharge = 0;
				shootSaw(actions, true);
			} else {
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
		if (this.variant == Variant.SAWED_ON) {
			wearSaw(actions);
			this.bladeScroll += this.force / 6 * dt;
		}
		updateMeter(dt);
	}

	/** The chainsaw starts revving: its damage zone comes on, forgetting old hits once their time is up. */
	private void startSawing() {
		if (this.sawHits != 0 && this.sawHitsReset <= 0) {
			this.sawHits = 0;
		}
	}

	/** The chainsaw stops revving: its hits are kept for a while. */
	private void stopSawing() {
		if (this.sawHits > 0) {
			this.sawHitsReset = SAW_HITS_RESET;
		}
	}

	/** The rev rises with the charge; worn out, it wanders instead. */
	private void rev(double dt) {
		if (this.sawBroken) {
			double step = BROKEN_PITCH_SPEED * dt;
			double toward = this.brokenPitchTarget - this.revPitch;
			this.revPitch = Math.abs(toward) <= step ? this.brokenPitchTarget : this.revPitch + Math.signum(toward) * step;
			if (this.revPitch == this.brokenPitchTarget) {
				this.brokenPitchTarget = 1 + spread(BROKEN_PITCH_RANGE);
			}
		} else {
			this.revPitch = (this.force / 2 + 30) / MAX_FORCE;
		}
		this.sawRev.update("chainsaw_rev", 1, this.revPitch);
	}

	/** Too many hits that don't kill wear the chainsaw out, and then break it off. */
	private void wearSaw(Actions actions) {
		if (!this.charging) {
			return;
		}
		if (this.sawHits > SAW_BREAK_HITS) {
			this.charging = false;
			this.sawCharge = 0;
			shootSaw(actions, false);
			this.sawBroken = false;
			actions.sawBreaks(this.variant, 0);
		} else if (this.sawHits > SAW_WEAR_HITS && !this.sawBroken) {
			this.sawBroken = true;
			actions.sawBreaks(this.variant, 1);
			this.brokenPitchTarget = 1;
		}
	}

	/** Shotgun.ShootSaw: the chainsaw leaves the gun, which is ready again straight away. */
	private void shootSaw(Actions actions, boolean thrown) {
		this.gunReady = true;
		this.jitter = Vec3.ZERO;
		this.sawRev.stop();
		stopSawing();
		this.sawAttached = false;
		this.releasingHeat = false;
		if (thrown) {
			this.sawBroken = false;
		}
		actions.shootSaw(this.variant, this.force, thrown);
		this.force = 0;
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
			if (this.variant == Variant.SAWED_ON && this.sawCharge >= 1) {
				this.sawAttached = true;
			}
			boolean grayed = this.variant == Variant.SAWED_ON && this.force == 0 && this.sawCharge < 1;
			this.tint = grayed ? MeterTint.GRAY : MeterTint.VARIANT;
			this.reddening = this.force / MAX_FORCE;
		}
	}

	private double spread(double range) {
		return (this.random.nextDouble() * 2 - 1) * range;
	}

	/**
	 * Whether or not the gun is held, the heat sink cools, and (WeaponCharges) the chainsaw recharges in four
	 * seconds and its hits' time runs out.
	 */
	void cool(double dt) {
		if (this.releasingHeat) {
			this.heat -= HEAT_FADE * dt;
		}
		this.sawCharge = Math.min(this.sawCharge + SAW_RECHARGE * dt, 1);
		this.sawHitsReset = Math.max(this.sawHitsReset - dt, 0);
	}

	/**
	 * Drawn (Shotgun.OnEnable): its Equip animation readies it. A charged chainsaw is on the gun, and a worn-out
	 * one is mended once its hits' time is up.
	 */
	void equip() {
		this.gunReady = false;
		this.resettingCores = false;
		this.sinceEquipped = 0;
		if (this.variant == Variant.SAWED_ON) {
			this.sawAttached = this.sawCharge >= 1;
			if (this.sawBroken && this.sawHitsReset <= 0) {
				this.sawBroken = false;
			}
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
		if (this.charging && this.variant == Variant.SAWED_ON) {
			stopSawing();
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
		this.sawRev.stop();
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

	/** Whether the chainsaw is revving, so its damage zone is on. */
	boolean sawing() {
		return this.charging && this.variant == Variant.SAWED_ON;
	}

	/** The revving chainsaw hit an enemy without killing it. */
	void sawHit() {
		this.sawHits++;
	}

	boolean sawAttached() {
		return this.sawAttached;
	}

	boolean sawBroken() {
		return this.sawBroken;
	}

	double bladeScroll() {
		return this.bladeScroll;
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
		if (this.variant == Variant.SAWED_ON) {
			return this.force > 0 ? this.force : this.sawCharge * MAX_FORCE;
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
