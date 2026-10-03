package dev.ultracraft.client.movement;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraPlayer;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds.Channel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.function.IntToDoubleFunction;

/**
 * ULTRAKILL's player controller (NewMovement, with its GroundCheck, SlopeCheck, WallCheck and ClimbStep)
 * driving Minecraft's local player.
 *
 * <p>Velocities, forces and distances are ULTRAKILL units; the player's position is Minecraft's, in blocks.
 * Geometry converts with {@link PlayerShape#BLOCKS_PER_UNIT}, and displacement additionally with the
 * configured speed scale. Each frame runs the 125 Hz physics steps that are due (FixedUpdate), then the
 * per-frame logic (Update) and the camera (LateUpdate), in Unity's order.
 */
public final class UltraMovement {
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** The player's Rigidbody mass. */
	private static final double MASS = 100;
	private static final double DT = UltraBody.FIXED_DT;
	private static final Vec3 UP = UltraBody.UP;
	private static final Vec3 G = UltraBody.GRAVITY_DIRECTION;
	private static final int MAX_STEPS_PER_FRAME = 12;

	// NewMovement's serialized values (reference/player/player_0.json).
	private static final double WALK_SPEED = 750;
	private static final double AIR_ACCELERATION = 6000;
	private static final double JUMP_POWER = 90;
	private static final double WALL_JUMP_POWER = 150;
	private static final double SSJ_MAX_FRAMES = 4;
	private static final double ONE_PHYSICS_FRAME = 0.00800000037997961;

	/** NewMovement's transform.position above the feet, with the standing and the short collider. */
	private static final double STANDING_ROOT = 1.5;
	private static final double SHORT_ROOT = 0.375;
	/** GroundCheck's capsule sits 0.244 units above the feet and reaches 0.276 below them. */
	private static final double GROUND_CHECK_HEIGHT = 0.244;
	private static final double GROUND_CHECK_DEPTH = 0.276;
	/** SlopeCheck is the body's capsule, 0.05 units thinner and 0.1 units lower. */
	private static final double SLOPE_CHECK_DEPTH = 0.1;
	private static final double SLOPE_CHECK_INSET = 0.05;
	/** WallCheck is a sphere 0.1 units below the root. */
	private static final double WALL_CHECK_OFFSET = -0.1;
	private static final double WALL_CHECK_RADIUS = 0.9;
	private static final double ENEMY_CHECK_RADIUS = 2.5;
	private static final double CLIMB_STEP = 2.1;
	private static final double CLIMB_FORWARD = 0.6;
	private static final double CLIMB_COOLDOWN = 0.1;

	/** Jumps and landings are quieter than ULTRAKILL's, which stand out against Minecraft's sounds. */
	private static final float JUMP_VOLUME = 0.5F;
	private static final float LANDING_VOLUME = 0.5F;
	/**
	 * Landings slower than this (about a 2.5-block drop) sound like a footstep. Minecraft's terrain is full
	 * of one- and two-block drops, where ULTRAKILL's landing sound would play every few steps.
	 */
	private static final double SOFT_LANDING_SPEED = -20;

	private final LocalPlayer player;
	final UltraBody body = new UltraBody();
	final UltraCamera camera = new UltraCamera();
	private final UltraSounds sounds = new UltraSounds();
	private UltraInput input = new UltraInput();
	private double clock;
	private double accumulator;
	/** Feet before and after the last physics step, for drawing the camera between them. */
	private Vec3 previousFeet;
	private Vec3 currentFeet;

	// NewMovement
	double movementHor;
	private Vec3 inputDir = Vec3.ZERO;
	private Vec3 airDirection = Vec3.ZERO;
	boolean walking;
	private boolean enemyStepping;
	boolean falling;
	private boolean jumpCooldown;
	private int currentWallJumps;
	private double clingFade;
	boolean boost;
	private double boostCharge = 300;
	private double boostLeft;
	private boolean dashedFromGround;
	private double dashStorage;
	private Vec3 dodgeDirection = Vec3.ZERO;
	private boolean jumping;
	private double fallSpeed;
	private double fallTime;
	private boolean stillHolding;
	private double slamForce;
	private boolean slamStorage;
	private double slamCooldown;
	boolean sliding;
	private double slideSafety;
	private boolean slideEnding;
	private boolean crouching;
	boolean standing = true;
	private double preSlideSpeed;
	private double preSlideDelay;
	private boolean slowMode;
	/** Unity layer 15: dashing makes the player invincible. */
	private boolean invincible;
	private double windState;
	private double slideTimestamp;
	private double jumpTimestamp;
	private double lastJump = 10;
	private Vec3 preDashSpeed = Vec3.ZERO;
	private int framesSinceSlide;
	private Vec3 velocityAfterSlide = Vec3.ZERO;
	/** The collider is the 1.25-unit one: sliding, or crouched under something. */
	private boolean shortCollider;
	private double notJumpingTimer;
	private double jumpReadyTimer;
	/** Rocket jumps since the player last stood on the ground or stepped on an enemy. */
	private int rocketJumps;
	/** A harmless explosion can't launch the player again until this runs out. */
	private double safeExplosionLaunchCooldown;
	/** PlayerFootsteps: counts down to the next footstep while walking. */
	private double footstepTimer = 1;

	// GroundCheck (gc) and SlopeCheck
	private boolean groundTouching;
	private boolean onGround;
	private boolean slopeOnGround;
	private boolean canJump;
	private boolean heavyFall;
	private double superJumpChance;
	private double extraJumpChance;
	private double sinceLastGrounded;

	// WallCheck
	private boolean onWall;
	private Vec3 pointOfCollision = Vec3.ZERO;

	// ClimbStep
	private double climbCooldown;

	public UltraMovement(LocalPlayer player) {
		this.player = player;
		this.previousFeet = player.position();
		this.currentFeet = this.previousFeet;
		this.body.velocity = player.getDeltaMovement().scale(20 / displacementScale());
		if (!canStandWithFeetAt(0)) {
			this.shortCollider = true;
			this.crouching = true;
			this.slowMode = true;
			this.camera.crouch();
			this.camera.defaultPos = this.camera.defaultTarget;
			this.camera.resetToDefaultPos();
		}
		updateTriggers();
		this.onGround = this.groundTouching;
		this.slopeOnGround = this.onGround;
	}

	private static double displacementScale() {
		return S * UltraConfig.get().speedScale;
	}

	// ---- per frame ----------------------------------------------------------------------------

	public void frame(double dt, double clock, UltraInput input) {
		this.clock = clock;
		this.input = input;
		if (input.jumpPressed) {
			this.jumpTimestamp = clock;
		}
		if (input.slideReleased && this.sliding) {
			this.slideTimestamp = clock;
		}

		this.accumulator += dt;
		int steps = 0;
		while (this.accumulator >= DT && steps < MAX_STEPS_PER_FRAME) {
			this.previousFeet = this.currentFeet;
			physicsStep();
			this.currentFeet = this.player.position();
			this.accumulator -= DT;
			steps++;
		}
		if (steps == MAX_STEPS_PER_FRAME) {
			this.accumulator = 0;
		}

		this.sinceLastGrounded += dt;
		this.lastJump += dt;
		groundCheckUpdate(dt);
		update(dt);
		footsteps(dt);
		runTimers(dt);
		this.camera.lateUpdate(dt, this, defaultFov());
	}

	private static double defaultFov() {
		return net.minecraft.client.Minecraft.getInstance().options.fov().get();
	}

	/** Takes in position and velocity changes made outside this controller since the last frame. */
	public void syncExternal(Vec3 lastDeltaMovement) {
		Vec3 feet = this.player.position();
		Vec3 offset = feet.subtract(this.currentFeet);
		if (offset.lengthSqr() > 16) {
			this.body.velocity = Vec3.ZERO;
			this.previousFeet = feet;
		} else {
			this.previousFeet = this.previousFeet.add(offset);
		}
		this.currentFeet = feet;

		Vec3 change = this.player.getDeltaMovement().subtract(lastDeltaMovement);
		if (change.lengthSqr() > 0.05 * 0.05) {
			launch(change.scale(20 / displacementScale()));
		}
	}

	/** The player's velocity in Minecraft's terms: blocks per tick. */
	public Vec3 deltaMovement() {
		return this.body.velocity.scale(displacementScale() / 20);
	}

	// ---- physics step (FixedUpdate, then the physics simulation, then contacts and triggers) --------

	private void physicsStep() {
		this.climbCooldown = this.climbCooldown <= 0 ? 0 : this.climbCooldown - DT;
		fixedUpdate();
		UltraBody.Contact contact = this.body.step(this.player, displacementScale());
		if (UltraConfig.get().stepMode == UltraConfig.StepMode.ULTRAKILL && contact.hitHorizontally()) {
			climbStep(contact);
		}
		updateTriggers();
		// Minecraft's own ground flag (mining speed, the server's fall checks) follows ULTRAKILL's ground
		// check: a step with gravity off moves nothing vertically, which Minecraft would read as airborne.
		this.player.setOnGround(this.groundTouching);
	}

	private void fixedUpdate() {
		if (this.sliding) {
			if (this.slideSafety <= 0) {
				double horizontalSpeed = new Vec3(this.body.velocity.x, 0, this.body.velocity.z).length();
				if (horizontalSpeed < 10) {
					this.slideSafety = U.moveTowards(this.slideSafety, -0.1, DT);
					if (this.slideSafety <= -0.1) {
						stopSlide();
					}
				} else {
					this.slideSafety = 0;
				}
			}
			if (this.onWall && upwardSpeed() < 0) {
				this.body.addAcceleration(UltraBody.gravityVector().scale(-0.4));
			}
		}
		if (!this.sliding) {
			this.framesSinceSlide++;
			if (this.heavyFall) {
				this.preSlideDelay = 0.2;
				this.preSlideSpeed = this.slamForce;
				snapSlamToGround();
			} else if (!this.boost && this.falling && this.body.velocity.length() / 24 > this.preSlideSpeed) {
				this.preSlideSpeed = this.body.velocity.length() / 24;
				this.preSlideDelay = 0.2;
			} else {
				this.preSlideDelay = U.moveTowards(this.preSlideDelay, 0, DT);
				if (this.preSlideDelay <= 0) {
					this.preSlideDelay = 0.2;
					this.preSlideSpeed = this.body.velocity.length() / 24;
				}
			}
		}
		this.windState -= DT;
		if (this.boost) {
			this.body.useGravity = true;
			dodge();
		} else {
			move();
		}
		if (!this.boost || this.boostLeft <= 0) {
			this.preDashSpeed = this.body.velocity;
		}
	}

	/** While slamming, lands on ground the slam is about to reach this step. */
	private void snapSlamToGround() {
		double reach = 0.35 * S + DT * Math.abs(this.body.velocity.y) * displacementScale();
		Vec3 allowed = Entity.collideBoundingBox(this.player, new Vec3(0, -reach, 0), this.player.getBoundingBox(), level(), List.of());
		if (allowed.y > -reach + 1.0E-7) {
			this.player.setPos(this.player.getX(), this.player.getY() + allowed.y, this.player.getZ());
			this.body.velocity = Vec3.ZERO;
		}
	}

	private void move() {
		this.slideEnding = false;
		this.invincible = false;
		if (this.onGround && !this.jumping) {
			this.currentWallJumps = 0;
			this.rocketJumps = 0;
			double downwardSpeed = G.dot(this.body.velocity);
			if (this.slopeOnGround && this.inputDir.x == 0 && this.inputDir.z == 0) {
				downwardSpeed = 0;
				this.body.useGravity = false;
			} else {
				this.body.useGravity = true;
			}
			double speed = this.slowMode ? 1.25 : 2.75;
			Vec3 targetVel = this.inputDir.scale(WALK_SPEED * DT * speed).add(G.scale(downwardSpeed));
			this.body.velocity = U.lerp(this.body.velocity, targetVel, 0.25);
			return;
		}

		this.body.useGravity = true;
		Vec3 wishVelocity = this.inputDir.scale((this.slowMode ? 1.25 : 2.75) * DT * WALK_SPEED);
		Vec3 horizontal = U.projectOnPlane(this.body.velocity, G);
		Vec3 forward = forward();
		Vec3 right = right();
		this.airDirection = Vec3.ZERO;
		double wind = this.windState > 0 ? 1 : !(this.windState > -0.25) ? 0 : U.inverseLerp(0, -0.25, this.windState);
		double controlBoost = U.lerp(1, 2, wind);
		if (this.inputDir.length() > 0) {
			double acceleration = AIR_ACCELERATION * U.lerp(1, 4, wind) * DT * (1 / UltraBody.MASS);
			Vec3 strafe = U.project(this.inputDir, right);
			double strafeSpeed = horizontal.dot(U.normalized(strafe));
			double strafeAcceleration = acceleration;
			if (strafeSpeed + strafeAcceleration > 16.5) {
				strafeAcceleration = Math.max(16.5 - strafeSpeed, 0);
			}
			Vec3 forwards = U.project(this.inputDir, forward);
			double forwardSpeed = horizontal.dot(U.normalized(forwards));
			double forwardAcceleration = acceleration;
			if (forwardSpeed + forwardAcceleration > 16.5) {
				forwardAcceleration = Math.max(16.5 - forwardSpeed, 0);
			}
			Vec3 accelerationVector = strafe.scale(strafeAcceleration).add(forwards.scale(forwardAcceleration));
			Vec3 heading = horizontal.length() > 0.001 ? U.normalized(horizontal) : this.inputDir;
			Vec3 across = U.projectOnPlane(accelerationVector, heading);
			Vec3 along = U.project(accelerationVector, heading);
			double alongFactor = U.lerp(0.1, 0.4, 1 - wind);
			boolean speedingUp = along.dot(heading) > 0;
			boolean overCap = horizontal.length() > 16.5;
			this.airDirection = this.airDirection.add(across).add(along.scale(speedingUp && overCap ? alongFactor : 1));
			double remaining = 1 - this.airDirection.length() / (acceleration + 0.0001);
			remaining = Math.pow(remaining, 3);
			double maxTurn = AIR_ACCELERATION * (1 / UltraBody.MASS) * DT * controlBoost * remaining;
			Vec3 turn = this.inputDir.scale(horizontal.length()).subtract(horizontal);
			Vec3 turnAlong = U.project(turn, heading);
			turn = U.projectOnPlane(turn, heading);
			if (turnAlong.dot(horizontal) < 0) {
				turn = turn.add(turnAlong.scale(U.lerp(0.4, 1, 1 - wind)));
			} else {
				turn = turn.add(turnAlong);
			}
			this.airDirection = this.airDirection.add(U.clampMagnitude(turn, maxTurn));
		}
		this.body.addVelocityChange(this.airDirection);
	}

	private void dodge() {
		if (this.sliding) {
			if (this.boostLeft <= 0) {
				this.invincible = false;
			}
			double speed = 1;
			if (this.preSlideSpeed > 1) {
				if (this.preSlideSpeed > 3) {
					this.preSlideSpeed = 3;
				}
				speed = this.preSlideSpeed;
				if (this.onGround) {
					this.preSlideSpeed -= DT * this.preSlideSpeed;
				}
				this.preSlideDelay = 0;
			}
			double upward = upwardSpeed();
			Vec3 slide = U.projectOnPlane(this.dodgeDirection, G).scale(WALK_SPEED * DT * 4 * speed).add(UP.scale(upward));
			if (this.boostLeft > 0) {
				this.dashStorage = U.moveTowards(this.dashStorage, 0, DT);
				if (this.dashStorage <= 0) {
					this.boostLeft = 0;
				}
			}
			this.inputDir = U.clampMagnitude(right().scale(this.input.moveX), 1).scale(5);
			this.body.velocity = slide.add(this.inputDir);
			return;
		}

		if (this.framesSinceSlide <= 3) {
			return;
		}
		double upward = this.slideEnding ? upwardSpeed() : 0;
		double angle = U.angle(UP, this.dodgeDirection);
		if (angle < 85 || angle > 95) {
			this.boost = false;
			return;
		}
		if (this.onGround) {
			this.dashedFromGround = true;
		}
		Vec3 targetVel = this.dodgeDirection.scale(WALK_SPEED * DT * 2.75).add(UP.scale(upward));
		this.invincible = true;
		if (this.slideEnding) {
			this.slideEnding = false;
			if (!this.onGround) {
				this.boost = false;
				return;
			}
		}
		if (this.boostLeft > 0) {
			this.body.velocity = targetVel.scale(3);
			this.boostLeft -= 4;
			return;
		}
		this.body.velocity = targetVel;
		this.boost = false;
	}

	// ---- Update -------------------------------------------------------------------------------

	private void update(double dt) {
		this.movementHor = this.input.moveX;
		this.inputDir = U.clampMagnitude(right().scale(this.input.moveX).add(forward().scale(this.input.moveY)), 1);

		if (this.onGround) {
			this.fallTime = 0;
			this.clingFade = 0;
		} else {
			if (this.fallTime < 1) {
				this.fallTime += dt * 5;
			} else {
				this.falling = true;
			}
			double upward = upwardSpeed();
			if (upward < -2) {
				this.fallSpeed = upward;
			}
			if (upward < -20) {
				this.sounds.woosh(Math.abs(this.fallSpeed) / 80, Math.abs(this.fallSpeed) / 120);
			} else {
				this.sounds.woosh(0, 0);
			}
			if (this.windState > 0) {
				this.sounds.woosh(Math.min(this.windState, 1), 1);
			}
			if (upward < -100) {
				this.body.velocity = U.projectOnPlane(this.body.velocity, G).add(G.scale(100));
			}
		}
		if (this.falling) {
			checkLanding();
		} else {
			this.sounds.woosh(0, 0);
		}
		handleInputs();
		if (this.onGround) {
			this.sounds.wallScrape(null);
		} else {
			cling(dt);
		}
		tryFloorSnap(dt);
		if (this.heavyFall) {
			if (!this.slamStorage) {
				this.body.velocity = G.scale(100);
			}
			this.slamForce += dt * 5;
		}
		if (this.stillHolding && this.input.slideReleased) {
			this.stillHolding = false;
		}
		handleSlideState(dt);
		this.walking = (this.input.moveX != 0 || this.input.moveY != 0) && !this.sliding && this.onGround;
		if (this.boostCharge != 300 && !this.sliding && !this.slowMode) {
			this.boostCharge = U.moveTowards(this.boostCharge, 300, 70 * dt);
		}
		if (this.slamCooldown > 0) {
			this.slamCooldown = U.moveTowards(this.slamCooldown, 0, dt);
		}
	}

	private void handleInputs() {
		boolean canGroundJump = !this.falling;
		boolean canEnemyStep = !this.onGround && (this.canJump || enemyNearWallCheck());
		if (this.input.jumpPressed && !this.jumpCooldown) {
			if (canEnemyStep) {
				this.enemyStepping = true;
				enemyStepResets();
				if (this.sliding || this.clock - this.slideTimestamp < 0.10000000149011612) {
					this.windState = 0.5;
				}
				jump();
			} else if (canGroundJump) {
				jump();
			}
		}
		if (this.input.slidePressed) {
			boolean groundClose = raycast(groundCheckPosition().add(UP.scale(S)), G, 2) >= 0;
			if ((this.onGround || this.sinceLastGrounded < 0.06 || this.lastJump < 0.06 || groundClose) && (!this.slowMode || this.crouching) && !this.sliding) {
				startSlide();
			}
		}
		if (!this.slowMode && this.input.dashPressed) {
			tryDash();
		}
		if (!this.onGround) {
			if (this.input.jumpPressed && this.currentWallJumps < 3 && !this.jumpCooldown && this.onWall) {
				wallJump();
			}
			if (this.input.slidePressed) {
				tryStartSlam();
			}
		}
	}

	private void tryDash() {
		this.dashedFromGround = this.onGround;
		if (this.boostCharge < 100) {
			this.sounds.play("stamina_fail", 1, 1);
			return;
		}
		if (this.sliding) {
			stopSlide();
		}
		this.boostLeft = 100;
		this.dashStorage = 1;
		this.boost = true;
		this.dodgeDirection = U.isZero(this.inputDir) ? forward() : this.inputDir;
		if (!UltraConfig.get().infiniteStamina) {
			this.boostCharge -= 100;
		}
		this.camera.dodgeDirection = UltraCamera.dodgeIndex(this.dodgeDirection, forward());
		this.sounds.play(Channel.MAIN, "dodge", 1, 1);
		if (this.heavyFall) {
			this.fallSpeed = 0;
			this.heavyFall = false;
		}
	}

	private void tryStartSlam() {
		if (this.dashedFromGround && this.boost && !this.sliding) {
			this.dashedFromGround = false;
			this.windState = 0.5;
			this.boost = false;
		}
		boolean groundBelow = raycast(groundCheckPosition().add(UP.scale(S)), G, 3) >= 0;
		if (!groundBelow && this.fallTime > 0.5 && this.slamCooldown == 0 && !this.heavyFall) {
			if (this.boost) {
				this.boostLeft = 0;
				this.boost = false;
			}
			if (this.sliding) {
				stopSlide();
			}
			this.falling = true;
			this.fallSpeed = -100;
			this.body.velocity = new Vec3(0, -100, 0);
			this.stillHolding = true;
			this.heavyFall = true;
			this.slamForce = 1;
		}
	}

	private void checkLanding() {
		if (!this.onGround) {
			return;
		}
		if (this.fallSpeed > SOFT_LANDING_SPEED) {
			footstep(0.25F, true, 0);
		} else if (this.fallSpeed > -50) {
			this.sounds.play(Channel.GROUND, "landing", (float)(0.5 + this.fallSpeed * -0.01) * LANDING_VOLUME, this.sounds.randomPitch());
			footstep(0.5F, true, 0);
			footstep(0.5F, true, 1);
		} else {
			this.sounds.play("landing_heavy", LANDING_VOLUME, 1);
			footstep(1, true, 0);
			footstep(1, true, 1);
		}
		if (!this.jumpCooldown) {
			this.falling = false;
		}
		this.fallSpeed = 0;
		this.slamStorage = false;
		this.heavyFall = false;
	}

	/** Sliding down a wall: holding into it while falling slows the fall at first. */
	private void cling(double dt) {
		boolean againstWall = this.onWall && raycast(root(), this.inputDir, 1) >= 0;
		if (!this.sliding && againstWall && !this.heavyFall && upwardSpeed() < -1) {
			Vec3 right = right();
			Vec3 forward = forward();
			double sideways = U.clamp(-1, 1, right.dot(this.body.velocity));
			double forwards = U.clamp(-1, 1, forward.dot(this.body.velocity));
			this.body.velocity = right.scale(sideways).add(forward.scale(forwards)).add(G.scale(2 * this.clingFade));
			this.clingFade = U.moveTowards(this.clingFade, 50, dt * 4);
			this.sounds.wallScrape(wallSurface());
		} else {
			this.sounds.wallScrape(null);
		}
	}

	private void handleSlideState(double dt) {
		if (this.sliding) {
			if (this.input.slideReleased || this.slowMode && !this.crouching) {
				stopSlide();
			}
			this.standing = false;
			this.camera.crouch();
			if (this.slideSafety > 0) {
				this.slideSafety -= dt * 5;
			}
			if (this.sliding && (this.onGround || this.onWall)) {
				this.camera.shake(0.1);
				this.sounds.slide(!this.onGround ? wallSurface() : groundSurface());
			} else {
				this.sounds.slide(null);
			}
			return;
		}
		if (this.shortCollider) {
			if (!canStandWithFeetAt(0)) {
				this.crouching = true;
				this.slowMode = true;
				return;
			}
			this.crouching = false;
			this.slowMode = false;
			boolean groundBelow = raycast(root(), G, 2.25) >= 0;
			if (!groundBelow && canStandWithFeetAt(-1.75)) {
				// In the air, the body grows downwards.
				this.player.setPos(this.player.getX(), this.player.getY() - 1.75 * S, this.player.getZ());
				shiftDrawnPosition(new Vec3(0, -1.75 * S, 0));
				this.standing = true;
			}
			setShortCollider(false);
			this.camera.defaultTarget = UltraCamera.ORIGINAL_HEIGHT;
		} else if (this.camera.defaultTarget != UltraCamera.ORIGINAL_HEIGHT) {
			this.camera.defaultTarget = UltraCamera.ORIGINAL_HEIGHT;
		} else {
			this.standing = true;
		}
	}

	/** Walking down slopes and small steps keeps the player on the ground. */
	private void tryFloorSnap(double dt) {
		if (this.slopeOnGround || this.jumping || this.boost || U.isZero(this.body.velocity)) {
			return;
		}
		double rootHeight = rootHeight();
		double distance = raycast(root(), G, rootHeight + 1);
		if (distance < 0) {
			return;
		}
		double rise = U.moveTowards(0, rootHeight - distance, distance * dt * 10);
		moveDrawn(new Vec3(0, rise * S, 0));
		if (U.normalized(this.body.velocity).dot(UP) > 0) {
			this.body.velocity = U.projectOnPlane(this.body.velocity, UP);
		}
	}

	private void startSlide() {
		if (!this.crouching) {
			setShortCollider(true);
		}
		this.slideSafety = 1;
		this.sliding = true;
		this.boost = true;
		this.dodgeDirection = U.isZero(this.inputDir) ? forward() : this.inputDir;
		this.camera.dodgeDirection = UltraCamera.dodgeIndex(this.dodgeDirection, forward());
	}

	private void stopSlide() {
		this.sounds.slide(null);
		this.sounds.play("slide_stop", 1, 1);
		this.camera.resetToDefaultPos();
		this.sliding = false;
		this.slideEnding = true;
		this.framesSinceSlide = 0;
		this.velocityAfterSlide = U.normalized(this.dodgeDirection).scale(Math.max(24, this.preDashSpeed.length()));
	}

	private void jump() {
		this.fallTime = 0;
		this.lastJump = 0;
		double force = JUMP_POWER * 1500;
		this.jumping = true;
		this.notJumpingTimer = 0.25;
		this.falling = true;
		if (this.superJumpChance > 0) {
			this.sounds.play(Channel.MAIN, "jump", 0.85F * JUMP_VOLUME, 2);
		} else {
			this.sounds.play(Channel.MAIN, "jump", 0.75F * JUMP_VOLUME, 1);
		}
		this.body.velocity = U.projectOnPlane(this.body.velocity, UP);
		if (this.sliding) {
			this.body.addForce(UP.scale(force * (this.slowMode ? 1 : 2)));
			stopSlide();
		} else if (this.boost && this.jumpTimestamp - this.slideTimestamp > 0.008F * SSJ_MAX_FRAMES) {
			if (this.enemyStepping) {
				this.sounds.play("dash_jump", 1, 1);
				this.windState = 0.5;
			} else if (this.boostCharge >= 100) {
				if (!UltraConfig.get().infiniteStamina) {
					this.boostCharge -= 100;
				}
				this.sounds.play("dash_jump", 1, 1);
			} else {
				double speed = WALK_SPEED * DT * 2.75;
				this.body.velocity = new Vec3(this.inputDir.x * speed, 0, this.inputDir.z * speed);
				this.sounds.play("stamina_fail", 1, 1);
			}
			this.body.addForce(UP.scale(force * (this.slowMode ? 0.75 : 1.5)));
		} else if (this.slowMode) {
			this.body.addForce(UP.scale(force * 1.25));
		} else if (this.superJumpChance > 0 || this.extraJumpChance > 0) {
			this.body.addForce(UP.scale(force * (this.slamForce < 5.5 ? 3 + (this.slamForce - 1) : 12.5)));
		} else {
			this.body.addForce(UP.scale(force * 2.6));
		}
		trySSJ(U.normalized(this.dodgeDirection), 0.5, frame -> 1 / Math.pow(2, frame - 1));
		this.jumpCooldown = true;
		this.jumpReadyTimer = 0.2;
		this.boost = false;
		this.heavyFall = false;
		this.enemyStepping = false;
	}

	/** Super slide jump: jumping within a few physics frames of letting go of a slide keeps its speed. */
	private void trySSJ(Vec3 direction, double speedMultiplier, IntToDoubleFunction speedLoss) {
		double sinceSlide = this.jumpTimestamp - this.slideTimestamp;
		if (!(sinceSlide > 0)) {
			return;
		}
		int frames = (int)(sinceSlide / ONE_PHYSICS_FRAME);
		if (frames == 0 || frames >= SSJ_MAX_FRAMES) {
			return;
		}
		double bonus = speedLoss.applyAsDouble(frames) * speedMultiplier * WALK_SPEED * 2.75 * 3 * DT;
		double y = this.body.velocity.y;
		Vec3 velocity = this.velocityAfterSlide.add(direction.scale(bonus));
		velocity = new Vec3(velocity.x, y, velocity.z);
		this.body.velocity = U.normalized(velocity).scale(Math.min(velocity.length(), 100));
	}

	private void wallJump() {
		this.jumping = true;
		this.notJumpingTimer = scheduleEarliest(this.notJumpingTimer, 0.25);
		this.currentWallJumps++;
		if (this.heavyFall) {
			this.slamStorage = true;
		}
		this.sounds.play(Channel.MAIN, "jump", 0.75F * JUMP_VOLUME, 1 + 0.25F * this.currentWallJumps);
		if (this.currentWallJumps == 3) {
			this.sounds.play(Channel.GROUND, "wall_jump_final", 0.75F, 1);
		}
		this.sounds.play(Channel.FOOTSTEPS, "footstep." + wallSurface(), 0.5F, this.sounds.randomPitch());
		Vec3 awayFromWall = U.normalized(root().subtract(this.pointOfCollision));
		if (this.sliding || this.clock - this.slideTimestamp < SSJ_MAX_FRAMES * 0.008F) {
			Vec3 direction = U.reflect(U.normalized(this.dodgeDirection), awayFromWall);
			direction = U.normalized(U.projectOnPlane(direction, UP));
			direction = U.normalized(direction.add(awayFromWall.scale(0.35)));
			this.dodgeDirection = direction;
			this.body.velocity = direction.scale(this.body.velocity.length());
			trySSJ(direction, 0.75, frame -> (SSJ_MAX_FRAMES - frame + 1) / SSJ_MAX_FRAMES);
			double upward = this.body.velocity.dot(UP);
			this.body.velocity = U.projectOnPlane(this.body.velocity, G).add(UP.scale(Math.max(upward, 15)));
			this.windState = 0.5;
		} else {
			this.boost = false;
			this.body.velocity = Vec3.ZERO;
			Vec3 push = U.projectOnPlane(awayFromWall, UP).add(UP);
			this.body.addForce(push.scale(2000 * WALL_JUMP_POWER));
		}
		this.jumpCooldown = true;
		this.jumpReadyTimer = scheduleEarliest(this.jumpReadyTimer, 0.1);
	}

	private void enemyStepResets() {
		this.currentWallJumps = 0;
		this.rocketJumps = 0;
		this.clingFade = 0;
	}

	/** An outside push (knockback, explosions), handled like NewMovement.Launch. */
	private void launch(Vec3 velocityChange) {
		this.jumping = true;
		this.notJumpingTimer = scheduleEarliest(this.notJumpingTimer, 0.5);
		this.jumpCooldown = true;
		this.jumpReadyTimer = scheduleEarliest(this.jumpReadyTimer, 0.2);
		this.boost = false;
		if (this.heavyFall) {
			this.fallSpeed = 0;
			this.heavyFall = false;
		}
		this.body.velocity = this.body.velocity.add(velocityChange);
	}

	/**
	 * Explosion.Collide's push on the player: NewMovement.LaunchFromPoint, away from the explosion. A harmless
	 * rocket explosion pushes less with every rocket jump in a row, and a harmless explosion can't push again
	 * for half a second.
	 *
	 * @param origin the explosion's centre
	 * @param harmful whether the explosion also hurts the player
	 */
	public void explosionLaunch(Vec3 origin, double pushForceMultiplier, double maxSize, boolean rocketExplosion, boolean harmful) {
		if (this.safeExplosionLaunchCooldown > 0 && !harmful) {
			return;
		}
		double strength = 200;
		if (rocketExplosion && !harmful) {
			strength = Math.rint(100 / ((this.rocketJumps + 3) / 3.0));
			this.rocketJumps++;
		}
		Vec3 root = root();
		Vec3 offset = origin.subtract(root);
		Vec3 from = (offset.x * offset.x + offset.z * offset.z) / (S * S) < 0.0625 ? root : origin;
		launchFromPoint(from, strength * pushForceMultiplier, maxSize);
		if (!harmful) {
			this.safeExplosionLaunchCooldown = 0.5;
		}
	}

	/**
	 * NewMovement.LaunchFromPoint: away from {@code position}, harder the closer the player is to it within
	 * {@code maxDistance} units (at full strength while jumping), and at least upwards by half that.
	 */
	private void launchFromPoint(Vec3 position, double strength, double maxDistance) {
		Vec3 root = root();
		Vec3 away = root.equals(position) ? new Vec3(0, 1, 0) : root.subtract(position).normalize();
		double reach = this.jumping ? maxDistance : Math.max(0, maxDistance - root.distanceTo(position) / S);
		Vec3 push = away.scale(reach * strength);
		double up = Math.max(-G.dot(this.body.velocity), 0.5 * reach * strength);
		push = push.subtract(G.scale(G.dot(push))).subtract(G.scale(up));
		launchImpulse(push);
	}

	/** NewMovement.Launch: an impulse of {@code direction} × 8 on the player's 100 kg body, replacing its velocity. */
	private void launchImpulse(Vec3 direction) {
		if (direction.subtract(G).lengthSqr() < 1.0E-10 && this.onGround) {
			return;
		}
		if (direction.lengthSqr() > 0) {
			this.body.velocity = Vec3.ZERO;
		}
		launch(U.clampMagnitude(direction, 1000).scale(8 / MASS));
	}

	/** Unity's Invoke without CancelInvoke: when one is already pending, the earlier one fires first. */
	private static double scheduleEarliest(double pending, double delay) {
		return pending > 0 ? Math.min(pending, delay) : delay;
	}

	private void runTimers(double dt) {
		this.safeExplosionLaunchCooldown = U.moveTowards(this.safeExplosionLaunchCooldown, 0, dt);
		if (this.notJumpingTimer > 0) {
			this.notJumpingTimer -= dt;
			if (this.notJumpingTimer <= 0) {
				this.jumping = false;
			}
		}
		if (this.jumpReadyTimer > 0) {
			this.jumpReadyTimer -= dt;
			if (this.jumpReadyTimer <= 0) {
				this.jumpCooldown = false;
			}
		}
	}

	/** PlayerFootsteps.Update: a step every third of a second at full walking speed. */
	private void footsteps(double dt) {
		if (this.walking) {
			this.footstepTimer = U.moveTowards(this.footstepTimer, 0, Math.min(this.body.velocity.length(), 15) / 15 * dt * 3);
		}
		if (this.footstepTimer <= 0) {
			footstep(0.25F, false, 0);
		}
	}

	private void footstep(float volume, boolean force, int delayTicks) {
		if (!this.onGround && !force) {
			return;
		}
		this.footstepTimer = 1;
		if (UltraConfig.get().ultrakillFootsteps) {
			String surface = this.player.isInWater() ? Surfaces.WET : groundSurface();
			this.sounds.play(Channel.FOOTSTEPS, "footstep." + surface, volume, this.sounds.randomPitch(), delayTicks);
		}
	}

	/** The surface under the player, found like SceneHelper.TryGetSurfaceData: straight down, 3 units. */
	private String groundSurface() {
		Vec3 from = this.player.position().add(0, 0.1, 0);
		BlockHitResult hit = level().clip(new ClipContext(from, from.add(0, -3 * S, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.player));
		return hit.getType() == HitResult.Type.MISS ? Surfaces.GENERIC : Surfaces.of(level().getBlockState(hit.getBlockPos()));
	}

	/** The surface of the wall the wall check touches. */
	private String wallSurface() {
		Vec3 inwards = U.normalized(this.pointOfCollision.subtract(wallCheckCenter())).scale(0.01);
		BlockState state = level().getBlockState(BlockPos.containing(this.pointOfCollision.add(inwards)));
		return state.isAir() ? Surfaces.GENERIC : Surfaces.of(state);
	}

	/** Stops the sounds that keep playing on their own. */
	public void close() {
		this.sounds.stopAll();
	}

	// ---- GroundCheck, WallCheck, ClimbStep ---------------------------------------------------

	private void updateTriggers() {
		boolean touching = groundOverlap(GROUND_CHECK_DEPTH, 0.001);
		if (touching && !this.groundTouching && this.heavyFall) {
			this.heavyFall = false;
			this.superJumpChance = 0.1;
		}
		this.groundTouching = touching;
		this.slopeOnGround = groundOverlap(SLOPE_CHECK_DEPTH, SLOPE_CHECK_INSET * S);
		this.canJump = enemyUnderFeet();
		updateWallCheck();
	}

	private void groundCheckUpdate(double dt) {
		this.onGround = this.groundTouching;
		if (this.onGround) {
			this.sinceLastGrounded = 0;
		}
		if (this.superJumpChance > 0) {
			this.superJumpChance = U.moveTowards(this.superJumpChance, 0, dt);
			if (this.superJumpChance == 0) {
				if (this.stillHolding) {
					this.camera.shake(0.75);
				}
				this.extraJumpChance = 0.306;
				this.stillHolding = false;
			}
		}
		if (this.extraJumpChance > 0) {
			this.extraJumpChance = U.moveTowards(this.extraJumpChance, 0, dt);
			if (this.extraJumpChance <= 0 && this.superJumpChance <= 0) {
				this.slamForce = 0;
			}
		}
	}

	private boolean groundOverlap(double depthUnits, double insetBlocks) {
		AABB box = this.player.getBoundingBox();
		AABB check = new AABB(
			box.minX + insetBlocks, box.minY - depthUnits * S, box.minZ + insetBlocks,
			box.maxX - insetBlocks, box.minY, box.maxZ - insetBlocks
		);
		return !level().noCollision(this.player, check);
	}

	private boolean enemyUnderFeet() {
		AABB box = this.player.getBoundingBox();
		AABB check = new AABB(box.minX, box.minY - GROUND_CHECK_DEPTH * S, box.minZ, box.maxX, box.minY + 0.5 * S, box.maxZ);
		return !level().getEntitiesOfClass(Mob.class, check, Mob::isAlive).isEmpty();
	}

	private Vec3 wallCheckCenter() {
		return this.player.position().add(0, (rootHeight() + WALL_CHECK_OFFSET) * S, 0);
	}

	private void updateWallCheck() {
		Vec3 center = wallCheckCenter();
		double radius = WALL_CHECK_RADIUS * S;
		double nearest = Double.MAX_VALUE;
		Vec3 point = null;
		for (VoxelShape shape : level().getBlockCollisions(this.player, new AABB(center, center).inflate(radius))) {
			for (AABB box : shape.toAabbs()) {
				Vec3 closest = closestPoint(box, center);
				double distance = closest.distanceTo(center);
				if (distance <= radius && distance < nearest) {
					nearest = distance;
					point = closest;
				}
			}
		}
		this.onWall = point != null;
		if (point != null) {
			this.pointOfCollision = point;
		}
	}

	private boolean enemyNearWallCheck() {
		Vec3 center = wallCheckCenter();
		double radius = ENEMY_CHECK_RADIUS * S;
		for (Mob mob : level().getEntitiesOfClass(Mob.class, new AABB(center, center).inflate(radius), Mob::isAlive)) {
			if (closestPoint(mob.getBoundingBox(), center).distanceTo(center) <= radius) {
				return true;
			}
		}
		return false;
	}

	private static Vec3 closestPoint(AABB box, Vec3 point) {
		return new Vec3(
			Math.clamp(point.x, box.minX, box.maxX),
			Math.clamp(point.y, box.minY, box.maxY),
			Math.clamp(point.z, box.minZ, box.maxZ)
		);
	}

	/** Walking into a ledge up to 2.1 units tall while not moving vertically steps onto it. */
	private void climbStep(UltraBody.Contact contact) {
		if (this.climbCooldown > 0 || Math.abs(this.body.velocity.y) >= 0.1) {
			return;
		}
		Vec3 heading = this.boost ? this.dodgeDirection : moveDirection();
		if (contact.hitX() && tryClimb(new Vec3(Math.signum(contact.attempted().x), 0, 0), heading, contact)) {
			return;
		}
		if (contact.hitZ()) {
			tryClimb(new Vec3(0, 0, Math.signum(contact.attempted().z)), heading, contact);
		}
	}

	private boolean tryClimb(Vec3 intoWall, Vec3 heading, UltraBody.Contact contact) {
		if (heading.dot(intoWall) <= 0.5) {
			return false;
		}
		Vec3 feet = this.player.position();
		double reach = this.player.getBbWidth() / 2 + 0.1 * S;
		Vec3 probeTop = feet.add(intoWall.scale(reach)).add(0, CLIMB_STEP * S, 0);
		BlockHitResult hit = level().clip(new ClipContext(probeTop, probeTop.add(0, -CLIMB_STEP * S, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.player));
		if (hit.getType() == HitResult.Type.MISS) {
			return false;
		}
		double rise = probeTop.y - hit.getLocation().y;
		rise = CLIMB_STEP * S - rise;
		if (rise <= 1.0E-4) {
			return false;
		}
		AABB raised = this.player.getBoundingBox().move(0, rise + 1.0E-4, 0);
		if (!level().noCollision(this.player, raised) || !level().noCollision(this.player, raised.move(intoWall.scale(0.5 * S)))) {
			return false;
		}
		this.climbCooldown = CLIMB_COOLDOWN;
		this.player.setPos(feet.x, feet.y + rise + 1.0E-4, feet.z);
		this.player.move(MoverType.SELF, intoWall.scale(CLIMB_FORWARD * S));
		this.body.velocity = contact.beforeContact();
		this.camera.holdWorldHeight(rise / S);
		return true;
	}

	// ---- helpers ------------------------------------------------------------------------------

	private void setShortCollider(boolean shortCollider) {
		if (this.shortCollider == shortCollider) {
			return;
		}
		this.shortCollider = shortCollider;
		((UltraPlayer)this.player).ultracraft$setUltraState(true, shortCollider);
	}

	private boolean canStandWithFeetAt(double offsetUnits) {
		AABB box = PlayerShape.STANDING.makeBoundingBox(this.player.position().add(0, offsetUnits * S, 0)).deflate(1.0E-7);
		return level().noCollision(this.player, box);
	}

	/** Moves the player now, outside a physics step; the camera follows without easing. */
	private void moveDrawn(Vec3 delta) {
		Vec3 start = this.player.position();
		this.player.move(MoverType.SELF, delta);
		shiftDrawnPosition(this.player.position().subtract(start));
	}

	private void shiftDrawnPosition(Vec3 delta) {
		this.previousFeet = this.previousFeet.add(delta);
		this.currentFeet = this.currentFeet.add(delta);
	}

	/**
	 * Casts a ray against block collision shapes.
	 *
	 * @return the hit distance in units, or -1
	 */
	private double raycast(Vec3 from, Vec3 direction, double lengthUnits) {
		if (U.isZero(direction)) {
			return -1;
		}
		Vec3 to = from.add(U.normalized(direction).scale(lengthUnits * S));
		BlockHitResult hit = level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.player));
		return hit.getType() == HitResult.Type.MISS ? -1 : from.distanceTo(hit.getLocation()) / S;
	}

	private double rootHeight() {
		return this.shortCollider ? SHORT_ROOT : STANDING_ROOT;
	}

	/** NewMovement's transform.position. */
	private Vec3 root() {
		return this.player.position().add(0, rootHeight() * S, 0);
	}

	private Vec3 groundCheckPosition() {
		return this.player.position().add(0, GROUND_CHECK_HEIGHT * S, 0);
	}

	private double upwardSpeed() {
		return UP.dot(this.body.velocity);
	}

	/** The player's facing, flat. */
	private Vec3 forward() {
		double yaw = Math.toRadians(this.player.getYRot());
		return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
	}

	private Vec3 right() {
		Vec3 forward = forward();
		return new Vec3(-forward.z, 0, forward.x);
	}

	/** ClimbStep's movementDirection: the raw input, before sliding rewrites inputDir. */
	private Vec3 moveDirection() {
		return U.clampMagnitude(right().scale(this.input.moveX).add(forward().scale(this.input.moveY)), 1);
	}

	private Level level() {
		return this.player.level();
	}

	// ---- for the camera, HUD and controller ----------------------------------------------------

	public boolean shortCollider() {
		return this.shortCollider;
	}

	/** NewMovement's transform.position, in blocks. */
	public Vec3 rootPosition() {
		return root();
	}

	/** Where the camera is drawn this frame, between the last two physics steps. */
	public Vec3 eyePosition() {
		Vec3 feet = U.lerp(this.previousFeet, this.currentFeet, this.accumulator / DT);
		Vec3 right = right();
		double yaw = Math.toRadians(this.player.getYRot());
		double pitch = Math.toRadians(this.player.getXRot());
		Vec3 up = new Vec3(-Math.sin(yaw) * Math.sin(pitch), Math.cos(pitch), Math.cos(yaw) * Math.sin(pitch));
		return feet
			.add(0, (rootHeight() + this.camera.height()) * S, 0)
			.add(right.scale(this.camera.shakeRight() * S))
			.add(up.scale(this.camera.shakeUp() * S));
	}

	public void kickFov(double amount) {
		this.camera.kickFov(amount);
	}

	public void shake(double amount) {
		this.camera.shake(amount);
	}

	/** The screen shake, every frame the game isn't paused, hitstops included. */
	public void updateShake(double dt) {
		this.camera.updateShake(dt);
	}

	/** NewMovement.FullStamina. */
	public void fullStamina() {
		this.boostCharge = 300;
	}

	/** Units per second. */
	public Vec3 velocity() {
		return this.body.velocity;
	}

	public double cameraRoll() {
		return this.camera.roll();
	}

	public double fovMultiplier() {
		return this.camera.fovMultiplier();
	}

	/** Stamina, 0 to 300: each dash costs 100. */
	public double stamina() {
		return this.boostCharge;
	}

	public double speed() {
		return this.body.velocity.length();
	}

	public String debugState() {
		return String.format(
			"ground %s slope %s wall %s | falling %s jumping %s cooldown %s | boost %s slide %s slam %s (force %.1f) | walljumps %d | wind %.2f",
			this.onGround, this.slopeOnGround, this.onWall, this.falling, this.jumping, this.jumpCooldown,
			this.boost, this.sliding, this.heavyFall, this.slamForce, this.currentWallJumps, this.windState
		);
	}
}
