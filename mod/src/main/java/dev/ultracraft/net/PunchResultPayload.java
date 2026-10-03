package dev.ultracraft.net;

import dev.ultracraft.Ultracraft;
import dev.ultracraft.combat.Gore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Server to client: what a punch connected with.
 *
 * @param gore the blood an enemy hit leaves
 */
public record PunchResultPayload(Result result, Vec3 position, Gore gore) implements CustomPacketPayload {
	public static final Type<PunchResultPayload> TYPE = new Type<>(Ultracraft.id("punch_result"));
	public static final StreamCodec<ByteBuf, PunchResultPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.idMapper(i -> Result.values()[i], Result::ordinal), PunchResultPayload::result,
		Vec3.STREAM_CODEC, PunchResultPayload::position,
		ByteBufCodecs.idMapper(i -> Gore.values()[i], Gore::ordinal), PunchResultPayload::gore,
		PunchResultPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public enum Result {
		ENEMY,
		PARRY
	}
}
