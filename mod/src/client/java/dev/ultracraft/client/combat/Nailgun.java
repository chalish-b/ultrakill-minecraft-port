package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * ULTRAKILL's nailgun (Nailgun), as the Attractor, the Overheat or the JumpStart, on the standard nailgun or the
 * Sawblade Launcher (its altVersion). Held down, its barrels spin up and it fires nails from each in turn; their heat
 * glows on the barrels. The Attractor runs on a hundred nails that refill while it isn't firing, and throws magnets.
 * The Overheat fires slower the hotter it gets, and burns out a heat sink to fire a stream of heated nails until it
 * has cooled. The JumpStart fires its zapper, and fires fastest while the zapper is attached to an enemy.
 * <p>
 * The Sawblade Launcher fires sawblades instead, slower. Its Attractor runs on ten of them. Its Overheat heats up
 * with each shot, firing slower and wider and its saws breaking sooner the hotter it is, and spends its one heat sink
 * on a super saw that hits each enemy more times the hotter it was. Its JumpStart fires fastest while the zapper is
 * out. It decides when to fire; {@link ClientCombat} does the firing.
 */
final class Nailgun {
	enum Variant {
		ATTRACTOR(10),
		OVERHEAT(20),
		JUMPSTART(10);

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
		void shootNail(Nailgun nailgun, int barrel, boolean heated, double spread, float volume, float pitch, int steps);

		/**
		 * Nailgun.Shoot on the Sawblade Launcher: a sawblade out of shoot point {@code barrel}, turned up to a third of
		 * {@code spread} degrees about each axis, wearing out after {@code hitAmount} (NaN for its prefab's), and its
		 * muzzle flash. It flies {@code steps} physics steps before the frame is drawn.
		 */
		void shootSaw(Nailgun nailgun, int barrel, double spread, double hitAmount, int steps);

		/** Nailgun.SuperSaw: the super saw out of shoot point {@code barrel}, stopping to hit each enemy {@code multiHitAmount} times. */
		void shootSuperSaw(Nailgun nailgun, int barrel, int multiHitAmount);

		/** Nailgun.ShootMagnet: a magnet thrown along the aim; the gun plays its Shoot animation if {@code animate}. */
		void shootMagnet(Nailgun nailgun, boolean animate);

		/** Nailgun.ShootZapper: the zapper shot along the aim; the gun plays its Shoot animation if {@code animate}. */
		void shootZapper(Nailgun nailgun, boolean animate);

		/** Whether the zapper is out and attached to an enemy. */
		boolean zapperAttached();

		/** The zapper, if it's out. */
		@Nullable Zapper zapper();

		/** How many units away the enemy straight ahead of the camera is, before any wall, or NaN if there's none. */
		double aimedEnemyDistance();
	}

	/**
	 * WeaponCharges' nailgun charges, which go on recharging whichever weapon is out: the Overheats' heat sinks and
	 * the barrels' heat as the last nailgun put away left them, the Attractors' nails and sawblades and their magnet
	 * charges, of which there are three less the magnets out, and the JumpStarts' zapper, which recharges after a zap.
	 */
	static final class Charges {
		/** naiHeatsinks, naiSawHeatsinks and naiheatUp; nai0set: whether a nailgun has left its heat for the next one. */
		double heatSinks = HEAT_SINKS;
		double sawHeatSinks = SAW_HEAT_SINKS;
		double heat;
		boolean heatLeft;
		/** naiAmmo and naiSaws; naiAmmoDontCharge while an Attractor is firing. */
		double ammo = AMMO;
		double saws = SAWS;
		boolean ammoHeld;
		/** naiMagnetCharge. */
		double magnetCharge = MAGNET_CHARGES;
		/** naiZapperRecharge: the zapper can be shot once it's full. */
		double zapperRecharge = ZAPPER_RECHARGE;

		/** WeaponCharges.Charge. */
		void recharge(double dt, int magnets) {
			this.heatSinks = toward(this.heatSinks, HEAT_SINKS, dt * HEAT_SINK_RECHARGE);
			this.sawHeatSinks = toward(this.sawHeatSinks, SAW_HEAT_SINKS, dt * HEAT_SINK_RECHARGE);
			this.heat = toward(this.heat, 0, dt * COOLING);
			if (!this.ammoHeld) {
				this.ammo = toward(this.ammo, AMMO, dt * AMMO_RECHARGE);
				this.saws = toward(this.saws, SAWS, dt * SAW_RECHARGE);
			}
			int room = MAGNET_CHARGES - magnets;
			if (magnets > 0) {
				if (room > 0 && this.magnetCharge < room) {
					this.magnetCharge = toward(this.magnetCharge, room, dt * MAGNET_RECHARGE);
				}
			} else if (this.magnetCharge < MAGNET_CHARGES) {
				this.magnetCharge = toward(this.magnetCharge, MAGNET_CHARGES, dt * MAGNET_RECHARGE);
			}
			this.zapperRecharge = toward(this.zapperRecharge, ZAPPER_RECHARGE, dt);
		}
	}

	/** Nailgun.fireRate: the fire cooldown a shot sets, before heat changes it, and the Sawblade Launcher's. */
	private static final double FIRE_RATE = 4;
	private static final double SAW_FIRE_RATE = 24;
	/** Nailgun.emptyColor and fullColor: the heat meter's background goes from one to the other with the heat. */
	private static final float[] EMPTY_COLOR = {0.33F, 0.33F, 0.33F, 1};
	private static final float[] FULL_COLOR = {1, 0.5F, 0, 1};
	/** Time.fixedDeltaTime: the fire cooldown counts down, and shots go off, in physics steps. */
	private static final double STEP = 0.008;
	/** The fire cooldown counts down by this much a second; a shot sets it to the fire rate. */
	private static final double COOLDOWN_SPEED = 100;
	private static final int HEAT_SINKS = 2;
	private static final int SAW_HEAT_SINKS = 1;
	private static final double HEAT_SINK_RECHARGE = 0.125;
	private static final int AMMO = 100;
	private static final double AMMO_RECHARGE = 5;
	private static final int SAWS = 10;
	private static final double SAW_RECHARGE = 0.5;
	private static final int MAGNET_CHARGES = 3;
	private static final double MAGNET_RECHARGE = 3;
	/** The zapper recharges this many seconds after a zap. */
	static final double ZAPPER_RECHARGE = 5;
	/** The JumpStart's barrels' heat while it fires: full with the zapper attached, else this. */
	private static final double JUMPSTART_HEAT = 0.33;
	/** Zapper.maxDistance: the zapper's cable reach. An enemy shows as in range this many units closer. */
	private static final double ZAPPER_REACH = 20;
	private static final double ZAPPER_SLACK = 5;
	/** Without a zapper out, the distance meter moves toward the aimed enemy's distance at this much a second. */
	private static final double DISTANCE_METER_SPEED = 10;
	/** About to break, the warning X flashes red and white this often. */
	private static final double WARNING_FLASH = 0.1;
	/** Unity's Color.white, Color.gray and Color.red. */
	private static final float[] WHITE = {1, 1, 1, 1};
	private static final float[] GRAY = {0.5F, 0.5F, 0.5F, 1};
	private static final float[] RED = {1, 0, 0, 1};
	/** The barrels heat up this much a second while the Overheat fires, cool this much while no nailgun fires, and this much burning out. */
	private static final double HEATING = 0.55;
	private static final double COOLING = 0.3;
	private static final double BURNING_OUT = 1;
	/**
	 * The Sawblade Launcher's Overheat heats up this much a shot, and cools this much a second once it's ready to
	 * fire again (this much until then). Its spread widens to this many degrees with its heat past a quarter, and its
	 * saws wear out after three hits cold down to one hot.
	 */
	private static final double SAW_HEATING = 0.125;
	private static final double SAW_COOLING = 0.2;
	private static final double SAW_COOLING_FIRING = 0.03;
	private static final double SAW_SPREAD = 45;
	private static final double SAW_HIT_AMOUNT_COLD = 3;
	private static final double SAW_HIT_AMOUNT_HOT = 1;
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
	/** The Sawblade Launcher (Nailgun.altVersion). */
	final boolean alternate;
	final FirstPersonWeapons.Weapon weapon;
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
	/** The JumpStart's recharge overlay is up; its distance meter; and its screen as it shows now. */
	private boolean recharging;
	private double distanceMeter;
	private FirstPersonWeapons.ZapHud zapHud = FirstPersonWeapons.ZapHud.NONE;
	private double accumulator;

	Nailgun(Variant variant, boolean alternate, FirstPersonWeapons.Weapon weapon, Charges charges, UltraSounds sounds) {
		this.variant = variant;
		this.alternate = alternate;
		this.weapon = weapon;
		this.charges = charges;
		this.sounds = sounds;
		this.whir = sounds.loop();
		this.steam = sounds.loop();
		// Nailgun.Start: the Sawblade Launcher's Overheat has the one heat sink.
		if (alternate && variant == Variant.OVERHEAT) {
			this.heatSinks = SAW_HEAT_SINKS;
		}
	}

	/** Nailgun.OnEnable: the Overheat takes back its heat sinks, and either takes on the heat the last nailgun left. */
	void equip() {
		this.canShoot = false;
		if (this.variant == Variant.OVERHEAT) {
			this.heatSinks = this.alternate ? this.charges.sawHeatSinks : this.charges.heatSinks;
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
			if (this.alternate) {
				this.charges.sawHeatSinks = this.heatSinks;
			} else {
				this.charges.heatSinks = this.heatSinks;
			}
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
		} else if (firing && (this.heat < 1 || this.variant == Variant.JUMPSTART)) {
			this.heat = switch (this.variant) {
				case ATTRACTOR -> 1;
				case OVERHEAT -> this.alternate ? this.heat : toward(this.heat, 1, dt * HEATING);
				case JUMPSTART -> actions.zapperAttached() ? 1 : JUMPSTART_HEAT;
			};
		} else if (this.heat > 0 && !firing) {
			double cooling = !this.alternate ? COOLING : this.fireCooldown <= 0 ? SAW_COOLING : SAW_COOLING_FIRING;
			this.heat = toward(this.heat, 0, dt * cooling);
		}
		if (this.variant == Variant.OVERHEAT) {
			updateHeatMeter();
		}
		this.spinSpeed = firing ? SPIN + this.heat * SPIN_HEAT : toward(this.spinSpeed, 0, dt * SPIN_SLOWING);
		this.fireRate = currentFireRate(actions);
		this.barrelAngle = (this.barrelAngle + this.spinSpeed * dt) % 360;
		this.whir.update("nailgun_spin", 1, this.spinSpeed / SPIN_PITCH);
		if (this.variant == Variant.ATTRACTOR) {
			this.charges.ammoHeld = input.fire1Held;
		}
		if (this.variant == Variant.OVERHEAT && input.fire2Pressed && this.canShoot && !this.burnOut && this.heatSinks >= 1 && this.heat >= 0.1) {
			if (this.alternate) {
				superSaw(actions);
			} else {
				this.burnOut = true;
				this.fireCooldown = 0;
				this.heatSinks--;
				this.steaming = true;
				this.fireRate = FIRE_RATE - 2.5;
			}
		}
		if (this.variant == Variant.ATTRACTOR && input.fire2Pressed) {
			if (this.charges.magnetCharge >= 1) {
				this.layerWeight = animationWeight();
				actions.shootMagnet(this, this.canShoot);
				this.charges.magnetCharge--;
			} else {
				noAmmo();
			}
		}
		if (this.variant == Variant.JUMPSTART && input.fire2Pressed) {
			if (this.charges.zapperRecharge >= ZAPPER_RECHARGE) {
				this.layerWeight = animationWeight();
				actions.shootZapper(this, this.canShoot);
			} else {
				noAmmo();
			}
		}
		if (this.variant == Variant.OVERHEAT && !input.fire1Held) {
			this.heatSinks = toward(this.heatSinks, this.alternate ? SAW_HEAT_SINKS : HEAT_SINKS, dt * HEAT_SINK_RECHARGE);
		}
		if (this.variant != Variant.JUMPSTART) {
			refreshMeter(dt, this.variant == Variant.OVERHEAT ? this.heatSinks : this.charges.magnetCharge);
		} else {
			updateZapHud(dt, actions);
		}
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
			if (this.variant == Variant.ATTRACTOR && Math.rint(this.alternate ? this.charges.saws : this.charges.ammo) <= 0) {
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
			if (this.alternate) {
				this.charges.saws--;
			} else {
				this.charges.ammo--;
			}
		}
		this.barrel = (this.barrel + 1) % 2;
		if (this.alternate) {
			shootSaw(actions, steps);
			return;
		}
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
		actions.shootNail(this, this.barrel, this.burnOut, spread, volume, pitch, steps);
	}

	/**
	 * Nailgun.Shoot on the Sawblade Launcher: the Overheat fires wider the hotter it is (widest with no heat sink
	 * left), and its saws, like the JumpStart's, wear out sooner; the Overheat then heats up.
	 */
	private void shootSaw(Actions actions, int steps) {
		double spread = 0;
		if (this.variant == Variant.OVERHEAT) {
			spread = this.heatSinks < 1 ? SAW_SPREAD : SAW_SPREAD * Math.clamp(this.heat - 0.25, 0, 1);
		}
		double hitAmount = Double.NaN;
		if (this.variant != Variant.ATTRACTOR) {
			hitAmount = this.heatSinks >= 1 && this.variant != Variant.JUMPSTART
				? SAW_HIT_AMOUNT_COLD + (SAW_HIT_AMOUNT_HOT - SAW_HIT_AMOUNT_COLD) * Math.clamp(this.heat, 0, 1)
				: SAW_HIT_AMOUNT_HOT;
		}
		actions.shootSaw(this, this.barrel, spread, hitAmount, steps);
		if (this.variant == Variant.OVERHEAT && this.heatSinks >= 1) {
			this.heat = toward(this.heat, 1, SAW_HEATING);
		}
	}

	/** Nailgun.SuperSaw: the super saw out of the next barrel, hitting each enemy once for every third of the heat; it spends the heat sink and the heat. */
	private void superSaw(Actions actions) {
		this.fireCooldown = this.fireRate;
		this.shotSuccessfully = true;
		this.layerWeight = 0;
		this.barrel = (this.barrel + 1) % 2;
		actions.shootSuperSaw(this, this.barrel, (int)Math.rint(this.heat * 3));
		this.heatSinks--;
		this.heat = 0;
	}

	/**
	 * Nailgun.UpdateZapHud: the recharge overlay shows while the zapper recharges, and goes with a chime once it has.
	 * With no zapper out, the screen reads whether the enemy aimed at is within reach, its distance meter following
	 * how far; with one out, the cable's length (reddening toward its reach) and the zapper's charge, or a flashing
	 * X while the cable is too long or blocked.
	 */
	private void updateZapHud(double dt, Actions actions) {
		if (this.charges.zapperRecharge < ZAPPER_RECHARGE) {
			this.recharging = true;
			this.distanceMeter = 0;
			this.zapHud = new FirstPersonWeapons.ZapHud(true, this.charges.zapperRecharge / ZAPPER_RECHARGE, "", WHITE, false, 0, false, 0, false, WHITE);
			return;
		}
		if (this.recharging) {
			this.sounds.play("jumpstart_ready", 1, 1);
			this.recharging = false;
		}
		Zapper zapper = actions.zapper();
		if (zapper == null) {
			double aimed = actions.aimedEnemyDistance();
			String status = this.alternate ? "NULL" : "NO TARGET";
			float[] color = GRAY;
			boolean variantColor = false;
			double target = 0;
			if (!Double.isNaN(aimed)) {
				double inRange = ZAPPER_REACH - ZAPPER_SLACK;
				if (aimed < inRange) {
					status = "READY";
					target = 1 - (inRange - aimed) / inRange;
					color = WHITE;
				} else {
					status = this.alternate ? "TOO FAR" : "OUT OF RANGE";
					variantColor = true;
				}
			}
			this.distanceMeter = toward(this.distanceMeter, target, dt * DISTANCE_METER_SPEED);
			this.zapHud = new FirstPersonWeapons.ZapHud(false, 0, status, color, variantColor, this.distanceMeter, false, 0, false, WHITE);
			return;
		}
		boolean zapMeter = zapper.charge > 0;
		double charge = zapper.charge / Zapper.FULL_CHARGE;
		if (zapper.distance > ZAPPER_REACH || zapper.raycastBlocked) {
			this.distanceMeter = 1;
			float[] flash = zapper.breakTimer % WARNING_FLASH > WARNING_FLASH / 2 ? RED : WHITE;
			this.zapHud = new FirstPersonWeapons.ZapHud(false, 0, zapper.raycastBlocked ? "BLOCKED" : this.alternate ? "TOO FAR" : "OUT OF RANGE", WHITE, true, 1, zapMeter, charge, true, flash);
			return;
		}
		double left = (ZAPPER_REACH - zapper.distance) / ZAPPER_REACH;
		this.distanceMeter = 1 - left;
		float[] color = {RED[0] + (WHITE[0] - RED[0]) * (float)left, RED[1] + (WHITE[1] - RED[1]) * (float)left, RED[2] + (WHITE[2] - RED[2]) * (float)left, 1};
		String status = String.format(Locale.ROOT, this.alternate ? "%.1f" : "DISTANCE: %.1f", zapper.distance);
		this.zapHud = new FirstPersonWeapons.ZapHud(false, 0, status, color, false, this.distanceMeter, zapMeter, charge, false, WHITE);
	}

	/** The NailgunNoAmmo click. */
	private void noAmmo() {
		this.sounds.play("nailgun_no_ammo", 1, this.sounds.randomPitch(0.95F, 1.05F));
	}

	/**
	 * Nailgun's fire rate: the Attractor's and the JumpStart's slow as their heat drops (the Sawblade Launcher's
	 * JumpStart fires at half its rate with no zapper out); the Overheat's slows past half heat, and slower still with
	 * no heat sink left, while a burnout fires fastest.
	 */
	private double currentFireRate(Actions actions) {
		double fireRate = this.alternate ? SAW_FIRE_RATE : FIRE_RATE;
		if (this.variant != Variant.OVERHEAT) {
			if (this.variant == Variant.JUMPSTART && this.alternate && actions.zapper() == null) {
				return fireRate * 2;
			}
			return fireRate + 3.5 - this.heat * 3.5;
		}
		if (this.burnOut) {
			return FIRE_RATE - 2.5;
		}
		if (this.heatSinks >= 1) {
			return this.heat < 0.5 ? fireRate : fireRate + (this.heat - 0.5) * (this.alternate ? 65 : 7);
		}
		return fireRate + (this.alternate ? 50 : 10);
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
			(int)Math.rint(this.alternate ? this.charges.saws : this.charges.ammo), this.layerWeight, this.steaming, this.zapHud);
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
