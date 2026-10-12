package dev.ultracraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ultracraft.Ultracraft;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import java.util.List;
import net.minecraft.client.KeyMapping;

/**
 * ULTRAKILL's controls. Dash, slide and punch default to ULTRAKILL's Shift, Ctrl and F, and each arm has its
 * own punch key: F for the Feedbacker, G (ULTRAKILL's arm switch) for the Knuckleblaster. While ULTRAKILL
 * movement is on, those keys stop sneaking, sprinting and swapping hands, and sneaking moves to its own key.
 * The weapon switching keys pick the held weapon's variants by their place in its order.
 */
public final class UltraKeys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Ultracraft.id("ultracraft"));

	public static final KeyMapping TOGGLE = key("toggle", InputConstants.KEY_U);
	public static final KeyMapping DASH = key("dash", InputConstants.KEY_LSHIFT);
	public static final KeyMapping SLIDE = key("slide", InputConstants.KEY_LCONTROL);
	public static final KeyMapping SNEAK = key("sneak", InputConstants.KEY_LALT);
	public static final KeyMapping PUNCH = key("punch", InputConstants.KEY_F);
	public static final KeyMapping PUNCH_KNUCKLEBLASTER = key("punch_knuckleblaster", InputConstants.KEY_G);
	public static final KeyMapping WHIPLASH = key("whiplash", InputConstants.KEY_R);
	public static final KeyMapping VARIANT_1 = key("variant_1", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping VARIANT_2 = key("variant_2", InputConstants.KEY_X);
	public static final KeyMapping VARIANT_3 = key("variant_3", InputConstants.KEY_C);
	public static final KeyMapping NEXT_VARIATION = key("next_variation", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping PREVIOUS_VARIATION = key("previous_variation", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping LAST_WEAPON = key("last_weapon", InputConstants.UNKNOWN.getValue());
	public static final KeyMapping LOADOUT = key("loadout", InputConstants.KEY_K);
	public static final List<KeyMapping> VARIANTS = List.of(VARIANT_1, VARIANT_2, VARIANT_3);
	public static final List<KeyMapping> ALL = List.of(
		TOGGLE, DASH, SLIDE, SNEAK, PUNCH, PUNCH_KNUCKLEBLASTER, WHIPLASH,
		VARIANT_1, VARIANT_2, VARIANT_3, NEXT_VARIATION, PREVIOUS_VARIATION, LAST_WEAPON, LOADOUT
	);

	private UltraKeys() {
	}

	private static KeyMapping key(String name, int key) {
		return new KeyMapping("key.ultracraft." + name, key, CATEGORY);
	}

	static void register() {
		ALL.forEach(KeyMappingHelper::registerKeyMapping);
	}
}
