package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record LeadConnection(UUID id, LeadAnchor from, LeadAnchor to, LeadKind kind, int power) {
    public static final Codec<LeadConnection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(LeadConnection::id),
                    LeadAnchor.CODEC.fieldOf("from").forGetter(LeadConnection::from),
                    LeadAnchor.CODEC.fieldOf("to").forGetter(LeadConnection::to),
                    LeadKind.CODEC.optionalFieldOf("kind", LeadKind.NORMAL).forGetter(LeadConnection::kind),
                    Codec.INT.optionalFieldOf("power", 0).forGetter(LeadConnection::power))
                    .apply(instance, (id, from, to, kind, power) -> new LeadConnection(id, from, to, kind, power)));

    public LeadConnection {
        power = Math.max(0, Math.min(15, power));
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to) {
        return create(from, to, LeadKind.NORMAL);
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to, LeadKind kind) {
        return new LeadConnection(UUID.randomUUID(), from, to, kind, 0);
    }

    public boolean powered() {
        return power > 0;
    }

    public LeadConnection withKind(LeadKind kind) {
        return new LeadConnection(id, from, to, kind, kind == LeadKind.REDSTONE ? power : 0);
    }

    public LeadConnection withPower(int power) {
        return new LeadConnection(id, from, to, kind, power);
    }
}
