package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraConfig;
import dev.ultracraft.client.UltraController;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Silences Minecraft's footsteps while ULTRAKILL's play instead. */
@Mixin(Entity.class)
public abstract class StepSoundMixin {
	@Inject(method = {"playCombinationStepSounds", "playMuffledStepSound"}, at = @At("HEAD"), cancellable = true)
	private void ultracraft$ultrakillFootsteps(CallbackInfo ci) {
		if (UltraConfig.get().ultrakillFootsteps && UltraController.INSTANCE.isDriving(this)) {
			ci.cancel();
		}
	}
}
