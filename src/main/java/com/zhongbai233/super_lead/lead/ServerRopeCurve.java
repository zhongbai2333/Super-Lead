package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.lead.physics.RopeSagModel;
import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import com.zhongbai233.super_lead.lead.client.geom.SegmentHit;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Deterministic server-side coarse physics rope used by zipline and contact
 * validation.
 */
final class ServerRopeCurve {
    private static final double EPS = 1.0e-8D;
    private static final int MIN_SEGMENTS = 4;
    private static final int MAX_SERVER_SEGMENTS = 64;
    private static final int MIN_RELAX_TICKS = 36;
    private static final int MAX_RELAX_TICKS = 96;
    private static final double GRAVITY_SCALE = 1.0D;
    private static final double MAX_INITIAL_SAG = 8.0D;
    private static final int TERRAIN_PASSES = 8;
    private static final int TERRAIN_DISTANCE_PASSES = 4;
    private static final double SERVER_WIND_TIMESTEP = 0.045D;

    private ServerRopeCurve() {
    }

    static Shape from(ServerLevel level, LeadConnection connection, Vec3 a, Vec3 b) {
        if (connection == null || connection.physicsPreset().isBlank()) {
            return straight(a, b);
        }
        return from(a, b, ServerPhysicsTuning.loadServerPhysicsTuning(level, connection.physicsPreset()), level,
                connection);
    }

    static Shape from(Vec3 a, Vec3 b, ServerPhysicsTuning tuning) {
        return from(a, b, tuning, null, null);
    }

    private static Shape from(Vec3 a, Vec3 b, ServerPhysicsTuning tuning, ServerLevel level,
            LeadConnection connection) {
        double chord = a.distanceTo(b);
        if (chord < EPS || tuning == null || !tuning.physicsEnabled() || Math.abs(tuning.gravity()) < EPS) {
            return straight(a, b);
        }

        double targetLength = targetLength(a, b, tuning);
        if (targetLength <= chord + 1.0e-5D) {
            return straight(a, b);
        }

        int segments = segmentCount(chord, tuning);
        int nodes = segments + 1;
        double[] x = new double[nodes];
        double[] y = new double[nodes];
        double[] z = new double[nodes];
        double[] px = new double[nodes];
        double[] py = new double[nodes];
        double[] pz = new double[nodes];
        double[] vx = new double[nodes];
        double[] vy = new double[nodes];
        double[] vz = new double[nodes];
        double[] lambda = new double[segments];

        initialiseNodes(a, b, tuning, x, y, z);
        System.arraycopy(x, 0, px, 0, nodes);
        System.arraycopy(y, 0, py, 0, nodes);
        System.arraycopy(z, 0, pz, 0, nodes);

        double targetSegment = targetLength / segments;
        double tautWeight = RopeSagModel.tautProjectionWeight(tuning.slack());
        double gravityScale = 1.0D - tautWeight;
        int minPasses = Math.max((segments + 1) / 2,
                (int) Math.ceil(segments * (1.0D + tautWeight * 3.0D)));
        int iterations = Math.max(tuning.iterAir(), minPasses);
        int relaxTicks = relaxTicks(segments, iterations);
        double damping = clamp(tuning.damping(), 0.0D, 0.999D);
        double alpha = Math.max(0.0D, tuning.compliance());
        long gameTick = level == null ? 0L : level.getGameTime();
        for (int tick = 0; tick < relaxTicks; tick++) {
            Arrays.fill(lambda, 0.0D);
            for (int i = 1; i < nodes - 1; i++) {
                px[i] = x[i];
                py[i] = y[i];
                pz[i] = z[i];
                vx[i] *= damping;
                vy[i] = vy[i] * damping + tuning.gravity() * GRAVITY_SCALE * gravityScale;
                vz[i] *= damping;
                applyWind(i, gameTick, SERVER_WIND_TIMESTEP, gravityScale, tuning, segments, x, z, vx, vy, vz);
                x[i] += vx[i];
                y[i] += vy[i];
                z[i] += vz[i];
            }
            pin(a, b, x, y, z);

            for (int it = 0; it < iterations; it++) {
                if ((it & 1) == 0) {
                    for (int s = 0; s < segments; s++) {
                        solveDistance(s, targetSegment, alpha, lambda, x, y, z);
                    }
                } else {
                    for (int s = segments - 1; s >= 0; s--) {
                        solveDistance(s, targetSegment, alpha, lambda, x, y, z);
                    }
                }
                pin(a, b, x, y, z);
            }

            for (int i = 1; i < nodes - 1; i++) {
                vx[i] = x[i] - px[i];
                vy[i] = y[i] - py[i];
                vz[i] = z[i] - pz[i];
            }
            applyTautProjection(a, b, tautWeight, x, y, z, vx, vy, vz);
        }

        resolveTerrain(level, connection, tuning, a, b, targetSegment, alpha, lambda, x, y, z);
        return shapeFromNodes(a, b, targetLength, x, y, z);
    }

    static Vec3 point(Shape shape, double rawT) {
        double target = shape.length() * clamp01(rawT);
        int seg = segmentAt(shape, target);
        double start = shape.lengths()[seg];
        double end = shape.lengths()[seg + 1];
        double f = end - start <= EPS ? 0.0D : (target - start) / (end - start);
        return new Vec3(
                shape.x()[seg] + (shape.x()[seg + 1] - shape.x()[seg]) * f,
                shape.y()[seg] + (shape.y()[seg + 1] - shape.y()[seg]) * f,
                shape.z()[seg] + (shape.z()[seg + 1] - shape.z()[seg]) * f);
    }

    static Vec3 tangent(Shape shape, double rawT) {
        double target = shape.length() * clamp01(rawT);
        int seg = segmentAt(shape, target);
        Vec3 tangent = new Vec3(
                shape.x()[seg + 1] - shape.x()[seg],
                shape.y()[seg + 1] - shape.y()[seg],
                shape.z()[seg + 1] - shape.z()[seg]);
        if (tangent.lengthSqr() < EPS * EPS) {
            Vec3 fallback = shape.b().subtract(shape.a());
            return fallback.lengthSqr() < EPS * EPS ? new Vec3(1.0D, 0.0D, 0.0D) : fallback.normalize();
        }
        return tangent.normalize();
    }

    static double distancePointToCurveSqr(Shape shape, Vec3 point, double[] out) {
        double best = Double.POSITIVE_INFINITY;
        double[] candidate = new double[4];
        for (int i = 0; i < shape.x().length - 1; i++) {
            Vec3 a = new Vec3(shape.x()[i], shape.y()[i], shape.z()[i]);
            Vec3 b = new Vec3(shape.x()[i + 1], shape.y()[i + 1], shape.z()[i + 1]);
            double segLen = shape.lengths()[i + 1] - shape.lengths()[i];
            double d = closestPointOnSegment(a, b, point, candidate, shape.lengths()[i], segLen, shape.length());
            if (d < best) {
                best = d;
                if (out != null) {
                    out[0] = candidate[0];
                    out[1] = candidate[1];
                    out[2] = candidate[2];
                    out[3] = candidate[3];
                }
            }
        }
        return best;
    }

    static double freeSlack(Vec3 a, Vec3 b, ServerPhysicsTuning tuning) {
        if (tuning == null || !tuning.physicsEnabled() || Math.abs(tuning.gravity()) < EPS) {
            return 0.0D;
        }
        return Math.max(0.0D, targetLength(a, b, tuning) - a.distanceTo(b));
    }

    private static Shape straight(Vec3 a, Vec3 b) {
        double[] x = { a.x, b.x };
        double[] y = { a.y, b.y };
        double[] z = { a.z, b.z };
        double length = Math.max(a.distanceTo(b), EPS);
        double[] lengths = { 0.0D, length };
        return new Shape(a, b, x, y, z, lengths, length, length);
    }

    private static void initialiseNodes(Vec3 a, Vec3 b, ServerPhysicsTuning tuning,
            double[] x, double[] y, double[] z) {
        int last = x.length - 1;
        double sag = Math.min(MAX_INITIAL_SAG,
                RopeSagModel.midspanSag(a, b, tuning.slack(), tuning.gravity()));
        Vec3 sagDir = RopeSagModel.sagDirection(a, b, tuning.gravity(), null);
        for (int i = 0; i <= last; i++) {
            double t = i / (double) last;
            double bend = Math.sin(Math.PI * t) * sag;
            x[i] = a.x + (b.x - a.x) * t + sagDir.x * bend;
            y[i] = a.y + (b.y - a.y) * t + sagDir.y * bend;
            z[i] = a.z + (b.z - a.z) * t + sagDir.z * bend;
        }
        applyTautProjection(a, b, RopeSagModel.tautProjectionWeight(tuning.slack()), x, y, z, null, null, null);
        pin(a, b, x, y, z);
    }

    private static void applyTautProjection(Vec3 a, Vec3 b, double weight,
            double[] x, double[] y, double[] z,
            double[] vx, double[] vy, double[] vz) {
        if (weight <= 0.0D || x.length < 3) {
            return;
        }
        double clamped = Math.min(1.0D, weight);
        double keepVelocity = 1.0D - clamped;
        int last = x.length - 1;
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        for (int i = 1; i < last; i++) {
            double t = i / (double) last;
            double tx = a.x + dx * t;
            double ty = a.y + dy * t;
            double tz = a.z + dz * t;
            x[i] += (tx - x[i]) * clamped;
            y[i] += (ty - y[i]) * clamped;
            z[i] += (tz - z[i]) * clamped;
            if (vx != null) {
                vx[i] *= keepVelocity;
            }
            if (vy != null) {
                vy[i] *= keepVelocity;
            }
            if (vz != null) {
                vz[i] *= keepVelocity;
            }
        }
    }

    private static void solveDistance(int seg, double targetLen, double alpha,
            double[] lambda, double[] x, double[] y, double[] z) {
        int i = seg;
        int j = seg + 1;
        double dx = x[j] - x[i];
        double dy = y[j] - y[i];
        double dz = z[j] - z[i];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < EPS) {
            return;
        }
        double wi = i == 0 ? 0.0D : 1.0D;
        double wj = j == x.length - 1 ? 0.0D : 1.0D;
        double wsum = wi + wj;
        if (wsum <= 0.0D) {
            return;
        }
        double c = len - targetLen;
        double dlambda = (-c - alpha * lambda[seg]) / (wsum + alpha);
        lambda[seg] += dlambda;
        double nx = dx / len;
        double ny = dy / len;
        double nz = dz / len;
        if (wi > 0.0D) {
            x[i] -= nx * dlambda * wi;
            y[i] -= ny * dlambda * wi;
            z[i] -= nz * dlambda * wi;
        }
        if (wj > 0.0D) {
            x[j] += nx * dlambda * wj;
            y[j] += ny * dlambda * wj;
            z[j] += nz * dlambda * wj;
        }
    }

    private static Shape shapeFromNodes(Vec3 a, Vec3 b, double targetLength,
            double[] x, double[] y, double[] z) {
        int nodes = x.length;
        double[] outX = Arrays.copyOf(x, nodes);
        double[] outY = Arrays.copyOf(y, nodes);
        double[] outZ = Arrays.copyOf(z, nodes);
        double[] lengths = new double[nodes];
        for (int i = 1; i < nodes; i++) {
            double dx = outX[i] - outX[i - 1];
            double dy = outY[i] - outY[i - 1];
            double dz = outZ[i] - outZ[i - 1];
            lengths[i] = lengths[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        double length = Math.max(lengths[nodes - 1], EPS);
        return new Shape(a, b, outX, outY, outZ, lengths, length, targetLength);
    }

    private static int segmentAt(Shape shape, double distance) {
        double[] lengths = shape.lengths();
        int lastSegment = lengths.length - 2;
        if (distance <= 0.0D) {
            return 0;
        }
        if (distance >= lengths[lengths.length - 1]) {
            return lastSegment;
        }
        int lo = 0;
        int hi = lastSegment;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lengths[mid + 1] < distance) {
                lo = mid + 1;
            } else if (lengths[mid] > distance) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(lastSegment, lo));
    }

    private static int segmentCount(double chord, ServerPhysicsTuning tuning) {
        double segmentLength = Math.max(0.05D, tuning.segmentLength());
        int cap = Math.max(MIN_SEGMENTS, Math.min(MAX_SERVER_SEGMENTS, tuning.segmentMax()));
        return Math.max(MIN_SEGMENTS, Math.min(cap, (int) Math.ceil(chord / segmentLength)));
    }

    private static int relaxTicks(int segments, int iterations) {
        return Math.max(MIN_RELAX_TICKS, Math.min(MAX_RELAX_TICKS, segments + iterations));
    }

    private static void pin(Vec3 a, Vec3 b, double[] x, double[] y, double[] z) {
        int last = x.length - 1;
        x[0] = a.x;
        y[0] = a.y;
        z[0] = a.z;
        x[last] = b.x;
        y[last] = b.y;
        z[last] = b.z;
    }

    private static void resolveTerrain(ServerLevel level, LeadConnection connection, ServerPhysicsTuning tuning,
            Vec3 a, Vec3 b,
            double targetSegment,
            double alpha, double[] lambda, double[] x, double[] y, double[] z) {
        if (level == null || connection == null || x.length < 3) {
            return;
        }
        int nodes = x.length;
        int segments = nodes - 1;
        double radius = terrainRadius(tuning);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double[] sx = new double[nodes];
        double[] sy = new double[nodes];
        double[] sz = new double[nodes];
        SegmentHit sweepHit = new SegmentHit();
        for (int pass = 0; pass < TERRAIN_PASSES; pass++) {
            System.arraycopy(x, 0, sx, 0, nodes);
            System.arraycopy(y, 0, sy, 0, nodes);
            System.arraycopy(z, 0, sz, 0, nodes);

            boolean moved = false;
            for (int node = 1; node < x.length - 1; node++) {
                moved |= resolveNodeAgainstTerrain(level, connection, cursor, node, radius, x, y, z);
            }
            for (int seg = 0; seg < segments; seg++) {
                moved |= resolveSegmentAgainstTerrain(level, connection, cursor, seg, seg + 1, radius,
                        x, y, z);
            }
            // Sweep: detect tunneling when a node was pushed past a thin/tall block.
            for (int seg = 0; seg < segments; seg++) {
                int aIdx = seg;
                double fx = sx[aIdx], fy = sy[aIdx], fz = sz[aIdx];
                double tx = x[aIdx], ty = y[aIdx], tz = z[aIdx];
                double moveSqr = (tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz);
                if (moveSqr < 1.0e-6D)
                    continue;
                double bestT = Double.POSITIVE_INFINITY;
                double bestDx = 0, bestDy = 0, bestDz = 0;
                int bxMin = (int) Math.floor(Math.min(fx, tx) - radius) - 1;
                int bxMax = (int) Math.floor(Math.max(fx, tx) + radius) + 1;
                int byMin = (int) Math.floor(Math.min(fy, ty) - radius) - 1;
                int byMax = (int) Math.floor(Math.max(fy, ty) + radius) + 1;
                int bzMin = (int) Math.floor(Math.min(fz, tz) - radius) - 1;
                int bzMax = (int) Math.floor(Math.max(fz, tz) + radius) + 1;
                for (int bx = bxMin; bx <= bxMax; bx++) {
                    for (int by = byMin; by <= byMax; by++) {
                        for (int bz = bzMin; bz <= bzMax; bz++) {
                            if (isAnchorColumn(connection, bx, by, bz))
                                continue;
                            cursor.set(bx, by, bz);
                            BlockState state = level.getBlockState(cursor);
                            VoxelShape shape = state.getCollisionShape(level, cursor);
                            if (shape.isEmpty())
                                continue;
                            for (AABB local : shape.toAabbs()) {
                                AABB box = local.move(bx, by, bz).inflate(radius);
                                if (RopeMath.intersectSegmentAabb(fx, fy, fz, tx, ty, tz, box,
                                        0.0D, 0.0D, sweepHit)) {
                                    if (sweepHit.t < bestT) {
                                        bestT = sweepHit.t;
                                        bestDx = sweepHit.dx;
                                        bestDy = sweepHit.dy;
                                        bestDz = sweepHit.dz;
                                    }
                                }
                            }
                        }
                    }
                }
                if (bestT < Double.POSITIVE_INFINITY) {
                    x[aIdx] += bestDx;
                    y[aIdx] += bestDy;
                    z[aIdx] += bestDz;
                    moved = true;
                }
            }
            if (!moved) {
                return;
            }
            satisfyDistance(a, b, targetSegment, alpha, lambda, x, y, z, TERRAIN_DISTANCE_PASSES);
        }
    }

    private static boolean resolveNodeAgainstTerrain(ServerLevel level, LeadConnection connection,
            BlockPos.MutableBlockPos cursor, int node, double radius, double[] x, double[] y, double[] z) {
        boolean moved = false;
        int bxMin = (int) Math.floor(x[node] - radius) - 1;
        int bxMax = (int) Math.floor(x[node] + radius) + 1;
        int byMin = (int) Math.floor(y[node] - radius) - 1;
        int byMax = (int) Math.floor(y[node] + radius) + 1;
        int bzMin = (int) Math.floor(z[node] - radius) - 1;
        int bzMax = (int) Math.floor(z[node] + radius) + 1;
        for (int bx = bxMin; bx <= bxMax; bx++) {
            for (int by = byMin; by <= byMax; by++) {
                for (int bz = bzMin; bz <= bzMax; bz++) {
                    if (isAnchorColumn(connection, bx, by, bz)) {
                        continue;
                    }
                    cursor.set(bx, by, bz);
                    BlockState state = level.getBlockState(cursor);
                    VoxelShape shape = state.getCollisionShape(level, cursor);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    for (AABB local : shape.toAabbs()) {
                        moved |= projectNodeOutOfBox(node, local.move(bx, by, bz), radius, x, y, z);
                    }
                }
            }
        }
        return moved;
    }

    private static boolean resolveSegmentAgainstTerrain(ServerLevel level, LeadConnection connection,
            BlockPos.MutableBlockPos cursor, int nodeA, int nodeB, double radius,
            double[] x, double[] y, double[] z) {
        boolean pinnedA = nodeA == 0;
        boolean pinnedB = nodeB == x.length - 1;
        if (pinnedA && pinnedB) {
            return false;
        }
        double ax = x[nodeA], ay = y[nodeA], az = z[nodeA];
        double bx = x[nodeB], by = y[nodeB], bz = z[nodeB];
        int bxMin = (int) Math.floor(Math.min(ax, bx) - radius) - 1;
        int bxMax = (int) Math.floor(Math.max(ax, bx) + radius) + 1;
        int byMin = (int) Math.floor(Math.min(ay, by) - radius) - 1;
        int byMax = (int) Math.floor(Math.max(ay, by) + radius) + 1;
        int bzMin = (int) Math.floor(Math.min(az, bz) - radius) - 1;
        int bzMax = (int) Math.floor(Math.max(az, bz) + radius) + 1;
        boolean moved = false;
        for (int blockX = bxMin; blockX <= bxMax; blockX++) {
            for (int blockY = byMin; blockY <= byMax; blockY++) {
                for (int blockZ = bzMin; blockZ <= bzMax; blockZ++) {
                    if (isAnchorColumn(connection, blockX, blockY, blockZ)) {
                        continue;
                    }
                    cursor.set(blockX, blockY, blockZ);
                    BlockState state = level.getBlockState(cursor);
                    VoxelShape shape = state.getCollisionShape(level, cursor);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    for (AABB local : shape.toAabbs()) {
                        moved |= pushSegmentOutOfBox(nodeA, nodeB, local.move(blockX, blockY, blockZ), radius,
                                x, y, z);
                    }
                }
            }
        }
        return moved;
    }

    private static boolean pushSegmentOutOfBox(int nodeA, int nodeB, AABB box, double radius,
            double[] x, double[] y, double[] z) {
        SegmentBoxContact contact = SegmentBoxContact.compute(
                x[nodeA], y[nodeA], z[nodeA],
                x[nodeB], y[nodeB], z[nodeB], box);
        if (contact.distSqr >= radius * radius) {
            return false;
        }

        double pushLen;
        double nx;
        double ny;
        double nz;
        if (contact.distSqr > 1.0e-12D) {
            double d = Math.sqrt(contact.distSqr);
            pushLen = radius - d;
            double inv = 1.0D / d;
            nx = contact.dx * inv;
            ny = contact.dy * inv;
            nz = contact.dz * inv;
        } else {
            pushLen = radius;
            nx = 0.0D;
            ny = 1.0D;
            nz = 0.0D;
        }

        double wa = nodeA == 0 ? 0.0D : 1.0D;
        double wb = nodeB == x.length - 1 ? 0.0D : 1.0D;
        double oneMinusS = 1.0D - contact.s;
        double denom = wa * oneMinusS * oneMinusS + wb * contact.s * contact.s;
        if (denom < 1.0e-9D) {
            return false;
        }

        boolean moved = false;
        double k = pushLen / denom;
        double maxStep = 2.0D * pushLen;
        if (wa > 0.0D) {
            double ka = Math.min(k * wa * oneMinusS, maxStep);
            x[nodeA] += nx * ka;
            y[nodeA] += ny * ka;
            z[nodeA] += nz * ka;
            moved = true;
        }
        if (wb > 0.0D) {
            double kb = Math.min(k * wb * contact.s, maxStep);
            x[nodeB] += nx * kb;
            y[nodeB] += ny * kb;
            z[nodeB] += nz * kb;
            moved = true;
        }
        return moved;
    }

    private static boolean projectNodeOutOfBox(int node, AABB box, double radius,
            double[] x, double[] y, double[] z) {
        return projectNodeOutOfBox(node, box, radius, 1.0D, x, y, z);
    }

    private static boolean projectNodeOutOfBox(int node, AABB box, double radius, double scale,
            double[] x, double[] y, double[] z) {
        double px = x[node];
        double py = y[node];
        double pz = z[node];
        double cpx = clamp(px, box.minX, box.maxX);
        double cpy = clamp(py, box.minY, box.maxY);
        double cpz = clamp(pz, box.minZ, box.maxZ);
        double dx = px - cpx;
        double dy = py - cpy;
        double dz = pz - cpz;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > 1.0e-12D) {
            if (d2 >= radius * radius) {
                return false;
            }
            double d = Math.sqrt(d2);
            double push = (radius - d) * scale;
            x[node] += dx / d * push;
            y[node] += dy / d * push;
            z[node] += dz / d * push;
            return true;
        }

        AABB inflated = box.inflate(radius);
        double eps = Math.max(1.0e-4D, Math.min(0.025D, radius * 0.25D));
        double[] delta = {
                inflated.minX - eps - px,
                inflated.maxX + eps - px,
                inflated.minY - eps - py,
                inflated.maxY + eps - py,
                inflated.minZ - eps - pz,
                inflated.maxZ + eps - pz
        };
        int best = 0;
        for (int i = 1; i < delta.length; i++) {
            if (Math.abs(delta[i]) < Math.abs(delta[best])) {
                best = i;
            }
        }
        switch (best) {
            case 0, 1 -> x[node] += delta[best] * scale;
            case 2, 3 -> y[node] += delta[best] * scale;
            default -> z[node] += delta[best] * scale;
        }
        return true;
    }

    private static boolean isAnchorColumn(LeadConnection connection, int bx, int by, int bz) {
        return isAnchorColumn(connection.from().pos(), bx, by, bz)
                || isAnchorColumn(connection.to().pos(), bx, by, bz);
    }

    private static boolean isAnchorColumn(BlockPos anchor, int bx, int by, int bz) {
        return bx == anchor.getX()
                && bz == anchor.getZ()
                && (by == anchor.getY() || by == anchor.getY() - 1);
    }

    private static void satisfyDistance(Vec3 a, Vec3 b, double targetSegment, double alpha, double[] lambda,
            double[] x, double[] y, double[] z, int passes) {
        for (int it = 0; it < passes; it++) {
            Arrays.fill(lambda, 0.0D);
            if ((it & 1) == 0) {
                for (int s = 0; s < x.length - 1; s++) {
                    solveDistance(s, targetSegment, alpha, lambda, x, y, z);
                }
            } else {
                for (int s = x.length - 2; s >= 0; s--) {
                    solveDistance(s, targetSegment, alpha, lambda, x, y, z);
                }
            }
            pin(a, b, x, y, z);
        }
    }

    private static double terrainRadius(ServerPhysicsTuning tuning) {
        if (tuning == null) {
            return 0.095D;
        }
        return Math.max(0.01D, Math.min(0.45D, tuning.terrainRadius() + tuning.collisionEps()));
    }

    private static void applyWind(int nodeIndex, long tick, double h, double gravityScale,
            ServerPhysicsTuning tuning, int segments, double[] x, double[] z,
            double[] vx, double[] vy, double[] vz) {
        if (!windEnabled(tuning) || RopeSagModel.tautProjectionWeight(tuning.slack()) >= 0.98D) {
            return;
        }
        WindSample wind = windAt(tuning, x[nodeIndex], z[nodeIndex], tick);
        if (wind.envelope() <= 1.0e-5D) {
            return;
        }
        double t = nodeIndex / (double) segments;
        double nodeWeight = Math.sin(Math.PI * t);
        if (nodeWeight <= 1.0e-5D) {
            return;
        }
        double force = tuning.windStrength() * wind.strengthScale() * wind.envelope() * nodeWeight
                * Math.max(0.0D, gravityScale);
        vx[nodeIndex] += wind.dirX() * force * h;
        vz[nodeIndex] += wind.dirZ() * force * h;
        vy[nodeIndex] += force * tuning.windVerticalLift() * h;
    }

    private static boolean windEnabled(ServerPhysicsTuning tuning) {
        return tuning != null
                && tuning.windEnabled()
                && tuning.windStrength() > 0.0D
                && tuning.windWaveLength() > 1.0e-5D
                && tuning.windSpeed() > 0.0D;
    }

    private static WindSample windAt(ServerPhysicsTuning tuning, double wx, double wz, long tick) {
        long windSeed = windRegionSeed(tuning, wx, wz);
        double baseDirRad = Math.toRadians(tuning.windDirectionDeg());
        double baseDirX = Math.cos(baseDirRad);
        double baseDirZ = Math.sin(baseDirRad);
        double speed = Math.max(1.0e-5D, tuning.windSpeed());
        double baseCycleTicks = Math.max(8.0D, tuning.windWaveLength() / speed);
        double windClock = tick - (wx * baseDirX + wz * baseDirZ) / speed + windSeedPhase(windSeed) * baseCycleTicks;
        long eventIndex = (long) Math.floor(windClock / baseCycleTicks);
        WindSample current = windEventAt(tuning, eventIndex, windClock, baseCycleTicks, windSeed);
        if (current.envelope() > 0.0D) {
            return current;
        }
        return windEventAt(tuning, eventIndex - 1L, windClock, baseCycleTicks, windSeed);
    }

    private static WindSample windEventAt(ServerPhysicsTuning tuning, long eventIndex, double windClock,
            double baseCycleTicks, long windSeed) {
        double duty = Math.max(0.05D, Math.min(0.95D, tuning.windDuty()));
        double pauseRoom = baseCycleTicks * (1.0D - duty);
        double startOffset = randomSigned(windSeed, eventIndex, 0x632BE59BD9B4E019L)
                * pauseRoom * Math.max(0.0D, Math.min(1.0D, tuning.windPauseJitter())) * 0.55D;
        double eventStart = eventIndex * baseCycleTicks + startOffset;

        double activeScale = jitterScale(windSeed, eventIndex, 0x41C64E6DL, tuning.windDurationJitter());
        double activeTicks = Math.max(2.0D, baseCycleTicks * duty * activeScale);
        double cyclePos = windClock - eventStart;
        if (cyclePos < 0.0D || cyclePos >= activeTicks) {
            return WindSample.NONE;
        }

        double local = cyclePos / activeTicks;
        boolean rampingGust = random01(windSeed, eventIndex, 0xD1B54A32D192ED03L) < tuning.windRampBias();
        boolean doubleSwell = random01(windSeed, eventIndex, 0xA24BAED4963EE407L) < 0.58D;
        double envelope = doubleSwell
                ? doubleSwellEnvelope(local, eventIndex, rampingGust, windSeed)
                : linearTriangleEnvelope(local, rampingGust);
        if (envelope <= 1.0e-5D) {
            return WindSample.NONE;
        }

        double strengthJitter = Math.max(0.0D, Math.min(1.0D, tuning.windStrengthJitter()));
        double strengthNoise = randomSigned(windSeed, eventIndex, 0x94D049BB133111EBL);
        double strengthScale = 1.0D + strengthNoise * strengthJitter * (strengthNoise >= 0.0D ? 1.30D : 0.75D);
        strengthScale = Math.max(0.22D, strengthScale);

        double directionOffset = randomSigned(windSeed, eventIndex, 0xBF58476D1CE4E5B9L)
                * tuning.windDirectionJitterDeg();
        double dirRad = Math.toRadians(tuning.windDirectionDeg() + directionOffset);
        return new WindSample(envelope, Math.cos(dirRad), Math.sin(dirRad), strengthScale);
    }

    private static double windSeedPhase(long seed) {
        long mixed = seed ^ (seed >>> 33) ^ 0x9E3779B97F4A7C15L;
        mixed ^= (mixed << 13);
        mixed ^= (mixed >>> 7);
        mixed ^= (mixed << 17);
        return (mixed & 0xFFFFL) / 65536.0D;
    }

    private static long windRegionSeed(ServerPhysicsTuning tuning, double wx, double wz) {
        int cellX = (int) Math.floor(wx / 24.0D);
        int cellZ = (int) Math.floor(wz / 24.0D);
        long mixed = 0x6A09E667F3BCC909L;
        mixed ^= (long) cellX * 0xBF58476D1CE4E5B9L;
        mixed ^= (long) cellZ * 0x94D049BB133111EBL;
        mixed ^= ((long) Math.floor(tuning.windDirectionDeg() * 8.0D)) * 0x9E3779B97F4A7C15L;
        return mixed;
    }

    private static double jitterScale(long seed, long eventIndex, long salt, double amount) {
        double clamped = Math.max(0.0D, Math.min(1.0D, amount));
        return Math.max(0.2D, 1.0D + randomSigned(seed, eventIndex, salt) * clamped);
    }

    private static double randomSigned(long seed, long index, long salt) {
        return random01(seed, index, salt) * 2.0D - 1.0D;
    }

    private static double random01(long seed, long index, long salt) {
        long mixed = seed ^ salt ^ (index * 0x9E3779B97F4A7C15L);
        mixed ^= (mixed >>> 30);
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= (mixed >>> 27);
        mixed *= 0x94D049BB133111EBL;
        mixed ^= (mixed >>> 31);
        return ((mixed >>> 11) & ((1L << 53) - 1)) * 0x1.0p-53D;
    }

    private static double linearTriangleEnvelope(double local, boolean rampingGust) {
        double triangle = local < 0.5D ? local * 2.0D : (1.0D - local) * 2.0D;
        if (!rampingGust) {
            return Math.max(0.0D, triangle);
        }
        double ramp = 0.45D + 0.55D * local;
        return Math.max(0.0D, triangle * ramp);
    }

    private static double doubleSwellEnvelope(double local, long eventIndex, boolean rampingGust, long windSeed) {
        double firstPeakT = 0.24D + randomSigned(windSeed, eventIndex, 0xC6A4A7935BD1E995L) * 0.08D;
        double dipT = 0.50D + randomSigned(windSeed, eventIndex, 0x165667B19E3779F9L) * 0.08D;
        double secondPeakT = 0.73D + randomSigned(windSeed, eventIndex, 0x85EBCA77C2B2AE63L) * 0.08D;
        firstPeakT = clamp(firstPeakT, 0.14D, 0.36D);
        dipT = clamp(dipT, firstPeakT + 0.10D, 0.68D);
        secondPeakT = clamp(secondPeakT, dipT + 0.08D, 0.90D);

        double firstPeak = rampingGust ? 0.42D : 0.66D;
        firstPeak += random01(windSeed, eventIndex, 0x27D4EB2F165667C5L) * 0.18D;
        double dip = 0.18D + random01(windSeed, eventIndex, 0x9E3779B185EBCA87L) * 0.26D;
        double secondPeak = 0.82D + random01(windSeed, eventIndex, 0xD1B54A32D192ED03L) * 0.38D;

        if (local < firstPeakT) {
            return lerp(0.0D, firstPeak, local / firstPeakT);
        }
        if (local < dipT) {
            return lerp(firstPeak, dip, (local - firstPeakT) / (dipT - firstPeakT));
        }
        if (local < secondPeakT) {
            return lerp(dip, secondPeak, (local - dipT) / (secondPeakT - dipT));
        }
        return lerp(secondPeak, 0.0D, (local - secondPeakT) / (1.0D - secondPeakT));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp(t, 0.0D, 1.0D);
    }

    private static double targetLength(Vec3 a, Vec3 b, ServerPhysicsTuning tuning) {
        return Math.max(EPS, RopeSagModel.physicsTargetLength(a, b, tuning.slack(), tuning.gravity()));
    }

    private static double closestPointOnSegment(
            Vec3 a, Vec3 b, Vec3 p, double[] out, double walkedBefore, double segmentLength, double totalLength) {
        double sx = b.x - a.x;
        double sy = b.y - a.y;
        double sz = b.z - a.z;
        double lenSqr = sx * sx + sy * sy + sz * sz;
        double t = 0.0D;
        if (lenSqr > EPS) {
            t = ((p.x - a.x) * sx + (p.y - a.y) * sy + (p.z - a.z) * sz) / lenSqr;
            t = clamp01(t);
        }
        double qx = a.x + sx * t;
        double qy = a.y + sy * t;
        double qz = a.z + sz * t;
        if (out != null) {
            out[0] = qx;
            out[1] = qy;
            out[2] = qz;
            out[3] = totalLength <= EPS ? t : (walkedBefore + segmentLength * t) / totalLength;
        }
        double dx = p.x - qx;
        double dy = p.y - qy;
        double dz = p.z - qz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    private static final class SegmentBoxContact {
        private double s;
        private double dx;
        private double dy;
        private double dz;
        private double distSqr;

        private static SegmentBoxContact compute(double ax, double ay, double az,
                double bx, double by, double bz, AABB box) {
            SegmentBoxContact contact = new SegmentBoxContact();
            double ux = bx - ax;
            double uy = by - ay;
            double uz = bz - az;
            double segLenSqr = ux * ux + uy * uy + uz * uz;
            double spx;
            double spy;
            double spz;
            if (segLenSqr < 1.0e-12D) {
                contact.s = 0.0D;
                spx = ax;
                spy = ay;
                spz = az;
            } else {
                double mx = (box.minX + box.maxX) * 0.5D - ax;
                double my = (box.minY + box.maxY) * 0.5D - ay;
                double mz = (box.minZ + box.maxZ) * 0.5D - az;
                contact.s = clamp01((mx * ux + my * uy + mz * uz) / segLenSqr);
                spx = ax + ux * contact.s;
                spy = ay + uy * contact.s;
                spz = az + uz * contact.s;

                for (int it = 0; it < 4; it++) {
                    double cpx = clamp(spx, box.minX, box.maxX);
                    double cpy = clamp(spy, box.minY, box.maxY);
                    double cpz = clamp(spz, box.minZ, box.maxZ);
                    double tx = cpx - ax;
                    double ty = cpy - ay;
                    double tz = cpz - az;
                    double ns = clamp01((tx * ux + ty * uy + tz * uz) / segLenSqr);
                    if (Math.abs(ns - contact.s) < 1.0e-6D) {
                        contact.s = ns;
                        spx = ax + ux * contact.s;
                        spy = ay + uy * contact.s;
                        spz = az + uz * contact.s;
                        break;
                    }
                    contact.s = ns;
                    spx = ax + ux * contact.s;
                    spy = ay + uy * contact.s;
                    spz = az + uz * contact.s;
                }
            }

            double cpx = clamp(spx, box.minX, box.maxX);
            double cpy = clamp(spy, box.minY, box.maxY);
            double cpz = clamp(spz, box.minZ, box.maxZ);
            contact.dx = spx - cpx;
            contact.dy = spy - cpy;
            contact.dz = spz - cpz;
            contact.distSqr = contact.dx * contact.dx + contact.dy * contact.dy + contact.dz * contact.dz;
            return contact;
        }
    }

    private record WindSample(double envelope, double dirX, double dirZ, double strengthScale) {
        private static final WindSample NONE = new WindSample(0.0D, 0.0D, 0.0D, 0.0D);
    }

    record Shape(Vec3 a, Vec3 b, double[] x, double[] y, double[] z,
            double[] lengths, double length, double targetLength) {
    }
}
