package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraMovement;
import dev.ultracraft.client.movement.UltraSounds;
import dev.ultracraft.client.render.FirstPersonWeapons;
import dev.ultracraft.client.render.animation.Animator;
import dev.ultracraft.combat.WhiplashTargets;
import dev.ultracraft.net.WhiplashPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The Whiplash (HookArm): a hook on a wire, thrown along the aim for as long as its key is held. It catches the first
 * enemy it reaches, takes a core along, bounces the coins it passes and brings back an item; a wall stops it. Let go
 * of on an enemy, it pulls: a light enemy comes to the player, the player goes to a heavy one. Then it reels back to
 * the hand.
 *
 * <p>It moves in ULTRAKILL's physics steps, run with the player's (after NewMovement's own); its key and the jump
 * that ends a pull are read every frame.
 */
final class Whiplash {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** Time.fixedDeltaTime. */
	private static final double STEP = 0.008;
	/** The hook flies out at this speed (units a second), on up to this much wire. */
	private static final double THROW_SPEED = 250;
	private static final double MAX_LENGTH = 300;
	/** It sweeps a sphere this many times smaller than its distance from the arm, up to this big (units). */
	private static final double SWEEP_SHARE = 15;
	private static final double MAX_SWEEP_RADIUS = 5;
	/** A throw can come this often. Letting go of the key stops it once this little of the wait is left. */
	private static final double COOLDOWN = 0.5;
	private static final double LET_GO_COOLDOWN = 0.1;
	/** The player is pulled to a heavy enemy at this speed (units a second). */
	private static final double PULL_SPEED = 60;
	/** Pulling ends once the player and the enemy are this close (units). */
	private static final double ARRIVE_DISTANCE = 0.25;
	/** Reaching an enemy in the air, the player rises at this speed (units a second), plus how far below it they were. */
	private static final double ARRIVE_RISE = 15;
	/** A light enemy's pull lifts a player in the air at this speed, or this one under water. */
	private static final double LIGHT_PULL_RISE = 15;
	private static final double LIGHT_PULL_RISE_WATER = 5;
	/** Jumping off a pull: the player keeps at most this speed, rising at least at 1, and in the air gets this much more. */
	private static final double JUMP_MAX_SPEED = 30;
	private static final double JUMP_AIR_BOOST = 15;
	/** The ground counts as under the player for a jump within this far below the ground check (units). */
	private static final double JUMP_GROUND_REACH = 1.5;
	/** The hook reels back at this speed plus half the distance it was let go at (at least this much), to a point this far ahead of the camera. */
	private static final double RETURN_SPEED = 100;
	private static final double MIN_RETURN_DISTANCE = 25;
	private static final double CATCH_REACH = 1.5;
	/** The thrown wire's wobble fades this fast. */
	private static final double WARP_FADE = 6.5;
	/** The arm hangs this far below the camera (FistControl's "Punch"). */
	private static final double ARM_DROP = 0.225;
	/** Where HookArm puts its hand (the wire's start) for the throw, and for the pull and catch. */
	private static final float[] THROW_HAND = {0.09F, -0.051F, 0.045F};
	private static final float[] PULL_HAND = {-0.015F, 0.071F, 0.04F};

	enum State {
		READY,
		THROWING,
		CAUGHT,
		PULLING
	}

	/** What the hook does to things. */
	interface Hits {
		List<Coin> coins();

		List<Core> cores();

		/** Coin.Bounce: the coin pops up again as {@code bounced}. */
		void coinBounced(Coin bounced);

		/** The hook caught an enemy here: its hit. */
		void hooked(LivingEntity enemy, Vec3 at);

		/** The core on the hook reached what stopped the hook. */
		void coreExploded(Core core);

		/** HookArm's clinkSparks (or, for something it caught, clinkObjectSparks) here, facing {@code facing}. */
		void clink(Vec3 at, Vec3 facing, boolean object);

		/** The throw takes the arm: the punch underway is called off, and the next comes sooner. */
		void throwStarted();
	}

	private final UltraSounds sounds;
	private final Hits hits;
	/** The arm's AudioSource, playing the throw's and the pull's loops. */
	private final UltraSounds.Loop arm;
	private @Nullable String armLoop;
	private float armPitch = 1;
	private final UltraSounds.Loop woosh;

	State state = State.READY;
	boolean returning;
	/** The wire is drawn (its LineRenderer is enabled). */
	boolean wireShown;
	Vec3 hookPoint = Vec3.ZERO;
	Vec3 throwDirection = new Vec3(0, 0, 1);
	/** How much the thrown wire still wobbles, 1 to 0. */
	double throwWarp;
	private double cooldown;
	/** Units. */
	private double returnDistance;
	private @Nullable LivingEntity caughtEnemy;
	/** Where on the enemy the hook is, from its position. */
	private Vec3 caughtOffset = Vec3.ZERO;
	private boolean lightTarget;
	private @Nullable Core caughtCore;
	private boolean forcingGroundCheck;
	/** The Whiplash has the arm, from a throw until it's caught again. */
	private boolean forcingFistControl;
	private boolean beingPulled;
	private boolean held;
	private @Nullable UltraMovement movement;

	Whiplash(UltraSounds sounds, Hits hits) {
		this.sounds = sounds;
		this.hits = hits;
		this.arm = sounds.loop();
		this.woosh = sounds.once();
	}

	/** HookArm.Update: the key throws (or, while pulling, lets go), a jump ends a pull to a heavy enemy. */
	void frame(double dt, Level level, LocalPlayer player, UltraMovement movement, boolean pressed, boolean held) {
		this.movement = movement;
		this.held = held;
		if (pressed) {
			if (this.state == State.PULLING) {
				stopThrow(level, player, 0, false);
			} else if (this.cooldown <= 0) {
				throwHook(movement, player);
			}
		}
		this.cooldown = Math.max(this.cooldown - dt, 0);
		this.throwWarp = Math.max(this.throwWarp - dt * WARP_FADE, 0);
		if (this.state == State.PULLING && !this.lightTarget && movement.jumpPressed()) {
			Vec3 velocity = movement.velocity();
			if (velocity.y < 1) {
				velocity = new Vec3(velocity.x, 1, velocity.z);
			}
			if (velocity.length() > JUMP_MAX_SPEED) {
				velocity = velocity.normalize().scale(JUMP_MAX_SPEED);
			}
			movement.setVelocity(velocity);
			if (!movement.touchingGround() && !movement.groundBelow(JUMP_GROUND_REACH)) {
				movement.addVelocityChange(new Vec3(0, JUMP_AIR_BOOST, 0));
			} else if (!movement.jumping()) {
				movement.jump();
			}
			stopThrow(level, player, 1, false);
		}
		if (this.armLoop != null) {
			this.arm.update(this.armLoop, 1, this.armPitch);
		}
	}

	/** HookArm.FixedUpdate. */
	void fixedUpdate(Level level, LocalPlayer player, UltraMovement movement) {
		this.movement = movement;
		if (this.caughtCore != null && this.caughtCore.removed) {
			this.caughtCore = null;
		}
		if (this.state == State.READY && this.returning) {
			reelIn(player, movement);
		}
		if (this.state == State.THROWING) {
			if (!this.held && (this.cooldown <= LET_GO_COOLDOWN || this.caughtCore != null)) {
				stopThrow(level, player, 0, false);
			} else {
				fly(level, player, movement);
			}
		} else if (this.state == State.CAUGHT) {
			if (!holdOn(level, player, movement)) {
				movement.setBeingPulled(false);
				return;
			}
		}
		if (this.state == State.PULLING) {
			pull(level, player, movement);
		} else {
			this.beingPulled = false;
		}
		movement.setBeingPulled(this.beingPulled);
	}

	/** A throw: out of the arm along the aim, the arm's hand set for it, a core still on the hook sent to the player. */
	private void throwHook(UltraMovement movement, LocalPlayer player) {
		this.cooldown = COOLDOWN;
		FirstPersonWeapons.INSTANCE.showWhiplash(true);
		if (!this.forcingFistControl) {
			this.hits.throwStarted();
			FirstPersonWeapons.INSTANCE.cancelPunch();
			this.forcingFistControl = true;
		}
		this.wireShown = true;
		this.hookPoint = armOrigin(movement, player);
		this.throwDirection = player.getLookAngle();
		this.returning = false;
		releaseCore(movement);
		this.state = State.THROWING;
		this.lightTarget = false;
		this.throwWarp = 1;
		play("Throw", 0);
		FirstPersonWeapons.INSTANCE.setWireStart(THROW_HAND[0], THROW_HAND[1], THROW_HAND[2]);
		this.caughtEnemy = null;
		this.sounds.play("whiplash_throw", 1, this.sounds.randomPitch(0.9F, 1.1F));
		playArm("whiplash_throw_loop");
	}

	/**
	 * One step of the flight: the sphere it sweeps (wider the further out it is) catches the nearest enemy it can
	 * reach, picks up a core, bounces coins onto the wire and grabs an item; a wall ahead stops it short.
	 */
	private void fly(Level level, LocalPlayer player, UltraMovement movement) {
		double step = THROW_SPEED * STEP * S * UltraConfig.get().speedScale;
		BlockHitResult wall = level.clip(new ClipContext(this.hookPoint, this.hookPoint.add(this.throwDirection.scale(step)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		boolean wallAhead = wall.getType() != HitResult.Type.MISS;
		if (wallAhead) {
			step = wall.getLocation().distanceTo(this.hookPoint);
		}
		Vec3 origin = armOrigin(movement, player);
		double radius = Math.min(origin.distanceTo(this.hookPoint) / SWEEP_SHARE, MAX_SWEEP_RADIUS * S);
		boolean caught = false;
		for (Touch touch : sweep(level, player, step, radius)) {
			if (touch.core != null) {
				if (this.caughtCore == null) {
					this.caughtCore = touch.core;
					touch.core.hooked = true;
					this.hits.clink(touch.core.position, this.throwDirection.scale(-1), true);
				}
			} else if (touch.coin != null) {
				if (!touch.coin.shot && touch.coin.hittable()) {
					touch.coin.position = this.hookPoint.add(this.throwDirection.scale(touch.distance));
					this.hits.coinBounced(touch.coin.bounced());
				}
			} else if (touch.entity instanceof LivingEntity enemy) {
				Vec3 center = enemy.getBoundingBox().getCenter();
				if (blocked(level, player, this.hookPoint, center)) {
					continue;
				}
				catchEnemy(enemy, center, touch.contact);
				caught = true;
				break;
			} else if (touch.entity instanceof ItemEntity item) {
				if (blocked(level, player, this.hookPoint, item.position())) {
					continue;
				}
				ClientPlayNetworking.send(new WhiplashPayload(WhiplashPayload.Action.GRAB, item.getId(), item.position(), scale()));
				caught = true;
				stopThrow(level, player, 0, false);
				break;
			}
		}
		Vec3 point = this.hookPoint;
		if (wallAhead && !caught) {
			this.hits.clink(wall.getLocation(), wall.getDirection().getUnitVec3(), false);
			point = wall.getLocation();
			stopThrow(level, player, 0, false);
			caught = true;
		}
		if (!caught && origin.distanceTo(this.hookPoint) > MAX_LENGTH * S) {
			stopThrow(level, player, 0, false);
		} else if (!caught) {
			this.hookPoint = this.hookPoint.add(this.throwDirection.scale(step));
			point = this.hookPoint;
		}
		if (this.caughtCore != null) {
			this.caughtCore.position = point;
			if (caught) {
				this.caughtCore.hooked = false;
				this.hits.coreExploded(this.caughtCore);
				this.caughtCore = null;
			}
		}
	}

	/** The hook caught an enemy: it holds on at the enemy's middle and hits it. */
	private void catchEnemy(LivingEntity enemy, Vec3 center, Vec3 contact) {
		this.caughtEnemy = enemy;
		this.lightTarget = !WhiplashTargets.heavy(enemy);
		this.hits.hooked(enemy, contact);
		ClientPlayNetworking.send(new WhiplashPayload(WhiplashPayload.Action.HOOK, enemy.getId(), contact, scale()));
		this.hookPoint = center;
		this.caughtOffset = center.subtract(enemy.position());
		this.state = State.CAUGHT;
		stopArm();
		this.sounds.once().update("whiplash_hit", 1, this.sounds.randomPitch(1.3F, 1.7F), contact);
	}

	/**
	 * Held on an enemy: it stays on while the key is held and nothing comes between them. Letting go starts the pull:
	 * a light enemy is pulled (a player in the air rises a little), or the player is.
	 *
	 * @return whether the hook is still on
	 */
	private boolean holdOn(Level level, LocalPlayer player, UltraMovement movement) {
		LivingEntity enemy = this.caughtEnemy;
		if (enemy == null || !enemy.isAlive()) {
			stopThrow(level, player, 0, false);
			return false;
		}
		Vec3 next = enemy.position().add(this.caughtOffset);
		if (blocked(level, player, armOrigin(movement, player), next)) {
			stopThrow(level, player, 0, false);
			return false;
		}
		this.hookPoint = next;
		if (this.held) {
			return true;
		}
		play("Pull", 0);
		FirstPersonWeapons.INSTANCE.setWireStart(PULL_HAND[0], PULL_HAND[1], PULL_HAND[2]);
		this.state = State.PULLING;
		this.woosh.update("whiplash_woosh", 1, 1);
		this.sounds.play("whiplash_pull", 1, this.sounds.randomPitch(0.9F, 1.1F));
		playArm("whiplash_pull_loop");
		if (this.lightTarget) {
			ClientPlayNetworking.send(new WhiplashPayload(WhiplashPayload.Action.PULL_ENEMY, enemy.getId(), next, scale()));
			if (!movement.touchingGround()) {
				movement.setVelocity(new Vec3(0, player.isInWater() ? LIGHT_PULL_RISE_WATER : LIGHT_PULL_RISE, 0));
			}
		} else {
			ClientPlayNetworking.send(new WhiplashPayload(WhiplashPayload.Action.PULL_PLAYER, enemy.getId(), next, scale()));
			if (!this.forcingGroundCheck) {
				forceGroundCheck(movement);
			}
		}
		return true;
	}

	/**
	 * Pulling, until the player and the enemy touch: a light enemy comes to the player (the server moves it), the
	 * player flies to a heavy one at 60 units a second unless dashing. Reaching it, a player in the air rises a little.
	 */
	private void pull(Level level, LocalPlayer player, UltraMovement movement) {
		LivingEntity enemy = this.caughtEnemy;
		if (enemy == null || enemy.isRemoved()) {
			stopThrow(level, player, 1, false);
			return;
		}
		Vec3 next = enemy.position().add(this.caughtOffset);
		Vec3 origin = armOrigin(movement, player);
		if (blocked(level, player, origin, next) || !enemy.isAlive()) {
			stopThrow(level, player, 1, false);
			return;
		}
		AABB enemyBox = enemy.getBoundingBox();
		Vec3 near = closest(player.getBoundingBox(), this.hookPoint);
		Vec3 touch = closest(enemyBox, near);
		double gap = near.distanceTo(touch);
		Vec3 velocity = movement.velocity();
		Vec3 nearNext = near.add(velocity.scale(STEP * S * UltraConfig.get().speedScale));
		double arrive = ARRIVE_DISTANCE * S;
		if (gap < arrive || !this.lightTarget && nearNext.distanceTo(touch) < arrive) {
			if (!this.lightTarget && gap >= arrive && velocity.lengthSqr() > 0) {
				movement.movePosition(touch.subtract(velocity.normalize().scale(arrive)));
			}
			if (this.lightTarget) {
				ClientPlayNetworking.send(new WhiplashPayload(WhiplashPayload.Action.ARRIVED, enemy.getId(), next, scale()));
			}
			stopThrow(level, player, 1, false);
			double below = (this.hookPoint.y - origin.y) / S;
			if (!movement.touchingGround()) {
				if (player.isInWater()) {
					movement.setVelocity(Vec3.ZERO);
				} else if (origin.y < this.hookPoint.y) {
					movement.setVelocity(new Vec3(0, ARRIVE_RISE + below, 0));
				} else {
					movement.setVelocity(new Vec3(0, ARRIVE_RISE, 0));
				}
			}
			return;
		}
		if (this.lightTarget && this.forcingGroundCheck) {
			stopForceGroundCheck(movement);
		} else if (!this.lightTarget && !this.forcingGroundCheck) {
			forceGroundCheck(movement);
		}
		this.hookPoint = next;
		if (this.lightTarget) {
			return;
		}
		this.beingPulled = true;
		if (!movement.boosting() || movement.sliding()) {
			movement.setVelocity(this.hookPoint.subtract(movement.rootPosition()).normalize().scale(PULL_SPEED));
		}
	}

	/** Reeling back to just ahead of the camera, faster the further out it was let go. There the arm catches it. */
	private void reelIn(LocalPlayer player, UltraMovement movement) {
		double step = STEP * (RETURN_SPEED + this.returnDistance / 2) * S * UltraConfig.get().speedScale;
		Vec3 target = movement.eyePosition().add(player.getLookAngle().scale(CATCH_REACH * S));
		Vec3 toTarget = target.subtract(this.hookPoint);
		this.hookPoint = toTarget.length() <= step ? target : this.hookPoint.add(toTarget.normalize().scale(step));
		if (this.caughtCore != null) {
			this.caughtCore.position = this.hookPoint;
		}
		if (!this.hookPoint.equals(target)) {
			return;
		}
		this.wireShown = false;
		this.returning = false;
		play("Catch", 0);
		this.sounds.play("whiplash_catch", 1, this.sounds.randomPitch(0.9F, 1.1F));
		stopArm();
		if (this.caughtCore != null) {
			// The core comes away with the player's velocity, without its fall.
			Vec3 velocity = movement.velocity();
			if (velocity.y < 0) {
				velocity = new Vec3(velocity.x, 0, velocity.z);
			}
			this.caughtCore.hooked = false;
			this.caughtCore.launch(velocity);
			this.caughtCore = null;
		}
	}

	/**
	 * HookArm.StopThrow: the hook lets go and reels back. Let go of mid-pull, the pull's sounds end; otherwise the
	 * reeling starts with its own.
	 *
	 * @param animationTime where the arm's pull animation starts: 0 to reel in, 1 for a pull that's over
	 * @param sparks whether the hook comes off with sparks
	 */
	void stopThrow(Level level, LocalPlayer player, double animationTime, boolean sparks) {
		if (animationTime == 0) {
			this.sounds.play("whiplash_pull", 1, this.sounds.randomPitch(0.9F, 1.1F));
			playArm("whiplash_pull_loop");
		} else {
			this.sounds.play("whiplash_pull_done", 1, this.sounds.randomPitch(1.9F, 2.1F));
			this.sounds.play("whiplash_pull_done_stop", 1, this.sounds.randomPitch(0.9F, 1.1F));
		}
		if (this.forcingGroundCheck && this.movement != null) {
			stopForceGroundCheck(this.movement);
		}
		this.lightTarget = false;
		if (this.caughtEnemy != null) {
			ClientPlayNetworking.send(WhiplashPayload.of(WhiplashPayload.Action.RELEASE));
			this.caughtEnemy = null;
		}
		Vec3 origin = this.movement != null ? armOrigin(this.movement, player) : player.getEyePosition();
		if (sparks) {
			this.hits.clink(this.hookPoint, origin.subtract(this.hookPoint), false);
		}
		this.state = State.READY;
		play("Pull", animationTime);
		FirstPersonWeapons.INSTANCE.setWireStart(PULL_HAND[0], PULL_HAND[1], PULL_HAND[2]);
		this.returnDistance = Math.max(origin.distanceTo(this.hookPoint) / S, MIN_RETURN_DISTANCE);
		this.returning = true;
		this.throwWarp = 0;
		this.woosh.stop();
	}

	/** HookArm.Cancel: everything stops at once, the arm goes away, and a core on the hook is sent to the player. */
	void cancel() {
		if (!active()) {
			return;
		}
		if (this.forcingGroundCheck && this.movement != null) {
			stopForceGroundCheck(this.movement);
		}
		this.forcingFistControl = false;
		if (this.movement != null) {
			releaseCore(this.movement);
		}
		this.caughtCore = null;
		this.lightTarget = false;
		if (this.caughtEnemy != null) {
			ClientPlayNetworking.send(WhiplashPayload.of(WhiplashPayload.Action.RELEASE));
			this.caughtEnemy = null;
		}
		this.state = State.READY;
		play("Idle", 0);
		this.returning = false;
		this.throwWarp = 0;
		this.wireShown = false;
		this.beingPulled = false;
		if (this.movement != null) {
			this.movement.setBeingPulled(false);
		}
		stopArm();
		this.woosh.stop();
		FirstPersonWeapons.INSTANCE.showWhiplash(false);
		FirstPersonWeapons.INSTANCE.placeHook(null, this.throwDirection);
	}

	/** HookArm.CatchOver, at the end of the catch: the arm goes away. */
	void catchOver() {
		if (this.state != State.READY || this.returning) {
			return;
		}
		this.forcingFistControl = false;
		FirstPersonWeapons.INSTANCE.showWhiplash(false);
	}

	/** The enemy on the hook teleported away: the hook comes off it with sparks. */
	void knockedOff(Level level, LocalPlayer player) {
		if (this.state == State.CAUGHT || this.state == State.PULLING) {
			this.caughtEnemy = null;
			stopThrow(level, player, 1, true);
		}
	}

	/** Whether the arm is out: thrown, on something, reeling in, or being caught. */
	boolean active() {
		return this.state != State.READY || this.returning || this.forcingFistControl;
	}

	/** Where the hook is drawn: on the enemy it's caught as the enemy is drawn, or where it is. */
	Vec3 drawnHookPoint(float partialTick) {
		LivingEntity enemy = this.caughtEnemy;
		if (enemy != null && (this.state == State.CAUGHT || this.state == State.PULLING)) {
			return enemy.getPosition(partialTick).add(this.caughtOffset);
		}
		return this.hookPoint;
	}

	/** Whether the hook is out of the hand (HookArm.LateUpdate places it). */
	boolean hookOut() {
		return this.state != State.READY || this.returning;
	}

	/** A core on the hook when it's thrown again or called off is sent at the player. */
	private void releaseCore(UltraMovement movement) {
		if (this.caughtCore == null) {
			return;
		}
		Vec3 toPlayer = movement.rootPosition().subtract(this.caughtCore.position);
		this.caughtCore.hooked = false;
		this.caughtCore.launch(toPlayer.lengthSqr() > 0 ? toPlayer.normalize().scale(RETURN_SPEED + this.returnDistance / 2) : Vec3.ZERO);
		this.caughtCore = null;
	}

	private void forceGroundCheck(UltraMovement movement) {
		this.forcingGroundCheck = true;
		movement.forceGroundOff(true);
	}

	private void stopForceGroundCheck(UltraMovement movement) {
		this.forcingGroundCheck = false;
		movement.forceGroundOff(false);
	}

	private void play(String state, double normalizedTime) {
		Animator animator = FirstPersonWeapons.INSTANCE.whiplashAnimator();
		if (animator != null) {
			animator.play(state, normalizedTime);
		}
	}

	/** The arm's AudioSource plays a loop, at a new pitch each time. */
	private void playArm(String loop) {
		this.arm.stop();
		this.armLoop = loop;
		this.armPitch = this.sounds.randomPitch(0.9F, 1.1F);
		this.arm.update(loop, 1, this.armPitch);
	}

	private void stopArm() {
		this.arm.stop();
		this.armLoop = null;
	}

	/** HookArm's transform: the arm, below the camera. */
	private static Vec3 armOrigin(UltraMovement movement, LocalPlayer player) {
		Vec3 up = upVector(player);
		return movement.eyePosition().subtract(up.scale(ARM_DROP * S));
	}

	/** The camera's up. */
	static Vec3 upVector(LocalPlayer player) {
		double yaw = Math.toRadians(player.getYRot());
		double pitch = Math.toRadians(player.getXRot());
		return new Vec3(-Math.sin(yaw) * Math.sin(pitch), Math.cos(pitch), Math.cos(yaw) * Math.sin(pitch));
	}

	/** Blocks a tick for one unit a second. */
	private static double scale() {
		return S * UltraConfig.get().speedScale / 20;
	}

	private static boolean blocked(Level level, LocalPlayer player, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS;
	}

	/** What a sphere of {@code radius} swept {@code step} along the throw touches, nearest first. */
	private List<Touch> sweep(Level level, LocalPlayer player, double step, double radius) {
		Vec3 from = this.hookPoint;
		Vec3 to = from.add(this.throwDirection.scale(step));
		List<Touch> touches = new ArrayList<>();
		AABB area = new AABB(from, to).inflate(radius + Core.HIT_RADIUS);
		for (Entity entity : level.getEntities(player, area, e -> ThrownSaw.isEnemy(e) || e instanceof ItemEntity && e.isAlive())) {
			AABB box = entity.getBoundingBox();
			OptionalDouble at = sweptEntry(box.inflate(radius), from, to);
			if (at.isPresent()) {
				Vec3 sphere = from.add(this.throwDirection.scale(at.getAsDouble()));
				touches.add(new Touch(at.getAsDouble(), closest(box, sphere), entity, null, null));
			}
		}
		for (Core core : this.hits.cores()) {
			if (!core.removed && !core.hooked) {
				sphereEntry(core.position, radius + Core.HIT_RADIUS, from, step).ifPresent(at -> touches.add(new Touch(at, core.position, null, core, null)));
			}
		}
		for (Coin coin : this.hits.coins()) {
			if (!coin.removed) {
				sphereEntry(coin.position, radius + Coin.HIT_RADIUS, from, step).ifPresent(at -> touches.add(new Touch(at, coin.position, null, null, coin)));
			}
		}
		touches.sort(Comparator.comparingDouble(Touch::distance));
		return touches;
	}

	/** Something the sweep touched, {@code distance} along it, at {@code contact}. */
	private record Touch(double distance, Vec3 contact, @Nullable Entity entity, @Nullable Core core, @Nullable Coin coin) {
	}

	/** How far along from {@code from} to {@code to} the segment enters the box: 0 if it starts inside. */
	private static OptionalDouble sweptEntry(AABB box, Vec3 from, Vec3 to) {
		if (box.contains(from)) {
			return OptionalDouble.of(0);
		}
		return box.clip(from, to).map(at -> OptionalDouble.of(at.distanceTo(from))).orElse(OptionalDouble.empty());
	}

	/** How far along the throw from {@code from} a point comes within {@code reach} of {@code center}: 0 if it starts within. */
	private OptionalDouble sphereEntry(Vec3 center, double reach, Vec3 from, double step) {
		Vec3 offset = center.subtract(from);
		double along = offset.dot(this.throwDirection);
		double off = offset.lengthSqr() - along * along;
		if (offset.lengthSqr() <= reach * reach) {
			return OptionalDouble.of(0);
		}
		if (along < 0 || off > reach * reach) {
			return OptionalDouble.empty();
		}
		double distance = along - Math.sqrt(reach * reach - off);
		return distance <= step ? OptionalDouble.of(distance) : OptionalDouble.empty();
	}

	private static Vec3 closest(AABB box, Vec3 point) {
		return new Vec3(Math.clamp(point.x, box.minX, box.maxX), Math.clamp(point.y, box.minY, box.maxY), Math.clamp(point.z, box.minZ, box.maxZ));
	}
}
