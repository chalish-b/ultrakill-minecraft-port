package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ULTRAKILL has no sprinting: double-tapping forward doesn't start it while ULTRAKILL movement drives the player. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noSprinting(CallbackInfoReturnable<Boolean> cir) {
		if (UltraController.INSTANCE.isDriving(this)) {
			cir.setReturnValue(false);
		}
	}
}
