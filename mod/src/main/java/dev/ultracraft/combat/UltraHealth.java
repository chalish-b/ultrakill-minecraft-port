package dev.ultracraft.combat;

import dev.ultracraft.UltraDifficulty;
import dev.ultracraft.UltraPlayer;
import net.minecraft.server.level.ServerPlayer;

/**
 * ULTRAKILL's health on a player driven by ULTRAKILL movement (NewMovement's hp, antiHp and GetHealth).
 *
 * <p>ULTRAKILL HP is Minecraft health times five: 100 HP is 20 health, and a hit costs the same share of
 * the bar in both. Health only comes back from blood and parries. Part of every hit becomes hard damage,
 * which caps how far the player can heal until it wears off.
 */
public final class UltraHealth {
	public static final float HP_PER_HEALTH = 5;
	public static final float MAX_HP = 100;
	private static final float HARD_DAMAGE_SHARE = 0.35F;
	private static final float WHIPLASH_HARD_DAMAGE = 66;
	private static final float WHIPLASH_HARD_DAMAGE_CAP = 50;

	private UltraHealth() {
	}

	static int difficulty(ServerPlayer player) {
		return UltraDifficulty.of(player.level().getDifficulty());
	}

	/** NewMovement.GetHurt's hard damage, for a hit of {@code health} Minecraft health. */
	public static void onHurt(ServerPlayer player, float health) {
		State state = state(player);
		float damage = health * HP_PER_HEALTH;
		int difficulty = difficulty(player);
		if (difficulty < UltraDifficulty.STANDARD || hp(player) > MAX_HP) {
			return;
		}
		state.hardDamage = Math.min(state.hardDamage + damage * HARD_DAMAGE_SHARE, 99);
		if (state.hardDamageCooldown == 0) {
			state.hardDamageCooldown++;
		}
		if (difficulty >= UltraDifficulty.VIOLENT) {
			state.hardDamageCooldown++;
		}
		state.hardDamageCooldown += damage / 20;
	}

	/**
	 * HookArm while the player pulls or is pulled to an enemy: their empty health turns into hard damage, 66 HP a
	 * second up to 50, never taking health they have (NewMovement.ForceAntiHP with dontOverwriteHp).
	 */
	public static void whiplash(ServerPlayer player, float dt) {
		State state = state(player);
		float amount = state.hardDamage + WHIPLASH_HARD_DAMAGE * dt;
		if (amount > WHIPLASH_HARD_DAMAGE_CAP) {
			if (state.hardDamage > WHIPLASH_HARD_DAMAGE_CAP) {
				return;
			}
			amount = WHIPLASH_HARD_DAMAGE_CAP;
		}
		float hp = hp(player);
		if (hp > MAX_HP) {
			return;
		}
		amount = Math.clamp(amount, 0, 99);
		if (hp > MAX_HP - amount) {
			amount = MAX_HP - hp;
		}
		float added = amount - state.hardDamage;
		state.hardDamage = amount;
		int difficulty = difficulty(player);
		if (state.hardDamageCooldown < 1 || difficulty >= UltraDifficulty.VIOLENT && state.hardDamageCooldown < 2) {
			state.hardDamageCooldown = difficulty < UltraDifficulty.VIOLENT ? 1 : 2;
		}
		state.hardDamageCooldown += added < 50 ? added / 20 : 2.5F;
	}

	/** NewMovement.GetHealth: heals {@code hp} ULTRAKILL HP, up to what hard damage allows. */
	public static boolean heal(ServerPlayer player, float hp) {
		float cap = MAX_HP - Math.round(state(player).hardDamage);
		float current = hp(player);
		if (current >= cap) {
			return false;
		}
		player.setHealth(Math.min(current + hp, cap) / HP_PER_HEALTH);
		return true;
	}

	static float hp(ServerPlayer player) {
		return player.getHealth() * HP_PER_HEALTH;
	}

	/**
	 * NewMovement.GetHurt from an explosion: until the hurt invincibility it brings runs out, blood from
	 * explosions doesn't heal (NewMovement.exploded).
	 */
	public static void onExplosionHurt(ServerPlayer player, int damage) {
		state(player).explodedTime = damage >= 50 ? 0.8F : 0.5F;
	}

	/** Whether blood spilled by an explosion can't heal the player right now. */
	public static boolean exploded(ServerPlayer player) {
		return state(player).explodedTime > 0;
	}

	/** Hard damage wears off once its cooldown runs out (NewMovement.Update, at style rank D). */
	public static void tick(ServerPlayer player, float dt) {
		State state = state(player);
		state.explodedTime = Math.max(state.explodedTime - dt, 0);
		if (difficulty(player) <= UltraDifficulty.LENIENT) {
			state.hardDamage = 0;
			state.hardDamageCooldown = 0;
		} else if (state.hardDamageCooldown > 0) {
			state.hardDamageCooldown = Math.max(state.hardDamageCooldown - dt, 0);
		} else if (state.hardDamage > 0) {
			state.hardDamage = Math.max(state.hardDamage - 15 * dt, 0);
		}
	}

	public static void reset(ServerPlayer player) {
		State state = state(player);
		state.hardDamage = 0;
		state.hardDamageCooldown = 0;
	}

	private static State state(ServerPlayer player) {
		return ((UltraPlayer)player).ultracraft$health();
	}

	/** Per-player hard damage, in ULTRAKILL HP. */
	public static final class State {
		float hardDamage;
		float hardDamageCooldown;
		/** Seconds of hurt invincibility left after an explosion hurt the player. */
		float explodedTime;
	}
}
