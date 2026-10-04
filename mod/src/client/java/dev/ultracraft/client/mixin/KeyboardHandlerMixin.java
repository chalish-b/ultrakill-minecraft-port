package dev.ultracraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.InputConstants;
import dev.ultracraft.client.UltraKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ULTRAKILL's actions go off once per press (WasPerformedThisFrame), so a held key's repeats don't click
 * ULTRACRAFT's keys or jump.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@WrapOperation(method = "keyPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
	private void ultracraft$noRepeatClicks(InputConstants.Key key, Operation<Void> original, @Local(argsOnly = true) int action) {
		original.call(key);
		if (action != InputConstants.REPEAT) {
			return;
		}
		for (KeyMapping mapping : UltraKeys.ALL) {
			unclick(mapping, key);
		}
		unclick(Minecraft.getInstance().options.keyJump, key);
	}

	private static void unclick(KeyMapping mapping, InputConstants.Key key) {
		if (mapping.matches(key)) {
			mapping.consumeClick();
		}
	}
}
