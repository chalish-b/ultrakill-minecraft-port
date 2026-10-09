package dev.ultracraft.client.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraItems;
import dev.ultracraft.client.UltraController;
import dev.ultracraft.client.UltraKeys;
import dev.ultracraft.client.movement.UltraMovement;
import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import dev.ultracraft.client.render.ScreenFlashes;
import dev.ultracraft.client.render.UltraModel;
import dev.ultracraft.client.render.animation.Animator;
import dev.ultracraft.combat.BlockDamage;
import dev.ultracraft.combat.FistType;
import dev.ultracraft.combat.Gore;
import dev.ultracraft.combat.ServerCombat;
import dev.ultracraft.net.BlockHitPayload;
import dev.ultracraft.net.CannonballHitPayload;
import dev.ultracraft.net.CycleVariantPayload;
import dev.ultracraft.net.ExplosionBlocksPayload;
import dev.ultracraft.net.ExplosionHitPayload;
import dev.ultracraft.net.ExplosionHurtPayload;
import dev.ultracraft.net.HitstopPayload;
import dev.ultracraft.net.MagnetPullPayload;
import dev.ultracraft.net.PunchPayload;
import dev.ultracraft.net.PunchResultPayload;
import dev.ultracraft.net.SlamHitPayload;
import dev.ultracraft.net.WeaponHitPayload;
import dev.ultracraft.net.WhiplashBrokenPayload;
import dev.ultracraft.net.ZapHurtPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The client's side of ULTRAKILL combat: weapon input, the revolver's shots and coins, the shotgun's pellets, the
 * nailgun's nails, magnets and zapper and the electricity between enemies, the railcannon's beams, the rocket launcher's rockets and cannonballs, the arms' punches (and boosting pellets with them) and the
 * Knuckleblaster's blast, the Whiplash, explosions, weapon charges (WeaponCharges) and the hitstops after a parry
 * or a punch. Hits go to the server, which applies them.
 */
public final class ClientCombat implements Revolver.Actions, Shotgun.Actions, Nailgun.Actions, Railcannon.Actions, RocketLauncher.Actions, Pellet.Hits, Core.Hits,
	Rocket.Hits, FirstPersonWeapons.Events, Explosion.Hits, SawZone.Hits, ThrownSaw.Hits, Sawblade.Hits, Shockwave.Hits, Whiplash.Hits, Drill.Hits, Nail.Hits,
	Magnet.Hits, Zapper.Hits, Conductor.Hits {
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
	/** Coins look for cores, rockets and cannonballs this close to the player. */
	private static final double GRENADE_TARGET_RANGE = 100 * S;
	/** TimeController.HitStop when a core or a rocket hits an enemy. */
	private static final double GRENADE_HITSTOP = 0.05;
	/** The Whiplash's hit on the enemy it catches. */
	private static final float WHIPLASH_DAMAGE = 0.2F;
	/** Landed shells stay until there are more than this many (GoreZone's maxGibs at the default maxGore). */
	private static final int MAX_SHELLS = 150;
	/** The piercer's charged shot goes through up to this many blocks, as long as it breaks them. */
	private static final int PIERCER_BLOCKS = 3;
	/** Shotgun.Shoot: pellets per shot, and how far each is turned about every axis, in degrees. */
	private static final int PELLETS = 12;
	private static final double PELLET_SPREAD = 10;
	/**
	 * The Pump Charge's shot by its pumps: its pellets, its spread's multiplier, and the middle of the shot's
	 * pitch. Fully pumped, it's an explosion instead.
	 */
	private static final int[] PUMP_PELLETS = {10, 16, 24, 0};
	private static final double[] PUMP_SPREAD = {1 / 1.5, 1, 2, 0};
	private static final float[] PUMP_PITCH = {1.2F, 1, 0.8F, 0.8F};
	/** The overpumped explosion: a unit ahead of the camera, half again as big, and with this damage. */
	private static final double OVERPUMP_REACH = 1 * S;
	private static final double OVERPUMP_SIZE = 1.5;
	private static final int OVERPUMP_DAMAGE = 50;
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
	/** A slam hits enemies within this far of the line this far below the ground check, for this much. */
	private static final double SLAM_RADIUS = 1.25 * S;
	private static final double SLAM_REACH = 3 * S;
	private static final float SLAM_DAMAGE = 2;
	/** A power slam's shockwave, and the bits of floor it throws up. */
	private static final String SHOCKWAVE = "slam_shockwave";
	private static final int SLAM_GIBS = 10;
	/** The revving chainsaw grinds walls within this far of the camera. */
	private static final double SAW_WALL_REACH = 3 * S;
	/** The grinding sparks burst again this often (their system's duration), and bits of the wall come off this often. */
	private static final double SAW_SPARK_INTERVAL = 0.05;
	private static final double SAW_GIB_INTERVAL = 0.1;
	/** The chainsaw's hits land where the aim meets the enemy, if it does within this far. */
	private static final double SAW_AIM_REACH = 15 * S;
	private static final String SAWED_ON = "shotgun_sawed_on";
	/** The thrown chainsaw, and the sawblade it becomes off its chain. */
	private static final String THROWN_SAW = "chainsaw_thrown";
	private static final String SAWBLADE = "chainsaw_sawblade";
	/** The sawblade's chainsaw hums at this volume (its AudioSource's). */
	private static final float SAWBLADE_SPIN_VOLUME = 0.25F;
	/** Punches find a thrown chainsaw within this far of the camera, and along the aim within the punch's reach. */
	private static final double SAW_PUNCH_TOUCH = 0.01 * S;
	private static final double SAW_HEAVY_PUNCH_TOUCH = 0.1 * S;
	private static final double SAW_PUNCH_BOX = 0.3 * S;
	/** A Feedbacker punch sends on punchable sawblades within a sphere this big, this far ahead. */
	private static final double SAWBLADE_PUNCH_RADIUS = 3 * S;
	/**
	 * "Railcannon Beam" (RevolverBeam): a 1.2 unit sphere swept along the shot, hitting every enemy in its way up
	 * to 4 times for 2 each. Each hit stops time for a moment (TimeController.HitStop), and the beam goes on to
	 * the next a moment later, sooner past an enemy that's dead.
	 */
	private static final double RAIL_RADIUS = 1.2 * S;
	private static final float RAIL_DAMAGE = 2;
	private static final int RAIL_HITS = 4;
	private static final double RAIL_HITSTOP = 0.05;
	private static final double RAIL_STEP = 0.025;
	private static final double RAIL_STEP_DEAD = 0.01;
	/** The Electric railcannon's beam bores through the blocks it breaks, up to this far past the first one it meets. */
	private static final double RAIL_TUNNEL_DEPTH = 12;
	/** The nails: the Overheat's, the Attractor's, and the burnout's heated ones. */
	private static final String NAIL = "nail";
	private static final String NAIL_FODDER = "nail_fodder";
	private static final String NAIL_HEATED = "nail_heated";
	/** The nailgun's muzzle flashes (a burnout's is bigger), gone after a tenth of a second (RemoveOnTime). */
	private static final String NAILGUN_MUZZLE_FLASH = "nailgun_muzzle_flash";
	private static final String NAILGUN_MUZZLE_FLASH_HEATED = "nailgun_muzzle_flash_heated";
	private static final double NAILGUN_MUZZLE_FLASH_LIFETIME = 0.1;
	/** The nailgun's muzzle flash shows smaller than its prefab makes it: half its size, by eye against ULTRAKILL. */
	private static final double NAILGUN_MUZZLE_FLASH_SIZE = 0.5;
	/** Nail.HitEnemy and OnCollisionEnter play their sounds at this volume. */
	private static final float NAIL_HIT_VOLUME = 0.2F;
	/** An enemy keeps this many of the nails stuck in it (EnemyIdentifier.DeliverDamage). */
	private static final int MAX_STUCK_NAILS = 10;
	/** The Attractor's magnet, and its TimeBomb's explosion: debris that falls off as it breaks. */
	private static final String MAGNET = "magnet";
	private static final String MAGNET_BREAK = "magnet_break";
	/**
	 * A landing impact's dust (DustBig) carries a Shockwave sphere that breaks what a slam can break, the magnets'
	 * cubes among it: a 0.625 unit sphere growing 50 times its size a second, for its first tenth of a second.
	 */
	private static final double LANDING_DUST_RADIUS = 0.625;
	private static final double LANDING_DUST_GROWTH = 50;
	private static final double LANDING_DUST_TIME = 0.1;
	/** The JumpStart's zapper, and its zap's sparks (zapParticle and zapImpactParticle). */
	private static final String ZAPPER = "zapper";
	private static final String ZAP_SPARKS = "zap_sparks";

	private final UltraSounds sounds = new UltraSounds();
	private final Effects effects = new Effects();
	private final SlideEffects slideEffects = new SlideEffects(this.effects);
	private final Map<Revolver.Variant, Revolver> revolvers = new EnumMap<>(Revolver.Variant.class);
	private final List<Coin> coins = new ArrayList<>();
	private final List<Explosion> explosions = new ArrayList<>();
	private final List<Shell> shells = new ArrayList<>();
	private final List<Pellet> pellets = new ArrayList<>();
	private final List<Core> cores = new ArrayList<>();
	private final List<Rocket> rockets = new ArrayList<>();
	private final List<Cannonball> cannonballs = new ArrayList<>();
	private final Revolver.Input input = new Revolver.Input();
	private final RandomSource random = RandomSource.create();
	private final Map<Shotgun.Variant, Shotgun> shotguns = new EnumMap<>(Shotgun.Variant.class);
	private final SawZone sawZone = new SawZone();
	private final List<ThrownSaw> saws = new ArrayList<>();
	private final List<Shockwave> shockwaves = new ArrayList<>();
	/** Enemies the current slam has already hit (GroundCheck.hitEnemies). */
	private final Set<Integer> slammed = new HashSet<>();
	private final List<Sawblade> sawblades = new ArrayList<>();
	private final Whiplash whiplash = new Whiplash(this.sounds, this);
	private final Railcannon railcannon = new Railcannon(this.sounds);
	private final RocketLauncher rocketLauncher = new RocketLauncher(this.sounds);
	/** Railcannon beams still working their way through what they hit. */
	private final List<RailShot> railShots = new ArrayList<>();
	/** The Screwdriver's drills. */
	private final List<Drill> drills = new ArrayList<>();
	/** The nailguns, and the charges they share. */
	private final Nailgun.Charges nailgunCharges = new Nailgun.Charges();
	private final Map<Nailgun.Variant, Nailgun> nailguns = new EnumMap<>(Nailgun.Variant.class);
	private final List<Nail> nails = new ArrayList<>();
	private final List<Magnet> magnets = new ArrayList<>();
	private final List<Nail.Burst> nailBursts = new ArrayList<>();
	/**
	 * How many nails each enemy has had stuck in it (EnemyIdentifier.nailsAmount), and those still in it, oldest
	 * first.
	 */
	private final Map<Integer, Integer> nailCounts = new HashMap<>();
	private final Map<Integer, List<Nail>> stuckNails = new HashMap<>();
	/** The JumpStart's zapper, while it's out, and the electricity between enemies. */
	private @Nullable Zapper zapper;
	private final Conductor conductor = new Conductor();
	/** Landing impacts' dust still breaking things: where each went up, and how long ago. */
	private final List<LandingDust> landingDusts = new ArrayList<>();

	private static final class LandingDust {
		final Vec3 position;
		double age;

		LandingDust(Vec3 position) {
			this.position = position;
		}
	}
	/** The Feedbacker's punch is still active and hasn't punched a chainsaw yet. */
	private boolean sawPunchPending;
	/** The chainsaw idling on the gun, and grinding a wall. */
	private final UltraSounds.Loop sawIdle = this.sounds.loop();
	private final UltraSounds.Loop sawGrind = this.sounds.loop();
	/** Since the grinding sparks last burst, and since bits last came off the wall; below 0 while not grinding. */
	private double sinceSawSparks = -1;
	private double sinceSawGibs;
	/** Since the grinding chainsaw last hit the wall; below 0 while not grinding. */
	private double sinceSawWallHit = -1;
	private FirstPersonWeapons.@Nullable Weapon held;
	private boolean wasFire1;
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
			this.shotguns.put(variant, new Shotgun(variant, this.sounds));
		}
		for (Revolver.Variant variant : Revolver.Variant.values()) {
			this.revolvers.put(variant, new Revolver(variant, this.sounds));
		}
		for (Nailgun.Variant variant : Nailgun.Variant.values()) {
			this.nailguns.put(variant, new Nailgun(variant, this.nailgunCharges, this.sounds));
		}
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(PunchResultPayload.TYPE, (payload, context) -> INSTANCE.onPunchResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(WhiplashBrokenPayload.TYPE, (payload, context) -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level != null && minecraft.player != null) {
				INSTANCE.whiplash.knockedOff(minecraft.level, minecraft.player);
			}
		});
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
			this.cores.clear();
			this.rockets.forEach(Rocket::discard);
			this.rockets.clear();
			this.cannonballs.clear();
			this.saws.forEach(saw -> saw.spin.stop());
			this.saws.clear();
			this.sawblades.forEach(sawblade -> sawblade.spin.stop());
			this.sawblades.clear();
			this.shockwaves.clear();
			this.slammed.clear();
			this.whiplash.cancel();
			this.railShots.forEach(shot -> this.effects.fade(shot.beam));
			this.railShots.clear();
			this.drills.forEach(drill -> drill.drillSound.stop());
			this.drills.clear();
			this.nails.clear();
			this.magnets.clear();
			this.nailBursts.clear();
			this.nailCounts.clear();
			this.stuckNails.clear();
			this.landingDusts.clear();
			if (this.zapper != null) {
				this.zapper.discard();
				this.zapper = null;
			}
			this.conductor.clear();
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
		this.railcannon.recharge(dt);
		this.rocketLauncher.recharge(dt, this.rockets.size());
		this.nailgunCharges.recharge(dt, this.magnets.size());
		updateRicochets(dt, minecraft.level, player);
		updateRailShots(dt, minecraft.level, player);
		this.punchStamina = Math.min(this.punchStamina + 1.25 * dt, 2);
		this.fistCooldown = Math.max(this.fistCooldown - 2 * dt, 0);
		if (this.punchActiveLeft > 0) {
			this.punchActiveLeft -= dt;
			punchCoin(minecraft.level, player);
			if (this.sawPunchPending && punchChainsaws(player)) {
				this.sawPunchPending = false;
			}
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
		for (Core core : List.copyOf(this.cores)) {
			if (!core.removed) {
				core.update(dt, minecraft.level, player, this);
			}
		}
		this.cores.removeIf(core -> core.removed);
		UltraMovement riderMovement = UltraController.INSTANCE.isDriving(player) ? UltraController.INSTANCE.movement() : null;
		for (Rocket rocket : List.copyOf(this.rockets)) {
			if (!rocket.removed) {
				rocket.update(dt, minecraft.level, player, riderMovement, this.rocketLauncher.frozen(), this);
			}
		}
		this.rockets.removeIf(this::rocketGone);
		for (Cannonball ball : List.copyOf(this.cannonballs)) {
			if (!ball.removed) {
				ball.update(dt, minecraft.level, player, this);
			}
		}
		this.cannonballs.removeIf(ball -> ball.removed);
		updateSaws(dt, minecraft.level, player);
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (Drill drill : List.copyOf(this.drills)) {
			drill.update(dt, partialTick, minecraft.level, player, this);
		}
		this.drills.removeIf(drill -> drill.removed);
		updateNails(dt, partialTick, minecraft.level, player);
		updateZapper(dt, partialTick, minecraft.level, player);
		this.conductor.update(dt, conductorWorld(minecraft.level, player), this);
		for (Shockwave shockwave : List.copyOf(this.shockwaves)) {
			shockwave.update(dt, minecraft.level, player, this);
		}
		this.shockwaves.removeIf(Shockwave::done);
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
			UltraMovement movement = UltraController.INSTANCE.movement();
			if (movement != null) {
				this.whiplash.frame(dt, minecraft.level, player, movement, consumeClicks(UltraKeys.WHIPLASH), UltraKeys.WHIPLASH.isDown());
			}
			Revolver revolver = revolver(this.held);
			if (revolver != null) {
				revolver.update(dt, this.input, this);
			}
			Shotgun shotgun = shotgun(this.held);
			if (shotgun != null) {
				shotgun.update(dt, this.input, this);
			}
			Nailgun nailgun = nailgun(this.held);
			if (nailgun != null) {
				nailgun.update(dt, this.input, this);
			}
			if (railcannonVariant(this.held) != null) {
				this.railcannon.update(dt, this.input, this);
			}
			if (rocketLauncherVariant(this.held) != null) {
				this.rocketLauncher.update(dt, this.input, this);
			}
			saw(dt, minecraft.level, player, shotgun);
			slam(minecraft.level, player);
			this.slideEffects.update(UltraController.INSTANCE.movement());
		} else {
			this.slideEffects.update(null);
			this.lastHealth = -1;
			saw(dt, minecraft.level, player, null);
			this.whiplash.cancel();
		}
		for (Shotgun shotgun : this.shotguns.values()) {
			shotgun.cool(dt, this.saws.size());
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
			: new FirstPersonWeapons.ShotgunState(shotgun.heat(), shotgun.meter(), shotgun.tint(), shotgun.reddening(), shotgun.jitter(), new FirstPersonWeapons.SawState(
				shotgun.variant == Shotgun.Variant.SAWED_ON && shotgun.sawAttached(), shotgun.sawing(), shotgun.sawBroken(), shotgun.bladeScroll()
			));
		Nailgun nailgun = nailgun(this.held);
		FirstPersonWeapons.NailgunState nailgunDisplay = nailgun == null ? FirstPersonWeapons.NailgunState.NONE : nailgun.display();
		FirstPersonWeapons.RocketLauncherState rocketLauncherDisplay = rocketLauncherVariant(this.held) == null
			? FirstPersonWeapons.RocketLauncherState.NONE
			: this.rocketLauncher.display();
		FirstPersonWeapons.INSTANCE.update(dt, this.held, display, shotgunDisplay, nailgunDisplay, this.railcannon.charge(), rocketLauncherDisplay, this);
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
		Nailgun nailgun = nailgun(this.held);
		if (nailgun != null) {
			nailgun.holster();
		}
		if (railcannonVariant(this.held) != null) {
			this.railcannon.holster(this);
		}
		if (rocketLauncherVariant(this.held) != null) {
			this.rocketLauncher.holster();
		}
		this.held = weapon;
		this.wasFire1 = true;
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
			nailgun = nailgun(weapon);
			if (nailgun != null) {
				nailgun.equip();
			}
			Railcannon.Variant variant = railcannonVariant(weapon);
			if (variant != null) {
				this.railcannon.equip(variant);
			}
			RocketLauncher.Variant launcher = rocketLauncherVariant(weapon);
			if (launcher != null) {
				this.rocketLauncher.equip(launcher);
			}
			FirstPersonWeapons.INSTANCE.equip(weapon);
		}
	}

	@Override
	public void weaponEvent(FirstPersonWeapons.Weapon weapon, String function, float floatParameter) {
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
			case "ClickSound" -> {
				boolean tinted = shotgun != null && shotgun.meterTinted();
				this.sounds.play(tinted ? "shotgun_click_charge" : "shotgun_click", 0.5F, this.sounds.randomPitch(0.95F, 1.05F));
			}
			// NailgunAnimationReceiver.CanShoot, as the Equip animation brings the nailgun up.
			case "CanShoot" -> {
				Nailgun nailgun = nailgun(weapon);
				if (nailgun != null) {
					nailgun.canShoot();
				}
			}
			// Shotgun.Smack, as the reload knocks the next core in.
			case "Smack" -> this.sounds.play("shotgun_smack", 0.75F, this.sounds.randomPitch(2, 2.2F));
			// Shotgun.Pump1Sound and Pump2Sound: the pump slides back and forth; going back, a tone rises with the pumps.
			case "Pump1Sound" -> {
				this.sounds.play("shotgun_pump1", 1, this.sounds.randomPitch(0.95F, 1.05F));
				this.sounds.play("shotgun_pump_charge", 1, 1 + (shotgun == null ? 0 : shotgun.pumps()) / 5F);
			}
			case "Pump2Sound" -> this.sounds.play("shotgun_pump2", 1, this.sounds.randomPitch(0.95F, 1.05F));
			// RocketLauncher.Clunk: a clunk around the event's pitch, and a small shake.
			case "Clunk" -> {
				this.sounds.play("rocket_launcher_clunk", 1, this.sounds.randomPitch(floatParameter - 0.1F, floatParameter + 0.1F));
				UltraMovement movement = UltraController.INSTANCE.movement();
				if (movement != null) {
					movement.shake(0.25);
				}
			}
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

	private @Nullable Nailgun nailgun(FirstPersonWeapons.@Nullable Weapon weapon) {
		if (weapon == null) {
			return null;
		}
		return switch (weapon) {
			case NAILGUN_ATTRACTOR -> this.nailguns.get(Nailgun.Variant.ATTRACTOR);
			case NAILGUN_OVERHEAT -> this.nailguns.get(Nailgun.Variant.OVERHEAT);
			case NAILGUN_JUMPSTART -> this.nailguns.get(Nailgun.Variant.JUMPSTART);
			default -> null;
		};
	}

	private static FirstPersonWeapons.Weapon weapon(Nailgun.Variant variant) {
		return switch (variant) {
			case ATTRACTOR -> FirstPersonWeapons.Weapon.NAILGUN_ATTRACTOR;
			case OVERHEAT -> FirstPersonWeapons.Weapon.NAILGUN_OVERHEAT;
			case JUMPSTART -> FirstPersonWeapons.Weapon.NAILGUN_JUMPSTART;
		};
	}

	private @Nullable Shotgun shotgun(FirstPersonWeapons.@Nullable Weapon weapon) {
		if (weapon == null) {
			return null;
		}
		return switch (weapon) {
			case SHOTGUN_CORE_EJECT -> this.shotguns.get(Shotgun.Variant.CORE_EJECT);
			case SHOTGUN_PUMP_CHARGE -> this.shotguns.get(Shotgun.Variant.PUMP_CHARGE);
			case SHOTGUN_SAWED_ON -> this.shotguns.get(Shotgun.Variant.SAWED_ON);
			default -> null;
		};
	}

	@Override
	public void armEvent(String function) {
		switch (function) {
			case "ActiveStart" -> {
				Minecraft minecraft = Minecraft.getInstance();
				LocalPlayer player = minecraft.player;
				// A boosted pellet, a punched cannonball, or a chainsaw or sawblade punched on, takes the punch: it hits nothing else.
				boolean taken = player != null && (this.arm == FistType.STANDARD
					? boostPellet(player) || punchCannonball(player) || punchSaws(player)
					: smashCannonball(player) || detachSaw(player));
				if (!taken) {
					ClientPlayNetworking.send(new PunchPayload(this.arm));
				}
				this.sawPunchPending = this.arm == FistType.STANDARD && !taken;
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
	public void whiplashEvent(String function) {
		if (function.equals("CatchOver")) {
			this.whiplash.catchOver();
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
		if (stack.is(UltraItems.SHOTGUN_CORE_EJECT)) {
			return FirstPersonWeapons.Weapon.SHOTGUN_CORE_EJECT;
		}
		if (stack.is(UltraItems.SHOTGUN_PUMP_CHARGE)) {
			return FirstPersonWeapons.Weapon.SHOTGUN_PUMP_CHARGE;
		}
		if (stack.is(UltraItems.NAILGUN_ATTRACTOR)) {
			return FirstPersonWeapons.Weapon.NAILGUN_ATTRACTOR;
		}
		if (stack.is(UltraItems.NAILGUN_OVERHEAT)) {
			return FirstPersonWeapons.Weapon.NAILGUN_OVERHEAT;
		}
		if (stack.is(UltraItems.NAILGUN_JUMPSTART)) {
			return FirstPersonWeapons.Weapon.NAILGUN_JUMPSTART;
		}
		if (stack.is(UltraItems.RAILCANNON_ELECTRIC)) {
			return FirstPersonWeapons.Weapon.RAILCANNON_ELECTRIC;
		}
		if (stack.is(UltraItems.RAILCANNON_SCREWDRIVER)) {
			return FirstPersonWeapons.Weapon.RAILCANNON_SCREWDRIVER;
		}
		if (stack.is(UltraItems.RAILCANNON_MALICIOUS)) {
			return FirstPersonWeapons.Weapon.RAILCANNON_MALICIOUS;
		}
		if (stack.is(UltraItems.ROCKET_LAUNCHER_FREEZEFRAME)) {
			return FirstPersonWeapons.Weapon.ROCKET_LAUNCHER_FREEZEFRAME;
		}
		if (stack.is(UltraItems.ROCKET_LAUNCHER_SRS)) {
			return FirstPersonWeapons.Weapon.ROCKET_LAUNCHER_SRS;
		}
		return stack.is(UltraItems.SHOTGUN_SAWED_ON) ? FirstPersonWeapons.Weapon.SHOTGUN_SAWED_ON : null;
	}

	private static Railcannon.@Nullable Variant railcannonVariant(FirstPersonWeapons.@Nullable Weapon weapon) {
		if (weapon == null) {
			return null;
		}
		return switch (weapon) {
			case RAILCANNON_ELECTRIC -> Railcannon.Variant.ELECTRIC;
			case RAILCANNON_SCREWDRIVER -> Railcannon.Variant.SCREWDRIVER;
			case RAILCANNON_MALICIOUS -> Railcannon.Variant.MALICIOUS;
			default -> null;
		};
	}

	private static FirstPersonWeapons.Weapon weapon(Railcannon.Variant variant) {
		return switch (variant) {
			case ELECTRIC -> FirstPersonWeapons.Weapon.RAILCANNON_ELECTRIC;
			case SCREWDRIVER -> FirstPersonWeapons.Weapon.RAILCANNON_SCREWDRIVER;
			case MALICIOUS -> FirstPersonWeapons.Weapon.RAILCANNON_MALICIOUS;
		};
	}

	private static RocketLauncher.@Nullable Variant rocketLauncherVariant(FirstPersonWeapons.@Nullable Weapon weapon) {
		if (weapon == null) {
			return null;
		}
		return switch (weapon) {
			case ROCKET_LAUNCHER_FREEZEFRAME -> RocketLauncher.Variant.FREEZEFRAME;
			case ROCKET_LAUNCHER_SRS -> RocketLauncher.Variant.SRS;
			default -> null;
		};
	}

	private static FirstPersonWeapons.Weapon weapon(RocketLauncher.Variant variant) {
		return switch (variant) {
			case FREEZEFRAME -> FirstPersonWeapons.Weapon.ROCKET_LAUNCHER_FREEZEFRAME;
			case SRS -> FirstPersonWeapons.Weapon.ROCKET_LAUNCHER_SRS;
		};
	}

	/** Whether the stack is one of the railcannons, which show their shared charge as a cooldown. */
	public static boolean isRailcannon(ItemStack stack) {
		return stack.is(UltraItems.RAILCANNON_ELECTRIC) || stack.is(UltraItems.RAILCANNON_SCREWDRIVER) || stack.is(UltraItems.RAILCANNON_MALICIOUS);
	}

	/** The railcannons' cooldown as the hotbar shows it: 1 just after a shot, 0 once fully charged. */
	public double railcannonCooldown() {
		return this.railcannon.cooldown();
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
		boolean fire1 = options.keyAttack.isDown();
		boolean fire2 = options.keyUse.isDown();
		this.input.fire1Held = fire1;
		this.input.fire1Pressed = fire1 && !this.wasFire1;
		this.wasFire1 = fire1;
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
		int bodiesLeft = charged ? 3 : 1;
		int blocksLeft = charged ? PIERCER_BLOCKS : 1;
		float blockDamage = charged ? 2 : 1;
		// Blocks the shot breaks on its way, and how far along it the last of them is.
		Set<BlockPos> broken = new HashSet<>();
		double passed = -1;
		Vec3 end = eye;
		shot:
		while (true) {
			for (Hitscan.Hit hit : Hitscan.cast(minecraft.level, player, eye, look, RANGE, this.coins, grenades(), broken)) {
				boolean wall = hit.kind() == Hitscan.Kind.BLOCK || hit.kind() == Hitscan.Kind.END;
				if (!wall && hit.distance() <= passed) {
					continue;
				}
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
						break shot;
					}
				} else if (hit.kind() == Hitscan.Kind.COIN) {
					hit.coin().hit(hit.coin().power, new HashSet<>(), charged ? Effects.BeamStyle.SUPER : Effects.BeamStyle.COIN);
					end = hit.position();
					break shot;
				} else if (hit.kind() == Hitscan.Kind.GRENADE) {
					grenadeShot(hit.grenade());
					if (!charged) {
						end = hit.position();
						break shot;
					}
				} else {
					end = hit.position();
					if (hit.kind() == Hitscan.Kind.BLOCK) {
						this.effects.impact(minecraft.level, hit.position());
						hitBlock(hit.block(), blockDamage);
						// The charged beam carries on through the blocks it breaks.
						if (--blocksLeft > 0 && BlockDamage.breaksInOneHit(minecraft.level, hit.block(), blockDamage)) {
							broken.add(hit.block());
							passed = hit.distance();
							continue shot;
						}
					}
					break shot;
				}
			}
		}
		beam(muzzle(eye, look, player), end, charged ? Effects.BeamStyle.SUPER : Effects.BeamStyle.REVOLVER);
	}

	/** A revolver beam (or a coin's, or a railcannon's) from one point to another: it breaks the magnets it goes through. */
	private void beam(Vec3 from, Vec3 to, Effects.BeamStyle style) {
		breakMagnets(from, to);
		this.effects.beam(from, to, style);
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
		List<Hitscan.Hit> hits = Hitscan.cast(level, player, from, direction, RANGE, this.coins, grenades());
		Vec3 end = hits.getLast().position();
		for (Hitscan.Hit hit : hits) {
			if (hit.kind() == Hitscan.Kind.GRENADE) {
				grenadeShot(hit.grenade());
			} else if (hit.kind() == Hitscan.Kind.ENTITY) {
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
					hitBlock(hit.block(), 1);
					if (ricochets > 0) {
						Vec3 normal = hit.face().getUnitVec3();
						Vec3 reflected = direction.subtract(normal.scale(2 * direction.dot(normal)));
						this.ricochets.add(new Ricochet(end.add(normal.scale(0.01)), reflected, ricochets - 1));
					}
				}
				break;
			}
		}
		beam(drawFrom, end, Effects.BeamStyle.SHARP);
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

	private static void hitBlock(@Nullable BlockPos block, float damage) {
		if (block != null) {
			ClientPlayNetworking.send(new BlockHitPayload(block, damage));
		}
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
		if (coin.beam == Effects.BeamStyle.RAILCANNON || coin.beam == Effects.BeamStyle.MALICIOUS) {
			railPassOn(coin, level, player);
			return;
		}

		Coin next = this.coins.stream()
			.filter(c -> c != coin && c.hittable() && Hitscan.clear(level, player, coin.position, c.position))
			.min(Comparator.comparingDouble(c -> c.position.distanceToSqr(coin.position)))
			.orElse(null);
		if (next != null) {
			next.hit(coin.power + 1, coin.chainHits, coin.beam);
			beam(coin.position, next.position, coin.beam);
			return;
		}

		// After coins, a core or rocket the coin can see within 100 units of the player: it goes off. The coin's own
		// beam is only drawn, so the grenade just goes off; a piercing shot the coin passes on is a real shot at
		// it, with the parry flash a revolver shot on a grenade brings.
		Grenade grenade = grenadeTarget(coin, level, player);
		if (grenade != null) {
			beam(coin.position, grenade.position, coin.beam);
			if (coin.beam == Effects.BeamStyle.COIN) {
				shotExplode(grenade);
			} else {
				grenadeShot(grenade);
			}
			return;
		}

		LivingEntity target = coinTarget(coin, level, player);
		if (target != null) {
			Vec3 head = target.getEyePosition();
			coin.chainHits.add(target.getId());
			damage(level, target, coin.power * 2, true, head);
			beam(coin.position, head, coin.beam);
			return;
		}

		Vec3 direction = new Vec3(this.random.nextGaussian(), this.random.nextGaussian(), this.random.nextGaussian()).normalize();
		Vec3 end = coin.position.add(direction.scale(30));
		BlockHitResult hit = level.clip(new ClipContext(coin.position, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		beam(coin.position, hit.getLocation(), coin.beam);
		if (hit.getType() != HitResult.Type.MISS) {
			hitBlock(hit.getBlockPos(), coin.power);
		}
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
			beam(coin.position, head, Effects.BeamStyle.COIN);
			landing = head;
			power++;
		} else {
			BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(RANGE)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			beam(coin.position, hit.getLocation(), Effects.BeamStyle.COIN);
			if (hit.getType() != HitResult.Type.MISS) {
				hitBlock(hit.getBlockPos(), coin.power);
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

	/**
	 * Coin.ExplosiveReflectCheck: the nearest core or rocket to the coin that it can see, within reach of the player,
	 * but not a rocket the player rides.
	 */
	private @Nullable Grenade grenadeTarget(Coin coin, Level level, LocalPlayer player) {
		return grenades().stream()
			.filter(g -> !g.removed && !(g instanceof Rocket rocket && rocket.playerRiding) && g.position.distanceTo(player.position()) < GRENADE_TARGET_RANGE && Hitscan.clear(level, player, coin.position, g.position))
			.min(Comparator.comparingDouble(g -> g.position.distanceToSqr(coin.position)))
			.orElse(null);
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

	// ---- nailgun --------------------------------------------------------------------------------

	/**
	 * Nailgun.Shoot: a nail out of a unit ahead of the camera (or the camera, with a wall closer), turned up to the
	 * spread about each axis and flying at 200 units a second; the muzzle flash at the barrel's shoot point with
	 * the shot, the Shoot animation and a camera shake (stronger during a burnout).
	 */
	@Override
	public void shootNail(Nailgun.Variant variant, int barrel, boolean heated, double spread, float volume, float pitch, int steps) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		String prefab = heated ? NAIL_HEATED : variant == Nailgun.Variant.ATTRACTOR ? NAIL_FODDER : NAIL;
		UltraModel model = UltraModel.get(prefab);
		if (player == null || minecraft.level == null || movement == null || model == null) {
			return;
		}
		FirstPersonWeapons.Weapon weapon = weapon(variant);
		FirstPersonWeapons.Placement point = FirstPersonWeapons.INSTANCE.nailgunShootPoint(weapon, barrel);
		if (point != null) {
			this.effects.stepFlash(heated ? NAILGUN_MUZZLE_FLASH_HEATED : NAILGUN_MUZZLE_FLASH, point.position(), point.rotation(), NAILGUN_MUZZLE_FLASH_LIFETIME, point.scale() * NAILGUN_MUZZLE_FLASH_SIZE);
		}
		this.sounds.play("nailgun_shoot", volume, pitch);
		Vec3 eye = movement.eyePosition();
		Vec3 ahead = eye.add(player.getLookAngle().scale(S));
		Vec3 from = Hitscan.clear(minecraft.level, player, eye, ahead) ? ahead : eye;
		Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
		double up = Math.toRadians(spread(spread / 3));
		double yaw = Math.toRadians(spread(spread / 3));
		Vector3f direction = camera.transform(new Vector3f(
			(float)(Math.cos(up) * Math.sin(yaw)), (float)-Math.sin(up), (float)(-Math.cos(up) * Math.cos(yaw))
		));
		Nail nail = new Nail(prefab, model.script("nail"), from, new Vec3(direction.x, direction.y, direction.z).scale(Nail.SPEED));
		this.nails.add(nail);
		nail.fly(steps, minecraft.level, player, this);
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon);
		if (animator != null) {
			animator.setTrigger("Shoot");
		}
		movement.shake(heated ? 0.35 : 0.1);
	}

	/** Nailgun.ShootMagnet: the magnet thrown out of a unit ahead of the camera (or the camera) along the aim, with its throw. */
	@Override
	public void shootMagnet(Nailgun.Variant variant, boolean animate) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		UltraModel model = UltraModel.get(MAGNET);
		if (player == null || minecraft.level == null || movement == null || model == null) {
			return;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 ahead = eye.add(look.scale(S));
		this.magnets.add(new Magnet(model, this.sounds, Hitscan.clear(minecraft.level, player, eye, ahead) ? ahead : eye, look));
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon(variant));
		if (animator != null && animate) {
			animator.setTrigger("Shoot");
		}
		this.sounds.play("magnet_shoot", 1, this.sounds.randomPitch(1.4F, 1.6F));
	}

	/** The magnets (each catching and letting go of nails), then the nails, and the nail bombs; enemies that are gone are forgotten. */
	private void updateNails(double dt, float partialTick, Level level, LocalPlayer player) {
		for (Magnet magnet : List.copyOf(this.magnets)) {
			if (!magnet.removed) {
				magnet.update(dt, partialTick, level, player, this.nails, this.sawblades, this.saws, this.magnets, this);
			}
		}
		this.magnets.removeIf(magnet -> magnet.removed);
		for (Nail nail : List.copyOf(this.nails)) {
			if (!nail.removed) {
				nail.update(dt, partialTick, level, player, this);
			}
		}
		this.nails.removeIf(nail -> nail.removed);
		for (Nail.Burst burst : this.nailBursts) {
			burst.update(dt);
		}
		this.nailBursts.removeIf(Nail.Burst::over);
		for (LandingDust dust : this.landingDusts) {
			double radius = LANDING_DUST_RADIUS * (1 + LANDING_DUST_GROWTH * dust.age) * S;
			for (Magnet magnet : List.copyOf(this.magnets)) {
				OrientedBox cube = magnet.removed ? null : magnet.cube();
				if (cube != null && cube.distanceTo(dust.position) <= radius) {
					magnet.hitCube(level, player, this);
				}
			}
			dust.age += dt;
		}
		this.landingDusts.removeIf(dust -> dust.age > LANDING_DUST_TIME);
		this.nailCounts.keySet().removeIf(id -> level.getEntity(id) == null);
		this.stuckNails.keySet().removeIf(id -> level.getEntity(id) == null);
	}

	/**
	 * Nail.HitEnemy: the nail's hit, with its sound. A nail bomb's nail does every nail's damage times half their
	 * number, once for each enemy. The nail counts as stuck in the enemy, which keeps the ten latest.
	 */
	@Override
	public void nailEnemy(Nail nail, LivingEntity enemy) {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		this.sounds.once().update("nail_hit_enemy", NAIL_HIT_VOLUME, this.sounds.randomPitch(0.9F, 1.1F), nail.position);
		Nail.Burst burst = nail.burst != null && !nail.burst.over() ? nail.burst : null;
		if (burst == null) {
			nailHit(level, enemy, nail.damage, nail.position, WeaponHitPayload.Kind.NAIL);
		} else if (burst.damaged.add(enemy.getId())) {
			float damage = nail.damage * (burst.nails / 2);
			if (damage > 0) {
				nailHit(level, enemy, damage, nail.position, WeaponHitPayload.Kind.NAIL_BOMB);
			}
		}
		this.nailCounts.merge(enemy.getId(), 1, Integer::sum);
		List<Nail> stuck = this.stuckNails.computeIfAbsent(enemy.getId(), id -> new ArrayList<>());
		stuck.removeIf(n -> n.removed);
		stuck.add(nail);
		while (stuck.size() > MAX_STUCK_NAILS) {
			stuck.removeFirst().remove();
		}
	}

	private void nailHit(Level level, LivingEntity enemy, float damage, Vec3 at, WeaponHitPayload.Kind kind) {
		ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), damage, false, at, kind));
		Gore gore = Gore.of(false, damage, false);
		this.effects.blood(level, at, gore);
		this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
	}

	/**
	 * Nail.OnCollisionEnter on a wall: its ricochet, a bit of the wall, and the hit on the block; a heated nail
	 * shatters glass.
	 */
	@Override
	public void nailWall(Nail nail, BlockPos block, Vec3 at) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		this.sounds.once().update("nail_hit_wall", NAIL_HIT_VOLUME, this.sounds.randomPitch(0.9F, 1.1F), at);
		BlockState state = level.getBlockState(block);
		if (!state.isAir()) {
			level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 0, 0, 0);
		}
		float hardness = state.getDestroySpeed(level, block);
		hitBlock(block, nail.heated && hardness >= 0 && state.getSoundType() == SoundType.GLASS ? hardness : nail.damage);
	}

	@Override
	public int nailsIn(LivingEntity enemy) {
		return this.nailCounts.getOrDefault(enemy.getId(), 0);
	}

	@Override
	public void magnetPull(LivingEntity enemy, Vec3 change) {
		ClientPlayNetworking.send(new MagnetPullPayload(enemy.getId(), change));
	}

	/** TimeBomb.Beep on a magnet: the beeper flashes, its beep as high as the magnet's load makes it. */
	@Override
	public void magnetBeep(Magnet magnet) {
		this.sounds.once().update("magnet_beep", 1, magnet.beeperPitch, magnet.position);
	}

	/** The magnet broke: its debris and their sound (TimeBomb's explosion); the nails it threw out go as one nail bomb. */
	@Override
	public void magnetBroke(Magnet magnet, List<Nail> launched) {
		this.effects.particles(MAGNET_BREAK, null, magnet.position);
		this.sounds.once().update(MAGNET_BREAK, 1, this.sounds.randomPitch(0.9F, 1.1F), magnet.position);
		if (!launched.isEmpty()) {
			Nail.Burst burst = new Nail.Burst(launched.size());
			this.nailBursts.add(burst);
			for (Nail nail : launched) {
				nail.burst = burst;
			}
		}
	}

	/** RevolverBeam through a magnet's cube: the magnet breaks, and the beam goes on. */
	private void breakMagnets(Vec3 from, Vec3 to) {
		Minecraft minecraft = Minecraft.getInstance();
		double length = from.distanceTo(to);
		if (minecraft.level == null || minecraft.player == null || length <= 0) {
			return;
		}
		Vec3 direction = to.subtract(from).scale(1 / length);
		for (Magnet magnet : List.copyOf(this.magnets)) {
			OrientedBox cube = magnet.removed ? null : magnet.cube();
			if (cube != null && cube.clip(from, direction, length).isPresent()) {
				magnet.hitCube(minecraft.level, minecraft.player, this);
			}
		}
	}

	/**
	 * Nailgun.ShootZapper: the zapper out of the camera along the aim, breaking the one already out; the throw's
	 * sound, and the gun's Shoot animation.
	 */
	@Override
	public void shootZapper(Nailgun.Variant variant, boolean animate) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		UltraModel model = UltraModel.get(ZAPPER);
		if (player == null || minecraft.level == null || movement == null || model == null) {
			return;
		}
		if (this.zapper != null) {
			this.zapper.destroy(false, this);
		}
		JsonObject script = model.script("zapper");
		int orb = script.get("orb").getAsInt();
		Particles lightning = null;
		for (JsonElement system : model.particles()) {
			if (system.getAsJsonObject().get("bone").getAsInt() == orb) {
				lightning = new Particles(system.getAsJsonObject(), movement.eyePosition(), new Quaternionf(), ExplosionLook.unityRotation(model, orb), model.rest().sx(orb), true);
			}
		}
		this.zapper = new Zapper(script, lightning, this.sounds, movement.eyePosition(), player.getLookAngle(), movement.rootPosition());
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon(variant));
		if (animator != null && animate) {
			animator.setTrigger("Shoot");
		}
		this.sounds.play("magnet_shoot", 1, this.sounds.randomPitch(1.4F, 1.6F));
	}

	@Override
	public boolean zapperAttached() {
		return this.zapper != null && !this.zapper.removed && this.zapper.attached;
	}

	@Override
	public @Nullable Zapper zapper() {
		return this.zapper;
	}

	/** Nailgun.UpdateZapHud's raycast: the first enemy or wall along the aim. */
	@Override
	public double aimedEnemyDistance() {
		Minecraft minecraft = Minecraft.getInstance();
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (minecraft.level == null || minecraft.player == null || movement == null) {
			return Double.NaN;
		}
		Hitscan.Hit first = Hitscan.cast(minecraft.level, minecraft.player, movement.eyePosition(), minecraft.player.getLookAngle(), RANGE, List.of()).getFirst();
		return first.kind() == Hitscan.Kind.ENTITY ? first.distance() / S : Double.NaN;
	}

	/** The zapper, its cable from the JumpStart's ZapperPosition while it's held, else from the player. */
	private void updateZapper(double dt, float partialTick, Level level, LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (this.zapper == null || movement == null) {
			return;
		}
		Vec3 root = movement.rootPosition();
		Vec3 lineStart = root;
		if (this.held == FirstPersonWeapons.Weapon.NAILGUN_JUMPSTART) {
			FirstPersonWeapons.Frame point = FirstPersonWeapons.INSTANCE.weaponBone(this.held, "ZapperPosition");
			if (point != null) {
				Minecraft minecraft = Minecraft.getInstance();
				Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
				lineStart = world(camera, point.position()).scale(S).add(minecraft.gameRenderer.mainCamera().position());
			}
		}
		this.zapper.update(dt, partialTick, level, player, lineStart, root, this);
		if (this.zapper != null && this.zapper.removed) {
			this.zapper = null;
		}
	}

	/**
	 * Zapper.Zap: the enemy takes the zapper's damage, electric; if it conducts, the zap leaves it alive for its
	 * aftershock. Sparks fly off it, and the zapper starts recharging.
	 */
	@Override
	public void zap(Zapper zapper, LivingEntity enemy) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}
		boolean conducting = this.conductor.electrify(enemy, conductorWorld(minecraft.level, minecraft.player), this);
		float damage = zapper.damage();
		ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), damage, false, zapper.position,
			conducting ? WeaponHitPayload.Kind.ZAP_CONDUCTING : WeaponHitPayload.Kind.ZAP));
		Gore gore = Gore.of(false, damage, false);
		this.effects.blood(minecraft.level, zapper.position, gore);
		this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
		this.nailgunCharges.zapperRecharge = 0;
		sparks(enemy.getBoundingBox().getCenter());
	}

	/** Zapper.Break: its break effect (sparks and bits, with their sounds), and the cable's snap if it snapped. */
	@Override
	public void zapperBroke(Zapper zapper, boolean snapped) {
		this.effects.particles("chainsaw_break", null, zapper.position);
		this.sounds.play("chainsaw_break", 1, this.sounds.randomPitch(0.9F, 1.1F));
		this.sounds.play("chainsaw_spark", 1, this.sounds.randomPitch(0.85F, 1.15F));
		if (snapped) {
			this.sounds.once().update("zapper_snap", 1, this.sounds.randomPitch(0.9F, 1.1F), zapper.position);
		}
	}

	/** SparksNail: a zap's sparks, with their crackle. */
	private void sparks(Vec3 at) {
		this.effects.particles(ZAP_SPARKS, null, at);
		this.sounds.play("zap", 1, this.sounds.randomPitch(0.8F, 1.2F));
	}

	private Conductor.World conductorWorld(Level level, LocalPlayer player) {
		List<Conductor.Zappable> zappables = new ArrayList<>();
		zappables.addAll(this.coins);
		zappables.addAll(this.saws);
		zappables.addAll(this.drills);
		return new Conductor.World(level, player, this.magnets, zappables);
	}

	@Override
	public void zapNails(LivingEntity enemy) {
		for (Nail nail : this.stuckNails.getOrDefault(enemy.getId(), List.of())) {
			nail.zapped = true;
		}
	}

	/** EnemyIdentifier.PreShockBleed: the nails stuck in the enemy come out, each with a little blood, and sparks if zapped. */
	@Override
	public void bleedNails(LivingEntity enemy) {
		Level level = Minecraft.getInstance().level;
		List<Nail> stuck = this.stuckNails.remove(enemy.getId());
		if (level != null && stuck != null) {
			for (Nail nail : stuck) {
				if (nail.removed) {
					continue;
				}
				ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), 0, false, nail.position, WeaponHitPayload.Kind.BLEED));
				this.effects.blood(level, nail.position, Gore.SMALL);
				this.sounds.play("gore.small", 1, this.sounds.randomPitch(0.75F, 1.5F));
				if (nail.zapped) {
					sparks(nail.position);
				}
				nail.remove();
			}
		}
		this.nailCounts.put(enemy.getId(), 0);
	}

	@Override
	public void zapEnemy(LivingEntity enemy, float damage, Vec3 at) {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), damage, false, at, WeaponHitPayload.Kind.ZAP));
		Gore gore = Gore.of(false, damage, false);
		this.effects.blood(level, at, gore);
		this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
	}

	@Override
	public void zapPlayer(Vec3 from, Vec3 to) {
		ClientPlayNetworking.send(new ZapHurtPayload());
		arc(from, to, true);
	}

	/** ElectricityLine, its crackle halfway along it (RandomPitch), and the zap's sparks where it lands. */
	@Override
	public void arc(Vec3 from, Vec3 to, boolean sparks) {
		this.effects.arc(from, to);
		this.sounds.once().update("electric_line", 1, this.sounds.randomPitch(0.9F, 1.1F), from.lerp(to, 0.5));
		if (sparks) {
			sparks(to);
		}
	}

	@Override
	public void damageMagnet(Magnet magnet) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && minecraft.player != null) {
			magnet.damage(minecraft.level, minecraft.player, this);
		}
	}

	@Override
	public void aftershockShake() {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			movement.shake(1);
		}
	}

	// ---- railcannon ----------------------------------------------------------------------------

	/**
	 * Railcannon.Shoot: the variant's beam out of the camera along the aim, drawn from the gun's Shootpoint with its
	 * muzzle flash there, or the Screwdriver's drill thrown along it; its fire sound, its Shoot animation and a
	 * camera shake.
	 */
	@Override
	public void fireRailcannon(Railcannon.Variant variant) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || minecraft.level == null || movement == null) {
			return;
		}
		FirstPersonWeapons.Weapon weapon = weapon(variant);
		// The fire sound prefab's sources (the Malicious railcannon's has three).
		for (int i = 0; i < 3; i++) {
			this.sounds.play("railcannon_fire." + variant.sounds + "." + i, 1, 1);
		}
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon);
		if (animator != null) {
			animator.setTrigger("Shoot");
		}
		movement.shake(2);
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 shootPoint = FirstPersonWeapons.INSTANCE.railcannonShootPoint(weapon);
		Vec3 drawFrom = shootPoint != null ? shootPoint : eye;
		Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
		if (variant == Railcannon.Variant.ELECTRIC) {
			this.effects.beamFlash(drawFrom, camera, Effects.BeamStyle.RAILCANNON);
			electricBeam(minecraft.level, player, eye, look, drawFrom, RAIL_DAMAGE, Float.NaN, new HashSet<>());
		} else if (variant == Railcannon.Variant.SCREWDRIVER) {
			UltraModel model = UltraModel.get(DRILL);
			if (model != null) {
				this.drills.add(new Drill(model.script("harpoon"), model.script("timeBomb").get("timer").getAsDouble(), this.sounds, eye, look));
			}
		} else {
			this.effects.beamFlash(drawFrom, camera, Effects.BeamStyle.MALICIOUS);
			maliciousBeam(minecraft.level, player, eye, look, drawFrom, 0, Float.NaN, new HashSet<>());
		}
	}

	@Override
	public void zoom(double share) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			movement.zoom(share);
		}
		FirstPersonWeapons.INSTANCE.zoom(share > 0);
	}

	/**
	 * The Electric railcannon's beam ("Railcannon Beam", a piercing RevolverBeam): it bores through the blocks it
	 * breaks, and sweeps up everything in its way to the block that stops it, or to a coin, which fires it on. Its
	 * hits then land one after another, nearest first, and it stays at full width until they're done.
	 *
	 * @param damage each hit's, a block's being all four hits'
	 * @param coinPower the power a coin it hits takes, or NaN for the coin to keep its own
	 * @param chainHits the enemies coins have already sent this beam at
	 */
	private void electricBeam(Level level, LocalPlayer player, Vec3 from, Vec3 direction, Vec3 drawFrom, float damage, float coinPower, Set<Integer> chainHits) {
		Tunnel tunnel = Tunnel.bore(level, player, from, direction, RANGE, damage * RAIL_HITS, RAIL_RADIUS, RAIL_TUNNEL_DEPTH);
		List<Hitscan.Hit> hits = new ArrayList<>(tunnel.blocks());
		for (Hitscan.Hit hit : Hitscan.sweep(level, player, from, tunnel.end(), RAIL_RADIUS, this.coins, grenades())) {
			for (int i = 0; i < (hit.kind() == Hitscan.Kind.ENTITY ? RAIL_HITS : 1); i++) {
				hits.add(hit);
			}
		}
		hits.sort(Comparator.comparingDouble(Hitscan.Hit::distance));
		Vec3 end = tunnel.end();
		for (int i = 0; i < hits.size(); i++) {
			if (hits.get(i).kind() == Hitscan.Kind.COIN) {
				end = hits.get(i).coin().position;
				hits = hits.subList(0, i + 1);
				break;
			}
		}
		breakMagnets(drawFrom, end);
		this.conductor.beamThroughWater(from, end, conductorWorld(level, player), this);
		RailShot shot = new RailShot(hits, this.effects.heldBeam(drawFrom, end, Effects.BeamStyle.RAILCANNON), damage, coinPower, chainHits);
		this.railShots.add(shot);
		advance(shot, level, player);
	}

	/** A railcannon beam working its way through its hits (RevolverBeam.PiercingShotCheck). */
	private static final class RailShot {
		final List<Hitscan.Hit> hits;
		final Effects.Beam beam;
		final float damage;
		final float coinPower;
		final Set<Integer> chainHits;
		/** Each enemy's health when the beam first reached it, and the health the beam has taken from it since. */
		final Map<Integer, Float> health = new HashMap<>();
		final Map<Integer, Float> taken = new HashMap<>();
		int next;
		double wait;
		boolean done;

		RailShot(List<Hitscan.Hit> hits, Effects.Beam beam, float damage, float coinPower, Set<Integer> chainHits) {
			this.hits = hits;
			this.beam = beam;
			this.damage = damage;
			this.coinPower = coinPower;
			this.chainHits = chainHits;
		}
	}

	private void updateRailShots(double dt, Level level, LocalPlayer player) {
		for (RailShot shot : List.copyOf(this.railShots)) {
			shot.wait -= dt;
			advance(shot, level, player);
		}
		this.railShots.removeIf(shot -> shot.done);
	}

	/** Lands the shot's hits that are due, until one stops time; once they're all in, the beam fades. */
	private void advance(RailShot shot, Level level, LocalPlayer player) {
		while (shot.wait <= 0 && shot.next < shot.hits.size() && this.hitstopLeft <= 0) {
			shot.wait += railHit(shot, shot.hits.get(shot.next++), level, player);
		}
		if (shot.next >= shot.hits.size() && !shot.done) {
			shot.done = true;
			this.effects.fade(shot.beam);
		}
	}

	/** One of a railcannon beam's hits, and how long until the next. */
	private double railHit(RailShot shot, Hitscan.Hit hit, Level level, LocalPlayer player) {
		switch (hit.kind()) {
			case ENTITY -> {
				LivingEntity target = hit.entity();
				int id = target.getId();
				float health = shot.health.computeIfAbsent(id, i -> target.getHealth() + target.getAbsorptionAmount());
				float taken = shot.taken.getOrDefault(id, 0F);
				if (taken >= health) {
					// Most likely dead already: the server only applies the hit if it isn't.
					ClientPlayNetworking.send(new WeaponHitPayload(id, shot.damage, hit.head(), hit.position()));
					return RAIL_STEP_DEAD;
				}
				shot.taken.put(id, taken + shot.damage * ServerCombat.HEALTH_PER_DAMAGE);
				this.conductor.electrify(target, conductorWorld(level, player), this);
				damage(level, target, shot.damage, hit.head(), hit.position());
				this.hitstopLeft = Math.max(this.hitstopLeft, RAIL_HITSTOP);
				ClientPlayNetworking.send(new HitstopPayload((int)(RAIL_HITSTOP * 1000)));
				UltraMovement movement = UltraController.INSTANCE.movement();
				if (movement != null) {
					movement.shake(1);
				}
				return RAIL_STEP;
			}
			case COIN -> hit.coin().hit(Float.isNaN(shot.coinPower) ? hit.coin().power : shot.coinPower, shot.chainHits, Effects.BeamStyle.RAILCANNON);
			case GRENADE -> grenadeShot(hit.grenade());
			case BLOCK -> {
				if (hit.face() != null) {
					this.effects.impact(level, hit.position());
				}
				hitBlock(hit.block(), shot.damage * RAIL_HITS);
			}
			default -> {
			}
		}
		return 0;
	}

	/**
	 * The Malicious railcannon's beam ("Railcannon Beam Malicious", a RevolverBeam with one hit): a 0.4 unit sphere
	 * along the aim stops at the first enemy, coin, core or rocket in its way, else at the wall, and its hit
	 * particle, an explosion, goes off there. A coin fires it on instead. A core or rocket goes off twice as big,
	 * ultraboosting the player, and the beam's own explosion then spares the player.
	 *
	 * @param added damage coins have added to the beam, which its explosion gets 20 times over
	 */
	private void maliciousBeam(Level level, LocalPlayer player, Vec3 from, Vec3 direction, Vec3 drawFrom, float added, float coinPower, Set<Integer> chainHits) {
		Hitscan.Hit wall = Hitscan.cast(level, player, from, direction, RANGE, List.of()).getLast();
		List<Hitscan.Hit> hits = Hitscan.sweep(level, player, from, wall.position(), MALICIOUS_RADIUS, this.coins, grenades());
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			movement.shake(2);
		}
		Hitscan.Hit hit = hits.isEmpty() ? wall : hits.getFirst();
		beam(drawFrom, hit.position(), Effects.BeamStyle.MALICIOUS);
		Vec3 back = direction.scale(-1);
		switch (hit.kind()) {
			case COIN -> hit.coin().hit(Float.isNaN(coinPower) ? hit.coin().power : coinPower, chainHits, Effects.BeamStyle.MALICIOUS);
			case ENTITY -> {
				damage(level, hit.entity(), RAIL_DAMAGE + added, hit.head(), hit.position());
				if (movement != null) {
					movement.shake(0.5);
				}
				maliciousExplosion(hit.position(), back, added, false);
			}
			case GRENADE -> {
				grenadeNuke(hit.grenade());
				maliciousExplosion(hit.position(), back, added, !(hit.grenade() instanceof Cannonball));
			}
			case BLOCK -> {
				this.effects.impact(level, hit.position());
				hitBlock(hit.block(), RAIL_DAMAGE + added);
				maliciousExplosion(hit.position(), hit.face() != null ? hit.face().getUnitVec3() : back, added, false);
			}
			default -> {
			}
		}
	}

	private void maliciousExplosion(Vec3 at, Vec3 facing, float added, boolean sparesPlayer) {
		explode(EXPLOSION_MALICIOUS, at, facing, false, MALICIOUS_BLOCK_POWER, settings -> {
			Explosion.Settings set = settings.withAddedDamage(added);
			return sparesPlayer ? set.enemiesOnly() : set;
		}, MALICIOUS_PITCH);
	}

	/**
	 * RevolverBeam's Malicious railcannon beam on a grenade: the parry flash, and it goes off twice as big,
	 * ultraboosting: a core as a super explosion, a rocket as a big explosion. A cannonball explodes as it does for
	 * any shot.
	 */
	private void grenadeNuke(Grenade grenade) {
		if (grenade.removed) {
			return;
		}
		parryFlash();
		ClientPlayNetworking.send(new HitstopPayload((int)(PARRY_HITSTOP * 1000)));
		if (grenade instanceof Cannonball ball) {
			explodeCannonball(ball);
			return;
		}
		if (grenade instanceof Rocket rocket) {
			explodeRocket(rocket, true, false, false, NUKE_SIZE, true, false);
			return;
		}
		grenade.removed = true;
		explode(EXPLOSION_SUPER, grenade.position, null, false, SUPER_BLOCK_POWER, settings -> settings.ultraboosted(NUKE_SIZE));
	}

	@Override
	public void drillImpact(LivingEntity enemy, float damage, Vec3 at) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), damage, false, at));
		if (minecraft.level != null) {
			bleed(minecraft.level, at, Gore.of(false, damage, false), 1);
		}
	}

	/** A drill tick: the enemy bleeds, twice the usual size. */
	@Override
	public void drillTick(LivingEntity enemy, Vec3 at) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), Drill.TICK_DAMAGE, false, at, WeaponHitPayload.Kind.DRILL));
		if (minecraft.level != null) {
			bleed(minecraft.level, at, Gore.SMALL, ServerCombat.DRILL_BLOOD_SCALE);
		}
	}

	@Override
	public void drillPunched(LivingEntity enemy, float damage, Vec3 at) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPlayNetworking.send(new WeaponHitPayload(enemy.getId(), damage, false, at, WeaponHitPayload.Kind.DRILL_PUNCH));
		if (minecraft.level != null) {
			bleed(minecraft.level, at, Gore.of(false, damage, false), 1);
		}
	}

	private void bleed(Level level, Vec3 at, Gore gore, float scale) {
		this.effects.blood(level, at, gore, scale);
		this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
	}

	@Override
	public void drillBlock(BlockPos block, float damage) {
		hitBlock(block, damage);
	}

	/** TimeBomb.Beep: the beeper flashes at full size on the drill (its size, on the drill's scale), with a beep. */
	@Override
	public void drillBeep(Drill drill) {
		UltraModel model = UltraModel.get(DRILL);
		if (model == null) {
			return;
		}
		drill.beeperScale = model.script("timeBomb").get("beeperSize").getAsDouble() * Drill.MODEL_SCALE;
		this.sounds.once().update("drill_beep", 1, model.script("timeBomb").get("beeperPitch").getAsDouble(), drill.position);
	}

	/** The drill breaks: its sparks and break sound (Harpoon's breakEffect), and its puff of debris (TimeBomb's explosion). */
	@Override
	public void drillBroke(Drill drill) {
		this.effects.particles("chainsaw_break", null, drill.position);
		this.effects.particles("drill_break", null, drill.position);
		this.sounds.play("chainsaw_break", 1, this.sounds.randomPitch(0.9F, 1.1F));
		this.sounds.play("chainsaw_spark", 1, this.sounds.randomPitch(0.85F, 1.15F));
		this.sounds.once().update("drill_break", 1, this.sounds.randomPitch(0.9F, 1.1F), drill.position);
	}

	/** The last drill stuck in the enemy, for a Feedbacker punch on it to send on (Punch.ActiveFrame). */
	private @Nullable Drill drillIn(int entityId) {
		Minecraft minecraft = Minecraft.getInstance();
		Entity enemy = minecraft.level == null ? null : minecraft.level.getEntity(entityId);
		if (enemy == null) {
			return null;
		}
		for (int i = this.drills.size() - 1; i >= 0; i--) {
			if (this.drills.get(i).stuckIn(enemy)) {
				return this.drills.get(i);
			}
		}
		return null;
	}

	/**
	 * Coin.ReflectRevolver with a railcannon's beam: the coin fires the beam again, at the nearest coin it can see,
	 * else a core or rocket, else the nearest enemy's head, else anywhere. Going on to anything but a coin, it hits harder by a
	 * quarter of the coin's power.
	 */
	private void railPassOn(Coin coin, Level level, LocalPlayer player) {
		Coin next = this.coins.stream()
			.filter(c -> c != coin && c.hittable() && Hitscan.clear(level, player, coin.position, c.position))
			.min(Comparator.comparingDouble(c -> c.position.distanceToSqr(coin.position)))
			.orElse(null);
		Vec3 target = null;
		float added = coin.power / 4;
		if (next != null) {
			target = next.position;
			added = 0;
		} else {
			Grenade grenade = grenadeTarget(coin, level, player);
			if (grenade != null) {
				target = grenade.hitCenter();
			} else {
				LivingEntity enemy = coinTarget(coin, level, player);
				if (enemy != null) {
					coin.chainHits.add(enemy.getId());
					target = enemy.getEyePosition();
				}
			}
		}
		Vec3 direction = target != null
			? target.subtract(coin.position).normalize()
			: new Vec3(this.random.nextGaussian(), this.random.nextGaussian(), this.random.nextGaussian()).normalize();
		if (coin.beam == Effects.BeamStyle.RAILCANNON) {
			electricBeam(level, player, coin.position, direction, coin.position, RAIL_DAMAGE + added, coin.power + 1, coin.chainHits);
		} else {
			maliciousBeam(level, player, coin.position, direction, coin.position, added, coin.power + 1, coin.chainHits);
		}
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
		this.whiplash.cancel();
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
		List<Explosion.Settings> blast = Explosion.prefab("explosion_wave_knuckleblaster");
		if (blast.isEmpty()) {
			return;
		}
		Quaternionf rotation = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
		Explosion explosion = new Explosion(blast.getFirst(), position, rotation, look);
		this.explosions.add(explosion);
		ClientPlayNetworking.send(new ExplosionBlocksPayload(position, BLAST_BLOCK_POWER));
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

	/**
	 * Explosion.Collide on the player: it launches them (an ultrabooster twice within 12 units), and hurts them
	 * unless they're dashing.
	 */
	@Override
	public void player(Explosion explosion) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement == null || movement.exploded()) {
			return;
		}
		Explosion.Settings settings = explosion.settings;
		double distance = movement.rootPosition().distanceTo(explosion.origin) / S;
		movement.explosionLaunch(explosion.origin, settings.pushForceMultiplier(), settings.maxSize(), settings.rocketExplosion(), explosion.boosted, settings.fup(), explosion.harmful());
		if (settings.ultrabooster() && distance < 12 && !explosion.boosted) {
			movement.explosionLaunch(explosion.origin, settings.pushForceMultiplier(), settings.maxSize(), settings.rocketExplosion(), false, settings.fup(), explosion.harmful());
		}
		if (explosion.harmful() && !movement.invincible()) {
			int damage = explosion.playerDamage(distance);
			movement.hurtByExplosion(damage);
			ClientPlayNetworking.send(new ExplosionHurtPayload(damage));
		}
	}

	private void onPunchResult(PunchResultPayload result) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraMovement movement = UltraController.INSTANCE.movement();
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		// Punch.ActiveFrame: the Feedbacker sends on a drill stuck in the enemy it hits, with the parry flash.
		Drill drill = result.result() == PunchResultPayload.Result.ENEMY && this.arm == FistType.STANDARD ? drillIn(result.entityId()) : null;
		if (drill != null && movement != null && minecraft.player != null) {
			parryFlash();
			ClientPlayNetworking.send(new HitstopPayload((int)(PARRY_HITSTOP * 1000)));
			drill.punched(movement.eyePosition(), minecraft.player.getLookAngle(), this);
		}
		if (arm != null) {
			if (result.result() == PunchResultPayload.Result.PARRY || drill != null) {
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
	 * Shotgun.Shoot: pellets out of the camera, each turned up to 10 degrees about every axis (the Pump Charge's
	 * pumps make them more and wider, and a full pump makes an explosion instead); the point-blank zone; a small
	 * push forwards; the shot sound, a camera shake and a muzzle flash.
	 */
	@Override
	public void shootShotgun(Shotgun.Variant variant, int pumps) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || minecraft.level == null || movement == null) {
			return;
		}
		boolean pump = variant == Shotgun.Variant.PUMP_CHARGE;
		int pellets = pump ? PUMP_PELLETS[pumps] : PELLETS;
		double spread = pump ? PELLET_SPREAD * PUMP_SPREAD[pumps] : PELLET_SPREAD;
		float pitch = pump ? PUMP_PITCH[pumps] : 1;
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 flat = new Vec3(look.x, 0, look.z);
		if (flat.lengthSqr() > 0) {
			movement.addVelocity(flat.normalize());
		}
		pointBlank(minecraft.level, player, eye, look);
		Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
		for (int i = 0; i < pellets; i++) {
			// Transform.Rotate by up to the spread about each axis; turning about its own length changes nothing.
			double up = Math.toRadians(spread(spread));
			double yaw = Math.toRadians(spread(spread));
			Vector3f direction = camera.transform(new Vector3f(
				(float)(Math.cos(up) * Math.sin(yaw)), (float)-Math.sin(up), (float)(-Math.cos(up) * Math.cos(yaw))
			));
			this.pellets.add(new Pellet(eye, new Vec3(direction.x, direction.y, direction.z), Pellet.SPEED + spread(Pellet.SPEED_RANDOMIZER)));
		}
		if (pump && pumps == Shotgun.MAX_PUMPS) {
			overpump(minecraft.level, player, eye, look);
		}
		this.sounds.play("shotgun_shoot", 0.45F, this.sounds.randomPitch(pitch - 0.05F, pitch + 0.05F));
		movement.stopShake();
		movement.shake(1);
		FirstPersonWeapons.Weapon weapon = weapon(variant);
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon);
		if (animator != null) {
			animator.setTrigger(pump ? "PumpFire" : "Fire");
		}
		muzzleFlash(weapon);
	}

	/**
	 * The overpumped shot: the shotgun's explosion a unit ahead of the camera (or just short of a wall within
	 * it), half again as big, and with 50 damage that enemies take in full.
	 */
	private void overpump(Level level, LocalPlayer player, Vec3 eye, Vec3 look) {
		Vec3 reach = eye.add(look.scale(OVERPUMP_REACH));
		BlockHitResult wall = level.clip(new ClipContext(eye, reach, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 at = wall.getType() == HitResult.Type.MISS
			? reach
			: eye.add(look.scale(Math.max(eye.distanceTo(wall.getLocation()) - 0.1 * S, 0)));
		explode(EXPLOSION, at, look, false, OVERPUMP_BLOCK_POWER, settings -> settings.overridden(OVERPUMP_SIZE, OVERPUMP_DAMAGE, 1));
	}

	/** Shotgun.Pump: the gun's pump animation. */
	@Override
	public void pump(Shotgun.Variant variant) {
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon(variant));
		if (animator != null) {
			animator.setTrigger("Pump");
		}
	}

	private static FirstPersonWeapons.Weapon weapon(Shotgun.Variant variant) {
		return switch (variant) {
			case CORE_EJECT -> FirstPersonWeapons.Weapon.SHOTGUN_CORE_EJECT;
			case PUMP_CHARGE -> FirstPersonWeapons.Weapon.SHOTGUN_PUMP_CHARGE;
			case SAWED_ON -> FirstPersonWeapons.Weapon.SHOTGUN_SAWED_ON;
		};
	}

	// ---- slam -----------------------------------------------------------------------------------

	/**
	 * GroundCheck: while slamming, every enemy within 1.25 units of the 3 units below the ground check (and not
	 * behind a wall) is hit once, and a living one can be bounced off. Landing a power slam sends out its
	 * shockwave, with a thud and bits of the floor.
	 */
	private void slam(Level level, LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement == null) {
			return;
		}
		if (movement.slamming()) {
			Vec3 from = movement.groundCheck();
			Vec3 to = from.add(0, -SLAM_REACH, 0);
			AABB area = new AABB(from, to).inflate(SLAM_RADIUS);
			for (Entity entity : level.getEntities(player, area, ThrownSaw::isEnemy)) {
				AABB box = entity.getBoundingBox();
				if (this.slammed.contains(entity.getId()) || !nearSegment(box, from, to, SLAM_RADIUS)) {
					continue;
				}
				Vec3 above = from.add(0, S, 0);
				if (level.clip(new ClipContext(above, box.getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) {
					continue;
				}
				this.slammed.add(entity.getId());
				Vec3 at = box.getCenter();
				ClientPlayNetworking.send(new SlamHitPayload(entity.getId(), at));
				Gore gore = Gore.of(false, SLAM_DAMAGE, false);
				this.effects.blood(level, at, gore);
				this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
				movement.slammedEnemy();
			}
		} else {
			this.slammed.clear();
		}
		Vec3 impact = movement.takeLandingImpact();
		if (impact != null) {
			this.landingDusts.add(new LandingDust(impact));
		}
		UltraMovement.PowerSlam powerSlam = movement.takePowerSlam();
		UltraModel model = UltraModel.get(SHOCKWAVE);
		if (powerSlam != null && model != null) {
			this.shockwaves.add(Shockwave.slam(model, powerSlam.position(), powerSlam.slamForce()));
			this.sounds.play(SHOCKWAVE, 1, 1);
			movement.enviroGibs(SLAM_GIBS);
		}
	}

	/** Whether a box comes within {@code radius} of the segment from {@code from} to {@code to}. */
	static boolean nearSegment(AABB box, Vec3 from, Vec3 to, double radius) {
		int samples = Math.max(1, (int)Math.ceil(from.distanceTo(to) / (0.25 * S)));
		for (int i = 0; i <= samples; i++) {
			Vec3 point = from.lerp(to, i / (double)samples);
			double x = Math.clamp(point.x, box.minX, box.maxX) - point.x;
			double y = Math.clamp(point.y, box.minY, box.maxY) - point.y;
			double z = Math.clamp(point.z, box.minZ, box.maxZ) - point.z;
			if (x * x + y * y + z * z <= radius * radius) {
				return true;
			}
		}
		return false;
	}

	/** PhysicalShockwave on an enemy: no damage, just the push up. */
	@Override
	public void shockwaveEnemy(LivingEntity enemy, Vec3 push) {
		ClientPlayNetworking.send(new ExplosionHitPayload(enemy.getId(), 0, push));
	}

	// ---- chainsaw -------------------------------------------------------------------------------

	/**
	 * The Sawed-On's chainsaw: it idles on the gun while charged; revving, its zone hits enemies in front of the
	 * gun, and it grinds walls the aim meets close by.
	 */
	private void saw(double dt, Level level, LocalPlayer player, @Nullable Shotgun shotgun) {
		boolean sawedOn = shotgun != null && shotgun.variant == Shotgun.Variant.SAWED_ON;
		if (sawedOn && shotgun.sawAttached()) {
			this.sawIdle.update("chainsaw_idle", 1, 1);
		} else {
			this.sawIdle.stop();
		}
		boolean sawing = sawedOn && shotgun.sawing();
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (!sawing || movement == null) {
			this.sawZone.clear();
			stopGrinding();
			return;
		}
		UltraModel model = UltraModel.get(SAWED_ON);
		JsonObject script = model == null ? null : model.script("shotgun");
		if (script == null || !script.has("sawZone")) {
			return;
		}
		JsonObject zone = script.getAsJsonObject("sawZone");
		Matrix4f bone = FirstPersonWeapons.INSTANCE.weaponBoneMatrix(FirstPersonWeapons.Weapon.SHOTGUN_SAWED_ON, zone.get("bone").getAsInt());
		if (bone != null) {
			// The trigger's capsule: its height along one of the bone's axes, its radius scaled by the larger of the other two.
			JsonArray c = zone.getAsJsonArray("center");
			Vector3f center = bone.transformPosition(c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat(), new Vector3f());
			int direction = zone.get("direction").getAsInt();
			Vector3f axis = bone.getColumn(direction, new Vector3f());
			float across = Math.max(bone.getColumn((direction + 1) % 3, new Vector3f()).length(), bone.getColumn((direction + 2) % 3, new Vector3f()).length());
			double radius = zone.get("radius").getAsDouble() * across;
			double half = Math.max(zone.get("height").getAsDouble() / 2 * axis.length() - radius, 0);
			axis.normalize((float)half);
			Quaternionf camera = new Quaternionf(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
			Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
			Vec3 from = world(camera, new Vector3f(center).sub(axis)).scale(S).add(eye);
			Vec3 to = world(camera, new Vector3f(center).add(axis)).scale(S).add(eye);
			this.sawZone.update(dt, level, player, from, to, radius * S, zone.get("hurtCooldown").getAsDouble(), this);
		}
		grind(dt, level, player, movement, zone.get("hurtCooldown").getAsDouble(), zone.get("enemyDamage").getAsFloat());
	}

	/**
	 * HurtZone on the chainsaw: the hit lands where the aim meets the enemy (or its nearest point), with a grind
	 * and a small camera shake. Hits that don't kill wear the chainsaw.
	 */
	@Override
	public void sawHit(LivingEntity enemy) {
		Minecraft minecraft = Minecraft.getInstance();
		UltraModel model = UltraModel.get(SAWED_ON);
		Shotgun shotgun = this.shotguns.get(Shotgun.Variant.SAWED_ON);
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (minecraft.level == null || minecraft.player == null || model == null || movement == null) {
			return;
		}
		float damage = model.script("shotgun").getAsJsonObject("sawZone").get("enemyDamage").getAsFloat();
		Vec3 eye = movement.eyePosition();
		AABB box = enemy.getBoundingBox();
		Vec3 at = box.clip(eye, eye.add(minecraft.player.getLookAngle().scale(SAW_AIM_REACH)))
			.orElseGet(() -> new Vec3(Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(eye.z, box.minZ, box.maxZ)));
		movement.shake(0.2);
		this.sounds.play("chainsaw_hit", 1, this.sounds.randomPitch(0.75F, 1.25F));
		this.sounds.play("chainsaw_spark", 1, this.sounds.randomPitch(0.75F, 1.25F));
		boolean survives = enemy.getHealth() > damage * ServerCombat.HEALTH_PER_DAMAGE;
		shotgunHit(minecraft.level, enemy, damage, at);
		if (survives) {
			shotgun.sawHit();
		}
	}

	/**
	 * The revving chainsaw on a wall within reach of the aim: sparks, a grinding sound, a shake, and bits of the
	 * wall. It hits the block as it hits enemies, straight away and again every {@code cooldown} seconds.
	 */
	private void grind(double dt, Level level, LocalPlayer player, UltraMovement movement, double cooldown, float damage) {
		Vec3 eye = movement.eyePosition();
		BlockHitResult wall = level.clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(SAW_WALL_REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (wall.getType() == HitResult.Type.MISS) {
			stopGrinding();
			return;
		}
		if (this.sinceSawSparks < 0 || this.sinceSawSparks >= SAW_SPARK_INTERVAL) {
			this.effects.particles(SAWED_ON, "ChainsawSparksContinuous", wall.getLocation());
			this.sinceSawSparks = 0;
		}
		this.sinceSawSparks += dt;
		if (this.sinceSawWallHit < 0 || this.sinceSawWallHit >= cooldown) {
			hitBlock(wall.getBlockPos(), damage);
			this.sinceSawWallHit = 0;
		}
		this.sinceSawWallHit += dt;
		this.sawGrind.update("chainsaw_grind", 1, 1);
		movement.shake(0.1);
		this.sinceSawGibs += dt;
		if (this.sinceSawGibs > SAW_GIB_INTERVAL && level instanceof ClientLevel client) {
			client.addBreakingBlockEffects(wall.getBlockPos(), wall.getDirection(), false);
			this.sinceSawGibs = 0;
		}
	}

	private void stopGrinding() {
		this.sawGrind.stop();
		this.sinceSawSparks = -1;
		this.sinceSawWallHit = -1;
	}

	/**
	 * Shotgun.ShootSaw: thrown, the chainsaw goes with the throw's sounds (the gun's louder the harder it's
	 * thrown); either way the gun plays its shot without reloading and the camera shakes.
	 */
	@Override
	public void shootSaw(Shotgun.Variant variant, double force, boolean thrown) {
		if (thrown) {
			throwSaw(force);
			double share = force / Shotgun.MAX_FORCE;
			this.sounds.play("shotgun_core_throw", (float)(0.45 * Math.sqrt(1 - share * share)), this.sounds.randomPitch(0.95F, 1.05F));
			this.sounds.play("shotgun_shoot", (float)(0.45 * Math.max(0.5, share)), this.sounds.randomPitch(0.75F, 0.85F));
		}
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon(variant));
		if (animator != null) {
			animator.play("FireNoReload", 0);
		}
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			movement.shake(1);
		}
	}

	/**
	 * Shotgun.ShootSaw's chainsaw: half a unit out of the camera (or as far as a wall allows), thrown along the aim
	 * at 15 to 105 units a second with the charge.
	 */
	private void throwSaw(double force) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		UltraModel model = UltraModel.get(THROWN_SAW);
		if (player == null || minecraft.level == null || movement == null || model == null) {
			return;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 reach = eye.add(look.scale(0.5 * S));
		BlockHitResult wall = minecraft.level.clip(new ClipContext(eye, reach, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 at = wall.getType() == HitResult.Type.MISS ? reach : eye.add(look.scale(Math.max(eye.distanceTo(wall.getLocation()) - 0.5 * S, 0)));
		ThrownSaw saw = new ThrownSaw(model.script("chainsaw"), at, this.sounds.loop());
		saw.launch(minecraft.level, player, look.scale((force + 10) * 1.5), this);
		this.saws.add(saw);
	}

	/** The thrown chainsaws and the sawblades, with their spinning sound where each is. */
	private void updateSaws(double dt, Level level, LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		Vec3 eye = movement != null ? movement.eyePosition() : player.getEyePosition();
		Vec3 look = player.getLookAngle();
		for (ThrownSaw saw : List.copyOf(this.saws)) {
			if (!saw.removed) {
				saw.update(dt, level, player, eye, look, this);
			}
			if (saw.removed) {
				saw.spin.stop();
			} else {
				saw.spin.update("chainsaw_spin", saw.spinVolume(), saw.spinPitch(), saw.position);
			}
		}
		this.saws.removeIf(saw -> saw.removed);
		for (Sawblade sawblade : List.copyOf(this.sawblades)) {
			if (!sawblade.removed) {
				sawblade.update(dt, level, player, this);
			}
			if (sawblade.removed) {
				sawblade.spin.stop();
			} else {
				sawblade.spin.update("chainsaw_spin", SAWBLADE_SPIN_VOLUME, 1, sawblade.position);
			}
		}
		this.sawblades.removeIf(sawblade -> sawblade.removed);
	}

	/**
	 * Where the chain starts: the chainsaw's place on the Sawed-On while it's held, or else the player
	 * (Shotgun.OnEnable and OnDisable).
	 */
	private @Nullable Vec3 chainStart() {
		Minecraft minecraft = Minecraft.getInstance();
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (this.saws.isEmpty() || movement == null) {
			return null;
		}
		if (this.held == FirstPersonWeapons.Weapon.SHOTGUN_SAWED_ON) {
			FirstPersonWeapons.Frame point = FirstPersonWeapons.INSTANCE.weaponBone(this.held, "ChainSawAttachPoint");
			if (point != null) {
				Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
				return world(camera, point.position()).scale(S).add(minecraft.gameRenderer.mainCamera().position());
			}
		}
		return movement.rootPosition();
	}

	/** Chainsaw.DamageEnemy: the hit, with a grind. */
	@Override
	public void sawEnemy(LivingEntity enemy) {
		sawHitEnemy(enemy, sawScript(THROWN_SAW, "chainsaw").get("damage").getAsFloat());
	}

	/** Nail.DamageEnemy for a sawblade: twice the damage while punched. */
	@Override
	public void sawbladeEnemy(LivingEntity enemy, boolean punched) {
		sawHitEnemy(enemy, sawScript(SAWBLADE, "sawblade").get("damage").getAsFloat() * (punched ? 2 : 1));
	}

	@Override
	public void sawWall(BlockPos block) {
		hitBlock(block, sawScript(THROWN_SAW, "chainsaw").get("damage").getAsFloat());
	}

	@Override
	public void sawbladeWall(BlockPos block, boolean punched) {
		hitBlock(block, sawScript(SAWBLADE, "sawblade").get("damage").getAsFloat() * (punched ? 2 : 1));
	}

	private void sawHitEnemy(LivingEntity enemy, float damage) {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		this.sounds.play("chainsaw_hit", 1, this.sounds.randomPitch(0.75F, 1.25F));
		this.sounds.play("chainsaw_spark", 1, this.sounds.randomPitch(0.75F, 1.25F));
		shotgunHit(level, enemy, damage, enemy.getBoundingBox().getCenter());
	}

	private static JsonObject sawScript(String model, String script) {
		return UltraModel.get(model).script(script);
	}

	@Override
	public void sawRicochet(Vec3 at, Direction face, boolean sound) {
		sawRicochet(at, face, sound, true);
	}

	/** A chainsaw or sawblade off a wall: sparks out of it, their ricochet, and bits of the wall. */
	@Override
	public void sawRicochet(Vec3 at, Direction face, boolean sound, boolean gibs) {
		Minecraft minecraft = Minecraft.getInstance();
		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		this.effects.particles("chainsaw_ricochet", null, at, Effects.facing(normal));
		if (sound) {
			this.sounds.play("chainsaw_spark", 1, this.sounds.randomPitch(0.85F, 1.15F));
		}
		if (gibs && minecraft.level instanceof ClientLevel client) {
			client.addBreakingBlockEffects(BlockPos.containing(at.subtract(normal.scale(0.01))), face, false);
		}
	}

	/** The chain snapped: the chainsaw flies on as a sawblade. */
	@Override
	public void sawSnapped(ThrownSaw saw) {
		turnIntoSawblade(saw, saw.heading().scale(ThrownSaw.PUNCH_SPEED));
	}

	/** Chainsaw.TurnIntoSawblade: its sawblade carries on at the given velocity, with the chain's snap. */
	private void turnIntoSawblade(ThrownSaw saw, Vec3 velocity) {
		saw.removed = true;
		saw.spin.stop();
		UltraModel model = UltraModel.get(SAWBLADE);
		if (model != null) {
			this.sawblades.add(new Sawblade(model.script("sawblade"), saw.position, velocity, this.sounds.loop()));
		}
		this.sounds.play("chainsaw_snap", 0.5F, 1);
	}

	/** Nail.SawBreak: the sawblade breaks apart. */
	@Override
	public void sawbladeBroke(Sawblade sawblade) {
		sawblade.spin.stop();
		this.effects.particles("chainsaw_break", null, sawblade.position);
		this.sounds.play("chainsaw_break", 1, this.sounds.randomPitch(0.56F, 0.76F));
		this.sounds.play("chainsaw_spark", 1, this.sounds.randomPitch(0.85F, 1.15F));
	}

	/** The Feedbacker on thrown chainsaws, or else on sawblades. */
	private boolean punchSaws(LocalPlayer player) {
		return punchChainsaws(player) || punchSawblades(player);
	}

	/**
	 * Punch.TryParryProjectile and ChainsawPunchRoutine: every thrown chainsaw at the camera or along the aim
	 * within the punch's reach goes back out from just ahead of the camera, towards what the player aims at.
	 */
	private boolean punchChainsaws(LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		Level level = Minecraft.getInstance().level;
		if (movement == null || level == null || this.saws.isEmpty()) {
			return false;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		List<ThrownSaw> punched = new ArrayList<>();
		for (ThrownSaw saw : this.saws) {
			OrientedBox box = saw.box();
			if (!saw.removed && (box.distanceTo(eye) <= SAW_PUNCH_TOUCH || box.clip(eye, look, PUNCH_REACH).isPresent() || box.inflated(SAW_PUNCH_BOX).clip(eye, look, PUNCH_REACH).isPresent())) {
				punched.add(saw);
			}
		}
		if (punched.isEmpty()) {
			return false;
		}
		Vec3 target = parryLookTarget(level, player, eye, look);
		for (ThrownSaw saw : punched) {
			saw.punched(eye, look, target);
		}
		this.punchStamina = 2;
		sawPunchFeedback();
		return true;
	}

	/** Punch.ActiveFrame on sawblades that have bounced, in a sphere ahead: on towards what the player aims at. */
	private boolean punchSawblades(LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		Level level = Minecraft.getInstance().level;
		if (movement == null || level == null || this.sawblades.isEmpty()) {
			return false;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 centre = eye.add(look.scale(SAWBLADE_PUNCH_RADIUS));
		Vec3 target = parryLookTarget(level, player, eye, look);
		boolean any = false;
		for (Sawblade sawblade : this.sawblades) {
			if (!sawblade.removed && sawblade.punchable && sawblade.box().distanceTo(centre) <= SAWBLADE_PUNCH_RADIUS) {
				sawblade.punched(target);
				any = true;
			}
		}
		if (any) {
			sawPunchFeedback();
		}
		return any;
	}

	/** The punch's specialHit: its sound, a short hitstop and the hook. */
	private void sawPunchFeedback() {
		this.sounds.play("parry_hit", 1, 1);
		this.hitstopLeft = Math.max(this.hitstopLeft, PUNCH_HITSTOP);
		ClientPlayNetworking.send(new HitstopPayload((int)(PUNCH_HITSTOP * 1000)));
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null) {
			arm.play("Hook", 0.065);
		}
	}

	/**
	 * Punch.ActiveFrame for the Knuckleblaster: a thrown chainsaw at the camera, or else the first along the aim,
	 * is knocked off its chain from just ahead of the camera towards what the player aims at, with a parry flash.
	 */
	private boolean detachSaw(LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		Level level = Minecraft.getInstance().level;
		if (movement == null || level == null || this.saws.isEmpty()) {
			return false;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		ThrownSaw found = null;
		double nearest = Double.MAX_VALUE;
		for (ThrownSaw saw : this.saws) {
			if (saw.removed) {
				continue;
			}
			OrientedBox box = saw.box();
			if (box.distanceTo(eye) <= SAW_HEAVY_PUNCH_TOUCH) {
				found = saw;
				break;
			}
			OptionalDouble along = box.clip(eye, look, PUNCH_REACH);
			if (along.isEmpty()) {
				along = box.inflated(SAW_PUNCH_BOX).clip(eye, look, PUNCH_REACH);
			}
			if (along.isPresent() && along.getAsDouble() < nearest) {
				nearest = along.getAsDouble();
				found = saw;
			}
		}
		if (found == null) {
			return false;
		}
		Vec3 toward = parryLookTarget(level, player, eye, look).subtract(eye).normalize();
		this.punchStamina = 2;
		found.position = eye.add(toward.scale(S));
		parryFlash();
		ClientPlayNetworking.send(new HitstopPayload((int)(PARRY_HITSTOP * 1000)));
		turnIntoSawblade(found, toward.scale(ThrownSaw.PUNCH_SPEED));
		return true;
	}

	/** The chainsaw's break effect where its blade is: a screech, sparks and their ricochet. */
	@Override
	public void sawBreaks(Shotgun.Variant variant, double shake) {
		Minecraft minecraft = Minecraft.getInstance();
		FirstPersonWeapons.Frame blade = FirstPersonWeapons.INSTANCE.weaponBone(weapon(variant), "Chainsaw_Blade");
		if (blade != null) {
			Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
			this.effects.particles("chainsaw_break", null, world(camera, blade.position()).scale(S).add(minecraft.gameRenderer.mainCamera().position()));
		}
		this.sounds.play("chainsaw_break", 1, this.sounds.randomPitch(0.56F, 0.76F));
		this.sounds.play("chainsaw_spark", 1, this.sounds.randomPitch(0.85F, 1.15F));
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null && shake > 0) {
			movement.shake(shake);
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
			explode(EXPLOSION, pellet.position, pellet.direction(), true, CORE_BLOCK_POWER);
			return;
		}
		shotgunHit(level, enemy, PELLET_DAMAGE, pellet.position);
		bulletSpark(pellet.position);
	}

	@Override
	public void wall(Pellet pellet, BlockPos block) {
		if (pellet.boosted) {
			explode(EXPLOSION, pellet.position, pellet.direction(), true, CORE_BLOCK_POWER);
		} else {
			bulletSpark(pellet.position);
			hitBlock(block, PELLET_DAMAGE);
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

	/** "Explosion": the standard explosion, where a core or a boosted projectile blew up. */
	static final String EXPLOSION = "explosion";
	/** "Explosion Super": a core shot with a revolver. */
	static final String EXPLOSION_SUPER = "explosion_super";
	/** The Screwdriver railcannon's drill. */
	private static final String DRILL = "drill";
	/** "Explosion Malicious Railcannon": where the Malicious railcannon's beam hit. */
	static final String EXPLOSION_MALICIOUS = "explosion_malicious_railcannon";
	/** Its RandomPitch: half pitch (baked into its sound), a fifth of that either way. */
	private static final float[] MALICIOUS_PITCH = {0.8F, 1.2F};
	/** The rocket launcher's rocket. */
	private static final String ROCKET = "rocket";
	/** "Explosion Rocket Harmless": where a rocket hit a wall. */
	private static final String EXPLOSION_ROCKET_HARMLESS = "explosion_rocket_harmless";
	/** The rocket launcher's muzzle flash ("RocketLauncherFire"). */
	private static final String ROCKET_LAUNCHER_MUZZLE_FLASH = "rocket_launcher_muzzle_flash";
	/** "Explosion Big": a cannonball's interruptionExplosion, where a shot set it off. */
	private static final String EXPLOSION_CANNONBALL = "explosion_cannonball";
	/** RocketLauncher.ShootCannonball: the cannonball's speed uncharged, and fully charged (units a second). */
	private static final double CANNONBALL_MIN_SPEED = 15;
	private static final double CANNONBALL_MAX_SPEED = 150;
	/** A cannonball's breakEffect shakes the camera this much (its CameraShake). */
	private static final double CANNONBALL_BREAK_SHAKE = 0.75;
	/** Punches find a cannonball along the aim with a box this much wider than a ray (half its size). */
	private static final double CANNONBALL_PUNCH_BOX = 0.3 * S;
	/**
	 * A cannonball the Feedbacker launches toward something within this far of it starts where the aim meets an
	 * enemy or a wall within this far of the camera.
	 */
	private static final double CANNONBALL_PUNCH_NEAR = 10 * S;
	private static final double CANNONBALL_PUNCH_PLACE = 5 * S;
	/**
	 * The Knuckleblaster's smash of a cannonball: its box's half width, half height and half depth, how far along the
	 * aim it's swept, the push it gives the enemies in it (DeliverDamage's force of 50000, units a second) and how
	 * fast it launches the player for each unit down they look.
	 */
	private static final double SMASH_HALF_WIDTH = 5 * S;
	private static final double SMASH_HALF_HEIGHT = 1 * S;
	private static final double SMASH_HALF_DEPTH = 1 * S;
	private static final double SMASH_REACH = 20 * S;
	private static final double SMASH_PUSH = 50;
	private static final double SMASH_LAUNCH = 42;
	/** A core or rocket the Malicious railcannon shoots goes off this many times as big. */
	private static final double NUKE_SIZE = 2;
	/** RevolverBeam sweeps a single-target beam, as the Malicious railcannon's is, with a sphere this big. */
	private static final double MALICIOUS_RADIUS = 0.4 * S;
	/**
	 * The powers of the Minecraft explosions that break blocks for each of the player's: a ghast's fireball is 1,
	 * a creeper 3 and TNT 4.
	 */
	private static final float BLAST_BLOCK_POWER = 1;
	private static final float CORE_BLOCK_POWER = 2;
	private static final float OVERPUMP_BLOCK_POWER = 3;
	private static final float SUPER_BLOCK_POWER = 4;
	private static final float MALICIOUS_BLOCK_POWER = 3.5F;
	private static final float CANNONBALL_BLOCK_POWER = 4;
	/** An explosion's RandomPitch: up to a quarter either way. */
	private static final float[] EXPLOSION_PITCH = {0.75F, 1.25F};

	/**
	 * An explosion prefab where something blew up, facing the way it was going (or the world's way): its
	 * Explosions, each shaking the camera, its sound and its look, and the blocks it breaks as a Minecraft
	 * explosion of {@code blockPower}.
	 */
	private void explode(String prefab, Vec3 at, @Nullable Vec3 facing, boolean boosted, float blockPower) {
		explode(prefab, at, facing, boosted, blockPower, UnaryOperator.identity());
	}

	/** An explosion prefab whose Explosions the weapon setting it off changes first. */
	private void explode(String prefab, Vec3 at, @Nullable Vec3 facing, boolean boosted, float blockPower, UnaryOperator<Explosion.Settings> setUp) {
		explode(prefab, at, facing, boosted, blockPower, setUp, EXPLOSION_PITCH);
	}

	/** Like {@link #explode(String, Vec3, Vec3, boolean, float, UnaryOperator)}, its sound's pitch picked from {@code pitch}'s range. */
	private void explode(String prefab, Vec3 at, @Nullable Vec3 facing, boolean boosted, float blockPower, UnaryOperator<Explosion.Settings> setUp, float[] pitch) {
		UltraModel model = UltraModel.get(prefab);
		if (model == null) {
			return;
		}
		if (blockPower > 0) {
			ClientPlayNetworking.send(new ExplosionBlocksPayload(at, blockPower));
		}
		Quaternionf rotation = facing == null ? new Quaternionf()
			: new Quaternionf().lookAlong((float)facing.x, (float)facing.y, (float)facing.z, 0, 1, 0).conjugate();
		List<Explosion> parts = new ArrayList<>();
		for (Explosion.Settings settings : Explosion.prefab(prefab)) {
			parts.add(new Explosion(setUp.apply(settings), at, rotation, null, boosted));
		}
		this.explosions.addAll(parts);
		this.effects.explosion(new ExplosionLook(model, parts, at, rotation));
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
			double shake = 0;
			for (Explosion explosion : parts) {
				shake = Math.max(shake, explosion.cameraShake(camera));
			}
			movement.shake(shake);
		}
		this.sounds.play(prefab, 1, this.sounds.randomPitch(pitch[0], pitch[1]));
	}

	/**
	 * Shotgun.ShootSinks: a core out of the camera along the aim, tilted up by the charge, at 10 to 70 units a
	 * second and tumbling; the throw sounds (the core's quieter, the gun's louder, the harder it's thrown), a
	 * camera shake and a muzzle flash.
	 */
	@Override
	public void throwCore(Shotgun.Variant variant, double force) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || movement == null) {
			return;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 aim = look.add(0, force * 0.002, 0);
		Quaternionf tumble = new Quaternionf().rotateXYZ(this.random.nextFloat() * 6.2831855F, this.random.nextFloat() * 6.2831855F, this.random.nextFloat() * 6.2831855F);
		Vector3f spin;
		do {
			spin = new Vector3f(this.random.nextFloat() * 2 - 1, this.random.nextFloat() * 2 - 1, this.random.nextFloat() * 2 - 1);
		} while (spin.lengthSquared() > 1);
		this.cores.add(new Core(eye.add(look.scale(0.5 * S)), aim.scale(force + 10), tumble, spin.mul((float)(force * 3))));
		double share = force / Shotgun.MAX_FORCE;
		this.sounds.play("shotgun_core_throw", (float)(0.45 * Math.sqrt(1 - share * share)), this.sounds.randomPitch(0.95F, 1.05F));
		this.sounds.play("shotgun_shoot", (float)(0.45 * share), this.sounds.randomPitch(0.75F, 0.85F));
		movement.shake(1);
		FirstPersonWeapons.Weapon weapon = weapon(variant);
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon);
		if (animator != null) {
			animator.setTrigger("Secondary Fire");
		}
		muzzleFlash(weapon);
	}

	private void muzzleFlash(FirstPersonWeapons.Weapon weapon) {
		Minecraft minecraft = Minecraft.getInstance();
		FirstPersonWeapons.Frame barrel = FirstPersonWeapons.INSTANCE.weaponBone(weapon, "ShootPoint L");
		if (barrel != null) {
			Quaternionf camera = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation());
			Vec3 at = world(camera, barrel.position()).scale(S).add(minecraft.gameRenderer.mainCamera().position());
			this.effects.muzzleFlash(at, new Quaternionf(camera).mul(mirrored(barrel)));
		}
	}

	/** Grenade.Collision: a core that touches a wall or an enemy explodes; an enemy gets a moment's hitstop. */
	@Override
	public void coreLanded(Core core, boolean enemy) {
		if (enemy) {
			this.hitstopLeft = Math.max(this.hitstopLeft, GRENADE_HITSTOP);
			ClientPlayNetworking.send(new HitstopPayload((int)(GRENADE_HITSTOP * 1000)));
		}
		explode(EXPLOSION, core.position, null, false, CORE_BLOCK_POWER);
	}

	/** RevolverBeam on a core, rocket or cannonball: the parry flash, and it goes off. */
	private void grenadeShot(Grenade grenade) {
		if (grenade.removed) {
			return;
		}
		parryFlash();
		ClientPlayNetworking.send(new HitstopPayload((int)(PARRY_HITSTOP * 1000)));
		shotExplode(grenade);
	}

	/**
	 * A revolver shot (or a coin's) on a core, rocket or cannonball: a core makes a super explosion, a rocket a big
	 * explosion, and a cannonball its interruption explosion.
	 */
	private void shotExplode(Grenade grenade) {
		if (grenade.removed) {
			return;
		}
		if (grenade instanceof Cannonball ball) {
			explodeCannonball(ball);
			return;
		}
		if (grenade instanceof Rocket rocket) {
			explodeRocket(rocket, true, false, false, 1, false, false);
			return;
		}
		grenade.removed = true;
		explode(EXPLOSION_SUPER, grenade.position, null, false, SUPER_BLOCK_POWER);
	}

	@Override
	public List<Core> cores() {
		return this.cores;
	}

	@Override
	public List<Cannonball> cannonballs() {
		return this.cannonballs;
	}

	/** The player's cores, rockets and cannonballs. */
	@Override
	public List<Grenade> grenades() {
		List<Grenade> grenades = new ArrayList<>(this.cores);
		grenades.addAll(this.rockets);
		grenades.addAll(this.cannonballs);
		return grenades;
	}

		@Override
	public void touched(Explosion explosion) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null && explosion.settings.touchShake() > 0) {
			movement.shake(explosion.settings.touchShake());
		}
	}

	// ---- rocket launcher -------------------------------------------------------------------------

	/**
	 * RocketLauncher.Shoot: the shot (its pitch a tenth either way), the muzzle flash at the barrel, the Fire
	 * animation, a rocket out of the camera along the aim, and a camera shake.
	 */
	@Override
	public void shootRocket(RocketLauncher.Variant variant) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || movement == null) {
			return;
		}
		this.sounds.play("rocket_launcher_shoot", 1, this.sounds.randomPitch(0.9F, 1.1F));
		rocketLauncherFired(weapon(variant));
		Vector3f up = new Quaternionf(minecraft.gameRenderer.mainCamera().rotation()).transform(new Vector3f(0, 1, 0));
		this.rockets.add(new Rocket(movement.eyePosition(), player.getLookAngle(), new Vec3(up.x, up.y, up.z), this.sounds));
		movement.shake(0.75);
	}

	/**
	 * RocketLauncher.ShootCannonball: the shot, lower (0.6 to 0.8), the muzzle flash, the Fire animation and a camera
	 * shake; the cannonball comes out a unit ahead of the camera, along the aim at 15 units a second up to 150 at
	 * full charge, keeping what's left uncharged of the player's own velocity.
	 */
	@Override
	public void shootCannonball(RocketLauncher.Variant variant, double charge) {
		LocalPlayer player = Minecraft.getInstance().player;
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (player == null || movement == null) {
			return;
		}
		this.sounds.play("rocket_launcher_shoot", 1, this.sounds.randomPitch(0.6F, 0.8F));
		rocketLauncherFired(weapon(variant));
		Vec3 look = player.getLookAngle();
		Vec3 velocity = movement.velocity().scale(1 - charge).add(look.scale(Math.max(CANNONBALL_MIN_SPEED, charge * CANNONBALL_MAX_SPEED)));
		this.cannonballs.add(new Cannonball(movement.eyePosition().add(look.scale(S)), velocity, look));
		movement.shake(0.75);
	}

	/** The rocket launcher's muzzle flash at its barrel, and its Fire animation. */
	private void rocketLauncherFired(FirstPersonWeapons.Weapon weapon) {
		FirstPersonWeapons.Placement point = FirstPersonWeapons.INSTANCE.rocketLauncherShootPoint(weapon);
		UltraModel flash = UltraModel.get(ROCKET_LAUNCHER_MUZZLE_FLASH);
		if (point != null && flash != null) {
			this.effects.explosion(new ExplosionLook(flash, List.of(), point.position(), point.rotation()));
		}
		Animator animator = FirstPersonWeapons.INSTANCE.animator(weapon);
		if (animator != null) {
			animator.setTrigger("Fire");
		}
	}

	/**
	 * Grenade.Collision for a rocket: on an enemy, a moment's hitstop, the magnets stuck in the enemy take a hit
	 * (two with a super explosion), and it explodes; against a wall, it makes a harmless explosion.
	 */
	@Override
	public void rocketHit(Rocket rocket, @Nullable LivingEntity enemy, boolean superExplosion) {
		Minecraft minecraft = Minecraft.getInstance();
		if (enemy == null) {
			explodeRocket(rocket, false, true, false, 1, false, false);
			return;
		}
		if (minecraft.level != null && minecraft.player != null) {
			for (Magnet magnet : List.copyOf(this.magnets)) {
				for (int i = 0; i < (superExplosion ? 2 : 1) && !magnet.removed && magnet.onEnemy() == enemy; i++) {
					magnet.damage(minecraft.level, minecraft.player, this);
				}
			}
		}
		this.hitstopLeft = Math.max(this.hitstopLeft, GRENADE_HITSTOP);
		ClientPlayNetworking.send(new HitstopPayload((int)(GRENADE_HITSTOP * 1000)));
		explodeRocket(rocket, false, false, superExplosion, 1, false, false);
	}

	/**
	 * Grenade.Explode for a rocket, where it is: a harmless explosion, a super explosion or an explosion. A big super
	 * explosion is two and a half times the size; otherwise a big or frozen rocket's is half again as big. It's then
	 * {@code sizeMultiplier} times bigger again, ultraboosting the player if {@code ultrabooster}; {@code fup}
	 * launches the player at a set speed, as a rocket the Whiplash brought to them does.
	 */
	private void explodeRocket(Rocket rocket, boolean big, boolean harmless, boolean superExplosion, double sizeMultiplier, boolean ultrabooster, boolean fup) {
		rocket.removed = true;
		String prefab = harmless ? EXPLOSION_ROCKET_HARMLESS : superExplosion ? EXPLOSION_SUPER : EXPLOSION;
		double size = superExplosion && big ? 2.5 : big || rocket.frozen ? 1.5 : 1;
		float blockPower = harmless ? 0 : superExplosion ? SUPER_BLOCK_POWER : CORE_BLOCK_POWER;
		explode(prefab, rocket.position, null, false, blockPower, settings -> {
			Explosion.Settings set = settings.scaled(size);
			set = ultrabooster ? set.ultraboosted(sizeMultiplier) : set.scaled(sizeMultiplier);
			return fup ? set.fupped() : set;
		});
	}

	/** The rocket's LevelUpEffect: its particles burst from it, and its level-up sound plays from there. */
	@Override
	public void rocketLevelledUp(Rocket rocket) {
		UltraModel model = UltraModel.get(ROCKET);
		if (model == null) {
			return;
		}
		int bone = model.script("grenade").get("levelUpEffect").getAsInt();
		// The model's matrices are in Minecraft's axes, its forward along -z.
		double ahead = -model.boneMatrices(model.rest(), new Quaternionf())[bone].getTranslation(new Vector3f()).z * S;
		Vec3 origin = rocket.position.add(rocket.forward.scale(ahead));
		List<Particles> systems = new ArrayList<>();
		for (JsonElement element : model.particles()) {
			Particles system = Particles.once(model, element.getAsJsonObject(), origin, Effects.facing(rocket.forward));
			systems.add(system);
			this.effects.play(system);
		}
		rocket.attachLevelUp(systems, ahead);
		this.sounds.once().update("rocket_level_up", 1, 1, origin);
	}

	@Override
	public List<Magnet> magnets() {
		return this.magnets;
	}

	// ---- cannonball -----------------------------------------------------------------------------

	/** Cannonball.Explode: its interruption explosion, and it breaks. */
	private void explodeCannonball(Cannonball ball) {
		if (ball.removed) {
			return;
		}
		explode(EXPLOSION_CANNONBALL, ball.position, null, false, CANNONBALL_BLOCK_POWER);
		ball.breakApart(this);
	}

	@Override
	public void cannonballHitEnemy(LivingEntity enemy, float damage, Vec3 push, Vec3 at) {
		ClientPlayNetworking.send(new CannonballHitPayload(enemy.getId(), damage, push, at));
		Level level = Minecraft.getInstance().level;
		if (level != null) {
			Gore gore = Gore.of(false, damage, false);
			this.effects.blood(level, at, gore);
			this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
		}
	}

	@Override
	public void cannonballHitBlock(BlockPos block, float damage) {
		if (damage > 0) {
			hitBlock(block, damage);
		}
	}

	/** Cannonball.Bounce: a camera shake, and its bounce sound from where it is. */
	@Override
	public void cannonballBounced(Cannonball ball) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			movement.shake(1);
		}
		this.sounds.once().update("cannonball_bounce", 1, 1, ball.position);
	}

	/** Its groundHitShockwave, with the slam's sound from where it landed. */
	@Override
	public void cannonballLanded(Cannonball ball, Vec3 at, double force) {
		UltraModel model = UltraModel.get(SHOCKWAVE);
		if (model != null) {
			this.shockwaves.add(new Shockwave(model, at, force));
		}
		this.sounds.once().update("cannonball_shockwave", 1, 1, at);
	}

	/** Its breakEffect: its sound (its RandomPitch: 0.75, a tenth either way) and its dust's, and a camera shake. */
	@Override
	public void cannonballBroke(Cannonball ball) {
		this.sounds.once().update("cannonball_break", 1, this.sounds.randomPitch(0.65F, 0.85F), ball.position);
		this.sounds.once().update("cannonball_break_dust", 1, 1, ball.position);
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement != null) {
			movement.shake(CANNONBALL_BREAK_SHAKE);
		}
	}

	/**
	 * The cannonball a punch reaches through its ParryHelper sphere: one the camera is within {@code touch} of, or else
	 * the first along the aim within the punch's reach (a ray, then a 0.3 unit box).
	 */
	private @Nullable Cannonball cannonballInReach(Vec3 eye, Vec3 look, double touch) {
		double radius = Cannonball.HIT_RADIUS * S;
		Cannonball nearest = null;
		double best = Double.MAX_VALUE;
		for (Cannonball ball : this.cannonballs) {
			if (ball.removed || ball.hooked) {
				continue;
			}
			if (ball.position.distanceTo(eye) <= radius + touch) {
				return ball;
			}
			OptionalDouble along = Hitscan.sphereEntry(eye, look, PUNCH_REACH, ball.position, radius + CANNONBALL_PUNCH_BOX);
			if (along.isPresent() && along.getAsDouble() < best) {
				best = along.getAsDouble();
				nearest = ball;
			}
		}
		return nearest;
	}

	/**
	 * Punch.TryParryProjectile on a cannonball, for the Feedbacker: the parry flash and the hook, and it's launched
	 * toward what the player aims at. Aimed at something within 10 units of it, it starts where the aim meets an
	 * enemy or a wall within 5 units of the camera (or 5 units ahead), flying along the aim.
	 */
	private boolean punchCannonball(LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		Level level = Minecraft.getInstance().level;
		if (movement == null || level == null || this.cannonballs.isEmpty()) {
			return false;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Cannonball ball = cannonballInReach(eye, look, SAW_PUNCH_TOUCH);
		if (ball == null || !ball.punchable()) {
			return false;
		}
		Animator arm = FirstPersonWeapons.INSTANCE.armAnimator();
		if (arm != null) {
			arm.play("Hook", 0.065);
		}
		parryFlash();
		ClientPlayNetworking.send(new HitstopPayload((int)(PARRY_HITSTOP * 1000)));
		Vec3 target = parryLookTarget(level, player, eye, look);
		if (ball.position.distanceTo(target) < CANNONBALL_PUNCH_NEAR) {
			ball.punched(Hitscan.cast(level, player, eye, look, CANNONBALL_PUNCH_PLACE, List.of()).getFirst().position(), look);
		} else {
			ball.punched(ball.position, target.subtract(ball.position).normalize());
		}
		return true;
	}

	/**
	 * Punch.ActiveFrame on a cannonball, for the Knuckleblaster: a short hitstop, a shake and the parry flash; the
	 * cannonball breaks, every enemy in a box 10 units wide and 2 high reaching 20 units along the aim is knocked
	 * away along it, unhurt, and the player is launched up as far as they look down (down as far as they look up).
	 */
	private boolean smashCannonball(LocalPlayer player) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		Level level = Minecraft.getInstance().level;
		if (movement == null || level == null || this.cannonballs.isEmpty()) {
			return false;
		}
		Vec3 eye = movement.eyePosition();
		Vec3 look = player.getLookAngle();
		Cannonball ball = cannonballInReach(eye, look, SAW_HEAVY_PUNCH_TOUCH);
		if (ball == null) {
			return false;
		}
		this.hitstopLeft = Math.max(this.hitstopLeft, PUNCH_HITSTOP);
		ClientPlayNetworking.send(new HitstopPayload((int)(PUNCH_HITSTOP * 1000)));
		movement.shake(0.5 * this.arm.screenShakeMultiplier);
		parryFlash();
		ClientPlayNetworking.send(new HitstopPayload((int)(PARRY_HITSTOP * 1000)));
		ball.breakApart(this);
		Vec3 up = Whiplash.upVector(player);
		OrientedBox swept = new OrientedBox(eye.add(look.scale(SMASH_REACH / 2)), look.cross(up).normalize(), up, look,
			SMASH_HALF_WIDTH, SMASH_HALF_HEIGHT, SMASH_REACH / 2 + SMASH_HALF_DEPTH);
		AABB area = new AABB(eye, eye.add(look.scale(SMASH_REACH))).inflate(SMASH_HALF_WIDTH + SMASH_HALF_DEPTH);
		for (LivingEntity enemy : level.getEntitiesOfClass(LivingEntity.class, area, e -> e != player && e.isAlive() && !e.isSpectator())) {
			if (swept.intersects(enemy.getBoundingBox())) {
				ClientPlayNetworking.send(new ExplosionHitPayload(enemy.getId(), 0, look.scale(SMASH_PUSH)));
			}
		}
		Vec3 velocity = movement.velocity();
		movement.setVelocity(new Vec3(velocity.x, -look.y * SMASH_LAUNCH, velocity.z));
		return true;
	}

	/**
	 * Whether the rocket is gone: then its sounds stop, its rider gets off, and the freeze's idle time starts over
	 * (Grenade.OnDestroy).
	 */
	private boolean rocketGone(Rocket rocket) {
		if (!rocket.removed) {
			return false;
		}
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (rocket.playerRiding && movement != null) {
			rocket.endRide(movement);
		}
		rocket.discard();
		this.rocketLauncher.rocketGone();
		return true;
	}

	// ---- whiplash -------------------------------------------------------------------------------

	/** HookArm.FixedUpdate, in each of the player's physics steps. */
	public void whiplashFixedUpdate() {
		Minecraft minecraft = Minecraft.getInstance();
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (minecraft.level != null && minecraft.player != null && movement != null) {
			this.whiplash.fixedUpdate(minecraft.level, minecraft.player, movement);
		}
	}

	@Override
	public List<Coin> coins() {
		return this.coins;
	}

	@Override
	public void coinBounced(Coin bounced) {
		this.respawned.add(bounced);
	}

	/** The hook's hit on the enemy it caught. */
	@Override
	public void hooked(LivingEntity enemy, Vec3 at) {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		Gore gore = Gore.of(false, WHIPLASH_DAMAGE, false);
		this.effects.blood(level, at, gore);
		this.sounds.play("gore." + gore.name().toLowerCase(), 1, this.sounds.randomPitch(0.75F, 1.5F));
	}

	/** Grenade.Explode on the core or rocket the hook took along. */
	@Override
	public void grenadeExploded(Grenade grenade) {
		if (grenade.removed) {
			return;
		}
		if (grenade instanceof Rocket rocket) {
			explodeRocket(rocket, false, false, false, 1, false, false);
			return;
		}
		grenade.removed = true;
		explode(EXPLOSION, grenade.position, null, false, CORE_BLOCK_POWER);
	}

	/**
	 * HookArm's catch, with a rocket on the hook: it's brought to the player. Coming up from below them (within 45
	 * degrees of straight down), or with jump held, the player gets on it; otherwise it goes off there, launching
	 * them, as a super explosion if they're in the air.
	 */
	@Override
	public void rocketCaught(Rocket rocket, Vec3 from) {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (rocket.removed || movement == null) {
			return;
		}
		Vec3 root = movement.rootPosition();
		rocket.position = root;
		Vec3 toRocket = from.subtract(root);
		boolean below = toRocket.lengthSqr() > 0 && Math.toDegrees(Math.acos(Math.clamp(-toRocket.normalize().y, -1, 1))) < 45;
		if (!movement.ridingRocket() && (below || movement.jumpHeld())) {
			rocket.startRide(movement);
		} else {
			explodeRocket(rocket, false, false, !movement.onGround(), 1, false, true);
		}
	}

	/** The Whiplash's pull takes the player off the rocket they ride. */
	@Override
	public void groundCheckForced() {
		UltraMovement movement = UltraController.INSTANCE.movement();
		if (movement == null) {
			return;
		}
		for (Rocket rocket : this.rockets) {
			if (rocket.playerRiding) {
				rocket.endRide(movement);
			}
		}
	}

	/** HookArm's clinkSparks and clinkObjectSparks: sparks out of {@code facing}, with their clink. */
	@Override
	public void clink(Vec3 at, Vec3 facing, boolean object) {
		String prefab = object ? "whiplash_clink_object" : "whiplash_clink";
		this.effects.particles(prefab, null, at, Effects.facing(facing));
		this.sounds.once().update(prefab, 1, object ? this.sounds.randomPitch(1.8F, 2.2F) : this.sounds.randomPitch(1.3F, 1.7F), at);
	}

	/**
	 * The Whiplash takes the arm (FistControl): the punch underway is called off (Punch.CancelAttack) and the next
	 * can come within a tenth of a second.
	 */
	@Override
	public void throwStarted() {
		this.punchReady = true;
		this.punchActiveLeft = 0;
		this.sawPunchPending = false;
		this.fistCooldown = Math.min(this.fistCooldown, 0.1);
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
		this.effects.render(context, this.coins, this.shells, this.pellets, this.cores);
		this.effects.renderRockets(context, this.rockets);
		this.effects.renderCannonballs(context, this.cannonballs);
		this.effects.renderSaws(context, this.saws, this.sawblades, chainStart());
		this.effects.renderDrills(context, this.drills);
		this.effects.renderNails(context, this.nails);
		this.effects.renderMagnets(context, this.magnets);
		if (this.zapper != null) {
			this.effects.renderZapper(context, this.zapper);
		}
		this.effects.renderShockwaves(context, this.shockwaves);
		renderWhiplash(context);
	}

	/** HookArm.LateUpdate and its LineRenderer: the hook out of the hand, and the wire to it from the hand. */
	private void renderWhiplash(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 hook = this.whiplash.drawnHookPoint(partialTick);
		FirstPersonWeapons.INSTANCE.placeHook(this.whiplash.hookOut() ? hook : null, this.whiplash.throwDirection);
		if (!this.whiplash.active() || minecraft.level == null || minecraft.player == null) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		if (this.whiplash.hookOut()) {
			FirstPersonWeapons.INSTANCE.submitHook(context.poseStack(), context.submitNodeCollector(), camera,
				LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(hook)));
		}
		Vec3 hand = FirstPersonWeapons.INSTANCE.wireStartWorld();
		UltraModel model = UltraModel.get("whiplash");
		if (this.whiplash.wireShown && hand != null && model != null) {
			this.effects.renderWire(context, model.script("hookArm").getAsJsonObject("line"), hand, hook, Whiplash.upVector(minecraft.player), this.whiplash.throwWarp);
		}
	}
}
