package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record LeadConnection(UUID id, LeadAnchor from, LeadAnchor to, LeadKind kind, int power, int tier, int extractAnchor) {
    public static final Codec<LeadConnection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(LeadConnection::id),
                    LeadAnchor.CODEC.fieldOf("from").forGetter(LeadConnection::from),
                    LeadAnchor.CODEC.fieldOf("to").forGetter(LeadConnection::to),
                    LeadKind.CODEC.optionalFieldOf("kind", LeadKind.NORMAL).forGetter(LeadConnection::kind),
                    Codec.INT.optionalFieldOf("power", 0).forGetter(LeadConnection::power),
                    Codec.INT.optionalFieldOf("tier", 0).forGetter(LeadConnection::tier),
                    Codec.INT.optionalFieldOf("extract", 0).forGetter(LeadConnection::extractAnchor))
                    .apply(instance, (id, from, to, kind, power, tier, extract) -> new LeadConnection(id, from, to, kind, power, tier, extract)));

    public LeadConnection {
        power = Math.max(0, Math.min(15, power));
        tier = Math.max(0, tier);
        extractAnchor = Math.max(0, Math.min(2, extractAnchor));
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to) {
        return create(from, to, LeadKind.NORMAL);
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to, LeadKind kind) {
        return new LeadConnection(UUID.randomUUID(), from, to, kind, 0, 0, 0);
    }

    public boolean powered() {
        return power > 0;
    }

    public LeadConnection withKind(LeadKind kind) {
        boolean keepsTier = kind == LeadKind.ENERGY || kind == LeadKind.ITEM || kind == LeadKind.FLUID;
        int newTier = keepsTier ? tier : 0;
        int newPower = (kind == LeadKind.REDSTONE || kind == LeadKind.ENERGY) ? power : 0;
        boolean keepsExtract = kind == LeadKind.ITEM || kind == LeadKind.FLUID;
        int newExtract = keepsExtract ? extractAnchor : 0;
        return new LeadConnection(id, from, to, kind, newPower, newTier, newExtract);
    }

    public LeadConnection withPower(int power) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor);
    }

    public LeadConnection withTier(int tier) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor);
    }

    public LeadConnection withExtractAnchor(int extractAnchor) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor);
    }

    /** Returns the anchor resources are extracted from (null if not in extract mode or wrong kind). */
    public LeadAnchor extractSource() {
        if (kind != LeadKind.ITEM && kind != LeadKind.FLUID) return null;
        return switch (extractAnchor) {
            case 1 -> from;
            case 2 -> to;
            default -> null;
        };
    }

    /** Returns the anchor resources are inserted into (null if not in extract mode or wrong kind). */
    public LeadAnchor extractTarget() {
        if (kind != LeadKind.ITEM && kind != LeadKind.FLUID) return null;
        return switch (extractAnchor) {
            case 1 -> to;
            case 2 -> from;
            default -> null;
        };
    }

    /** Speed multiplier: tier 0 = 1×, each tier doubles; saturates at Integer.MAX_VALUE. */
    public int speedMultiplier() {
        if (tier <= 0) {
            return 1;
        }
        if (tier >= 31) {
            return Integer.MAX_VALUE;
        }
        return 1 << tier;
    }
}
