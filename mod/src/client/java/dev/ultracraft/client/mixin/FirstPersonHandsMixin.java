package dev.ultracraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.client.UltraController;
import dev.ultracraft.client.render.FirstPersonWeapons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws ULTRAKILL's weapons and arm in first person, in place of Minecraft's hands while a weapon is held. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void ultracraft$weapons(
		float partialTicks, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState,
		FirstPersonHandsAndItemsRenderState state, CallbackInfo ci
	) {
		if (playerState.avatarRenderState == null || !UltraController.INSTANCE.isDriving(Minecraft.getInstance().player)) {
			return;
		}
		if (FirstPersonWeapons.INSTANCE.submit(poseStack, collector, playerState.avatarRenderState.lightCoords)) {
			ci.cancel();
		}
	}
}
