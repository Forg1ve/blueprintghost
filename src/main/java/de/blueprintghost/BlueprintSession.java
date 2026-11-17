package de.blueprintghost;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Der aktuell geladene, platzierte Bauplan und alles, was zur Anzeige im Spiel gehört. */
public final class BlueprintSession {
	public static final byte CORRECT = 0, MISSING = 1, WRONG_BLOCK = 2, WRONG_STATE = 3;
	public static final int LAYER_ALL = Integer.MIN_VALUE;
	public static final int LAYER_AUTO = Integer.MIN_VALUE + 1;

	/** Eigenschaften, die für "richtig gebaut" zählen. Verbindungen, Wasser, Redstone-Zustand usw. werden ignoriert. */
	private static final Set<String> IMPORTANT_PROPERTIES = Set.of(
		"facing", "axis", "half", "type", "rotation", "hinge", "face", "attachment", "orientation", "hanging", "part"
	);

	/** Höchstzahl Kästen (falsche Blöcke bzw. Kasten-Modus). */
	private static final int MAX_GIZMOS = 2500;
	/** Höchstzahl gleichzeitig gezeichneter Geister-Modelle. */
	public static final int MAX_MODELS = 40000;
	/** In diesem Umkreis wird jeden Tick geprüft, damit gesetzte Blöcke sofort verschwinden. */
	private static final int NEAR_RADIUS_SQ = 12 * 12;

	public static final BlueprintSession INSTANCE = new BlueprintSession();

	// --- Zustand
	public Blueprint blueprint;
	public String source;
	public BlockPos origin = BlockPos.ZERO;
	public Rotation rotation = Rotation.NONE;
	public Mirror mirror = Mirror.NONE;
	public int layer = LAYER_ALL;
	public int radius = 160;
	public boolean visible = true;
	/** true = echte Blockmodelle, false = nur blaue Kästen. */
	public boolean modelGhosts = true;
	/** Deckkraft der Geister-Modelle (0-255). */
	public int ghostAlpha = 150;
	/** Bevorzugte Ansicht: true = Ebene für Ebene, false = ganzer Bauplan. Gilt auch für neu geladene Baupläne. */
	public boolean layerByLayer = false;
	/** Im Ebenen-Modus: true = automatisch die unterste unfertige Ebene, false = manuell gewählt. */
	public boolean layerFollowAuto = true;

	// --- Ebenen-Statistik (für HUD/Organisation)
	/** Welt-Y der aktuell gezeigten Ebene, oder Integer.MIN_VALUE wenn keine. */
	public int currentLayerY = Integer.MIN_VALUE;
	public int currentLayerRemaining, currentLayerTotal;
	private int lastAutoLayerY = Integer.MIN_VALUE;

	// --- Abgeleitete Daten
	private BlockState[] worldStates = new BlockState[0];
	private long[] worldPos = new long[0];
	private byte[] status = new byte[0];
	/** Blöcke, die rundum von deckenden Bauplan-Blöcken umschlossen sind -> nie sichtbar, nie zeichnen. */
	private java.util.BitSet interior = new java.util.BitSet();
	private Long2IntOpenHashMap lookup = new Long2IntOpenHashMap();
	public int countCorrect, countMissing, countWrong;
	/** Unterste unfertige Ebene (Welt-Y) für den Auto-Ebenen-Modus. */
	public int lowestIncompleteY = Integer.MIN_VALUE;
	private int tickCounter;
	private boolean dirty = true;
	private boolean stateLoaded;
	private int scanCursor;
	private int passCorrect, passMissing, passWrong;
	private int passLowest = Integer.MAX_VALUE;
	private long[] candidateBuffer = new long[1024];
	private int[] boxIndices = new int[0];

	// --- Anvisierter Block (für HUD)
	public BlockPos targetPos;
	public BlockState targetState;
	public byte targetStatus;

	private BlueprintSession() {
	}

	public boolean active() {
		return blueprint != null;
	}

	public void set(Blueprint blueprint, String source, BlockPos origin) {
		this.blueprint = blueprint;
		this.source = source;
		this.origin = origin;
		this.rotation = Rotation.NONE;
		this.mirror = Mirror.NONE;
		this.visible = true;
		this.lastAutoLayerY = Integer.MIN_VALUE;
		applyViewMode();
		rebuild();
	}

	public void clear() {
		blueprint = null;
		source = null;
		worldStates = new BlockState[0];
		worldPos = new long[0];
		status = new byte[0];
		lookup = new Long2IntOpenHashMap();
		boxIndices = new int[0];
		targetPos = null;
		GhostRenderer.publish(null);
		save();
	}

	public void markDirty() {
		dirty = true;
	}

	/** Setzt {@link #layer} passend zu Ansicht/Ebenen-Einstellung. */
	public void applyViewMode() {
		if (!layerByLayer) {
			layer = LAYER_ALL;
		} else if (layerFollowAuto) {
			layer = LAYER_AUTO;
		} else if (layer < 0) {
			int auto = lowestIncompleteY == Integer.MIN_VALUE ? 0 : lowestIncompleteY - origin.getY();
			layer = blueprint == null ? 0 : Math.max(0, Math.min(blueprint.sizeY - 1, auto));
		}
		dirty = true;
		save();
	}

	/** Manuell eine Ebene vor/zurück (schaltet in den manuellen Ebenen-Modus). */
	public void stepLayer(int step) {
		if (blueprint == null) {
			return;
		}
		int current = layer >= 0 ? layer
			: currentLayerY != Integer.MIN_VALUE ? currentLayerY - origin.getY()
			: step > 0 ? -1 : blueprint.sizeY;
		layerByLayer = true;
		layerFollowAuto = false;
		layer = Math.max(0, Math.min(blueprint.sizeY - 1, current + step));
		dirty = true;
		save();
	}

	/** Welt-Y der Ebene, die gerade gezeigt wird (MIN_VALUE = alle bzw. fertig). */
	public int visibleLayerY() {
		if (layer == LAYER_ALL) {
			return Integer.MIN_VALUE;
		}
		return layer == LAYER_AUTO ? lowestIncompleteY : origin.getY() + layer;
	}

	/** Weltpositionen/-zustände nach Verschieben, Drehen oder Spiegeln neu berechnen. */
	public void rebuild() {
		if (blueprint == null) {
			return;
		}
		int n = blueprint.size();
		worldStates = new BlockState[n];
		worldPos = new long[n];
		status = new byte[n];
		Arrays.fill(status, MISSING);
		lookup = new Long2IntOpenHashMap(n);
		lookup.defaultReturnValue(-1);
		int sx = blueprint.sizeX, sz = blueprint.sizeZ;
		for (int i = 0; i < n; i++) {
			int x = blueprint.xs[i], y = blueprint.ys[i], z = blueprint.zs[i];
			if (mirror == Mirror.LEFT_RIGHT) {
				z = sz - 1 - z;
			} else if (mirror == Mirror.FRONT_BACK) {
				x = sx - 1 - x;
			}
			int rx, rz;
			switch (rotation) {
				case CLOCKWISE_90 -> { rx = sz - 1 - z; rz = x; }
				case CLOCKWISE_180 -> { rx = sx - 1 - x; rz = sz - 1 - z; }
				case COUNTERCLOCKWISE_90 -> { rx = z; rz = sx - 1 - x; }
				default -> { rx = x; rz = z; }
			}
			worldPos[i] = BlockPos.asLong(origin.getX() + rx, origin.getY() + y, origin.getZ() + rz);
			worldStates[i] = blueprint.states[i].mirror(mirror).rotate(rotation);
			lookup.put(worldPos[i], i);
		}
		interior = new java.util.BitSet(n);
		Direction[] dirs = Direction.values();
		for (int i = 0; i < n; i++) {
			boolean enclosed = true;
			for (Direction d : dirs) {
				int ni = lookup.get(BlockPos.offset(worldPos[i], d));
				if (ni < 0 || !worldStates[ni].canOcclude()) {
					enclosed = false;
					break;
				}
			}
			if (enclosed) {
				interior.set(i);
			}
		}
		tickCounter = 0;
		scanCursor = 0;
		passCorrect = passMissing = passWrong = 0;
		passLowest = Integer.MAX_VALUE;
		boxIndices = new int[0];
		dirty = true;
		save();
	}

	/** Größe in Weltachsen (nach Drehung). */
	public int worldSizeX() {
		return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90 ? blueprint.sizeZ : blueprint.sizeX;
	}

	public int worldSizeZ() {
		return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90 ? blueprint.sizeX : blueprint.sizeZ;
	}

	public static byte compare(BlockState expected, BlockState actual) {
		if (actual == expected) {
			return CORRECT;
		}
		if (actual.getBlock() != expected.getBlock()) {
			return actual.isAir() || actual.canBeReplaced() ? MISSING : WRONG_BLOCK;
		}
		for (Property<?> property : expected.getProperties()) {
			if (IMPORTANT_PROPERTIES.contains(property.getName()) && actual.hasProperty(property)
				&& !actual.getValue(property).equals(expected.getValue(property))) {
				return WRONG_STATE;
			}
		}
		return CORRECT;
	}

	public boolean isLayerVisible(int worldY) {
		if (layer == LAYER_ALL) {
			return true;
		}
		if (layer == LAYER_AUTO) {
			return worldY == lowestIncompleteY;
		}
		return worldY == origin.getY() + layer;
	}

	/** Wird am Ende jedes Client-Ticks aufgerufen (innerhalb des Gizmo-Sammlers von Minecraft). */
	public void tick(Minecraft mc) {
		if (!stateLoaded && mc.level != null) {
			stateLoaded = true;
			load();
		}
		if (blueprint == null || mc.level == null || mc.player == null) {
			targetPos = null;
			GhostRenderer.publish(null);
			return;
		}
		Level level = mc.level;
		BlockPos playerPos = mc.player.blockPosition();
		int n = worldStates.length;
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

		// 1) Verteilter Komplettscan: pro Tick 1/20 aller Blöcke -> Fortschritt jede Sekunde, ohne Ruckler
		int slice = n / 20 + 1;
		for (int k = 0; k < slice && n > 0; k++) {
			int i = scanCursor;
			long packed = worldPos[i];
			mpos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
			if (level.isLoaded(mpos)) {
				status[i] = compare(worldStates[i], level.getBlockState(mpos));
			}
			byte s = status[i];
			if (s == CORRECT) {
				passCorrect++;
			} else {
				if (s == MISSING) passMissing++; else passWrong++;
				passLowest = Math.min(passLowest, mpos.getY());
			}
			if (++scanCursor >= n) {
				scanCursor = 0;
				countCorrect = passCorrect;
				countMissing = passMissing;
				countWrong = passWrong;
				lowestIncompleteY = passLowest == Integer.MAX_VALUE ? Integer.MIN_VALUE : passLowest;
				passCorrect = passMissing = passWrong = 0;
				passLowest = Integer.MAX_VALUE;
			}
		}

		// 2) Direkte Umgebung jeden Tick prüfen, damit gesetzte Blöcke sofort verschwinden
		boolean nearChanged = false;
		int px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();
		for (int i = 0; i < n; i++) {
			long packed = worldPos[i];
			int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
			int dx = x - px, dy = y - py, dz = z - pz;
			if (dx * dx + dy * dy + dz * dz <= NEAR_RADIUS_SQ) {
				mpos.set(x, y, z);
				byte s = compare(worldStates[i], level.getBlockState(mpos));
				if (s != status[i]) {
					status[i] = s;
					nearChanged = true;
				}
			}
		}

		// Ebene fertig? -> Hinweis und (im Auto-Modus) automatisch weiter
		if (layer == LAYER_AUTO && lowestIncompleteY != lastAutoLayerY) {
			if (lastAutoLayerY != Integer.MIN_VALUE && (lowestIncompleteY > lastAutoLayerY || lowestIncompleteY == Integer.MIN_VALUE)) {
				int done = lastAutoLayerY - origin.getY() + 1;
				BlueprintCommands.actionBar(lowestIncompleteY == Integer.MIN_VALUE
					? "✔ Ebene " + done + " fertig – Bauplan komplett!"
					: "✔ Ebene " + done + " fertig – weiter mit Ebene " + (lowestIncompleteY - origin.getY() + 1));
				mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.4F, 0.6F));
			}
			lastAutoLayerY = lowestIncompleteY;
			dirty = true;
		}

		// 3) Anzeige-Liste neu bauen (alle 4 Ticks oder sofort bei Änderung in der Nähe)
		if (nearChanged || dirty || tickCounter++ % 4 == 0) {
			dirty = false;
			rebuildDisplay(px, py, pz);
		}

		updateTarget(mc);
		if (!visible) {
			return;
		}

		// Kästen für falsche Blöcke (und im Kasten-Modus auch für fehlende)
		for (int i : boxIndices) {
			BlockPos pos = BlockPos.of(worldPos[i]);
			switch (status[i]) {
				case MISSING -> {
					if (!modelGhosts) {
						Gizmos.cuboid(new AABB(pos).inflate(-0.06), GizmoStyle.strokeAndFill(0xC04FC3F7, 1.5F, 0x284FC3F7));
					}
				}
				case WRONG_BLOCK -> Gizmos.cuboid(new AABB(pos).inflate(0.004), GizmoStyle.strokeAndFill(0xFFFF4040, 2.0F, 0x50FF3030));
				case WRONG_STATE -> Gizmos.cuboid(new AABB(pos).inflate(0.004), GizmoStyle.strokeAndFill(0xFFFFC107, 2.0F, 0x40FFC107));
				default -> {
				}
			}
		}

		// Umriss des ganzen Bauplans
		AABB bounds = new AABB(
			origin.getX(), origin.getY(), origin.getZ(),
			origin.getX() + worldSizeX(), origin.getY() + blueprint.sizeY, origin.getZ() + worldSizeZ());
		Gizmos.cuboid(bounds, GizmoStyle.stroke(0x90FFFFFF, 1.0F));
		if (layer != LAYER_ALL) {
			int ly = layer == LAYER_AUTO ? lowestIncompleteY : origin.getY() + layer;
			if (ly != Integer.MIN_VALUE) {
				Gizmos.cuboid(new AABB(bounds.minX, ly, bounds.minZ, bounds.maxX, ly + 1, bounds.maxZ), GizmoStyle.stroke(0xA0FFFFFF, 1.5F));
			}
		}

		// Anvisierter Block: hervorheben und beschriften
		if (targetPos != null) {
			int color = switch (targetStatus) {
				case WRONG_BLOCK -> 0xFFFF6060;
				case WRONG_STATE -> 0xFFFFD54F;
				default -> 0xFFFFFFFF;
			};
			Gizmos.cuboid(new AABB(targetPos).inflate(0.01), GizmoStyle.stroke(color, 3.0F)).setAlwaysOnTop();
			Gizmos.billboardText(targetState.getBlock().getName().getString(), Vec3.atCenterOf(targetPos).add(0, 0.85, 0),
				TextGizmo.Style.forColorAndCentered(color).withScale(0.45F)).setAlwaysOnTop();
			String hint = BlockText.orientationHint(targetState);
			if (!hint.isEmpty()) {
				Gizmos.billboardText(hint, Vec3.atCenterOf(targetPos).add(0, 0.6, 0),
					TextGizmo.Style.forColorAndCentered(0xFFDDDDDD).withScale(0.3F)).setAlwaysOnTop();
			}
		}
	}

	/** Sucht die nächsten unfertigen Blöcke im Radius und erzeugt Snapshot (Modelle) + Kasten-Liste. */
	private void rebuildDisplay(int px, int py, int pz) {
		int n = worldStates.length;
		long r2 = (long) radius * radius;
		long[] keys = candidateBuffer;
		int count = 0;
		int layerY = visibleLayerY();
		int layerRemaining = 0, layerTotal = 0;
		if (layerY != Integer.MIN_VALUE) {
			for (int i = 0; i < n; i++) {
				if (BlockPos.getY(worldPos[i]) == layerY) {
					layerTotal++;
					if (status[i] != CORRECT) {
						layerRemaining++;
					}
				}
			}
		}
		currentLayerY = layerY;
		currentLayerRemaining = layerRemaining;
		currentLayerTotal = layerTotal;
		if (visible) {
			for (int i = 0; i < n; i++) {
				if (status[i] == CORRECT) {
					continue;
				}
				long packed = worldPos[i];
				int y = BlockPos.getY(packed);
				// Eingeschlossene Blöcke sind unsichtbar - außer im Ebenen-Modus, dort ist die Ebene "aufgeschnitten"
				if (layer == LAYER_ALL && interior.get(i) && status[i] == MISSING) {
					continue;
				}
				if (!isLayerVisible(y)) {
					continue;
				}
				long dx = BlockPos.getX(packed) - px, dy = y - py, dz = BlockPos.getZ(packed) - pz;
				long d2 = dx * dx + dy * dy + dz * dz;
				if (d2 > r2) {
					continue;
				}
				if (count == keys.length) {
					keys = Arrays.copyOf(keys, keys.length * 2);
				}
				// Distanz in die oberen Bits, Index in die unteren -> sortierbar
				keys[count++] = (Math.min(d2, 0x7FFFFFFFL) << 32) | i;
			}
		}
		candidateBuffer = keys;
		Arrays.sort(keys, 0, count);

		// Kästen: falsche Blöcke (+ fehlende im Kasten-Modus), die nächsten zuerst
		int[] boxes = new int[Math.min(count, MAX_GIZMOS)];
		int b = 0;
		for (int c = 0; c < count && b < boxes.length; c++) {
			int i = (int) (keys[c] & 0xFFFFFFFFL);
			if (status[i] != MISSING || !modelGhosts) {
				boxes[b++] = i;
			}
		}
		boxIndices = Arrays.copyOf(boxes, b);

		if (!modelGhosts || !visible) {
			GhostRenderer.publish(null);
			return;
		}

		// Modelle: die nächsten MAX_MODELS fehlenden Blöcke
		int[] models = new int[Math.min(count, MAX_MODELS)];
		int m = 0;
		for (int c = 0; c < count && m < models.length; c++) {
			int i = (int) (keys[c] & 0xFFFFFFFFL);
			if (status[i] == MISSING) {
				models[m++] = i;
			}
		}
		LongOpenHashSet shown = new LongOpenHashSet(Math.max(16, m * 2));
		for (int k = 0; k < m; k++) {
			shown.add(worldPos[models[k]]);
		}
		// Von hinten nach vorne zeichnen -> Transparenz sieht sauberer aus
		long[] pos = new long[m];
		BlockState[] st = new BlockState[m];
		byte[] hidden = new byte[m];
		Direction[] dirs = Direction.values();
		for (int k = 0; k < m; k++) {
			int i = models[m - 1 - k];
			long p = worldPos[i];
			pos[k] = p;
			st[k] = worldStates[i];
			if (worldStates[i].canOcclude() && layer == LAYER_ALL) {
				// Innenflächen zwischen zwei Geister-Vollblöcken weglassen
				byte mask = 0;
				for (int d = 0; d < 6; d++) {
					long neighbour = BlockPos.offset(p, dirs[d]);
					if (shown.contains(neighbour)) {
						int ni = lookup.get(neighbour);
						if (ni >= 0 && worldStates[ni].canOcclude()) {
							mask |= (byte) (1 << d);
						}
					}
				}
				hidden[k] = mask;
			}
		}
		GhostRenderer.publish(new GhostRenderer.Snapshot(pos, st, hidden, ghostAlpha));
	}

	/** Sucht entlang der Blickrichtung den ersten Bauplan-Block, der noch nicht stimmt. */
	private void updateTarget(Minecraft mc) {
		targetPos = null;
		if (!visible || mc.player == null || mc.level == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1.0F);
		double reach = 7.0;
		BlockPos last = null;
		for (double t = 0; t <= reach; t += 0.05) {
			BlockPos pos = BlockPos.containing(eye.add(look.scale(t)));
			if (pos.equals(last)) {
				continue;
			}
			last = pos;
			BlockState world = mc.level.getBlockState(pos);
			int idx = lookup.get(pos.asLong());
			if (idx >= 0 && isLayerVisible(pos.getY())) {
				byte s = compare(worldStates[idx], world);
				if (s != CORRECT) {
					targetPos = pos;
					targetState = worldStates[idx];
					targetStatus = s;
					return;
				}
			}
			if (!world.isAir() && !world.canBeReplaced()) {
				return; // Sicht blockiert
			}
		}
	}

	public BlockState[] worldStates() {
		return worldStates;
	}

	public byte[] status() {
		return status;
	}

	public long[] worldPositions() {
		return worldPos;
	}

	// ------------------------------------------------------------ Speichern / Laden

	private static Path stateFile() {
		return BlueprintFiles.folder().resolve("state.properties");
	}

	public void save() {
		Properties p = new Properties();
		p.setProperty("viewRadius", Integer.toString(radius));
		p.setProperty("style", modelGhosts ? "model" : "box");
		p.setProperty("alpha", Integer.toString(ghostAlpha));
		p.setProperty("view", layerByLayer ? "layer" : "whole");
		p.setProperty("layerFollow", layerFollowAuto ? "auto" : "manual");
		if (layer >= 0) {
			p.setProperty("manualLayer", Integer.toString(layer));
		}
		if (blueprint != null && source != null) {
			p.setProperty("source", source);
			p.setProperty("x", Integer.toString(origin.getX()));
			p.setProperty("y", Integer.toString(origin.getY()));
			p.setProperty("z", Integer.toString(origin.getZ()));
			p.setProperty("rotation", rotation.name());
			p.setProperty("mirror", mirror.name());
		}
		try (Writer w = Files.newBufferedWriter(stateFile())) {
			p.store(w, "BlueprintGhost");
		} catch (IOException ignored) {
		}
	}

	private void load() {
		Path file = stateFile();
		if (!Files.isRegularFile(file)) {
			return;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file)) {
			p.load(r);
		} catch (IOException e) {
			return;
		}
		try {
			radius = Integer.parseInt(p.getProperty("viewRadius", "160"));
			modelGhosts = !"box".equals(p.getProperty("style"));
			ghostAlpha = Integer.parseInt(p.getProperty("alpha", "150"));
			layerByLayer = "layer".equals(p.getProperty("view"));
			layerFollowAuto = !"manual".equals(p.getProperty("layerFollow"));
			int manual = Integer.parseInt(p.getProperty("manualLayer", "-1"));
			layer = !layerByLayer ? LAYER_ALL : layerFollowAuto || manual < 0 ? LAYER_AUTO : manual;
		} catch (NumberFormatException ignored) {
		}
		String src = p.getProperty("source");
		if (src == null) {
			return;
		}
		try {
			Path path = BlueprintFiles.resolveLocalOnly(src);
			Blueprint bp = SchematicReader.read(path);
			this.blueprint = bp;
			this.source = src;
			this.origin = new BlockPos(Integer.parseInt(p.getProperty("x", "0")), Integer.parseInt(p.getProperty("y", "0")), Integer.parseInt(p.getProperty("z", "0")));
			this.rotation = Rotation.valueOf(p.getProperty("rotation", "NONE"));
			this.mirror = Mirror.valueOf(p.getProperty("mirror", "NONE"));
			if (layer >= bp.sizeY) {
				layer = bp.sizeY - 1;
			}
			rebuild();
		} catch (Exception ignored) {
			// Datei verschwunden o.ä. – einfach nichts laden
		}
	}
}
