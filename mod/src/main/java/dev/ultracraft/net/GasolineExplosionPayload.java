package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Server to client: gasoline lit in water exploded there (VoxelProxy.ExplodeAndDestroy). */
public record GasolineExplosionPayload(Vec3 at) implements CustomPacketPayload {
	public static final Type<GasolineExplosionPayload> TYPE = new Type<>(Ultracraft.id("gasoline_explosion"));
	public static final StreamCodec<ByteBuf, GasolineExplosionPayload> CODEC = StreamCodec.composite(
		Vec3.STREAM_CODEC, GasolineExplosionPayload::at,
		GasolineExplosionPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
