package de.blueprintghost;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Liste aller Projekte: wechseln, entfernen, neues Projekt laden. */
public final class ProjectsScreen extends Screen {
	private static final int PER_PAGE = 4;
	private static final int ROW = 22;

	private final Screen parent;
	private int page;
	private EditBox sourceBox;
	private Button loadButton;
	private Component status = Component.empty();
	private final List<Button> rowButtons = new ArrayList<>();

	public ProjectsScreen(Screen parent) {
		super(Component.literal("BlueprintGhost – Projekte"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		BlueprintSession s = BlueprintSession.INSTANCE;
		int w = 300;
		int left = width / 2 - w / 2;
		int y = 36;
		rowButtons.clear();
		int pages = Math.max(1, (s.projects.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.min(page, pages - 1);

		for (int k = 0; k < PER_PAGE; k++) {
			int index = page * PER_PAGE + k;
			if (index >= s.projects.size()) {
				break;
			}
			Project p = s.projects.get(index);
			boolean active = index == s.activeProject && s.active();
			String shown = p.name.length() > 30 ? p.name.substring(0, 29) + "…" : p.name;
			Component label = Component.literal((active ? "▶ " : "") + shown + "  ")
				.withStyle(active ? ChatFormatting.GREEN : ChatFormatting.WHITE)
				.append(Component.literal(p.percent() + "%").withStyle(ChatFormatting.GRAY));
			Button open = addRenderableWidget(Button.builder(label, b -> {
				status = Component.literal("Lade „" + p.name + "“ …").withStyle(ChatFormatting.GRAY);
				s.switchTo(index, msg -> status = Component.literal(msg).withStyle(ChatFormatting.RED), this::rebuild);
			}).bounds(left, y, w - 74, 20).build());
			open.setTooltip(Tooltip.create(Component.literal(p.source + "\nEcke: " + p.x + " " + p.y + " " + p.z + "  Drehung: " + p.rotation)));
			rowButtons.add(open);
			Button remove = addRenderableWidget(Button.builder(Component.literal("Entfernen"), b -> {
				s.removeProject(index);
				status = Component.literal("„" + p.name + "“ entfernt (Datei bleibt erhalten).").withStyle(ChatFormatting.GRAY);
				rebuild();
			}).bounds(left + w - 70, y, 70, 20).build());
			remove.setTooltip(Tooltip.create(Component.literal("Aus der Liste entfernen – die Bauplan-Datei wird nicht gelöscht.")));
			y += ROW;
		}
		if (s.projects.isEmpty()) {
			y += ROW;
		}

		// Seiten
		int pagerY = 36 + PER_PAGE * ROW + 2;
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				page = Math.max(0, page - 1);
				rebuild();
			}).bounds(left, pagerY, 30, 20).build()).active = page > 0;
			addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				page = Math.min(pages - 1, page + 1);
				rebuild();
			}).bounds(left + w - 30, pagerY, 30, 20).build()).active = page < pages - 1;
		}

		// Neues Projekt
		int newY = pagerY + 28;
		String previous = sourceBox != null ? sourceBox.getValue() : "";
		sourceBox = new EditBox(font, left, newY, w - 74, 20, Component.literal("Link oder Dateiname"));
		sourceBox.setMaxLength(1024);
		sourceBox.setHint(Component.literal("Link oder Dateiname (.schem, .litematic, .nbt)").withStyle(ChatFormatting.DARK_GRAY));
		sourceBox.setValue(previous);
		addRenderableWidget(sourceBox);
		loadButton = addRenderableWidget(Button.builder(Component.literal("Laden"), b -> loadNew()).bounds(left + w - 70, newY, 70, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Ordner öffnen"), b -> com.mojang.blaze3d.Blaze3D.openPath(BlueprintFiles.folder()))
			.bounds(left, newY + 38, w / 2 - 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose())
			.bounds(left + w / 2 + 2, newY + 38, w / 2 - 2, 20).build());
	}

	private void loadNew() {
		String value = sourceBox.getValue().trim();
		if (value.isEmpty() || minecraft.player == null) {
			status = Component.literal("Bitte einen Link oder Dateinamen eingeben.").withStyle(ChatFormatting.RED);
			return;
		}
		loadButton.active = false;
		BlueprintCommands.loadInto(value, minecraft.player.blockPosition(),
			msg -> status = msg,
			msg -> status = msg,
			() -> {
				if (BlueprintSession.INSTANCE.source != null && !status.getString().startsWith("Fehler")) {
					sourceBox.setValue("");
					status = Component.literal("✔ Geladen und an deine Position gesetzt.").withStyle(ChatFormatting.GREEN);
				}
				rebuild();
			});
	}

	private void rebuild() {
		if (minecraft.gui.screen() == this) {
			rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		BlueprintSession s = BlueprintSession.INSTANCE;
		int left = width / 2 - 150;
		graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
		if (s.projects.isEmpty()) {
			graphics.centeredText(font, "Noch keine Projekte – unten einen Bauplan laden.", width / 2, 42, 0xFFAAAAAA);
		}
		int pagerY = 36 + PER_PAGE * ROW + 2;
		int pages = Math.max(1, (s.projects.size() + PER_PAGE - 1) / PER_PAGE);
		if (pages > 1) {
			graphics.centeredText(font, "Seite " + (page + 1) + " / " + pages, width / 2, pagerY + 6, 0xFFAAAAAA);
		}
		graphics.text(font, "Neues Projekt laden:", left, pagerY + 17, 0xFFFFFFFF);
		graphics.centeredText(font, s.isLoading() ? Component.literal("Lade …") : status, width / 2, pagerY + 28 + 25, 0xFFFFFFFF);
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
