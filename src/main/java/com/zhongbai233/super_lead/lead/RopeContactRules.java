package com.zhongbai233.super_lead.lead;

/** Shared rigid-contact rules used by both client prediction and server authority. */
public final class RopeContactRules {
    public static final double TOP_JUMP_SPEED = 0.42D;
    public static final double SIDE_HARD_DEPTH_FRACTION = 0.65D;
    public static final double EXIT_INPUT_DOT = 0.05D;

    private RopeContactRules() {
    }

    public static boolean shouldBlockSideMotion(double inwardVelocity, double inputDot) {
        return inwardVelocity < 0.0D && inputDot <= EXIT_INPUT_DOT;
    }
}