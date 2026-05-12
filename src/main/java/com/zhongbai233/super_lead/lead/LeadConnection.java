package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

public record LeadConnection(UUID id, LeadAnchor from, LeadAnchor to, LeadKind kind, int power, int tier,
                             int extractAnchor, List<RopeAttachment> attachments, String physicsPreset) {
    public static final String NO_PHYSICS_PRESET = "";

    public static final Codec<LeadConnection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(LeadConnection::id),
                    LeadAnchor.CODEC.fieldOf("from").forGetter(LeadConnection::from),
                    LeadAnchor.CODEC.fieldOf("to").forGetter(LeadConnection::to),
                    LeadKind.CODEC.optionalFieldOf("kind", LeadKind.NORMAL).forGetter(LeadConnection::kind),
                    Codec.INT.optionalFieldOf("power", 0).forGetter(LeadConnection::power),
                    Codec.INT.optionalFieldOf("tier", 0).forGetter(LeadConnection::tier),
                    Codec.INT.optionalFieldOf("extract", 0).forGetter(LeadConnection::extractAnchor),
                    RopeAttachment.CODEC.listOf().optionalFieldOf("attachments", List.of()).forGetter(LeadConnection::attachments),
                    Codec.STRING.optionalFieldOf("physicsPreset", NO_PHYSICS_PRESET).forGetter(LeadConnection::physicsPreset))
                    .apply(instance, (id, from, to, kind, power, tier, extract, attachments, physicsPreset) ->
                            new LeadConnection(id, from, to, kind, power, tier, extract, attachments, physicsPreset)));

    public LeadConnection {
        power = Math.max(0, Math.min(15, power));
        tier = Math.max(0, tier);
        extractAnchor = Math.max(0, Math.min(2, extractAnchor));
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        physicsPreset = normalizePhysicsPreset(physicsPreset);
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to) {
        return create(from, to, LeadKind.NORMAL);
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to, LeadKind kind) {
        return new LeadConnection(UUID.randomUUID(), from, to, kind, 0, 0, 0, List.of(), NO_PHYSICS_PRESET);
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
        return new LeadConnection(id, from, to, kind, newPower, newTier, newExtract, attachments, physicsPreset);
    }

    public LeadConnection withPower(int power) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, attachments, physicsPreset);
    }

    public LeadConnection withTier(int tier) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, attachments, physicsPreset);
    }

    public LeadConnection withExtractAnchor(int extractAnchor) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, attachments, physicsPreset);
    }

    public LeadConnection withAttachments(List<RopeAttachment> attachments) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, attachments, physicsPreset);
    }

    public LeadConnection withPhysicsPreset(String physicsPreset) {
        String normalized = normalizePhysicsPreset(physicsPreset);
        if (normalized.equals(this.physicsPreset)) {
            return this;
        }
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, attachments, normalized);
    }

    public LeadConnection addAttachment(RopeAttachment attachment) {
        List<RopeAttachment> list = new ArrayList<>(attachments);
        list.add(attachment);
        Collections.sort(list, (x, y) -> Double.compare(x.t(), y.t()));
        return withAttachments(list);
    }

    public LeadConnection removeAttachment(UUID attachmentId) {
        if (attachments.isEmpty()) return this;
        List<RopeAttachment> list = new ArrayList<>(attachments.size());
        for (RopeAttachment a : attachments) {
            if (!a.id().equals(attachmentId)) list.add(a);
        }
        if (list.size() == attachments.size()) return this;
        return withAttachments(list);
    }

    /** Flip the {@code displayAsBlock} flag of {@code attachmentId}. No-op when the attachment
     *  does not exist or its stack is not a BlockItem (since item form is the only available
     *  shape for non-block items). */
    public LeadConnection toggleAttachmentForm(UUID attachmentId) {
        if (attachments.isEmpty()) return this;
        List<RopeAttachment> list = new ArrayList<>(attachments.size());
        boolean changed = false;
        for (RopeAttachment a : attachments) {
            if (a.id().equals(attachmentId) && RopeAttachmentItems.isBlockItem(a.stack())) {
                list.add(a.withDisplayAsBlock(!a.displayAsBlock()));
                changed = true;
            } else {
                list.add(a);
            }
        }
        return changed ? withAttachments(list) : this;
    }

    /** Returns the resource extraction anchor, or null when extraction is disabled. */
    public LeadAnchor extractSource() {
        if (kind != LeadKind.ITEM && kind != LeadKind.FLUID) return null;
        return switch (extractAnchor) {
            case 1 -> from;
            case 2 -> to;
            default -> null;
        };
    }

    /** Returns the resource insertion anchor, or null when extraction is disabled. */
    public LeadAnchor extractTarget() {
        if (kind != LeadKind.ITEM && kind != LeadKind.FLUID) return null;
        return switch (extractAnchor) {
            case 1 -> to;
            case 2 -> from;
            default -> null;
        };
    }

    /** Speed multiplier: tier 0 = 1x, each tier doubles; saturates at Integer.MAX_VALUE. */
    public int speedMultiplier() {
        if (tier <= 0) {
            return 1;
        }
        if (tier >= 31) {
            return Integer.MAX_VALUE;
        }
        return 1 << tier;
    }

    private static String normalizePhysicsPreset(String physicsPreset) {
        return physicsPreset == null ? NO_PHYSICS_PRESET : physicsPreset.trim();
    }
}
