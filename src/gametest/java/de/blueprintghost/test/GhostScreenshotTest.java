package de.blueprintghost.test;

import de.blueprintghost.Blueprint;
import de.blueprintghost.BlueprintSession;
import de.blueprintghost.SchematicReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Lädt Baupläne in eine Testwelt und macht Screenshots der Geisterblöcke. */
public class GhostScreenshotTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("time set noon");

			BlockPos spawn = ctx.computeOnClient(mc -> mc.player.blockPosition());

			// --- 1) Kleines Testhaus mit verschiedenen Blocktypen
			BlockPos houseOrigin = spawn.offset(4, 0, -3);
			Blueprint house = smallHouse();
			ctx.runOnClient(mc -> BlueprintSession.INSTANCE.set(house, "test-house", houseOrigin));
			// ein paar Blöcke schon "gebaut": richtig, falsch, falsch gedreht
			setblock(sp, houseOrigin, "oak_planks");
			setblock(sp, houseOrigin.offset(1, 0, 0), "stone");
			setblock(sp, houseOrigin.offset(0, 4, 0), "oak_stairs[facing=south]");
			tpLook(sp, spawn.offset(-3, 2, -8), -40F, 15F);
			ctx.waitTicks(30);
			sp.getConnection().waitForChunksRender();
			ctx.takeScreenshot("bpg_house");

			// --- Ebene für Ebene: unterste Ebene zeigen, dann fertig bauen -> automatisch weiter
			ctx.runOnClient(mc -> {
				BlueprintSession.INSTANCE.layerByLayer = true;
				BlueprintSession.INSTANCE.layerFollowAuto = true;
				BlueprintSession.INSTANCE.applyViewMode();
			});
			ctx.waitTicks(30);
			ctx.takeScreenshot("bpg_house_layer1");
			sp.getServer().runOnServer(server -> {
				var level = server.overworld();
				BlueprintSession bs = BlueprintSession.INSTANCE;
				long[] pos = bs.worldPositions();
				BlockState[] st = bs.worldStates();
				for (int i = 0; i < pos.length; i++) {
					if (BlockPos.getY(pos[i]) == houseOrigin.getY()) {
						level.setBlock(BlockPos.of(pos[i]), st[i], 2);
					}
				}
			});
			ctx.waitTicks(40);
			ctx.takeScreenshot("bpg_house_layer2");

			// --- Einstellungsmenü
			ctx.setScreen(() -> new de.blueprintghost.BlueprintSettingsScreen(null));
			ctx.waitTicks(5);
			ctx.takeScreenshot("bpg_settings");
			ctx.clickScreenButton("Tastenbelegung …");
			ctx.waitTicks(5);
			ctx.takeScreenshot("bpg_hotkeys");
			// Umbelegen testen: N -> J, dann zurücksetzen
			ctx.clickScreenButton("N");
			ctx.waitTicks(2);
			ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_J);
			ctx.waitTicks(2);
			String rebound = ctx.computeOnClient(mc -> de.blueprintghost.BlueprintGhostClient.keys().get(1).getTranslatedKeyMessage().getString());
			String saved = ctx.computeOnClient(mc -> {
				try {
					return java.nio.file.Files.readAllLines(mc.gameDirectory.toPath().resolve("options.txt")).stream()
						.filter(l -> l.startsWith("key_key.blueprintghost.toggle")).findFirst().orElse("?");
				} catch (java.io.IOException e) {
					return "?";
				}
			});
			System.out.println("BPGDEBUG rebound toggle -> " + rebound + " | options.txt: " + saved);
			if (!"J".equals(rebound)) {
				throw new AssertionError("Umbelegen fehlgeschlagen: " + rebound);
			}
			ctx.clickScreenButton("Alle zurücksetzen");
			ctx.waitTicks(2);
			ctx.setScreen(() -> null);
			ctx.runOnClient(mc -> {
				BlueprintSession.INSTANCE.layerByLayer = false;
				BlueprintSession.INSTANCE.applyViewMode();
			});

			// --- 2) Die echte Insel (falls vorhanden)
			String schem = System.getProperty("bpg.schem");
			if (schem != null && Files.isRegularFile(Path.of(schem))) {
				Blueprint island;
				try {
					island = SchematicReader.read(Path.of(schem));
				} catch (Exception e) {
					throw new AssertionError("Insel konnte nicht geladen werden", e);
				}
				BlockPos islandOrigin = spawn.offset(10, 5, 10);
				ctx.runOnClient(mc -> BlueprintSession.INSTANCE.set(island, schem, islandOrigin));

				// Übersicht von schräg oben (außerhalb des Radius -> nur teilweise sichtbar)
				tpLook(sp, islandOrigin.offset(86, 125, -70), 0F, 35F);
				ctx.waitTicks(60);
				ctx.runOnClient(mc -> {
					BlueprintSession bs = BlueprintSession.INSTANCE;
					int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
					for (int y : bs.blueprint.ys) { minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
					System.out.println("BPGDEBUG ghosts=" + de.blueprintghost.GhostRenderer.debugCount()
						+ " player=" + mc.player.blockPosition() + " origin=" + bs.origin + " size=" + bs.blueprint.sizeX + "x" + bs.blueprint.sizeY + "x" + bs.blueprint.sizeZ
						+ " relY=" + minY + ".." + maxY + " first=" + bs.blueprint.xs[0] + "," + bs.blueprint.ys[0] + "," + bs.blueprint.zs[0] + " " + bs.blueprint.states[0]);
				});
				ctx.takeScreenshot("bpg_island_overview");

				// Näher dran, Blick auf die Unterseite/Mitte
				tpLook(sp, islandOrigin.offset(86, 75, 20), 0F, 20F);
				ctx.waitTicks(60);
				ctx.takeScreenshot("bpg_island_mid");

				ctx.takeScreenshot("bpg_island_mid2");
				// ganz nah
				tpLook(sp, islandOrigin.offset(86, 95, 55), 0F, 45F);
				ctx.waitTicks(60);
				ctx.takeScreenshot("bpg_island_close");
				// Ebenen-Modus
				ctx.runOnClient(mc -> {
					BlueprintSession.INSTANCE.layerByLayer = true;
					BlueprintSession.INSTANCE.applyViewMode();
				});
				ctx.waitTicks(40);
				ctx.takeScreenshot("bpg_island_layer_auto");

				// Kasten-Modus zum Vergleich
				ctx.runOnClient(mc -> {
					BlueprintSession.INSTANCE.layerByLayer = false;
					BlueprintSession.INSTANCE.applyViewMode();
					BlueprintSession.INSTANCE.modelGhosts = false;
					BlueprintSession.INSTANCE.markDirty();
				});
				ctx.waitTicks(20);
				ctx.takeScreenshot("bpg_island_boxes");
				ctx.runOnClient(mc -> {
					BlueprintSession.INSTANCE.modelGhosts = true;
					BlueprintSession.INSTANCE.markDirty();
				});

				// --- Projekte & Infoleiste
				ctx.setScreen(() -> new de.blueprintghost.BlueprintSettingsScreen(null));
				ctx.waitTicks(5);
				ctx.takeScreenshot("bpg_settings_full");
				ctx.setScreen(() -> new de.blueprintghost.ProjectsScreen(null));
				ctx.waitTicks(5);
				ctx.takeScreenshot("bpg_projects");
				ctx.setScreen(() -> new de.blueprintghost.HudPositionScreen(null));
				ctx.waitTicks(5);
				ctx.clickScreenButton("Oben rechts ↗");
				ctx.waitTicks(5);
				ctx.takeScreenshot("bpg_hud_editor");
				ctx.setScreen(() -> null);
				// zurück zum Testhaus wechseln (liegt im Speicher)
				ctx.runOnClient(mc -> BlueprintSession.INSTANCE.switchTo(0, msg -> {
					throw new AssertionError(msg);
				}, () -> {
				}));
				tpLook(sp, houseOrigin.offset(-3, 2, -8), -40F, 15F);
				ctx.waitTicks(40);
				String activeName = ctx.computeOnClient(mc -> BlueprintSession.INSTANCE.activeProjectInfo().name
					+ " projects=" + BlueprintSession.INSTANCE.projects.size());
				System.out.println("BPGDEBUG after switch: " + activeName);
				ctx.takeScreenshot("bpg_hud_topright_switched");
				ctx.runOnClient(mc -> {
					BlueprintSession.INSTANCE.hudX = -1;
					BlueprintSession.INSTANCE.hudY = -1;
					BlueprintSession.INSTANCE.save();
				});
			}
		}
	}

	private static void setblock(TestSingleplayerContext sp, BlockPos pos, String block) {
		sp.getServer().runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
	}

	private static void tpLook(TestSingleplayerContext sp, BlockPos pos, float yaw, float pitch) {
		setblock(sp, pos.below(), "barrier");
		sp.getServer().runCommand("tp @a " + (pos.getX() + 0.5) + " " + pos.getY() + " " + (pos.getZ() + 0.5) + " " + yaw + " " + pitch);
		sp.getConnection().waitForChunksRender();
	}

	/** 5x5 Häuschen: Bretter, Glas, Stamm, Treppendach, Laub, Fackel. */
	private static Blueprint smallHouse() {
		List<int[]> pos = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 5; x++) {
				for (int z = 0; z < 5; z++) {
					boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
					if (!edge && y > 0) continue;
					boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
					BlockState s = corner ? Blocks.OAK_LOG.defaultBlockState()
						: (y == 2 && (x == 2 || z == 2)) ? Blocks.GLASS.defaultBlockState()
						: (y == 0 && !edge) ? Blocks.STONE_BRICKS.defaultBlockState()
						: Blocks.OAK_PLANKS.defaultBlockState();
					if (y > 0 && x == 2 && z == 0) {
						if (y == 1) s = Blocks.OAK_DOOR.defaultBlockState();
						else if (y == 2) s = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
					}
					pos.add(new int[]{x, y, z});
					states.add(s);
				}
			}
		}
		// Treppendach
		for (int x = 0; x < 5; x++) {
			for (int z = 0; z < 5; z++) {
				Direction facing = z < 2 ? Direction.SOUTH : z > 2 ? Direction.NORTH : null;
				BlockState s = facing == null ? Blocks.OAK_SLAB.defaultBlockState()
					: Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
				pos.add(new int[]{x, 4, z});
				states.add(s);
			}
		}
		// Laub + Fackel
		pos.add(new int[]{2, 5, 2});
		states.add(Blocks.OAK_LEAVES.defaultBlockState());
		pos.add(new int[]{1, 1, 1});
		states.add(Blocks.TORCH.defaultBlockState());

		int n = pos.size();
		int[] xs = new int[n], ys = new int[n], zs = new int[n];
		for (int i = 0; i < n; i++) {
			xs[i] = pos.get(i)[0];
			ys[i] = pos.get(i)[1];
			zs[i] = pos.get(i)[2];
		}
		return new Blueprint("Testhaus", 5, 6, 5, xs, ys, zs, states.toArray(new BlockState[0]), 0);
	}
}
