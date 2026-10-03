package dev.ultracraft.client.movement;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/** ULTRAKILL's SurfaceType for a Minecraft block, picking its footstep and scrape sounds. */
final class Surfaces {
	static final String GENERIC = "generic";
	static final String WET = "wet";
	private static final Map<SoundType, String> BY_SOUND = new IdentityHashMap<>();

	static {
		map("grass", SoundType.GRASS, SoundType.WET_GRASS, SoundType.CROP, SoundType.HARD_CROP, SoundType.VINE, SoundType.SWEET_BERRY_BUSH,
			SoundType.AZALEA, SoundType.FLOWERING_AZALEA, SoundType.MOSS, SoundType.MOSS_CARPET, SoundType.PINK_PETALS, SoundType.LEAF_LITTER,
			SoundType.ROOTS, SoundType.CAVE_VINES, SoundType.BIG_DRIPLEAF, SoundType.SMALL_DRIPLEAF, SoundType.HANGING_ROOTS,
			SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES, SoundType.POPLAR_LEAVES, SoundType.SPORE_BLOSSOM, SoundType.GLOW_LICHEN,
			SoundType.LILY_PAD, SoundType.BAMBOO_SAPLING, SoundType.CHERRY_SAPLING, SoundType.NETHER_SPROUTS, SoundType.FUNGUS,
			SoundType.WEEPING_VINES, SoundType.TWISTING_VINES, SoundType.STEM, SoundType.NYLIUM, SoundType.CACTUS_FLOWER, SoundType.RED_SHRUB);
		map("gravel", SoundType.GRAVEL, SoundType.SUSPICIOUS_GRAVEL);
		map("dirt", SoundType.ROOTED_DIRT, SoundType.PACKED_MUD);
		map("sand", SoundType.SAND, SoundType.SUSPICIOUS_SAND, SoundType.SOUL_SAND, SoundType.SOUL_SOIL);
		map("snow", SoundType.SNOW, SoundType.POWDER_SNOW);
		map("wood", SoundType.WOOD, SoundType.NETHER_WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD, SoundType.BAMBOO,
			SoundType.CHISELED_BOOKSHELF, SoundType.SHELF, SoundType.HANGING_SIGN, SoundType.NETHER_WOOD_HANGING_SIGN,
			SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundType.CHERRY_WOOD_HANGING_SIGN, SoundType.MANGROVE_ROOTS, SoundType.CREAKING_HEART);
		map("wood_thin", SoundType.LADDER, SoundType.SCAFFOLDING, SoundType.STRAW_BED);
		map("glass", SoundType.GLASS, SoundType.AMETHYST, SoundType.AMETHYST_CLUSTER, SoundType.SMALL_AMETHYST_BUD,
			SoundType.MEDIUM_AMETHYST_BUD, SoundType.LARGE_AMETHYST_BUD);
		map("metal", SoundType.METAL, SoundType.IRON, SoundType.NETHERITE_BLOCK, SoundType.ANVIL, SoundType.LODESTONE, SoundType.HEAVY_CORE,
			SoundType.VAULT, SoundType.TRIAL_SPAWNER, SoundType.SPAWNER);
		map("metal_low", SoundType.COPPER, SoundType.COPPER_BULB, SoundType.COPPER_GOLEM_STATUE);
		map("metal_grill", SoundType.COPPER_GRATE, SoundType.CHAIN);
		map("metal_thin", SoundType.LANTERN);
		map("carpet", SoundType.WOOL, SoundType.SPONGE);
		map(WET, SoundType.SLIME_BLOCK, SoundType.HONEY_BLOCK, SoundType.MUD, SoundType.MUDDY_MANGROVE_ROOTS, SoundType.FROGSPAWN,
			SoundType.WET_SPONGE, SoundType.CORAL_BLOCK);
		map("flesh", SoundType.WART_BLOCK, SoundType.NETHER_WART, SoundType.SCULK, SoundType.SCULK_VEIN, SoundType.SCULK_CATALYST,
			SoundType.SCULK_SENSOR, SoundType.SCULK_SHRIEKER, SoundType.SHROOMLIGHT, SoundType.FROGLIGHT);
		map("crunchy", SoundType.BONE_BLOCK, SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED, SoundType.DRIED_GHAST, SoundType.RESIN);
		map("rubble", SoundType.NETHERRACK, SoundType.NETHER_ORE, SoundType.NETHER_GOLD_ORE, SoundType.BASALT, SoundType.DRIPSTONE_BLOCK,
			SoundType.POINTED_DRIPSTONE, SoundType.GILDED_BLACKSTONE, SoundType.ANCIENT_DEBRIS, SoundType.SULFUR, SoundType.POTENT_SULFUR,
			SoundType.SULFUR_SPIKE, SoundType.CINNABAR);
		map("marble", SoundType.CALCITE, SoundType.POLISHED_DEEPSLATE, SoundType.DEEPSLATE_TILES, SoundType.DEEPSLATE_BRICKS,
			SoundType.TUFF_BRICKS, SoundType.POLISHED_TUFF, SoundType.MUD_BRICKS, SoundType.NETHER_BRICKS, SoundType.RESIN_BRICKS);
	}

	private Surfaces() {
	}

	private static void map(String surface, SoundType... types) {
		for (SoundType type : types) {
			BY_SOUND.put(type, surface);
		}
	}

	static String of(BlockState state) {
		SoundType sound = state.getSoundType();
		if (sound == SoundType.GRAVEL && state.is(BlockTags.DIRT)) {
			return "dirt";
		}
		return BY_SOUND.getOrDefault(sound, GENERIC);
	}
}
