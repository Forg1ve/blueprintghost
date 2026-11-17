package de.blueprintghost;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Zeichnet fehlende Blöcke als echte, halbtransparente Blockmodelle.
 * Die Liste der zu zeichnenden Blöcke wird im Client-Tick als unveränderlicher Snapshot gebaut
 * (Rendering kann auf einem anderen Thread laufen).
 */
public final class GhostRenderer {
	private static final Direction[] DIRECTIONS = Direction.values();
	/** Schrumpfung, damit Geisterflächen nicht mit echten Nachbarblöcken flackern. */
	private static final float SCALE = 0.96F;
	private static final float INSET = (1.0F - SCALE) / 2.0F;
	/** Farbe für eingefärbte Flächen (Gras, Laub), da die Biomfarbe beim Geist nicht bekannt ist. */
	private static final int FOLIAGE_TINT = 0x5FA03B;

	/** Unveränderliche Daten für einen Frame. */
	public record Snapshot(long[] positions, BlockState[] states, byte[] hiddenFaces, int alpha) {
	}

	private static volatile Snapshot snapshot;
	private static final Map<BlockState, QuadSet> QUAD_CACHE = new ConcurrentHashMap<>();
	private static volatile BlockStateModelSet cachedFor;

	private GhostRenderer() {
	}

	public static int debugCount() {
		Snapshot s = snapshot;
		return s == null ? -1 : s.positions.length;
	}

	public static void publish(Snapshot s) {
		snapshot = s;
	}

	public static void render(LevelRenderContext context) {
		Snapshot s = snapshot;
		if (s == null || s.positions.length == 0) {
			return;
		}
		Vec3 cam = context.levelState().cameraRenderState.pos;
		double camX = cam.x, camY = cam.y, camZ = cam.z;
		RenderType type = RenderTypes.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS);
		int alpha = s.alpha;

		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), type, (pose, buffer) -> {
			for (int i = 0; i < s.positions.length; i++) {
				long p = s.positions[i];
				float bx = (float) (BlockPos.getX(p) - camX);
				float by = (float) (BlockPos.getY(p) - camY);
				float bz = (float) (BlockPos.getZ(p) - camZ);
				QuadSet quads = quadsFor(s.states[i]);
				int hidden = s.hiddenFaces[i];
				for (int d = 0; d < 6; d++) {
					if ((hidden & (1 << d)) != 0) {
						continue;
					}
					emit(buffer, quads.byFace[d], bx, by, bz, alpha);
				}
				emit(buffer, quads.unculled, bx, by, bz, alpha);
			}
		});
	}

	private static void emit(VertexConsumer buffer, List<BakedQuad> quads, float bx, float by, float bz, int alpha) {
		for (int q = 0; q < quads.size(); q++) {
			BakedQuad quad = quads.get(q);
			Vector3fc n = quad.direction().getUnitVec3f();
			int rgb = quad.materialInfo().tintIndex() >= 0 ? FOLIAGE_TINT : 0xFFFFFF;
			int color = (alpha << 24) | rgb;
			for (int v = 0; v < 4; v++) {
				Vector3fc pos = quad.position(v);
				long uv = quad.packedUV(v);
				buffer.addVertex(
					bx + INSET + pos.x() * SCALE, by + INSET + pos.y() * SCALE, bz + INSET + pos.z() * SCALE,
					color, UVPair.unpackU(uv), UVPair.unpackV(uv),
					OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, n.x(), n.y(), n.z());
			}
		}
	}

	private static QuadSet quadsFor(BlockState state) {
		BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
		if (models != cachedFor) {
			QUAD_CACHE.clear(); // Ressourcenpaket gewechselt
			cachedFor = models;
		}
		return QUAD_CACHE.computeIfAbsent(state, st -> {
			List<BlockStateModelPart> parts = new ArrayList<>();
			models.get(st).collectParts(RandomSource.create(42L), parts);
			QuadSet set = new QuadSet();
			for (BlockStateModelPart part : parts) {
				for (int d = 0; d < 6; d++) {
					set.byFace[d].addAll(part.getQuads(DIRECTIONS[d]));
				}
				set.unculled.addAll(part.getQuads(null));
			}
			return set;
		});
	}

	private static final class QuadSet {
		@SuppressWarnings("unchecked")
		final List<BakedQuad>[] byFace = new List[6];
		final List<BakedQuad> unculled = new ArrayList<>();

		QuadSet() {
			for (int i = 0; i < 6; i++) {
				byFace[i] = new ArrayList<>();
			}
		}
	}
}
