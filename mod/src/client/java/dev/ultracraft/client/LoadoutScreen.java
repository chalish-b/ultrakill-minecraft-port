package dev.ultracraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ultracraft.UltraItems;
import dev.ultracraft.Ultracraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shop terminal's weapon pages (VariationInfo, WeaponOrderController): each weapon's variants with their equip
 * state and place in the order, and a page for the weapon switching options.
 */
public final class LoadoutScreen extends Screen {
	private static final FontDescription FONT = new FontDescription.Resource(Ultracraft.id("vcr_osd_mono_1_001"));
	private static final int WIDTH = 420;
	private static final int HEIGHT = 240;
	private static final int MENU_WIDTH = 120;
	private static final int GAP = 8;
	private static final int ROW_HEIGHT = 74;
	private static final int BLACK = 0xFF000000;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFF808080;
	private static final int HOVER = 0xFF404040;
	/** VariationInfo.SetEquipStatusText's colors: Color.gray, Color.white, (1, 0.3, 0.3). */
	private static final int[] EQUIP_COLORS = {GRAY, WHITE, 0xFFFF4D4D};

	/** The page shown: a weapon, or null for the options. */
	private UltraItems.Weapon page = UltraItems.Weapon.REVOLVER;
	private final List<Button> buttons = new ArrayList<>();

	public LoadoutScreen() {
		super(Component.translatable("ultracraft.loadout"));
	}

	/** A box drawn with a white border that does something when clicked. */
	private record Button(int x, int y, int width, int height, Runnable click) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		this.buttons.clear();
		int left = (this.width - WIDTH) / 2;
		int top = (this.height - HEIGHT) / 2;
		menu(graphics, left, top, mouseX, mouseY);
		int x = left + MENU_WIDTH + GAP;
		int width = WIDTH - MENU_WIDTH - GAP;
		if (this.page == null) {
			options(graphics, x, top, width, mouseX, mouseY);
		} else {
			for (int color = 0; color < UltraItems.Weapon.COLORS; color++) {
				variant(graphics, this.page, color, x, top + color * (ROW_HEIGHT + (HEIGHT - 3 * ROW_HEIGHT) / 2), width, mouseX, mouseY);
			}
		}
	}

	private void menu(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
		panel(graphics, x, y, MENU_WIDTH, HEIGHT);
		text(graphics, Component.translatable("ultracraft.loadout.menu"), x + MENU_WIDTH / 2f, y + 16, 2, WHITE, true);
		int buttonY = y + 50;
		for (UltraItems.Weapon weapon : UltraItems.Weapon.values()) {
			tab(graphics, weapon, Component.translatable("ultracraft.loadout." + name(weapon)), x + 8, buttonY, mouseX, mouseY);
			buttonY += 26;
		}
		tab(graphics, null, Component.translatable("ultracraft.loadout.options"), x + 8, buttonY + 12, mouseX, mouseY);
	}

	private void tab(GuiGraphicsExtractor graphics, UltraItems.Weapon weapon, Component label, int x, int y, int mouseX, int mouseY) {
		boolean selected = this.page == weapon;
		Button button = button(graphics, x, y, MENU_WIDTH - 16, 20, mouseX, mouseY, selected ? WHITE : BLACK, () -> this.page = weapon);
		text(graphics, label, button.x() + button.width() / 2f, button.y() + 6, 1, selected ? BLACK : WHITE, true);
	}

	/** A variant's row: its place in the order, icon and name, and its equip state. */
	private void variant(GuiGraphicsExtractor graphics, UltraItems.Weapon weapon, int color, int x, int y, int width, int mouseX, int mouseY) {
		UltraConfig.Loadout loadout = UltraConfig.get().loadout(weapon);
		UltraConfig.Equip equip = loadout.equip[color];
		panel(graphics, x, y, width, ROW_HEIGHT);
		int middle = y + ROW_HEIGHT / 2;
		boolean equipped = equip != UltraConfig.Equip.UNEQUIPPED;
		if (equipped) {
			Button up = button(graphics, x + 6, middle - 31, 16, 14, mouseX, mouseY, BLACK, () -> change(() -> loadout.move(color, -1)));
			text(graphics, "▲", up.x() + 8.5f, up.y() + 3, 1, WHITE, true);
			box(graphics, x + 6, middle - 13, 16, 26, BLACK);
			text(graphics, Integer.toString(loadout.order[color]), x + 14.5f, middle - 8, 2, WHITE, true);
			Button down = button(graphics, x + 6, middle + 17, 16, 14, mouseX, mouseY, BLACK, () -> change(() -> loadout.move(color, 1)));
			text(graphics, "▼", down.x() + 8.5f, down.y() + 3, 1, WHITE, true);
		}
		// VariationInfo moves the icon over the order buttons and enlarges it while they're hidden.
		int iconSize = equipped ? 48 : 64;
		int iconX = equipped ? x + 28 : x + 6;
		Item shown = weapon.variant(color, equip == UltraConfig.Equip.ALTERNATE);
		Identifier icon = Ultracraft.id("textures/item/" + BuiltInRegistries.ITEM.getKey(shown).getPath() + ".png");
		graphics.blit(RenderPipelines.GUI_TEXTURED, icon, iconX, middle - iconSize / 2, 0, 0, iconSize, iconSize, 64, 64, 64, 64);
		text(graphics, Component.translatable("ultracraft.loadout." + name(weapon) + "." + color), x + 82, middle - 7, 1.5f, WHITE, false);

		int statusX = x + width - 92;
		Button status = button(graphics, statusX, middle - 22, 86, 24, mouseX, mouseY, BLACK, () -> changeEquipment(weapon, color, 1));
		text(graphics, Component.translatable("ultracraft.loadout.equip." + equip.name().toLowerCase(Locale.ROOT)),
			status.x() + status.width() / 2f, status.y() + 7, 1.5f, EQUIP_COLORS[equip.ordinal()], true);
		Button previous = button(graphics, statusX, middle + 6, 41, 14, mouseX, mouseY, BLACK, () -> changeEquipment(weapon, color, -1));
		text(graphics, "«", previous.x() + 21, previous.y() + 3, 1, WHITE, true);
		Button next = button(graphics, statusX + 45, middle + 6, 41, 14, mouseX, mouseY, BLACK, () -> changeEquipment(weapon, color, 1));
		text(graphics, "»", next.x() + 21, next.y() + 3, 1, WHITE, true);
	}

	/** VariationInfo.ChangeEquipment: steps through unequipped, equipped and, if the weapon has one, alternate. */
	private void changeEquipment(UltraItems.Weapon weapon, int color, int value) {
		UltraConfig.Loadout loadout = UltraConfig.get().loadout(weapon);
		int status = loadout.equip[color].ordinal() + (value > 0 ? 1 : -1);
		if (status < 0) {
			status = weapon.hasAlternate() ? 2 : 1;
		} else if (status == 2) {
			status = weapon.hasAlternate() ? 2 : 0;
		} else if (status > 2) {
			status = 0;
		}
		int equip = status;
		change(() -> loadout.equip[color] = UltraConfig.Equip.values()[equip]);
	}

	private void options(GuiGraphicsExtractor graphics, int x, int y, int width, int mouseX, int mouseY) {
		panel(graphics, x, y, width, HEIGHT);
		UltraConfig config = UltraConfig.get();
		int rowY = y + 14;
		option(graphics, "weapon_redraw", "redraw." + config.weaponRedraw.name().toLowerCase(Locale.ROOT), x, rowY, width, mouseX, mouseY,
			() -> config.weaponRedraw = next(UltraConfig.Redraw.values(), config.weaponRedraw));
		option(graphics, "variation_memory", onOff(config.variationMemory), x, rowY += 30, width, mouseX, mouseY,
			() -> config.variationMemory = !config.variationMemory);
		option(graphics, "scroll_enabled", onOff(config.scrollEnabled), x, rowY += 30, width, mouseX, mouseY,
			() -> config.scrollEnabled = !config.scrollEnabled);
		option(graphics, "scroll_mode", "scroll_mode." + config.scrollMode.name().toLowerCase(Locale.ROOT), x, rowY += 30, width, mouseX, mouseY,
			() -> config.scrollMode = next(UltraConfig.ScrollMode.values(), config.scrollMode));
		option(graphics, "scroll_reversed", onOff(config.scrollReversed), x, rowY += 30, width, mouseX, mouseY,
			() -> config.scrollReversed = !config.scrollReversed);
	}

	private void option(GuiGraphicsExtractor graphics, String name, String value, int x, int y, int width, int mouseX, int mouseY, Runnable change) {
		text(graphics, Component.translatable("ultracraft.loadout.option." + name), x + 10, y + 7, 1, WHITE, false);
		Button button = button(graphics, x + width - 128, y, 120, 22, mouseX, mouseY, BLACK, () -> change(change));
		text(graphics, Component.translatable("ultracraft.loadout.option." + value), button.x() + button.width() / 2f, button.y() + 7, 1, WHITE, true);
	}

	private static String onOff(boolean on) {
		return on ? "on" : "off";
	}

	private static <T extends Enum<T>> T next(T[] values, T value) {
		return values[(value.ordinal() + 1) % values.length];
	}

	private static void change(Runnable change) {
		change.run();
		UltraConfig.get().save();
	}

	private static String name(UltraItems.Weapon weapon) {
		return weapon.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (Button button : this.buttons) {
				if (button.contains(event.x(), event.y())) {
					button.click().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (UltraKeys.LOADOUT.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private Button button(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY, int fill, Runnable click) {
		Button button = new Button(x, y, width, height, click);
		box(graphics, x, y, width, height, fill == BLACK && button.contains(mouseX, mouseY) ? HOVER : fill);
		this.buttons.add(button);
		return button;
	}

	/** A panel: black with a gray frame inside a white one. */
	private static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		graphics.fill(x, y, x + width, y + height, BLACK);
		graphics.outline(x, y, width, height, GRAY);
		graphics.outline(x + 2, y + 2, width - 4, height - 4, WHITE);
	}

	private static void box(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill) {
		graphics.fill(x, y, x + width, y + height, fill);
		graphics.outline(x, y, width, height, WHITE);
	}

	private void text(GuiGraphicsExtractor graphics, String content, float x, float y, float scale, int color, boolean centered) {
		text(graphics, Component.literal(content), x, y, scale, color, centered);
	}

	/** Text in the terminal's font, {@code scale} times its size, from {@code x} or centered on it. */
	private void text(GuiGraphicsExtractor graphics, Component content, float x, float y, float scale, int color, boolean centered) {
		Component styled = content.copy().withStyle(style -> style.withFont(FONT));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.text(this.font, styled, centered ? -this.font.width(styled) / 2 : 0, 0, color, false);
		graphics.pose().popMatrix();
	}
}
