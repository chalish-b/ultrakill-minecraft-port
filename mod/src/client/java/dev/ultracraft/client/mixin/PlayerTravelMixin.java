package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips Minecraft's movement for the player ULTRAKILL movement is driving. */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void ultracraft$skipVanillaMovement(Vec3 input, CallbackInfo ci) {
		if (UltraController.INSTANCE.isDriving(this)) {
			ci.cancel();
		}
	}
}
