package dev.ultracraft;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** ULTRAKILL's weapons as items. Each variant is its own item, standard and alternate versions alike. */
public final class UltraItems {
	public static final Item REVOLVER_PIERCER = register("revolver_piercer");
	public static final Item REVOLVER_MARKSMAN = register("revolver_marksman");
	public static final Item REVOLVER_SHARPSHOOTER = register("revolver_sharpshooter");
	public static final Item REVOLVER_SLAB_PIERCER = register("revolver_slab_piercer");
	public static final Item REVOLVER_SLAB_MARKSMAN = register("revolver_slab_marksman");
	public static final Item REVOLVER_SLAB_SHARPSHOOTER = register("revolver_slab_sharpshooter");
	public static final Item SHOTGUN_CORE_EJECT = register("shotgun_core_eject");
	public static final Item SHOTGUN_PUMP_CHARGE = register("shotgun_pump_charge");
	public static final Item SHOTGUN_SAWED_ON = register("shotgun_sawed_on");
	public static final Item SHOTGUN_HAMMER_CORE_EJECT = register("shotgun_hammer_core_eject");
	public static final Item SHOTGUN_HAMMER_PUMP_CHARGE = register("shotgun_hammer_pump_charge");
	public static final Item SHOTGUN_HAMMER_SAWED_ON = register("shotgun_hammer_sawed_on");
	public static final Item NAILGUN_ATTRACTOR = register("nailgun_attractor");
	public static final Item NAILGUN_OVERHEAT = register("nailgun_overheat");
	public static final Item NAILGUN_JUMPSTART = register("nailgun_jumpstart");
	public static final Item RAILCANNON_ELECTRIC = register("railcannon_electric");
	public static final Item RAILCANNON_SCREWDRIVER = register("railcannon_screwdriver");
	public static final Item RAILCANNON_MALICIOUS = register("railcannon_malicious");
	public static final Item ROCKET_LAUNCHER_FREEZEFRAME = register("rocket_launcher_freezeframe");
	public static final Item ROCKET_LAUNCHER_SRS = register("rocket_launcher_srs");
	public static final Item ROCKET_LAUNCHER_FIRESTARTER = register("rocket_launcher_firestarter");

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
			output.accept(REVOLVER_SLAB_PIERCER);
			output.accept(REVOLVER_SLAB_MARKSMAN);
			output.accept(REVOLVER_SLAB_SHARPSHOOTER);
			output.accept(SHOTGUN_CORE_EJECT);
			output.accept(SHOTGUN_PUMP_CHARGE);
			output.accept(SHOTGUN_SAWED_ON);
			output.accept(SHOTGUN_HAMMER_CORE_EJECT);
			output.accept(SHOTGUN_HAMMER_PUMP_CHARGE);
			output.accept(SHOTGUN_HAMMER_SAWED_ON);
			output.accept(NAILGUN_ATTRACTOR);
			output.accept(NAILGUN_OVERHEAT);
			output.accept(NAILGUN_JUMPSTART);
			output.accept(RAILCANNON_ELECTRIC);
			output.accept(RAILCANNON_SCREWDRIVER);
			output.accept(RAILCANNON_MALICIOUS);
			output.accept(ROCKET_LAUNCHER_FREEZEFRAME);
			output.accept(ROCKET_LAUNCHER_SRS);
			output.accept(ROCKET_LAUNCHER_FIRESTARTER);
		});
	}

	public static boolean isWeapon(ItemStack stack) {
		return Weapon.of(stack.getItem()) != null;
	}

	/**
	 * ULTRAKILL's weapons, each with a variant per color (blue, green, red, the order {@link #COLORS} counts them
	 * in) in its standard version and, for the weapons that have one, its alternate version.
	 */
	public enum Weapon {
		REVOLVER(
			List.of(REVOLVER_PIERCER, REVOLVER_MARKSMAN, REVOLVER_SHARPSHOOTER),
			List.of(REVOLVER_SLAB_PIERCER, REVOLVER_SLAB_MARKSMAN, REVOLVER_SLAB_SHARPSHOOTER)
		),
		SHOTGUN(
			List.of(SHOTGUN_CORE_EJECT, SHOTGUN_PUMP_CHARGE, SHOTGUN_SAWED_ON),
			List.of(SHOTGUN_HAMMER_CORE_EJECT, SHOTGUN_HAMMER_PUMP_CHARGE, SHOTGUN_HAMMER_SAWED_ON)
		),
		NAILGUN(List.of(NAILGUN_ATTRACTOR, NAILGUN_OVERHEAT, NAILGUN_JUMPSTART), List.of()),
		RAILCANNON(List.of(RAILCANNON_ELECTRIC, RAILCANNON_SCREWDRIVER, RAILCANNON_MALICIOUS), List.of()),
		ROCKET_LAUNCHER(List.of(ROCKET_LAUNCHER_FREEZEFRAME, ROCKET_LAUNCHER_SRS, ROCKET_LAUNCHER_FIRESTARTER), List.of());

		public static final int COLORS = 3;

		private final List<Item> standard;
		private final List<Item> alternate;

		Weapon(List<Item> standard, List<Item> alternate) {
			this.standard = standard;
			this.alternate = alternate;
		}

		/** The weapon {@code item} is a variant of, or null if it isn't one. */
		public static @Nullable Weapon of(Item item) {
			for (Weapon weapon : values()) {
				if (weapon.standard.contains(item) || weapon.alternate.contains(item)) {
					return weapon;
				}
			}
			return null;
		}

		public boolean hasAlternate() {
			return !this.alternate.isEmpty();
		}

		/** The variant of {@code color} in the given version, or null if the weapon has no such variant. */
		public @Nullable Item variant(int color, boolean alternate) {
			List<Item> variants = alternate ? this.alternate : this.standard;
			return color >= 0 && color < variants.size() ? variants.get(color) : null;
		}

		/** The color of the weapon's variant {@code item}. */
		public int color(Item item) {
			int index = this.standard.indexOf(item);
			return index >= 0 ? index : this.alternate.indexOf(item);
		}

		public boolean isAlternate(Item item) {
			return this.alternate.contains(item);
		}
	}
}
