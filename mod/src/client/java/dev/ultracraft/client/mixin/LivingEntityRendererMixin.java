package dev.ultracraft.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ultracraft.client.combat.GasolineStains;
import dev.ultracraft.combat.Gasoline;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enemies soaked in gasoline darken toward its color. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Unique
	private static final RenderStateDataKey<Float> FUEL = RenderStateDataKey.create(() -> "ultracraft:gasoline_fuel");

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void ultracraft$fuel(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		((FabricRenderState)state).setData(FUEL, entity.getAttachedOrElse(Gasoline.FUEL, 0F));
	}

	@ModifyArg(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"),
		index = 6
	)
	private int ultracraft$oilTint(int color, @Local(argsOnly = true) LivingEntityRenderState state) {
		float fuel = ((FabricRenderState)state).getDataOrDefault(FUEL, 0F);
		return fuel > 0 ? ARGB.multiply(color, GasolineStains.oilTint(fuel)) : color;
	}
}
