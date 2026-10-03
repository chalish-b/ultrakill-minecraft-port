package dev.ultracraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ultracraft.Ultracraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client settings, saved to config/ultracraft.json and changed in game with /ultracraft. */
public final class UltraConfig {
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("ultracraft.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static UltraConfig instance = new UltraConfig();

	public enum StepMode {
		/** ULTRAKILL's step climbing: walk into a ledge up to about one block tall to get onto it. */
		ULTRAKILL,
		/** Minecraft's auto-step: walk up anything up to 0.6 blocks without slowing down. */
		MINECRAFT
	}

	/** ULTRAKILL movement replaces Minecraft's. */
	public boolean enabled = true;
	/**
	 * How far ULTRAKILL's speeds carry you. 1 matches ULTRAKILL relative to your body (the player is
	 * scaled to Minecraft's height); 1.944 makes one ULTRAKILL unit one block.
	 */
	public double speedScale = 1.0;
	public StepMode stepMode = StepMode.ULTRAKILL;
	/** The camera leans into strafes and dashes. */
	public boolean cameraTilt = true;
	public double screenShake = 1.0;
	/** Dashes and slides change the field of view. */
	public boolean fovEffects = true;
	public boolean speedometer = false;
	/** Shows the movement state under the speedometer. */
	public boolean debug = false;
	public boolean infiniteStamina = false;

	public static UltraConfig get() {
		return instance;
	}

	public static void load() {
		if (!Files.exists(PATH)) {
			instance.save();
			return;
		}
		try {
			UltraConfig loaded = GSON.fromJson(Files.readString(PATH), UltraConfig.class);
			if (loaded != null) {
				instance = loaded;
			}
		} catch (IOException | RuntimeException e) {
			Ultracraft.LOGGER.warn("Couldn't read {}, using defaults", PATH, e);
		}
	}

	public void save() {
		try {
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			Ultracraft.LOGGER.warn("Couldn't save {}", PATH, e);
		}
	}
}
