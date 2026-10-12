package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: switch the weapon in a hotbar slot to another of its variants.
 *
 * @param slot the hotbar slot
 * @param color the variant's color (blue, green, red)
 * @param alternate whether it's the alternate version
 */
public record SwitchVariantPayload(int slot, int color, boolean alternate) implements CustomPacketPayload {
	public static final Type<SwitchVariantPayload> TYPE = new Type<>(Ultracraft.id("switch_variant"));
	public static final StreamCodec<ByteBuf, SwitchVariantPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, SwitchVariantPayload::slot,
		ByteBufCodecs.VAR_INT, SwitchVariantPayload::color,
		ByteBufCodecs.BOOL, SwitchVariantPayload::alternate,
		SwitchVariantPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
