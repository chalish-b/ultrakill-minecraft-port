package dev.ultracraft.combat;

/**
 * ULTRAKILL's gore types, by the blood splatter each leaves: how much HP standing in it heals and the
 * radius (units) it heals within. Values from the BS Head, BS Body and BS Body Small prefabs.
 */
public enum Gore {
	HEAD(30, 7),
	BODY(10, 5),
	SMALL(10, 5);

	public final float hp;
	public final float radius;

	Gore(float hp, float radius) {
		this.hp = hp;
		this.radius = radius;
	}

	/** Enemy.GetGore: a head hit of at least 1 damage or a killing blow to the head, otherwise body or small. */
	public static Gore of(boolean head, float damage, boolean killed) {
		boolean heavy = damage >= 1 || killed;
		if (head && heavy) {
			return HEAD;
		}
		return heavy ? BODY : SMALL;
	}
}
