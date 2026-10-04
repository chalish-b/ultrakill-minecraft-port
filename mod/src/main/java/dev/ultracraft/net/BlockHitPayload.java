package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the local player's weapon hit a block.
 *
 * @param damage ULTRAKILL damage
 */
public record BlockHitPayload(BlockPos pos, float damage) implements CustomPacketPayload {
	public static final Type<BlockHitPayload> TYPE = new Type<>(Ultracraft.id("block_hit"));
	public static final StreamCodec<ByteBuf, BlockHitPayload> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, BlockHitPayload::pos,
		ByteBufCodecs.FLOAT, BlockHitPayload::damage,
		BlockHitPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
