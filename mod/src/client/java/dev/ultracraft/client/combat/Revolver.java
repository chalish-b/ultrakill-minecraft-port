package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;

/**
 * ULTRAKILL's revolver (Revolver), one per variant: the 0.5 s shot cooldown, the piercer's charged shot, the
 * marksman's coin toss and the sharpshooter's twirl. It decides when to shoot; {@link ClientCombat} does the
 * shooting.
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
		void shoot(Variant variant, boolean charged, int ricochets);

		void throwCoin();

		/** Coins left to throw, 0 to 400 (WeaponCharges.rev1charge). */
		double coinCharge();

		void spendCoin();

		/** Sharpshooter shots left, 0 to 300 (WeaponCharges.rev2charge). */
		double sharpshooterCharge();

		void spendSharpshooterCharge();
	}

	final Variant variant;
	private final UltraSounds sounds;
	private final UltraSounds.Loop chargeLoop;
	private final UltraSounds.Loop rechargeLoop;
	private boolean shootReady = true;
	private double shootCharge = 100;
	/** Set by the PickUp and Shoot animations' ReadyGun event. */
	private boolean gunReady;
	private boolean pierceReady = true;
	private double pierceCharge = 100;
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

	Revolver(Variant variant, UltraSounds sounds) {
		this.variant = variant;
		this.sounds = sounds;
		this.chargeLoop = sounds.loop();
		this.rechargeLoop = sounds.loop();
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
			double rate = this.variant == Variant.PIERCER ? 40 : 480;
			this.pierceCharge += rate * dt;
			if (this.pierceCharge >= 100) {
				this.pierceCharge = 100;
				this.pierceReady = true;
				if (this.variant == Variant.PIERCER) {
					this.rechargeLoop.stop();
					this.sounds.play("revolver_charged", 0.35F, this.sounds.randomPitch(1, 1.1F));
				}
			}
		}
	}

	/** Revolver.Update's firing logic and LateUpdate's twirl, while held. */
	void update(double dt, Input input, Actions actions) {
		if (this.variant == Variant.PIERCER) {
			if (!this.pierceReady) {
				this.cylinderSpin = Math.max(this.cylinderSpin - 50 * dt, 0);
			} else {
				this.cylinderSpin = this.pierceShotCharge;
			}
		}
		if (this.variant == Variant.MARKSMAN) {
			if (input.fire2Pressed && this.pierceReady && actions.coinCharge() >= 100) {
				actions.spendCoin();
				actions.throwCoin();
				this.pierceCharge = 0;
				this.pierceReady = false;
			} else if (this.gunReady && input.fire1Held && this.shootReady) {
				shoot(actions, false);
			}
			return;
		}
		boolean piercer = this.variant == Variant.PIERCER;
		if (this.gunReady) {
			double rate = piercer ? 175 : 75;
			boolean charged = piercer ? this.pierceShotCharge == 100 : this.pierceShotCharge >= 25;
			boolean canCharge = piercer ? this.shootReady && this.pierceReady : actions.sharpshooterCharge() >= 100;
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
			if (!this.pierceReady) {
				this.rechargeLoop.update("revolver_recharging", 0.55, 1);
			}
		} else {
			twirl(dt);
		}
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
		int ricochets = 0;
		if (charged) {
			ricochets = (int)Math.min(3, Math.floor(this.pierceShotCharge / 25));
			this.pierceShotCharge = 0;
			this.pierceReady = false;
			this.pierceCharge = 0;
			if (this.variant == Variant.PIERCER) {
				this.chargeLoop.stop();
			} else {
				actions.spendSharpshooterCharge();
			}
		}
		actions.shoot(this.variant, charged, ricochets);
	}

	/** Drawn (Revolver.OnEnable): not ready to fire until its PickUp animation says so. */
	void equip() {
		this.shootCharge = 100;
		this.gunReady = false;
	}

	/** The ReadyGun animation event. */
	void readyGun() {
		this.gunReady = true;
	}

	/** What its screen, charge glow, cylinder and twirl show. */
	FirstPersonWeapons.RevolverState display(double coinCharge) {
		return new FirstPersonWeapons.RevolverState(
			this.pierceShotCharge, this.pierceCharge, this.pierceReady, coinCharge, this.cylinderSpin,
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
