package dev.ultracraft.client.mixin;

import dev.ultracraft.client.combat.ClientCombat;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ServerItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The hotbar shows the railcannons' shared charge as their cooldown. */
@Mixin(ItemCooldowns.class)
public abstract class ItemCooldownsMixin {
	@Inject(method = "getCooldownPercent", at = @At("HEAD"), cancellable = true)
	private void ultracraft$railcannonCharge(ItemStack item, float a, CallbackInfoReturnable<Float> cir) {
		if (!((Object)this instanceof ServerItemCooldowns) && ClientCombat.isRailcannon(item)) {
			cir.setReturnValue((float)ClientCombat.INSTANCE.railcannonCooldown());
		}
	}
}
