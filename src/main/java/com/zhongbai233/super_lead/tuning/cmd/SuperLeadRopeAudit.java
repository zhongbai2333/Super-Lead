package com.zhongbai233.super_lead.tuning.cmd;

import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.super_lead.lead.LeadConnection;
import com.zhongbai233.super_lead.lead.LeadKind;
import com.zhongbai233.super_lead.lead.SuperLeadSavedData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Region-level diagnostics for server-side rope density and adventure usage. */
final class SuperLeadRopeAudit {
    private SuperLeadRopeAudit() {
    }

    static int report(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        RopeAudit audit = RopeAudit.scan(server);
        ctx.getSource().sendSuccess(() -> Component.literal("Super Lead rope audit")
                .withStyle(ChatFormatting.GOLD), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  total=%d adventure=%d functional=%d crossChunk=%d attachments=%d",
                audit.total, audit.adventure, audit.functional, audit.crossChunk, audit.attachments))
                .withStyle(ChatFormatting.WHITE), false);

        if (!audit.byKind.isEmpty()) {
            StringBuilder kinds = new StringBuilder("  by kind:");
            for (Map.Entry<LeadKind, Integer> entry : audit.byKind.entrySet()) {
                kinds.append(' ').append(entry.getKey().name().toLowerCase(Locale.ROOT))
                        .append('=').append(entry.getValue());
            }
            ctx.getSource().sendSuccess(() -> Component.literal(kinds.toString())
                    .withStyle(ChatFormatting.GRAY), false);
        }

        if (audit.total == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("  no ropes found.")
                    .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("  hottest 512x512 regions:")
                .withStyle(ChatFormatting.YELLOW), false);
        for (RegionBucket bucket : audit.topRegions(5)) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "    %s region[%d,%d]: ropes=%d adventure=%d functional=%d attachments=%d",
                    bucket.dimension, bucket.regionX, bucket.regionZ, bucket.count, bucket.adventure,
                    bucket.functional, bucket.attachments))
                    .withStyle(bucket.count >= 64 ? ChatFormatting.RED : ChatFormatting.AQUA), false);
        }

        if (!audit.byAdventureOwner.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  adventure owner counts:")
                    .withStyle(ChatFormatting.YELLOW), false);
            audit.byAdventureOwner.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(entry -> ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                            "    %s: %d", shortUuid(entry.getKey()), entry.getValue()))
                            .withStyle(entry.getValue() >= 32 ? ChatFormatting.RED : ChatFormatting.GRAY), false));
        }

        if (audit.maxRegionCount() >= 64 || audit.maxOwnerCount() >= 32) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  warning: dense rope activity found. Output is intentionally region-level, not per-rope coordinates.")
                    .withStyle(ChatFormatting.RED), false);
        }
        return Math.max(1, audit.total);
    }

    private static String shortUuid(UUID uuid) {
        String value = uuid.toString();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static final class RopeAudit {
        private int total;
        private int adventure;
        private int functional;
        private int crossChunk;
        private int attachments;
        private final EnumMap<LeadKind, Integer> byKind = new EnumMap<>(LeadKind.class);
        private final Map<RegionKey, RegionBucket> byRegion = new LinkedHashMap<>();
        private final Map<UUID, Integer> byAdventureOwner = new LinkedHashMap<>();

        private static RopeAudit scan(MinecraftServer server) {
            RopeAudit audit = new RopeAudit();
            for (ServerLevel level : server.getAllLevels()) {
                SuperLeadSavedData data = SuperLeadSavedData.get(level);
                String dimension = level.dimension().toString();
                for (LeadConnection connection : data.connections()) {
                    audit.accept(data, dimension, connection);
                }
            }
            return audit;
        }

        private void accept(SuperLeadSavedData data, String dimension, LeadConnection connection) {
            total++;
            byKind.merge(connection.kind(), 1, (a, b) -> a + b);
            boolean adventurePlaced = connection.adventurePlaced();
            boolean functionalRope = connection.kind() != LeadKind.NORMAL;
            int attachmentCount = connection.attachments().size();

            if (adventurePlaced) {
                adventure++;
                byAdventureOwner.merge(connection.adventureOwner(), 1, (a, b) -> a + b);
            }
            if (functionalRope) {
                functional++;
            }
            if (data.chunksForConnection(connection.id()).size() > 1) {
                crossChunk++;
            }
            attachments += attachmentCount;

            int midX = Math.floorDiv(connection.from().pos().getX() + connection.to().pos().getX(), 2);
            int midZ = Math.floorDiv(connection.from().pos().getZ() + connection.to().pos().getZ(), 2);
            int regionX = Math.floorDiv(midX, 512);
            int regionZ = Math.floorDiv(midZ, 512);
            RegionKey key = new RegionKey(dimension, regionX, regionZ);
            RegionBucket bucket = byRegion.computeIfAbsent(key,
                    k -> new RegionBucket(k.dimension, k.regionX, k.regionZ));
            bucket.count++;
            if (adventurePlaced) {
                bucket.adventure++;
            }
            if (functionalRope) {
                bucket.functional++;
            }
            bucket.attachments += attachmentCount;
        }

        private ArrayList<RegionBucket> topRegions(int limit) {
            ArrayList<RegionBucket> out = new ArrayList<>(byRegion.values());
            out.sort(Comparator.comparingInt((RegionBucket bucket) -> bucket.count).reversed()
                    .thenComparing(bucket -> bucket.dimension)
                    .thenComparingInt(bucket -> bucket.regionX)
                    .thenComparingInt(bucket -> bucket.regionZ));
            if (out.size() > limit) {
                return new ArrayList<>(out.subList(0, limit));
            }
            return out;
        }

        private int maxRegionCount() {
            int max = 0;
            for (RegionBucket bucket : byRegion.values()) {
                max = Math.max(max, bucket.count);
            }
            return max;
        }

        private int maxOwnerCount() {
            int max = 0;
            for (int count : byAdventureOwner.values()) {
                max = Math.max(max, count);
            }
            return max;
        }
    }

    private record RegionKey(String dimension, int regionX, int regionZ) {
    }

    private static final class RegionBucket {
        private final String dimension;
        private final int regionX;
        private final int regionZ;
        private int count;
        private int adventure;
        private int functional;
        private int attachments;

        private RegionBucket(String dimension, int regionX, int regionZ) {
            this.dimension = dimension;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }
    }
}