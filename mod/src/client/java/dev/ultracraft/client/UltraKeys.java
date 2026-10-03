package dev.ultracraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ultracraft.Ultracraft;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * ULTRAKILL's controls. Dash and slide default to ULTRAKILL's Shift and Ctrl; while ULTRAKILL movement is
 * on, those keys stop sneaking and sprinting, and sneaking moves to its own key.
 */
public final class UltraKeys {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Ultracraft.id("ultracraft"));

	public static final KeyMapping TOGGLE = key("toggle", InputConstants.KEY_U);
	public static final KeyMapping DASH = key("dash", InputConstants.KEY_LSHIFT);
	public static final KeyMapping SLIDE = key("slide", InputConstants.KEY_LCONTROL);
	public static final KeyMapping SNEAK = key("sneak", InputConstants.KEY_LALT);

	private UltraKeys() {
	}

	private static KeyMapping key(String name, int key) {
		return new KeyMapping("key.ultracraft." + name, key, CATEGORY);
	}

	static void register() {
		KeyMappingHelper.registerKeyMapping(TOGGLE);
		KeyMappingHelper.registerKeyMapping(DASH);
		KeyMappingHelper.registerKeyMapping(SLIDE);
		KeyMappingHelper.registerKeyMapping(SNEAK);
	}
}
