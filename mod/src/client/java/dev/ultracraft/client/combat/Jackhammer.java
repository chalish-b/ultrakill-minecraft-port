package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import dev.ultracraft.client.render.FirstPersonWeapons.MeterTint;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * ULTRAKILL's alternate shotgun, the Jackhammer (ShotgunHammer), one per variant. Holding fire pulls the hammer back
 * for half a second; letting go once it's back (or straight away, if fire was let go early) swings it. Its motor runs
 * with the player's speed, which sets the swing's tier on the dial. A red hit, or a third yellow one in a row, on an
 * enemy overheats it. The Core Eject lobs a core with alt fire, the Pump Charge pumps its next swing's explosion, and
 * the Sawed-On revs its chainsaw ({@link MountedSaw}). It decides when to swing, throw, pump and saw;
 * {@link ClientCombat} does the rest.
 */
final class Jackhammer {
	interface Actions extends MountedSaw.Actions {
		/** ShotgunHammer.Impact: the swing lands, at the hammer's tier. */
		void swingHammer(Jackhammer hammer);

		/** ShotgunHammer.ThrowNade: a core lobbed out of the camera. */
		void throwHammerCore(Jackhammer hammer);

		/** ShotgunHammer.Pump: pumped once more, as {@link #pumps()} now counts. */
		void pumpHammer(Jackhammer hammer);
	}

	/** The motor's speed is the player's over this (units a second), up to 1, and moves towards it at this much a second. */
	private static final double FULL_SPEED = 60;
	private static final double SPEED_RATE = 2;
	/** The Whiplash's pull holds the motor to this. */
	private static final double PULLED_SPEED = 0.5;
	/** The motor's speed over which the tier is yellow, and red. */
	private static final double YELLOW = 0.33;
	private static final double RED = 0.66;
	/** A tier the speed drops below is kept this long, its glow blinking every tenth of a second. */
	private static final double TIER_HOLD = 0.5;
	private static final double BLINK = 0.1;
	/** Drawn, it can swing this long after (pulledOut). */
	private static final double READY_TIME = 0.5;
	/** Fire held as it's drawn only starts pulling the hammer back after this long. */
	private static final double HELD_FIRE_DELAY = 0.25;
	/** The hammer is pulled all the way back in half a second, and can't be pulled again until this long after a swing. */
	private static final double PULL_RATE = 2;
	private static final double SWING_COOLDOWN = 0.5;
	/** The hammer comes back this far down (units), and the gun shakes by up to the pull over this. */
	private static final double PULL_DISTANCE = 0.65;
	private static final double PULL_SHAKE = 1 / 30.0;
	/** DeliverDamage: a red hit, or a yellow one after two in a row, overheats the hammer this long; yellows in a row are forgotten after this. */
	private static final double OVERHEAT = 7;
	private static final int YELLOWS_BEFORE_OVERHEAT = 2;
	private static final double YELLOWS_WINDOW = 3;
	/** ThrowNade: after a core, the gun is ready again in a fifth of a second (pulledOut starts at 0.3). */
	private static final double CORE_PULLED_OUT = 0.3;
	/** The Pump Charge's pumps go up to this; a full pump makes the swing's explosion an Explosion Super. */
	static final int MAX_PUMPS = 3;
	/** The secondary meter, a radial fill: partly full, it shows between these shares of its circle. */
	private static final double METER_EMPTY = 0.275;
	private static final double METER_FULL = 0.625;
	/** LateUpdate: the dial's needle is turned this many degrees about its up axis at no speed, and this many fewer at full. */
	private static final double DIAL_REST = 170;
	private static final double DIAL_SWEEP = 200;
	/** The motor turns this many degrees a second at full speed (10 a frame at 150 frames a second). */
	private static final double MOTOR_SPIN = 1500;

	final Variant variant;
	final FirstPersonWeapons.Weapon weapon;
	private final Shotgun.Charges charges;
	private final UltraSounds sounds;
	private final UltraSounds.Loop pullSound;
	private final UltraSounds.Loop motorSound;
	private final UltraSounds.Loop overheatSound;
	/** The Sawed-On's chainsaw. */
	final @Nullable MountedSaw saw;
	private final RandomSource random = RandomSource.create();
	/** pulledOut: seconds since it was drawn, set back by a throw or a pump. */
	private double pulledOut;
	private boolean gunReady;
	private boolean fireHeldOnPullOut;
	private boolean chargingSwing;
	/** swingCharge: how far the hammer is pulled back, 0 to 1. */
	private double swingCharge;
	private double swingCooldown;
	/** currentSpeed: the motor's speed, 0 to 1. */
	private double currentSpeed;
	/** The speed tier (green, yellow, red), and how long since the speed last reached it (tierDownTimer). */
	private int tier;
	private double tierDownTimer;
	/** The dial's glow: 1, or 0 while overheated or blinking. */
	private double glow = 1;
	private boolean overheated;
	/** primaryCharge: the Pump Charge's pumps. */
	private int pumps;
	/** Counts down from 1 to the next warning beep. */
	private double timeToBeep;
	/** The secondary meter's value (0 to 1) and color. */
	private double meterFill;
	private MeterTint meterTint = MeterTint.WHITE;
	private double reddening;
	/** nadeCharging: the core was recharging while the gun was out, so it chimes once ready. */
	private boolean coreCharging;
	/** storedSpeed: the player's speed lately, which a hit on an enemy keeps them going at; and how long it's been kept. */
	private double storedSpeed;
	private double sinceSpeedStored;
	/** A swing is landing (impactRoutine), so its sounds play on through the hitstop. */
	private boolean impacting;
	/** The gun's shake while the hammer is pulled back, and the chainsaw's while revving, in units. */
	private Vec3 jitter = Vec3.ZERO;
	private Vec3 sawJitter = Vec3.ZERO;
	private double motorAngle;

	enum Variant {
		CORE_EJECT,
		PUMP_CHARGE,
		SAWED_ON
	}

	Jackhammer(Variant variant, FirstPersonWeapons.Weapon weapon, Shotgun.Charges charges, UltraSounds sounds) {
		this.variant = variant;
		this.weapon = weapon;
		this.charges = charges;
		this.sounds = sounds;
		this.pullSound = sounds.loop();
		this.motorSound = sounds.loop();
		this.overheatSound = sounds.loop();
		this.saw = variant == Variant.SAWED_ON ? new MountedSaw(weapon, charges, sounds) : null;
	}

	/**
	 * ShotgunHammer.Update, with the gun drawn.
	 *
	 * @param speed the player's speed (PlayerTracker.GetPlayerVelocity), units a second
	 * @param beingPulled whether the Whiplash is pulling the player
	 */
	void update(double dt, Revolver.Input input, double speed, boolean beingPulled, Actions actions) {
		this.pulledOut += dt;
		this.tierDownTimer += dt;
		this.overheated = this.charges.overheat[this.variant.ordinal()] > 0;
		this.currentSpeed = this.overheated ? 0 : moveTowards(this.currentSpeed, Math.min(speed / FULL_SPEED, 1), SPEED_RATE * dt);
		if (beingPulled) {
			this.currentSpeed = Math.min(this.currentSpeed, PULLED_SPEED);
		}
		updateMeter(dt, beingPulled);
		if (this.pulledOut >= READY_TIME) {
			this.gunReady = true;
		}
		if (!this.chargingSwing && this.swingCooldown <= 0 && !this.overheated && input.fire1Held && (!this.fireHeldOnPullOut || this.pulledOut >= HELD_FIRE_DELAY)) {
			this.fireHeldOnPullOut = false;
			this.chargingSwing = true;
		} else if (!input.fire1Held && this.swingCharge == 1 && this.gunReady) {
			this.chargingSwing = false;
			this.swingCharge = 0;
			this.swingCooldown = SWING_COOLDOWN;
			actions.swingHammer(this);
		}
		if (this.saw != null) {
			if (input.fire2Held && this.gunReady && this.saw.charged()) {
				this.saw.rev(dt);
				double shake = this.saw.jitter();
				this.sawJitter = new Vec3(spread(shake), spread(shake), spread(shake));
			}
			if (!input.fire2Held && this.gunReady && this.saw.revving()) {
				shootSaw(actions, true);
			}
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
		if (this.chargingSwing) {
			this.swingCharge = moveTowards(this.swingCharge, 1, PULL_RATE * dt);
		}
		double shake = this.swingCharge * PULL_SHAKE;
		this.jitter = new Vec3(spread(shake), spread(shake), spread(shake));
		if (input.fire2Held && this.variant != Variant.SAWED_ON && (this.variant == Variant.PUMP_CHARGE || this.charges.coreCharge >= 1) && this.gunReady) {
			this.gunReady = false;
			if (this.variant == Variant.CORE_EJECT) {
				this.charges.coreCharge = 0;
				this.pulledOut = CORE_PULLED_OUT;
				actions.throwHammerCore(this);
			} else {
				this.pumps = Math.min(this.pumps + 1, MAX_PUMPS);
				this.pulledOut = 0;
				actions.pumpHammer(this);
			}
		}
		this.swingCooldown = moveTowards(this.swingCooldown, 0, dt);
		if (this.charges.coreCharge < 1) {
			this.coreCharging = true;
		} else if (this.coreCharging) {
			this.coreCharging = false;
			this.sounds.play("hammer_core_ready", 1, 1);
		}
	}

	/** ShotgunHammer.ShootSaw: the chainsaw leaves the gun, which is ready again straight away. */
	private void shootSaw(Actions actions, boolean thrown) {
		this.gunReady = true;
		this.sawJitter = Vec3.ZERO;
		actions.shootSaw(this.saw, this.saw.comeOff(thrown), thrown);
	}

	/**
	 * ShotgunHammer.UpdateMeter: the tier from the motor's speed (one it drops below is kept for half a second,
	 * blinking; the Whiplash's pull takes red down to yellow), and the secondary meter: the core's recharge, the
	 * pumps (green, yellow, then beeping red and black), or the chainsaw's recharge or rev.
	 */
	private void updateMeter(double dt, boolean beingPulled) {
		int tier = this.currentSpeed > RED ? 2 : this.currentSpeed > YELLOW ? 1 : 0;
		if (beingPulled && this.tier == 2) {
			tier = 1;
		} else if (tier < this.tier) {
			if (this.tierDownTimer <= TIER_HOLD) {
				tier = this.tier;
			}
		} else {
			this.tierDownTimer = 0;
		}
		this.tier = tier;
		if (this.overheated) {
			this.glow = 0;
		} else if (this.tierDownTimer > 0) {
			this.glow = this.tierDownTimer % BLINK >= BLINK / 2 ? 1 : 0;
		} else {
			this.glow = 1;
		}
		switch (this.variant) {
			case CORE_EJECT -> {
				this.meterFill = this.charges.coreCharge;
				this.meterTint = this.meterFill >= 1 ? MeterTint.VARIANT : MeterTint.WHITE;
				this.reddening = 0;
			}
			case PUMP_CHARGE -> {
				if (this.timeToBeep != 0) {
					this.timeToBeep = moveTowards(this.timeToBeep, 0, Shotgun.BEEP_RATE * dt);
				}
				this.meterFill = this.pumps / (double)MAX_PUMPS;
				if (this.pumps == MAX_PUMPS) {
					if (this.timeToBeep == 0) {
						this.timeToBeep = 1;
						this.sounds.play("shotgun_warning_beep", 1, this.sounds.randomPitch(0.95F, 1.05F));
						this.meterTint = MeterTint.RED;
					} else if (this.timeToBeep < 0.5) {
						this.meterTint = MeterTint.BLACK;
					}
				} else if (this.pumps == 1) {
					this.meterTint = MeterTint.VARIANT;
				} else if (this.pumps == 2) {
					this.meterTint = MeterTint.YELLOW;
				}
				this.reddening = 0;
			}
			case SAWED_ON -> {
				this.saw.reattach();
				if (this.saw.revving()) {
					this.meterFill = this.saw.force() / MountedSaw.MAX_FORCE;
					this.meterTint = MeterTint.VARIANT;
					this.reddening = this.meterFill;
				} else {
					this.meterFill = this.charges.sawCharge;
					this.meterTint = this.charges.sawCharge >= 1 ? MeterTint.VARIANT : MeterTint.WHITE;
					this.reddening = 0;
				}
			}
		}
	}

	/**
	 * ShotgunHammer.LateUpdate's sounds, every frame, hitstops included: the hammer's pull, louder the further back it
	 * is; the motor, louder and higher the faster it runs (while a swing lands, as high as its tier); and the
	 * overheat's hiss. Time stopped stops the motor's spin, unless a swing is landing.
	 */
	void lateUpdate(double dt, boolean timeStopped) {
		double timeScale = this.impacting || !timeStopped ? 1 : 0;
		this.pullSound.update("hammer_pull", this.swingCharge * 0.6, this.swingCharge + this.currentSpeed * 0.2 * timeScale * dt * 150);
		this.motorAngle = (this.motorAngle + this.currentSpeed * MOTOR_SPIN * timeScale * dt) % 360;
		this.motorSound.update("hammer_motor", this.currentSpeed / 2, this.impacting ? this.tier + 1 : this.currentSpeed * timeScale);
		if (this.overheated) {
			this.overheatSound.update("hammer_overheat", 1, 1);
		} else {
			this.overheatSound.stop();
		}
	}

	/** ShotgunHammer.FixedUpdate: the speed it keeps is the player's, unless that dropped more than 5 within half a second. */
	void fixedUpdate(double dt, double speed) {
		this.sinceSpeedStored += dt;
		if (speed >= this.storedSpeed - 5 || this.sinceSpeedStored > 0.5) {
			this.storedSpeed = speed;
			this.sinceSpeedStored = 0;
		}
	}

	/** Drawn (ShotgunHammer.OnEnable): fire already held only counts once the gun has been out a moment. */
	void equip(boolean fire1Held) {
		this.fireHeldOnPullOut = fire1Held;
		this.pulledOut = 0;
		this.impacting = false;
		if (this.saw != null) {
			this.saw.equip();
		}
	}

	/** Put away (ShotgunHammer.OnDisable): the hammer drops back, the pumps are let go and a revving chainsaw stops. */
	void holster() {
		this.impacting = false;
		this.gunReady = false;
		this.pumps = 0;
		this.chargingSwing = false;
		this.swingCharge = 0;
		this.tier = 0;
		this.swingCooldown = 0;
		this.jitter = Vec3.ZERO;
		this.sawJitter = Vec3.ZERO;
		if (this.saw != null) {
			this.saw.holster();
		}
		this.pullSound.stop();
		this.motorSound.stop();
		this.overheatSound.stop();
	}

	/** The swing is landing, through its hitstops, until {@link #landed()}. */
	void impacting() {
		this.impacting = true;
	}

	void landed() {
		this.impacting = false;
	}

	/**
	 * DeliverDamage on an enemy that was alive: a red hit overheats the hammer; a yellow one does after two others in
	 * a row, each three seconds of the last.
	 */
	void hitLiving() {
		if (this.tier == 2) {
			this.charges.overheat[this.variant.ordinal()] = OVERHEAT;
		} else if (this.tier == 1) {
			if (this.charges.yellows >= YELLOWS_BEFORE_OVERHEAT) {
				this.charges.overheat[this.variant.ordinal()] = OVERHEAT;
			}
			this.charges.yellowsTimer = YELLOWS_WINDOW;
			this.charges.yellows++;
		}
	}

	/** ImpactEffects: the pumps go into the swing's explosion. */
	int takePumps() {
		int pumps = this.pumps;
		this.pumps = 0;
		return pumps;
	}

	int pumps() {
		return this.pumps;
	}

	int tier() {
		return this.tier;
	}

	double storedSpeed() {
		return this.storedSpeed;
	}

	boolean overheated() {
		return this.overheated;
	}

	FirstPersonWeapons.HammerState display() {
		double needle = DIAL_REST - (this.overheated ? this.charges.overheat[this.variant.ordinal()] / OVERHEAT : this.currentSpeed) * DIAL_SWEEP;
		double fill = this.meterFill >= 1 ? 1 : this.meterFill <= 0 ? 0 : METER_EMPTY + (METER_FULL - METER_EMPTY) * this.meterFill;
		return new FirstPersonWeapons.HammerState(
			this.tier, this.glow, needle, this.swingCharge * PULL_DISTANCE, this.motorAngle, this.currentSpeed / 3, fill, this.meterTint, this.reddening,
			this.overheated, this.jitter, this.sawJitter, this.saw != null ? this.saw.display() : FirstPersonWeapons.SawState.NONE
		);
	}

	private double spread(double range) {
		return (this.random.nextDouble() * 2 - 1) * range;
	}

	private static double moveTowards(double current, double target, double maxDelta) {
		return Math.abs(target - current) <= maxDelta ? target : current + Math.signum(target - current) * maxDelta;
	}
}
