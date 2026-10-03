package dev.ultracraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultracraft.UltraPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Accepts the client's position for players driven by ULTRAKILL movement.
 *
 * <p>The server re-runs each tick's movement as one straight move and rejects positions that differ
 * from it ("moved wrongly"). ULTRAKILL movement steps at 125 Hz and climbs ledges, so its path through
 * a tick doesn't match a single move. Players in their impulse grace time are already exempt from
 * that check, and ULTRAKILL players are treated the same way.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@WrapOperation(
		method = "handlePlayerPositionChange",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isInPostImpulseGraceTime()Z")
	)
	private boolean ultracraft$trustUltraMovement(ServerPlayer player, Operation<Boolean> original) {
		return ((UltraPlayer)player).ultracraft$isActive() || original.call(player);
	}
}
