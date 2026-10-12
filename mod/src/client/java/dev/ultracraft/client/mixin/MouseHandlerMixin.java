package dev.ultracraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ultracraft.client.UltraController;
import dev.ultracraft.client.WeaponSwitching;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * CameraController: while a zoom narrows the view, the mouse turns it slower by as much. While ULTRAKILL movement
 * is on, the scroll wheel switches weapons as GunControl does.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@ModifyArgs(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void ultracraft$zoomedLook(Args args) {
		double scale = UltraController.INSTANCE.lookScale();
		if (scale != 1) {
			args.set(0, (double)args.get(0) * scale);
			args.set(1, (double)args.get(1) * scale);
		}
	}

	@WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"))
	private int ultracraft$scrollWeapons(double wheel, int current, int limit, Operation<Integer> original) {
		int toward = original.call(wheel, current, limit);
		if (!UltraController.INSTANCE.isDriving(this.minecraft.player)) {
			return toward;
		}
		return WeaponSwitching.INSTANCE.scroll(this.minecraft.player.getInventory(), toward);
	}
}
