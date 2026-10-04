package dev.ultracraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ServerExplosion.class)
public interface ServerExplosionInvoker {
	@Invoker("calculateExplodedPositions")
	List<BlockPos> ultracraft$calculateExplodedPositions();

	@Invoker("interactWithBlocks")
	void ultracraft$interactWithBlocks(List<BlockPos> targetBlocks);
}
