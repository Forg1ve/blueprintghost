package de.blueprintghost;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

public final class BlueprintCommands {
	private BlueprintCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		var root = literal("bp")
			.executes(BlueprintCommands::help)
			.then(literal("help").executes(BlueprintCommands::help))
			.then(literal("load")
				.then(argument("quelle", StringArgumentType.greedyString())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(BlueprintFiles.list(), b))
					.executes(c -> load(c, StringArgumentType.getString(c, "quelle")))))
			.then(literal("list").executes(BlueprintCommands::list))
			.then(literal("folder").executes(c -> {
				com.mojang.blaze3d.Blaze3D.openPath(BlueprintFiles.folder());
				return 1;
			}))
			.then(literal("here").executes(c -> {
				if (!requireActive(c)) return 0;
				BlueprintSession.INSTANCE.origin = c.getSource().getPlayer().blockPosition();
				BlueprintSession.INSTANCE.rebuild();
				ok(c, "Bauplan an deine Position verschoben.");
				return 1;
			}))
			.then(literal("pos")
				.then(argument("x", IntegerArgumentType.integer())
					.then(argument("y", IntegerArgumentType.integer())
						.then(argument("z", IntegerArgumentType.integer())
							.executes(c -> {
								if (!requireActive(c)) return 0;
								BlueprintSession.INSTANCE.origin = new BlockPos(
									IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z"));
								BlueprintSession.INSTANCE.rebuild();
								ok(c, "Ecke gesetzt auf " + BlueprintSession.INSTANCE.origin.toShortString());
								return 1;
							})))))
			.then(literal("move")
				.then(argument("dx", IntegerArgumentType.integer())
					.then(argument("dy", IntegerArgumentType.integer())
						.then(argument("dz", IntegerArgumentType.integer())
							.executes(c -> {
								if (!requireActive(c)) return 0;
								BlueprintSession s = BlueprintSession.INSTANCE;
								s.origin = s.origin.offset(IntegerArgumentType.getInteger(c, "dx"), IntegerArgumentType.getInteger(c, "dy"), IntegerArgumentType.getInteger(c, "dz"));
								s.rebuild();
								ok(c, "Verschoben. Ecke: " + s.origin.toShortString());
								return 1;
							})))))
			.then(literal("rotate")
				.executes(c -> rotate(c, Rotation.CLOCKWISE_90))
				.then(literal("rechts").executes(c -> rotate(c, Rotation.CLOCKWISE_90)))
				.then(literal("links").executes(c -> rotate(c, Rotation.COUNTERCLOCKWISE_90)))
				.then(literal("180").executes(c -> rotate(c, Rotation.CLOCKWISE_180))))
			.then(literal("mirror")
				.then(literal("aus").executes(c -> mirror(c, Mirror.NONE)))
				.then(literal("x").executes(c -> mirror(c, Mirror.FRONT_BACK)))
				.then(literal("z").executes(c -> mirror(c, Mirror.LEFT_RIGHT))))
			.then(literal("projects").executes(BlueprintCommands::projects))
			.then(literal("switch")
				.then(argument("projekt", StringArgumentType.greedyString())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(BlueprintSession.INSTANCE.projects.stream().map(p -> p.name), b))
					.executes(c -> switchProject(c, StringArgumentType.getString(c, "projekt")))))
			.then(literal("remove")
				.then(argument("projekt", StringArgumentType.greedyString())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(BlueprintSession.INSTANCE.projects.stream().map(p -> p.name), b))
					.executes(c -> {
						BlueprintSession s = BlueprintSession.INSTANCE;
						int idx = s.indexOfName(StringArgumentType.getString(c, "projekt").trim());
						if (idx < 0) {
							c.getSource().sendError(Component.literal("Projekt nicht gefunden. /bp projects zeigt alle."));
							return 0;
						}
						String name = s.projects.get(idx).name;
						s.removeProject(idx);
						ok(c, "Projekt „" + name + "“ entfernt (die Datei bleibt erhalten).");
						return 1;
					})))
			.then(literal("hud")
				.then(literal("an").executes(c -> hud(c, true)))
				.then(literal("aus").executes(c -> hud(c, false)))
				.then(literal("verschieben").executes(c -> {
					Minecraft mc = Minecraft.getInstance();
					mc.execute(() -> mc.gui.setScreen(new HudPositionScreen(null)));
					return 1;
				}))
				.then(literal("reset").executes(c -> {
					BlueprintSession s = BlueprintSession.INSTANCE;
					s.hudX = s.hudY = -1;
					s.hudEnabled = true;
					s.save();
					ok(c, "Infoleiste zurück oben links.");
					return 1;
				})))
			.then(literal("settings").executes(c -> {
				openSettings();
				return 1;
			}))
			.then(literal("view")
				.then(literal("ganz").executes(c -> view(c, false)))
				.then(literal("ebenen").executes(c -> view(c, true))))
			.then(literal("layer")
				.then(literal("alle").executes(c -> view(c, false)))
				.then(literal("auto").executes(c -> {
					if (!requireActive(c)) return 0;
					BlueprintSession s = BlueprintSession.INSTANCE;
					s.layerByLayer = true;
					s.layerFollowAuto = true;
					s.applyViewMode();
					ok(c, "Zeige automatisch die unterste unfertige Ebene.");
					return 1;
				}))
				.then(literal("hoch").executes(c -> layerStep(c, 1)))
				.then(literal("runter").executes(c -> layerStep(c, -1)))
				.then(argument("nummer", IntegerArgumentType.integer(1))
					.executes(c -> layer(c, IntegerArgumentType.getInteger(c, "nummer") - 1))))
			.then(literal("radius")
				.then(argument("bloecke", IntegerArgumentType.integer(4, 512))
					.executes(c -> {
						BlueprintSession.INSTANCE.radius = IntegerArgumentType.getInteger(c, "bloecke");
						BlueprintSession.INSTANCE.markDirty();
						BlueprintSession.INSTANCE.save();
						ok(c, "Anzeigeradius: " + BlueprintSession.INSTANCE.radius + " Blöcke");
						return 1;
					})))
			.then(literal("style")
				.then(literal("modell").executes(c -> style(c, true)))
				.then(literal("kasten").executes(c -> style(c, false))))
			.then(literal("alpha")
				.then(argument("prozent", IntegerArgumentType.integer(10, 100))
					.executes(c -> {
						BlueprintSession s = BlueprintSession.INSTANCE;
						s.ghostAlpha = Math.round(IntegerArgumentType.getInteger(c, "prozent") * 2.55F);
						s.markDirty();
						s.save();
						ok(c, "Deckkraft der Geisterblöcke: " + IntegerArgumentType.getInteger(c, "prozent") + "%");
						return 1;
					})))
			.then(literal("toggle").executes(c -> {
				toggle();
				ok(c, BlueprintSession.INSTANCE.visible ? "Bauplan eingeblendet." : "Bauplan ausgeblendet.");
				return 1;
			}))
			.then(literal("materials").executes(c -> materials(c, BlueprintSession.INSTANCE.layer != BlueprintSession.LAYER_ALL))
				.then(literal("alle").executes(c -> materials(c, false)))
				.then(literal("ebene").executes(c -> materials(c, true))))
			.then(literal("info").executes(BlueprintCommands::info))
			.then(literal("clear").executes(c -> {
				BlueprintSession.INSTANCE.clear();
				ok(c, "Bauplan entfernt.");
				return 1;
			}));

		var node = dispatcher.register(root);
		dispatcher.register(literal("blueprint").redirect(node).executes(BlueprintCommands::help));
	}

	// ------------------------------------------------------------ Befehle

	private static int help(CommandContext<FabricClientCommandSource> c) {
		String[][] lines = {
			{"/bp load <URL oder Datei>", "Bauplan laden (.litematic, .schem, .nbt, auch .zip)"},
			{"/bp list", "Baupläne im Ordner anzeigen"},
			{"/bp folder", "Bauplan-Ordner öffnen"},
			{"/bp here", "Bauplan an deine Position setzen"},
			{"/bp move <dx> <dy> <dz>", "Bauplan verschieben"},
			{"/bp rotate [rechts|links|180]", "Bauplan drehen"},
			{"/bp mirror <x|z|aus>", "Bauplan spiegeln"},
			{"/bp settings", "Einstellungsmenü öffnen (Taste K)"},
			{"/bp projects | switch <name> | remove <name>", "Projekte verwalten"},
			{"/bp hud <an|aus|verschieben|reset>", "Infoleiste oben links"},
			{"/bp view <ganz|ebenen>", "Ganzer Bauplan oder Ebene für Ebene"},
			{"/bp layer <auto|nummer|hoch|runter>", "Ebene wählen"},
			{"/bp materials [alle|ebene]", "Fehlende Blöcke (gesamt oder aktuelle Ebene)"},
			{"/bp style <modell|kasten>", "Geister als Blockmodelle oder Kästen"},
			{"/bp alpha <10-100>", "Deckkraft der Geisterblöcke"},
			{"/bp radius <4-512>", "Sichtweite der Geisterblöcke"},
			{"/bp toggle | info | clear", "clear = aktuelles Projekt entfernen"},
		};
		c.getSource().sendFeedback(Component.literal("— BlueprintGhost —").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		for (String[] l : lines) {
			c.getSource().sendFeedback(Component.literal(l[0]).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(l[1].isEmpty() ? "" : "  " + l[1]).withStyle(ChatFormatting.GRAY)));
		}
		c.getSource().sendFeedback(Component.literal("Geisterblock = fehlt, rot = falscher Block, gelb = falsch gedreht").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int load(CommandContext<FabricClientCommandSource> c, String source) {
		FabricClientCommandSource src = c.getSource();
		loadInto(source, src.getPlayer().blockPosition(), src::sendFeedback, src::sendError, () -> {
		});
		return 1;
	}

	/** Lädt (und lädt ggf. herunter) einen Bauplan als neues Projekt. Auch vom Projekte-Bildschirm genutzt. */
	public static void loadInto(String source, BlockPos origin, java.util.function.Consumer<Component> feedback,
		java.util.function.Consumer<Component> errorOut, Runnable onDone) {
		Minecraft mc = Minecraft.getInstance();
		boolean remote = source.trim().toLowerCase().startsWith("http");
		feedback.accept(Component.literal(remote ? "Lade Bauplan herunter…" : "Lade Bauplan…").withStyle(ChatFormatting.GRAY));

		CompletableFuture.supplyAsync(() -> {
			try {
				Path file = BlueprintFiles.resolve(source);
				return new Object[]{file, SchematicReader.read(file)};
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("abgebrochen", e);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}, Util.ioPool()).whenComplete((result, error) -> mc.execute(() -> {
			if (error != null) {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
				if (cause.getCause() != null && cause.getCause().getMessage() != null) {
					msg = cause.getCause().getMessage();
				}
				errorOut.accept(Component.literal("Fehler: " + msg));
				onDone.run();
				return;
			}
			Path file = (Path) result[0];
			Blueprint bp = (Blueprint) result[1];
			BlueprintSession.INSTANCE.set(bp, file.toAbsolutePath().toString(), origin);
			feedback.accept(Component.literal("✔ " + bp.name).withStyle(ChatFormatting.GREEN)
				.append(Component.literal(" geladen: " + bp.sizeX + "×" + bp.sizeY + "×" + bp.sizeZ + ", " + bp.size() + " Blöcke.").withStyle(ChatFormatting.GRAY)));
			if (bp.unknownStates > 0) {
				feedback.accept(Component.literal(bp.unknownStates + " Blocktypen unbekannt (andere Version oder Mod-Blöcke) – werden übersprungen.").withStyle(ChatFormatting.GOLD));
			}
			feedback.accept(Component.literal("Die Ecke liegt an deinen Füßen. Mit /bp move, /bp rotate oder /bp here ausrichten.").withStyle(ChatFormatting.GRAY));
			onDone.run();
		}));
	}

	private static int list(CommandContext<FabricClientCommandSource> c) {
		List<String> files = BlueprintFiles.list();
		if (files.isEmpty()) {
			c.getSource().sendFeedback(Component.literal("Keine Baupläne gefunden. Lege .litematic/.schem/.nbt-Dateien in den Ordner (/bp folder) oder nutze /bp load <URL>.").withStyle(ChatFormatting.GRAY));
			return 0;
		}
		c.getSource().sendFeedback(Component.literal("Baupläne (anklicken zum Laden):").withStyle(ChatFormatting.AQUA));
		for (String f : files) {
			MutableComponent line = Component.literal(" • " + f).withStyle(style -> style
				.withColor(ChatFormatting.YELLOW)
				.withClickEvent(new ClickEvent.SuggestCommand("/bp load " + f)));
			c.getSource().sendFeedback(line);
		}
		return files.size();
	}

	private static int rotate(CommandContext<FabricClientCommandSource> c, Rotation by) {
		if (!requireActive(c)) return 0;
		BlueprintSession s = BlueprintSession.INSTANCE;
		s.rotation = s.rotation.getRotated(by);
		s.rebuild();
		ok(c, "Gedreht (" + switch (s.rotation) {
			case NONE -> "0°";
			case CLOCKWISE_90 -> "90°";
			case CLOCKWISE_180 -> "180°";
			case COUNTERCLOCKWISE_90 -> "270°";
		} + ").");
		return 1;
	}

	private static int style(CommandContext<FabricClientCommandSource> c, boolean models) {
		BlueprintSession s = BlueprintSession.INSTANCE;
		s.modelGhosts = models;
		s.markDirty();
		s.save();
		ok(c, models ? "Geisterblöcke als echte Blockmodelle." : "Geisterblöcke als blaue Kästen.");
		return 1;
	}

	private static int mirror(CommandContext<FabricClientCommandSource> c, Mirror m) {
		if (!requireActive(c)) return 0;
		BlueprintSession.INSTANCE.mirror = m;
		BlueprintSession.INSTANCE.rebuild();
		ok(c, m == Mirror.NONE ? "Spiegelung aus." : "Gespiegelt.");
		return 1;
	}

	private static int view(CommandContext<FabricClientCommandSource> c, boolean layered) {
		BlueprintSession s = BlueprintSession.INSTANCE;
		s.layerByLayer = layered;
		s.applyViewMode();
		ok(c, layered ? "Ansicht: Ebene für Ebene" + (s.layerFollowAuto ? " (automatisch)." : " (manuell, Bild↑/Bild↓).") : "Ansicht: ganzer Bauplan.");
		return 1;
	}

	private static int hud(CommandContext<FabricClientCommandSource> c, boolean on) {
		BlueprintSession.INSTANCE.hudEnabled = on;
		BlueprintSession.INSTANCE.save();
		ok(c, on ? "Infoleiste eingeblendet." : "Infoleiste ausgeblendet (/bp hud an zum Einblenden).");
		return 1;
	}

	private static int projects(CommandContext<FabricClientCommandSource> c) {
		BlueprintSession s = BlueprintSession.INSTANCE;
		if (s.projects.isEmpty()) {
			c.getSource().sendFeedback(Component.literal("Noch keine Projekte. Lade einen Bauplan mit /bp load <URL oder Datei>.").withStyle(ChatFormatting.GRAY));
			return 0;
		}
		c.getSource().sendFeedback(Component.literal("Projekte (anklicken zum Wechseln):").withStyle(ChatFormatting.AQUA));
		for (int i = 0; i < s.projects.size(); i++) {
			Project p = s.projects.get(i);
			boolean active = i == s.activeProject;
			String text = (active ? " ▶ " : " • ") + p.name + "  " + p.percent() + "%  (" + p.x + " " + p.y + " " + p.z + ")";
			c.getSource().sendFeedback(Component.literal(text).withStyle(style -> style
				.withColor(active ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
				.withClickEvent(new ClickEvent.RunCommand("/bp switch " + p.name))));
		}
		return s.projects.size();
	}

	private static int switchProject(CommandContext<FabricClientCommandSource> c, String name) {
		BlueprintSession s = BlueprintSession.INSTANCE;
		int idx = s.indexOfName(name.trim());
		if (idx < 0) {
			c.getSource().sendError(Component.literal("Projekt nicht gefunden. /bp projects zeigt alle."));
			return 0;
		}
		FabricClientCommandSource src = c.getSource();
		String target = s.projects.get(idx).name;
		s.switchTo(idx, msg -> src.sendError(Component.literal(msg)),
			() -> src.sendFeedback(Component.literal("Projekt gewechselt: " + target).withStyle(ChatFormatting.GREEN)));
		return 1;
	}

	public static void openSettings() {
		Minecraft mc = Minecraft.getInstance();
		// Nach dem Chat-Befehl schließt sich der Chat erst noch -> Bildschirm einen Tick später öffnen
		mc.execute(() -> mc.gui.setScreen(new BlueprintSettingsScreen(null)));
	}

	private static int layer(CommandContext<FabricClientCommandSource> c, int layer) {
		if (!requireActive(c)) return 0;
		BlueprintSession s = BlueprintSession.INSTANCE;
		if (layer >= 0) {
			layer = Math.min(layer, s.blueprint.sizeY - 1);
			s.layerByLayer = true;
			s.layerFollowAuto = false;
		}
		s.layer = layer;
		s.markDirty();
		s.save();
		ok(c, switch (layer) {
			case BlueprintSession.LAYER_ALL -> "Alle Ebenen sichtbar.";
			case BlueprintSession.LAYER_AUTO -> "Zeige automatisch die unterste unfertige Ebene.";
			default -> "Zeige Ebene " + (layer + 1) + "/" + s.blueprint.sizeY + ".";
		});
		return 1;
	}

	private static int layerStep(CommandContext<FabricClientCommandSource> c, int step) {
		if (!requireActive(c)) return 0;
		stepLayer(step);
		return 1;
	}

	/** Auch von den Tastenkürzeln genutzt. */
	public static void stepLayer(int step) {
		BlueprintSession s = BlueprintSession.INSTANCE;
		if (!s.active()) return;
		s.stepLayer(step);
		actionBar("Ebene " + (s.layer + 1) + "/" + s.blueprint.sizeY + " (manuell)");
	}

	public static void toggle() {
		BlueprintSession.INSTANCE.visible = !BlueprintSession.INSTANCE.visible;
		BlueprintSession.INSTANCE.markDirty();
	}

	private static int materials(CommandContext<FabricClientCommandSource> c, boolean onlyLayer) {
		if (!requireActive(c)) return 0;
		BlueprintSession s = BlueprintSession.INSTANCE;
		BlockState[] states = s.worldStates();
		byte[] status = s.status();
		int layerY = onlyLayer ? s.visibleLayerY() : Integer.MIN_VALUE;
		if (onlyLayer && layerY == Integer.MIN_VALUE) {
			onlyLayer = false;
		}
		long[] positions = s.worldPositions();
		Map<Item, Integer> needed = new HashMap<>();
		for (int i = 0; i < states.length; i++) {
			if (status[i] == BlueprintSession.CORRECT || status[i] == BlueprintSession.WRONG_STATE) continue;
			if (onlyLayer && BlockPos.getY(positions[i]) != layerY) continue;
			Item item = states[i].getBlock().asItem();
			if (item == Items.AIR) continue;
			needed.merge(item, 1, Integer::sum);
		}
		if (needed.isEmpty()) {
			ok(c, onlyLayer ? "In dieser Ebene fehlt nichts mehr!" : "Es fehlen keine Blöcke mehr – fertig!");
			return 1;
		}
		Inventory inv = c.getSource().getPlayer().getInventory();
		List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(needed.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());

		String scope = onlyLayer ? "Ebene " + (layerY - s.origin.getY() + 1) : "gesamt";
		c.getSource().sendFeedback(Component.literal("Fehlende Blöcke " + scope + " (" + sorted.size() + " Sorten):").withStyle(ChatFormatting.AQUA));
		int shown = 0;
		StringBuilder file = new StringBuilder("Material für " + s.blueprint.name + " (" + scope + ")\n\n");
		for (Map.Entry<Item, Integer> e : sorted) {
			int have = 0;
			for (int slot = 0; slot < inv.getContainerSize(); slot++) {
				ItemStack st = inv.getItem(slot);
				if (st.is(e.getKey())) have += st.getCount();
			}
			String name = e.getKey().getName(new ItemStack(e.getKey())).getString();
			int need = e.getValue();
			String stacks = need >= 64 ? " (" + (need / 64) + " Stacks + " + (need % 64) + ")" : "";
			file.append(need).append("x ").append(name).append(stacks).append("   [im Inventar: ").append(have).append("]\n");
			if (shown++ < 15) {
				ChatFormatting color = have >= need ? ChatFormatting.GREEN : have > 0 ? ChatFormatting.YELLOW : ChatFormatting.RED;
				c.getSource().sendFeedback(Component.literal(" " + need + "× ").withStyle(ChatFormatting.WHITE)
					.append(Component.literal(name).withStyle(ChatFormatting.GRAY))
					.append(Component.literal("  (hast " + have + ")").withStyle(color)));
			}
		}
		Path out = BlueprintFiles.folder().resolve("materialliste.txt");
		try (Writer w = Files.newBufferedWriter(out)) {
			w.write(file.toString());
		} catch (IOException ignored) {
		}
		if (sorted.size() > 15) {
			c.getSource().sendFeedback(Component.literal(" … und " + (sorted.size() - 15) + " weitere.").withStyle(ChatFormatting.GRAY));
		}
		c.getSource().sendFeedback(Component.literal("Komplette Liste: materialliste.txt (klicken)").withStyle(style -> style
			.withColor(ChatFormatting.DARK_AQUA).withUnderlined(true)
			.withClickEvent(new ClickEvent.OpenFile(out))));
		return 1;
	}

	private static int info(CommandContext<FabricClientCommandSource> c) {
		if (!requireActive(c)) return 0;
		BlueprintSession s = BlueprintSession.INSTANCE;
		ok(c, s.blueprint.name + ": " + s.blueprint.sizeX + "×" + s.blueprint.sizeY + "×" + s.blueprint.sizeZ
			+ ", Ecke " + s.origin.toShortString() + ", Drehung " + s.rotation.name() + ", Radius " + s.radius);
		return 1;
	}

	// ------------------------------------------------------------ Hilfen

	private static boolean requireActive(CommandContext<FabricClientCommandSource> c) {
		if (BlueprintSession.INSTANCE.active()) return true;
		c.getSource().sendError(Component.literal("Kein Bauplan geladen. Nutze /bp load <URL oder Datei>."));
		return false;
	}

	private static void ok(CommandContext<FabricClientCommandSource> c, String msg) {
		c.getSource().sendFeedback(Component.literal(msg).withStyle(ChatFormatting.GREEN));
	}

	static void actionBar(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(msg));
		}
	}
}
