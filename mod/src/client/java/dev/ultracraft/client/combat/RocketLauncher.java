package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;

/**
 * ULTRAKILL's rocket launcher (RocketLauncher), with the charges its variants share (WeaponCharges): its fire
 * cooldown, which goes on counting down while it's put away and is at least a quarter of a second once it's drawn
 * again, and the Freezeframe's freeze. Fire2 on the Freezeframe freezes every rocket out, for up to 5 seconds, and
 * again unfreezes them; frozen, the freeze runs down in real time and the clock ticks, and with no rockets left
 * it soon unfreezes by itself. Unfrozen, it refills at half that speed, its windup sounding at each quarter. It
 * decides when to fire; {@link ClientCombat} does the firing.
 */
final class RocketLauncher {
	enum Variant {
		FREEZEFRAME
	}

	interface Actions {
		/** RocketLauncher.Shoot. */
		void shootRocket(Variant variant);
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
	/** RocketLauncher.lastKnownTimerAmount: the freeze when the windups were last checked. */
	private double lastKnownTimerAmount = FREEZE_TIME;
	/** RocketLauncher.currentTimerTickSound, and how long since it started. */
	private final UltraSounds.Loop ticker;
	private double tickerAge;

	RocketLauncher(UltraSounds sounds) {
		this.sounds = sounds;
		this.ticker = sounds.loop();
	}

	/** Whether the rockets out are frozen (WeaponCharges.rocketFrozen). */
	boolean frozen() {
		return this.frozen;
	}

	/** How full the freeze is, 0 to 1, as the Freezeframe's dial shows it. */
	double freezeShare() {
		return this.freezeTime / FREEZE_TIME;
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
	}

	/** Put away: its cooldown goes on counting down. */
	void holster() {
		this.equipped = false;
	}

	/** RocketLauncher.Update, for the held rocket launcher. */
	void update(double dt, Revolver.Input input, Actions actions) {
		if (this.held == Variant.FREEZEFRAME) {
			if (this.freezeTime > 0 && input.fire2Pressed) {
				if (this.frozen) {
					unfreeze();
				} else {
					freeze();
				}
			}
			windups();
		}
		if (this.cooldown > 0) {
			this.cooldown = Math.max(this.cooldown - dt, 0);
		} else if (input.fire1Held) {
			this.cooldown = RATE_OF_FIRE;
			actions.shootRocket(this.held);
		}
	}

	/** The windup as the freeze passes each quarter on its way up, higher the fuller it is. */
	private void windups() {
		if (this.lastKnownTimerAmount == this.freezeTime) {
			return;
		}
		for (int quarter = 4; quarter > 0; quarter--) {
			if (this.freezeTime / FREEZE_TIME >= quarter / 4.0 && this.lastKnownTimerAmount / FREEZE_TIME < quarter / 4.0) {
				this.sounds.play("rocket_freeze_windup", 1, WINDUP_PITCH + quarter * 0.1F);
				break;
			}
		}
		this.lastKnownTimerAmount = this.freezeTime;
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
