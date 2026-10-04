package dev.ultracraft;

import net.minecraft.world.Difficulty;

/** ULTRAKILL's difficulty for Minecraft's: peaceful is Lenient, easy Standard, normal Violent and hard Brutal. */
public final class UltraDifficulty {
	public static final int LENIENT = 1;
	public static final int STANDARD = 2;
	public static final int VIOLENT = 3;
	public static final int BRUTAL = 4;

	private UltraDifficulty() {
	}

	public static int of(Difficulty difficulty) {
		return switch (difficulty) {
			case PEACEFUL -> LENIENT;
			case EASY -> STANDARD;
			case NORMAL -> VIOLENT;
			case HARD -> BRUTAL;
		};
	}
}
