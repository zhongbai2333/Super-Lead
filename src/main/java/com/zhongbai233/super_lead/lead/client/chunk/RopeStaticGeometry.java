package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;
import com.zhongbai233.super_lead.lead.client.render.RopeDynamicLights;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.LightCoordsUtil;

public final class RopeStaticGeometry {

    private static final int NODE_COUNT = 16;

    private RopeStaticGeometry() {
    }

    public static RopeStaticGeometryResult build(LeadConnection connection, Level clientLevel) {
        return build(connection, clientLevel, List.of());
    }

    public static RopeStaticGeometryResult build(LeadConnection connection, Level clientLevel,
            List<LeadConnection> allConnections) {
        LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(clientLevel, connection, allConnections);
        Vec3 a = endpoints.from();
        Vec3 b = endpoints.to();
        return build(connection.id(), a, b, clientLevel,
                connection.kind(), connection.powered(), connection.tier(), RopeTuning.forConnection(connection));
    }

    public static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel) {
        return build(id, a, b, clientLevel, LeadKind.NORMAL, false, 0, RopeTuning.localDefaults());
    }

    public static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel,
            LeadKind kind, boolean powered, int tier) {
        return build(id, a, b, clientLevel, kind, powered, tier, RopeTuning.localDefaults());
    }

    public static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning) {
        double len = a.distanceTo(b);
        if (len < 1.0e-6D) {
            return RopeStaticGeometryResult.EMPTY;
        }
        double sag = Math.min(0.55D, len * 0.055D);

        float[] x = new float[NODE_COUNT];
        float[] y = new float[NODE_COUNT];
        float[] z = new float[NODE_COUNT];
        for (int i = 0; i < NODE_COUNT; i++) {
            double t = i / (double) (NODE_COUNT - 1);
            x[i] = (float) (a.x + (b.x - a.x) * t);
            y[i] = (float) (a.y + (b.y - a.y) * t - Math.sin(Math.PI * t) * sag);
            z[i] = (float) (a.z + (b.z - a.z) * t);
        }

        return finalizeSnapshot(id, x, y, z, clientLevel, kind, powered, tier, tuning);
    }

    public static RopeStaticGeometryResult buildFromSim(LeadConnection connection,
            RopeSimulation sim,
            Level clientLevel) {
        return buildFromSim(connection.id(), sim, clientLevel,
                connection.kind(), connection.powered(), connection.tier(), sim.tuning());
    }

    public static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel) {
        return buildFromSim(id, sim, clientLevel, LeadKind.NORMAL, false, 0, sim.tuning());
    }

    public static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier) {
        return buildFromSim(id, sim, clientLevel, kind, powered, tier, sim.tuning());
    }

    public static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning) {
        int n = sim.nodeCount();
        if (n < 2)
            return RopeStaticGeometryResult.EMPTY;
        sim.prepareRender(1.0F);
        float[] x = new float[n];
        float[] y = new float[n];
        float[] z = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = (float) sim.renderX(i);
            y[i] = (float) sim.renderY(i);
            z[i] = (float) sim.renderZ(i);
        }
        double dx = x[n - 1] - x[0], dy = y[n - 1] - y[0], dz = z[n - 1] - z[0];
        if (dx * dx + dy * dy + dz * dz < 1.0e-8D)
            return RopeStaticGeometryResult.EMPTY;
        return finalizeSnapshot(id, x, y, z, clientLevel, kind, powered, tier, tuning);
    }

    private static RopeStaticGeometryResult finalizeSnapshot(java.util.UUID id,
            float[] x, float[] y, float[] z,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning) {
        int n = x.length;
        RopeTuning effectiveTuning = tuning != null ? tuning : RopeTuning.localDefaults();
        float halfThickness = (float) effectiveTuning.halfThickness();
        float[] sx = new float[n];
        float[] sy = new float[n];
        float[] sz = new float[n];
        float[] ux = new float[n];
        float[] uy = new float[n];
        float[] uz = new float[n];
        buildFrames(x, y, z, halfThickness, sx, sy, sz, ux, uy, uz);

        BlockPos endA = BlockPos.containing(x[0], y[0], z[0]);
        BlockPos endB = BlockPos.containing(x[n - 1], y[n - 1], z[n - 1]);
        boolean glow = powered && (kind == LeadKind.REDSTONE || kind == LeadKind.ENERGY);
        int blockA = glow ? 15
                : RopeDynamicLights.boostBlockLight(endA,
                        clientLevel.getBrightness(LightLayer.BLOCK, endA));
        int blockB = glow ? 15
                : RopeDynamicLights.boostBlockLight(endB,
                        clientLevel.getBrightness(LightLayer.BLOCK, endB));
        int skyA = clientLevel.getBrightness(LightLayer.SKY, endA);
        int skyB = clientLevel.getBrightness(LightLayer.SKY, endB);
        int[] nodeLight = new int[n];
        for (int i = 0; i < n; i++) {
            float t = (n == 1) ? 0f : (i / (float) (n - 1));
            int bL = (int) (blockA + (blockB - blockA) * t);
            int sL = (int) (skyA + (skyB - skyA) * t);
            nodeLight[i] = LightCoordsUtil.pack(bL, sL);
        }

        int[] segColor = new int[(n - 1) * 4];
        for (int s = 0; s < n - 1; s++) {
            int base = s * 4;
            segColor[base] = LeashBuilder.ropeColor(s, 0, kind, powered, tier, tuning);
            segColor[base + 1] = LeashBuilder.ropeColor(s, 1, kind, powered, tier, tuning);
            segColor[base + 2] = LeashBuilder.ropeColor(s, 2, kind, powered, tier, tuning);
            segColor[base + 3] = LeashBuilder.ropeColor(s, 3, kind, powered, tier, tuning);
        }

        Map<Long, List<int[]>> rangesBySection = new HashMap<>();
        Set<Long> sections = new HashSet<>(4);
        for (int i = 0; i < n - 1; i++) {
            long section = sectionAt((x[i] + x[i + 1]) * 0.5f,
                    (y[i] + y[i + 1]) * 0.5f, (z[i] + z[i + 1]) * 0.5f);
            sections.add(section);
            List<int[]> ranges = rangesBySection.computeIfAbsent(section, k -> new ArrayList<>(1));
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1)[1] == i) {
                ranges.get(ranges.size() - 1)[1] = i + 1;
            } else {
                ranges.add(new int[] { i, i + 1 });
            }
        }

        Map<Long, List<RopeSectionSnapshot>> snapshotsBySection = new HashMap<>(rangesBySection.size());
        for (Map.Entry<Long, List<int[]>> e : rangesBySection.entrySet()) {
            List<RopeSectionSnapshot> snapshots = new ArrayList<>(e.getValue().size());
            for (int[] range : e.getValue()) {
                snapshots.add(new RopeSectionSnapshot(
                        id, x, y, z, sx, sy, sz, ux, uy, uz, nodeLight, segColor,
                        range[0], range[1]));
            }
            snapshotsBySection.put(e.getKey(), List.copyOf(snapshots));
        }
        return new RopeStaticGeometryResult(snapshotsBySection, sections);
    }

    private static long sectionAt(float x, float y, float z) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord((int) Math.floor(x)),
                SectionPos.blockToSectionCoord((int) Math.floor(y)),
                SectionPos.blockToSectionCoord((int) Math.floor(z)));
    }

    private static void buildFrames(float[] x, float[] y, float[] z, float halfThickness,
            float[] sx, float[] sy, float[] sz,
            float[] ux, float[] uy, float[] uz) {
        int n = x.length;
        double prevSideX = 0.0D;
        double prevSideY = 0.0D;
        double prevSideZ = 0.0D;
        boolean hasPrevSide = false;
        for (int i = 0; i < n; i++) {
            double tx, ty, tz;
            if (i == 0) {
                tx = x[1] - x[0];
                ty = y[1] - y[0];
                tz = z[1] - z[0];
            } else if (i == n - 1) {
                tx = x[i] - x[i - 1];
                ty = y[i] - y[i - 1];
                tz = z[i] - z[i - 1];
            } else {
                double px = x[i] - x[i - 1];
                double py = y[i] - y[i - 1];
                double pz = z[i] - z[i - 1];
                double nx2 = x[i + 1] - x[i];
                double ny2 = y[i + 1] - y[i];
                double nz2 = z[i + 1] - z[i];
                double pLen = Math.sqrt(px * px + py * py + pz * pz);
                double nLen = Math.sqrt(nx2 * nx2 + ny2 * ny2 + nz2 * nz2);
                if (pLen > 1.0e-6D) {
                    px /= pLen;
                    py /= pLen;
                    pz /= pLen;
                }
                if (nLen > 1.0e-6D) {
                    nx2 /= nLen;
                    ny2 /= nLen;
                    nz2 /= nLen;
                }
                tx = px + nx2;
                ty = py + ny2;
                tz = pz + nz2;
                if (tx * tx + ty * ty + tz * tz < 1.0e-8D) {
                    tx = nx2;
                    ty = ny2;
                    tz = nz2;
                }
            }
            double tLen = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tLen < 1.0e-6D) {
                tx = 1.0D;
                ty = 0.0D;
                tz = 0.0D;
            } else {
                tx /= tLen;
                ty /= tLen;
                tz /= tLen;
            }

            double s0x;
            double s0y;
            double s0z;
            if (hasPrevSide) {
                double along = prevSideX * tx + prevSideY * ty + prevSideZ * tz;
                s0x = prevSideX - tx * along;
                s0y = prevSideY - ty * along;
                s0z = prevSideZ - tz * along;
            } else {
                s0x = -tz;
                s0y = 0.0D;
                s0z = tx;
            }
            double sLenSqr = s0x * s0x + s0y * s0y + s0z * s0z;
            if (sLenSqr < 1.0e-8D) {
                s0x = -tz;
                s0y = 0.0D;
                s0z = tx;
                sLenSqr = s0x * s0x + s0z * s0z;
            }
            if (sLenSqr < 1.0e-8D) {
                s0x = 1.0D;
                s0y = 0.0D;
                s0z = 0.0D;
                double fallbackAlong = s0x * tx + s0y * ty + s0z * tz;
                s0x -= tx * fallbackAlong;
                s0y -= ty * fallbackAlong;
                s0z -= tz * fallbackAlong;
                sLenSqr = s0x * s0x + s0y * s0y + s0z * s0z;
                if (sLenSqr < 1.0e-8D) {
                    s0x = 0.0D;
                    s0y = 0.0D;
                    s0z = 1.0D;
                    fallbackAlong = s0x * tx + s0y * ty + s0z * tz;
                    s0x -= tx * fallbackAlong;
                    s0y -= ty * fallbackAlong;
                    s0z -= tz * fallbackAlong;
                    sLenSqr = s0x * s0x + s0y * s0y + s0z * s0z;
                }
            }
            double invSide = 1.0D / Math.sqrt(sLenSqr);
            s0x *= invSide;
            s0y *= invSide;
            s0z *= invSide;

            double u0x = s0y * tz - s0z * ty;
            double u0y = s0z * tx - s0x * tz;
            double u0z = s0x * ty - s0y * tx;
            double invUp = 1.0D / Math.sqrt(u0x * u0x + u0y * u0y + u0z * u0z);
            u0x *= invUp;
            u0y *= invUp;
            u0z *= invUp;

            if (hasPrevSide && s0x * prevSideX + s0y * prevSideY + s0z * prevSideZ < 0.0D) {
                s0x = -s0x;
                s0y = -s0y;
                s0z = -s0z;
                u0x = -u0x;
                u0y = -u0y;
                u0z = -u0z;
            }

            prevSideX = s0x;
            prevSideY = s0y;
            prevSideZ = s0z;
            hasPrevSide = true;

            sx[i] = (float) (s0x * halfThickness);
            sy[i] = (float) (s0y * halfThickness);
            sz[i] = (float) (s0z * halfThickness);
            ux[i] = (float) (u0x * halfThickness);
            uy[i] = (float) (u0y * halfThickness);
            uz[i] = (float) (u0z * halfThickness);
        }
    }
}
