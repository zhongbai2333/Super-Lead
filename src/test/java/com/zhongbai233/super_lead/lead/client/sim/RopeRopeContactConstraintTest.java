package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.lead.client.geom.RopeMath;
import com.zhongbai233.super_lead.lead.client.geom.SegmentPair;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RopeRopeContactConstraintTest {
    private static final Vec3 LOWER_A = new Vec3(0.0D, 0.0D, 0.0D);
    private static final Vec3 LOWER_B = new Vec3(4.0D, 0.0D, 0.0D);

        @Test
        void parallelOverlappingSegmentsChooseStableOverlapMidpoint() {
                SegmentPair pair = new SegmentPair();

                RopeMath.closestSegmentPoints(
                                0.0D, 0.1D, 0.0D, 1.0D, 0.1D, 0.0D,
                                0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, pair);

                assertEquals(0.50D, pair.s, 1.0e-12D,
                                "parallel contact must not bias correction toward the first endpoint");
                assertEquals(0.50D, pair.t, 1.0e-12D);
                assertEquals(0.01D, pair.distSqr, 1.0e-12D);
        }

        @Test
        void nearParallelPerturbationKeepsClosestPointNearSegmentMiddle() {
                SegmentPair above = new SegmentPair();
                SegmentPair below = new SegmentPair();
                RopeMath.closestSegmentPoints(
                                0.0D, 0.1D, 0.0D, 1.0D, 0.1D + 1.0e-10D, 0.0D,
                                0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, above);
                RopeMath.closestSegmentPoints(
                                0.0D, 0.1D, 0.0D, 1.0D, 0.1D - 1.0e-10D, 0.0D,
                                0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, below);

                assertEquals(0.50D, above.s, 1.0e-6D);
                assertEquals(0.50D, below.s, 1.0e-6D);
                assertTrue(Math.abs(above.s - below.s) < 1.0e-6D,
                                "sub-nanometre geometry noise must not flip contact between endpoints");
        }

    @Test
    void pairContactDistanceIsSymmetricAndIncludesVisibleThickness() {
        double forward = RopeSimulationCore.ropeContactDistance(
                0.10D, 0.04D, 0.05D,
                0.06D, 0.03D, 0.12D);
        double reverse = RopeSimulationCore.ropeContactDistance(
                0.06D, 0.03D, 0.12D,
                0.10D, 0.04D, 0.05D);

        assertEquals(0.128D, forward, 1.0e-12D,
                "visible half-thicknesses must use the calibrated rope-rope geometry scale");
        assertEquals(forward, reverse, 0.0D,
                "the same pair must not use a different target when solve order reverses");
    }

    @Test
    void pairContactDistanceAlsoRespectsPhysicalRadiiAndExplicitRepel() {
        assertEquals(0.12D, RopeSimulationCore.ropeContactDistance(
                0.01D, 0.07D, 0.04D,
                0.01D, 0.08D, 0.05D), 1.0e-12D);
        assertEquals(0.25D, RopeSimulationCore.ropeContactDistance(
                0.01D, 0.02D, 0.25D,
                0.01D, 0.02D, 0.10D), 1.0e-12D);
    }

    @Test
    void defaultRopesSeparateToAtLeastTheirPhysicalDiameter() {
        RopeSimulation lower = ropeAtHeight(0.0D, 11L);
        RopeSimulation upper = ropeAtHeight(0.075D, 29L);
        double target = upper.ropeContactDistance(lower);
        int middle = upper.nodeCount() / 2;

        assertEquals(0.072D, target, 1.0e-12D,
                "default physical diameter must use the calibrated 80% rope-rope scale");
        for (int i = 0; i < 12; i++) {
            upper.solveRopeRopeConstraints(List.of(lower));
        }

        assertTrue(upper.currentY(middle) - lower.currentY(middle) >= target - 1.0e-5D);
    }

    @Test
        void separatedRopeDoesNotTeleportAcrossTheNeighbour() {
        RopeSimulation lower = ropeAtHeight(0.0D, 41L);
                RopeSimulation crossing = ropeAtHeight(-2.0D, 43L);
                int middle = crossing.nodeCount() / 2;

        for (int i = 1; i < crossing.nodeCount() - 1; i++) {
                        crossing.y[i] = -0.50D;
                        crossing.yPrev[i] = -0.50D;
        }
                double before = crossing.currentY(middle);
        crossing.markBoundsDirty();
        crossing.solveRopeRopeConstraints(List.of(lower));

                assertTrue(Math.abs(crossing.currentY(middle) - before) < 0.10D,
                        "a rope outside the current contact range must not be teleported: before=" + before
                                + ", after=" + crossing.currentY(middle) + ", lower=" + lower.currentY(middle));
        }

        @Test
        void repeatedOverlapSolvesDoNotOvershootTheContactDistance() {
                RopeSimulation lower = ropeAtHeight(0.0D, 51L);
                RopeSimulation upper = ropeAtHeight(0.02D, 53L);
                int middle = upper.nodeCount() / 2;
                double target = upper.ropeContactDistance(lower);

                for (int i = 0; i < 24; i++) {
                        upper.solveRopeRopeConstraints(List.of(lower));
                        assertTrue(Double.isFinite(upper.currentY(middle)), "contact solve must remain finite");
                        assertTrue(Math.abs(upper.currentY(middle)) < 2.0D,
                                        "repeated contact solves must not create an unbounded position spike");
                }

                assertTrue(Math.abs(upper.currentY(middle) - lower.currentY(middle)) >= target - 1.0e-5D);
    }

    @Test
    void broadPhaseReachIncludesConfiguredGeometryAndFiniteMotion() {
        RopeSimulation rope = ropeAtHeight(0.0D, 47L);
        int middle = rope.nodeCount() / 2;
        double base = Math.max(
                Math.max(rope.tuning().halfThickness(), rope.tuning().ropeRadius())
                        * RopeSimulationCore.ROPE_ROPE_GEOMETRY_SCALE,
                rope.tuning().ropeRepelDistance());
        rope.vy[middle] = 10.0D;

        double reach = rope.ropeContactBroadPhaseReach();

        assertTrue(reach >= base);
        assertTrue(reach <= base + 0.50D + 1.0e-12D,
                "motion inflation must stay bounded in crowded scenes");
    }

        @Test
        void ropeContactDistanceSolveDoesNotPushCompressedSegmentsApart() {
                RopeSimulation tensileOnly = compressedUnpinnedRope(61L);
                RopeSimulation bilateral = compressedUnpinnedRope(67L);
                double[] before = tensileOnly.x.clone();

                tensileOnly.solveDistanceConstraints(0.5D, 0.0D, true, true);
                bilateral.solveDistanceConstraints(0.5D, 0.0D, true, false);

                for (int i = 0; i < tensileOnly.nodeCount(); i++) {
                        assertEquals(before[i], tensileOnly.x[i], 0.0D,
                                        "contact mode must not turn local compression into a separating impulse");
                }
                assertTrue(Math.abs(bilateral.x[1] - before[1]) > 1.0e-6D,
                                "the control solve must prove the same compressed chain moves in bilateral mode");
        }

        @Test
        void ropeContactVelocityProjectionRemovesOnlyReentry() {
                RopeSimulation rope = ropeAtHeight(0.0D, 71L);
                int middle = rope.nodeCount() / 2;
                rope.ropeContactNode[middle] = true;
                rope.ropeContactNormalY[middle] = 1.0D;
                rope.vx[middle] = 0.75D;
                rope.vy[middle] = -0.50D;
                rope.vz[middle] = -0.25D;

                rope.projectRopeContactVelocities();

                assertEquals(0.75D, rope.vx[middle], 0.0D, "tangential X velocity must survive");
                assertEquals(0.0D, rope.vy[middle], 1.0e-12D, "inward normal velocity must be removed");
                assertEquals(-0.25D, rope.vz[middle], 0.0D, "tangential Z velocity must survive");
        }

        @Test
        void ropeContactVelocityProjectionPreservesSeparation() {
                RopeSimulation rope = ropeAtHeight(0.0D, 73L);
                int middle = rope.nodeCount() / 2;
                rope.ropeContactNode[middle] = true;
                rope.ropeContactNormalY[middle] = 2.0D;
                rope.vy[middle] = 0.40D;

                rope.projectRopeContactVelocities();

                assertEquals(0.40D, rope.vy[middle], 0.0D,
                                "already-separating velocity must not be turned into sticky contact");
        }

        @Test
        void coMovingRopesDoNotLoseTangentialVelocity() {
                RopeSimulation lower = ropeAtHeight(0.0D, 79L);
                RopeSimulation upper = ropeAtHeight(0.05D, 83L);
                int segment = (upper.nodeCount() - 1) / 2;
                setUniformVelocity(lower, 0.60D, 0.0D, -0.20D);
                setUniformVelocity(upper, 0.60D, 0.0D, -0.20D);
                retainContact(upper, segment, lower, segment, 0.50D, 0.50D,
                                0.0D, 1.0D, 0.0D, 0.20D);

                upper.solveRopeContactVelocities(1.0D);

                assertEquals(0.60D, upper.vx[segment], 1.0e-12D,
                                "friction must use relative velocity, not damp shared world-space motion");
                assertEquals(-0.20D, upper.vz[segment], 1.0e-12D);
        }

        @Test
        void dynamicFrictionIsLoadLimitedAndCannotReverseSliding() {
                RopeSimulation lower = ropeAtHeight(0.0D, 89L);
                RopeSimulation upper = ropeAtHeight(0.05D, 97L);
                int segment = (upper.nodeCount() - 1) / 2;
                setUniformVelocity(lower, 0.0D, 0.0D, 0.0D);
                setUniformVelocity(upper, 1.0D, 0.0D, 0.0D);
                retainContact(upper, segment, lower, segment, 0.50D, 0.50D,
                                0.0D, 1.0D, 0.0D, 0.10D);

                upper.solveRopeContactVelocities(1.0D);

                double contactVelocity = (upper.vx[segment] + upper.vx[segment + 1]) * 0.50D;
                assertEquals(0.965D, contactVelocity, 1.0e-12D,
                                "default dynamic friction 0.35 times normal budget 0.10 must remove 0.035 speed");
                assertTrue(contactVelocity > 0.0D, "friction must never reverse the sliding direction");
        }

        @Test
        void narrowPhaseStoresPointCorrectionRatherThanInverseMassScaledLambda() {
                RopeSimulation lower = ropeAtHeight(0.0D, 103L);
                RopeSimulation upper = ropeAtHeight(0.05D, 107L);
                setUniformVelocity(lower, 0.0D, 0.0D, 0.0D);
                setUniformVelocity(upper, 1.0D, 0.0D, 0.0D);

                upper.solveRopeRopeConstraints(List.of(lower));
                int segment = firstRetainedContact(upper);
                assertTrue(segment >= 0, "the overlapping ropes must produce a retained contact");
                double pointCorrection = upper.ropeContactNormalCorrection[segment];
                assertTrue(pointCorrection > 0.0D && pointCorrection < upper.ropeContactDistance(lower),
                                "the retained load must be a point displacement, not an inverse-mass-scaled lambda");
        }

        @Test
        void rockingResistanceDampsEndpointOppositionWithoutMovingSegmentCenter() {
                RopeSimulation lower = ropeAtHeight(0.0D, 109L);
                RopeSimulation upper = ropeAtHeight(0.05D, 113L);
                int segment = (upper.nodeCount() - 1) / 2;
                setUniformVelocity(lower, 0.0D, 0.0D, 0.0D);
                setUniformVelocity(upper, 0.0D, 0.0D, 0.0D);
                upper.vy[segment] = -0.50D;
                upper.vy[segment + 1] = 0.50D;
                retainContact(upper, segment, lower, segment, 0.50D, 0.50D,
                                0.0D, 1.0D, 0.0D, 0.20D);

                upper.solveRopeContactVelocities(1.0D);

                assertEquals(0.0D, (upper.vy[segment] + upper.vy[segment + 1]) * 0.50D, 1.0e-12D,
                                "rocking resistance must preserve segment center velocity");
                assertEquals(0.93D, upper.vy[segment + 1] - upper.vy[segment], 1.0e-12D,
                                "default resistance 0.35 times load budget 0.20 must remove 0.07 differential speed");
        }

        @Test
        void cancellingContactNormalsDoNotFreezeFreeMotion() {
                RopeSimulation rope = ropeAtHeight(0.0D, 101L);
                int middle = rope.nodeCount() / 2;
                rope.ropeContactNode[middle] = true;
                rope.ropeContactNormalY[middle] = 1.0D;
                rope.ropeContactNormalY[middle] -= 1.0D;
                rope.vx[middle] = 0.45D;
                rope.vz[middle] = -0.30D;

                rope.projectRopeContactVelocities();

                assertEquals(0.45D, rope.vx[middle], 0.0D);
                assertEquals(-0.30D, rope.vz[middle], 0.0D,
                                "an ambiguous averaged normal must not zero unconstrained velocity");
        }

        private static void retainContact(RopeSimulation self, int segment,
                        RopeSimulation other, int otherSegment, double selfT, double otherT,
                        double nx, double ny, double nz, double normalCorrection) {
                self.ropeContactOther[segment] = other;
                self.ropeContactOtherSegment[segment] = otherSegment;
                self.ropeContactSelfT[segment] = selfT;
                self.ropeContactOtherT[segment] = otherT;
                self.ropeContactPairNormalX[segment] = nx;
                self.ropeContactPairNormalY[segment] = ny;
                self.ropeContactPairNormalZ[segment] = nz;
                self.ropeContactNormalCorrection[segment] = normalCorrection;
        }

        private static void setUniformVelocity(RopeSimulation rope, double x, double y, double z) {
                for (int i = 0; i < rope.nodeCount(); i++) {
                        rope.vx[i] = x;
                        rope.vy[i] = y;
                        rope.vz[i] = z;
                }
        }

        private static int firstRetainedContact(RopeSimulation rope) {
                for (int i = 0; i < rope.ropeContactOther.length; i++) {
                        if (rope.ropeContactOther[i] != null) {
                                return i;
                        }
                }
                return -1;
        }

        private static RopeSimulation compressedUnpinnedRope(long seed) {
                RopeSimulation rope = ropeAtHeight(0.0D, seed);
                int last = rope.nodeCount() - 1;
                for (int i = 0; i <= last; i++) {
                        rope.x[i] = i * 0.25D;
                        rope.y[i] = 0.0D;
                        rope.z[i] = 0.0D;
                        rope.pinned[i] = false;
                        rope.lambdaDistance[Math.min(i, last - 1)] = 0.0D;
                }
                return rope;
        }

    private static RopeSimulation ropeAtHeight(double y, long seed) {
        Vec3 a = LOWER_A.add(0.0D, y, 0.0D);
        Vec3 b = LOWER_B.add(0.0D, y, 0.0D);
        return new RopeSimulation(a, b, seed, RopeTuning.localDefaults().withTopology(0.5D, 32));
    }
}
