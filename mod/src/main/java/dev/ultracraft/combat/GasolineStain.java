package dev.ultracraft.combat;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * A gasoline stain (GasolineStain): a splat flat on a surface where a drop of the Firestarter's spray landed.
 *
 * @param position where the drop hit, on the surface
 * @param face which way the surface faces
 * @param angle its turn about the surface's normal, in degrees
 * @param splat which of the splatter atlas's splats it is
 * @param lit whether it burns
 */
public record GasolineStain(Vec3 position, Direction face, float angle, int splat, boolean lit) {
	public static final StreamCodec<ByteBuf, GasolineStain> STREAM_CODEC = StreamCodec.composite(
		Vec3.STREAM_CODEC, GasolineStain::position,
		Direction.STREAM_CODEC, GasolineStain::face,
		ByteBufCodecs.FLOAT, GasolineStain::angle,
		ByteBufCodecs.VAR_INT, GasolineStain::splat,
		ByteBufCodecs.BOOL, GasolineStain::lit,
		GasolineStain::new
	);

	GasolineStain burning() {
		return new GasolineStain(this.position, this.face, this.angle, this.splat, true);
	}

	/** GasolineStain.IsFloor: it's on a floor. */
	public boolean floor() {
		return this.face == Direction.UP;
	}

	/** The block space it's in, in front of its surface: where it burns. */
	public BlockPos block() {
		return BlockPos.containing(this.position.add(this.face.getUnitVec3().scale(1.0E-3)));
	}

	/** The block its surface belongs to. */
	public BlockPos surface() {
		return BlockPos.containing(this.position.subtract(this.face.getUnitVec3().scale(1.0E-3)));
	}
}
