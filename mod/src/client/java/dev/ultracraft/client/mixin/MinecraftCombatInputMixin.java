package dev.ultracraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultracraft.client.UltraController;
import dev.ultracraft.client.combat.ClientCombat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attack and use belong to a held ULTRAKILL weapon, F punches instead of swapping hands, and pressing the
 * selected slot's key again switches its weapon's variant.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftCombatInputMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void ultracraft$weaponAttack(CallbackInfoReturnable<Boolean> cir) {
		if (ClientCombat.INSTANCE.handlesHeldItem()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void ultracraft$weaponMining(boolean down, CallbackInfo ci) {
		if (ClientCombat.INSTANCE.handlesHeldItem()) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void ultracraft$weaponUse(CallbackInfo ci) {
		if (ClientCombat.INSTANCE.handlesHeldItem()) {
			ci.cancel();
		}
	}

	@WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
	private void ultracraft$cycleVariant(Inventory inventory, int slot, Operation<Void> original) {
		if (slot == inventory.getSelectedSlot()) {
			ClientCombat.INSTANCE.cycleVariant();
		}
		original.call(inventory, slot);
	}

	@WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
	private boolean ultracraft$punchNotSwap(KeyMapping key, Operation<Boolean> original) {
		boolean clicked = original.call(key);
		Minecraft minecraft = (Minecraft)(Object)this;
		return clicked && !(key == minecraft.options.keySwapOffhand && UltraController.INSTANCE.isDriving(minecraft.player));
	}
}
