package dev.ultracraft.mixin;

import dev.ultracraft.PlayerShape;
import dev.ultracraft.UltraPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gives players driven by ULTRAKILL movement ULTRAKILL's collider: standing, or short while sliding. */
@Mixin(Avatar.class)
public abstract class AvatarMixin {
	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void ultracraft$ultraDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if ((Object)this instanceof UltraPlayer player && player.ultracraft$isActive()) {
			if (pose == Pose.STANDING) {
				cir.setReturnValue(PlayerShape.STANDING);
			} else if (pose == Pose.SWIMMING) {
				cir.setReturnValue(PlayerShape.CROUCHED);
			}
		}
	}
}
