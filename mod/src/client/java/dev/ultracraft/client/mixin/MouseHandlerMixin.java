package dev.ultracraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultracraft.client.UltraController;
import dev.ultracraft.client.WeaponSwitching;
import net.minecraft.client.MouseHandler;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * CameraController: while a zoom narrows the view, the mouse turns it slower by as much. The scroll wheel selects
 * hotbar slots as the hotbar keys do.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@ModifyArgs(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void ultracraft$zoomedLook(Args args) {
		double scale = UltraController.INSTANCE.lookScale();
		if (scale != 1) {
			args.set(0, (double)args.get(0) * scale);
			args.set(1, (double)args.get(1) * scale);
		}
	}

	@WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
	private void ultracraft$scrollSlot(Inventory inventory, int slot, Operation<Void> original) {
		WeaponSwitching.INSTANCE.slotKey(inventory, slot);
		original.call(inventory, slot);
	}
}
