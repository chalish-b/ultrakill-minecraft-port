package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraItems;
import dev.ultracraft.client.UltraController;
import dev.ultracraft.client.UltraKeys;
import dev.ultracraft.client.movement.UltraMovement;
import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import dev.ultracraft.client.render.ScreenFlashes;
import dev.ultracraft.client.render.animation.Animator;
import dev.ultracraft.combat.Gore;
import dev.ultracraft.net.CycleVariantPayload;
import dev.ultracraft.net.PunchPayload;
import dev.ultracraft.net.PunchResultPayload;
import dev.ultracraft.net.WeaponHitPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The client's side of ULTRAKILL combat: weapon input, the revolver's shots and coins, the Feedbacker punch,
 * weapon charges (WeaponCharges) and the hitstop after a parry. Hits go to the server, which applies them.
 */
public final class ClientCombat implements Revolver.Actions, FirstPersonWeapons.Events {
	public static final ClientCombat INSTANCE = new ClientCombat();
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	private static final double RANGE = 256;
	private static final double COIN_TARGET_RANGE = 64;
	private static final double HITSTOP = 0.25;

	private final UltraSounds sounds = new UltraSounds();
	private final Effects effects = new Effects();
	private final Map<Revolver.Variant, Revolver> revolvers = new EnumMap<>(Revolver.Variant.class);
	private final List<Coin> coins = new ArrayList<>();
	private final Revolver.Input input = new Revolver.Input();
	private final RandomSource random = RandomSource.create();
	private Revolver.@Nullable Variant held;
	private boolean wasFire2;
	private double coinCharge = 400;
	private int readyCoins = 4;
	private double punchStamina = 2;
	/** Punch.ready: cleared by a punch, set again by its animation's ReadyToPunch event. */
	private boolean punchReady = true;
	private double fistCooldown;
	private double hitstopLeft;
	private float lastHealth = -1;
	private double quietHealLeft;

	private ClientCombat() {
		for (Revolver.Variant variant : Revolver.Variant.values()) {
			this.revolvers.put(variant, new Revolver(variant, this.sounds));
		}
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(PunchResultPayload.TYPE, (payload, context) -> INSTANCE.onPunchResult(payload));
	}

	/** Whether time stands still after a parry. */
	public boolean inHitstop() {
		return this.hitstopLeft > 0;
	}

	/** Called every frame, with or without ULTRAKILL movement. */
	public void frame(double dt, Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			this.coins.clear();
			return;
		}
		ScreenFlashes.INSTANCE.update(dt);
		if (this.hitstopLeft > 0) {
			this.hitstopLeft -= dt;
			return;
		}
		this.effects.update(dt);
		this.quietHealLeft -= dt;
		for (Revolver revolver : this.revolvers.values()) {
			revolver.recharge(dt);
		}
		this.coinCharge = Math.min(this.coinCharge + 25 * dt, 400);
		int coins = (int)(this.coinCharge / 100);
		if (coins > this.readyCoins && this.held == Revolver.Variant.MARKSMAN) {
			this.sounds.play("coin_ready", 1, 1 + (coins - 1) / 2F);
		}
		this.readyCoins = coins;
		this.punchStamina = Math.min(this.punchStamina + 1.25 * dt, 2);
		this.fistCooldown = Math.max(this.fistCooldown - 2 * dt, 0);
		for (Coin coin : this.coins) {
			coin.update(dt, minecraft.level, player);
			if (coin.shot && !coin.removed) {
				coin.reflectTimer -= dt;
				if (coin.reflectTimer <= 0) {
					reflect(coin, minecraft.level, player);
				}
			}
		}
		this.coins.removeIf(coin -> coin.removed);

		boolean driving = UltraController.INSTANCE.isDriving(player);
		updateHeld(driving ? variantOf(player.getMainHandItem()) : null);
		if (driving) {
			healFeedback(player);
			sampleInput(minecraft.options);
			if (consumePunch() && this.punchReady && this.fistCooldown <= 0 && this.punchStamina >= 1) {
				punch();
			}
			if (this.held != null) {
				this.revolvers.get(this.held).update(dt, this.input, this);
			}
		} else {
			this.lastHealth = -1;
		}
		// Animators run after gameplay code, as Unity's do, so what it set this frame plays this frame.
		FirstPersonWeapons.INSTANCE.update(dt, viewmodel(this.held), this.held == null ? 0 : this.revolvers.get(this.held).pierceShotCharge, this);
	}

	private void updateHeld(Revolver.@Nullable Variant variant) {
		if (variant == this.held) {
			return;
		}
		if (this.held != null) {
			this.revolvers.get(this.held).holster();
		}
		this.held = variant;
		this.wasFire2 = false;
		FirstPersonWeapons.Weapon weapon = viewmodel(variant);
		if (variant != null && weapon != null) {
			this.revolvers.get(variant).equip();
			FirstPersonWeapons.INSTANCE.equip(weapon);
		}
	}

	@Override
	public void weaponEvent(FirstPersonWeapons.Weapon weapon, String function) {
		if (function.equals("ReadyGun")) {
			this.revolvers.get(weapon == FirstPersonWeapons.Weapon.REVOLVER_PIERCER ? Revolver.Variant.PIERCER : Revolver.Variant.MARKSMAN).readyGun();
		}
	}

	@Override
	public void armEvent(String function) {
		switch (function) {
			case "ActiveStart" -> ClientPlayNetworking.send(new PunchPayload());
			case "ReadyToPunch" -> this.punchReady = true;
			default -> {
			}
		}
	}

	private static FirstPersonWeapons.@Nullable Weapon viewmodel(Revolver.@Nullable Variant variant) {
		if (variant == null) {
			return null;
		}
		return variant == Revolver.Variant.PIERCER ? FirstPersonWeapons.Weapon.REVOLVER_PIERCER : FirstPersonWeapons.Weapon.REVOLVER_MARKSMAN;
	}

	private static Revolver.@Nullable Variant variantOf(ItemStack stack) {
		if (stack.is(UltraItems.REVOLVER_PIERCER)) {
			return Revolver.Variant.PIERCER;
		}
		return stack.is(UltraItems.REVOLVER_MARKSMAN) ? Revolver.Variant.MARKSMAN : null;
	}

	/** Whether the held item is an ULTRAKILL weapon in use, so attacking and using belong to it. */
	public boolean handlesHeldItem() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && UltraController.INSTANCE.isDriving(player) && UltraItems.isWeapon(player.getMainHandItem());
	}

	/** Pressing the selected slot's key again switches the weapon there to its next variant. */
	public void cycleVariant() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && UltraItems.isWeapon(player.getMainHandItem()) && ClientPlayNetworking.canSend(CycleVariantPayload.TYPE)) {
			ClientPlayNetworking.send(new CycleVariantPayload());
		}
	}

	private void sampleInput(Options options) {
		boolean fire2 = options.keyUse.isDown();
		this.input.fire1Held = options.keyAttack.isDown();
		this.input.fire2Held = fire2;
		this.input.fire2Pressed = fire2 && !this.wasFire2;
		this.input.fire2Released = !fire2 && this.wasFire2;
		this.wasFire2 = fire2;
	}

	private static boolean consumePunch() {
		boolean pressed = false;
		while (UltraKeys.PUNCH.consumeClick()) {
			pressed = true;
		}
		return pressed;
	}

	// ---- revolver ---------------------------------------------------------------------------

	@Override
	public void shoot(Revolver.Variant variant, boolean charged) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || minecraft.level == null || movement == null) {
			return;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		List<Hitscan.Hit> hits = Hitscan.cast(minecraft.level, player, eye, look, RANGE, this.coins);

		String sound = variant == Revolver.Variant.PIERCER ? "revolver_shoot.piercer" : "revolver_shoot.marksman";
		this.sounds.play(sound, 0.55F, this.sounds.randomPitch());
		this.sounds.play("revolver_click", 1, 1);
		if (charged) {
			this.sounds.play("revolver_super", 1, 1);
		}
		movement.kickFov(charged ? 1 / 20.0 : 1 / 40.0);
		Animator animator = FirstPersonWeapons.INSTANCE.animator(viewmodel(variant));
		if (animator != null) {
			animator.setFloat("RandomChance", this.random.nextFloat());
			animator.setTrigger(charged ? "ChargeShoot" : "Shoot");
		}

		Vec3 end = hits.getLast().position();
		int bodiesLeft = charged ? 3 : 1;
		for (Hitscan.Hit hit : hits) {
			if (hit.kind() == Hitscan.Kind.ENTITY) {
				if (bodiesLeft-- <= 0) {
					continue;
				}
				// The charged beam usually crosses two of an enemy's hitboxes; Minecraft mobs have one, so it
				// counts twice, once with the head bonus.
				float damage = charged ? (hit.head() ? 2.5F : 1) + 1 : (hit.head() ? 2 : 1);
				damage(minecraft.level, hit.entity(), damage, hit.head(), hit.position());
				movement.shake(charged ? 1 : 0.5);
				if (!charged) {
					end = hit.position();
					break;
				}
			} else if (hit.kind() == Hitscan.Kind.COIN) {
				hit.coin().hit(hit.coin().power, new HashSet<>());
				end = hit.position();
				break;
			} else {
				if (hit.kind() == Hitscan.Kind.BLOCK) {
					this.effects.impact(minecraft.level, hit.position());
				}
				end = hit.position();
				break;
			}
		}
		this.effects.beam(muzzle(eye, look, player), end, charged ? Effects.BeamStyle.SUPER : Effects.BeamStyle.REVOLVER);
	}

	/** Roughly where the barrel is, for drawing the beam from it. */
	private static Vec3 muzzle(Vec3 eye, Vec3 look, LocalPlayer player) {
		double yaw = Math.toRadians(player.getYRot());
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		return eye.add(look.scale(0.6)).add(right.scale(0.18)).add(0, -0.14, 0);
	}

	private void damage(Level level, LivingEntity target, float damage, boolean head, Vec3 at) {
		ClientPlayNetworking.send(new WeaponHitPayload(target.getId(), damage, head, at));
		Gore gore = Gore.of(head, damage, false);
		this.effects.blood(level, at, gore);
		this.sounds.play("beam_hit", 1, 1);
		this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
	}

	@Override
	public void throwCoin() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || movement == null) {
			return;
		}
		Vec3 velocity = player.getLookAngle().scale(20).add(0, 15, 0).add(movement.velocity());
		this.coins.add(new Coin(movement.eyePosition().add(0, -0.5 * S, 0), velocity, player.getYRot(), player.getXRot()));
		this.sounds.play("coin_throw", 1, 1);
		// Punch.CoinFlip
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null && this.punchReady) {
			arm.setTrigger("CoinFlip");
		}
	}

	@Override
	public double coinCharge() {
		return this.coinCharge;
	}

	@Override
	public void spendCoin() {
		this.coinCharge -= 100;
	}

	/** Coin.ReflectRevolver: the shot goes on to the nearest coin, else the nearest enemy's head. */
	private void reflect(Coin coin, Level level, LocalPlayer player) {
		coin.removed = true;
		float pitch = coin.power > 2 ? 1 + (coin.power - 2) / 5 : 1;
		this.sounds.play("coin_hit", 1, pitch);
		this.sounds.play("coin_hit_flip", 1, 1);

		Coin next = this.coins.stream()
			.filter(c -> c != coin && c.hittable() && Hitscan.clear(level, player, coin.position, c.position))
			.min(Comparator.comparingDouble(c -> c.position.distanceToSqr(coin.position)))
			.orElse(null);
		if (next != null) {
			next.hit(coin.power + 1, coin.chainHits);
			this.effects.beam(coin.position, next.position, Effects.BeamStyle.COIN);
			return;
		}

		LivingEntity target = coinTarget(coin, level, player);
		if (target != null) {
			Vec3 head = target.getEyePosition();
			coin.chainHits.add(target.getId());
			damage(level, target, coin.power * 2, true, head);
			this.effects.beam(coin.position, head, Effects.BeamStyle.COIN);
			return;
		}

		Vec3 direction = new Vec3(this.random.nextGaussian(), this.random.nextGaussian(), this.random.nextGaussian()).normalize();
		Vec3 end = coin.position.add(direction.scale(30));
		HitResult hit = level.clip(new ClipContext(coin.position, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		this.effects.beam(coin.position, hit.getLocation(), Effects.BeamStyle.COIN);
	}

	/** The nearest enemy the coin can see the head of, hostile mobs before anything else. */
	private static @Nullable LivingEntity coinTarget(Coin coin, Level level, LocalPlayer player) {
		AABB area = new AABB(coin.position, coin.position).inflate(COIN_TARGET_RANGE);
		return level.getEntitiesOfClass(LivingEntity.class, area, e -> e != player && e.isAlive() && !e.isSpectator()
				&& !coin.chainHits.contains(e.getId()) && Hitscan.clear(level, player, coin.position, e.getEyePosition()))
			.stream()
			.min(Comparator.<LivingEntity>comparingInt(e -> e instanceof Enemy ? 0 : 1)
				.thenComparingDouble(e -> e.distanceToSqr(coin.position)))
			.orElse(null);
	}

	// ---- Feedbacker ---------------------------------------------------------------------------

	/** Punch.PunchStart: the hit itself goes out on the animation's ActiveStart event. */
	private void punch() {
		this.punchReady = false;
		this.fistCooldown = 0.5;
		this.punchStamina -= 1;
		this.sounds.play("punch_swoosh", 1, 1);
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null) {
			arm.setFloat("PunchRandomizer", this.random.nextFloat());
			arm.setTrigger("Punch");
		}
	}

	private void onPunchResult(PunchResultPayload result) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraMovement movement = UltraController.INSTANCE.movement();
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null) {
			if (result.result() == PunchResultPayload.Result.PARRY) {
				arm.play("Hook", 0.065);
			} else {
				arm.play(arm.getFloat("PunchRandomizer") < 0.5F ? "Jab" : "Jab2", 0.075);
			}
		}
		if (result.result() == PunchResultPayload.Result.PARRY) {
			this.sounds.play("parry", 1, 1);
			this.sounds.play("parry_ricochet", 1, 1);
			this.hitstopLeft = HITSTOP;
			this.quietHealLeft = 0.5;
			ScreenFlashes.INSTANCE.parry();
			if (movement != null) {
				movement.shake(0.5);
			}
		} else {
			this.sounds.play("punch_hit", 1, 1);
			if (minecraft.level != null) {
				this.effects.blood(minecraft.level, result.position(), Gore.BODY);
			}
		}
	}

	// ---- health ---------------------------------------------------------------------------------

	/** NewMovement.GetHealth's sound, when blood heals the player. */
	private void healFeedback(LocalPlayer player) {
		float health = player.getHealth();
		if (this.lastHealth >= 0 && health > this.lastHealth + 1.0E-3F && this.quietHealLeft <= 0) {
			this.sounds.play("heal", 1, 1);
		}
		this.lastHealth = health;
	}

	public void render(LevelRenderContext context) {
		this.effects.render(context, this.coins);
	}
}
