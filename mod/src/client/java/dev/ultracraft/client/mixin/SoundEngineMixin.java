package dev.ultracraft.client.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ULTRAKILL's sounds play at the pitches its code gives them, beyond Minecraft's 0.5 to 2: the nailgun's whir goes
 * from silent to over three times as high as its barrels spin up.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	/** OpenAL takes any pitch above 0. */
	@Unique
	private static final float MIN_PITCH = 0.001F;
	@Unique
	private static final float MAX_PITCH = 8;

	@Inject(method = "calculatePitch", at = @At("HEAD"), cancellable = true)
	private void ultracraft$fullPitch(SoundInstance instance, CallbackInfoReturnable<Float> cir) {
		if (instance.getIdentifier().getNamespace().equals(Ultracraft.MOD_ID)) {
			cir.setReturnValue(Mth.clamp(instance.getPitch(), MIN_PITCH, MAX_PITCH));
		}
	}
}
