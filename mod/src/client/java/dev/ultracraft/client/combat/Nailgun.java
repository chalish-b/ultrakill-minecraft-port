package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;

/**
 * ULTRAKILL's nailgun (Nailgun), as the Attractor or the Overheat. Held down, its barrels spin up and it fires nails
 * from each in turn; their heat glows on the barrels. The Attractor runs on a hundred nails that refill while it
 * isn't firing, and throws magnets. The Overheat fires slower the hotter it gets, and burns out a heat sink to fire
 * a stream of heated nails until it has cooled. It decides when to fire; {@link ClientCombat} does the firing.
 */
final class Nailgun {
	enum Variant {
		ATTRACTOR(10),
		OVERHEAT(20);

		/** Nailgun.spread: a nail is turned up to a third of this many degrees about each axis. */
		final double spread;

		Variant(double spread) {
			this.spread = spread;
		}
	}

	interface Actions {
		/**
		 * Nailgun.Shoot: a nail out of shoot point {@code barrel} (a heated one during a burnout), turned up to a third
		 * of {@code spread} degrees about each axis, its muzzle flash, and the shot at {@code volume} and {@code pitch}.
		 * The nail flies {@code steps} physics steps before the frame is drawn: the one it's fired in, and those left.
		 */
		void shootNail(Variant variant, int barrel, boolean heated, double spread, float volume, float pitch, int steps);

		/** Nailgun.ShootMagnet: a magnet thrown along the aim; the gun plays its Shoot animation if {@code animate}. */
		void shootMagnet(Variant variant, boolean animate);
	}

	/**
	 * WeaponCharges' nailgun charges, which go on recharging whichever weapon is out: the Overheat's heat sinks and
	 * the barrels' heat as the last nailgun put away left them, the Attractor's nails and its magnet charges, of
	 * which there are three less the magnets out.
	 */
	static final class Charges {
		/** naiHeatsinks and naiheatUp; nai0set: whether a nailgun has left its heat for the next one. */
		double heatSinks = HEAT_SINKS;
		double heat;
		boolean heatLeft;
		/** naiAmmo; naiAmmoDontCharge while the Attractor is firing. */
		double ammo = AMMO;
		boolean ammoHeld;
		/** naiMagnetCharge. */
		double magnetCharge = MAGNET_CHARGES;

		/** WeaponCharges.Charge. */
		void recharge(double dt, int magnets) {
			this.heatSinks = toward(this.heatSinks, HEAT_SINKS, dt * HEAT_SINK_RECHARGE);
			this.heat = toward(this.heat, 0, dt * COOLING);
			if (!this.ammoHeld) {
				this.ammo = toward(this.ammo, AMMO, dt * AMMO_RECHARGE);
			}
			int room = MAGNET_CHARGES - magnets;
			if (magnets > 0) {
				if (room > 0 && this.magnetCharge < room) {
					this.magnetCharge = toward(this.magnetCharge, room, dt * MAGNET_RECHARGE);
				}
			} else if (this.magnetCharge < MAGNET_CHARGES) {
				this.magnetCharge = toward(this.magnetCharge, MAGNET_CHARGES, dt * MAGNET_RECHARGE);
			}
		}
	}

	/** Nailgun.fireRate: the fire cooldown a shot sets, before heat changes it. */
	private static final double FIRE_RATE = 4;
	/** Nailgun.emptyColor and fullColor: the heat meter's background goes from one to the other with the heat. */
	private static final float[] EMPTY_COLOR = {0.33F, 0.33F, 0.33F, 1};
	private static final float[] FULL_COLOR = {1, 0.5F, 0, 1};
	/** Time.fixedDeltaTime: the fire cooldown counts down, and shots go off, in physics steps. */
	private static final double STEP = 0.008;
	/** The fire cooldown counts down by this much a second; a shot sets it to the fire rate. */
	private static final double COOLDOWN_SPEED = 100;
	private static final int HEAT_SINKS = 2;
	private static final double HEAT_SINK_RECHARGE = 0.125;
	private static final int AMMO = 100;
	private static final double AMMO_RECHARGE = 5;
	private static final int MAGNET_CHARGES = 3;
	private static final double MAGNET_RECHARGE = 3;
	/** The barrels heat up this much a second while the Overheat fires, cool this much while no nailgun fires, and this much burning out. */
	private static final double HEATING = 0.55;
	private static final double COOLING = 0.3;
	private static final double BURNING_OUT = 1;
	/** The barrels spin at this many degrees a second, more by this much at full heat, and slow by this much a second. */
	private static final double SPIN = 250;
	private static final double SPIN_HEAT = 2250;
	private static final double SPIN_SLOWING = 1000;
	/** Their whir's pitch is their speed over this much. */
	private static final double SPIN_PITCH = 750;
	/** The meter fills toward its value at this much a second, and 20 times the gap more. */
	private static final double METER_SPEED = 1;
	private static final double METER_CATCH_UP = 20;
	/** Unity's Color.clear. */
	private static final float[] CLEAR = {0, 0, 0, 0};

	final Variant variant;
	private final Charges charges;
	private final UltraSounds sounds;
	private final UltraSounds.Loop whir;
	private final UltraSounds.Loop steam;
	/** Set by the Equip animation's CanShoot event. */
	private boolean canShoot;
	private double heat;
	private boolean burnOut;
	private double heatSinks = HEAT_SINKS;
	private double fireCooldown;
	private double fireRate;
	private double spinSpeed;
	private double barrelAngle;
	private int barrel;
	private boolean shotSuccessfully;
	private float layerWeight;
	private boolean steaming;
	/** The meter's heat sinks or magnet charges as shown, and whether each shows full. */
	private double meterFill = HEAT_SINKS;
	private final boolean[] meterFull = {true, true, true};
	/** The Overheat's heat meter (its Slider's value) and its background's color. */
	private double heatMeter = 1;
	private float[] heatMeterBackground = CLEAR;
	private double accumulator;

	Nailgun(Variant variant, Charges charges, UltraSounds sounds) {
		this.variant = variant;
		this.charges = charges;
		this.sounds = sounds;
		this.whir = sounds.loop();
		this.steam = sounds.loop();
	}

	/** Nailgun.OnEnable: the Overheat takes back its heat sinks, and either takes on the heat the last nailgun left. */
	void equip() {
		this.canShoot = false;
		if (this.variant == Variant.OVERHEAT) {
			this.heatSinks = this.charges.heatSinks;
		}
		if (this.charges.heatLeft) {
			this.charges.heatLeft = false;
			if (this.heatSinks >= 1) {
				this.heat = this.charges.heat;
			}
		}
		this.spinSpeed = SPIN + this.heat * SPIN_HEAT;
		this.layerWeight = 0;
	}

	/** Nailgun.OnDisable: it leaves its heat (and the Overheat its heat sinks) to the charges. */
	void holster() {
		this.canShoot = false;
		this.charges.heat = this.heat;
		this.charges.heatLeft = true;
		if (this.variant == Variant.OVERHEAT) {
			this.charges.heatSinks = this.heatSinks;
		}
		this.charges.ammoHeld = false;
		this.whir.stop();
		this.steam.stop();
		this.steaming = false;
	}

	/** NailgunAnimationReceiver.CanShoot, from the Equip animation. */
	void canShoot() {
		this.canShoot = true;
	}

	/** Nailgun.FixedUpdate in each physics step, then Nailgun.Update, for the held nailgun. */
	void update(double dt, Revolver.Input input, Actions actions) {
		this.accumulator += dt;
		int steps = (int)(this.accumulator / STEP);
		this.accumulator -= steps * STEP;
		for (int i = 0; i < steps; i++) {
			fixedUpdate(input, actions, steps - i);
		}
		boolean firing = this.canShoot && input.fire1Held;
		if (this.burnOut || this.heatSinks < 1) {
			this.heat = toward(this.heat, 0, dt * BURNING_OUT);
			if (this.burnOut && this.heat <= 0) {
				this.burnOut = false;
				this.steaming = false;
			}
		} else if (firing && this.heat < 1) {
			this.heat = this.variant == Variant.ATTRACTOR ? 1 : toward(this.heat, 1, dt * HEATING);
		} else if (this.heat > 0 && !firing) {
			this.heat = toward(this.heat, 0, dt * COOLING);
		}
		if (this.variant == Variant.OVERHEAT) {
			updateHeatMeter();
		}
		this.spinSpeed = firing ? SPIN + this.heat * SPIN_HEAT : toward(this.spinSpeed, 0, dt * SPIN_SLOWING);
		this.fireRate = currentFireRate();
		this.barrelAngle = (this.barrelAngle + this.spinSpeed * dt) % 360;
		this.whir.update("nailgun_spin", 1, this.spinSpeed / SPIN_PITCH);
		if (this.variant == Variant.ATTRACTOR) {
			this.charges.ammoHeld = input.fire1Held;
		}
		if (this.variant == Variant.OVERHEAT && input.fire2Pressed && this.canShoot && !this.burnOut && this.heatSinks >= 1 && this.heat >= 0.1) {
			this.burnOut = true;
			this.fireCooldown = 0;
			this.heatSinks--;
			this.steaming = true;
			this.fireRate = FIRE_RATE - 2.5;
		}
		if (this.variant == Variant.ATTRACTOR && input.fire2Pressed) {
			if (this.charges.magnetCharge >= 1) {
				this.layerWeight = animationWeight();
				actions.shootMagnet(this.variant, this.canShoot);
				this.charges.magnetCharge--;
			} else {
				noAmmo();
			}
		}
		if (this.variant == Variant.OVERHEAT && !input.fire1Held) {
			this.heatSinks = toward(this.heatSinks, HEAT_SINKS, dt * HEAT_SINK_RECHARGE);
		}
		refreshMeter(dt, this.variant == Variant.OVERHEAT ? this.heatSinks : this.charges.magnetCharge);
		if (this.steaming) {
			this.steam.update("nailgun_steam", 1, 1);
		} else {
			this.steam.stop();
		}
	}

	/** Nailgun.FixedUpdate: once the cooldown is out, a held trigger (or a burnout) fires; the empty Attractor clicks. */
	private void fixedUpdate(Revolver.Input input, Actions actions, int stepsLeft) {
		if (this.fireCooldown > 0) {
			this.fireCooldown = toward(this.fireCooldown, 0, STEP * COOLDOWN_SPEED);
			if (this.fireCooldown < 0.01) {
				this.fireCooldown = 0;
			}
		}
		if (this.canShoot && (input.fire1Held || this.burnOut)) {
			if (this.fireCooldown != 0) {
				return;
			}
			if (this.variant == Variant.ATTRACTOR && Math.rint(this.charges.ammo) <= 0) {
				this.fireCooldown = this.fireRate * 2;
				if (this.shotSuccessfully) {
					this.sounds.play("nailgun_last_shot", 1, 1);
				} else {
					noAmmo();
				}
				this.shotSuccessfully = false;
			} else {
				shoot(actions, stepsLeft);
			}
		} else if (!input.fire1Held) {
			this.shotSuccessfully = false;
		}
	}

	/**
	 * Nailgun.Shoot: from the next barrel, at double the spread during a burnout. The muzzle flash's shot plays a
	 * little higher during a burnout, and lower and quieter with no heat sink left.
	 */
	private void shoot(Actions actions, int steps) {
		this.layerWeight = animationWeight();
		this.fireCooldown = this.fireRate;
		this.shotSuccessfully = true;
		if (this.variant == Variant.ATTRACTOR) {
			this.charges.ammo--;
		}
		this.barrel = (this.barrel + 1) % 2;
		double spread = this.variant.spread;
		float volume = 0.65F;
		float pitch;
		if (this.burnOut) {
			pitch = this.sounds.randomPitch(1.05F, 1.15F);
			spread *= 2;
		} else if (this.heatSinks < 1) {
			pitch = 0.75F;
			volume = 0.25F;
		} else {
			pitch = this.sounds.randomPitch(0.9F, 1.1F);
		}
		actions.shootNail(this.variant, this.barrel, this.burnOut, spread, volume, pitch, steps);
	}

	/** The NailgunNoAmmo click. */
	private void noAmmo() {
		this.sounds.play("nailgun_no_ammo", 1, this.sounds.randomPitch(0.95F, 1.05F));
	}

	/**
	 * Nailgun's fire rate: the Attractor's slows as its heat drops; the Overheat's slows past half heat, and slower
	 * still with no heat sink left, while a burnout fires fastest.
	 */
	private double currentFireRate() {
		if (this.variant == Variant.ATTRACTOR) {
			return FIRE_RATE + 3.5 - this.heat * 3.5;
		}
		if (this.burnOut) {
			return FIRE_RATE - 2.5;
		}
		if (this.heatSinks >= 1) {
			return this.heat < 0.5 ? FIRE_RATE : FIRE_RATE + (this.heat - 0.5) * 7;
		}
		return FIRE_RATE + 10;
	}

	/** Nailgun.UpdateAnimationWeight: the hotter the Overheat (or with no heat sink left), the less each shot kicks. */
	private float animationWeight() {
		if (this.variant == Variant.OVERHEAT && !this.burnOut) {
			return this.heatSinks < 1 ? 0.9F : (float)(this.heat * 0.6);
		}
		return 0;
	}

	/** The Overheat's heat meter shows the heat over a background going from gray to orange; empty, with no heat sink left, it goes dark. */
	private void updateHeatMeter() {
		if (this.heatMeter != this.heat) {
			this.heatMeter = this.heat;
			this.heatMeterBackground = lerp(EMPTY_COLOR, FULL_COLOR, (float)this.heat);
			if (this.heat <= 0 && this.heatSinks < 1) {
				this.heatMeterBackground = CLEAR;
			}
		} else if (this.heat == 0 && this.heatSinks >= 1 && this.heatMeterBackground[3] == 0) {
			this.heatMeterBackground = EMPTY_COLOR;
		}
	}

	/**
	 * Nailgun.RefreshHeatSinkFill: the meter catches up with {@code value}, each piece filling in gray and chiming
	 * (higher for each) as it fills while the meter is catching up.
	 */
	private void refreshMeter(double dt, double value) {
		boolean catchingUp = this.meterFill != value;
		this.meterFill = toward(this.meterFill, value, dt * (Math.abs((value - this.meterFill) * METER_CATCH_UP) + METER_SPEED));
		for (int i = 0; i < this.meterFull.length; i++) {
			if (this.meterFill >= i + 1 && !this.meterFull[i]) {
				if (catchingUp) {
					this.sounds.play("nailgun_charge", 1, i * 0.5F + 1);
				}
				this.meterFull[i] = true;
			} else if (this.meterFill > i && this.meterFill < i + 1) {
				this.meterFull[i] = false;
			}
		}
	}

	/** What its viewmodel shows. */
	FirstPersonWeapons.NailgunState display() {
		double[] fills = new double[this.meterFull.length];
		for (int i = 0; i < fills.length; i++) {
			fills[i] = Math.clamp(this.meterFill - i, 0, 1);
		}
		return new FirstPersonWeapons.NailgunState(this.barrelAngle, this.heat, this.heatMeter, this.heatMeterBackground, fills, this.meterFull.clone(),
			(int)Math.rint(this.charges.ammo), this.layerWeight, this.steaming);
	}

	private static float[] lerp(float[] from, float[] to, float t) {
		float[] out = new float[4];
		for (int i = 0; i < 4; i++) {
			out[i] = from[i] + (to[i] - from[i]) * t;
		}
		return out;
	}

	/** Mathf.MoveTowards. */
	static double toward(double value, double target, double step) {
		return Math.abs(target - value) <= step ? target : value + Math.signum(target - value) * step;
	}
}
