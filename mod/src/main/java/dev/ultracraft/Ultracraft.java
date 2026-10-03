package dev.ultracraft;

import dev.ultracraft.combat.ServerCombat;
import dev.ultracraft.net.CycleVariantPayload;
import dev.ultracraft.net.PunchPayload;
import dev.ultracraft.net.PunchResultPayload;
import dev.ultracraft.net.UltraStatePayload;
import dev.ultracraft.net.WeaponHitPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Ultracraft implements ModInitializer {
	public static final String MOD_ID = "ultracraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(UltraStatePayload.TYPE, UltraStatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(WeaponHitPayload.TYPE, WeaponHitPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PunchPayload.TYPE, PunchPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CycleVariantPayload.TYPE, CycleVariantPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PunchResultPayload.TYPE, PunchResultPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(UltraStatePayload.TYPE, (payload, context) ->
			((UltraPlayer)context.player()).ultracraft$setUltraState(payload.active(), payload.crouched())
		);
		UltraItems.init();
		ServerCombat.init();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
