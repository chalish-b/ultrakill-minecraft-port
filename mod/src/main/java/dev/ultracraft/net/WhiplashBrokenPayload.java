package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the enemy on the player's Whiplash teleported away, which knocks the hook off it. */
public record WhiplashBrokenPayload() implements CustomPacketPayload {
	public static final Type<WhiplashBrokenPayload> TYPE = new Type<>(Ultracraft.id("whiplash_broken"));
	public static final StreamCodec<ByteBuf, WhiplashBrokenPayload> CODEC = StreamCodec.unit(new WhiplashBrokenPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
