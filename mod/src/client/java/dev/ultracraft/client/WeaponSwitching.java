package dev.ultracraft.client;

import dev.ultracraft.UltraItems;
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
	/** GunControl.scrollCooldown: 0.5, counted down at 5 a second. */
	private static final long SCROLL_COOLDOWN_NANOS = 100_000_000L;

	/** GunControl.lastSlotIndex: the hotbar slot selected before the current one, or -1. */
	private int lastSlot = -1;
	private long scrollReadyAt;

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

	/** A hotbar key pressed for {@code slot}, before Minecraft selects it. */
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
		List<Item> variants = variants(weapon);
		if (variants.isEmpty()) {
			return;
		}
		int index = variants.indexOf(held);
		switch (UltraConfig.get().weaponRedraw) {
			case CYCLE -> switchTo(inventory, slot, variants.get(Math.floorMod(index + 1, variants.size())));
			case FIRST -> switchTo(inventory, slot, variants.getFirst());
			case KEEP -> {
				if (index < 0) {
					switchTo(inventory, slot, variants.getFirst());
				}
			}
		}
	}

	/**
	 * A scroll of the hotbar while ULTRAKILL movement is on: {@code toward} is the slot Minecraft picks for it.
	 * Returns the slot to select.
	 */
	public int scroll(Inventory inventory, int toward) {
		UltraConfig config = UltraConfig.get();
		int current = inventory.getSelectedSlot();
		long now = System.nanoTime();
		if (!config.scrollEnabled || now < this.scrollReadyAt) {
			return current;
		}
		// Rightwards along the hotbar is forwards.
		int direction = Math.floorMod(toward - current, Inventory.getSelectionSize()) == 1 ? 1 : -1;
		if (config.scrollReversed) {
			direction = -direction;
		}
		Item held = inventory.getSelectedItem().getItem();
		UltraItems.Weapon weapon = UltraItems.Weapon.of(held);
		if (weapon != null && config.scrollMode != UltraConfig.ScrollMode.WEAPONS) {
			List<Item> variants = variants(weapon);
			int index = variants.indexOf(held);
			if (config.scrollMode == UltraConfig.ScrollMode.VARIATIONS) {
				if (variants.size() > 1) {
					step(inventory, direction);
					this.scrollReadyAt = now + SCROLL_COOLDOWN_NANOS;
				}
				return current;
			}
			if (direction > 0 ? index + 1 < variants.size() : index > 0) {
				switchTo(inventory, current, variants.get(index + direction));
				this.scrollReadyAt = now + SCROLL_COOLDOWN_NANOS;
				return current;
			}
		}
		int slot = Math.floorMod(current + direction, Inventory.getSelectionSize());
		boolean backIntoLast = config.scrollMode == UltraConfig.ScrollMode.BOTH && direction < 0;
		selecting(inventory, slot, backIntoLast);
		if (backIntoLast) {
			UltraItems.Weapon previous = UltraItems.Weapon.of(inventory.getItem(slot).getItem());
			List<Item> variants = previous == null ? List.of() : variants(previous);
			if (!variants.isEmpty()) {
				switchTo(inventory, slot, variants.getLast());
			}
		}
		this.scrollReadyAt = now + SCROLL_COOLDOWN_NANOS;
		return slot;
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

	/** Swaps the weapon in the hotbar slot for its variant {@code item}, here at once and on the server. */
	private static void switchTo(Inventory inventory, int slot, Item item) {
		ItemStack current = inventory.getItem(slot);
		UltraItems.Weapon weapon = UltraItems.Weapon.of(item);
		if (current.is(item) || weapon == null || !ClientPlayNetworking.canSend(SwitchVariantPayload.TYPE)) {
			return;
		}
		inventory.setItem(slot, new ItemStack(item, current.getCount()));
		ClientPlayNetworking.send(new SwitchVariantPayload(slot, weapon.color(item), weapon.isAlternate(item)));
	}
}
