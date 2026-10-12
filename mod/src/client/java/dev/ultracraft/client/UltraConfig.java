package dev.ultracraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.UltraItems;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

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

	/** GunControl's WeaponRedrawBehaviour: what pressing the key of the weapon in hand does. */
	public enum Redraw {
		/** Switch to its next variant. */
		CYCLE,
		/** Switch to its first variant. */
		FIRST,
		/** Keep its variant. */
		KEEP
	}

	/** A weapon variant's equip state in the loadout. */
	public enum Equip {
		UNEQUIPPED,
		EQUIPPED,
		ALTERNATE
	}

	/** A weapon's loadout, by color (blue, green, red): each variant's equip state and its place in the order, 1 to 3. */
	public static final class Loadout {
		public Equip[] equip = {Equip.EQUIPPED, Equip.EQUIPPED, Equip.EQUIPPED};
		public int[] order = {1, 2, 3};

		/** WeaponOrderController.ChangeOrderNumber: moves the color's variant by {@code step}, swapping places with the one there. */
		public void move(int color, int step) {
			int place = this.order[color] + step;
			if (place < 1 || place > UltraItems.Weapon.COLORS) {
				return;
			}
			for (int other = 0; other < this.order.length; other++) {
				if (this.order[other] == place) {
					this.order[other] = this.order[color];
				}
			}
			this.order[color] = place;
		}

		private boolean valid(UltraItems.Weapon weapon) {
			if (this.equip == null || this.order == null || this.equip.length != UltraItems.Weapon.COLORS || this.order.length != UltraItems.Weapon.COLORS) {
				return false;
			}
			for (int color = 0; color < UltraItems.Weapon.COLORS; color++) {
				if (this.equip[color] == null || this.equip[color] == Equip.ALTERNATE && !weapon.hasAlternate()) {
					return false;
				}
				int place = color + 1;
				if (Arrays.stream(this.order).filter(o -> o == place).count() != 1) {
					return false;
				}
			}
			return true;
		}
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
	/** ULTRAKILL's footsteps, matched to the block underfoot, replace Minecraft's. */
	public boolean ultrakillFootsteps = true;
	public Redraw weaponRedraw = Redraw.CYCLE;
	/** Switching to a weapon brings back the variant it had instead of its first. */
	public boolean variationMemory = false;
	private Map<UltraItems.Weapon, Loadout> loadouts = new EnumMap<>(UltraItems.Weapon.class);

	/** The weapon's loadout, ULTRAKILL's default (every variant equipped, in color order) if it has none. */
	public Loadout loadout(UltraItems.Weapon weapon) {
		Loadout loadout = this.loadouts.get(weapon);
		if (loadout == null || !loadout.valid(weapon)) {
			loadout = new Loadout();
			this.loadouts.put(weapon, loadout);
		}
		return loadout;
	}

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
