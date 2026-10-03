package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.UltraController;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Silences Minecraft's footsteps while ULTRAKILL's play instead. */
@Mixin(Player.class)
public abstract class PlayerStepSoundMixin {
	@Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
	private void ultracraft$ultrakillFootsteps(BlockPos onPos, BlockState onState, CallbackInfo ci) {
		if (UltraConfig.get().ultrakillFootsteps && UltraController.INSTANCE.isDriving(this)) {
			ci.cancel();
		}
	}
}
