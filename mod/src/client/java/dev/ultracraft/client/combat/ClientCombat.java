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
import dev.ultracraft.combat.FistType;
import dev.ultracraft.combat.Gore;
import dev.ultracraft.net.CycleVariantPayload;
import dev.ultracraft.net.ExplosionHitPayload;
import dev.ultracraft.net.ExplosionHurtPayload;
import dev.ultracraft.net.HitstopPayload;
import dev.ultracraft.net.PunchPayload;
import dev.ultracraft.net.PunchResultPayload;
import dev.ultracraft.net.WeaponHitPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The client's side of ULTRAKILL combat: weapon input, the revolver's shots and coins, the shotgun's pellets, the
 * arms' punches (and boosting pellets with them) and the Knuckleblaster's blast, explosions, weapon charges
 * (WeaponCharges) and the hitstops after a parry or a punch. Hits go to the server, which applies them.
 */
public final class ClientCombat implements Revolver.Actions, Shotgun.Actions, Pellet.Hits, FirstPersonWeapons.Events, Explosion.Hits {
	public static final ClientCombat INSTANCE = new ClientCombat();
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	private static final double RANGE = 256;
	private static final double COIN_TARGET_RANGE = 64;
	/** TimeController.TrueStop after a parry. */
	private static final double PARRY_HITSTOP = 0.25;
	/** TimeController.HitStop after a punch lands. */
	private static final double PUNCH_HITSTOP = 0.1;
	/** A split shot goes on from its coin the second time this long after the first (ReflectRevolver). */
	private static final double SPLIT_DELAY = 0.05;
	/** Punch.ActiveFrame looks this far ahead for coins. */
	private static final double PUNCH_REACH = 4 * S;
	/** The punch checks for coins on ActiveStart and the six physics steps after it. */
	private static final double PUNCH_ACTIVE_TIME = 6 * 0.008;
	/** The Knuckleblaster's blast goes off this far ahead of the camera, or just short of a wall (Punch.BlastCheck). */
	private static final double BLAST_DISTANCE = 2 * S;
	/** Landed shells stay until there are more than this many (GoreZone's maxGibs at the default maxGore). */
	private static final int MAX_SHELLS = 150;
	/** Shotgun.Shoot: pellets per shot, and how far each is turned about every axis, in degrees. */
	private static final int PELLETS = 12;
	private static final double PELLET_SPREAD = 10;
	/** A pellet's damage (its Projectile damage of 1, quartered for the player's projectiles). */
	private static final float PELLET_DAMAGE = 0.25F;
	/** The shotgun's point-blank zone: enemies within this many units of the aim take this much. */
	private static final double POINT_BLANK_REACH = 4 * S;
	private static final float POINT_BLANK_DAMAGE = 4;
	/** The Projectile Parry Zone in front of the punch: 3 by 3 by 6 units, its centre 3 units ahead. */
	private static final double PARRY_ZONE_HALF_WIDTH = 1.5 * S;
	private static final double PARRY_ZONE_HALF_LENGTH = 3 * S;
	/** The punch's origin (FistControl's "Punch") is this far below the camera. */
	private static final double PUNCH_DROP = 0.225 * S;

	private final UltraSounds sounds = new UltraSounds();
	private final Effects effects = new Effects();
	private final Map<Revolver.Variant, Revolver> revolvers = new EnumMap<>(Revolver.Variant.class);
	private final List<Coin> coins = new ArrayList<>();
	private final List<Explosion> explosions = new ArrayList<>();
	private final List<Shell> shells = new ArrayList<>();
	private final List<Pellet> pellets = new ArrayList<>();
	private final Revolver.Input input = new Revolver.Input();
	private final RandomSource random = RandomSource.create();
	private final Map<Shotgun.Variant, Shotgun> shotguns = new EnumMap<>(Shotgun.Variant.class);
	private FirstPersonWeapons.@Nullable Weapon held;
	private boolean wasFire2;
	private double coinCharge = 400;
	private int readyCoins = 4;
	private double sharpshooterCharge = 300;
	private int readyShots = 3;
	private final List<Ricochet> ricochets = new ArrayList<>();
	/** Coins punched this frame pop back up as new ones. */
	private final List<Coin> respawned = new ArrayList<>();
	private boolean alreadyHitCoin;
	private double punchActiveLeft;
	/** FistControl's current arm. */
	private FistType arm = FistType.STANDARD;
	private double punchStamina = 2;
	/** Punch.ready: cleared by a punch, set again by its animation's ReadyToPunch event. */
	private boolean punchReady = true;
	private double fistCooldown;
	private double hitstopLeft;
	/** Whether the hitstop is a parry's, which stops the world's sounds. */
	private boolean soundsPaused;
	private float lastHealth = -1;
	private double quietHealLeft;

	private ClientCombat() {
		for (Shotgun.Variant variant : Shotgun.Variant.values()) {
			this.shotguns.put(variant, new Shotgun(variant));
		}
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
			this.coins.forEach(Coin::remove);
			this.coins.clear();
			this.explosions.clear();
			this.shells.clear();
			this.pellets.clear();
			return;
		}
		ScreenFlashes.INSTANCE.update(dt, this.hitstopLeft > 0);
		if (this.hitstopLeft > 0) {
			this.hitstopLeft -= dt;
			if (this.hitstopLeft <= 0 && this.soundsPaused) {
				this.soundsPaused = false;
				minecraft.getSoundManager().resume();
			}
			updateViewmodels(0);
			return;
		}
		this.effects.update(dt, minecraft.level, player);
		this.quietHealLeft -= dt;
		for (Revolver revolver : this.revolvers.values()) {
			revolver.recharge(dt);
		}
		this.coinCharge = Math.min(this.coinCharge + 25 * dt, 400);
		int coins = (int)(this.coinCharge / 100);
		if (coins > this.readyCoins && this.held == FirstPersonWeapons.Weapon.REVOLVER_MARKSMAN) {
			this.sounds.play("coin_ready", 1, 1 + (coins - 1) / 2F);
		}
		this.readyCoins = coins;
		this.sharpshooterCharge = Math.min(this.sharpshooterCharge + 15 * dt, 300);
		int shots = (int)(this.sharpshooterCharge / 100);
		if (shots > this.readyShots && this.held == FirstPersonWeapons.Weapon.REVOLVER_SHARPSHOOTER) {
			this.sounds.play("sharpshooter_ready", 1, 1 + (shots - 1) / 2F);
		}
		this.readyShots = shots;
		updateRicochets(dt, minecraft.level, player);
		this.punchStamina = Math.min(this.punchStamina + 1.25 * dt, 2);
		this.fistCooldown = Math.max(this.fistCooldown - 2 * dt, 0);
		if (this.punchActiveLeft > 0) {
			this.punchActiveLeft -= dt;
			punchCoin(minecraft.level, player);
		}
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
		this.coins.addAll(this.respawned);
		this.respawned.clear();
		for (Pellet pellet : List.copyOf(this.pellets)) {
			pellet.update(dt, minecraft.level, player, this);
		}
		this.pellets.removeIf(pellet -> pellet.removed);
		for (Explosion explosion : List.copyOf(this.explosions)) {
			explosion.update(dt, minecraft.level, player, this);
		}
		this.explosions.removeIf(explosion -> explosion.removed);
		for (Shell shell : this.shells) {
			shell.update(dt, minecraft.level, player, this.sounds);
		}
		this.shells.removeIf(shell -> shell.removed);
		while (this.shells.size() > MAX_SHELLS) {
			this.shells.removeFirst();
		}

		boolean driving = UltraController.INSTANCE.isDriving(player);
		updateHeld(driving ? weaponOf(player.getMainHandItem()) : null);
		if (driving) {
			healFeedback(player);
			sampleInput(minecraft.options);
			// FistControl.Update: each arm's punch key switches to that arm and punches with it.
			boolean feedbacker = consumeClicks(UltraKeys.PUNCH);
			boolean knuckleblaster = consumeClicks(UltraKeys.PUNCH_KNUCKLEBLASTER);
			if (this.fistCooldown <= 0 && this.punchStamina >= 1) {
				if (feedbacker) {
					punchWith(FistType.STANDARD);
				}
				if (knuckleblaster) {
					punchWith(FistType.HEAVY);
				}
			}
			Revolver revolver = revolver(this.held);
			if (revolver != null) {
				revolver.update(dt, this.input, this);
			}
			Shotgun shotgun = shotgun(this.held);
			if (shotgun != null) {
				shotgun.update(dt, this.input, this);
			}
		} else {
			this.lastHealth = -1;
		}
		for (Shotgun shotgun : this.shotguns.values()) {
			shotgun.cool(dt);
		}
		// Animators run after gameplay code, as Unity's do, so what it set this frame plays this frame.
		updateViewmodels(dt);
	}

	/**
	 * Runs the viewmodels' Animators. During a hitstop they still run, with no time passing, as Unity's do at a
	 * time scale of 0: an animation started by what caused the hitstop shows on the frozen frame.
	 */
	private void updateViewmodels(double dt) {
		Revolver revolver = revolver(this.held);
		FirstPersonWeapons.RevolverState display = revolver == null
			? FirstPersonWeapons.RevolverState.NONE
			: revolver.display(revolver.variant == Revolver.Variant.SHARPSHOOTER ? this.sharpshooterCharge : this.coinCharge);
		Shotgun shotgun = shotgun(this.held);
		FirstPersonWeapons.ShotgunState shotgunDisplay = shotgun == null
			? FirstPersonWeapons.ShotgunState.NONE
			: new FirstPersonWeapons.ShotgunState(shotgun.heat());
		FirstPersonWeapons.INSTANCE.update(dt, this.held, display, shotgunDisplay, this);
	}

	private void updateHeld(FirstPersonWeapons.@Nullable Weapon weapon) {
		if (weapon == this.held) {
			return;
		}
		Revolver revolver = revolver(this.held);
		if (revolver != null) {
			revolver.holster();
		}
		Shotgun shotgun = shotgun(this.held);
		if (shotgun != null) {
			shotgun.holster();
		}
		this.held = weapon;
		this.wasFire2 = false;
		if (weapon != null) {
			revolver = revolver(weapon);
			if (revolver != null) {
				revolver.equip();
			}
			shotgun = shotgun(weapon);
			if (shotgun != null) {
				shotgun.equip();
			}
			FirstPersonWeapons.INSTANCE.equip(weapon);
		}
	}

	@Override
	public void weaponEvent(FirstPersonWeapons.Weapon weapon, String function) {
		Revolver revolver = revolver(weapon);
		Shotgun shotgun = shotgun(weapon);
		switch (function) {
			case "ReadyGun" -> {
				if (revolver != null) {
					revolver.readyGun();
				}
				if (shotgun != null) {
					shotgun.readyGun();
				}
			}
			// Shotgun.ReleaseHeat: the heat sink cools with a hiss.
			case "ReleaseHeat" -> {
				if (shotgun != null) {
					shotgun.releaseHeat();
					this.sounds.play("shotgun_heat_release", 1, 1);
				}
			}
			case "ClickSound" -> this.sounds.play("shotgun_click", 0.5F, this.sounds.randomPitch(0.95F, 1.05F));
			default -> {
			}
		}
	}

	private @Nullable Revolver revolver(FirstPersonWeapons.@Nullable Weapon weapon) {
		if (weapon == null) {
			return null;
		}
		return switch (weapon) {
			case REVOLVER_PIERCER -> this.revolvers.get(Revolver.Variant.PIERCER);
			case REVOLVER_MARKSMAN -> this.revolvers.get(Revolver.Variant.MARKSMAN);
			case REVOLVER_SHARPSHOOTER -> this.revolvers.get(Revolver.Variant.SHARPSHOOTER);
			default -> null;
		};
	}

	private @Nullable Shotgun shotgun(FirstPersonWeapons.@Nullable Weapon weapon) {
		return weapon == FirstPersonWeapons.Weapon.SHOTGUN_CORE_EJECT ? this.shotguns.get(Shotgun.Variant.CORE_EJECT) : null;
	}

	@Override
	public void armEvent(String function) {
		switch (function) {
			case "ActiveStart" -> {
				Minecraft minecraft = Minecraft.getInstance();
				// A boosted pellet takes the punch: it hits nothing else.
				if (this.arm != FistType.STANDARD || minecraft.player == null || !boostPellet(minecraft.player)) {
					ClientPlayNetworking.send(new PunchPayload(this.arm));
				}
				if (this.arm == FistType.STANDARD && minecraft.level != null && minecraft.player != null) {
					this.punchActiveLeft = PUNCH_ACTIVE_TIME;
					punchCoin(minecraft.level, minecraft.player);
				}
			}
			case "ReadyToPunch" -> this.punchReady = true;
			case "BlastCheck" -> blastCheck();
			case "Eject" -> eject();
			default -> {
			}
		}
	}

	@Override
	public void cylinderClicked() {
		this.sounds.play("revolver_click", 1, 1);
	}

	private static FirstPersonWeapons.@Nullable Weapon viewmodel(Revolver.@Nullable Variant variant) {
		if (variant == null) {
			return null;
		}
		return switch (variant) {
			case PIERCER -> FirstPersonWeapons.Weapon.REVOLVER_PIERCER;
			case MARKSMAN -> FirstPersonWeapons.Weapon.REVOLVER_MARKSMAN;
			case SHARPSHOOTER -> FirstPersonWeapons.Weapon.REVOLVER_SHARPSHOOTER;
		};
	}

	private static FirstPersonWeapons.@Nullable Weapon weaponOf(ItemStack stack) {
		if (stack.is(UltraItems.REVOLVER_PIERCER)) {
			return FirstPersonWeapons.Weapon.REVOLVER_PIERCER;
		}
		if (stack.is(UltraItems.REVOLVER_MARKSMAN)) {
			return FirstPersonWeapons.Weapon.REVOLVER_MARKSMAN;
		}
		if (stack.is(UltraItems.REVOLVER_SHARPSHOOTER)) {
			return FirstPersonWeapons.Weapon.REVOLVER_SHARPSHOOTER;
		}
		return stack.is(UltraItems.SHOTGUN_CORE_EJECT) ? FirstPersonWeapons.Weapon.SHOTGUN_CORE_EJECT : null;
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

	private static boolean consumeClicks(KeyMapping key) {
		boolean pressed = false;
		while (key.consumeClick()) {
			pressed = true;
		}
		return pressed;
	}

	// ---- revolver ---------------------------------------------------------------------------

	@Override
	public void shoot(Revolver.Variant variant, boolean charged, int ricochets) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || minecraft.level == null || movement == null) {
			return;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();

		// Revolver.Shoot: a normal shot plays a gunshot; a charged one its super shot sound (the sharpshooter's twirl shot).
		if (!charged) {
			this.sounds.play("revolver_shoot." + variant.name().toLowerCase(), 0.55F, this.sounds.randomPitch());
		} else if (variant == Revolver.Variant.SHARPSHOOTER) {
			this.sounds.play("revolver_twirl_shot", 1, 1);
		} else {
			this.sounds.play("revolver_super", 1, 1);
		}
		movement.kickFov(charged ? 1 / 20.0 : 1 / 40.0);
		Animator animator = FirstPersonWeapons.INSTANCE.animator(viewmodel(variant));
		if (animator != null) {
			animator.setFloat("RandomChance", this.random.nextFloat());
			animator.setTrigger(charged ? "ChargeShoot" : "Shoot");
		}
		FirstPersonWeapons.INSTANCE.turnCylinder(viewmodel(variant));

		if (charged && variant == Revolver.Variant.SHARPSHOOTER) {
			sharpshooterBeam(minecraft.level, player, eye, look, muzzle(eye, look, player), ricochets);
			return;
		}
		List<Hitscan.Hit> hits = Hitscan.cast(minecraft.level, player, eye, look, RANGE, this.coins);
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
				hit.coin().hit(hit.coin().power, new HashSet<>(), charged ? Effects.BeamStyle.SUPER : Effects.BeamStyle.COIN);
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

	/**
	 * The sharpshooter's charged beam ("Revolver Beam Sharp"): it pierces everything in its way, and while it has
	 * ricochets left, a wall sends it on 0.1 s later, reflected and aimed at the nearest enemy near its path.
	 */
	private void sharpshooterBeam(Level level, LocalPlayer player, Vec3 from, Vec3 direction, Vec3 drawFrom, int ricochets) {
		List<Hitscan.Hit> hits = Hitscan.cast(level, player, from, direction, RANGE, this.coins);
		Vec3 end = hits.getLast().position();
		for (Hitscan.Hit hit : hits) {
			if (hit.kind() == Hitscan.Kind.ENTITY) {
				damage(level, hit.entity(), hit.head() ? 2 : 1, hit.head(), hit.position());
				UltraMovement movement = UltraController.INSTANCE.movement();
				if (movement != null) {
					movement.shake(1);
				}
			} else if (hit.kind() == Hitscan.Kind.COIN) {
				hit.coin().hit(hit.coin().power, new HashSet<>(), Effects.BeamStyle.SHARP);
				end = hit.position();
				break;
			} else {
				end = hit.position();
				if (hit.kind() == Hitscan.Kind.BLOCK && hit.face() != null) {
					this.effects.impact(level, end);
					if (ricochets > 0) {
						Vec3 normal = hit.face().getUnitVec3();
						Vec3 reflected = direction.subtract(normal.scale(2 * direction.dot(normal)));
						this.ricochets.add(new Ricochet(end.add(normal.scale(0.01)), reflected, ricochets - 1));
					}
				}
				break;
			}
		}
		this.effects.beam(drawFrom, end, Effects.BeamStyle.SHARP);
	}

	/** A sharpshooter beam about to come off a wall. */
	private static final class Ricochet {
		final Vec3 from;
		final Vec3 direction;
		final int ricochetsLeft;
		double delay = 0.1;

		Ricochet(Vec3 from, Vec3 direction, int ricochetsLeft) {
			this.from = from;
			this.direction = direction;
			this.ricochetsLeft = ricochetsLeft;
		}
	}

	private void updateRicochets(double dt, Level level, LocalPlayer player) {
		List<Ricochet> due = new ArrayList<>();
		for (Ricochet ricochet : this.ricochets) {
			ricochet.delay -= dt;
			if (ricochet.delay <= 0) {
				due.add(ricochet);
			}
		}
		this.ricochets.removeAll(due);
		for (Ricochet ricochet : due) {
			this.sounds.play("beam_ricochet.sharpshooter", 1, 1);
			Vec3 direction = aimAssist(level, player, ricochet.from, ricochet.direction);
			sharpshooterBeam(level, player, ricochet.from, direction, ricochet.from, ricochet.ricochetsLeft);
		}
	}

	/**
	 * RevolverBeam.RicochetAimAssist: sweeps a 5 unit sphere along the beam and turns it towards the nearest thing
	 * the sweep touches that it can see, coins before enemies, and an enemy's head when that's in view.
	 */
	private Vec3 aimAssist(Level level, LocalPlayer player, Vec3 from, Vec3 direction) {
		double radius = 5 * S;
		Vec3 target = null;
		double nearest = Double.MAX_VALUE;
		boolean coin = false;
		for (Coin c : this.coins) {
			if (c.removed || c.shot) {
				continue;
			}
			double along = c.position.subtract(from).dot(direction);
			double off = c.position.distanceTo(from.add(direction.scale(Math.max(along, 0))));
			double reach = radius + Coin.HIT_RADIUS;
			if (along < 0 || off > reach) {
				continue;
			}
			double distance = along - Math.sqrt(reach * reach - off * off);
			if ((!coin || distance < nearest) && Hitscan.clear(level, player, from, c.position)) {
				coin = true;
				nearest = distance;
				target = c.position;
			}
		}
		if (!coin) {
			Vec3 far = from.add(direction.scale(RANGE));
			AABB area = new AABB(from, far).inflate(radius);
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, e -> e != player && e.isAlive() && !e.isSpectator())) {
				AABB box = entity.getBoundingBox();
				Optional<Vec3> entry = box.inflate(radius).clip(from, far);
				if (entry.isEmpty()) {
					continue;
				}
				double distance = entry.get().distanceTo(from);
				if (distance < 0.1 * S || distance >= nearest) {
					continue;
				}
				Vec3 along = from.add(direction.scale(distance));
				Vec3 point = new Vec3(Math.clamp(along.x, box.minX, box.maxX), Math.clamp(along.y, box.minY, box.maxY), Math.clamp(along.z, box.minZ, box.maxZ));
				if (!Hitscan.clear(level, player, from, point)) {
					continue;
				}
				nearest = distance;
				Vec3 head = entity.getEyePosition();
				target = Hitscan.clear(level, player, from, head) ? head : point;
			}
		}
		return target == null ? direction : target.subtract(from).normalize();
	}

	@Override
	public double sharpshooterCharge() {
		return this.sharpshooterCharge;
	}

	@Override
	public void spendSharpshooterCharge() {
		this.sharpshooterCharge -= 100;
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
		this.coins.add(new Coin(movement.eyePosition().add(0, -0.5 * S, 0), velocity, player.getYRot(), player.getXRot(), this.sounds));
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

	/**
	 * Coin.ReflectRevolver: the shot goes on to the nearest coin, else the nearest enemy's head. A coin that's
	 * flashing or charged splits the shot: it goes on a second time, 0.05 s later, to another target. That's any
	 * shot the coin passes on with its own beam, and the piercer's charged shot; the sharpshooter's doesn't split.
	 */
	private void reflect(Coin coin, Level level, LocalPlayer player) {
		boolean split = coin.hitTimes > 1 && (coin.beam == Effects.BeamStyle.COIN || coin.beam == Effects.BeamStyle.SUPER);
		passOn(coin, level, player);
		coin.hitTimes--;
		if (split && coin.hitTimes > 0) {
			coin.reflectTimer = SPLIT_DELAY;
		} else {
			coin.remove();
		}
	}

	private void passOn(Coin coin, Level level, LocalPlayer player) {
		float pitch = coin.power > 2 ? 1 + (coin.power - 2) / 5 : 1;
		this.sounds.play("coin_hit", 1, pitch);
		this.sounds.play("coin_hit_flip", 1, 1);

		Coin next = this.coins.stream()
			.filter(c -> c != coin && c.hittable() && Hitscan.clear(level, player, coin.position, c.position))
			.min(Comparator.comparingDouble(c -> c.position.distanceToSqr(coin.position)))
			.orElse(null);
		if (next != null) {
			next.hit(coin.power + 1, coin.chainHits, coin.beam);
			this.effects.beam(coin.position, next.position, coin.beam);
			return;
		}

		LivingEntity target = coinTarget(coin, level, player);
		if (target != null) {
			Vec3 head = target.getEyePosition();
			coin.chainHits.add(target.getId());
			damage(level, target, coin.power * 2, true, head);
			this.effects.beam(coin.position, head, coin.beam);
			return;
		}

		Vec3 direction = new Vec3(this.random.nextGaussian(), this.random.nextGaussian(), this.random.nextGaussian()).normalize();
		Vec3 end = coin.position.add(direction.scale(30));
		HitResult hit = level.clip(new ClipContext(coin.position, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		this.effects.beam(coin.position, hit.getLocation(), coin.beam);
	}

	/**
	 * Punch.ActiveFrame's coin check: what's straight ahead within 4 units (a ray, then a 1 unit sphere), if it's
	 * a coin past its flash, gets punched.
	 */
	private void punchCoin(Level level, LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (this.alreadyHitCoin || movement == null) {
			return;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		double reach = PUNCH_REACH;
		Coin coin = null;
		Hitscan.Hit first = Hitscan.cast(level, player, eye, look, reach, this.coins).getFirst();
		boolean rayHit = first.kind() == Hitscan.Kind.COIN || first.kind() == Hitscan.Kind.ENTITY;
		if (first.kind() == Hitscan.Kind.COIN) {
			coin = first.coin();
		}
		if (!rayHit) {
			double nearest = Double.MAX_VALUE;
			for (Coin c : this.coins) {
				double along = c.position.subtract(eye).dot(look);
				double off = c.position.distanceTo(eye.add(look.scale(along)));
				double radius = Coin.HIT_RADIUS + 1 * S;
				if (along >= 0 && along <= reach && off <= radius && c.hittable()) {
					double distance = along - Math.sqrt(radius * radius - off * off);
					if (distance < nearest) {
						nearest = distance;
						coin = c;
					}
				}
			}
		}
		if (coin == null || !coin.doubled || !coin.hittable()) {
			return;
		}
		this.alreadyHitCoin = true;
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null) {
			arm.play("Hook", 0.065);
		}
		punchflection(coin, level, player, eye, look);
	}

	/**
	 * Coin.Punchflection: the coin strikes the nearest enemy it can see (in the head), or flies along the aim to
	 * the wall. A new coin with one more power pops up where it landed.
	 */
	private void punchflection(Coin coin, Level level, LocalPlayer player, Vec3 eye, Vec3 look) {
		coin.punched();
		float pitch = coin.power > 2 ? 1 + (coin.power - 2) / 5 : 1;
		this.sounds.play("coin_hit", 1, pitch);
		this.sounds.play("coin_hit_flip", 1, pitch);
		LivingEntity target = coinTarget(coin, level, player);
		Vec3 landing = null;
		float power = coin.power;
		if (target != null) {
			Vec3 head = target.getEyePosition();
			damage(level, target, coin.power * 2, true, head);
			this.effects.beam(coin.position, head, Effects.BeamStyle.COIN);
			landing = head;
			power++;
		} else {
			HitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(RANGE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			this.effects.beam(coin.position, hit.getLocation(), Effects.BeamStyle.COIN);
			if (hit.getType() != HitResult.Type.MISS) {
				landing = hit.getLocation().subtract(look.scale(S));
				if (power < 5) {
					power++;
				}
			}
		}
		coin.remove();
		if (landing != null) {
			this.respawned.add(coin.respawn(landing, power));
		}
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

	// ---- arms ----------------------------------------------------------------------------------

	/** FistControl.ForceArm, then Punch.PunchStart. */
	private void punchWith(FistType type) {
		if (type != this.arm) {
			changeArm(type);
		}
		punchStart();
	}

	/**
	 * FistControl.ArmChange: the other arm takes over, ready to punch (Punch.OnEnable), with at most a tenth of a
	 * second of cooldown left.
	 */
	private void changeArm(FistType type) {
		this.arm = type;
		this.punchReady = true;
		this.punchActiveLeft = 0;
		this.fistCooldown = Math.min(this.fistCooldown, 0.1);
		FirstPersonWeapons.INSTANCE.changeArm(type);
	}

	/** Punch.PunchStart: the hit itself goes out on the animation's ActiveStart event. */
	private void punchStart() {
		if (!this.punchReady) {
			return;
		}
		this.punchReady = false;
		this.alreadyHitCoin = false;
		this.fistCooldown = this.arm.cooldownCost * 0.25;
		this.punchStamina -= this.arm.cooldownCost / 2;
		this.sounds.play("punch_swoosh." + this.arm.arm, 1, this.sounds.randomPitch());
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null) {
			arm.setFloat("PunchRandomizer", this.random.nextFloat());
			arm.setTrigger("Punch");
		}
	}

	/**
	 * Punch.BlastCheck: if the Knuckleblaster's punch is still held halfway through, the arm fires its blast,
	 * an explosion that throws enemies, projectiles and loose things the way the player is looking.
	 */
	private void blastCheck() {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (this.arm != FistType.HEAVY || !UltraKeys.PUNCH_KNUCKLEBLASTER.isDown() || player == null || minecraft.level == null || movement == null || arm == null) {
			return;
		}
		arm.setTrigger("PunchBlast");
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 position = eye.add(look.scale(BLAST_DISTANCE));
		HitResult wall = minecraft.level.clip(new ClipContext(eye, position, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (wall.getType() != HitResult.Type.MISS) {
			position = wall.getLocation().subtract(look.scale(0.1 * S));
		}
		Quaternionf rotation = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
		Explosion explosion = new Explosion(Explosion.KNUCKLEBLASTER, position, rotation, look);
		this.explosions.add(explosion);
		this.effects.wave(explosion);
		movement.shake(explosion.cameraShake(eye));
		// The blast's RandomPitch: up to a quarter either way.
		this.sounds.play("explosion_wave", 1, this.sounds.randomPitch(0.75F, 1.25F));
	}

	/**
	 * Punch.Eject: the Knuckleblaster's reload throws out two shells from its ejector, side by side, each turned
	 * up to 45 degrees about its length and tossed forwards and up.
	 */
	private void eject() {
		Minecraft minecraft = Minecraft.getInstance();
		FirstPersonWeapons.Frame ejector = FirstPersonWeapons.INSTANCE.armBone("ShellEjector");
		if (ejector == null || minecraft.player == null) {
			return;
		}
		this.sounds.play("fist_reload", 1, 1);
		Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
		Vec3 cameraPosition = minecraft.gameRenderer.mainCamera().position();
		Vec3 position = world(camera, ejector.position()).scale(S).add(cameraPosition);
		Vec3 right = world(camera, ejector.right());
		Vec3 up = world(camera, ejector.up());
		Vec3 forward = world(camera, ejector.forward());
		for (int i = 0; i < 2; i++) {
			Vec3 at = position.add(right.scale((i == 0 ? 0.075 : -0.075) * S));
			// Unity's Random.Range(-45, 45) and Random.Range(8, 12) on integers leave out the top.
			float roll = (float)Math.toRadians(this.random.nextIntBetweenInclusive(-45, 44));
			Matrix3f axes = new Matrix3f(ejector.right(), ejector.up(), ejector.forward()).rotateZ(roll);
			// The model is drawn mirrored into Minecraft's axes, so its rotation is the ejector's mirrored back.
			Quaternionf rotation = new Quaternionf(camera).mul(axes.mul(new Matrix3f().scaling(1, 1, -1)).getNormalizedRotation(new Quaternionf()));
			Vec3 velocity = forward.scale(1 / 1.75).add(up.scale(0.5)).add(0, 1 / 1.75, 0).scale(this.random.nextIntBetweenInclusive(8, 11));
			this.shells.add(new Shell(at, velocity, rotation));
		}
	}

	private static Vec3 world(Quaternionf camera, Vector3f cameraSpace) {
		Vector3f v = camera.transform(new Vector3f(cameraSpace));
		return new Vec3(v.x, v.y, v.z);
	}

	// ---- explosions -----------------------------------------------------------------------------

	@Override
	public void enemy(LivingEntity enemy, float damage, Vec3 push) {
		ClientPlayNetworking.send(new ExplosionHitPayload(enemy.getId(), damage, push));
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && damage > 0) {
			Gore gore = Gore.of(false, damage, false);
			this.effects.blood(minecraft.level, enemy.getBoundingBox().getCenter(), gore);
			this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
		}
	}

	@Override
	public void projectile(Projectile projectile, Vec3 push) {
		ClientPlayNetworking.send(new ExplosionHitPayload(projectile.getId(), 0, push));
	}

	@Override
	public void object(Entity object, Vec3 velocity) {
		ClientPlayNetworking.send(new ExplosionHitPayload(object.getId(), 0, velocity));
	}

	@Override
	public void player(Explosion explosion) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement == null || movement.exploded()) {
			return;
		}
		Explosion.Settings settings = explosion.settings;
		movement.explosionLaunch(explosion.origin, settings.pushForceMultiplier(), settings.maxSize(), settings.rocketExplosion(), explosion.boosted, explosion.harmful());
		if (explosion.harmful()) {
			movement.hurtByExplosion(explosion.playerDamage());
			ClientPlayNetworking.send(new ExplosionHurtPayload(explosion.playerDamage()));
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
				FirstPersonWeapons.INSTANCE.punchSuccess(result.position());
			}
		}
		if (result.result() == PunchResultPayload.Result.PARRY) {
			// NewMovement.Parry: the flash, a full heal and full stamina.
			parryFlash();
			this.quietHealLeft = 0.5;
			if (movement != null) {
				movement.fullStamina();
			}
		} else {
			// Punch.PunchSuccess
			this.sounds.play("punch_hit." + this.arm.arm, 1, 1);
			this.hitstopLeft = Math.max(this.hitstopLeft, PUNCH_HITSTOP);
			if (movement != null) {
				movement.shake(0.5 * this.arm.screenShakeMultiplier);
			}
			if (minecraft.level != null) {
				this.effects.blood(minecraft.level, result.position(), result.gore());
			}
			this.sounds.play("gore." + result.gore().name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
		}
	}

	/** TimeController.ParryFlash: the parry sounds, the white flash, a quarter-second stop of the world and a camera shake. */
	private void parryFlash() {
		this.sounds.play("parry", 1, 1);
		this.sounds.play("parry_ricochet", 1, 1);
		this.hitstopLeft = Math.max(this.hitstopLeft, PARRY_HITSTOP);
		// TimeController.TrueStop stops the world's sounds and music; the player's own play on.
		Minecraft.getInstance().getSoundManager().pauseAllExcept(SoundSource.PLAYERS);
		this.soundsPaused = true;
		ScreenFlashes.INSTANCE.parry();
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			movement.shake(0.5);
		}
	}

	// ---- shotgun --------------------------------------------------------------------------------

	/**
	 * Shotgun.Shoot: twelve pellets out of the camera, each turned up to 10 degrees about every axis; the
	 * point-blank zone; a small push forwards; the shot sound, a camera shake and a muzzle flash.
	 */
	@Override
	public void shootShotgun(Shotgun.Variant variant) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || minecraft.level == null || movement == null) {
			return;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0, look.z);
		if (flat.lengthSqr() > 0) {
			movement.addVelocity(flat.normalize());
		}
		pointBlank(minecraft.level, player, eye, look);
		Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
		for (int i = 0; i < PELLETS; i++) {
			// Transform.Rotate by up to the spread about each axis; turning about its own length changes nothing.
			double pitch = Math.toRadians(spread(PELLET_SPREAD));
			double yaw = Math.toRadians(spread(PELLET_SPREAD));
			Vector3f direction = camera.transform(new Vector3f(
				(float)(Math.cos(pitch) * Math.sin(yaw)), (float)-Math.sin(pitch), (float)(-Math.cos(pitch) * Math.cos(yaw))
			));
			this.pellets.add(new Pellet(eye, new Vec3(direction.x, direction.y, direction.z), Pellet.SPEED + spread(Pellet.SPEED_RANDOMIZER)));
		}
		this.sounds.play("shotgun_shoot", 0.45F, this.sounds.randomPitch(0.95F, 1.05F));
		movement.stopShake();
		movement.shake(1);
		FirstPersonWeapons.Weapon weapon = FirstPersonWeapons.Weapon.SHOTGUN_CORE_EJECT;
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon);
		if (animator != null) {
			animator.setTrigger("Fire");
		}
		FirstPersonWeapons.Frame barrel = FirstPersonWeapons.INSTANCE.weaponBone(weapon, "ShootPoint L");
		if (barrel != null) {
			Vec3 cameraPosition = minecraft.gameRenderer.mainCamera().position();
			Vec3 at = world(camera, barrel.position()).scale(S).add(cameraPosition);
			this.effects.muzzleFlash(at, new Quaternionf(camera).mul(mirrored(barrel)));
		}
	}

	private double spread(double range) {
		return (this.random.nextDouble() * 2 - 1) * range;
	}

	/** A viewmodel bone's rotation for drawing a model or sprite on it, which are drawn mirrored into Minecraft's axes. */
	private static Quaternionf mirrored(FirstPersonWeapons.Frame frame) {
		return new Matrix3f(frame.right(), frame.up(), frame.forward()).mul(new Matrix3f().scaling(1, 1, -1)).getNormalizedRotation(new Quaternionf());
	}

	/**
	 * The shotgun's point-blank zone (Shotgun.Shoot): every enemy body the first 4 units of the aim pass
	 * through, walls or not, takes 4 damage.
	 */
	private void pointBlank(Level level, LocalPlayer player, Vec3 eye, Vec3 look) {
		Vec3 end = eye.add(look.scale(POINT_BLANK_REACH));
		for (LivingEntity enemy : level.getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(1), e -> e != player && e.isAlive() && !e.isSpectator())) {
			AABB box = enemy.getBoundingBox();
			Optional<Vec3> entry = box.contains(eye) ? Optional.of(eye) : box.clip(eye, end);
			entry.ifPresent(at -> shotgunHit(level, enemy, POINT_BLANK_DAMAGE, at));
		}
	}

	private void shotgunHit(Level level, LivingEntity enemy, float damage, Vec3 at) {
		ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), damage, false, at));
		Gore gore = Gore.of(false, damage, false);
		this.effects.blood(level, at, gore);
		this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
	}

	@Override
	public void enemy(Pellet pellet, LivingEntity enemy) {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		if (pellet.boosted) {
			explode(pellet.position, pellet.direction(), true);
			return;
		}
		shotgunHit(level, enemy, PELLET_DAMAGE, pellet.position);
		bulletSpark(pellet.position);
	}

	@Override
	public void wall(Pellet pellet) {
		if (pellet.boosted) {
			explode(pellet.position, pellet.direction(), true);
		} else {
			bulletSpark(pellet.position);
		}
	}

	/** A pellet's hit effect (BulletSpark): sparks, and a ricochet. */
	private void bulletSpark(Vec3 at) {
		this.effects.bulletSpark(at);
		this.sounds.play("bullet_spark", 1, 1);
	}

	/**
	 * Punch.ActiveFrame's first frame with the Feedbacker: one of the player's own pellets in reach is boosted.
	 * That's one the camera is inside, else the first along the aim within 4 units (or in a 0.3 unit box swept
	 * that far), else the nearest in the Projectile Parry Zone in front of the punch.
	 *
	 * @return whether a pellet was boosted
	 */
	private boolean boostPellet(LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		Level level = Minecraft.getInstance().level;
		if (movement == null || level == null || this.pellets.isEmpty()) {
			return false;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Pellet pellet = pelletAt(eye);
		if (pellet == null) {
			pellet = pelletAlong(eye, look);
		}
		if (pellet == null) {
			pellet = pelletInParryZone(player, eye, look);
		}
		if (pellet == null) {
			return false;
		}
		pellet.boost(parryLookTarget(level, player, eye, look));
		parryFlash();
		ClientPlayNetworking.send(new HitstopPayload((int)(PARRY_HITSTOP * 1000)));
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null) {
			arm.play("Hook", 0.065);
		}
		return true;
	}

	private @Nullable Pellet pelletAt(Vec3 point) {
		for (Pellet pellet : this.pellets) {
			if (!pellet.removed && pellet.position.distanceTo(point) <= pellet.radius() + 0.01 * S) {
				return pellet;
			}
		}
		return null;
	}

	/** The nearest pellet a ray, or else a 0.3 unit box, swept 4 units along the aim touches. */
	private @Nullable Pellet pelletAlong(Vec3 eye, Vec3 look) {
		Vec3 up = cameraUp();
		Vec3 right = look.cross(up).normalize();
		for (double half : new double[]{0, 0.3 * S}) {
			Pellet nearest = null;
			double best = Double.MAX_VALUE;
			for (Pellet pellet : this.pellets) {
				Vec3 offset = pellet.position.subtract(eye);
				double along = offset.dot(look);
				double reach = half + pellet.radius();
				boolean inside = half == 0
					? offset.subtract(look.scale(along)).length() <= pellet.radius()
					: Math.abs(offset.dot(right)) <= reach && Math.abs(offset.dot(up)) <= reach;
				if (!pellet.removed && along >= -pellet.radius() && along <= PUNCH_REACH + pellet.radius() && inside && along < best) {
					best = along;
					nearest = pellet;
				}
			}
			if (nearest != null) {
				return nearest;
			}
		}
		return null;
	}

	/** ProjectileParryZone.CheckParryZone: the pellet in the zone nearest the punch. */
	private @Nullable Pellet pelletInParryZone(LocalPlayer player, Vec3 eye, Vec3 look) {
		Vec3 up = cameraUp();
		Vec3 right = look.cross(up).normalize();
		Vec3 punch = eye.subtract(up.scale(PUNCH_DROP));
		Vec3 centre = punch.add(look.scale(PARRY_ZONE_HALF_LENGTH));
		Pellet nearest = null;
		double best = 100 * S;
		for (Pellet pellet : this.pellets) {
			Vec3 offset = pellet.position.subtract(centre);
			double r = pellet.radius();
			boolean inZone = Math.abs(offset.dot(look)) <= PARRY_ZONE_HALF_LENGTH + r
				&& Math.abs(offset.dot(right)) <= PARRY_ZONE_HALF_WIDTH + r
				&& Math.abs(offset.dot(up)) <= PARRY_ZONE_HALF_WIDTH + r;
			double distance = pellet.position.distanceTo(punch);
			if (!pellet.removed && inZone && distance < best) {
				best = distance;
				nearest = pellet;
			}
		}
		return nearest;
	}

	private static Vec3 cameraUp() {
		Vector3fc up = Minecraft.getInstance().gameRenderer.mainCamera().upVector();
		return new Vec3(up.x(), up.y(), up.z());
	}

	/** Punch.GetParryLookTarget: the first enemy along the aim, through walls, or far ahead. */
	private static Vec3 parryLookTarget(Level level, LocalPlayer player, Vec3 eye, Vec3 look) {
		Vec3 end = eye.add(look.scale(RANGE));
		Vec3 nearest = null;
		double best = Double.MAX_VALUE;
		for (LivingEntity enemy : level.getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(1), e -> e != player && e.isAlive() && !e.isSpectator())) {
			Optional<Vec3> entry = enemy.getBoundingBox().clip(eye, end);
			if (entry.isPresent() && entry.get().distanceToSqr(eye) < best) {
				best = entry.get().distanceToSqr(eye);
				nearest = entry.get();
			}
		}
		return nearest != null ? nearest : eye.add(look.scale(1000 * S));
	}

	/**
	 * The standard explosion ("Explosion") where something blew up, facing the way it was going: its blast and its
	 * push wave, its sound and its look.
	 */
	private void explode(Vec3 at, @Nullable Vec3 facing, boolean boosted) {
		Quaternionf rotation = facing == null ? new Quaternionf()
			: new Quaternionf().lookAlong((float)facing.x, (float)facing.y, (float)facing.z, 0, 1, 0).conjugate();
		Explosion blast = new Explosion(Explosion.STANDARD, at, rotation, null, boosted);
		Explosion wave = new Explosion(Explosion.STANDARD_WAVE, at, rotation, null, boosted);
		this.explosions.add(blast);
		this.explosions.add(wave);
		this.effects.explosion(blast, wave);
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
			movement.shake(Math.max(blast.cameraShake(camera), wave.cameraShake(camera)));
		}
		// The explosion's RandomPitch: up to a quarter either way.
		this.sounds.play("explosion", 1, this.sounds.randomPitch(0.75F, 1.25F));
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
		this.effects.render(context, this.coins, this.shells, this.pellets);
	}
}
