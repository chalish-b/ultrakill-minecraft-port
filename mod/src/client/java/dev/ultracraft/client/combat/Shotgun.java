package dev.ultracraft.client.combat;

/**
 * ULTRAKILL's shotgun (Shotgun), one per variant. Holding fire shoots whenever the gun is ready, which its
 * animations' ReadyGun event says; after a shot its heat sink glows, and cools once the animation releases the
 * heat. It decides when to shoot; {@link ClientCombat} does the shooting.
 */
final class Shotgun {
	enum Variant {
		CORE_EJECT
	}

	interface Actions {
		void shootShotgun(Variant variant);
	}

	/** How fast the heat sink's glow fades once released, in alpha a second. */
	private static final double HEAT_FADE = 2.5;

	final Variant variant;
	/** Set by the Equip and firing animations' ReadyGun event. */
	private boolean gunReady;
	/** The heat sink's glow (HeatSinkHeat's tint alpha). */
	private double heat;
	private boolean releasingHeat;

	Shotgun(Variant variant) {
		this.variant = variant;
	}

	void update(double dt, Revolver.Input input, Actions actions) {
		if (input.fire1Held && this.gunReady) {
			this.gunReady = false;
			this.releasingHeat = false;
			this.heat = 1;
			actions.shootShotgun(this.variant);
		}
	}

	/** The heat sink cools whether or not the gun is held. */
	void cool(double dt) {
		if (this.releasingHeat) {
			this.heat -= HEAT_FADE * dt;
		}
	}

	/** Drawn (Shotgun.OnEnable): its Equip animation readies it. */
	void equip() {
		this.gunReady = false;
	}

	/** The ReadyGun animation event. */
	void readyGun() {
		this.gunReady = true;
	}

	/** The ReleaseHeat animation event. */
	void releaseHeat() {
		this.releasingHeat = true;
	}

	/** Put away (Shotgun.OnDisable). */
	void holster() {
		this.gunReady = false;
	}

	double heat() {
		return this.heat;
	}
}
