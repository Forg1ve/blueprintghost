package de.blueprintghost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Lesbare (deutsche) Hinweise zur Ausrichtung eines Blocks. */
public final class BlockText {
	private static final Map<String, String> NAMES = Map.ofEntries(
		Map.entry("facing", "Richtung"),
		Map.entry("axis", "Achse"),
		Map.entry("half", "Hälfte"),
		Map.entry("type", "Typ"),
		Map.entry("hinge", "Scharnier"),
		Map.entry("face", "Seite"),
		Map.entry("rotation", "Drehung"),
		Map.entry("attachment", "Befestigung"),
		Map.entry("hanging", "hängend")
	);
	private static final Map<String, String> VALUES = Map.ofEntries(
		Map.entry("north", "Norden"),
		Map.entry("south", "Süden"),
		Map.entry("east", "Osten"),
		Map.entry("west", "Westen"),
		Map.entry("up", "oben"),
		Map.entry("down", "unten"),
		Map.entry("top", "oben"),
		Map.entry("bottom", "unten"),
		Map.entry("upper", "oben"),
		Map.entry("lower", "unten"),
		Map.entry("double", "doppelt"),
		Map.entry("left", "links"),
		Map.entry("right", "rechts"),
		Map.entry("floor", "Boden"),
		Map.entry("ceiling", "Decke"),
		Map.entry("wall", "Wand"),
		Map.entry("true", "ja"),
		Map.entry("false", "nein")
	);

	private BlockText() {
	}

	public static String orientationHint(BlockState state) {
		List<String> parts = new ArrayList<>();
		for (Property<?> property : state.getProperties()) {
			String name = NAMES.get(property.getName());
			if (name == null) {
				continue;
			}
			String value = String.valueOf(state.getValue(property));
			if (property.getName().equals("type") && (value.equals("single"))) {
				continue;
			}
			parts.add(name + ": " + VALUES.getOrDefault(value, value));
		}
		return String.join(", ", parts);
	}
}
