package dev.ultracraft.client;

import dev.ultracraft.client.combat.ClientCombat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class UltracraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		UltraConfig.load();
		UltraKeys.register();
		UltraHud.register();
		UltraCommands.register();
		WeaponSwitching.init();
		ClientCombat.init();
		LevelRenderEvents.COLLECT_SUBMITS.register(ClientCombat.INSTANCE::render);
	}
}
