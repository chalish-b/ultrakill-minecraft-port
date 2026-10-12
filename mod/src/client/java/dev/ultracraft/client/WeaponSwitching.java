package dev.ultracraft.client;

import dev.ultracraft.UltraItems;
import dev.ultracraft.client.combat.ClientCombat;
import dev.ultracraft.net.SwitchVariantPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GunControl's weapon switching. A weapon's hotbar slot holds one of its variants, which the slot switches between
 * in the order and the versions its loadout picks.
 */
public final class WeaponSwitching {
	public static final WeaponSwitching INSTANCE = new WeaponSwitching();

	/** GunControl.lastSlotIndex: the hotbar slot selected before the current one, or -1. */
	private int lastSlot = -1;

	private WeaponSwitching() {
	}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::tick);
	}

	/** The variants a weapon's slot switches between: its equipped colors in the loadout's order, each in its version. */
	public static List<Item> variants(UltraItems.Weapon weapon) {
		UltraConfig.Loadout loadout = UltraConfig.get().loadout(weapon);
		List<Item> variants = new ArrayList<>();
		for (int place = 1; place <= UltraItems.Weapon.COLORS; place++) {
			for (int color = 0; color < UltraItems.Weapon.COLORS; color++) {
				if (loadout.order[color] == place && loadout.equip[color] != UltraConfig.Equip.UNEQUIPPED) {
					variants.add(weapon.variant(color, loadout.equip[color] == UltraConfig.Equip.ALTERNATE));
				}
			}
		}
		return variants;
	}

	private void tick(Minecraft minecraft) {
		while (UltraKeys.LOADOUT.consumeClick()) {
			if (minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new LoadoutScreen());
			}
		}
		LocalPlayer player = minecraft.player;
		Inventory inventory = player == null ? null : player.getInventory();
		while (UltraKeys.NEXT_VARIATION.consumeClick()) {
			if (inventory != null) {
				step(inventory, 1);
			}
		}
		while (UltraKeys.PREVIOUS_VARIATION.consumeClick()) {
			if (inventory != null) {
				step(inventory, -1);
			}
		}
		for (int place = 0; place < UltraKeys.VARIANTS.size(); place++) {
			KeyMapping key = UltraKeys.VARIANTS.get(place);
			while (key.consumeClick()) {
				if (inventory != null) {
					pick(inventory, place);
				}
			}
		}
		while (UltraKeys.LAST_WEAPON.consumeClick()) {
			if (inventory != null && this.lastSlot >= 0 && this.lastSlot != inventory.getSelectedSlot()) {
				int slot = this.lastSlot;
				selecting(inventory, slot, true);
				inventory.setSelectedSlot(slot);
			}
		}
	}

	/** A hotbar key pressed or the scroll wheel turned to {@code slot}, before Minecraft selects it. */
	public void slotKey(Inventory inventory, int slot) {
		if (slot != inventory.getSelectedSlot()) {
			selecting(inventory, slot, false);
			return;
		}
		Item held = inventory.getItem(slot).getItem();
		UltraItems.Weapon weapon = UltraItems.Weapon.of(held);
		if (weapon == null) {
			return;
		}
		// The held weapon's own key only acts when its slot has another variant.
		List<Item> variants = variants(weapon);
		if (variants.isEmpty() || variants.equals(List.of(held))) {
			return;
		}
		int index = variants.indexOf(held);
		switchTo(inventory, slot, switch (UltraConfig.get().weaponRedraw) {
			case CYCLE -> variants.get(Math.floorMod(index + 1, variants.size()));
			case FIRST -> variants.getFirst();
			case KEEP -> index < 0 ? variants.getFirst() : held;
		});
	}

	/**
	 * The hotbar is about to select {@code slot} instead of the current one. Without variation memory, a weapon
	 * there switches to its first variant, unless {@code retain}.
	 */
	private void selecting(Inventory inventory, int slot, boolean retain) {
		this.lastSlot = inventory.getSelectedSlot();
		if (retain || UltraConfig.get().variationMemory) {
			return;
		}
		UltraItems.Weapon weapon = UltraItems.Weapon.of(inventory.getItem(slot).getItem());
		List<Item> variants = weapon == null ? List.of() : variants(weapon);
		if (!variants.isEmpty()) {
			switchTo(inventory, slot, variants.getFirst());
		}
	}

	/** Next/Previous Variation: the held weapon's variant {@code step} places along its order, round to the start. */
	private void step(Inventory inventory, int step) {
		Item held = inventory.getSelectedItem().getItem();
		UltraItems.Weapon weapon = UltraItems.Weapon.of(held);
		List<Item> variants = weapon == null ? List.of() : variants(weapon);
		if (variants.size() < 2) {
			return;
		}
		int index = variants.indexOf(held);
		int target = index < 0 ? (step > 0 ? 0 : variants.size() - 1) : Math.floorMod(index + step, variants.size());
		switchTo(inventory, inventory.getSelectedSlot(), variants.get(target));
	}

	/** Variation Slot keys: the held weapon's variant at {@code place} in its order, counted round past the end. */
	private void pick(Inventory inventory, int place) {
		UltraItems.Weapon weapon = UltraItems.Weapon.of(inventory.getSelectedItem().getItem());
		List<Item> variants = weapon == null ? List.of() : variants(weapon);
		if (!variants.isEmpty()) {
			switchTo(inventory, inventory.getSelectedSlot(), variants.get(place % variants.size()));
		}
	}

	/**
	 * Swaps the weapon in the hotbar slot for its variant {@code item}, here at once and on the server. A slot
	 * holding it already draws it again.
	 */
	private static void switchTo(Inventory inventory, int slot, Item item) {
		ItemStack current = inventory.getItem(slot);
		if (current.is(item)) {
			ClientCombat.INSTANCE.redraw();
			return;
		}
		UltraItems.Weapon weapon = UltraItems.Weapon.of(item);
		if (weapon == null || !ClientPlayNetworking.canSend(SwitchVariantPayload.TYPE)) {
			return;
		}
		inventory.setItem(slot, new ItemStack(item, current.getCount()));
		ClientPlayNetworking.send(new SwitchVariantPayload(slot, weapon.color(item), weapon.isAlternate(item)));
	}
}
