package de.blueprintghost;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Infoleiste mit der Maus verschieben, an Ecken ausrichten oder abschalten. */
public final class HudPositionScreen extends Screen {
	private final Screen parent;
	private boolean dragging;
	private double grabX, grabY;

	public HudPositionScreen(Screen parent) {
		super(Component.literal("Infoleiste verschieben"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		BlueprintSession s = BlueprintSession.INSTANCE;
		int w = 100;
		int cx = width / 2;
		int y = height / 2 - 10;
		addRenderableWidget(Button.builder(Component.literal("↖ Oben links"), b -> corner(0, 0)).bounds(cx - w - 2, y - 24, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Oben rechts ↗"), b -> corner(1, 0)).bounds(cx + 2, y - 24, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("↙ Unten links"), b -> corner(0, 1)).bounds(cx - w - 2, y, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Unten rechts ↘"), b -> corner(1, 1)).bounds(cx + 2, y, w, 20).build());
		addRenderableWidget(CycleButton.booleanBuilder(Component.literal("An"), Component.literal("Aus"), s.hudEnabled)
			.create(cx - w - 2, y + 30, w * 2 + 4, 20, Component.literal("Infoleiste"), (b, v) -> {
				s.hudEnabled = v;
				s.save();
			}));
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose()).bounds(cx - w - 2, y + 54, w * 2 + 4, 20).build());
	}

	private void corner(double fx, double fy) {
		BlueprintSession s = BlueprintSession.INSTANCE;
		s.hudX = fx;
		s.hudY = fy;
		s.save();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && inside(event.x(), event.y())) {
			dragging = true;
			grabX = event.x() - BlueprintHud.lastX;
			grabY = event.y() - BlueprintHud.lastY;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			BlueprintSession s = BlueprintSession.INSTANCE;
			double freeW = Math.max(1, width - BlueprintHud.lastW - 4);
			double freeH = Math.max(1, height - BlueprintHud.lastH - 4);
			s.hudX = clamp((event.x() - grabX - 2) / freeW);
			s.hudY = clamp((event.y() - grabY - 2) / freeH);
			// Nah am Rand einrasten
			if (s.hudX < 0.02) s.hudX = 0;
			if (s.hudX > 0.98) s.hudX = 1;
			if (s.hudY < 0.02) s.hudY = 0;
			if (s.hudY > 0.98) s.hudY = 1;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging) {
			dragging = false;
			BlueprintSession.INSTANCE.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}

	private boolean inside(double mx, double my) {
		return mx >= BlueprintHud.lastX && mx <= BlueprintHud.lastX + BlueprintHud.lastW
			&& my >= BlueprintHud.lastY && my <= BlueprintHud.lastY + BlueprintHud.lastH;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// kein Weichzeichner: man soll das Spielbild sehen
		graphics.fill(0, 0, width, height, 0x30000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		BlueprintHud.draw(graphics, true);
		int color = dragging || inside(mouseX, mouseY) ? 0xFFFFFF55 : 0xA0FFFFFF;
		int x0 = BlueprintHud.lastX, y0 = BlueprintHud.lastY, x1 = x0 + BlueprintHud.lastW, y1 = y0 + BlueprintHud.lastH;
		graphics.fill(x0 - 1, y0 - 1, x1 + 1, y0, color);
		graphics.fill(x0 - 1, y1, x1 + 1, y1 + 1, color);
		graphics.fill(x0 - 1, y0, x0, y1, color);
		graphics.fill(x1, y0, x1 + 1, y1, color);
		graphics.centeredText(font, title, width / 2, height / 2 - 62, 0xFFFFFFFF);
		graphics.centeredText(font, "Leiste mit der Maus ziehen oder eine Ecke wählen", width / 2, height / 2 - 50, 0xFFAAAAAA);
	}

	@Override
	public void onClose() {
		BlueprintSession.INSTANCE.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
