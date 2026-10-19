package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import dev.ultracraft.client.UltraKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While ULTRAKILL movement drives the player, jump, Shift and Ctrl belong to it: Minecraft's player
 * doesn't jump, sprint or sneak from them, and sneaking comes from ULTRACRAFT's own sneak key. Riding keeps
 * Minecraft's keys for the vehicle.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void ultracraft$ultraKeys(CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (!UltraController.INSTANCE.isDriving(player) || player.isPassenger()) {
			return;
		}
		ClientInput self = (ClientInput)(Object)this;
		Input keys = self.keyPresses;
		self.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), false, UltraKeys.SNEAK.isDown(), false);
	}
}
