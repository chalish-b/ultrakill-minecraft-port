package dev.ultracraft.client.combat;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.movement.UltraSounds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * A shotgun shell the Knuckleblaster ejects after its blast (ShotgunShell). It falls through everything for a
 * moment, then lands with a clink. Two seconds in it's gone, unless it has landed: then it stays on the floor
 * like the rest of the gibs.
 */
final class Shell {
	/** Physics.gravity, units per second squared. */
	private static final double GRAVITY = 40;
	/** Its collider turns on this long after it's ejected (TurnGib). */
	private static final double SOLID_AFTER = 0.2;
	/** When it's removed, or comes to rest for good if it has landed (Remove). */
	private static final double SETTLE_TIME = 2;

	Vec3 position;
	/** Units per second. */
	private Vec3 velocity;
	/** Its rotation in the world, for its model. */
	final Quaternionf rotation;
	private double age;
	private boolean landed;
	private boolean settled;
	boolean removed;

	Shell(Vec3 position, Vec3 velocity, Quaternionf rotation) {
		this.position = position;
		this.velocity = velocity;
		this.rotation = rotation;
	}

	void update(double dt, Level level, Entity thrower, UltraSounds sounds) {
		if (this.settled) {
			return;
		}
		this.age += dt;
		if (this.age > SETTLE_TIME) {
			this.removed = !this.landed;
			this.settled = true;
			return;
		}
		this.velocity = this.velocity.add(0, -GRAVITY * dt, 0);
		Vec3 next = this.position.add(this.velocity.scale(dt * PlayerShape.BLOCKS_PER_UNIT * UltraConfig.get().speedScale));
		BlockHitResult hit = this.age < SOLID_AFTER ? null
			: level.clip(new ClipContext(this.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrower));
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			if (!this.landed) {
				this.landed = true;
				sounds.play("shell_impact", 1, sounds.randomPitch(0.85F, 1.15F));
			}
			this.position = hit.getLocation().add(hit.getDirection().getUnitVec3().scale(0.02));
			this.velocity = Vec3.ZERO;
		} else {
			this.position = next;
		}
	}
}
