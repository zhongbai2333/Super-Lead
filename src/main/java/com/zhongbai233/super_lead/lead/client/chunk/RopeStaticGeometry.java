package com.zhongbai233.super_lead.lead.client.chunk;

import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadEndpointLayout;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.client.geom.BoundedHermiteCurve;
import com.zhongbai233.super_lead.lead.client.render.LeashBuilder;
import com.zhongbai233.super_lead.lead.client.render.RopeAttachmentRenderer;
import com.zhongbai233.super_lead.lead.client.render.RopeDynamicLights;
import com.zhongbai233.super_lead.lead.client.sim.RopeSimulation;
import com.zhongbai233.super_lead.lead.client.sim.RopeTuning;
import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
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

/**
 * Builds static chunk-local rope mesh data for ropes that no longer need
 * dynamic
 * simulation.
 *
 * <p>
 * The output is consumed by {@link StaticRopeChunkRegistry}; this builder keeps
 * geometry deterministic per chunk section so upload/rebuild decisions can be
 * made without touching gameplay state.
 */
public final class RopeStaticGeometry {

    private static final int NODE_COUNT = 16;
    private static final double EXTRACT_END_AMPLITUDE = 0.9D;
    private static final double EXTRACT_END_LENGTH = 0.45D;

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
        int extractEnd = extractEnd(connection);
        return build(connection.id(), a, b, clientLevel,
                connection.kind(), connection.powered(), connection.tier(), RopeTuning.forConnection(connection),
            extractEnd, connection);
    }

    public static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel) {
        return build(id, a, b, clientLevel, LeadKind.NORMAL, false, 0, RopeTuning.localDefaults(), 0);
    }

    public static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel,
            LeadKind kind, boolean powered, int tier) {
        return build(id, a, b, clientLevel, kind, powered, tier, RopeTuning.localDefaults(), 0);
    }

    public static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning) {
        return build(id, a, b, clientLevel, kind, powered, tier, tuning, 0);
    }

    public static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning, int extractEnd) {
        return build(id, a, b, clientLevel, kind, powered, tier, tuning, extractEnd, null);
        }

        private static RopeStaticGeometryResult build(java.util.UUID id, Vec3 a, Vec3 b, Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning, int extractEnd, LeadConnection connection) {
        double len = a.distanceTo(b);
        if (len < 1.0e-6D) {
            return RopeStaticGeometryResult.EMPTY;
        }
        RopeTuning effectiveTuning = tuning != null ? tuning : RopeTuning.localDefaults();
        Vec3 fallback = RopeSagModel.stableUnitVector(id == null ? 0L : id.getLeastSignificantBits());
        double[] cx = new double[NODE_COUNT];
        double[] cy = new double[NODE_COUNT];
        double[] cz = new double[NODE_COUNT];
        RopeSagModel.writeCatenary(a, b, effectiveTuning.slack(), effectiveTuning.gravity(), fallback, cx, cy, cz);

        Points3 visualPoints = densifyForVisualStripes(smoothVisualPolyline(cx, cy, cz), effectiveTuning);
        return finalizeSnapshot(id, visualPoints.x(), visualPoints.y(), visualPoints.z(), clientLevel, kind, powered, tier, effectiveTuning, extractEnd,
            connection, cx, cy, cz);
    }

    public static RopeStaticGeometryResult buildFromSim(LeadConnection connection,
            RopeSimulation sim,
            Level clientLevel) {
        return buildFromSim(connection.id(), sim, clientLevel,
            connection.kind(), connection.powered(), connection.tier(), sim.tuning(), extractEnd(connection),
            connection);
    }

    public static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel) {
        return buildFromSim(id, sim, clientLevel, LeadKind.NORMAL, false, 0, sim.tuning(), 0);
    }

    public static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier) {
        return buildFromSim(id, sim, clientLevel, kind, powered, tier, sim.tuning(), 0);
    }

    public static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning) {
        return buildFromSim(id, sim, clientLevel, kind, powered, tier, tuning, 0);
    }

    public static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning, int extractEnd) {
        return buildFromSim(id, sim, clientLevel, kind, powered, tier, tuning, extractEnd, null);
        }

        private static RopeStaticGeometryResult buildFromSim(java.util.UUID id,
            RopeSimulation sim,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning, int extractEnd, LeadConnection connection) {
        int n = sim.nodeCount();
        if (n < 2)
            return RopeStaticGeometryResult.EMPTY;
        sim.prepareRender(1.0F);
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = sim.renderX(i);
            y[i] = sim.renderY(i);
            z[i] = sim.renderZ(i);
        }
        double dx = x[n - 1] - x[0], dy = y[n - 1] - y[0], dz = z[n - 1] - z[0];
        if (dx * dx + dy * dy + dz * dz < 1.0e-8D)
            return RopeStaticGeometryResult.EMPTY;
        RopeTuning effectiveTuning = tuning != null ? tuning : sim.tuning();
        Points3 visualPoints = densifyForVisualStripes(smoothVisualPolyline(x, y, z), effectiveTuning);
        return finalizeSnapshot(id, visualPoints.x(), visualPoints.y(), visualPoints.z(), clientLevel, kind, powered, tier,
            effectiveTuning, extractEnd, connection, x, y, z);
    }

    static Points3 smoothVisualPolyline(double[] x, double[] y, double[] z) {
        if (x.length < 2) {
            return new Points3(x, y, z);
        }
        int outCount = x.length * 2 - 1;
        double[] outX = new double[outCount];
        double[] outY = new double[outCount];
        double[] outZ = new double[outCount];
        double[] point = new double[3];
        for (int i = 0; i < x.length - 1; i++) {
            int out = i * 2;
            outX[out] = x[i];
            outY[out] = y[i];
            outZ[out] = z[i];
            BoundedHermiteCurve.sampleSegment(x, y, z, i, 0.5D, point);
            outX[out + 1] = point[0];
            outY[out + 1] = point[1];
            outZ[out + 1] = point[2];
        }
        outX[outCount - 1] = x[x.length - 1];
        outY[outCount - 1] = y[y.length - 1];
        outZ[outCount - 1] = z[z.length - 1];
        return new Points3(outX, outY, outZ);
    }

    private static Points3 densifyForVisualStripes(Points3 points, RopeTuning tuning) {
        return densifyForVisualStripes(points.x(), points.y(), points.z(), tuning);
    }

    private static Points3 densifyForVisualStripes(double[] x, double[] y, double[] z, RopeTuning tuning) {
        if (x.length < 2) {
            return new Points3(x, y, z);
        }
        double stripeLength = Math.max(0.05D, tuning.visualSegmentLength());
        double visualScale = visualArcScale(x, y, z, tuning);
        double geometryStripeLength = stripeLength / visualScale;
        ArrayList<Double> outX = new ArrayList<>(x.length * 2);
        ArrayList<Double> outY = new ArrayList<>(y.length * 2);
        ArrayList<Double> outZ = new ArrayList<>(z.length * 2);
        outX.add(x[0]);
        outY.add(y[0]);
        outZ.add(z[0]);

        double arcStart = 0.0D;
        for (int i = 0; i < x.length - 1; i++) {
            double ax = x[i], ay = y[i], az = z[i];
            double bx = x[i + 1], by = y[i + 1], bz = z[i + 1];
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double segmentLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (segmentLength <= 1.0e-6D) {
                continue;
            }
            double arcEnd = arcStart + segmentLength;
            int firstBoundary = (int) Math.floor(arcStart / geometryStripeLength) + 1;
            int lastBoundary = (int) Math.floor((arcEnd - 1.0e-6D) / geometryStripeLength);
            for (int stripe = firstBoundary; stripe <= lastBoundary; stripe++) {
                double boundary = stripe * geometryStripeLength;
                double t = (boundary - arcStart) / segmentLength;
                if (t > 1.0e-5D && t < 1.0D - 1.0e-5D) {
                    outX.add(ax + dx * t);
                    outY.add(ay + dy * t);
                    outZ.add(az + dz * t);
                }
            }
            outX.add(bx);
            outY.add(by);
            outZ.add(bz);
            arcStart = arcEnd;
        }
        return new Points3(toDoubleArray(outX), toDoubleArray(outY), toDoubleArray(outZ));
    }

    private static double[] toDoubleArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    static record Points3(double[] x, double[] y, double[] z) {
    }

    private static RopeStaticGeometryResult finalizeSnapshot(java.util.UUID id,
            double[] x, double[] y, double[] z,
            Level clientLevel,
            LeadKind kind, boolean powered, int tier, RopeTuning tuning, int extractEnd,
            LeadConnection connection, double[] sourceX, double[] sourceY, double[] sourceZ) {
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
        float[] nodeThicknessScale = buildNodeThicknessScale(x, y, z, extractEnd);

        boolean glow = powered && (kind == LeadKind.REDSTONE || kind == LeadKind.ENERGY);
        int[] nodeLight = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos nodePos = BlockPos.containing(x[i], y[i], z[i]);
            int blockLight = glow ? 15
                : RopeDynamicLights.boostBlockLight(nodePos,
                    clientLevel.getBrightness(LightLayer.BLOCK, nodePos));
            int skyLight = clientLevel.getBrightness(LightLayer.SKY, nodePos);
            nodeLight[i] = LightCoordsUtil.pack(blockLight, skyLight);
        }

        int[] segmentStripes = buildSegmentStripeIndices(x, y, z, effectiveTuning);
        int[] segColor = new int[(n - 1) * 4];
        for (int s = 0; s < n - 1; s++) {
            int base = s * 4;
            int stripe = segmentStripes[s];
            segColor[base] = LeashBuilder.ropeColor(stripe, 0, kind, powered, tier, tuning);
            segColor[base + 1] = LeashBuilder.ropeColor(stripe, 1, kind, powered, tier, tuning);
            segColor[base + 2] = LeashBuilder.ropeColor(stripe, 2, kind, powered, tier, tuning);
            segColor[base + 3] = LeashBuilder.ropeColor(stripe, 3, kind, powered, tier, tuning);
        }

        Map<Long, List<int[]>> rangesBySection = new HashMap<>();
        Set<Long> sections = new HashSet<>(4);
        for (int i = 0; i < n - 1; i++) {
                long section = sectionAt((x[i] + x[i + 1]) * 0.5D,
                    (y[i] + y[i + 1]) * 0.5D, (z[i] + z[i + 1]) * 0.5D);
            sections.add(section);
            List<int[]> ranges = rangesBySection.computeIfAbsent(section, k -> new ArrayList<>(1));
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1)[1] == i) {
                ranges.get(ranges.size() - 1)[1] = i + 1;
            } else {
                ranges.add(new int[] { i, i + 1 });
            }
        }

        Map<Long, List<RopeSectionLine>> attachmentLinesBySection = connection == null
            ? Map.of()
            : RopeAttachmentRenderer.bakeStaticHangerLines(clientLevel, connection, x, y, z);
        sections.addAll(attachmentLinesBySection.keySet());

        Map<Long, List<RopeSectionSnapshot>> snapshotsBySection = new HashMap<>(rangesBySection.size());
        for (Map.Entry<Long, List<int[]>> e : rangesBySection.entrySet()) {
            List<RopeSectionSnapshot> snapshots = new ArrayList<>(e.getValue().size());
            boolean firstRangeInSection = true;
            for (int[] range : e.getValue()) {
                snapshots.add(new RopeSectionSnapshot(
                        id, x, y, z, sx, sy, sz, ux, uy, uz, nodeLight, segColor,
                        nodeThicknessScale, extractEnd,
                        firstRangeInSection ? attachmentLinesBySection.getOrDefault(e.getKey(), List.of())
                                : List.of(),
                        range[0], range[1], sourceX, sourceY, sourceZ));
                firstRangeInSection = false;
            }
            snapshotsBySection.put(e.getKey(), List.copyOf(snapshots));
        }
        for (Map.Entry<Long, List<RopeSectionLine>> e : attachmentLinesBySection.entrySet()) {
            if (snapshotsBySection.containsKey(e.getKey())) {
                continue;
            }
            snapshotsBySection.put(e.getKey(), List.of(new RopeSectionSnapshot(
                    id, x, y, z, sx, sy, sz, ux, uy, uz, nodeLight, segColor,
                    nodeThicknessScale, extractEnd, e.getValue(), 0, 0, sourceX, sourceY, sourceZ)));
        }
        return new RopeStaticGeometryResult(snapshotsBySection, sections);
    }

    /**
     * Maps every generated mesh segment back to the same world-length stripe used
     * by the dynamic renderer. Static geometry inserts nodes both at stripe
     * boundaries and at original simulation nodes, so using the generated segment
     * index directly would introduce an extra color transition at every original
     * node and make the pattern look compressed and uneven.
     */
    static int[] buildSegmentStripeIndices(double[] x, double[] y, double[] z, double stripeLength) {
        return buildSegmentStripeIndices(x, y, z, stripeLength, 1.0D);
    }

    static int[] buildSegmentStripeIndices(double[] x, double[] y, double[] z, RopeTuning tuning) {
        RopeTuning effectiveTuning = tuning != null ? tuning : RopeTuning.localDefaults();
        return buildSegmentStripeIndices(
                x, y, z,
                Math.max(0.05D, effectiveTuning.visualSegmentLength()),
                visualArcScale(x, y, z, effectiveTuning));
    }

    private static int[] buildSegmentStripeIndices(
            double[] x, double[] y, double[] z, double stripeLength, double visualScale) {
        int segmentCount = Math.max(0, x.length - 1);
        int[] stripes = new int[segmentCount];
        double safeStripeLength = Math.max(0.05D, stripeLength);
        double safeVisualScale = Math.max(1.0e-6D, visualScale);
        double arcStart = 0.0D;
        for (int i = 0; i < segmentCount; i++) {
            double dx = x[i + 1] - x[i];
            double dy = y[i + 1] - y[i];
            double dz = z[i + 1] - z[i];
            double segmentLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Densification already splits segments at visual stripe boundaries.
            // Sampling the midpoint avoids assigning a boundary-adjacent segment to
            // the previous stripe because of float-coordinate rounding.
            double arcMid = (arcStart + segmentLength * 0.5D) * safeVisualScale;
            stripes[i] = (int) Math.floor(Math.max(0.0D, arcMid) / safeStripeLength + 1.0e-6D);
            arcStart += segmentLength;
        }
        return stripes;
    }

    private static double visualArcScale(double[] x, double[] y, double[] z, RopeTuning tuning) {
        if (x.length < 2) {
            return 1.0D;
        }
        double geometryLength = 0.0D;
        for (int i = 1; i < x.length; i++) {
            double dx = x[i] - x[i - 1];
            double dy = y[i] - y[i - 1];
            double dz = z[i] - z[i - 1];
            geometryLength += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        if (geometryLength < 1.0e-6D) {
            return 1.0D;
        }
        int last = x.length - 1;
        double endpointDx = x[last] - x[0];
        double endpointDy = y[last] - y[0];
        double endpointDz = z[last] - z[0];
        double endpointDistance = Math.sqrt(
                endpointDx * endpointDx + endpointDy * endpointDy + endpointDz * endpointDz);
        double visualLength = RopeSagModel.physicsTargetLength(
                endpointDistance, tuning.slack(), tuning.gravity());
        return Math.max(1.0e-6D, visualLength / geometryLength);
    }

    private static int extractEnd(LeadConnection connection) {
        return switch (connection.kind()) {
            case ITEM, FLUID, PRESSURIZED, ENERGY -> connection.extractAnchor();
            default -> 0;
        };
    }

    private static float[] buildNodeThicknessScale(double[] x, double[] y, double[] z, int extractEnd) {
        if (extractEnd == 0)
            return null;
        int n = x.length;
        float[] scales = new float[n];
        double[] cumulative = new double[n];
        double total = 0.0D;
        for (int i = 1; i < n; i++) {
            double dx = x[i] - x[i - 1];
            double dy = y[i] - y[i - 1];
            double dz = z[i] - z[i - 1];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
            cumulative[i] = total;
        }
        if (total < 1.0e-6D) {
            java.util.Arrays.fill(scales, 1.0F);
            return scales;
        }
        for (int i = 0; i < n; i++) {
            double normalized = cumulative[i] / total;
            double bonus = 0.0D;
            if (extractEnd == 1 && normalized < EXTRACT_END_LENGTH) {
                double f = 1.0D - (normalized / EXTRACT_END_LENGTH);
                bonus = EXTRACT_END_AMPLITUDE * f * f;
            } else if (extractEnd == 2 && normalized > 1.0D - EXTRACT_END_LENGTH) {
                double f = (normalized - (1.0D - EXTRACT_END_LENGTH)) / EXTRACT_END_LENGTH;
                bonus = EXTRACT_END_AMPLITUDE * f * f;
            }
            scales[i] = (float) (1.0D + bonus);
        }
        return scales;
    }

    private static long sectionAt(double x, double y, double z) {
        return SectionPos.asLong(
                SectionPos.blockToSectionCoord((int) Math.floor(x)),
                SectionPos.blockToSectionCoord((int) Math.floor(y)),
                SectionPos.blockToSectionCoord((int) Math.floor(z)));
    }

    private static void buildFrames(double[] x, double[] y, double[] z, float halfThickness,
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
