package com.zhongbai233.super_lead.lead.client.sim;

/**
 * Per-rope trend scheduler. Physics step size remains fixed; only the number of
 * client ticks between solves changes.
 */
public final class RopeActivityScheduler {
    private static final double EMA_RISE = 0.55D;
    private static final double EMA_FALL = 0.16D;
    private static final int QUIET_SAMPLES_TO_DOWN_SHIFT = 3;

    private RopeActivityScheduler() {
    }

    public enum Tier {
        HOT(1, 0.68D),
        ACTIVE(2, 0.34D),
        COOLING(4, 0.12D),
        IDLE(8, 0.0D);

        private final int interval;
        private final double entryThreshold;

        Tier(int interval, double entryThreshold) {
            this.interval = interval;
            this.entryThreshold = entryThreshold;
        }

        public int interval() {
            return interval;
        }
    }

    public record State(Tier tier, double activity, int quietSamples, long lastSampleTick) {
        public static State initial(long tick) {
            return new State(Tier.HOT, 1.0D, 0, tick);
        }
    }

    public static State update(State previous, long tick, double sample, boolean forceHot) {
        if (!Double.isFinite(sample)) {
            if (forceHot || previous == null) {
                return State.initial(tick);
            }
            return previous;
        }
        double clamped = clamp01(sample);
        if (previous == null || tick < previous.lastSampleTick()) {
            return forceHot ? State.initial(tick) : new State(tierFor(clamped), clamped, 0, tick);
        }
        if (previous.lastSampleTick() == tick) {
            if (!forceHot && clamped <= previous.activity()) {
                return previous;
            }
        }
        double alpha = clamped > previous.activity() ? EMA_RISE : EMA_FALL;
        double activity = forceHot ? 1.0D : previous.activity() + (clamped - previous.activity()) * alpha;
        Tier desired = forceHot ? Tier.HOT : tierFor(activity);
        if (desired.ordinal() < previous.tier().ordinal()) {
            // More active: jump upward immediately, including IDLE -> HOT.
            return new State(desired, activity, 0, tick);
        }
        if (desired.ordinal() == previous.tier().ordinal()) {
            return new State(previous.tier(), activity, 0, tick);
        }
        int quiet = previous.quietSamples() + 1;
        if (quiet < QUIET_SAMPLES_TO_DOWN_SHIFT) {
            return new State(previous.tier(), activity, quiet, tick);
        }
        Tier next = Tier.values()[previous.tier().ordinal() + 1];
        return new State(next, activity, 0, tick);
    }

    static Tier tierFor(double activity) {
        if (!Double.isFinite(activity)) {
            return Tier.HOT;
        }
        double value = clamp01(activity);
        for (Tier tier : Tier.values()) {
            if (value >= tier.entryThreshold) {
                return tier;
            }
        }
        return Tier.IDLE;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}