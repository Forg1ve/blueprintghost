package de.blueprintghost;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Ein geladener Bauplan. Enthält nur Nicht-Luft-Blöcke, Koordinaten relativ zur
 * minimalen Ecke (0..size-1).
 */
public final class Blueprint {
	public final String name;
	public final int sizeX, sizeY, sizeZ;
	public final int[] xs, ys, zs;
	public final BlockState[] states;
	/** Anzahl Blockzustände, die nicht erkannt wurden (z.B. Mod-Blöcke, sehr alte Namen). */
	public final int unknownStates;

	public Blueprint(String name, int sizeX, int sizeY, int sizeZ, int[] xs, int[] ys, int[] zs, BlockState[] states, int unknownStates) {
		this.name = name;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.xs = xs;
		this.ys = ys;
		this.zs = zs;
		this.states = states;
		this.unknownStates = unknownStates;
	}

	public int size() {
		return states.length;
	}
}
