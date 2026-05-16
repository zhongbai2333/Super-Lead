package com.zhongbai233.super_lead.lead;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;

/**
 * Tracks the first anchor selected while a player is placing a rope.
 *
 * <p>
 * The key includes the physical side, because integrated-server play has a
 * client player and server player with the same UUID in the same JVM. Keeping
 * those pending anchors separate prevents a client-side preview click from
 * overwriting the authoritative server-side placement state.
 */
final class LeadPlacementState {
    private static final Map<PlayerKey, PendingLead> PENDING_LEADS = new HashMap<>();

    private LeadPlacementState() {
    }

    static Optional<LeadAnchor> pendingAnchor(Player player) {
        return pendingLead(player).map(PendingLead::anchor);
    }

    static Optional<LeadKind> pendingKind(Player player) {
        return pendingLead(player).map(PendingLead::kind);
    }

    static void setPendingAnchor(Player player, LeadAnchor anchor, LeadKind kind) {
        PENDING_LEADS.put(PlayerKey.of(player), new PendingLead(anchor, kind));
    }

    static void clearPendingAnchor(Player player) {
        PENDING_LEADS.remove(PlayerKey.of(player));
    }

    private static Optional<PendingLead> pendingLead(Player player) {
        return Optional.ofNullable(PENDING_LEADS.get(PlayerKey.of(player)));
    }

    private record PlayerKey(UUID playerId, boolean clientSide) {
        static PlayerKey of(Player player) {
            return new PlayerKey(player.getUUID(), player.level().isClientSide());
        }
    }

    private record PendingLead(LeadAnchor anchor, LeadKind kind) {
    }
}