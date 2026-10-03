package dev.ultracraft.client;

import net.fabricmc.api.ClientModInitializer;

public class UltracraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		UltraConfig.load();
		UltraKeys.register();
		UltraHud.register();
		UltraCommands.register();
	}
}
