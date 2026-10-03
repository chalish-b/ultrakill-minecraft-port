package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ULTRAKILL's camera has its own walk bob, so Minecraft's view bobbing is off while it drives the player. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noViewBob(CallbackInfo ci) {
		if (UltraController.INSTANCE.isDriving(Minecraft.getInstance().player)) {
			ci.cancel();
		}
	}
}
