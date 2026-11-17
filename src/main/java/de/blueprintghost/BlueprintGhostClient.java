package de.blueprintghost;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class BlueprintGhostClient implements ClientModInitializer {
	public static final String MOD_ID = "blueprintghost";

	private static KeyMapping toggleKey;
	private static KeyMapping layerUpKey;
	private static KeyMapping layerDownKey;
	private static KeyMapping layerAutoKey;
	private static KeyMapping settingsKey;

	/** Alle Tasten dieser Mod, in der Reihenfolge fürs Menü. */
	public static java.util.List<KeyMapping> keys() {
		return java.util.List.of(settingsKey, toggleKey, layerUpKey, layerDownKey, layerAutoKey);
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blueprintghost.toggle", InputConstants.KEY_N, category));
		layerUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blueprintghost.layer_up", InputConstants.KEY_PAGEUP, category));
		layerDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blueprintghost.layer_down", InputConstants.KEY_PAGEDOWN, category));
		layerAutoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blueprintghost.layer_auto", InputConstants.KEY_HOME, category));

		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.blueprintghost.settings", InputConstants.KEY_K, category));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> BlueprintCommands.register(dispatcher));

		// END_CLIENT_TICK läuft innerhalb von Minecrafts Gizmo-Sammler für Ticks -> Gizmos bleiben bis zum nächsten Tick sichtbar
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			BlueprintSession session = BlueprintSession.INSTANCE;
			while (toggleKey.consumeClick()) {
				if (session.active()) {
					BlueprintCommands.toggle();
					BlueprintCommands.actionBar(session.visible ? "Bauplan eingeblendet" : "Bauplan ausgeblendet");
				}
			}
			while (layerUpKey.consumeClick()) {
				BlueprintCommands.stepLayer(1);
			}
			while (layerDownKey.consumeClick()) {
				BlueprintCommands.stepLayer(-1);
			}
			while (layerAutoKey.consumeClick()) {
				if (session.active()) {
					// Pos1: zwischen "ganzer Bauplan" und "Ebene für Ebene (automatisch)" wechseln
					boolean toLayers = session.layer == BlueprintSession.LAYER_ALL;
					session.layerByLayer = toLayers;
					session.layerFollowAuto = true;
					session.applyViewMode();
					BlueprintCommands.actionBar(toLayers ? "Ansicht: Ebene für Ebene (automatisch)" : "Ansicht: ganzer Bauplan");
				}
			}
			while (settingsKey.consumeClick()) {
				if (mc.gui.screen() == null) {
					mc.gui.setScreen(new BlueprintSettingsScreen(null));
				}
			}
			session.tick(mc);
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(GhostRenderer::render);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), BlueprintHud::render);
	}
}
