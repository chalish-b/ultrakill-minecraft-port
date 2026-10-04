package dev.ultracraft.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Accessor("lastHurt")
	float ultracraft$getLastHurt();

	@Accessor("lastHurt")
	void ultracraft$setLastHurt(float lastHurt);
}
