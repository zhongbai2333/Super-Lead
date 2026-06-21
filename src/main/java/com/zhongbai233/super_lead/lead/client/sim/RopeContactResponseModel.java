package com.zhongbai233.super_lead.lead.client.sim;

import com.zhongbai233.super_lead.lead.physics.RopeSagModel;

/**
 * Tension-aware contact response shared by rope physics and rendering.
 * <p>
 * The rope uses two complementary contact behaviours:
 * <ul>
 * <li><strong>Flexible response</strong> for loose ropes: local nodes may be pushed
 * by entity volumes and server contact pulses.</li>
 * <li><strong>Tension response</strong> for taut ropes: physics nodes stay stable
 * while rendering shows a whole-span V whose endpoints remain pinned.</li>
 * </ul>
 *
 * <p>
 * Keeping these weights in one place makes the intended algorithm visible and
 * avoids scattering slack/tautness heuristics through the solver.
 */
final class RopeContactResponseModel {
    private static final double EPS = 1.0e-6D;

    private RopeContactResponseModel() {
    }

    static Weights weights(double slack) {
        double tension = clamp01(RopeSagModel.tautProjectionWeight(slack));
        double flexible = 1.0D - tension;
        // Entity-volume displacement is deliberately quadratic: a rope needs usable
        // free length before a whole player AABB should sculpt it into a broad U.
        double entityVolume = flexible * flexible;
        return new Weights(tension, flexible, entityVolume);
    }

    static double spanVWeight(double nodeT, double contactT) {
        double contact = clamp01(contactT);
        double leftDenom = Math.max(contact, EPS);
        double rightDenom = Math.max(1.0D - contact, EPS);
        double weight = nodeT <= contact
                ? nodeT / leftDenom
                : (1.0D - nodeT) / rightDenom;
        return clamp01(weight);
    }

    static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }

    record Weights(double tension, double flexible, double entityVolume) {
        boolean hasTension() {
            return tension > EPS;
        }

        boolean hasFlexible() {
            return flexible > EPS;
        }

        boolean hasEntityVolume() {
            return entityVolume > EPS;
        }
    }
}
