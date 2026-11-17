package de.blueprintghost;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Kleines Info-Panel oben links. */
public final class BlueprintHud {
	private BlueprintHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		BlueprintSession s = BlueprintSession.INSTANCE;
		if (!s.active() || mc.player == null || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		Font font = mc.font;
		int total = s.blueprint.size();
		int percent = total == 0 ? 0 : (int) Math.round(100.0 * s.countCorrect / total);

		String line1 = "Bauplan: " + s.blueprint.name + (s.visible ? "" : " (ausgeblendet)");
		String line2 = "Fertig: " + s.countCorrect + "/" + total + " (" + percent + "%)  Fehlt: " + s.countMissing + "  Falsch: " + s.countWrong;
		String line3 = (s.layer == BlueprintSession.LAYER_ALL ? "Ansicht: " : "Ebene: ") + layerText(s);

		int x = 4, y = 4;
		int w = Math.max(font.width(line1), Math.max(font.width(line2), font.width(line3)));

		String t1 = null, t2 = null, t3 = null;
		ItemStack icon = ItemStack.EMPTY;
		int t1Color = 0xFFFFFFFF;
		if (s.targetPos != null) {
			Item item = s.targetState.getBlock().asItem();
			icon = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
			String prefix = switch (s.targetStatus) {
				case BlueprintSession.WRONG_BLOCK -> "Falscher Block! Soll: ";
				case BlueprintSession.WRONG_STATE -> "Falsch gedreht! Soll: ";
				default -> "Hier hin: ";
			};
			t1Color = switch (s.targetStatus) {
				case BlueprintSession.WRONG_BLOCK -> 0xFFFF6060;
				case BlueprintSession.WRONG_STATE -> 0xFFFFD54F;
				default -> 0xFF80DEEA;
			};
			t1 = prefix + s.targetState.getBlock().getName().getString();
			t2 = BlockText.orientationHint(s.targetState);
			t3 = inventoryHint(mc.player.getInventory(), item);
			w = Math.max(w, 20 + Math.max(font.width(t1), Math.max(font.width(t2), font.width(t3))));
		}

		int lines = 3 + (t1 != null ? (t2.isEmpty() ? 2 : 3) : 0);
		int h = lines * 10 + (t1 != null ? 4 : 0) + 4;
		g.fill(x - 2, y - 2, x + w + 4, y + h, 0x90000000);

		g.text(font, line1, x, y, 0xFFFFFFFF);
		g.text(font, line2, x, y + 10, 0xFFB0BEC5);
		g.text(font, line3, x, y + 20, 0xFFB0BEC5);

		if (t1 != null) {
			int ty = y + 34;
			if (!icon.isEmpty()) {
				g.item(icon, x, ty);
			}
			g.text(font, t1, x + 20, ty, t1Color);
			int next = ty + 10;
			if (!t2.isEmpty()) {
				g.text(font, t2, x + 20, next, 0xFFDDDDDD);
				next += 10;
			}
			g.text(font, t3, x + 20, next, 0xFFB0BEC5);
		}
	}

	private static String layerText(BlueprintSession s) {
		if (s.layer == BlueprintSession.LAYER_ALL) {
			return "ganzer Bauplan (" + s.blueprint.sizeY + " Ebenen)";
		}
		if (s.currentLayerY == Integer.MIN_VALUE) {
			return "alle Ebenen fertig!";
		}
		int nr = s.currentLayerY - s.origin.getY() + 1;
		String mode = s.layer == BlueprintSession.LAYER_AUTO ? "auto" : "manuell";
		String rest = s.currentLayerRemaining == 0 ? "fertig ✔" : "noch " + s.currentLayerRemaining + "/" + s.currentLayerTotal;
		return nr + "/" + s.blueprint.sizeY + " (" + mode + ") – " + rest;
	}

	private static String inventoryHint(Inventory inv, Item item) {
		if (item == Items.AIR) {
			return "(kein Item)";
		}
		for (int slot = 0; slot < 9; slot++) {
			if (inv.getItem(slot).is(item)) {
				return "In Hotbar-Slot " + (slot + 1);
			}
		}
		int count = 0;
		for (int slot = 0; slot < inv.getContainerSize(); slot++) {
			ItemStack st = inv.getItem(slot);
			if (st.is(item)) {
				count += st.getCount();
			}
		}
		return count > 0 ? "Im Inventar (" + count + "x)" : "Nicht im Inventar";
	}
}
