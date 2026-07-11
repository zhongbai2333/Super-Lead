package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * Immutable description of one rope span and its gameplay upgrades.
 *
 * <p>
 * The record is shared by persistent SavedData, network payloads, rendering,
 * and transfer logic. Mutating operations therefore return a new normalized
 * instance instead of editing fields in place.
 */
public record LeadConnection(UUID id, LeadAnchor from, LeadAnchor to, LeadKind kind, int power, int tier,
        int extractAnchor, int lengthUnits, List<RopeAttachment> attachments, String physicsPreset,
        String manualPhysicsPreset, UUID adventureOwner) {
    public static final String NO_PHYSICS_PRESET = "";
    public static final UUID NO_ADVENTURE_OWNER = new UUID(0L, 0L);
    public static final int MIN_LENGTH_UNITS = 1;
    public static final int MAX_LENGTH_UNITS = 4;

    public static final Codec<LeadConnection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(connection -> connection.id()),
                LeadAnchor.CODEC.fieldOf("from").forGetter(connection -> connection.from()),
                LeadAnchor.CODEC.fieldOf("to").forGetter(connection -> connection.to()),
                LeadKind.CODEC.optionalFieldOf("kind", LeadKind.NORMAL).forGetter(connection -> connection.kind()),
                Codec.INT.optionalFieldOf("power", 0).forGetter(connection -> connection.power()),
                Codec.INT.optionalFieldOf("tier", 0).forGetter(connection -> connection.tier()),
                Codec.INT.optionalFieldOf("extract", 0).forGetter(connection -> connection.extractAnchor()),
                Codec.INT.optionalFieldOf("lengthUnits", MIN_LENGTH_UNITS).forGetter(connection -> connection.lengthUnits()),
            RopeAttachment.CODEC.listOf().optionalFieldOf("attachments", List.of())
                    .forGetter(connection -> connection.attachments()),
                Codec.STRING.optionalFieldOf("physicsPreset", NO_PHYSICS_PRESET)
                    .forGetter(connection -> connection.physicsPreset()),
            Codec.STRING.optionalFieldOf("manualPhysicsPreset", NO_PHYSICS_PRESET)
                    .forGetter(connection -> connection.manualPhysicsPreset()),
            UUIDUtil.CODEC.optionalFieldOf("adventureOwner", NO_ADVENTURE_OWNER)
                    .forGetter(connection -> connection.adventureOwner()))
            .apply(instance,
                    (id, from, to, kind, power, tier, extract, lengthUnits, attachments, physicsPreset,
                            manualPhysicsPreset, adventureOwner) -> new LeadConnection(id, from, to, kind, power, tier,
                                    extract, lengthUnits, attachments, physicsPreset, manualPhysicsPreset,
                                    adventureOwner)));

    public LeadConnection {
        power = Math.max(0, Math.min(15, power));
        tier = Math.max(0, tier);
        extractAnchor = Math.max(0, Math.min(2, extractAnchor));
        lengthUnits = Math.max(MIN_LENGTH_UNITS, Math.min(MAX_LENGTH_UNITS, lengthUnits));
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        physicsPreset = normalizePhysicsPreset(physicsPreset);
        manualPhysicsPreset = normalizePhysicsPreset(manualPhysicsPreset);
        adventureOwner = adventureOwner == null ? NO_ADVENTURE_OWNER : adventureOwner;
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to) {
        return create(from, to, LeadKind.NORMAL);
    }

    public static LeadConnection create(LeadAnchor from, LeadAnchor to, LeadKind kind) {
        return new LeadConnection(UUID.randomUUID(), from, to, kind, 0, 0, 0, MIN_LENGTH_UNITS, List.of(),
                NO_PHYSICS_PRESET,
                NO_PHYSICS_PRESET,
                NO_ADVENTURE_OWNER);
    }

    public boolean adventurePlaced() {
        return !NO_ADVENTURE_OWNER.equals(adventureOwner);
    }

    public boolean powered() {
        return power > 0;
    }

    public LeadConnection withKind(LeadKind kind) {
        boolean keepsTier = kind == LeadKind.ENERGY || kind == LeadKind.ITEM || kind == LeadKind.FLUID
                || kind == LeadKind.PRESSURIZED || kind == LeadKind.THERMAL || kind == LeadKind.AE_NETWORK;
        int newTier = keepsTier ? tier : 0;
        int newPower = (kind == LeadKind.REDSTONE || kind == LeadKind.ENERGY) ? power : 0;
        boolean keepsExtract = kind == LeadKind.ITEM || kind == LeadKind.FLUID || kind == LeadKind.PRESSURIZED
                || kind == LeadKind.ENERGY;
        int newExtract = keepsExtract ? extractAnchor : 0;
        return new LeadConnection(id, from, to, kind, newPower, newTier, newExtract, lengthUnits, attachments,
                physicsPreset, manualPhysicsPreset, adventureOwner);
    }

    public LeadConnection withPower(int power) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments,
                physicsPreset,
                manualPhysicsPreset, adventureOwner);
    }

    public LeadConnection withTier(int tier) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments,
                physicsPreset,
                manualPhysicsPreset, adventureOwner);
    }

    public LeadConnection withExtractAnchor(int extractAnchor) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments,
                physicsPreset, manualPhysicsPreset, adventureOwner);
    }

    public LeadConnection withLengthUnits(int lengthUnits) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments,
                physicsPreset, manualPhysicsPreset, adventureOwner);
    }

    public boolean canExtendLength() {
        return lengthUnits < MAX_LENGTH_UNITS;
    }

    public LeadConnection withAttachments(List<RopeAttachment> attachments) {
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments,
                physicsPreset, manualPhysicsPreset, adventureOwner);
    }

    public LeadConnection withPhysicsPreset(String physicsPreset) {
        String normalized = normalizePhysicsPreset(physicsPreset);
        if (normalized.equals(this.physicsPreset)) {
            return this;
        }
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments, normalized,
                manualPhysicsPreset, adventureOwner);
    }

    public LeadConnection withManualPhysicsPreset(String manualPhysicsPreset) {
        String normalized = normalizePhysicsPreset(manualPhysicsPreset);
        if (normalized.equals(this.manualPhysicsPreset)) {
            return this;
        }
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments,
                physicsPreset,
                normalized, adventureOwner);
    }

    public LeadConnection withAdventureOwner(UUID adventureOwner) {
        UUID normalized = adventureOwner == null ? NO_ADVENTURE_OWNER : adventureOwner;
        if (normalized.equals(this.adventureOwner)) {
            return this;
        }
        return new LeadConnection(id, from, to, kind, power, tier, extractAnchor, lengthUnits, attachments,
                physicsPreset, manualPhysicsPreset, normalized);
    }

    public LeadConnection addAttachment(RopeAttachment attachment) {
        List<RopeAttachment> list = new ArrayList<>(attachments);
        list.add(attachment);
        Collections.sort(list, (x, y) -> Double.compare(x.t(), y.t()));
        return withAttachments(list);
    }

    public LeadConnection removeAttachment(UUID attachmentId) {
        if (attachments.isEmpty())
            return this;
        List<RopeAttachment> list = new ArrayList<>(attachments.size());
        for (RopeAttachment a : attachments) {
            if (!a.id().equals(attachmentId))
                list.add(a);
        }
        if (list.size() == attachments.size())
            return this;
        return withAttachments(list);
    }

    /**
     * Flip the {@code displayAsBlock} flag of {@code attachmentId}. No-op when the
     * attachment
     * does not exist or its stack is not a BlockItem (since item form is the only
     * available
     * shape for non-block items).
     */
    public LeadConnection toggleAttachmentForm(UUID attachmentId) {
        if (attachments.isEmpty())
            return this;
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

    public LeadConnection setAttachmentDisplay(UUID attachmentId, int mountOverride,
            int displayModeOverride, int hangerOverride, int piercedOverride, double hangOffsetOverride,
            double mountOffsetOverride, double hangerLengthOverride, double hangerSpacingOverride, double scaleOverride,
            int frontSide, java.util.Map<String, String> modelStateOverride) {
        if (attachments.isEmpty())
            return this;
        List<RopeAttachment> list = new ArrayList<>(attachments.size());
        boolean changed = false;
        for (RopeAttachment a : attachments) {
            if (a.id().equals(attachmentId)) {
                RopeAttachment next = a.withDisplayOverrides(mountOverride, displayModeOverride,
                        hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride, hangerLengthOverride,
                    hangerSpacingOverride, scaleOverride, frontSide, modelStateOverride);
                list.add(next);
                changed = changed || !next.equals(a);
            } else {
                list.add(a);
            }
        }
        return changed ? withAttachments(list) : this;
    }

    /**
     * Returns the resource extraction anchor, or null when extraction is disabled.
     */
    public LeadAnchor extractSource() {
        if (kind != LeadKind.ITEM && kind != LeadKind.FLUID && kind != LeadKind.PRESSURIZED
                && kind != LeadKind.ENERGY)
            return null;
        return switch (extractAnchor) {
            case 1 -> from;
            case 2 -> to;
            default -> null;
        };
    }

    /**
     * Returns the resource insertion anchor, or null when extraction is disabled.
     */
    public LeadAnchor extractTarget() {
        if (kind != LeadKind.ITEM && kind != LeadKind.FLUID && kind != LeadKind.PRESSURIZED
                && kind != LeadKind.ENERGY)
            return null;
        return switch (extractAnchor) {
            case 1 -> to;
            case 2 -> from;
            default -> null;
        };
    }

    /**
     * Speed multiplier: tier 0 = 1x, each tier doubles; saturates at
     * Integer.MAX_VALUE.
     */
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
