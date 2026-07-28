package com.zhongbai233.super_lead.lead.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, detached rope geometry used by one client picking frame. */
public final class RopePickingFrame {
    public enum Source {
        DYNAMIC,
        STATIC,
        TRANSPARENT_FALLBACK
    }

    private final long tick;
    private final List<Rope> ropes;

    public RopePickingFrame(long tick, List<Rope> ropes) {
        this.tick = tick;
        if (ropes == null || ropes.isEmpty()) {
            this.ropes = List.of();
            return;
        }
        ArrayList<Rope> copies = new ArrayList<>(ropes.size());
        for (Rope rope : ropes) {
            if (rope != null && rope.nodeCount() >= 2) {
            // Rope is already immutable and owns its coordinate arrays. Do not
            // clone every coordinate array a second time just to detach an
            // immutable frame from an immutable rope.
            copies.add(rope);
            }
        }
        this.ropes = List.copyOf(copies);
    }

    public long tick() {
        return tick;
    }

    public List<Rope> ropes() {
        return ropes;
    }

    public boolean isEmpty() {
        return ropes.isEmpty();
    }

    /** A polyline whose coordinates, cumulative lengths and bounds are owned by this object. */
    public static final class Rope {
        private final UUID connectionId;
        private final Source source;
        private final double[] x;
        private final double[] y;
        private final double[] z;
        private final double[] lengths;
        private final Bounds bounds;

        public Rope(UUID connectionId, Source source, double[] x, double[] y, double[] z) {
            this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
            this.source = Objects.requireNonNull(source, "source");
            if (x == null || y == null || z == null || x.length != y.length || x.length != z.length
                    || x.length < 2) {
                throw new IllegalArgumentException("A rope polyline needs at least two matching coordinate arrays");
            }
            this.x = x.clone();
            this.y = y.clone();
            this.z = z.clone();
            this.lengths = new double[x.length];
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < x.length; i++) {
                if (!Double.isFinite(this.x[i]) || !Double.isFinite(this.y[i]) || !Double.isFinite(this.z[i])) {
                    throw new IllegalArgumentException("Rope coordinates must be finite");
                }
                if (i > 0) {
                    double dx = this.x[i] - this.x[i - 1];
                    double dy = this.y[i] - this.y[i - 1];
                    double dz = this.z[i] - this.z[i - 1];
                    lengths[i] = lengths[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
                }
                minX = Math.min(minX, this.x[i]);
                minY = Math.min(minY, this.y[i]);
                minZ = Math.min(minZ, this.z[i]);
                maxX = Math.max(maxX, this.x[i]);
                maxY = Math.max(maxY, this.y[i]);
                maxZ = Math.max(maxZ, this.z[i]);
            }
            this.bounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        public UUID connectionId() { return connectionId; }
        public Source source() { return source; }
        public int nodeCount() { return x.length; }
        public int segmentCount() { return x.length - 1; }
        public double x(int node) { return x[node]; }
        public double y(int node) { return y[node]; }
        public double z(int node) { return z[node]; }
        public double length(int node) { return lengths[node]; }
        public double totalLength() { return lengths[lengths.length - 1]; }
        public Bounds bounds() { return bounds; }
        public double[] xCopy() { return x.clone(); }
        public double[] yCopy() { return y.clone(); }
        public double[] zCopy() { return z.clone(); }
        public double[] lengthsCopy() { return lengths.clone(); }
    }

    public record Bounds(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Invalid bounds");
            }
        }

        Bounds inflate(double amount) {
            return new Bounds(minX - amount, minY - amount, minZ - amount,
                    maxX + amount, maxY + amount, maxZ + amount);
        }

        boolean intersects(Bounds other) {
            return maxX >= other.minX && minX <= other.maxX
                    && maxY >= other.minY && minY <= other.maxY
                    && maxZ >= other.minZ && minZ <= other.maxZ;
        }
    }
}