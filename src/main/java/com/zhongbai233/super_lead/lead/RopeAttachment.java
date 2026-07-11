package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * Item or block-like decoration attached to a rope at parameter {@code t}.
 *
 * <p>
 * {@code frontSide} stores the face shown to the viewer for directional
 * attachments such as signs. The display override fields are synchronized
 * visual-only settings, so other clients see the same customized placement. The stack
 * is copied/normalized so render and server drop logic can treat attachments as
 * immutable samples.
 */
public record RopeAttachment(UUID id, double t, ItemStack stack, boolean displayAsBlock, int frontSide,
    int mountOverride, int displayModeOverride, int hangerOverride, int piercedOverride,
    double hangOffsetOverride, double mountOffsetOverride, double hangerLengthOverride,
    double hangerSpacingOverride, double scaleOverride, Map<String, String> modelStateOverride) {
    public static final int OVERRIDE_DEFAULT = -1;
    public static final int OVERRIDE_FALSE = 0;
    public static final int OVERRIDE_TRUE = 1;
    public static final int DISPLAY_DEFAULT = -1;
    public static final int DISPLAY_ITEM = 0;
    public static final int DISPLAY_BLOCK = 1;
    public static final double DOUBLE_DEFAULT = Double.NaN;
    public static final double MIN_SCALE = 0.20D;
    public static final double MAX_SCALE = 3.00D;
    public static final double SCALE_STEP = 0.10D;

    public static final Codec<RopeAttachment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(attachment -> attachment.id()),
            Codec.DOUBLE.fieldOf("t").forGetter(a -> Double.valueOf(a.t())),
            ItemStack.CODEC.fieldOf("stack").forGetter(attachment -> attachment.stack()),
            Codec.BOOL.optionalFieldOf("display_as_block", Boolean.TRUE)
                    .forGetter(a -> Boolean.valueOf(a.displayAsBlock())),
            Codec.INT.optionalFieldOf("front_side", 1).forGetter(a -> Integer.valueOf(a.frontSide())),
            Codec.INT.optionalFieldOf("mount_override", OVERRIDE_DEFAULT)
                .forGetter(a -> Integer.valueOf(a.mountOverride())),
            Codec.INT.optionalFieldOf("display_mode_override", DISPLAY_DEFAULT)
                .forGetter(a -> Integer.valueOf(a.displayModeOverride())),
            Codec.INT.optionalFieldOf("hanger_override", OVERRIDE_DEFAULT)
                .forGetter(a -> Integer.valueOf(a.hangerOverride())),
            Codec.INT.optionalFieldOf("pierced_override", OVERRIDE_DEFAULT)
                .forGetter(a -> Integer.valueOf(a.piercedOverride())),
            Codec.DOUBLE.optionalFieldOf("hang_offset_override", DOUBLE_DEFAULT)
                .forGetter(a -> Double.valueOf(a.hangOffsetOverride())),
            Codec.DOUBLE.optionalFieldOf("mount_offset_override", DOUBLE_DEFAULT)
                .forGetter(a -> Double.valueOf(a.mountOffsetOverride())),
            Codec.DOUBLE.optionalFieldOf("hanger_length_override", DOUBLE_DEFAULT)
                .forGetter(a -> Double.valueOf(a.hangerLengthOverride())),
            Codec.DOUBLE.optionalFieldOf("hanger_spacing_override", DOUBLE_DEFAULT)
                .forGetter(a -> Double.valueOf(a.hangerSpacingOverride())),
            Codec.DOUBLE.optionalFieldOf("scale_override", DOUBLE_DEFAULT)
                .forGetter(a -> Double.valueOf(a.scaleOverride())),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("model_state_override", Map.of())
                .forGetter(attachment -> attachment.modelStateOverride()))
            .apply(instance, (id, t, stack, asBlock, frontSide, mountOverride, displayModeOverride,
                hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride, hangerLengthOverride,
                hangerSpacingOverride, scaleOverride, modelStateOverride) -> new RopeAttachment(id, t.doubleValue(), stack, asBlock.booleanValue(),
                    frontSide.intValue(), mountOverride.intValue(),
                    displayModeOverride.intValue(), hangerOverride.intValue(), piercedOverride.intValue(),
                    hangOffsetOverride.doubleValue(), mountOffsetOverride.doubleValue(),
                    hangerLengthOverride.doubleValue(), hangerSpacingOverride.doubleValue(), scaleOverride.doubleValue(),
                    modelStateOverride)));

    public static final StreamCodec<RegistryFriendlyByteBuf, RopeAttachment> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RopeAttachment decode(RegistryFriendlyByteBuf buffer) {
            UUID id = buffer.readUUID();
            double t = buffer.readDouble();
            ItemStack stack = ItemStack.STREAM_CODEC.decode(buffer);
            boolean asBlock = buffer.readBoolean();
            int frontSide = buffer.readVarInt();
            int mountOverride = buffer.readVarInt();
            int displayModeOverride = buffer.readVarInt();
            int hangerOverride = buffer.readVarInt();
            int piercedOverride = buffer.readVarInt();
            double hangOffsetOverride = buffer.readDouble();
            double mountOffsetOverride = buffer.readDouble();
            double hangerLengthOverride = buffer.readDouble();
            double hangerSpacingOverride = buffer.readDouble();
            double scaleOverride = buffer.readDouble();
            Map<String, String> modelStateOverride = readModelStateOverride(buffer);
            return new RopeAttachment(id, t, stack, asBlock, frontSide, mountOverride,
                    displayModeOverride, hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride,
                    hangerLengthOverride, hangerSpacingOverride, scaleOverride, modelStateOverride);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, RopeAttachment attachment) {
            buffer.writeUUID(attachment.id());
            buffer.writeDouble(attachment.t());
            ItemStack.STREAM_CODEC.encode(buffer, attachment.stack());
            buffer.writeBoolean(attachment.displayAsBlock());
            buffer.writeVarInt(attachment.frontSide());
            buffer.writeVarInt(attachment.mountOverride());
            buffer.writeVarInt(attachment.displayModeOverride());
            buffer.writeVarInt(attachment.hangerOverride());
            buffer.writeVarInt(attachment.piercedOverride());
            buffer.writeDouble(attachment.hangOffsetOverride());
            buffer.writeDouble(attachment.mountOffsetOverride());
            buffer.writeDouble(attachment.hangerLengthOverride());
            buffer.writeDouble(attachment.hangerSpacingOverride());
            buffer.writeDouble(attachment.scaleOverride());
            writeModelStateOverride(buffer, attachment.modelStateOverride());
        }
    };

    public RopeAttachment {
        if (Double.isNaN(t))
            t = 0.5D;
        t = Math.max(0.02D, Math.min(0.98D, t));
        stack = stack.copyWithCount(1);
        frontSide = normalizeFrontSide(frontSide);
        mountOverride = normalizeBooleanOverride(mountOverride);
        displayModeOverride = normalizeDisplayModeOverride(displayModeOverride);
        hangerOverride = normalizeBooleanOverride(hangerOverride);
        piercedOverride = normalizeBooleanOverride(piercedOverride);
        hangOffsetOverride = normalizeOptionalNonNegative(hangOffsetOverride);
        mountOffsetOverride = normalizeOptionalNonNegative(mountOffsetOverride);
        hangerLengthOverride = normalizeOptionalNonNegative(hangerLengthOverride);
        hangerSpacingOverride = normalizeOptionalNonNegative(hangerSpacingOverride);
        scaleOverride = normalizeOptionalScale(scaleOverride);
        modelStateOverride = normalizeModelStateOverride(modelStateOverride);
    }

    public RopeAttachment(UUID id, double t, ItemStack stack, boolean displayAsBlock, int frontSide) {
        this(id, t, stack, displayAsBlock, frontSide, OVERRIDE_DEFAULT, DISPLAY_DEFAULT,
            OVERRIDE_DEFAULT, OVERRIDE_DEFAULT, DOUBLE_DEFAULT, DOUBLE_DEFAULT, DOUBLE_DEFAULT, DOUBLE_DEFAULT,
            DOUBLE_DEFAULT, Map.of());
    }

    public static RopeAttachment create(double t, ItemStack stack) {
        return create(t, stack, 1);
    }

    public static RopeAttachment create(double t, ItemStack stack, int frontSide) {
        boolean asBlock = RopeAttachmentItems.isBlockItem(stack) || RopeAttachmentItems.isPanelLikeItem(stack);
        return new RopeAttachment(UUID.randomUUID(), t, stack, asBlock, frontSide);
    }

    public RopeAttachment withDisplayAsBlock(boolean asBlock) {
        return new RopeAttachment(id, t, stack, asBlock, frontSide, mountOverride, displayModeOverride,
            hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride, hangerLengthOverride,
            hangerSpacingOverride, scaleOverride, modelStateOverride);
    }

    public RopeAttachment withStack(ItemStack stack) {
        return new RopeAttachment(id, t, stack, displayAsBlock, frontSide, mountOverride,
            displayModeOverride, hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride,
            hangerLengthOverride, hangerSpacingOverride, scaleOverride, modelStateOverride);
    }

    public RopeAttachment withDisplayOverrides(int mountOverride, int displayModeOverride,
            int hangerOverride, int piercedOverride, double hangOffsetOverride, double mountOffsetOverride,
            double hangerLengthOverride, double hangerSpacingOverride, double scaleOverride, int frontSide,
            Map<String, String> modelStateOverride) {
        return new RopeAttachment(id, t, stack, displayAsBlock, frontSide, mountOverride,
            displayModeOverride, hangerOverride, piercedOverride, hangOffsetOverride, mountOffsetOverride,
            hangerLengthOverride, hangerSpacingOverride, scaleOverride, modelStateOverride);
    }

    public static int normalizeFrontSide(int frontSide) {
        if (frontSide == 0) {
            return 1;
        }
        if (frontSide > 3) {
            return 3;
        }
        if (frontSide < -3) {
            return -3;
        }
        return frontSide;
    }

    public static int normalizeBooleanOverride(int value) {
        return value == OVERRIDE_FALSE || value == OVERRIDE_TRUE ? value : OVERRIDE_DEFAULT;
    }

    public static int normalizeDisplayModeOverride(int value) {
        return value == DISPLAY_ITEM || value == DISPLAY_BLOCK ? value : DISPLAY_DEFAULT;
    }

    public static int cycleBooleanOverride(int value) {
        value = normalizeBooleanOverride(value);
        return value == OVERRIDE_DEFAULT ? OVERRIDE_TRUE : value == OVERRIDE_TRUE ? OVERRIDE_FALSE : OVERRIDE_DEFAULT;
    }

    public static int cycleDisplayModeOverride(int value) {
        value = normalizeDisplayModeOverride(value);
        return value == DISPLAY_DEFAULT ? DISPLAY_BLOCK : value == DISPLAY_BLOCK ? DISPLAY_ITEM : DISPLAY_DEFAULT;
    }

    public static boolean hasDoubleOverride(double value) {
        return Double.isFinite(value);
    }

    public static double normalizeOptionalNonNegative(double value) {
        if (!Double.isFinite(value)) {
            return DOUBLE_DEFAULT;
        }
        return Math.max(0.0D, value);
    }

    public static double normalizeOptionalScale(double value) {
        if (!Double.isFinite(value)) {
            return DOUBLE_DEFAULT;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    public static Map<String, String> normalizeModelStateOverride(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            String value = entry.getValue() == null ? "" : entry.getValue().trim().toLowerCase(java.util.Locale.ROOT);
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    public static Map<String, String> readModelStateOverride(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        TreeMap<String, String> out = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            out.put(buffer.readUtf(64), buffer.readUtf(64));
        }
        return normalizeModelStateOverride(out);
    }

    public static void writeModelStateOverride(RegistryFriendlyByteBuf buffer, Map<String, String> raw) {
        Map<String, String> normalized = normalizeModelStateOverride(raw);
        buffer.writeVarInt(normalized.size());
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            buffer.writeUtf(entry.getKey(), 64);
            buffer.writeUtf(entry.getValue(), 64);
        }
    }
}
