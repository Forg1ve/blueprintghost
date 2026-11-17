package de.blueprintghost;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Tastenbelegung nur für die Tasten dieser Mod. */
public final class BlueprintHotkeysScreen extends Screen {
	private static final int ROW = 24;

	private final Screen parent;
	private final List<Button> keyButtons = new ArrayList<>();
	private final List<Button> resetButtons = new ArrayList<>();
	private KeyMapping selected;

	public BlueprintHotkeysScreen(Screen parent) {
		super(Component.literal("BlueprintGhost – Tastenbelegung"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		keyButtons.clear();
		resetButtons.clear();
		List<KeyMapping> keys = BlueprintGhostClient.keys();
		int total = 360;
		int left = width / 2 - total / 2;
		int y = 44;
		for (KeyMapping key : keys) {
			Button keyButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
				selected = key;
				refresh();
			}).bounds(left + 200, y, 96, 20).build());
			Button reset = addRenderableWidget(Button.builder(Component.literal("Standard"), b -> {
				key.setKey(key.getDefaultKey());
				applyChange();
			}).bounds(left + 300, y, 60, 20).build());
			reset.setTooltip(Tooltip.create(Component.literal("Auf Standard zurücksetzen")));
			keyButtons.add(keyButton);
			resetButtons.add(reset);
			y += ROW;
		}
		y += 8;
		addRenderableWidget(Button.builder(Component.literal("Alle zurücksetzen"), b -> {
			for (KeyMapping key : keys) {
				key.setKey(key.getDefaultKey());
			}
			applyChange();
		}).bounds(left, y, total / 2 - 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose())
			.bounds(left + total / 2 + 2, y, total / 2 - 2, 20).build());
		refresh();
	}

	private void applyChange() {
		selected = null;
		KeyMapping.resetMapping();
		minecraft.options.save();
		refresh();
	}

	private void refresh() {
		List<KeyMapping> keys = BlueprintGhostClient.keys();
		for (int i = 0; i < keys.size(); i++) {
			KeyMapping key = keys.get(i);
			Button button = keyButtons.get(i);
			KeyMapping conflict = conflictOf(key);
			MutableComponent label = key.getTranslatedKeyMessage().copy();
			if (key == selected) {
				button.setMessage(Component.literal("> ").append(label.withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE))
					.append(" <").withStyle(ChatFormatting.YELLOW));
			} else if (conflict != null) {
				button.setMessage(label.withStyle(ChatFormatting.RED));
			} else {
				button.setMessage(key.isUnbound() ? Component.literal("—").withStyle(ChatFormatting.GRAY) : label);
			}
			button.setTooltip(conflict == null ? null
				: Tooltip.create(Component.literal("Auch belegt mit: ").append(Component.translatable(conflict.getName()))));
			resetButtons.get(i).active = !key.isDefault();
		}
	}

	/** Andere Taste (auch aus Vanilla/anderen Mods) mit derselben Belegung. */
	private KeyMapping conflictOf(KeyMapping key) {
		if (key.isUnbound()) {
			return null;
		}
		for (KeyMapping other : minecraft.options.keyMappings) {
			// Debug-Tasten (F3+…) nur zusammen mit F3 -> kein echter Konflikt
			if (other != key && other.same(key) && other.getCategory() != KeyMapping.Category.DEBUG) {
				return other;
			}
		}
		return null;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (selected != null) {
			if (event.isEscape()) {
				selected = null; // abbrechen
				refresh();
			} else if (event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE) {
				selected.setKey(InputConstants.UNKNOWN); // Taste entfernen
				applyChange();
			} else {
				selected.setKey(InputConstants.getKey(event));
				applyChange();
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Maustasten 4/5 usw. belegbar; Links-/Rechtsklick bleiben für die Bedienung
		if (selected != null && event.button() > 1) {
			selected.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
			applyChange();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
		int left = width / 2 - 180;
		int y = 44;
		for (KeyMapping key : BlueprintGhostClient.keys()) {
			graphics.text(font, Component.translatable(key.getName()), left, y + 6, 0xFFFFFFFF);
			y += ROW;
		}
		String hint = selected != null
			? "Drücke eine Taste …  (Esc = abbrechen, " + (BlueprintGhostClient.MAC ? "⌫" : "Entf/Rücktaste") + " = Taste entfernen)"
			: "Auf eine Taste klicken, um sie zu ändern. Rot = doppelt belegt.";
		graphics.centeredText(font, hint, width / 2, y + 40, selected != null ? 0xFFFFFF55 : 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
