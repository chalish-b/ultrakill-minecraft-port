package dev.ultracraft.client.mixin;

import dev.ultracraft.client.UltraController;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** CameraController: while a zoom narrows the view, the mouse turns it slower by as much. */
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
}
