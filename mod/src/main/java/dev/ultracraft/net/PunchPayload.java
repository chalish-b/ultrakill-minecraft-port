package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import dev.ultracraft.combat.FistType;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the local player punched with one of their arms. */
public record PunchPayload(FistType fist) implements CustomPacketPayload {
	public static final Type<PunchPayload> TYPE = new Type<>(Ultracraft.id("punch"));
	public static final StreamCodec<ByteBuf, PunchPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.idMapper(i -> FistType.values()[i], FistType::ordinal), PunchPayload::fist,
		PunchPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
