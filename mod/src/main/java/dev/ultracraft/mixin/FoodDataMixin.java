package dev.ultracraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultracraft.UltraPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** ULTRAKILL players don't regenerate: health only comes back from blood and parries. */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isHurt()Z"))
	private boolean ultracraft$noRegeneration(ServerPlayer player, Operation<Boolean> original) {
		return !((UltraPlayer)player).ultracraft$isActive() && original.call(player);
	}
}
