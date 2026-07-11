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

    /*
     * Static ropes are vertex-colored, but the chunk solid buffer still requires
     * UVs. This neutral atlas sprite is only used as a UV source.
     */
    private static final Identifier NEUTRAL_UV_SPRITE_ID = Identifier.withDefaultNamespace("block/white_stained_glass");
    private static final float ATTACHMENT_LINE_HALF_THICKNESS = 0.012F;
    private static volatile long debugCallbacks;
    private static volatile long debugHits;

    private RopeSectionMeshDriver() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        debugCallbacks++;
        if (!ClientTuning.MODE_CHUNK_MESH_STATIC_ROPES.get())
            return;

        BlockPos origin = event.getSectionOrigin();
        StaticRopeChunkRegistry registry = StaticRopeChunkRegistry.get();
        long key = SectionPos.asLong(origin);
        List<RopeSectionSnapshot> snaps = registry.snapshotsFor(key);
        if (snaps.isEmpty()) {
            long directKey = SectionPos.asLong(origin.getX(), origin.getY(), origin.getZ());
            List<RopeSectionSnapshot> directSnaps = registry.snapshotsFor(directKey);
            if (!directSnaps.isEmpty()) {
                key = directKey;
                snaps = directSnaps;
            }
        }
        if (snaps.isEmpty())
            return;
        debugHits++;
        TextureAtlasSprite sprite = neutralSprite();
        if (sprite == null)
            return;

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
        StaticRopeChunkRegistry.get().markSectionMeshAccepted(key);
    }

    public static long debugCallbacks() {
        return debugCallbacks;
    }

    public static long debugHits() {
        return debugHits;
    }

    private static TextureAtlasSprite neutralSprite() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null)
            return null;
        try {
            TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            return atlas.getSprite(NEUTRAL_UV_SPRITE_ID);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void emit(VertexConsumer vc, RopeSectionSnapshot s,
            float ox, float oy, float oz, float u, float v) {
        int last = s.nodeCount - 1;
        int start = Math.max(0, Math.min(s.segmentStart, last));
        int end = Math.max(start, Math.min(s.segmentEndExclusive, last));
        for (int i = start; i < end; i++) {
            int j = i + 1;
            float sxA = s.x[i] - ox, syA = s.y[i] - oy, szA = s.z[i] - oz;
            float sxB = s.x[j] - ox, syB = s.y[j] - oy, szB = s.z[j] - oz;
            float scaleA = nodeScale(s, i);
            float scaleB = nodeScale(s, j);
            float sideAx = s.sx[i] * scaleA, sideAy = s.sy[i] * scaleA, sideAz = s.sz[i] * scaleA;
            float upAx = s.ux[i] * scaleA, upAy = s.uy[i] * scaleA, upAz = s.uz[i] * scaleA;
            float sideBx = s.sx[j] * scaleB, sideBy = s.sy[j] * scaleB, sideBz = s.sz[j] * scaleB;
            float upBx = s.ux[j] * scaleB, upBy = s.uy[j] * scaleB, upBz = s.uz[j] * scaleB;

            float aPx = sxA + sideAx + upAx, aPy = syA + sideAy + upAy, aPz = szA + sideAz + upAz;
            float aQx = sxA + sideAx - upAx, aQy = syA + sideAy - upAy, aQz = szA + sideAz - upAz;
            float aRx = sxA - sideAx - upAx, aRy = syA - sideAy - upAy, aRz = szA - sideAz - upAz;
            float aSx = sxA - sideAx + upAx, aSy = syA - sideAy + upAy, aSz = szA - sideAz + upAz;

            float bPx = sxB + sideBx + upBx, bPy = syB + sideBy + upBy, bPz = szB + sideBz + upBz;
            float bQx = sxB + sideBx - upBx, bQy = syB + sideBy - upBy, bQz = szB + sideBz - upBz;
            float bRx = sxB - sideBx - upBx, bRy = syB - sideBy - upBy, bRz = szB - sideBz - upBz;
            float bSx = sxB - sideBx + upBx, bSy = syB - sideBy + upBy, bSz = szB - sideBz + upBz;

            int colorBase = i * 4;
            int lightA = s.nodeLight[i];
            int lightB = s.nodeLight[j];
            Normal sideNormal = normal(
                    sideAx + sideBx, sideAy + sideBy, sideAz + sideBz,
                    1.0F, 0.0F, 0.0F);
            Normal upNormal = normal(
                    upAx + upBx, upAy + upBy, upAz + upBz,
                    0.0F, 1.0F, 0.0F);

            // face 0 (+side)
            quad(vc, aPx, aPy, aPz, lightA, bPx, bPy, bPz, lightB, bQx, bQy, bQz, lightB, aQx, aQy, aQz, lightA,
                    s.segmentColorARGB[colorBase], u, v, sideNormal.x, sideNormal.y, sideNormal.z);
            // face 1 (-up)
            quad(vc, aQx, aQy, aQz, lightA, bQx, bQy, bQz, lightB, bRx, bRy, bRz, lightB, aRx, aRy, aRz, lightA,
                    s.segmentColorARGB[colorBase + 1], u, v, -upNormal.x, -upNormal.y, -upNormal.z);
            // face 2 (-side)
            quad(vc, aRx, aRy, aRz, lightA, bRx, bRy, bRz, lightB, bSx, bSy, bSz, lightB, aSx, aSy, aSz, lightA,
                    s.segmentColorARGB[colorBase + 2], u, v, -sideNormal.x, -sideNormal.y, -sideNormal.z);
            // face 3 (+up)
            quad(vc, aSx, aSy, aSz, lightA, bSx, bSy, bSz, lightB, bPx, bPy, bPz, lightB, aPx, aPy, aPz, lightA,
                    s.segmentColorARGB[colorBase + 3], u, v, upNormal.x, upNormal.y, upNormal.z);
        }

        // End cap at the first node (only if this section contains it).
        if (s.segmentStart == 0 && last >= 0) {
            int idx = 0;
            emitEndCap(vc, s, idx, ox, oy, oz, u, v, nodeScale(s, idx), false);
        }
        // End cap at the last node (only if this section contains it).
        if (s.segmentEndExclusive >= last && last >= 0) {
            int idx = last;
            emitEndCap(vc, s, idx, ox, oy, oz, u, v, nodeScale(s, idx), true);
        }

        for (RopeSectionLine line : s.attachmentLines) {
            emitLineQuads(vc,
                    line.ax() - ox, line.ay() - oy, line.az() - oz,
                    line.bx() - ox, line.by() - oy, line.bz() - oz,
                    line.color(), line.light(), u, v);
        }
    }

    private static float nodeScale(RopeSectionSnapshot s, int idx) {
        return s.nodeThicknessScale == null || idx < 0 || idx >= s.nodeThicknessScale.length
                ? 1.0f
                : s.nodeThicknessScale[idx];
    }

    private static void emitEndCap(VertexConsumer vc, RopeSectionSnapshot s, int idx,
            float ox, float oy, float oz, float u, float v, float scale, boolean flipWinding) {
        float px = s.x[idx] - ox;
        float py = s.y[idx] - oy;
        float pz = s.z[idx] - oz;
        float ssx = s.sx[idx] * scale;
        float ssy = s.sy[idx] * scale;
        float ssz = s.sz[idx] * scale;
        float sux = s.ux[idx] * scale;
        float suy = s.uy[idx] * scale;
        float suz = s.uz[idx] * scale;
        Normal capNormal = endCapNormal(s, idx, flipWinding);
        int light = s.nodeLight[idx];
        // End cap color: use the first segment's face color slightly darkened.
        int colorBase = Math.min(idx, s.segmentColorARGB.length / 4 - 1) * 4;
        int capColor = s.segmentColorARGB.length > 0
                ? s.segmentColorARGB[Math.min(colorBase, s.segmentColorARGB.length - 1)]
                : 0xFF888888;
        if (flipWinding) {
            vertex(vc, px + ssx + sux, py + ssy + suy, pz + ssz + suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // P
            vertex(vc, px + ssx - sux, py + ssy - suy, pz + ssz - suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // Q
            vertex(vc, px - ssx - sux, py - ssy - suy, pz - ssz - suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // R
            vertex(vc, px - ssx + sux, py - ssy + suy, pz - ssz + suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // S
        } else {
            vertex(vc, px - ssx + sux, py - ssy + suy, pz - ssz + suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // S
            vertex(vc, px - ssx - sux, py - ssy - suy, pz - ssz - suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // R
            vertex(vc, px + ssx - sux, py + ssy - suy, pz + ssz - suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // Q
            vertex(vc, px + ssx + sux, py + ssy + suy, pz + ssz + suz, capColor, u, v, light,
                    capNormal.x, capNormal.y, capNormal.z); // P
        }
    }

    private static Normal endCapNormal(RopeSectionSnapshot s, int idx, boolean endCap) {
        int other = endCap ? idx - 1 : idx + 1;
        if (other < 0 || other >= s.nodeCount) {
            return new Normal(0.0F, 1.0F, 0.0F);
        }
        float nx = s.x[idx] - s.x[other];
        float ny = s.y[idx] - s.y[other];
        float nz = s.z[idx] - s.z[other];
        return normal(nx, ny, nz, 0.0F, 1.0F, 0.0F);
    }

    private static Normal normal(float x, float y, float z, float fallbackX, float fallbackY, float fallbackZ) {
        float lenSqr = x * x + y * y + z * z;
        if (lenSqr < 1.0e-10F) {
            return new Normal(fallbackX, fallbackY, fallbackZ);
        }
        float invLen = (float) (1.0D / Math.sqrt(lenSqr));
        return new Normal(x * invLen, y * invLen, z * invLen);
    }

    private static void quad(VertexConsumer vc,
            float x0, float y0, float z0, int l0,
            float x1, float y1, float z1, int l1,
            float x2, float y2, float z2, int l2,
            float x3, float y3, float z3, int l3,
            int color, float u, float v,
            float nx, float ny, float nz) {
        // Reversed winding keeps the generated faces outward-facing in the chunk
        // buffer.
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

    private static void emitLineQuads(VertexConsumer vc,
            float ax, float ay, float az,
            float bx, float by, float bz,
            int color, int light, float u, float v) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-5F)
            return;
        float udx = dx / len, udy = dy / len, udz = dz / len;
        float n1x, n1y, n1z;
        if (Math.abs(udy) > 0.99F) {
            n1x = 1.0F;
            n1y = 0.0F;
            n1z = 0.0F;
        } else {
            n1x = udz;
            n1y = 0.0F;
            n1z = -udx;
            float l1 = (float) Math.sqrt(n1x * n1x + n1z * n1z);
            n1x /= l1;
            n1z /= l1;
        }
        float n2x = udy * n1z - udz * n1y;
        float n2y = udz * n1x - udx * n1z;
        float n2z = udx * n1y - udy * n1x;
        float w = ATTACHMENT_LINE_HALF_THICKNESS;
        n1x *= w;
        n1y *= w;
        n1z *= w;
        n2x *= w;
        n2y *= w;
        n2z *= w;
        linePlane(vc, ax, ay, az, bx, by, bz, n1x, n1y, n1z, color, light, u, v);
        linePlane(vc, ax, ay, az, bx, by, bz, n2x, n2y, n2z, color, light, u, v);
    }

    private static void linePlane(VertexConsumer vc,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float nx, float ny, float nz,
            int color, int light, float u, float v) {
        Normal faceNormal = normal(
                (by - ay) * nz - (bz - az) * ny,
                (bz - az) * nx - (bx - ax) * nz,
                (bx - ax) * ny - (by - ay) * nx,
                0.0F, 1.0F, 0.0F);
        vertex(vc, ax - nx, ay - ny, az - nz, color, u, v, light,
                faceNormal.x, faceNormal.y, faceNormal.z);
        vertex(vc, bx - nx, by - ny, bz - nz, color, u, v, light,
                faceNormal.x, faceNormal.y, faceNormal.z);
        vertex(vc, bx + nx, by + ny, bz + nz, color, u, v, light,
                faceNormal.x, faceNormal.y, faceNormal.z);
        vertex(vc, ax + nx, ay + ny, az + nz, color, u, v, light,
                faceNormal.x, faceNormal.y, faceNormal.z);
        vertex(vc, ax + nx, ay + ny, az + nz, color, u, v, light,
                -faceNormal.x, -faceNormal.y, -faceNormal.z);
        vertex(vc, bx + nx, by + ny, bz + nz, color, u, v, light,
                -faceNormal.x, -faceNormal.y, -faceNormal.z);
        vertex(vc, bx - nx, by - ny, bz - nz, color, u, v, light,
                -faceNormal.x, -faceNormal.y, -faceNormal.z);
        vertex(vc, ax - nx, ay - ny, az - nz, color, u, v, light,
                -faceNormal.x, -faceNormal.y, -faceNormal.z);
    }

    private record Normal(float x, float y, float z) {
    }
}
