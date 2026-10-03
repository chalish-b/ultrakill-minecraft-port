package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs ULTRAKILL movement once per frame, after the frame's ticks and mouse look, right before rendering. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void ultracraft$beforeFrame(boolean advanceGameTime, CallbackInfo ci) {
		UltraController.INSTANCE.onFrame();
	}
}
