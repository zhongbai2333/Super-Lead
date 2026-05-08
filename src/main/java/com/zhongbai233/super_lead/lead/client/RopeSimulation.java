package com.zhongbai233.super_lead.lead.client;

import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 客户端 Verlet 绳：多段，重力 + 阻尼 + 距离约束 + 带半径的方块 AABB 碰撞。
 *
 * 碰撞核心不是单点采样：绳子中心线有一个小半径，方块碰撞会先 inflate(radius)，
 * 然后分别做节点点位推出与线段-vs-inflated-AABB 求交。这样角、方块边缘、两个方块
 * 连接处都会有"厚度"，不会因为线段恰好从格子边缘穿过而漏检。
 */
public final class RopeSimulation {
    private static final double TARGET_SEGMENT_LENGTH = 0.30D;
    private static final int MIN_SEGMENTS = 4;
    private static final int MAX_SEGMENTS = 32;
    private static final int ITERATIONS = 12;
    private static final double GRAVITY = -0.065D;
    private static final double DAMPING = 0.80D;
    private static final double LOOSE_SLACK_FACTOR = 1.10D;
    private static final double TIGHT_SLACK_FACTOR = 1.03D;
    private static final double VERTICAL_LINE_THRESHOLD = 0.045D;
    private static final double ROPE_RADIUS = 0.045D;
    private static final double ROPE_REPEL_DISTANCE = 0.13D;
    private static final double LAYER_CAPTURE_HEIGHT = ROPE_RADIUS * 0.75D;
    private static final double COLLISION_EPS = 0.015D;
    private static final double SEGMENT_CORNER_PUSH_EPS = ROPE_RADIUS * 0.65D;
    private static final double SEGMENT_TOP_SUPPORT_EPS = ROPE_RADIUS * 1.8D;
    private static final double SEGMENT_PUSH_MAX_SCALE = 3.0D;
    private static final double CONTACT_FREEZE_SPEED_SQR = 0.08D * 0.08D;
    private static final double CONTACT_VELOCITY_KEEP = 0.18D;
    private static final double TOP_SUPPORT_SCAN_EPS = COLLISION_EPS * 3.0D;
    private static final long UNINIT = Long.MIN_VALUE;
    private static final AABB[] EMPTY_BOXES = new AABB[0];

    private final int segments;
    private final int nodes;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private final double[] px;
    private final double[] py;
    private final double[] pz;
    private final double[] rx;
    private final double[] ry;
    private final double[] rz;
    private final boolean[] contactNodes;
    private final boolean[] supportNodes;

    private final Vec3 separationAxis;
    private final Vec3 collisionAxis;
    private final double layerPriority;
    private final double maxSlackFactor;
    private final HashMap<Long, AABB[]> collisionCache = new HashMap<>();
    private final double[] candDelta = new double[6];
    private final double[] candDistance = new double[6];
    private final int[] candAxis = new int[6];

    private long lastSteppedTick = UNINIT;
    private long lastTouchTick = UNINIT;
    private long lastBlockHash;
    private boolean blockHashInit;
    private int settledTicks;
    private static final int SETTLE_THRESHOLD_TICKS = 4;
    private static final double SETTLE_MOTION_SQR = 1.0e-5D;

    public RopeSimulation(Vec3 a, Vec3 b) {
        this(a, b, 0L, false);
    }

    public RopeSimulation(Vec3 a, Vec3 b, long seed, boolean tight) {
        this.segments = segmentCount(a, b);
        this.nodes = segments + 1;
        this.x = new double[nodes];
        this.y = new double[nodes];
        this.z = new double[nodes];
        this.px = new double[nodes];
        this.py = new double[nodes];
        this.pz = new double[nodes];
        this.rx = new double[nodes];
        this.ry = new double[nodes];
        this.rz = new double[nodes];
        this.contactNodes = new boolean[nodes];
        this.supportNodes = new boolean[nodes];
        this.maxSlackFactor = tight ? TIGHT_SLACK_FACTOR : LOOSE_SLACK_FACTOR;
        Vec3 dir = b.subtract(a);
        Vec3 dirNorm = dir.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : dir.normalize();
        Vec3 side = dirNorm.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1e-6) {
            side = dirNorm.cross(new Vec3(1, 0, 0));
        }
        if (side.lengthSqr() < 1e-6) {
            side = new Vec3(1, 0, 0);
        } else {
            side = side.normalize();
        }
        Vec3 up = side.cross(dirNorm);
        if (up.lengthSqr() < 1e-6) {
            up = new Vec3(0, 1, 0);
        } else {
            up = up.normalize();
        }
        double angle = ((seed ^ (seed >>> 32)) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        this.separationAxis = side.scale(Math.cos(angle)).add(up.scale(Math.sin(angle))).normalize();
        this.collisionAxis = stableCollisionAxis(seed);
        this.layerPriority = stableLayerPriority(seed);

        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double nx = a.x + dir.x * t;
            double ny = a.y + dir.y * t;
            double nz = a.z + dir.z * t;
            x[i] = nx;
            y[i] = ny;
            z[i] = nz;
            rx[i] = nx;
            ry[i] = ny;
            rz[i] = nz;

            double s = Math.sin(Math.PI * t);
            double kickLat = 0.06D * s;
            double kickDown = 0.035D * s;
            px[i] = nx - separationAxis.x * kickLat;
            py[i] = ny + kickDown;
            pz[i] = nz - separationAxis.z * kickLat;
        }
        pinPreviousEndpoints();
    }

    public static RopeSimulation visualLeash(Vec3 a, Vec3 b) {
        RopeSimulation sim = new RopeSimulation(a, b);
        sim.setCatenary(a, b, 0.08D);
        return sim;
    }

    private static int segmentCount(Vec3 a, Vec3 b) {
        return Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, (int) Math.ceil(a.distanceTo(b) / TARGET_SEGMENT_LENGTH)));
    }

    public int nodeCount() {
        return nodes;
    }

    public boolean matchesLength(Vec3 a, Vec3 b) {
        return segmentCount(a, b) == segments;
    }

    public void updateVisualLeash(Vec3 a, Vec3 b, long currentTick, float smoothing) {
        lastTouchTick = currentTick;
        double sag = Math.min(0.55D, a.distanceTo(b) * 0.055D);
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double targetX = a.x + (b.x - a.x) * t;
            double targetY = a.y + (b.y - a.y) * t - Math.sin(Math.PI * t) * sag;
            double targetZ = a.z + (b.z - a.z) * t;

            rx[i] = x[i];
            ry[i] = y[i];
            rz[i] = z[i];
            x[i] += (targetX - x[i]) * smoothing;
            y[i] += (targetY - y[i]) * smoothing;
            z[i] += (targetZ - z[i]) * smoothing;
            px[i] = x[i];
            py[i] = y[i];
            pz[i] = z[i];
        }
    }

    private void setCatenary(Vec3 a, Vec3 b, double sagFactor) {
        double sag = Math.min(0.55D, a.distanceTo(b) * sagFactor);
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) segments;
            double nx = a.x + (b.x - a.x) * t;
            double ny = a.y + (b.y - a.y) * t - Math.sin(Math.PI * t) * sag;
            double nz = a.z + (b.z - a.z) * t;
            x[i] = nx;
            y[i] = ny;
            z[i] = nz;
            px[i] = nx;
            py[i] = ny;
            pz[i] = nz;
            rx[i] = nx;
            ry[i] = ny;
            rz[i] = nz;
        }
    }

    public boolean stepUpTo(Level level, Vec3 a, Vec3 b, long currentTick) {
        lastTouchTick = currentTick;
        if (isNearlyVertical(a, b)) {
            setStraightLine(a, b);
            lastSteppedTick = currentTick;
            return true;
        }
        if (lastSteppedTick == UNINIT) {
            lastSteppedTick = currentTick - 1;
        }
        long delta = currentTick - lastSteppedTick;
        if (delta <= 0) {
            return false;
        }
        if (delta > 2) {
            delta = 2;
        }

        // Cache: skip simulation while rope is settled and surrounding blocks are unchanged.
        long blockHash = computeBlockHash(level);
        if (!blockHashInit) {
            lastBlockHash = blockHash;
            blockHashInit = true;
        } else if (blockHash != lastBlockHash) {
            lastBlockHash = blockHash;
            settledTicks = 0;
        }
        if (settledTicks >= SETTLE_THRESHOLD_TICKS) {
            // Freeze: keep render positions stable. Reset previous-position to current to kill velocity.
            for (int i = 0; i < nodes; i++) {
                rx[i] = x[i];
                ry[i] = y[i];
                rz[i] = z[i];
                px[i] = x[i];
                py[i] = y[i];
                pz[i] = z[i];
            }
            lastSteppedTick = currentTick;
            return false;
        }

        for (int i = 0; i < delta; i++) {
            step(level, a, b);
        }
        // Measure motion since previous tick (rx/ry/rz hold pre-step positions).
        double maxMotionSqr = 0.0D;
        for (int i = 1; i < nodes - 1; i++) {
            double dx = x[i] - rx[i];
            double dy = y[i] - ry[i];
            double dz = z[i] - rz[i];
            double m = dx * dx + dy * dy + dz * dz;
            if (m > maxMotionSqr) maxMotionSqr = m;
        }
        if (maxMotionSqr < SETTLE_MOTION_SQR) {
            settledTicks++;
        } else {
            settledTicks = 0;
        }
        lastSteppedTick = currentTick;
        return true;
    }

    private long computeBlockHash(Level level) {
        // Hash all block-state identities in the rope's bounding region (inflated by ROPE_RADIUS+1).
        // BlockState references are interned; hashCode() is identity-stable.
        double minX = x[0], maxX = x[0], minY = y[0], maxY = y[0], minZ = z[0], maxZ = z[0];
        for (int i = 1; i < nodes; i++) {
            if (x[i] < minX) minX = x[i]; else if (x[i] > maxX) maxX = x[i];
            if (y[i] < minY) minY = y[i]; else if (y[i] > maxY) maxY = y[i];
            if (z[i] < minZ) minZ = z[i]; else if (z[i] > maxZ) maxZ = z[i];
        }
        int bx0 = (int) Math.floor(minX - ROPE_RADIUS) - 1;
        int bx1 = (int) Math.floor(maxX + ROPE_RADIUS) + 1;
        int by0 = (int) Math.floor(minY - ROPE_RADIUS) - 1;
        int by1 = (int) Math.floor(maxY + ROPE_RADIUS) + 1;
        int bz0 = (int) Math.floor(minZ - ROPE_RADIUS) - 1;
        int bz1 = (int) Math.floor(maxZ + ROPE_RADIUS) + 1;
        long hash = 1469598103934665603L;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int by = by0; by <= by1; by++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int bx = bx0; bx <= bx1; bx++) {
                    cursor.set(bx, by, bz);
                    BlockState state = level.getBlockState(cursor);
                    hash = (hash ^ System.identityHashCode(state)) * 1099511628211L;
                    hash ^= cursor.asLong();
                }
            }
        }
        return hash;
    }

    public long lastTouchTick() {
        return lastTouchTick;
    }

    public boolean isSettled() {
        return settledTicks >= SETTLE_THRESHOLD_TICKS;
    }

    public void repelFrom(RopeSimulation other) {
        // 两根都已冻结时，每 tick 的微小互推会让"冻结的位置"再被位移一点，
        // 视觉上就是低概率抖动。互相都 settled 直接跳过。
        if (this.isSettled() && other.isSettled()) {
            return;
        }
        if (!boundsOverlap(other, ROPE_REPEL_DISTANCE)) {
            return;
        }
        for (int i = 0; i < nodes - 1; i++) {
            for (int j = 0; j < other.nodes - 1; j++) {
                SegmentPair pair = closestSegmentPoints(other, i, j);
                if (pair.distanceSqr >= ROPE_REPEL_DISTANCE * ROPE_REPEL_DISTANCE) {
                    continue;
                }

                Vec3 normal;
                double dist;
                if (pair.distanceSqr < 1.0e-8D) {
                    normal = stableRepelNormal(other);
                    if (normal.lengthSqr() < 1.0e-8D) {
                        normal = this.separationAxis;
                    } else {
                        normal = normal.normalize();
                    }
                    dist = 0.0D;
                } else {
                    dist = Math.sqrt(pair.distanceSqr);
                    normal = new Vec3(pair.dx / dist, pair.dy / dist, pair.dz / dist);
                }
                normal = applyLayerRule(other, i, j, pair, normal);

                double endpointFade = endpointFade(i, pair.s) * other.endpointFade(j, pair.t);
                if (endpointFade <= 1.0e-4D) {
                    continue;
                }
                double push = (ROPE_REPEL_DISTANCE - dist) * 0.22D * endpointFade;
                applySegmentPush(i, pair.s, normal.x * push, normal.y * push, normal.z * push);
                other.applySegmentPush(j, pair.t, -normal.x * push, -normal.y * push, -normal.z * push);
            }
        }
    }

    public void settleExternalForces(Level level) {
        collisionCache.clear();
        clearContactState();
        resolveCollisions(level);
        stabilizeContactVelocities();
        refreshSupportNodes(level);
    }

    private double endpointFade(int segment, double t) {
        double ropeT = (segment + t) / segments;
        return Math.min(1.0D, Math.min(ropeT, 1.0D - ropeT) / 0.08D);
    }

    private boolean boundsOverlap(RopeSimulation other, double margin) {
        double minXa = x[0], maxXa = x[0];
        double minYa = y[0], maxYa = y[0];
        double minZa = z[0], maxZa = z[0];
        for (int i = 1; i < nodes; i++) {
            double vx = x[i]; if (vx < minXa) minXa = vx; else if (vx > maxXa) maxXa = vx;
            double vy = y[i]; if (vy < minYa) minYa = vy; else if (vy > maxYa) maxYa = vy;
            double vz = z[i]; if (vz < minZa) minZa = vz; else if (vz > maxZa) maxZa = vz;
        }
        double minXb = other.x[0], maxXb = other.x[0];
        double minYb = other.y[0], maxYb = other.y[0];
        double minZb = other.z[0], maxZb = other.z[0];
        for (int i = 1; i < other.nodes; i++) {
            double vx = other.x[i]; if (vx < minXb) minXb = vx; else if (vx > maxXb) maxXb = vx;
            double vy = other.y[i]; if (vy < minYb) minYb = vy; else if (vy > maxYb) maxYb = vy;
            double vz = other.z[i]; if (vz < minZb) minZb = vz; else if (vz > maxZb) maxZb = vz;
        }
        return maxXa + margin >= minXb && maxXb + margin >= minXa
                && maxYa + margin >= minYb && maxYb + margin >= minYa
                && maxZa + margin >= minZb && maxZb + margin >= minZa;
    }

    private Vec3 applyLayerRule(RopeSimulation other, int segment, int otherSegment, SegmentPair pair, Vec3 normal) {
        double verticalSeparation = Math.abs(pair.dy);
        if (verticalSeparation > LAYER_CAPTURE_HEIGHT) {
            return normal;
        }

        // 整绳稳定优先级：同一对绳子，无论段如何旋转，谁在上始终一致。
        // 仅靠每绳启动时确定的 layerPriority 作裁决，避免逐段几何带来的层级翻转抖动。
        double tie = this.layerPriority - other.layerPriority;
        int preference;
        if (Math.abs(tie) < 1.0e-6D) {
            // 极罕见的完全平局：用引用身份做最终稳定 fallback。
            preference = System.identityHashCode(this) > System.identityHashCode(other) ? 1 : -1;
        } else {
            preference = tie > 0.0D ? 1 : -1;
        }

        double capture = 1.0D - verticalSeparation / LAYER_CAPTURE_HEIGHT;
        double targetY = 0.34D * capture * preference;
        double y = normal.y * preference < targetY * preference ? targetY : normal.y;
        Vec3 layered = new Vec3(normal.x * 0.65D, y, normal.z * 0.65D);
        if (layered.lengthSqr() < 1.0e-8D) {
            return normal;
        }
        return layered.normalize();
    }

    private static Vec3 stableCollisionAxis(long seed) {
        long hash = seed * 0x9E3779B97F4A7C15L + 0xBF58476D1CE4E5B9L;
        double angle = ((hash ^ (hash >>> 33)) & 0xFFFFL) / 65535.0D * Math.PI * 2.0D;
        return new Vec3(Math.cos(angle), 0.35D, Math.sin(angle)).normalize();
    }

    private static double stableLayerPriority(long seed) {
        long hash = seed * 0x94D049BB133111EBL + 0x9E3779B97F4A7C15L;
        return ((hash ^ (hash >>> 33)) & 0xFFFFL) / 65535.0D;
    }

    private Vec3 stableRepelNormal(RopeSimulation other) {
        Vec3 normal = this.collisionAxis.subtract(other.collisionAxis);
        if (normal.lengthSqr() < 1.0e-8D) {
            normal = this.separationAxis.subtract(other.separationAxis);
        }
        return normal.lengthSqr() < 1.0e-8D ? this.collisionAxis : normal.normalize();
    }

    private SegmentPair closestSegmentPoints(RopeSimulation other, int i, int j) {
        double ax = x[i];
        double ay = y[i];
        double az = z[i];
        double bx = x[i + 1];
        double by = y[i + 1];
        double bz = z[i + 1];
        double cx = other.x[j];
        double cy = other.y[j];
        double cz = other.z[j];
        double dx = other.x[j + 1];
        double dy = other.y[j + 1];
        double dz = other.z[j + 1];

        double ux = bx - ax;
        double uy = by - ay;
        double uz = bz - az;
        double vx = dx - cx;
        double vy = dy - cy;
        double vz = dz - cz;
        double wx = ax - cx;
        double wy = ay - cy;
        double wz = az - cz;

        double aa = dot(ux, uy, uz, ux, uy, uz);
        double bb = dot(ux, uy, uz, vx, vy, vz);
        double cc = dot(vx, vy, vz, vx, vy, vz);
        double dd = dot(ux, uy, uz, wx, wy, wz);
        double ee = dot(vx, vy, vz, wx, wy, wz);
        double denom = aa * cc - bb * bb;

        double s;
        double t;
        if (aa < 1.0e-8D && cc < 1.0e-8D) {
            s = 0.0D;
            t = 0.0D;
        } else if (aa < 1.0e-8D) {
            s = 0.0D;
            t = clamp(ee / cc);
        } else if (cc < 1.0e-8D) {
            t = 0.0D;
            s = clamp(-dd / aa);
        } else {
            s = denom < 1.0e-8D ? 0.0D : clamp((bb * ee - cc * dd) / denom);
            t = clamp((bb * s + ee) / cc);
            s = clamp((bb * t - dd) / aa);
            t = clamp((bb * s + ee) / cc);
        }

        double px = ax + ux * s;
        double py = ay + uy * s;
        double pz = az + uz * s;
        double qx = cx + vx * t;
        double qy = cy + vy * t;
        double qz = cz + vz * t;
        double ox = px - qx;
        double oy = py - qy;
        double oz = pz - qz;
        return new SegmentPair(s, t, ox, oy, oz, ox * ox + oy * oy + oz * oz);
    }

    private void applySegmentPush(int segment, double t, double dx, double dy, double dz) {
        moveWeightedNode(segment, 1.0D - t, dx, dy, dz);
        moveWeightedNode(segment + 1, t, dx, dy, dz);
    }

    private void moveWeightedNode(int node, double weight, double dx, double dy, double dz) {
        if (node <= 0 || node >= nodes - 1 || weight <= 1.0e-4D) {
            return;
        }
        // Terrain support wins over rope layering; otherwise grounded nodes can bounce after collision resolution.
        double my = supportNodes[node] ? 0.0D : dy * weight;
        moveNodePreservingVelocity(node, dx * weight, my, dz * weight);
    }

    private static double dot(double ax, double ay, double az, double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz;
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public void disturb(Vec3 dir, double strength) {
        for (int i = 1; i < nodes - 1; i++) {
            double s = Math.sin(Math.PI * i / (double) (nodes - 1)) * strength;
            px[i] -= dir.x * s;
            py[i] -= dir.y * s;
            pz[i] -= dir.z * s;
        }
    }

    private void step(Level level, Vec3 a, Vec3 b) {
        collisionCache.clear();
        for (int i = 0; i < nodes; i++) {
            rx[i] = x[i];
            ry[i] = y[i];
            rz[i] = z[i];
        }
        clearContactState();

        for (int i = 1; i < nodes - 1; i++) {
            double vx = (x[i] - px[i]) * DAMPING;
            double vy = (y[i] - py[i]) * DAMPING;
            double vz = (z[i] - pz[i]) * DAMPING;
            double nx = x[i] + vx;
            double ny = y[i] + vy + GRAVITY;
            double nz = z[i] + vz;
            px[i] = x[i];
            py[i] = y[i];
            pz[i] = z[i];
            x[i] = nx;
            y[i] = ny;
            z[i] = nz;
        }

        pinEndpoints(a, b);

        double targetLen = a.distanceTo(b) * slackFactor(a, b) / segments;
        for (int it = 0; it < ITERATIONS; it++) {
            satisfyDistanceConstraints(targetLen);
            straightenMostlyVertical(a, b);
            pinEndpoints(a, b);
            resolveCollisions(level);
            pinEndpoints(a, b);
        }
        stabilizeContactVelocities();
        refreshSupportNodes(level);
    }

    private void clearContactState() {
        for (int i = 0; i < nodes; i++) {
            contactNodes[i] = false;
            supportNodes[i] = false;
        }
    }

    private void stabilizeContactVelocities() {
        for (int i = 1; i < nodes - 1; i++) {
            if (!contactNodes[i]) {
                continue;
            }

            double vx = x[i] - px[i];
            double vy = y[i] - py[i];
            double vz = z[i] - pz[i];
            double speedSqr = vx * vx + vy * vy + vz * vz;
            if (speedSqr <= CONTACT_FREEZE_SPEED_SQR) {
                px[i] = x[i];
                py[i] = y[i];
                pz[i] = z[i];
            } else {
                px[i] = x[i] - vx * CONTACT_VELOCITY_KEEP;
                py[i] = y[i] - vy * CONTACT_VELOCITY_KEEP;
                pz[i] = z[i] - vz * CONTACT_VELOCITY_KEEP;
            }
        }
    }

    private void satisfyDistanceConstraints(double targetLen) {
        for (int i = 0; i < nodes - 1; i++) {
            double dx = x[i + 1] - x[i];
            double dy = y[i + 1] - y[i];
            double dz = z[i + 1] - z[i];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) {
                continue;
            }
            double diff = (len - targetLen) / len * 0.5D;
            double cx = dx * diff;
            double cy = dy * diff;
            double cz = dz * diff;
            if (i != 0) {
                x[i] += cx;
                y[i] += cy;
                z[i] += cz;
            }
            if (i + 1 != nodes - 1) {
                x[i + 1] -= cx;
                y[i + 1] -= cy;
                z[i + 1] -= cz;
            }
        }
    }

    private void resolveCollisions(Level level) {
        for (int i = 1; i < nodes - 1; i++) {
            resolveNodeCollision(level, i);
        }
        for (int i = 0; i < nodes - 1; i++) {
            resolveSegmentCollision(level, i, i + 1);
        }
        for (int i = 1; i < nodes - 1; i++) {
            resolveNodeCollision(level, i);
        }
    }

    private void refreshSupportNodes(Level level) {
        for (int i = 1; i < nodes - 1; i++) {
            if (supportNodes[i] || isNearTopSupport(level, x[i], y[i], z[i])) {
                supportNodes[i] = true;
            }
        }
    }

    private boolean isNearTopSupport(Level level, double wx, double wy, double wz) {
        int minX = (int) Math.floor(wx - ROPE_RADIUS) - 1;
        int maxX = (int) Math.floor(wx + ROPE_RADIUS) + 1;
        int minY = (int) Math.floor(wy - ROPE_RADIUS) - 1;
        int maxY = (int) Math.floor(wy + ROPE_RADIUS) + 1;
        int minZ = (int) Math.floor(wz - ROPE_RADIUS) - 1;
        int maxZ = (int) Math.floor(wz + ROPE_RADIUS) + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double xzMargin = ROPE_RADIUS + COLLISION_EPS;
        double maxAboveTop = ROPE_RADIUS + TOP_SUPPORT_SCAN_EPS;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    cursor.set(bx, by, bz);
                    for (AABB box : collisionBoxes(level, cursor)) {
                        double dy = wy - box.maxY;
                        if (dy < -COLLISION_EPS || dy > maxAboveTop) {
                            continue;
                        }
                        if (wx >= box.minX - xzMargin && wx <= box.maxX + xzMargin
                                && wz >= box.minZ - xzMargin && wz <= box.maxZ + xzMargin) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void resolveNodeCollision(Level level, int node) {
        // 多跑两次：节点被一个盒子推出后可能正好进入另一个相邻盒子的 inflated 区域。
        for (int pass = 0; pass < 2; pass++) {
            boolean moved = false;
            int minX = (int) Math.floor(x[node] - ROPE_RADIUS) - 1;
            int maxX = (int) Math.floor(x[node] + ROPE_RADIUS) + 1;
            int minY = (int) Math.floor(y[node] - ROPE_RADIUS) - 1;
            int maxY = (int) Math.floor(y[node] + ROPE_RADIUS) + 1;
            int minZ = (int) Math.floor(z[node] - ROPE_RADIUS) - 1;
            int maxZ = (int) Math.floor(z[node] + ROPE_RADIUS) + 1;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            for (int bx = minX; bx <= maxX; bx++) {
                for (int by = minY; by <= maxY; by++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        cursor.set(bx, by, bz);
                        for (AABB box : collisionBoxes(level, cursor)) {
                            if (isEndpointSupportBox(node, box)) {
                                continue;
                            }
                            AABB inflated = box.inflate(ROPE_RADIUS);
                            if (!containsInclusive(inflated, x[node], y[node], z[node])) {
                                continue;
                            }
                            Push push = chooseTopSupportPush(inflated, node);
                            if (push == null) {
                                push = choosePointPush(level, inflated, x[node], y[node], z[node]);
                            }
                            if (push != null) {
                                applyPush(node, push);
                                moved = true;
                            }
                        }
                    }
                }
            }

            if (!moved) {
                return;
            }
        }
    }

    private void resolveSegmentCollision(Level level, int a, int b) {
        Vec3 from = new Vec3(x[a], y[a], z[a]);
        Vec3 to = new Vec3(x[b], y[b], z[b]);
        AABB segmentBounds = new AABB(
                Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z),
                Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z)).inflate(ROPE_RADIUS + COLLISION_EPS);
        int minX = (int) Math.floor(segmentBounds.minX) - 1;
        int maxX = (int) Math.floor(segmentBounds.maxX) + 1;
        int minY = (int) Math.floor(segmentBounds.minY) - 1;
        int maxY = (int) Math.floor(segmentBounds.maxY) + 1;
        int minZ = (int) Math.floor(segmentBounds.minZ) - 1;
        int maxZ = (int) Math.floor(segmentBounds.maxZ) + 1;

        SegmentHit best = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    cursor.set(bx, by, bz);
                    for (AABB box : collisionBoxes(level, cursor)) {
                        if (isEndpointSupportBox(a, b, box)) {
                            continue;
                        }
                        SegmentHit hit = intersectSegmentAabb(from, to, box.inflate(ROPE_RADIUS));
                        if (hit != null && (best == null || hit.t < best.t)) {
                            best = hit;
                        }
                    }
                }
            }
        }

        if (best == null) {
            return;
        }

        // 线段碰撞点通常不正好落在绳子节点上。只推某一端会让距离约束下一轮再拉回，
        // 在方块边角处尤其容易产生硬折角；这里优先把整段按最小分离向量平移出碰撞盒。
        applySegmentCollisionPush(a, b, best);
    }

    private void applySegmentCollisionPush(int a, int b, SegmentHit hit) {
        double wa = 1.0D - hit.t;
        double wb = hit.t;
        boolean moveA = a > 0 && a < nodes - 1;
        boolean moveB = b > 0 && b < nodes - 1;
        if (moveA && moveB) {
            // 线段不是关节时，把相邻两个节点整体平移出碰撞盒，避免单点推出造成尖锐折角。
            moveCollisionNode(a, 1.0D, hit.x(), hit.y(), hit.z());
            moveCollisionNode(b, 1.0D, hit.x(), hit.y(), hit.z());
            return;
        }

        if (moveA) {
            moveCollisionNode(a, Math.min(SEGMENT_PUSH_MAX_SCALE, 1.0D / Math.max(wa, 1.0e-6D)), hit.x(), hit.y(), hit.z());
        } else if (moveB) {
            moveCollisionNode(b, Math.min(SEGMENT_PUSH_MAX_SCALE, 1.0D / Math.max(wb, 1.0e-6D)), hit.x(), hit.y(), hit.z());
        }
    }

    private void moveCollisionNode(int node, double scale, double dx, double dy, double dz) {
        contactNodes[node] = true;
        double mx = dx * scale;
        double my = dy * scale;
        double mz = dz * scale;
        if (my > 0.0D) {
            supportNodes[node] = true;
        }
        x[node] += mx;
        y[node] += my;
        z[node] += mz;
        if (Math.abs(mx) > 1.0e-9D) {
            px[node] = x[node];
        }
        if (Math.abs(my) > 1.0e-9D) {
            py[node] = y[node];
        }
        if (Math.abs(mz) > 1.0e-9D) {
            pz[node] = z[node];
        }
    }

    private Push chooseTopSupportPush(AABB box, int node) {
        double pushUp = box.maxY + COLLISION_EPS - y[node];
        if (pushUp > 0.0D
                && y[node] <= box.maxY + COLLISION_EPS
                && py[node] >= box.maxY - SEGMENT_TOP_SUPPORT_EPS) {
            return new Push(0, pushUp, 0);
        }
        return null;
    }

    private Push choosePointPush(Level level, AABB box, double px, double py, double pz) {
        candAxis[0] = 0; candDelta[0] = box.minX - COLLISION_EPS - px; candDistance[0] = px - box.minX;
        candAxis[1] = 0; candDelta[1] = box.maxX + COLLISION_EPS - px; candDistance[1] = box.maxX - px;
        candAxis[2] = 1; candDelta[2] = box.minY - COLLISION_EPS - py; candDistance[2] = py - box.minY;
        candAxis[3] = 1; candDelta[3] = box.maxY + COLLISION_EPS - py; candDistance[3] = box.maxY - py;
        candAxis[4] = 2; candDelta[4] = box.minZ - COLLISION_EPS - pz; candDistance[4] = pz - box.minZ;
        candAxis[5] = 2; candDelta[5] = box.maxZ + COLLISION_EPS - pz; candDistance[5] = box.maxZ - pz;

        for (int i = 0; i < 5; i++) {
            int min = i;
            for (int j = i + 1; j < 6; j++) {
                if (candDistance[j] < candDistance[min]) {
                    min = j;
                }
            }
            if (min != i) {
                double td = candDelta[i]; candDelta[i] = candDelta[min]; candDelta[min] = td;
                double tdist = candDistance[i]; candDistance[i] = candDistance[min]; candDistance[min] = tdist;
                int ta = candAxis[i]; candAxis[i] = candAxis[min]; candAxis[min] = ta;
            }
        }

        for (int i = 0; i < 6; i++) {
            int axis = candAxis[i];
            double delta = candDelta[i];
            double nx = px;
            double ny = py;
            double nz = pz;
            if (axis == 0) {
                nx += delta;
            } else if (axis == 1) {
                ny += delta;
            } else {
                nz += delta;
            }
            if (!isRopeCenterInsideCollision(level, nx, ny, nz)) {
                return switch (axis) {
                    case 0 -> new Push(delta, 0, 0);
                    case 1 -> new Push(0, delta, 0);
                    default -> new Push(0, 0, delta);
                };
            }
        }
        // 完全被实心块团包住时，优先向上找出口，避免在水平相邻方块之间来回抖。
        return new Push(0, COLLISION_EPS + box.maxY - py, 0);
    }

    private void applyPush(int node, Push push) {
        contactNodes[node] = true;
        if (push.y > 0.0D) {
            supportNodes[node] = true;
        }
        x[node] += push.x;
        y[node] += push.y;
        z[node] += push.z;
        // 清掉被碰撞轴上的速度，避免下一帧 Verlet 又把节点带回方块里。
        if (push.x != 0) {
            px[node] = x[node];
        }
        if (push.y != 0) {
            py[node] = y[node];
        }
        if (push.z != 0) {
            pz[node] = z[node];
        }
    }

    private double slackFactor(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double dist = a.distanceTo(b);
        if (dist < 1e-6) {
            return 1.0D;
        }
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // 近乎竖直时不加额外长度，允许绳子真正拉直；水平分量足够大时再恢复 10% 松弛。
        double t = Math.min(1.0D, horizontal / (dist * 0.45D));
        return 1.0D + (this.maxSlackFactor - 1.0D) * t;
    }

    private void moveNodePreservingVelocity(int node, double dx, double dy, double dz) {
        x[node] += dx;
        y[node] += dy;
        z[node] += dz;
        px[node] += dx;
        py[node] += dy;
        pz[node] += dz;
    }

    private boolean isEndpointSupportBox(int node, AABB box) {
        // 只在端点真的位于碰撞盒内部时才跳过支撑盒（例如栅栏柱）。
        // 方块面锚点会沿法线外推 EXTRUDE，若用 inflated shell 判定，顶面锚点会刚好落在盒边界，
        // 导致第一节绳子把自身方块整块跳过，进而丢掉顶面碰撞。
        if (node == 1 && containsInclusive(box, x[0], y[0], z[0])) {
            return true;
        }
        return node == nodes - 2 && containsInclusive(box, x[nodes - 1], y[nodes - 1], z[nodes - 1]);
    }

    private boolean isEndpointSupportBox(int segmentA, int segmentB, AABB box) {
        if (segmentA == 0 && containsInclusive(box, x[0], y[0], z[0])) {
            return true;
        }
        return segmentB == nodes - 1 && containsInclusive(box, x[nodes - 1], y[nodes - 1], z[nodes - 1]);
    }

    private static SegmentHit intersectSegmentAabb(Vec3 from, Vec3 to, AABB box) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        double xEntry, xExit;
        if (Math.abs(dx) < 1e-9) {
            if (from.x < box.minX || from.x > box.maxX) return null;
            xEntry = 0.0D; xExit = 1.0D;
        } else {
            double t1 = (box.minX - from.x) / dx;
            double t2 = (box.maxX - from.x) / dx;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            xEntry = t1; xExit = t2;
        }

        double yEntry, yExit;
        if (Math.abs(dy) < 1e-9) {
            if (from.y < box.minY || from.y > box.maxY) return null;
            yEntry = 0.0D; yExit = 1.0D;
        } else {
            double t1 = (box.minY - from.y) / dy;
            double t2 = (box.maxY - from.y) / dy;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            yEntry = t1; yExit = t2;
        }

        double zEntry, zExit;
        if (Math.abs(dz) < 1e-9) {
            if (from.z < box.minZ || from.z > box.maxZ) return null;
            zEntry = 0.0D; zExit = 1.0D;
        } else {
            double t1 = (box.minZ - from.z) / dz;
            double t2 = (box.maxZ - from.z) / dz;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            zEntry = t1; zExit = t2;
        }

        double tMin = Math.max(0.0D, Math.max(xEntry, Math.max(yEntry, zEntry)));
        double tMax = Math.min(1.0D, Math.min(xExit, Math.min(yExit, zExit)));
        if (tMin > tMax || tMax < 0.0D || tMin > 1.0D) return null;

        double sxMin = Math.min(from.x, to.x);
        double sxMax = Math.max(from.x, to.x);
        double syMin = Math.min(from.y, to.y);
        double syMax = Math.max(from.y, to.y);
        double szMin = Math.min(from.z, to.z);
        double szMax = Math.max(from.z, to.z);

        double pushX = separationPush(sxMin, sxMax, box.minX, box.maxX);
        double pushY = separationPush(syMin, syMax, box.minY, box.maxY);
        double pushZ = separationPush(szMin, szMax, box.minZ, box.maxZ);
        double ax = Math.abs(pushX);
        double ay = Math.abs(pushY);
        double az = Math.abs(pushZ);
        double min = Math.min(ax, Math.min(ay, az));

        if (pushY > 0.0D && ay <= min + SEGMENT_TOP_SUPPORT_EPS) {
            double t = (tMin + tMax) * 0.5D;
            return new SegmentHit(t, 0.0D, pushY, 0.0D);
        }

        // 边/角附近多个轴的分离距离接近时，合成斜向推出，避免本帧选 X、下帧选 Z 的抖动。
        double outX = ax <= min + SEGMENT_CORNER_PUSH_EPS ? pushX : 0.0D;
        double outY = ay <= min + SEGMENT_CORNER_PUSH_EPS ? pushY : 0.0D;
        double outZ = az <= min + SEGMENT_CORNER_PUSH_EPS ? pushZ : 0.0D;
        double t = (tMin + tMax) * 0.5D;
        return new SegmentHit(t, outX, outY, outZ);
    }

    private static double separationPush(double minA, double maxA, double minB, double maxB) {
        double toMin = minB - maxA - COLLISION_EPS;
        double toMax = maxB - minA + COLLISION_EPS;
        return Math.abs(toMin) < Math.abs(toMax) ? toMin : toMax;
    }

    private static boolean containsInclusive(AABB box, double x, double y, double z) {
        return x >= box.minX && x <= box.maxX
                && y >= box.minY && y <= box.maxY
                && z >= box.minZ && z <= box.maxZ;
    }

    private boolean isRopeCenterInsideCollision(Level level, double wx, double wy, double wz) {
        int minX = (int) Math.floor(wx - ROPE_RADIUS) - 1;
        int maxX = (int) Math.floor(wx + ROPE_RADIUS) + 1;
        int minY = (int) Math.floor(wy - ROPE_RADIUS) - 1;
        int maxY = (int) Math.floor(wy + ROPE_RADIUS) + 1;
        int minZ = (int) Math.floor(wz - ROPE_RADIUS) - 1;
        int maxZ = (int) Math.floor(wz + ROPE_RADIUS) + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    cursor.set(bx, by, bz);
                    for (AABB box : collisionBoxes(level, cursor)) {
                        if (containsInclusive(box.inflate(ROPE_RADIUS), wx, wy, wz)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private AABB[] collisionBoxes(Level level, BlockPos pos) {
        long key = pos.asLong();
        AABB[] cached = collisionCache.get(key);
        if (cached != null) {
            return cached;
        }
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            collisionCache.put(key, EMPTY_BOXES);
            return EMPTY_BOXES;
        }
        List<AABB> raw = shape.toAabbs();
        int n = raw.size();
        AABB[] boxes = new AABB[n];
        double ox = pos.getX();
        double oy = pos.getY();
        double oz = pos.getZ();
        for (int i = 0; i < n; i++) {
            boxes[i] = raw.get(i).move(ox, oy, oz);
        }
        collisionCache.put(key, boxes);
        return boxes;
    }

    private void pinEndpoints(Vec3 a, Vec3 b) {
        x[0] = a.x;
        y[0] = a.y;
        z[0] = a.z;
        x[nodes - 1] = b.x;
        y[nodes - 1] = b.y;
        z[nodes - 1] = b.z;
    }

    private static boolean isNearlyVertical(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double dist = a.distanceTo(b);
        if (dist < 1.0e-6D) {
            return false;
        }
        return Math.sqrt(dx * dx + dz * dz) / dist <= VERTICAL_LINE_THRESHOLD;
    }

    private void straightenMostlyVertical(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double dist = a.distanceTo(b);
        if (dist < 1.0e-6D) {
            return;
        }

        double verticalness = 1.0D - Math.min(1.0D, Math.sqrt(dx * dx + dz * dz) / (dist * 0.28D));
        if (verticalness <= 0.0D) {
            return;
        }

        double pull = 0.55D * verticalness;
        for (int i = 1; i < nodes - 1; i++) {
            double t = i / (double) (nodes - 1);
            double tx = a.x + (b.x - a.x) * t;
            double ty = a.y + (b.y - a.y) * t;
            double tz = a.z + (b.z - a.z) * t;
            x[i] += (tx - x[i]) * pull;
            y[i] += (ty - y[i]) * pull;
            z[i] += (tz - z[i]) * pull;
            px[i] += (tx - px[i]) * pull;
            py[i] += (ty - py[i]) * pull;
            pz[i] += (tz - pz[i]) * pull;
        }
    }

    private void setStraightLine(Vec3 a, Vec3 b) {
        for (int i = 0; i < nodes; i++) {
            double t = i / (double) (nodes - 1);
            double nx = a.x + (b.x - a.x) * t;
            double ny = a.y + (b.y - a.y) * t;
            double nz = a.z + (b.z - a.z) * t;
            rx[i] = x[i];
            ry[i] = y[i];
            rz[i] = z[i];
            x[i] = nx;
            y[i] = ny;
            z[i] = nz;
            px[i] = nx;
            py[i] = ny;
            pz[i] = nz;
        }
    }

    private void pinPreviousEndpoints() {
        px[0] = x[0];
        py[0] = y[0];
        pz[0] = z[0];
        px[nodes - 1] = x[nodes - 1];
        py[nodes - 1] = y[nodes - 1];
        pz[nodes - 1] = z[nodes - 1];
    }

    public Vec3 nodeAt(int i, float partialTick) {
        return new Vec3(
                rx[i] + (x[i] - rx[i]) * partialTick,
                ry[i] + (y[i] - ry[i]) * partialTick,
                rz[i] + (z[i] - rz[i]) * partialTick);
    }

    private record Push(double x, double y, double z) {
    }

    private record SegmentHit(double t, double x, double y, double z) {
    }

    private record SegmentPair(double s, double t, double dx, double dy, double dz, double distanceSqr) {
    }
}
