package dev.ultracraft.mixin;

import dev.ultracraft.combat.ServerWhiplash;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An Enderman that teleports knocks a Whiplash off itself, as ULTRAKILL's teleporting enemies do. */
@Mixin(Enderman.class)
abstract class EndermanMixin {
	@Inject(method = "teleport(DDD)Z", at = @At("RETURN"))
	private void ultracraft$teleported(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !((Enderman)(Object)this).level().isClientSide()) {
			ServerWhiplash.teleported((Enderman)(Object)this);
		}
	}
}
