package dev.ultracraft.client.combat;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * A piercing RevolverBeam (one with more than one hit): the piercer's and sharpshooter's charged shots, every slab
 * revolver shot, and the Electric railcannon's beam. It sweeps up everything in its way and lands its hits one after
 * another, each enemy taking up to its hits per target. A coin it reaches fires it on, and a sharpshooter shot comes
 * off walls; each carries on with what's changed of it.
 */
final class PiercingBeam {
	/**
	 * The beam prefabs' values: how many hits it lands, how many of them on one enemy, its headshot bonus
	 * (critMultiplier), whether it's a slab revolver's (strongAlt), whether a coin splits it, how much of a coin's
	 * bonus it takes, and how much it does to a block and how many blocks it goes through if it breaks them.
	 */
	enum Type {
		/** "Revolver Beam Super", the piercer's charged shot. */
		SUPER(Effects.BeamStyle.SUPER, 1, 3, 3, 1.5F, false, true, 0.5F, 2, 3),
		/** "Revolver Beam Sharp", the sharpshooter's charged shot. */
		SHARP(Effects.BeamStyle.SHARP, 1, 999, 1, 1, false, false, 1, 1, 1),
		/** "Revolver Beam Alternative", the slab revolvers' shot. */
		SLAB(Effects.BeamStyle.SLAB, 1.25F, 2, 2, 0, true, false, 1, 2.5F, 1),
		/** "Revolver Beam Super Alternative", the slab piercer's charged shot. */
		SLAB_SUPER(Effects.BeamStyle.SUPER, 1.25F, 6, 4, 0, true, false, 1, 2.5F, 3),
		/** "Revolver Beam Sharp Alternative", the slab sharpshooter's charged shot. */
		SLAB_SHARP(Effects.BeamStyle.SHARP, 1.25F, 999, 2, 0, true, false, 1, 1.25F, 1),
		/** "Railcannon Beam", the Electric railcannon's: a block takes all four of its hits. */
		RAILCANNON(Effects.BeamStyle.RAILCANNON, 2, 999, 4, 0, false, false, 1, 0, 1);

		final Effects.BeamStyle style;
		final float damage;
		final int hitAmount;
		final int maxHitsPerTarget;
		final float critMultiplier;
		final boolean strongAlt;
		final boolean splitcoinable;
		final float coinDamageBonusMultiplier;
		final float blockDamage;
		final int blocks;

		Type(
			Effects.BeamStyle style, float damage, int hitAmount, int maxHitsPerTarget, float critMultiplier, boolean strongAlt, boolean splitcoinable,
			float coinDamageBonusMultiplier, float blockDamage, int blocks
		) {
			this.style = style;
			this.damage = damage;
			this.hitAmount = hitAmount;
			this.maxHitsPerTarget = maxHitsPerTarget;
			this.critMultiplier = critMultiplier;
			this.strongAlt = strongAlt;
			this.splitcoinable = splitcoinable;
			this.coinDamageBonusMultiplier = coinDamageBonusMultiplier;
			this.blockDamage = blockDamage;
			this.blocks = blocks;
		}

		boolean railgun() {
			return this == RAILCANNON;
		}
	}

	final Type type;
	float damage;
	int hitAmount;
	int maxHitsPerTarget;
	int ricochets;
	/** A sharpshooter shot fired to bounce: coins give it more hits per enemy instead of more damage. */
	final boolean hasBeenRicocheter;
	/** Hits it has landed on enemies, out of its hitAmount. */
	int bodiesPierced;
	/** The power a coin it reaches takes, or NaN for the coin to keep its own. */
	float coinPower = Float.NaN;
	/** The enemies coins have already sent this beam at. */
	Set<Integer> chainHits = new HashSet<>();
	/** The slab revolver that fired it, which fans its hammer when the beam splits on a coin. */
	final @Nullable Revolver source;

	PiercingBeam(Type type, @Nullable Revolver source, int ricochets) {
		this(type, source, ricochets, ricochets > 0);
	}

	private PiercingBeam(Type type, @Nullable Revolver source, int ricochets, boolean hasBeenRicocheter) {
		this.type = type;
		this.damage = type.damage;
		this.hitAmount = type.hitAmount;
		this.maxHitsPerTarget = type.maxHitsPerTarget;
		this.source = source;
		this.ricochets = ricochets;
		this.hasBeenRicocheter = hasBeenRicocheter;
	}

	PiercingBeam copy() {
		PiercingBeam copy = new PiercingBeam(this.type, this.source, this.ricochets, this.hasBeenRicocheter);
		copy.damage = this.damage;
		copy.hitAmount = this.hitAmount;
		copy.maxHitsPerTarget = this.maxHitsPerTarget;
		copy.bodiesPierced = this.bodiesPierced;
		copy.coinPower = this.coinPower;
		copy.chainHits = this.chainHits;
		return copy;
	}

	/** One hit's damage: on the head, with its headshot bonus. */
	float hitDamage(boolean head) {
		return head ? this.damage * (1 + this.type.critMultiplier) : this.damage;
	}

	/** What a block it hits takes. */
	float blockDamage() {
		return this.type.railgun() ? this.damage * this.type.maxHitsPerTarget : this.type.blockDamage;
	}

	/** The beam off a wall: one ricochet fewer, and one more hit per enemy, up to three (the slab's four). */
	PiercingBeam offWall() {
		PiercingBeam next = copy();
		next.ricochets--;
		if (this.maxHitsPerTarget < 3 || this.type.strongAlt && this.maxHitsPerTarget < 4) {
			next.maxHitsPerTarget++;
		}
		return next;
	}

	/**
	 * Coin.ReflectRevolver: the beam a coin of {@code power} fires on, with its hits starting over. A slab beam on a
	 * coin that would split it lands one more hit, all of them on one enemy. Going on to anything but another coin, a
	 * bouncing sharpshooter shot gets two more hits per enemy, up to three (the slab's four), and any other beam a
	 * quarter of the coin's power more damage, times its coin bonus.
	 */
	PiercingBeam fromCoin(float power, boolean splitting, boolean toCoin) {
		PiercingBeam next = copy();
		next.bodiesPierced = 0;
		if (this.type.strongAlt && splitting && next.hitAmount < 99) {
			next.hitAmount++;
			next.maxHitsPerTarget = next.hitAmount;
		}
		if (!toCoin) {
			int cap = this.type.strongAlt ? 4 : 3;
			if (!this.type.railgun() && this.hasBeenRicocheter) {
				if (next.maxHitsPerTarget < cap) {
					next.maxHitsPerTarget = Math.min(next.maxHitsPerTarget + 2, cap);
				}
			} else {
				next.damage += power / 4 * this.type.coinDamageBonusMultiplier;
			}
		}
		return next;
	}

	/** RevolverBeam.RicochetAimAssist turns a bouncing beam to an enemy's head, unless it's a slab beam with no headshot bonus. */
	boolean aimsAtHead() {
		return !this.type.strongAlt;
	}
}
