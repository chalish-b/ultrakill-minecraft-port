package dev.ultracraft.combat;

import dev.ultracraft.UltraPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;

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

	private UltraHealth() {
	}

	/** ULTRAKILL difficulty from Minecraft's: 1 (lenient) on peaceful and easy, 2 (standard), 3 (violent). */
	static int difficulty(ServerPlayer player) {
		Difficulty difficulty = player.level().getDifficulty();
		return switch (difficulty) {
			case PEACEFUL, EASY -> 1;
			case NORMAL -> 2;
			case HARD -> 3;
		};
	}

	/** NewMovement.GetHurt's hard damage, for a hit of {@code health} Minecraft health. */
	public static void onHurt(ServerPlayer player, float health) {
		State state = state(player);
		float damage = health * HP_PER_HEALTH;
		int difficulty = difficulty(player);
		if (difficulty < 2 || hp(player) > MAX_HP) {
			return;
		}
		state.hardDamage = Math.min(state.hardDamage + damage * HARD_DAMAGE_SHARE, 99);
		if (state.hardDamageCooldown == 0) {
			state.hardDamageCooldown++;
		}
		if (difficulty >= 3) {
			state.hardDamageCooldown++;
		}
		state.hardDamageCooldown += damage / 20;
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

	/** Hard damage wears off once its cooldown runs out (NewMovement.Update, at style rank D). */
	public static void tick(ServerPlayer player, float dt) {
		State state = state(player);
		if (difficulty(player) <= 1) {
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
	}
}
