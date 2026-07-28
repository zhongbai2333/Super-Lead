package com.zhongbai233.super_lead.lead.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Uniform 3D spatial hash over immutable rope-frame segments. */
public final class ClientRopePickingIndex {
    public static final double DEFAULT_CELL_SIZE = 4.0D;
    private static final long MAX_VISITED_CELLS = 65_536L;

    private final RopePickingFrame frame;
    private final double cellSize;
    private final Map<Cell, List<SegmentRef>> cells = new HashMap<>();
    private final List<SegmentRef> overflowSegments = new ArrayList<>();

    public ClientRopePickingIndex(RopePickingFrame frame) {
        this(frame, DEFAULT_CELL_SIZE);
    }

    ClientRopePickingIndex(RopePickingFrame frame, double cellSize) {
        if (frame == null || !(cellSize > 0.0D) || !Double.isFinite(cellSize)) {
            throw new IllegalArgumentException("A frame and positive finite cell size are required");
        }
        this.frame = frame;
        this.cellSize = cellSize;
        build();
    }

    public RopePickingFrame frame() {
        return frame;
    }

    public List<Candidate> queryRay(double ox, double oy, double oz,
            double dx, double dy, double dz, double maxDistance, double radius) {
        return queryRay(ox, oy, oz, dx, dy, dz, maxDistance, radius, ignored -> true);
    }

    public List<Candidate> queryRay(double ox, double oy, double oz,
            double dx, double dy, double dz, double maxDistance, double radius,
            Predicate<UUID> connectionFilter) {
        if (!(maxDistance >= 0.0D) || !(radius >= 0.0D) || connectionFilter == null) {
            return List.of();
        }
        if (!Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || !Double.isFinite(maxDistance) || !Double.isFinite(radius)) {
            return List.of();
        }
        double directionLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(directionLength > 1.0e-12D) || !Double.isFinite(directionLength)) {
            return List.of();
        }
        dx /= directionLength;
        dy /= directionLength;
        dz /= directionLength;
        double ex = ox + dx * maxDistance;
        double ey = oy + dy * maxDistance;
        double ez = oz + dz * maxDistance;
        RopePickingFrame.Bounds query = new RopePickingFrame.Bounds(
                Math.min(ox, ex), Math.min(oy, ey), Math.min(oz, ez),
                Math.max(ox, ex), Math.max(oy, ey), Math.max(oz, ez)).inflate(radius);

        Set<SegmentRef> unique = new HashSet<>();
        if (cellCount(query) <= MAX_VISITED_CELLS) {
            visitCells(query, ref -> addIfCandidate(unique, ref, query, connectionFilter));
            for (SegmentRef ref : overflowSegments) {
                addIfCandidate(unique, ref, query, connectionFilter);
            }
        } else {
            for (RopePickingFrame.Rope rope : frame.ropes()) {
                if (!connectionFilter.test(rope.connectionId()) || !rope.bounds().intersects(query)) {
                    continue;
                }
                for (int segment = 0; segment < rope.segmentCount(); segment++) {
                    SegmentRef ref = new SegmentRef(rope, segment, segmentBounds(rope, segment));
                    addIfCandidate(unique, ref, query, connectionFilter);
                }
            }
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        ArrayList<Candidate> out = new ArrayList<>(unique.size());
        double radiusSqr = radius * radius;
        for (SegmentRef ref : unique) {
            Candidate candidate = closestToRay(ref, ox, oy, oz, dx, dy, dz, maxDistance);
            if (candidate.distanceSqr() <= radiusSqr) {
                out.add(candidate);
            }
        }
        out.sort((left, right) -> {
            int order = Double.compare(left.distanceSqr(), right.distanceSqr());
            if (order != 0) return order;
            order = left.connectionId().compareTo(right.connectionId());
            return order != 0 ? order : Integer.compare(left.segment(), right.segment());
        });
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static void addIfCandidate(Set<SegmentRef> unique, SegmentRef ref,
            RopePickingFrame.Bounds query, Predicate<UUID> connectionFilter) {
        if (connectionFilter.test(ref.rope().connectionId()) && ref.bounds().intersects(query)) {
            unique.add(ref);
        }
    }

    private void build() {
        for (RopePickingFrame.Rope rope : frame.ropes()) {
            for (int segment = 0; segment < rope.segmentCount(); segment++) {
                RopePickingFrame.Bounds bounds = segmentBounds(rope, segment);
                SegmentRef ref = new SegmentRef(rope, segment, bounds);
                if (cellCount(bounds) > MAX_VISITED_CELLS) {
                    overflowSegments.add(ref);
                } else {
                    visitCellCoordinates(bounds,
                            cell -> cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(ref));
                }
            }
        }
    }

    private void visitCells(RopePickingFrame.Bounds bounds, java.util.function.Consumer<SegmentRef> visitor) {
        visitCellCoordinates(bounds, cell -> {
            List<SegmentRef> refs = cells.get(cell);
            if (refs != null) {
                refs.forEach(visitor);
            }
        });
    }

    private void visitCellCoordinates(RopePickingFrame.Bounds bounds, java.util.function.Consumer<Cell> visitor) {
        int minX = cell(bounds.minX());
        int minY = cell(bounds.minY());
        int minZ = cell(bounds.minZ());
        int maxX = cell(bounds.maxX());
        int maxY = cell(bounds.maxY());
        int maxZ = cell(bounds.maxZ());
        for (int x = minX;; x++) {
            for (int y = minY;; y++) {
                for (int z = minZ;; z++) {
                    visitor.accept(new Cell(x, y, z));
                    if (z == maxZ) break;
                }
                if (y == maxY) break;
            }
            if (x == maxX) break;
        }
    }

    private int cell(double coordinate) {
        return (int) Math.floor(coordinate / cellSize);
    }

    private long cellCount(RopePickingFrame.Bounds bounds) {
        long x = (long) cell(bounds.maxX()) - cell(bounds.minX()) + 1L;
        long y = (long) cell(bounds.maxY()) - cell(bounds.minY()) + 1L;
        long z = (long) cell(bounds.maxZ()) - cell(bounds.minZ()) + 1L;
        if (x <= 0L || y <= 0L || z <= 0L || x > MAX_VISITED_CELLS
                || y > MAX_VISITED_CELLS || z > MAX_VISITED_CELLS) {
            return Long.MAX_VALUE;
        }
        long xy = x * y;
        return xy > MAX_VISITED_CELLS || z > MAX_VISITED_CELLS / xy
                ? Long.MAX_VALUE : xy * z;
    }

    private static RopePickingFrame.Bounds segmentBounds(RopePickingFrame.Rope rope, int segment) {
        return new RopePickingFrame.Bounds(
                Math.min(rope.x(segment), rope.x(segment + 1)),
                Math.min(rope.y(segment), rope.y(segment + 1)),
                Math.min(rope.z(segment), rope.z(segment + 1)),
                Math.max(rope.x(segment), rope.x(segment + 1)),
                Math.max(rope.y(segment), rope.y(segment + 1)),
                Math.max(rope.z(segment), rope.z(segment + 1)));
    }

    private static Candidate closestToRay(SegmentRef ref,
            double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
        RopePickingFrame.Rope rope = ref.rope();
        int segment = ref.segment();
        double ax = rope.x(segment);
        double ay = rope.y(segment);
        double az = rope.z(segment);
        double ux = rope.x(segment + 1) - ax;
        double uy = rope.y(segment + 1) - ay;
        double uz = rope.z(segment + 1) - az;
        double vx = dx * maxDistance;
        double vy = dy * maxDistance;
        double vz = dz * maxDistance;
        double wx = ax - ox;
        double wy = ay - oy;
        double wz = az - oz;
        double a = ux * ux + uy * uy + uz * uz;
        double b = ux * vx + uy * vy + uz * vz;
        double c = vx * vx + vy * vy + vz * vz;
        double d = ux * wx + uy * wy + uz * wz;
        double e = vx * wx + vy * wy + vz * wz;
        double denominator = a * c - b * b;
        double segmentT;
        double rayT;
        if (a <= 1.0e-18D) {
            segmentT = 0.0D;
            rayT = c <= 1.0e-18D ? 0.0D : clamp(e / c);
        } else if (c <= 1.0e-18D) {
            rayT = 0.0D;
            segmentT = clamp(-d / a);
        } else {
            segmentT = denominator <= 1.0e-18D ? 0.0D : clamp((b * e - c * d) / denominator);
            rayT = clamp((b * segmentT + e) / c);
            segmentT = clamp((b * rayT - d) / a);
        }
        double px = ax + ux * segmentT;
        double py = ay + uy * segmentT;
        double pz = az + uz * segmentT;
        double qx = ox + vx * rayT;
        double qy = oy + vy * rayT;
        double qz = oz + vz * rayT;
        double rx = px - qx;
        double ry = py - qy;
        double rz = pz - qz;
        double segmentLength = rope.length(segment + 1) - rope.length(segment);
        double total = rope.totalLength();
        double ropeT = total > 1.0e-12D
                ? (rope.length(segment) + segmentLength * segmentT) / total
                : segment / (double) Math.max(1, rope.segmentCount());
        double ropeT0 = total > 1.0e-12D ? rope.length(segment) / total
            : segment / (double) Math.max(1, rope.segmentCount());
        double ropeT1 = total > 1.0e-12D ? rope.length(segment + 1) / total
            : (segment + 1) / (double) Math.max(1, rope.segmentCount());
        return new Candidate(rope.connectionId(), rope.source(), segment,
            rx * rx + ry * ry + rz * rz, ropeT, ropeT0, ropeT1, px, py, pz,
                ax, ay, az, ax + ux, ay + uy, az + uz);
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record Candidate(UUID connectionId, RopePickingFrame.Source source, int segment,
            double distanceSqr, double ropeT, double ropeT0, double ropeT1,
            double x, double y, double z,
            double ax, double ay, double az,
            double bx, double by, double bz) {
    }

    private record Cell(int x, int y, int z) {
    }

    private record SegmentRef(RopePickingFrame.Rope rope, int segment, RopePickingFrame.Bounds bounds) {
    }
}