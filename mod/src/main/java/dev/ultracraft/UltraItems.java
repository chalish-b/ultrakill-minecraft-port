package dev.ultracraft;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** ULTRAKILL's weapons as items. Each variant is its own item; pressing a held weapon's slot key cycles them. */
public final class UltraItems {
	public static final Item REVOLVER_PIERCER = register("revolver_piercer");
	public static final Item REVOLVER_MARKSMAN = register("revolver_marksman");
	public static final Item REVOLVER_SHARPSHOOTER = register("revolver_sharpshooter");
	public static final Item SHOTGUN_CORE_EJECT = register("shotgun_core_eject");
	public static final Item SHOTGUN_PUMP_CHARGE = register("shotgun_pump_charge");
	public static final Item SHOTGUN_SAWED_ON = register("shotgun_sawed_on");
	public static final Item NAILGUN_ATTRACTOR = register("nailgun_attractor");
	public static final Item NAILGUN_OVERHEAT = register("nailgun_overheat");
	public static final Item NAILGUN_JUMPSTART = register("nailgun_jumpstart");
	public static final Item RAILCANNON_ELECTRIC = register("railcannon_electric");
	public static final Item RAILCANNON_SCREWDRIVER = register("railcannon_screwdriver");
	public static final Item RAILCANNON_MALICIOUS = register("railcannon_malicious");
	public static final Item ROCKET_LAUNCHER_FREEZEFRAME = register("rocket_launcher_freezeframe");
	public static final Item ROCKET_LAUNCHER_SRS = register("rocket_launcher_srs");

	/** Variants in the order the slot key cycles through them. */
	private static final List<List<Item>> VARIANTS = List.of(
		List.of(REVOLVER_PIERCER, REVOLVER_MARKSMAN, REVOLVER_SHARPSHOOTER),
		List.of(SHOTGUN_CORE_EJECT, SHOTGUN_PUMP_CHARGE, SHOTGUN_SAWED_ON),
		List.of(NAILGUN_ATTRACTOR, NAILGUN_OVERHEAT, NAILGUN_JUMPSTART),
		List.of(RAILCANNON_ELECTRIC, RAILCANNON_SCREWDRIVER, RAILCANNON_MALICIOUS),
		List.of(ROCKET_LAUNCHER_FREEZEFRAME, ROCKET_LAUNCHER_SRS)
	);

	private UltraItems() {
	}

	private static Item register(String name) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Ultracraft.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
	}

	static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
			output.accept(REVOLVER_PIERCER);
			output.accept(REVOLVER_MARKSMAN);
			output.accept(REVOLVER_SHARPSHOOTER);
			output.accept(SHOTGUN_CORE_EJECT);
			output.accept(SHOTGUN_PUMP_CHARGE);
			output.accept(SHOTGUN_SAWED_ON);
			output.accept(NAILGUN_ATTRACTOR);
			output.accept(NAILGUN_OVERHEAT);
			output.accept(NAILGUN_JUMPSTART);
			output.accept(RAILCANNON_ELECTRIC);
			output.accept(RAILCANNON_SCREWDRIVER);
			output.accept(RAILCANNON_MALICIOUS);
			output.accept(ROCKET_LAUNCHER_FREEZEFRAME);
			output.accept(ROCKET_LAUNCHER_SRS);
		});
	}

	public static boolean isWeapon(ItemStack stack) {
		return VARIANTS.stream().anyMatch(variants -> variants.contains(stack.getItem()));
	}

	/** The next variant of the weapon {@code stack} holds, or null if it isn't one. */
	public static Item nextVariant(ItemStack stack) {
		for (List<Item> variants : VARIANTS) {
			int index = variants.indexOf(stack.getItem());
			if (index >= 0) {
				return variants.get((index + 1) % variants.size());
			}
		}
		return null;
	}
}
