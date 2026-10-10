package dev.ultracraft.client.combat;

import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import net.minecraft.util.RandomSource;

/**
 * The chainsaw on a Sawed-On gun, the shotgun's or the Jackhammer's (their chainsaw parts, which ShotgunHammer
 * copies from Shotgun): once recharged it's on the gun, and it revs while alt fire is held; enemies in front of it
 * take hits, and after enough of those that don't kill, it wears out and then breaks off. Otherwise letting go throws
 * it. The gun decides when it revs and comes off; {@link ClientCombat} does the rest.
 */
final class MountedSaw {
	interface Actions {
		/** ShootSaw: the chainsaw comes off the gun, thrown with {@code force} (0 to 60) of charge or, if not {@code thrown}, broken off. */
		void shootSaw(MountedSaw saw, double force, boolean thrown);

		/** The chainsaw wears out or breaks: its break effect at the blade, and a camera shake of {@code shake}. */
		void sawBreaks(MountedSaw saw, double shake);
	}

	/** What a rev's hits did to the chainsaw. */
	enum Wear {
		NONE,
		WORN,
		BROKE
	}

	/** The rev's charge goes up to this, at this much a second. */
	static final double MAX_FORCE = 60;
	/** Revving, the gun shakes by up to this fraction of the force, in units. */
	private static final double JITTER = 1 / 12000.0;
	/** Hits that don't kill wear the chainsaw out past this many, and break it past this many. */
	private static final int WEAR_HITS = 40;
	private static final int BREAK_HITS = 60;
	/** The chainsaw's hits are forgotten once it's been put away (or let go) this long (WeaponCharges.shoSawResetTimer). */
	private static final double HITS_RESET = 3;
	/** Worn out, the rev wanders between pitches this far either side of 1, at this much a second. */
	private static final double BROKEN_PITCH_RANGE = 0.33;
	private static final double BROKEN_PITCH_SPEED = 2;

	/** The gun it's on. */
	final FirstPersonWeapons.Weapon weapon;
	private final Shotgun.Charges charges;
	private final UltraSounds.Loop revSound;
	private final RandomSource random = RandomSource.create();
	private boolean revving;
	/** chargeForce: the rev's charge. */
	private double force;
	/** The chainsaw is on the gun (its attach point is active). */
	private boolean attached = true;
	/** chainsawNonlethalHits: the revving chainsaw's hits that didn't kill. */
	private int hits;
	/** chainsawBroken: the chainsaw is worn out. */
	private boolean broken;
	private double revPitch;
	private double brokenPitchTarget = 1;
	/** How far the blade's texture has scrolled across. */
	private double bladeScroll;

	MountedSaw(FirstPersonWeapons.Weapon weapon, Shotgun.Charges charges, UltraSounds sounds) {
		this.weapon = weapon;
		this.charges = charges;
		this.revSound = sounds.once();
	}

	/** WeaponCharges.shoSawCharge is full: the chainsaw can rev. */
	boolean charged() {
		return this.charges.sawCharge >= 1;
	}

	/** Alt fire held: the chainsaw revs harder, its sound rising with the charge (or wandering, worn out). */
	void rev(double dt) {
		if (!this.revving && this.hits != 0 && this.charges.sawHitsReset <= 0) {
			// HurtZone.OnEnable: hits from before are forgotten once their time is up.
			this.hits = 0;
		}
		this.revving = true;
		this.force = Math.min(this.force + MAX_FORCE * dt, MAX_FORCE);
		if (this.broken) {
			double step = BROKEN_PITCH_SPEED * dt;
			double toward = this.brokenPitchTarget - this.revPitch;
			this.revPitch = Math.abs(toward) <= step ? this.brokenPitchTarget : this.revPitch + Math.signum(toward) * step;
			if (this.revPitch == this.brokenPitchTarget) {
				this.brokenPitchTarget = 1 + (this.random.nextDouble() * 2 - 1) * BROKEN_PITCH_RANGE;
			}
		} else {
			this.revPitch = (this.force / 2 + 30) / MAX_FORCE;
		}
		this.revSound.update("chainsaw_rev", 1, this.revPitch);
	}

	/** The blade's texture runs with the rev. */
	void scroll(double dt) {
		this.bladeScroll += this.force / 6 * dt;
	}

	/** Too many hits that don't kill wear the chainsaw out, and then break it; a broken one is mended as it comes off. */
	Wear wear() {
		if (!this.revving) {
			return Wear.NONE;
		}
		if (this.hits > BREAK_HITS) {
			this.broken = false;
			return Wear.BROKE;
		}
		if (this.hits > WEAR_HITS && !this.broken) {
			this.broken = true;
			this.brokenPitchTarget = 1;
			return Wear.WORN;
		}
		return Wear.NONE;
	}

	/**
	 * ShootSaw's part for the chainsaw: it stops revving and leaves the gun, which has to recharge it; one thrown is
	 * mended. Its hits are kept for a while.
	 *
	 * @return the rev's charge it went with
	 */
	double comeOff(boolean thrown) {
		this.revving = false;
		this.charges.sawCharge = 0;
		this.revSound.stop();
		keepHits();
		this.attached = false;
		if (thrown) {
			this.broken = false;
		}
		double force = this.force;
		this.force = 0;
		return force;
	}

	/** HurtZone.OnDisable: the hits are forgotten if the chainsaw stays off for a while. */
	private void keepHits() {
		if (this.hits > 0) {
			this.charges.sawHitsReset = HITS_RESET;
		}
	}

	/** UpdateMeter: a recharged chainsaw is back on the gun. */
	void reattach() {
		if (charged()) {
			this.attached = true;
		}
	}

	/** Drawn (OnEnable): a charged chainsaw is on the gun, and a worn-out one is mended once its hits' time is up. */
	void equip() {
		this.attached = charged();
		if (this.broken && this.charges.sawHitsReset <= 0) {
			this.broken = false;
		}
	}

	/** Put away (OnDisable): a revving chainsaw stops. */
	void holster() {
		if (this.revving) {
			keepHits();
		}
		this.revving = false;
		this.force = 0;
		this.revSound.stop();
	}

	/** The revving chainsaw hit an enemy without killing it. */
	void hit() {
		this.hits++;
	}

	boolean revving() {
		return this.revving;
	}

	double force() {
		return this.force;
	}

	/** How far the gun shakes from its place while revving, in units. */
	double jitter() {
		return this.force * JITTER;
	}

	boolean attached() {
		return this.attached;
	}

	boolean broken() {
		return this.broken;
	}

	double bladeScroll() {
		return this.bladeScroll;
	}

	FirstPersonWeapons.SawState display() {
		return new FirstPersonWeapons.SawState(this.attached, this.revving, this.broken, this.bladeScroll);
	}
}
