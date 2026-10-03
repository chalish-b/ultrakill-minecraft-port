package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sets the step height of the player ULTRAKILL movement is driving. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "maxUpStep", at = @At("HEAD"), cancellable = true)
	private void ultracraft$stepHeight(CallbackInfoReturnable<Float> cir) {
		if (UltraController.INSTANCE.isDriving(this)) {
			cir.setReturnValue(UltraController.INSTANCE.stepHeight());
		}
	}
}
