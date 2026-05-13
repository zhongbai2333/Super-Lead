package com.zhongbai233.super_lead.lead;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Computes visually separated rope endpoints for multiple connections sharing
 * the
 * same two anchors. The canonical anchor point is still provided by
 * {@link LeadAnchor#attachmentPoint(Level)}; this helper only adds a small
 * deterministic in-face offset for rendering / client-side simulation.
 */
public final class LeadEndpointLayout {
    private static final double GRID_DIMENSION_THRESHOLD = 0.42D;
    private static final double FACE_MARGIN = 0.16D;
    private static final double FACE_MARGIN_FRACTION = 0.22D;

    private LeadEndpointLayout() {
    }

    public record Endpoints(Vec3 from, Vec3 to) {
    }

    public static Endpoints endpoints(Level level, LeadConnection connection, List<LeadConnection> allConnections) {
        Placement placement = placementFor(connection, allConnections);
        if (placement.count() <= 1) {
            return new Endpoints(connection.from().attachmentPoint(level), connection.to().attachmentPoint(level));
        }
        return new Endpoints(
                attachmentPoint(level, connection.from(), placement),
                attachmentPoint(level, connection.to(), placement));
    }

    public static Vec3 attachmentPoint(Level level, LeadAnchor anchor, LeadConnection connection,
            List<LeadConnection> allConnections) {
        Placement placement = placementFor(connection, allConnections);
        if (placement.count() <= 1) {
            return anchor.attachmentPoint(level);
        }
        return attachmentPoint(level, anchor, placement);
    }

    private static Vec3 attachmentPoint(Level level, LeadAnchor anchor, Placement placement) {
        Vec3 base = anchor.attachmentPoint(level);
        if (level == null || anchor == null || placement.count() <= 1) {
            return base;
        }

        BlockState state = level.getBlockState(anchor.pos());
        if (LeadAnchor.isKnotBlock(state)) {
            return base;
        }

        VoxelShape shape = state.getShape(level, anchor.pos());
        AABB bounds = shape.isEmpty() ? new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D) : shape.bounds();
        FaceExtents extents = faceExtents(anchor.face(), bounds);
        LocalOffset local = localOffset(placement.index(), placement.count(), extents.uLength(), extents.vLength());
        Vec3 worldOffset = worldOffset(anchor.face(), local);
        return base.add(worldOffset);
    }

    private static Placement placementFor(LeadConnection connection, List<LeadConnection> allConnections) {
        if (connection == null || allConnections == null || allConnections.size() <= 1) {
            return Placement.SINGLE;
        }
        ArrayList<UUID> ids = new ArrayList<>();
        boolean foundSelf = false;
        for (LeadConnection candidate : allConnections) {
            if (candidate != null && sameUndirectedAnchorPair(connection, candidate)) {
                ids.add(candidate.id());
                if (candidate.id().equals(connection.id())) {
                    foundSelf = true;
                }
            }
        }
        if (!foundSelf) {
            ids.add(connection.id());
        }
        if (ids.size() <= 1) {
            return Placement.SINGLE;
        }
        ids.sort(Comparator.naturalOrder());
        int index = ids.indexOf(connection.id());
        if (index < 0) {
            index = 0;
        }
        return new Placement(index, ids.size());
    }

    private static boolean sameUndirectedAnchorPair(LeadConnection a, LeadConnection b) {
        return (a.from().equals(b.from()) && a.to().equals(b.to()))
                || (a.from().equals(b.to()) && a.to().equals(b.from()));
    }

    private static FaceExtents faceExtents(Direction face, AABB bounds) {
        return switch (face) {
            case DOWN, UP -> new FaceExtents(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ);
            case NORTH, SOUTH -> new FaceExtents(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY);
            case WEST, EAST -> new FaceExtents(bounds.maxZ - bounds.minZ, bounds.maxY - bounds.minY);
        };
    }

    private static LocalOffset localOffset(int index, int count, double uLength, double vLength) {
        if (count <= 1) {
            return LocalOffset.ZERO;
        }
        if (uLength <= 1.0e-5D && vLength <= 1.0e-5D) {
            return LocalOffset.ZERO;
        }

        boolean grid = uLength >= GRID_DIMENSION_THRESHOLD && vLength >= GRID_DIMENSION_THRESHOLD;
        if (!grid) {
            return lineOffset(index, count, uLength, vLength);
        }

        double aspect = uLength / Math.max(vLength, 1.0e-6D);
        aspect = Math.max(0.35D, Math.min(2.85D, aspect));
        int columns = Math.max(1, Math.min(count, (int) Math.ceil(Math.sqrt(count * aspect))));
        int rows = Math.max(1, (count + columns - 1) / columns);
        int row = Math.min(rows - 1, index / columns);
        int column = index % columns;
        int rowStart = row * columns;
        int rowCount = Math.max(1, Math.min(columns, count - rowStart));

        double usableU = usableLength(uLength);
        double usableV = usableLength(vLength);
        double u = 0.0D;
        double v = 0.0D;
        if (rowCount > 1) {
            double centerColumn = (rowCount - 1) * 0.5D;
            u = usableU * ((column - centerColumn) / (rowCount - 1));
        }
        if (rows > 1) {
            double centerRow = (rows - 1) * 0.5D;
            v = usableV * ((row - centerRow) / (rows - 1));
        }
        return new LocalOffset(u, v);
    }

    private static LocalOffset lineOffset(int index, int count, double uLength, double vLength) {
        double t = count <= 1 ? 0.0D : index / (double) (count - 1) - 0.5D;
        if (uLength >= vLength) {
            return new LocalOffset(usableLength(uLength) * t, 0.0D);
        }
        return new LocalOffset(0.0D, usableLength(vLength) * t);
    }

    private static double usableLength(double length) {
        if (length <= 1.0e-5D) {
            return 0.0D;
        }
        double margin = Math.min(FACE_MARGIN, length * FACE_MARGIN_FRACTION);
        return Math.max(0.0D, length - margin * 2.0D);
    }

    private static Vec3 worldOffset(Direction face, LocalOffset local) {
        return switch (face) {
            case DOWN, UP -> new Vec3(local.u(), 0.0D, local.v());
            case NORTH, SOUTH -> new Vec3(local.u(), local.v(), 0.0D);
            case WEST, EAST -> new Vec3(0.0D, local.v(), local.u());
        };
    }

    private record Placement(int index, int count) {
        private static final Placement SINGLE = new Placement(0, 1);
    }

    private record FaceExtents(double uLength, double vLength) {
    }

    private record LocalOffset(double u, double v) {
        private static final LocalOffset ZERO = new LocalOffset(0.0D, 0.0D);
    }
}