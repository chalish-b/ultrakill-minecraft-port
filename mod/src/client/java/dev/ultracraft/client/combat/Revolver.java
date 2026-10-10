package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;

import java.util.Arrays;

/**
 * ULTRAKILL's revolver (Revolver), one per variant and version: the 0.5 s shot cooldown, the piercer's charged shot,
 * the marksman's coin toss and the sharpshooter's twirl, on the standard revolver or the slab (its altVersion). It
 * decides when to shoot; {@link ClientCombat} does the shooting.
 */
final class Revolver {
	enum Variant {
		PIERCER,
		MARKSMAN,
		SHARPSHOOTER
	}

	interface Actions {
		/**
		 * @param ricochets how often a charged sharpshooter shot bounces off walls
		 */
		void shoot(Revolver revolver, boolean charged, int ricochets);

		void throwCoin();
	}

	/** WeaponCharges' revolver charges, which the standard and slab revolvers share. */
	static final class Charges {
		/** rev0charge: the piercer's charged shot, 0 to 100. */
		double pierce = PIERCE_FULL;
		/** rev0alt: the last piercer drawn was the slab's, which recharges at half the speed. */
		boolean pierceSlab;
		/** rev1charge: coins left to throw, 0 to 400. */
		double coins = COINS_FULL;
		/** rev2charge: sharpshooter shots left, 0 to 300. */
		double sharpshooter = SHARPSHOOTER_FULL;
		/** rev2alt: the last sharpshooter drawn was the slab's, which recharges at 35 a second instead of 15. */
		boolean sharpshooterSlab;
		/**
		 * revaltpickupcharges: by variant, seconds the slab revolver still comes out slowly, reloading, after a shot
		 * its animation didn't get to finish.
		 */
		final double[] pickup = new double[Variant.values().length];

		/** WeaponCharges.Charge. */
		void recharge(double dt) {
			this.pierce = Math.min(this.pierce + (this.pierceSlab ? PIERCE_RECHARGE / 2 : PIERCE_RECHARGE) * dt, PIERCE_FULL);
			this.coins = Math.min(this.coins + COIN_RECHARGE * dt, COINS_FULL);
			double sharpshooterRate = this.sharpshooterSlab ? SLAB_SHARPSHOOTER_RECHARGE : SHARPSHOOTER_RECHARGE;
			this.sharpshooter = Math.min(this.sharpshooter + sharpshooterRate * dt, SHARPSHOOTER_FULL);
			for (int i = 0; i < this.pickup.length; i++) {
				this.pickup[i] = Math.max(this.pickup[i] - dt, 0);
			}
		}
	}

	private static final double PIERCE_FULL = 100;
	private static final double PIERCE_RECHARGE = 40;
	private static final double COINS_FULL = 400;
	private static final double COIN_RECHARGE = 25;
	private static final double SHARPSHOOTER_FULL = 300;
	private static final double SHARPSHOOTER_RECHARGE = 15;
	private static final double SLAB_SHARPSHOOTER_RECHARGE = 35;
	/** What a coin or a sharpshooter shot costs; the slab sharpshooter's shot costs all three. */
	private static final double COIN_COST = 100;
	private static final double SHARPSHOOTER_COST = 100;
	private static final double SLAB_SHARPSHOOTER_COST = 300;
	/** How fast a shot charges, a second: the piercer's, the sharpshooter's and the slab sharpshooter's. */
	private static final double PIERCE_CHARGE_RATE = 175;
	private static final double TWIRL_CHARGE_RATE = 75;
	private static final double SLAB_TWIRL_CHARGE_RATE = 750;
	/** A slab shot needs a slow pickup for this long, unless its animation gets to its Click first. */
	private static final double SLOW_PICKUP_TIME = 2;

	final Variant variant;
	/** The slab revolver (Revolver.altVersion). */
	final boolean slab;
	private final Charges charges;
	private final UltraSounds sounds;
	private final UltraSounds.Loop chargeLoop;
	private final UltraSounds.Loop rechargeLoop;
	private boolean shootReady = true;
	private double shootCharge = 100;
	/** Set by the PickUp and Shoot animations' ReadyGun event. */
	private boolean gunReady;
	/** The marksman's and sharpshooter's own short cooldown after a coin or a charged shot. */
	private boolean pierceReady = true;
	private double pierceCharge = 100;
	/** The piercer's charged shot was ready when it last looked, for the sound it makes when it's ready again. */
	private boolean pierceWasFull = true;
	/** The marksman's or sharpshooter's panels that were full when it last looked (Revolver.coinPanelsCharged). */
	private int chargedPanels;
	/** How far the piercer's or sharpshooter's shot is charged, 0 to 100. */
	double pierceShotCharge;
	/** How fast the charge spins the cylinder (RevolverCylinder.spinSpeed). */
	private double cylinderSpin;
	private boolean chargingPierce;
	/** The sharpshooter still spinning back round to its grip after a charge. */
	private boolean twirlRecovery;
	private double twirlLevel;
	/** Degrees the sharpshooter has spun in the hand this turn. */
	private double latestTwirlRotation;
	private boolean spinning;
	private double twirlSpriteAlpha;

	Revolver(Variant variant, boolean slab, Charges charges, UltraSounds sounds) {
		this.variant = variant;
		this.slab = slab;
		this.charges = charges;
		this.sounds = sounds;
		this.chargeLoop = sounds.loop();
		this.rechargeLoop = sounds.loop();
		this.chargedPanels = fullPanels();
	}

	/** Cooldowns, which keep running while the revolver is holstered. */
	void recharge(double dt) {
		if (!this.shootReady) {
			this.shootCharge += 200 * dt;
			if (this.shootCharge >= 100) {
				this.shootCharge = 100;
				this.shootReady = true;
			}
		}
		if (!this.pierceReady) {
			this.pierceCharge += 480 * dt;
			if (this.pierceCharge >= 100) {
				this.pierceCharge = 100;
				this.pierceReady = true;
			}
		}
	}

	/** Revolver.Update's firing logic and LateUpdate's twirl, while held. */
	void update(double dt, Input input, Actions actions) {
		checkCoinCharges();
		boolean piercer = this.variant == Variant.PIERCER;
		boolean pierceFull = this.charges.pierce >= PIERCE_FULL;
		if (piercer) {
			if (pierceFull && !this.pierceWasFull) {
				this.rechargeLoop.stop();
				this.sounds.play("revolver_charged", 0.35F, this.sounds.randomPitch(1, 1.1F));
			}
			this.pierceWasFull = pierceFull;
			if (!pierceFull) {
				this.cylinderSpin = Math.max(this.cylinderSpin - 50 * dt, 0);
			} else {
				this.cylinderSpin = this.pierceShotCharge;
			}
		}
		if (this.variant == Variant.MARKSMAN) {
			if (input.fire2Pressed && this.pierceReady && this.charges.coins >= COIN_COST) {
				this.charges.coins -= COIN_COST;
				actions.throwCoin();
				this.pierceCharge = 0;
				this.pierceReady = false;
			} else if (this.gunReady && input.fire1Held && this.shootReady) {
				shoot(actions, false);
			}
			return;
		}
		if (this.gunReady) {
			double rate = piercer ? PIERCE_CHARGE_RATE : this.slab ? SLAB_TWIRL_CHARGE_RATE : TWIRL_CHARGE_RATE;
			boolean charged = piercer ? this.pierceShotCharge == 100 : this.pierceShotCharge >= 25;
			boolean canCharge = piercer ? this.shootReady && pierceFull : this.charges.sharpshooter >= sharpshooterCost();
			if ((input.fire2Released || input.fire1Held) && this.shootReady && charged) {
				shoot(actions, true);
			} else if (input.fire1Held && this.shootReady && !this.chargingPierce) {
				shoot(actions, false);
			} else if (input.fire2Held && canCharge) {
				if (!this.chargingPierce && !this.twirlRecovery) {
					this.latestTwirlRotation = 0;
				}
				this.chargingPierce = true;
				this.pierceShotCharge = Math.min(this.pierceShotCharge + rate * dt, 100);
			} else {
				if (this.chargingPierce) {
					this.twirlRecovery = true;
				}
				this.chargingPierce = false;
				this.pierceShotCharge = Math.max(this.pierceShotCharge - rate * dt, 0);
			}
		}
		if (piercer) {
			if (this.pierceShotCharge > 0) {
				this.chargeLoop.update("revolver_charge", 0.25 + this.pierceShotCharge * 0.005, this.pierceShotCharge * 0.005);
			} else {
				this.chargeLoop.stop();
			}
			if (!pierceFull) {
				this.rechargeLoop.update("revolver_recharging", 0.55, this.slab ? 0.5 : 1);
			}
		} else {
			twirl(dt);
		}
	}

	/**
	 * Revolver.CheckCoinCharges: each of the marksman's and sharpshooter's panels chimes as it fills, higher for each
	 * further one. The slab sharpshooter's one panel fills over all three shots.
	 */
	private void checkCoinCharges() {
		int panels = fullPanels();
		if (panels > this.chargedPanels) {
			this.sounds.play(this.variant == Variant.MARKSMAN ? "coin_ready" : "sharpshooter_ready", 1, 1 + (panels - 1) / 2F);
		}
		this.chargedPanels = panels;
	}

	private int fullPanels() {
		return switch (this.variant) {
			case PIERCER -> 0;
			case MARKSMAN -> (int)(this.charges.coins / COIN_COST);
			case SHARPSHOOTER -> this.slab ? (int)(this.charges.sharpshooter / SHARPSHOOTER_FULL) : (int)(this.charges.sharpshooter / SHARPSHOOTER_COST);
		};
	}

	private double sharpshooterCost() {
		return this.slab ? SLAB_SHARPSHOOTER_COST : SHARPSHOOTER_COST;
	}

	/** Revolver.LateUpdate: the sharpshooter spins in the hand while it charges, then spins back round. */
	private void twirl(double dt) {
		if (!this.chargingPierce && !this.twirlRecovery) {
			this.spinning = false;
			this.twirlSpriteAlpha = 0;
			this.chargeLoop.stop();
			return;
		}
		this.spinning = true;
		boolean wasBehind = this.latestTwirlRotation < 0;
		if (this.chargingPierce) {
			this.twirlLevel = Math.min(3, Math.floor(this.pierceShotCharge / 25)) + 1;
		} else {
			this.twirlLevel = moveTowards(this.twirlLevel, 0.1, dt * 100 * this.twirlLevel);
		}
		this.latestTwirlRotation += 1200 * (this.twirlLevel / 3 + 0.5) * dt;
		this.twirlSpriteAlpha = Math.min(2, Math.floor(this.pierceShotCharge / 25)) / 3;
		this.chargeLoop.update("revolver_twirl", 0.25 + this.pierceShotCharge * 0.005, 0.5 + this.twirlLevel / 2);
		if (this.twirlRecovery && wasBehind && this.latestTwirlRotation >= 0) {
			this.latestTwirlRotation = 0;
			this.twirlRecovery = false;
			this.twirlSpriteAlpha = 0;
		} else {
			while (this.latestTwirlRotation > 180) {
				this.latestTwirlRotation -= 360;
			}
		}
	}

	private static double moveTowards(double current, double target, double maxDelta) {
		return Math.abs(target - current) <= maxDelta ? target : current + Math.signum(target - current) * maxDelta;
	}

	private void shoot(Actions actions, boolean charged) {
		this.shootReady = false;
		this.shootCharge = 0;
		this.gunReady = false;
		if (this.slab) {
			this.charges.pickup[this.variant.ordinal()] = SLOW_PICKUP_TIME;
		}
		int ricochets = 0;
		if (charged) {
			ricochets = (int)Math.min(3, Math.floor(this.pierceShotCharge / 25));
			this.pierceShotCharge = 0;
			this.pierceReady = false;
			this.pierceCharge = 0;
			if (this.variant == Variant.PIERCER) {
				this.charges.pierce = 0;
				this.pierceWasFull = false;
				this.chargeLoop.stop();
			} else {
				this.charges.sharpshooter -= sharpshooterCost();
			}
		}
		actions.shoot(this, charged, ricochets);
	}

	/**
	 * Drawn (Revolver.OnEnable): not ready to fire until its PickUp animation says so. The piercer's and
	 * sharpshooter's charges recharge at this one's rate from now on.
	 */
	void equip() {
		this.shootCharge = 100;
		this.gunReady = false;
		this.pierceWasFull = this.charges.pierce >= PIERCE_FULL;
		if (this.variant == Variant.PIERCER) {
			this.charges.pierceSlab = this.slab;
		} else if (this.variant == Variant.SHARPSHOOTER) {
			this.charges.sharpshooterSlab = this.slab;
		}
	}

	/** The slab revolver comes out with a reload (its Animator's SlowPickup) if a shot of its is still unfinished. */
	boolean slowPickup() {
		return this.slab && this.charges.pickup[this.variant.ordinal()] > 0;
	}

	/** The ReadyGun animation event. */
	void readyGun() {
		this.gunReady = true;
	}

	/** The slab revolver's Click animation event: its shot is finished, and the sharpshooter's twirl stops. */
	void click() {
		if (this.slab) {
			this.charges.pickup[this.variant.ordinal()] = 0;
		}
		if (this.variant == Variant.SHARPSHOOTER) {
			this.chargingPierce = false;
			this.twirlRecovery = false;
		}
	}

	/** Revolver.InstaClick: fanning the hammer finishes every slab revolver's shot. */
	void instaClick() {
		if (this.slab) {
			Arrays.fill(this.charges.pickup, 0);
		}
	}

	/** What its screen, charge glow, cylinder and twirl show. */
	FirstPersonWeapons.RevolverState display() {
		double coinCharge = this.variant == Variant.SHARPSHOOTER ? this.charges.sharpshooter : this.charges.coins;
		return new FirstPersonWeapons.RevolverState(
			this.pierceShotCharge, this.charges.pierce, this.charges.pierce >= PIERCE_FULL, coinCharge, this.cylinderSpin,
			this.spinning, this.twirlLevel / 3, this.spinning ? this.latestTwirlRotation : 0, this.twirlSpriteAlpha
		);
	}

	/** Put away: its sounds stop and any charge or twirl is lost. */
	void holster() {
		this.chargeLoop.stop();
		this.rechargeLoop.stop();
		this.chargingPierce = false;
		this.twirlRecovery = false;
		this.spinning = false;
		this.twirlSpriteAlpha = 0;
		this.pierceShotCharge = 0;
	}

	/** One frame of weapon input: Fire1 is attack, Fire2 is use. */
	static final class Input {
		boolean fire1Held;
		boolean fire1Pressed;
		boolean fire2Held;
		boolean fire2Pressed;
		boolean fire2Released;
	}
}
