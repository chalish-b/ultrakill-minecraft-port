package dev.ultracraft.combat;

/** The arms that punch (Punch's FistType), with the values Punch.Start gives each. */
public enum FistType {
	/** The Feedbacker: parries, and punches coins. */
	STANDARD("feedbacker", 1, 25, 1, false, 2),
	/** The Knuckleblaster: hits harder and further, and bursts the enemies it kills. */
	HEAVY("knuckleblaster", 2.5F, 100, 2, true, 3);

	/** The arm's model and sounds. */
	public final String arm;
	public final float damage;
	/** How hard the punch knocks an enemy back: its speed along the punch, in units per second. */
	public final double force;
	public final double screenShakeMultiplier;
	/** Whether an enemy the punch kills bursts. */
	public final boolean tryForExplode;
	/** A quarter of this is the cooldown before the next punch, and half of it the stamina the punch takes. */
	public final double cooldownCost;

	FistType(String arm, float damage, double force, double screenShakeMultiplier, boolean tryForExplode, double cooldownCost) {
		this.arm = arm;
		this.damage = damage;
		this.force = force;
		this.screenShakeMultiplier = screenShakeMultiplier;
		this.tryForExplode = tryForExplode;
		this.cooldownCost = cooldownCost;
	}
}
