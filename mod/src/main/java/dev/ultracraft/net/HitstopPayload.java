package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: something the local player did stops the world for {@code millis} (TimeController.TrueStop). */
public record HitstopPayload(int millis) implements CustomPacketPayload {
	public static final Type<HitstopPayload> TYPE = new Type<>(Ultracraft.id("hitstop"));
	public static final StreamCodec<ByteBuf, HitstopPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, HitstopPayload::millis,
		HitstopPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
