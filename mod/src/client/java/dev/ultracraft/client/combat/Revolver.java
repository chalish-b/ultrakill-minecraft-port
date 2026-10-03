package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;

/**
 * ULTRAKILL's revolver (Revolver), one per variant: the 0.5 s shot cooldown, the piercer's charged shot and
 * the marksman's coin toss. It decides when to shoot; {@link ClientCombat} does the shooting.
 */
final class Revolver {
	enum Variant {
		PIERCER,
		MARKSMAN
	}

	interface Actions {
		void shoot(Variant variant, boolean charged);

		void throwCoin();

		/** Coins left to throw, 0 to 400 (WeaponCharges.rev1charge). */
		double coinCharge();

		void spendCoin();
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
	/** How far the piercer's shot is charged, 0 to 100. */
	double pierceShotCharge;
	private boolean chargingPierce;

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

	/** Revolver.Update's firing logic, while held. */
	void update(double dt, Input input, Actions actions) {
		if (this.variant == Variant.PIERCER) {
			if (this.gunReady) {
				double rate = 175;
				if ((input.fire2Released || input.fire1Held) && this.shootReady && this.pierceShotCharge == 100) {
					shoot(actions, true);
				} else if (input.fire1Held && this.shootReady && !this.chargingPierce) {
					shoot(actions, false);
				} else if (input.fire2Held && this.shootReady && this.pierceReady) {
					this.chargingPierce = true;
					this.pierceShotCharge = Math.min(this.pierceShotCharge + rate * dt, 100);
				} else {
					this.chargingPierce = false;
					this.pierceShotCharge = Math.max(this.pierceShotCharge - rate * dt, 0);
				}
			}
			if (this.pierceShotCharge > 0) {
				this.chargeLoop.update("revolver_charge", 0.25 + this.pierceShotCharge * 0.005, this.pierceShotCharge * 0.005);
			} else {
				this.chargeLoop.stop();
			}
			if (!this.pierceReady) {
				this.rechargeLoop.update("revolver_recharging", 0.55, 1);
			}
		} else if (input.fire2Pressed && this.pierceReady && actions.coinCharge() >= 100) {
			actions.spendCoin();
			actions.throwCoin();
			this.pierceCharge = 0;
			this.pierceReady = false;
		} else if (this.gunReady && input.fire1Held && this.shootReady) {
			shoot(actions, false);
		}
	}

	private void shoot(Actions actions, boolean charged) {
		this.shootReady = false;
		this.shootCharge = 0;
		this.gunReady = false;
		if (charged) {
			this.pierceShotCharge = 0;
			this.pierceReady = false;
			this.pierceCharge = 0;
			this.chargingPierce = false;
			this.chargeLoop.stop();
		}
		actions.shoot(this.variant, charged);
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

	/** Put away: its sounds stop and any charge is lost. */
	void holster() {
		this.chargeLoop.stop();
		this.rechargeLoop.stop();
		this.chargingPierce = false;
		this.pierceShotCharge = 0;
	}

	/** One frame of weapon input: Fire1 is attack, Fire2 is use. */
	static final class Input {
		boolean fire1Held;
		boolean fire2Held;
		boolean fire2Pressed;
		boolean fire2Released;
	}
}
