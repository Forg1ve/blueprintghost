package de.blueprintghost;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Liest die gängigen Bauplan-Formate. Das Format wird am Inhalt erkannt, nicht an der Dateiendung:
 * <ul>
 *   <li>Litematica (.litematic)</li>
 *   <li>Sponge Schematic v1/v2/v3 (.schem, WorldEdit/FAWE)</li>
 *   <li>Vanilla-Strukturdatei (.nbt, Konstruktionsblock)</li>
 * </ul>
 */
public final class SchematicReader {
	private static final long MAX_VOLUME = 64L * 1024 * 1024;

	private SchematicReader() {
	}

	public static Blueprint read(Path file) throws IOException {
		CompoundTag root = readNbt(file);
		String name = stripExtension(file.getFileName().toString());

		if (root.contains("Regions")) {
			return readLitematic(name, root);
		}
		if (root.contains("Schematic")) { // Sponge v3: alles steckt in "Schematic"
			return readSpongeV3(name, root.getCompoundOrEmpty("Schematic"));
		}
		if (root.contains("Blocks") && root.getCompoundOrEmpty("Blocks").contains("Palette")) { // v3 ohne Wrapper
			return readSpongeV3(name, root);
		}
		if (root.contains("Palette") && root.contains("BlockData")) {
			return readSpongeV2(name, root);
		}
		if (root.contains("blocks") && (root.contains("palette") || root.contains("palettes"))) {
			return readStructure(name, root);
		}
		if (root.contains("Blocks") && root.contains("Data")) {
			throw new IOException("Altes MCEdit-Format (vor 1.13) wird nicht unterstützt. Bitte in WorldEdit als .schem neu speichern.");
		}
		throw new IOException("Unbekanntes Bauplan-Format.");
	}

	private static CompoundTag readNbt(Path file) throws IOException {
		try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
			raw.mark(2);
			int b1 = raw.read();
			int b2 = raw.read();
			raw.reset();
			boolean gzip = b1 == 0x1f && b2 == 0x8b;
			if (gzip) {
				return NbtIo.readCompressed(raw, NbtAccounter.create(512L * 1024 * 1024));
			}
			return NbtIo.read(new DataInputStream(raw), NbtAccounter.create(512L * 1024 * 1024));
		}
	}

	// ---------------------------------------------------------------- Litematica

	private static Blueprint readLitematic(String fallbackName, CompoundTag root) throws IOException {
		String name = root.getCompoundOrEmpty("Metadata").getStringOr("Name", fallbackName);
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		Builder builder = new Builder();

		for (String regionName : regions.keySet()) {
			CompoundTag region = regions.getCompoundOrEmpty(regionName);
			CompoundTag pos = region.getCompoundOrEmpty("Position");
			CompoundTag size = region.getCompoundOrEmpty("Size");
			int px = pos.getIntOr("x", 0), py = pos.getIntOr("y", 0), pz = pos.getIntOr("z", 0);
			int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
			// Negative Größen: Region erstreckt sich in die negative Richtung
			int minX = px + (sx < 0 ? sx + 1 : 0);
			int minY = py + (sy < 0 ? sy + 1 : 0);
			int minZ = pz + (sz < 0 ? sz + 1 : 0);
			int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
			checkVolume((long) ax * ay * az);

			ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
			BlockState[] palette = new BlockState[paletteTag.size()];
			for (int i = 0; i < palette.length; i++) {
				palette[i] = builder.readState(paletteTag.getCompoundOrEmpty(i));
			}
			long[] data = region.getLongArray("BlockStates").orElse(new long[0]);
			if (palette.length == 0 || data.length == 0) {
				continue;
			}
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
			long mask = (1L << bits) - 1L;
			long volume = (long) ax * ay * az;

			for (long index = 0; index < volume; index++) {
				long startOffset = index * bits;
				int startArr = (int) (startOffset >>> 6);
				int endArr = (int) (((index + 1) * bits - 1) >>> 6);
				int startBit = (int) (startOffset & 63);
				if (endArr >= data.length) {
					break;
				}
				int id;
				if (startArr == endArr) {
					id = (int) (data[startArr] >>> startBit & mask);
				} else {
					int endOffset = 64 - startBit;
					id = (int) ((data[startArr] >>> startBit | data[endArr] << endOffset) & mask);
				}
				if (id >= palette.length) {
					continue;
				}
				int x = (int) (index % ax);
				int z = (int) ((index / ax) % az);
				int y = (int) (index / ((long) ax * az));
				builder.add(minX + x, minY + y, minZ + z, palette[id]);
			}
		}
		return builder.build(name);
	}

	// ---------------------------------------------------------------- Sponge / WorldEdit

	private static Blueprint readSpongeV2(String name, CompoundTag root) throws IOException {
		int w = root.getShortOr("Width", (short) 0) & 0xFFFF;
		int h = root.getShortOr("Height", (short) 0) & 0xFFFF;
		int l = root.getShortOr("Length", (short) 0) & 0xFFFF;
		return readSpongeBlocks(name, w, h, l, root.getCompoundOrEmpty("Palette"), root.getByteArray("BlockData").orElse(new byte[0]));
	}

	private static Blueprint readSpongeV3(String name, CompoundTag schem) throws IOException {
		int w = schem.getShortOr("Width", (short) 0) & 0xFFFF;
		int h = schem.getShortOr("Height", (short) 0) & 0xFFFF;
		int l = schem.getShortOr("Length", (short) 0) & 0xFFFF;
		CompoundTag blocks = schem.getCompoundOrEmpty("Blocks");
		String metaName = schem.getCompoundOrEmpty("Metadata").getStringOr("Name", name);
		return readSpongeBlocks(metaName, w, h, l, blocks.getCompoundOrEmpty("Palette"), blocks.getByteArray("Data").orElse(new byte[0]));
	}

	private static Blueprint readSpongeBlocks(String name, int w, int h, int l, CompoundTag paletteTag, byte[] data) throws IOException {
		checkVolume((long) w * h * l);
		Builder builder = new Builder();
		Map<Integer, BlockState> palette = new HashMap<>();
		for (String key : paletteTag.keySet()) {
			palette.put(paletteTag.getIntOr(key, -1), builder.parseState(key));
		}

		int index = 0;
		int i = 0;
		long volume = (long) w * h * l;
		while (i < data.length && index < volume) {
			// VarInt
			int value = 0;
			int shift = 0;
			byte b;
			do {
				b = data[i++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && i < data.length);

			BlockState state = palette.get(value);
			if (state != null) {
				int y = index / (w * l);
				int rest = index % (w * l);
				int z = rest / w;
				int x = rest % w;
				builder.add(x, y, z, state);
			}
			index++;
		}
		return builder.build(name);
	}

	// ---------------------------------------------------------------- Vanilla .nbt

	private static Blueprint readStructure(String name, CompoundTag root) throws IOException {
		Builder builder = new Builder();
		ListTag paletteTag = root.contains("palette")
			? root.getListOrEmpty("palette")
			: root.getListOrEmpty("palettes").getListOrEmpty(0);
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int i = 0; i < palette.length; i++) {
			palette[i] = builder.readState(paletteTag.getCompoundOrEmpty(i));
		}
		ListTag blocks = root.getListOrEmpty("blocks");
		checkVolume(blocks.size());
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag block = blocks.getCompoundOrEmpty(i);
			ListTag pos = block.getListOrEmpty("pos");
			int stateId = block.getIntOr("state", -1);
			if (stateId < 0 || stateId >= palette.length || pos.size() < 3) {
				continue;
			}
			builder.add(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0), palette[stateId]);
		}
		return builder.build(name);
	}

	// ---------------------------------------------------------------- Hilfen

	private static void checkVolume(long volume) throws IOException {
		if (volume > MAX_VOLUME) {
			throw new IOException("Bauplan ist zu groß (" + volume + " Blöcke).");
		}
	}

	private static String stripExtension(String file) {
		int dot = file.lastIndexOf('.');
		return dot > 0 ? file.substring(0, dot) : file;
	}

	/** Sammelt Blöcke, normalisiert auf die minimale Ecke und filtert Luft. */
	private static final class Builder {
		private final List<int[]> positions = new ArrayList<>();
		private final List<BlockState> states = new ArrayList<>();
		private final Map<String, BlockState> parseCache = new HashMap<>();
		private int unknown;

		BlockState readState(CompoundTag tag) {
			try {
				BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag);
				// readBlockState liefert bei unbekanntem Namen Luft
				if (state.isAir() && !tag.getStringOr("Name", "").contains("air")) {
					unknown++;
					return null;
				}
				return state;
			} catch (Exception e) {
				unknown++;
				return null;
			}
		}

		BlockState parseState(String text) {
			return parseCache.computeIfAbsent(text, t -> {
				try {
					return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, t, false).blockState();
				} catch (CommandSyntaxException e) {
					// Unbekannte Eigenschaften? Nur den Blocknamen versuchen.
					int bracket = t.indexOf('[');
					if (bracket > 0) {
						try {
							return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, t.substring(0, bracket), false).blockState();
						} catch (CommandSyntaxException ignored) {
						}
					}
					unknown++;
					return null;
				}
			});
		}

		void add(int x, int y, int z, BlockState state) {
			if (state == null || state.isAir() || state.is(Blocks.STRUCTURE_VOID)) {
				return;
			}
			positions.add(new int[]{x, y, z});
			states.add(state);
		}

		Blueprint build(String name) throws IOException {
			if (states.isEmpty()) {
				throw new IOException("Der Bauplan enthält keine (bekannten) Blöcke.");
			}
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (int[] p : positions) {
				minX = Math.min(minX, p[0]);
				minY = Math.min(minY, p[1]);
				minZ = Math.min(minZ, p[2]);
				maxX = Math.max(maxX, p[0]);
				maxY = Math.max(maxY, p[1]);
				maxZ = Math.max(maxZ, p[2]);
			}
			int n = positions.size();
			int[] xs = new int[n], ys = new int[n], zs = new int[n];
			for (int i = 0; i < n; i++) {
				int[] p = positions.get(i);
				xs[i] = p[0] - minX;
				ys[i] = p[1] - minY;
				zs[i] = p[2] - minZ;
			}
			return new Blueprint(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, xs, ys, zs,
				states.toArray(new BlockState[0]), unknown);
		}
	}
}
