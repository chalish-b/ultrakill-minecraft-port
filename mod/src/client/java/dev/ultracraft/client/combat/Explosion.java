package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * ULTRAKILL's Explosion. Its trigger sphere grows every physics step and hits whatever it touches once, through
 * walls: enemies take damage and are knocked away (upwards too), projectiles turn friendly and fly away from it,
 * and loose objects are thrown. Its damage drops by a third once it's past half its size. At full size it turns
 * harmless, keeps growing at a quarter of the speed, and fades out.
 */
final class Explosion {
	/** Time.fixedDeltaTime: the sphere grows once per physics step. */
	private static final double STEP = 0.008;
	private static final double S = PlayerShape.BLOCKS_PER_UNIT;
	/** The sphere fades out at this much alpha a second once it's done. */
	private static final double FADE_SPEED = 2;
	/** The material is plain white for this long before it shows its own (whiteExplosion). */
	static final double WHITE_TIME = 0.1;
	/** The explosion's object is removed after this long (its RemoveOnTime). */
	private static final double LIFETIME = 10;

	/** AffectedSubjects: who the explosion can hit. */
	enum Subjects {
		ALL,
		PLAYER_ONLY,
		ENEMIES_ONLY
	}

	/**
	 * An explosion prefab's values.
	 *
	 * @param scale the explosion's scale from the prefab root above it
	 * @param startScale the explosion's own starting scale
	 * @param colliderRadius its trigger sphere's radius at scale 1; the sphere's radius is all three multiplied
	 * @param speed its scale grows by 0.05 × speed every physics step
	 * @param maxSize the radius (units) at which it's done
	 * @param damage the player's damage in HP; an enemy takes a tenth of it, times {@code enemyDamageMultiplier}
	 * @param halved whether the damage starts out already reduced, so it doesn't drop again
	 * @param rocketExplosion whether a harmless one pushes the player less with every rocket jump in a row
	 * @param playerDamageOverride the player's damage in place of {@code damage}, or -1
	 */
	record Settings(
		double scale, double startScale, double colliderRadius, double speed, double maxSize, int damage,
		double enemyDamageMultiplier, double pushForceMultiplier, boolean halved, Subjects canHit,
		boolean rocketExplosion, int playerDamageOverride
	) {
	}

	/** "Explosion Wave Knuckleblaster", the Knuckleblaster's blast: it only hits enemies, for 1 damage. */
	static final Settings KNUCKLEBLASTER = new Settings(12, 0.1, 0.2236069, 2.5, 12, 10, 1, 1.5, true, Subjects.ENEMIES_ONLY, false, -1);
	/** "Explosion", the standard explosion's blast: 3.5 to enemies within 6 units (2.3 past half of that) and 35 HP to the player. */
	static final Settings STANDARD = new Settings(12, 1, 0.2236069, 1, 6, 35, 1, 1, false, Subjects.ALL, false, -1);
	/** The standard explosion's second sphere: a wider, harmless wave that only pushes enemies. */
	static final Settings STANDARD_WAVE = new Settings(12, 0.1, 0.2236069, 2.5, 8, 0, 0, 1, false, Subjects.ENEMIES_ONLY, false, -1);

	/** What the explosion does to what it reaches; units per second throughout. */
	interface Hits {
		void enemy(LivingEntity enemy, float damage, Vec3 push);

		/** The projectile flies along {@code push}, at no less than its length. */
		void projectile(Projectile projectile, Vec3 push);

		/** The object's velocity becomes {@code velocity}. */
		void object(Entity object, Vec3 velocity);

		void player(Explosion explosion);
	}

	final Settings settings;
	/** Blocks. */
	final Vec3 origin;
	/** The prefab's rotation in the world. */
	final Quaternionf rotation;
	/** playerProjectileForceDirection: enemies, the player's projectiles and objects go this way instead of away. */
	private final @Nullable Vec3 forceDirection;
	/** From a boosted projectile: it launches the player at a set speed instead. */
	final boolean boosted;
	private final Set<Integer> hit = new HashSet<>();
	private double localScale;
	private double speed;
	private int damage;
	private boolean halved;
	private boolean harmless;
	private boolean fading;
	private boolean hitPlayer;
	private double accumulator;
	private double age;
	/** The material's alpha. */
	private double alpha = 1;
	boolean removed;

	Explosion(Settings settings, Vec3 origin, Quaternionf rotation, @Nullable Vec3 forceDirection) {
		this(settings, origin, rotation, forceDirection, false);
	}

	Explosion(Settings settings, Vec3 origin, Quaternionf rotation, @Nullable Vec3 forceDirection, boolean boosted) {
		this.settings = settings;
		this.origin = origin;
		this.rotation = rotation;
		this.forceDirection = forceDirection;
		this.boosted = boosted;
		this.localScale = settings.startScale;
		this.speed = settings.speed;
		this.damage = settings.damage;
		this.halved = settings.halved;
	}

	/** Explosion.Start shakes the camera: fully within three times its size, less further off, a quarter if it's harmless. */
	double cameraShake(Vec3 camera) {
		double distance = camera.distanceTo(this.origin) / S;
		double harmless = this.damage == 0 ? 0.25 : 1;
		if (distance < 3 * this.settings.maxSize) {
			return 1.5 * harmless;
		}
		if (distance < 85) {
			return (1.5 - (distance - 20) / 65 * 1.5) / 6 * this.settings.maxSize * harmless;
		}
		return 0;
	}

	/** The sphere's radius, in units. */
	double radius() {
		return this.settings.scale * this.localScale * this.settings.colliderRadius;
	}

	/** The sphere's scale from the prefab root down: what its mesh is drawn at. */
	double meshScale() {
		return this.settings.scale * this.localScale;
	}

	double age() {
		return this.age;
	}

	double alpha() {
		return this.alpha;
	}

	void update(double dt, Level level, LocalPlayer player, Hits hits) {
		this.age += dt;
		this.accumulator += dt;
		while (this.accumulator >= STEP) {
			this.accumulator -= STEP;
			step(level, player, hits);
		}
		if (this.fading) {
			this.alpha -= FADE_SPEED * dt;
		}
		if (this.alpha <= 0 || this.age > LIFETIME) {
			this.removed = true;
		}
	}

	/** Explosion.FixedUpdate, then the trigger's contacts. */
	private void step(Level level, LocalPlayer player, Hits hits) {
		this.localScale += 0.05 * this.speed;
		double radius = radius();
		if (!this.fading && radius > this.settings.maxSize) {
			this.harmless = true;
			this.fading = true;
			this.speed /= 4;
		}
		if (!this.halved && radius > this.settings.maxSize / 2) {
			this.halved = true;
			this.damage = (int)Math.rint(this.damage / 1.5);
		}
		if (!this.harmless) {
			collide(level, player, hits, radius * S);
		}
	}

	/** Explosion.Collide, for everything the sphere has newly reached. */
	private void collide(Level level, LocalPlayer player, Hits hits, double radius) {
		AABB area = new AABB(this.origin, this.origin).inflate(radius);
		for (Entity entity : level.getEntities((Entity)null, area, e -> !e.isSpectator())) {
			if (this.hit.contains(entity.getId()) || !touches(entity.getBoundingBox(), radius)) {
				continue;
			}
			if (entity == player) {
				this.hit.add(entity.getId());
				if (!this.hitPlayer && this.settings.canHit != Subjects.ENEMIES_ONLY) {
					this.hitPlayer = true;
					hits.player(this);
				}
				continue;
			}
			Vec3 position = entity.getBoundingBox().getCenter();
			Vec3 away = position.subtract(this.origin).normalize();
			if (entity instanceof LivingEntity enemy) {
				if (!enemy.isAlive() || this.settings.canHit == Subjects.PLAYER_ONLY) {
					continue;
				}
				this.hit.add(entity.getId());
				Vec3 direction = this.forceDirection != null ? this.forceDirection : away;
				if (direction.y <= 0.5) {
					direction = new Vec3(direction.x, direction.y + 0.5, direction.z);
				} else if (direction.y < 1) {
					direction = new Vec3(direction.x, 1, direction.z);
				}
				// DeliverDamage's force (direction × 50000 × pushForceMultiplier) comes to this much speed.
				hits.enemy(enemy, (float)(this.damage / 10.0 * this.settings.enemyDamageMultiplier), direction.scale(50 * this.settings.pushForceMultiplier));
			} else if (entity instanceof Projectile projectile) {
				this.hit.add(entity.getId());
				Vec3 direction = projectile.getOwner() == player && this.forceDirection != null ? this.forceDirection : away;
				hits.projectile(projectile, direction.scale(65));
			} else if (thrown(entity) && this.forceDirection != null) {
				this.hit.add(entity.getId());
				hits.object(entity, this.forceDirection.scale(100));
			}
		}
	}

	/** Loose things an explosion throws, as it does ULTRAKILL's physics objects. */
	private static boolean thrown(Entity entity) {
		return entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof FallingBlockEntity || entity instanceof PrimedTnt;
	}

	private boolean touches(AABB box, double radius) {
		double x = Math.clamp(this.origin.x, box.minX, box.maxX) - this.origin.x;
		double y = Math.clamp(this.origin.y, box.minY, box.maxY) - this.origin.y;
		double z = Math.clamp(this.origin.z, box.minZ, box.maxZ) - this.origin.z;
		return x * x + y * y + z * z <= radius * radius;
	}

	/** Whether it hurts the player, rather than only pushing them. */
	boolean harmful() {
		return this.damage > 0;
	}

	/** The player's damage in HP, if it's {@link #harmful}. */
	int playerDamage() {
		return this.settings.playerDamageOverride >= 0 ? this.settings.playerDamageOverride : this.damage;
	}
}
