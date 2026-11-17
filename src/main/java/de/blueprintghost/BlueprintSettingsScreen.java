package de.blueprintghost;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Einstellungsmenü (Standard-Taste K oder /bp settings). */
public final class BlueprintSettingsScreen extends Screen {
	private final Screen parent;
	private Button layerDown;
	private Button layerUp;
	private Button layerLabel;
	private CycleButton<Boolean> followButton;

	public BlueprintSettingsScreen(Screen parent) {
		super(Component.literal("BlueprintGhost – Einstellungen"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		BlueprintSession s = BlueprintSession.INSTANCE;
		int w = 200;
		int x = width / 2 - w - 4;
		int y = 52;
		int row = 22;

		// --- Ansicht: ganz / Ebene für Ebene
		addRenderableWidget(CycleButton.booleanBuilder(
				Component.literal("Ebene für Ebene"), Component.literal("Ganzer Bauplan"), s.layerByLayer)
			.create(x, y, w, 20, Component.literal("Ansicht"), (b, value) -> {
				s.layerByLayer = value;
				s.applyViewMode();
				refreshLayerWidgets();
			}));
		y += row;

		// --- Ebene automatisch / manuell
		followButton = addRenderableWidget(CycleButton.booleanBuilder(
				Component.literal("Automatisch (nächste unfertige)"), Component.literal("Manuell"), s.layerFollowAuto)
			.create(x, y, w, 20, Component.literal("Ebene"), (b, value) -> {
				s.layerFollowAuto = value;
				s.applyViewMode();
				refreshLayerWidgets();
			}));
		y += row;

		// --- Manuelle Ebenenwahl
		layerDown = addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
			s.stepLayer(-1);
			refreshLayerWidgets();
		}).bounds(x, y, 30, 20).build());
		layerLabel = addRenderableWidget(Button.builder(Component.empty(), b -> {
		}).bounds(x + 34, y, w - 68, 20).build());
		layerUp = addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
			s.stepLayer(1);
			refreshLayerWidgets();
		}).bounds(x + w - 30, y, 30, 20).build());
		y += row + 4;

		// --- Darstellung
		addRenderableWidget(CycleButton.builder((Boolean v) -> Component.literal(v ? "Echte Blöcke" : "Blaue Kästen"), s.modelGhosts)
			.withValues(List.of(true, false))
			.create(x, y, w, 20, Component.literal("Darstellung"), (b, value) -> {
				s.modelGhosts = value;
				s.markDirty();
				s.save();
			}));
		y += row;

		// --- Deckkraft
		addRenderableWidget(new AbstractSliderButton(x, y, w, 20, Component.empty(), (s.ghostAlpha / 255.0 - 0.1) / 0.9) {
			{
				updateMessage();
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.literal("Deckkraft: " + Math.round((0.1 + value * 0.9) * 100) + "%"));
			}

			@Override
			protected void applyValue() {
				s.ghostAlpha = (int) Math.round((0.1 + value * 0.9) * 255);
				s.markDirty();
				s.save();
			}
		});
		y += row;

		// --- Sichtweite 16..512
		addRenderableWidget(new AbstractSliderButton(x, y, w, 20, Component.empty(), (s.radius - 16) / 496.0) {
			{
				updateMessage();
			}

			@Override
			protected void updateMessage() {
				setMessage(Component.literal("Sichtweite: " + radiusFromValue() + " Blöcke"));
			}

			private int radiusFromValue() {
				return 16 + (int) Math.round(value * 496 / 8.0) * 8;
			}

			@Override
			protected void applyValue() {
				s.radius = radiusFromValue();
				s.markDirty();
				s.save();
			}
		});
		y += row + 4;

		int bottom = y;

		// --- Rechte Spalte: Projekte & Bedienung
		int rx = width / 2 + 4;
		int ry = 52;
		Project current = s.activeProjectInfo();
		String projectLabel = current != null && s.active() ? current.name : "kein Projekt";
		if (projectLabel.length() > 22) {
			projectLabel = projectLabel.substring(0, 21) + "…";
		}
		addRenderableWidget(Button.builder(Component.literal("Projekt: " + projectLabel), b -> minecraft.gui.setScreen(new ProjectsScreen(this)))
			.bounds(rx, ry, w, 20).build())
			.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Projekte wechseln, entfernen oder neu laden")));
		ry += row;
		Button next = addRenderableWidget(Button.builder(Component.literal("Nächstes Projekt ▶"), b -> {
			if (s.projects.size() > 1) {
				int target = (Math.max(0, s.activeProject) + 1) % s.projects.size();
				s.switchTo(target, msg -> {
				}, () -> {
					if (minecraft.gui.screen() == this) {
						rebuildWidgets();
					}
				});
			}
		}).bounds(rx, ry, w, 20).build());
		next.active = s.projects.size() > 1;
		ry += row + 4;
		addRenderableWidget(CycleButton.booleanBuilder(Component.literal("An"), Component.literal("Aus"), s.hudEnabled)
			.create(rx, ry, w, 20, Component.literal("Infoleiste"), (b, value) -> {
				s.hudEnabled = value;
				s.save();
			}));
		ry += row;
		addRenderableWidget(Button.builder(Component.literal("Infoleiste verschieben …"), b -> minecraft.gui.setScreen(new HudPositionScreen(this)))
			.bounds(rx, ry, w, 20).build());
		ry += row + 4;
		addRenderableWidget(Button.builder(Component.literal("Tastenbelegung …"), b -> minecraft.gui.setScreen(new BlueprintHotkeysScreen(this)))
			.bounds(rx, ry, w, 20).build());
		ry += row;

		int fy = Math.max(bottom, ry) + 6;
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose()).bounds(width / 2 - 100, fy, 200, 20).build());
		refreshLayerWidgets();
	}

	private void refreshLayerWidgets() {
		BlueprintSession s = BlueprintSession.INSTANCE;
		boolean layered = s.layerByLayer;
		followButton.active = layered;
		boolean manual = layered && !s.layerFollowAuto && s.active();
		layerDown.active = manual;
		layerUp.active = manual;
		layerLabel.active = false;
		String label;
		if (!s.active()) {
			label = "Kein Bauplan geladen";
		} else if (!layered) {
			label = "Alle " + s.blueprint.sizeY + " Ebenen";
		} else if (s.layerFollowAuto) {
			label = s.lowestIncompleteY == Integer.MIN_VALUE ? "Alle Ebenen fertig"
				: "Ebene " + (s.lowestIncompleteY - s.origin.getY() + 1) + " / " + s.blueprint.sizeY + " (auto)";
		} else {
			label = "Ebene " + (s.layer + 1) + " / " + s.blueprint.sizeY;
		}
		layerLabel.setMessage(Component.literal(label));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		graphics.text(font, "Anzeige", width / 2 - 204, 41, 0xFFFFD54F);
		graphics.text(font, "Projekte & Bedienung", width / 2 + 4, 41, 0xFFFFD54F);
		BlueprintSession s = BlueprintSession.INSTANCE;
		if (s.active()) {
			graphics.centeredText(font, s.blueprint.name, width / 2, 25, 0xFFAAAAAA);
		}
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
