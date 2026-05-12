package com.zhongbai233.super_lead.lead.client.render;

import com.zhongbai233.super_lead.lead.RopeContactPulse;
import com.zhongbai233.super_lead.lead.client.debug.RopeDebugStats;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * Client-side cache of currently active rope contacts (server-broadcast).
 * The render driver consults this before stepping each rope simulation so that the
 * same visual deflection appears on every observer.
 */
public final class RopeContactsClient {
    public record Contact(float t, float dx, float dy, float dz) {}

    private static final Map<UUID, Contact> ACTIVE = new HashMap<>();

    private RopeContactsClient() {}

    public static synchronized void apply(RopeContactPulse pulse) {
        ACTIVE.clear();
        UUID localPlayer = Minecraft.getInstance().player == null
                ? null
                : Minecraft.getInstance().player.getUUID();
        float pushX = 0.0F;
        float pushZ = 0.0F;
        int pushContacts = 0;
        for (RopeContactPulse.Entry e : pulse.contacts()) {
            // If a single rope receives multiple contacts in one snapshot, keep the deepest.
            Contact existing = ACTIVE.get(e.ropeId());
            float depthSqrNew = e.dx() * e.dx() + e.dy() * e.dy() + e.dz() * e.dz();
            if (existing == null
                    || depthSqrNew > existing.dx * existing.dx + existing.dy * existing.dy + existing.dz * existing.dz) {
                ACTIVE.put(e.ropeId(), new Contact(e.t(), e.dx(), e.dy(), e.dz()));
            }
            if (localPlayer != null && localPlayer.equals(e.playerId())) {
                pushContacts++;
                if (e.pushMag() > 0.0F) {
                    pushX += e.pushX();
                    pushZ += e.pushZ();
                }
            }
        }
        RopeDebugStats.pushContacts = pushContacts;
        RopeDebugStats.pushX = pushX;
        RopeDebugStats.pushZ = pushZ;
        RopeDebugStats.pushMagnitude = (float) Math.sqrt(pushX * pushX + pushZ * pushZ);
    }

    public static synchronized Contact get(UUID ropeId) {
        return ACTIVE.get(ropeId);
    }

    public static synchronized void clear() {
        ACTIVE.clear();
        RopeDebugStats.pushContacts = 0;
        RopeDebugStats.pushX = 0.0F;
        RopeDebugStats.pushZ = 0.0F;
        RopeDebugStats.pushMagnitude = 0.0F;
    }
}
