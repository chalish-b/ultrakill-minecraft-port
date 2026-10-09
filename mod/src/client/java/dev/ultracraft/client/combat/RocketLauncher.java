package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL's rocket launcher (RocketLauncher), with the charges its variants share (WeaponCharges): its fire
 * cooldown, which goes on counting down while it's put away and is at least a quarter of a second once it's drawn
 * again, and the Freezeframe's freeze. Fire2 on the Freezeframe freezes every rocket out, for up to 5 seconds, and
 * again unfreezes them; frozen, the freeze runs down in real time and the clock ticks, and with no rockets left
 * it soon unfreezes by itself. Unfrozen, it refills at half that speed, its windup sounding at each quarter. Fire2
 * on the S.R.S. Cannon, once its charge is full, charges a cannonball for up to a second, humming higher and shaking
 * the gun, and lets it go when released; the charge then refills over 8 seconds. Fire2 on the Firestarter sprays
 * gasoline while it's held and there's fuel, starting with at least a quarter; the fuel refills over 8 seconds, all
 * the while. It decides when to fire; {@link ClientCombat} does the firing.
 */
final class RocketLauncher {
	enum Variant {
		FREEZEFRAME,
		SRS,
		FIRESTARTER
	}

	interface Actions {
		/** RocketLauncher.Shoot. */
		void shootRocket(Variant variant);

		/** RocketLauncher.ShootCannonball, charged this far (0 to 1). */
		void shootCannonball(Variant variant, double charge);

		/** RocketLauncher.ShootNapalm: a drop of gasoline. */
		void shootNapalm(Variant variant);
	}

	/** RocketLauncher.rateOfFire: the cooldown a shot sets. */
	private static final double RATE_OF_FIRE = 1;
	/** Drawn, it can't fire for at least this long. */
	private static final double EQUIP_COOLDOWN = 0.25;
	/** WeaponCharges.rocketFreezeTime when full; unfrozen, it refills at half the speed it runs down. */
	private static final double FREEZE_TIME = 5;
	private static final double FREEZE_REFILL = 0.5;
	/** With no rockets left, the freeze ends by itself this long after it started or the last rocket went. */
	private static final double IDLE_UNFREEZE = 0.5;
	/** The clock's ticking fades out over this long (its Crossfade), from when the freeze starts. */
	private static final double TICK_FADE = 10;
	/** The ticking's pitch rises from this to 1 as the freeze runs out. */
	private static final double TICK_MIN_PITCH = 0.75;
	/** The windup's pitch at each quarter of the freeze: this plus a tenth per quarter. */
	private static final float WINDUP_PITCH = 0.6F;
	/** The S.R.S.'s windup's pitch at each quarter of its charge, a tenth more per quarter; it's at half volume until full. */
	private static final float CANNONBALL_WINDUP_PITCH = 1.6F;
	/** WeaponCharges.rocketCannonballCharge refills at this much a second. */
	private static final double CANNONBALL_REFILL = 0.125;
	/** The charge hum's pitch: this plus the charge. */
	private static final float CHARGE_HUM_PITCH = 0.5F;
	/** Charging, the gun shakes by up to this share of the charge, in units, along each axis. */
	private static final double CHARGE_JITTER = 0.01;
	/** WeaponCharges.rocketNapalmFuel refills at this much a second; spraying starts with at least a quarter. */
	private static final double NAPALM_REFILL = 0.125;
	private static final double NAPALM_MIN_FUEL = 0.25;
	/** RocketLauncher.ShootNapalm: each drop takes this much fuel, and the next follows this long after. */
	private static final double NAPALM_FUEL_PER_SHOT = 0.015;
	private static final double NAPALM_SHOT_COOLDOWN = 0.02;
	/** Time.fixedDeltaTime: FixedUpdate sprays the drops. */
	private static final double STEP = 0.008;
	/** Spraying can't start within this long of a rocket being fired. */
	private static final double NAPALM_START_COOLDOWN = 0.5;
	/** The spray's muzzle flash: its scale while spraying, shrinking at this much a second after. */
	private static final double NAPALM_FLASH_SCALE = 3;
	private static final double NAPALM_FLASH_SHRINK = 9;
	/** For a moment after the gun is drawn, alt fire held from before doesn't count. */
	private static final double EQUIP_GRACE = 0.25;
	private static final double FRESH_PRESS = 0.025;

	private final UltraSounds sounds;
	private Variant held = Variant.FREEZEFRAME;
	private boolean equipped;
	/** RocketLauncher.cooldown, kept as WeaponCharges.rocketcharge while it's put away. */
	private double cooldown = EQUIP_COOLDOWN;
	/** WeaponCharges.rocketFreezeTime, rocketFrozen, canAutoUnfreeze and timeSinceIdleFrozen. */
	private double freezeTime = FREEZE_TIME;
	private boolean frozen;
	private boolean canAutoUnfreeze;
	private double sinceIdleFrozen;
	/** RocketLauncher.lastKnownTimerAmount on each variant: its charge when the windups were last checked. */
	private double lastKnownFreeze = FREEZE_TIME;
	private double lastKnownCannonball = 1;
	/** WeaponCharges.rocketCannonballCharge, 0 to 1: a cannonball can be charged once it's full. */
	private double cannonballCharge = 1;
	/** RocketLauncher.cbCharge: the cannonball being charged, 0 to 1. */
	private double cbCharge;
	/** WeaponCharges.rocketNapalmFuel, 0 to 1, and the Firestarter's dial when its windup was last checked. */
	private double napalmFuel = 1;
	private double lastKnownNapalm = 1;
	/** RocketLauncher.firingNapalm, napalmProjectileCooldown, and the napalm muzzle flash's scale. */
	private boolean firingNapalm;
	private double napalmCooldown;
	private double napalmFlash;
	private double stepAccumulator;
	private final UltraSounds.Loop spray;
	private float sprayPitch = 1;
	private Vec3 jitter = Vec3.ZERO;
	private final UltraSounds.Loop chargeHum;
	private final RandomSource random = RandomSource.create();
	private double sinceEquipped;
	/** WeaponCharges.sinceAltFirePressed. */
	private double sinceAltFirePressed = 1;
	/** RocketLauncher.currentTimerTickSound, and how long since it started. */
	private final UltraSounds.Loop ticker;
	private double tickerAge;

	RocketLauncher(UltraSounds sounds) {
		this.sounds = sounds;
		this.ticker = sounds.loop();
		this.chargeHum = sounds.loop();
		this.spray = sounds.loop();
	}

	/** Whether the rockets out are frozen (WeaponCharges.rocketFrozen). */
	boolean frozen() {
		return this.frozen;
	}

	/** Whether the Firestarter is spraying: the Fire animation doesn't play for a rocket fired meanwhile. */
	boolean spraying() {
		return this.firingNapalm;
	}

	/**
	 * What the held variant's dial shows (RocketLauncher.Update): the Freezeframe's freeze; the S.R.S.'s charge, or the
	 * cannonball being charged, its colors turning red with it, and dimmed while the charge refills; the Firestarter's
	 * fuel, gray while there's too little to start spraying, and its spray's muzzle flash.
	 */
	FirstPersonWeapons.RocketLauncherState display() {
		if (this.held == Variant.FREEZEFRAME) {
			return new FirstPersonWeapons.RocketLauncherState(this.freezeTime / FREEZE_TIME, 0, false, false, Vec3.ZERO, 0, false);
		}
		if (this.held == Variant.FIRESTARTER) {
			boolean gray = !this.firingNapalm && this.napalmFuel < NAPALM_MIN_FUEL;
			return new FirstPersonWeapons.RocketLauncherState(this.napalmFuel, 0, false, gray, Vec3.ZERO, this.napalmFlash, this.firingNapalm);
		}
		if (this.cbCharge > 0) {
			return new FirstPersonWeapons.RocketLauncherState(this.cbCharge, this.cbCharge, false, false, this.jitter, 0, false);
		}
		return new FirstPersonWeapons.RocketLauncherState(this.cannonballCharge, 0, this.cannonballCharge < 1, false, Vec3.ZERO, 0, false);
	}

	/**
	 * WeaponCharges.Update and Charge, whichever weapon is out: the freeze runs down or refills, and ends by itself
	 * once it's out, or once the rockets it held are all gone.
	 *
	 * @param rockets how many rockets are out (WeaponCharges.rocketCount)
	 */
	void recharge(double dt, int rockets) {
		if (!this.equipped) {
			this.cooldown = Math.max(this.cooldown - dt, 0);
		}
		this.sinceIdleFrozen += dt;
		if (this.frozen) {
			if (rockets > 0) {
				this.canAutoUnfreeze = true;
			}
			if (this.canAutoUnfreeze && rockets == 0 && this.sinceIdleFrozen > IDLE_UNFREEZE) {
				unfreeze();
			}
		}
		this.freezeTime = this.frozen ? Math.max(this.freezeTime - dt, 0) : Math.min(this.freezeTime + dt * FREEZE_REFILL, FREEZE_TIME);
		this.cannonballCharge = Math.min(this.cannonballCharge + dt * CANNONBALL_REFILL, 1);
		this.napalmFuel = Math.min(this.napalmFuel + dt * NAPALM_REFILL, 1);
		if (this.frozen && this.freezeTime <= 0) {
			unfreeze();
		}
		if (this.frozen) {
			this.tickerAge += dt;
			double volume = Math.max(1 - this.tickerAge / TICK_FADE, 0);
			this.ticker.update("rocket_freeze_tick", volume, TICK_MIN_PITCH + (1 - TICK_MIN_PITCH) * this.freezeTime / FREEZE_TIME);
		}
	}

	/** A rocket went (Grenade.OnDestroy): the idle time before the freeze ends by itself starts over. */
	void rocketGone() {
		this.sinceIdleFrozen = 0;
	}

	/** Drawn (RocketLauncher.OnEnable): the cooldown left from before, but at least a quarter of a second. */
	void equip(Variant variant) {
		this.held = variant;
		this.equipped = true;
		this.cooldown = Math.max(this.cooldown, EQUIP_COOLDOWN);
		this.sinceEquipped = 0;
	}

	/**
	 * Put away (RocketLauncher.OnDisable): its cooldown goes on counting down, a cannonball being charged is dropped,
	 * and the spray stops.
	 */
	void holster() {
		this.equipped = false;
		stopCharging();
		this.firingNapalm = false;
		this.spray.stop();
		this.napalmFlash = 0;
	}

	/** RocketLauncher.Update, for the held rocket launcher. */
	void update(double dt, Revolver.Input input, Actions actions) {
		this.sinceEquipped += dt;
		this.sinceAltFirePressed = input.fire2Pressed ? 0 : this.sinceAltFirePressed + dt;
		boolean early = this.sinceEquipped < EQUIP_GRACE && this.sinceAltFirePressed > FRESH_PRESS;
		boolean alt = input.fire2Held && !early || input.fire2Pressed;
		if (alt && this.sinceEquipped < EQUIP_GRACE) {
			this.sinceEquipped = EQUIP_GRACE;
		}
		if (this.held == Variant.FREEZEFRAME) {
			if (this.freezeTime > 0 && input.fire2Pressed) {
				if (this.frozen) {
					unfreeze();
				} else {
					freeze();
				}
			}
			double share = this.freezeTime / FREEZE_TIME;
			if (windup(share, this.lastKnownFreeze / FREEZE_TIME, WINDUP_PITCH, 1)) {
				this.lastKnownFreeze = this.freezeTime;
			}
		} else if (this.held == Variant.FIRESTARTER) {
			napalm(dt, input);
		} else {
			if (windup(this.cannonballCharge, this.lastKnownCannonball, CANNONBALL_WINDUP_PITCH, this.cannonballCharge < 1 ? 0.5F : 1)) {
				this.lastKnownCannonball = this.cannonballCharge;
			}
			if (alt && !input.fire1Pressed && this.cannonballCharge >= 1) {
				this.chargeHum.update("rocket_cannonball_charge", 1, (float)this.cbCharge + CHARGE_HUM_PITCH);
				this.cbCharge = Math.min(this.cbCharge + dt, 1);
				double shake = this.cbCharge * CHARGE_JITTER;
				this.jitter = new Vec3(spread(shake), spread(shake), spread(shake));
			} else if (!early && this.cbCharge > 0) {
				double charge = this.cbCharge;
				stopCharging();
				this.cooldown = RATE_OF_FIRE;
				this.cannonballCharge = 0;
				actions.shootCannonball(this.held, charge);
			}
		}
		if (this.cooldown > 0) {
			this.cooldown = Math.max(this.cooldown - dt, 0);
		} else if (input.fire1Held) {
			this.cooldown = RATE_OF_FIRE;
			// A rocket fired while a cannonball charges drops the charge.
			stopCharging();
			actions.shootRocket(this.held);
		}
		if (this.held == Variant.FIRESTARTER) {
			this.stepAccumulator += dt;
			while (this.stepAccumulator >= STEP) {
				this.stepAccumulator -= STEP;
				fixedUpdate(actions);
			}
		}
	}

	/**
	 * The Firestarter's part of RocketLauncher.Update: Fire2 held sprays while there's fuel, starting only with at
	 * least a quarter and not within half a second of a rocket. Starting, the muzzle flash shows and the spray starts
	 * sounding; stopping, it stops with its own sound. Pressed with too little fuel, it clicks. The muzzle flash
	 * shrinks away once it stops, and the windup sounds as the fuel passes a quarter.
	 */
	private void napalm(double dt, Revolver.Input input) {
		if (input.fire2Held && this.napalmFuel > 0 && (this.firingNapalm || this.napalmFuel >= NAPALM_MIN_FUEL)) {
			if (this.cooldown < NAPALM_START_COOLDOWN) {
				if (!this.firingNapalm) {
					this.napalmFlash = NAPALM_FLASH_SCALE;
					this.sprayPitch = this.sounds.randomPitch(0.9F, 1.1F);
					this.sounds.play("rocket_napalm_start", 1, this.sounds.randomPitch(0.9F, 1.1F));
				}
				this.firingNapalm = true;
			}
		} else if (this.firingNapalm) {
			this.firingNapalm = false;
			this.spray.stop();
			this.sounds.play("rocket_napalm_stop", 1, this.sounds.randomPitch(0.9F, 1.1F));
		} else if (input.fire2Pressed && this.napalmFuel < NAPALM_MIN_FUEL) {
			this.sounds.play("rocket_napalm_no_ammo", 1, 1);
		}
		if (this.firingNapalm) {
			this.spray.update("rocket_napalm_spray", 1, this.sprayPitch);
		} else {
			this.napalmFlash = Math.max(this.napalmFlash - NAPALM_FLASH_SHRINK * dt, 0);
		}
		if (this.lastKnownNapalm != this.napalmFuel) {
			if (this.napalmFuel >= NAPALM_MIN_FUEL && this.lastKnownNapalm < NAPALM_MIN_FUEL) {
				this.sounds.play("rocket_freeze_windup", 1, 1);
			}
			this.lastKnownNapalm = this.napalmFuel;
		}
	}

	/** RocketLauncher.FixedUpdate: spraying, a drop goes out as soon as the last one's cooldown is over. */
	private void fixedUpdate(Actions actions) {
		this.napalmCooldown = Math.max(this.napalmCooldown - STEP, 0);
		if (this.firingNapalm && this.napalmCooldown == 0) {
			this.napalmCooldown = NAPALM_SHOT_COOLDOWN;
			this.napalmFuel -= NAPALM_FUEL_PER_SHOT;
			actions.shootNapalm(this.held);
		}
	}

	/**
	 * The windup as the dial passes each quarter on its way up, higher the fuller it is.
	 *
	 * @return whether the dial moved since it was last checked
	 */
	private boolean windup(double share, double lastShare, float pitch, float volume) {
		if (lastShare == share) {
			return false;
		}
		for (int quarter = 4; quarter > 0; quarter--) {
			if (share >= quarter / 4.0 && lastShare < quarter / 4.0) {
				this.sounds.play("rocket_freeze_windup", volume, pitch + quarter * 0.1F);
				break;
			}
		}
		return true;
	}

	private void stopCharging() {
		this.cbCharge = 0;
		this.jitter = Vec3.ZERO;
		this.chargeHum.stop();
	}

	private double spread(double range) {
		return (this.random.nextDouble() * 2 - 1) * range;
	}

	/** RocketLauncher.FreezeRockets: the clock starts, and ticks. */
	private void freeze() {
		this.frozen = true;
		this.sinceIdleFrozen = 0;
		this.sounds.play("rocket_freeze", 1, 1);
		this.tickerAge = 0;
	}

	/** RocketLauncher.UnfreezeRockets: the clock stops. */
	private void unfreeze() {
		this.frozen = false;
		this.canAutoUnfreeze = false;
		this.sounds.play("rocket_unfreeze", 1, 1);
		this.ticker.stop();
	}
}
