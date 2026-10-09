package dev.ultracraft;

import dev.ultracraft.combat.GasolineFireBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** The blocks ULTRAKILL's weapons leave in the world: the fire the Firestarter's gasoline burns as. */
public final class UltraBlocks {
	public static final Block GASOLINE_FIRE = Blocks.register(
		key("gasoline_fire"),
		GasolineFireBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.FIRE)
			.replaceable()
			.noCollision()
			.instabreak()
			.lightLevel(state -> 15)
			.sound(SoundType.WOOL)
			.noLootTable()
			.pushReaction(PushReaction.POPPED)
	);

	private UltraBlocks() {
	}

	private static ResourceKey<Block> key(String name) {
		return ResourceKey.create(Registries.BLOCK, Ultracraft.id(name));
	}

	static void init() {
	}
}
