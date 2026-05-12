package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.Super_lead;
import com.zhongbai233.super_lead.tuning.ClientTuning;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;

@EventBusSubscriber(modid = Super_lead.MODID, value = Dist.CLIENT)
public final class RopeSectionMeshDriver {

    private static final Identifier WHITE_SPRITE_ID =
            Identifier.withDefaultNamespace("block/lightning_rod_on");

    private RopeSectionMeshDriver() {}

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        if (!ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get()) return;

        BlockPos origin = event.getSectionOrigin();
        long key = SectionPos.asLong(origin);
        List<RopeSectionSnapshot> snaps = StaticRopeChunkRegistry.get().snapshotsFor(key);
        if (snaps.isEmpty()) return;

        TextureAtlasSprite sprite = whiteSprite();
        if (sprite == null) return;

        final int ox = origin.getX();
        final int oy = origin.getY();
        final int oz = origin.getZ();
        final float u = (sprite.getU0() + sprite.getU1()) * 0.5f;
        final float v = (sprite.getV0() + sprite.getV1()) * 0.5f;
        final List<RopeSectionSnapshot> capturedSnaps = snaps;

        event.addRenderer(ctx -> {
            VertexConsumer vc = ctx.getOrCreateChunkBuffer(ChunkSectionLayer.SOLID);
            for (RopeSectionSnapshot s : capturedSnaps) {
                emit(vc, s, ox, oy, oz, u, v);
            }
        });
    }

    private static TextureAtlasSprite whiteSprite() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        try {
            TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            return atlas.getSprite(WHITE_SPRITE_ID);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void emit(VertexConsumer vc, RopeSectionSnapshot s,
                              int ox, int oy, int oz, float u, float v) {
        int last = s.nodeCount - 1;
        final float NX = 0f, NY = 1f, NZ = 0f;
        int start = Math.max(0, Math.min(s.segmentStart, last));
        int end = Math.max(start, Math.min(s.segmentEndExclusive, last));
        for (int i = start; i < end; i++) {
            int j = i + 1;
            float sxA = s.x[i] - ox, syA = s.y[i] - oy, szA = s.z[i] - oz;
            float sxB = s.x[j] - ox, syB = s.y[j] - oy, szB = s.z[j] - oz;

            float aPx = sxA + s.sx[i] + s.ux[i], aPy = syA + s.sy[i] + s.uy[i], aPz = szA + s.sz[i] + s.uz[i];
            float aQx = sxA + s.sx[i] - s.ux[i], aQy = syA + s.sy[i] - s.uy[i], aQz = szA + s.sz[i] - s.uz[i];
            float aRx = sxA - s.sx[i] - s.ux[i], aRy = syA - s.sy[i] - s.uy[i], aRz = szA - s.sz[i] - s.uz[i];
            float aSx = sxA - s.sx[i] + s.ux[i], aSy = syA - s.sy[i] + s.uy[i], aSz = szA - s.sz[i] + s.uz[i];

            float bPx = sxB + s.sx[j] + s.ux[j], bPy = syB + s.sy[j] + s.uy[j], bPz = szB + s.sz[j] + s.uz[j];
            float bQx = sxB + s.sx[j] - s.ux[j], bQy = syB + s.sy[j] - s.uy[j], bQz = szB + s.sz[j] - s.uz[j];
            float bRx = sxB - s.sx[j] - s.ux[j], bRy = syB - s.sy[j] - s.uy[j], bRz = szB - s.sz[j] - s.uz[j];
            float bSx = sxB - s.sx[j] + s.ux[j], bSy = syB - s.sy[j] + s.uy[j], bSz = szB - s.sz[j] + s.uz[j];

            int colorBase = i * 4;
            int lightA = s.nodeLight[i];
            int lightB = s.nodeLight[j];

            // face 0 (+side)
            quad(vc, aPx, aPy, aPz, lightA, bPx, bPy, bPz, lightB, bQx, bQy, bQz, lightB, aQx, aQy, aQz, lightA,
                    s.segmentColorARGB[colorBase    ], u, v, NX, NY, NZ);
            // face 1 (-up)
            quad(vc, aQx, aQy, aQz, lightA, bQx, bQy, bQz, lightB, bRx, bRy, bRz, lightB, aRx, aRy, aRz, lightA,
                    s.segmentColorARGB[colorBase + 1], u, v, NX, NY, NZ);
            // face 2 (-side)
            quad(vc, aRx, aRy, aRz, lightA, bRx, bRy, bRz, lightB, bSx, bSy, bSz, lightB, aSx, aSy, aSz, lightA,
                    s.segmentColorARGB[colorBase + 2], u, v, NX, NY, NZ);
            // face 3 (+up)
            quad(vc, aSx, aSy, aSz, lightA, bSx, bSy, bSz, lightB, bPx, bPy, bPz, lightB, aPx, aPy, aPz, lightA,
                    s.segmentColorARGB[colorBase + 3], u, v, NX, NY, NZ);
        }
    }

    private static void quad(VertexConsumer vc,
                              float x0, float y0, float z0, int l0,
                              float x1, float y1, float z1, int l1,
                              float x2, float y2, float z2, int l2,
                              float x3, float y3, float z3, int l3,
                              int color, float u, float v,
                              float nx, float ny, float nz) {
        // Reversed winding (3,2,1,0) so the outward-facing normal matches Minecraft's CCW
        // front-face convention; previously the inward faces were the visible ones.
        vertex(vc, x3, y3, z3, color, u, v, l3, nx, ny, nz);
        vertex(vc, x2, y2, z2, color, u, v, l2, nx, ny, nz);
        vertex(vc, x1, y1, z1, color, u, v, l1, nx, ny, nz);
        vertex(vc, x0, y0, z0, color, u, v, l0, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc, float x, float y, float z, int color,
                                float u, float v, int light, float nx, float ny, float nz) {
        vc.addVertex(x, y, z)
                .setColor(color)
                .setUv(u, v)
                .setLight(light)
                .setNormal(nx, ny, nz);
    }
}
