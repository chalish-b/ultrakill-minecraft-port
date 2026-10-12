package dev.ultracraft.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** {@code /ultracraft} shows the settings; {@code /ultracraft <setting> <value>} changes one. */
final class UltraCommands {
	private UltraCommands() {
	}

	static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = literal("ultracraft").executes(context -> {
			show(context.getSource());
			return 1;
		});
		root.then(literal("speedScale").then(argument("value", DoubleArgumentType.doubleArg(0.1, 10)).executes(context -> {
			UltraConfig.get().speedScale = DoubleArgumentType.getDouble(context, "value");
			return saved(context.getSource());
		})));
		root.then(literal("screenShake").then(argument("value", DoubleArgumentType.doubleArg(0, 5)).executes(context -> {
			UltraConfig.get().screenShake = DoubleArgumentType.getDouble(context, "value");
			return saved(context.getSource());
		})));
		root.then(literal("stepMode")
			.then(literal("ultrakill").executes(context -> {
				UltraConfig.get().stepMode = UltraConfig.StepMode.ULTRAKILL;
				return saved(context.getSource());
			}))
			.then(literal("minecraft").executes(context -> {
				UltraConfig.get().stepMode = UltraConfig.StepMode.MINECRAFT;
				return saved(context.getSource());
			})));
		LiteralArgumentBuilder<FabricClientCommandSource> redraw = literal("weaponRedraw");
		for (UltraConfig.Redraw value : UltraConfig.Redraw.values()) {
			redraw.then(literal(value.name().toLowerCase()).executes(context -> {
				UltraConfig.get().weaponRedraw = value;
				return saved(context.getSource());
			}));
		}
		root.then(redraw);
		root.then(toggle("enabled", config -> config.enabled, (config, value) -> config.enabled = value));
		root.then(toggle("cameraTilt", config -> config.cameraTilt, (config, value) -> config.cameraTilt = value));
		root.then(toggle("fovEffects", config -> config.fovEffects, (config, value) -> config.fovEffects = value));
		root.then(toggle("speedometer", config -> config.speedometer, (config, value) -> config.speedometer = value));
		root.then(toggle("debug", config -> config.debug, (config, value) -> config.debug = value));
		root.then(toggle("infiniteStamina", config -> config.infiniteStamina, (config, value) -> config.infiniteStamina = value));
		root.then(toggle("ultrakillFootsteps", config -> config.ultrakillFootsteps, (config, value) -> config.ultrakillFootsteps = value));
		root.then(toggle("variationMemory", config -> config.variationMemory, (config, value) -> config.variationMemory = value));
		dispatcher.register(root);
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(
		String name, Function<UltraConfig, Boolean> getter, BiConsumer<UltraConfig, Boolean> setter
	) {
		return literal(name)
			.executes(context -> {
				setter.accept(UltraConfig.get(), !getter.apply(UltraConfig.get()));
				return saved(context.getSource());
			})
			.then(argument("value", BoolArgumentType.bool()).executes(context -> {
				setter.accept(UltraConfig.get(), BoolArgumentType.getBool(context, "value"));
				return saved(context.getSource());
			}));
	}

	private static int saved(FabricClientCommandSource source) {
		UltraConfig.get().save();
		show(source);
		return 1;
	}

	private static void show(FabricClientCommandSource source) {
		UltraConfig config = UltraConfig.get();
		source.sendFeedback(Component.literal(String.format(
			"ULTRACRAFT: enabled %s, speedScale %.3f, stepMode %s, cameraTilt %s, screenShake %.2f, fovEffects %s, speedometer %s, debug %s, infiniteStamina %s, ultrakillFootsteps %s, "
				+ "weaponRedraw %s, variationMemory %s",
			config.enabled, config.speedScale, config.stepMode.name().toLowerCase(), config.cameraTilt, config.screenShake,
			config.fovEffects, config.speedometer, config.debug, config.infiniteStamina, config.ultrakillFootsteps,
			config.weaponRedraw.name().toLowerCase(), config.variationMemory
		)));
	}
}
