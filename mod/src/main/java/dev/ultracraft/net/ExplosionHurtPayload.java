package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the local player was caught in their own explosion and takes {@code damage} ULTRAKILL HP. */
public record ExplosionHurtPayload(int damage) implements CustomPacketPayload {
	public static final Type<ExplosionHurtPayload> TYPE = new Type<>(Ultracraft.id("explosion_hurt"));
	public static final StreamCodec<ByteBuf, ExplosionHurtPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, ExplosionHurtPayload::damage,
		ExplosionHurtPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
