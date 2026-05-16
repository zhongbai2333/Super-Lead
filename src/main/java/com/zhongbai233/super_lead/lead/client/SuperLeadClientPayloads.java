package com.zhongbai233.super_lead.lead.client;

import com.zhongbai233.super_lead.lead.ClearRopeCache;
import com.zhongbai233.super_lead.lead.ItemPulse;
import com.zhongbai233.super_lead.lead.RopeContactPulse;
import com.zhongbai233.super_lead.lead.SuperLeadNetwork;
import com.zhongbai233.super_lead.lead.SyncRopeChunk;
import com.zhongbai233.super_lead.lead.SyncZiplines;
import com.zhongbai233.super_lead.lead.UnloadRopeChunk;
import com.zhongbai233.super_lead.lead.client.chunk.StaticRopeChunkRegistry;
import com.zhongbai233.super_lead.lead.client.render.ItemFlowAnimator;
import com.zhongbai233.super_lead.lead.client.render.RopeContactsClient;
import com.zhongbai233.super_lead.lead.client.render.ZiplineClientState;
import com.zhongbai233.super_lead.preset.OpenZoneCreateScreen;
import com.zhongbai233.super_lead.preset.PresetApplyOverrides;
import com.zhongbai233.super_lead.preset.PresetDetailsResponse;
import com.zhongbai233.super_lead.preset.PresetListResponse;
import com.zhongbai233.super_lead.preset.PresetPromptOpen;
import com.zhongbai233.super_lead.preset.SyncDimensionPresets;
import com.zhongbai233.super_lead.preset.SyncPhysicsZones;
import com.zhongbai233.super_lead.preset.ZoneSelectionState;
import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.preset.client.PresetClientHandler;
import com.zhongbai233.super_lead.preset.client.ZoneSelectionClient;
import com.zhongbai233.super_lead.serverconfig.ServerConfigSnapshot;
import com.zhongbai233.super_lead.serverconfig.client.ServerConfigClient;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-only payload handlers for all {@code playToClient} packets.
 * <p>
 * Registered via reflection from the mod constructor to avoid compile-time
 * bytecode references from common-side classes.
 */
public final class SuperLeadClientPayloads {
    private SuperLeadClientPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(SyncRopeChunk.TYPE, SyncRopeChunk.STREAM_CODEC,
                        SuperLeadClientPayloads::handleSyncRopeChunk)
                .playToClient(UnloadRopeChunk.TYPE, UnloadRopeChunk.STREAM_CODEC,
                        SuperLeadClientPayloads::handleUnloadRopeChunk)
                .playToClient(ClearRopeCache.TYPE, ClearRopeCache.STREAM_CODEC,
                        SuperLeadClientPayloads::handleClearRopeCache)
                .playToClient(ItemPulse.TYPE, ItemPulse.STREAM_CODEC,
                        SuperLeadClientPayloads::handleItemPulse)
                .playToClient(RopeContactPulse.TYPE, RopeContactPulse.STREAM_CODEC,
                        SuperLeadClientPayloads::handleRopeContactPulse)
                .playToClient(SyncZiplines.TYPE, SyncZiplines.STREAM_CODEC,
                        SuperLeadClientPayloads::handleSyncZiplines)
                .playToClient(PresetPromptOpen.TYPE, PresetPromptOpen.STREAM_CODEC,
                        SuperLeadClientPayloads::handlePresetPromptOpen)
                .playToClient(PresetApplyOverrides.TYPE, PresetApplyOverrides.STREAM_CODEC,
                        SuperLeadClientPayloads::handlePresetApply)
                .playToClient(PresetListResponse.TYPE, PresetListResponse.STREAM_CODEC,
                        SuperLeadClientPayloads::handlePresetListResponse)
                .playToClient(PresetDetailsResponse.TYPE, PresetDetailsResponse.STREAM_CODEC,
                        SuperLeadClientPayloads::handlePresetDetailsResponse)
                .playToClient(SyncDimensionPresets.TYPE, SyncDimensionPresets.STREAM_CODEC,
                        SuperLeadClientPayloads::handleSyncDimensionPresets)
                .playToClient(SyncPhysicsZones.TYPE, SyncPhysicsZones.STREAM_CODEC,
                        SuperLeadClientPayloads::handleSyncPhysicsZones)
                .playToClient(ServerConfigSnapshot.TYPE, ServerConfigSnapshot.STREAM_CODEC,
                        SuperLeadClientPayloads::handleServerConfigSnapshot)
                .playToClient(ZoneSelectionState.TYPE, ZoneSelectionState.STREAM_CODEC,
                        SuperLeadClientPayloads::handleZoneSelectionState)
                .playToClient(OpenZoneCreateScreen.TYPE, OpenZoneCreateScreen.STREAM_CODEC,
                        SuperLeadClientPayloads::handleOpenZoneCreateScreen);
    }

    private static void handleSyncRopeChunk(SyncRopeChunk payload, IPayloadContext context) {
        var level = context.player().level();
        SuperLeadNetwork.replaceChunkConnections(level, payload.chunk(), payload.connections());
        StaticRopeChunkRegistry.get()
                .onConnectionsReplaced(level, SuperLeadNetwork.connections(level));
    }

    private static void handleUnloadRopeChunk(UnloadRopeChunk payload, IPayloadContext context) {
        var level = context.player().level();
        SuperLeadNetwork.unloadChunkConnections(level, payload.chunk());
        StaticRopeChunkRegistry.get()
                .onConnectionsReplaced(level, SuperLeadNetwork.connections(level));
    }

    private static void handleClearRopeCache(ClearRopeCache payload, IPayloadContext context) {
        var level = context.player().level();
        SuperLeadNetwork.replaceConnections(level, java.util.List.of());
        StaticRopeChunkRegistry.get()
                .onConnectionsReplaced(level, java.util.List.of());
    }

    private static void handleItemPulse(ItemPulse payload, IPayloadContext context) {
        ItemFlowAnimator.queue(payload);
        var level = context.player().level();
        long now = level.getGameTime();
        long pulseEndTick = Math.max(now, payload.startTick()) + payload.durationTicks() + 2L;
        StaticRopeChunkRegistry.get()
                .holdDynamic(level, payload.connectionId(), pulseEndTick);
    }

    private static void handleRopeContactPulse(RopeContactPulse payload, IPayloadContext context) {
        RopeContactsClient.apply(payload);
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null)
            return;
        var staticRopes = StaticRopeChunkRegistry.get();
        for (RopeContactPulse.Entry entry : payload.contacts()) {
            staticRopes.invalidateConnection(level, entry.ropeId());
        }
    }

    private static void handleSyncZiplines(SyncZiplines payload, IPayloadContext context) {
        ZiplineClientState.apply(payload);
    }

    private static void handlePresetPromptOpen(PresetPromptOpen payload, IPayloadContext context) {
        PresetClientHandler.onPromptOpen(payload);
    }

    private static void handlePresetApply(PresetApplyOverrides payload, IPayloadContext context) {
        PresetClientHandler.onApply(payload);
    }

    private static void handlePresetListResponse(PresetListResponse payload, IPayloadContext context) {
        PresetClientHandler.onListResponse(payload);
    }

    private static void handlePresetDetailsResponse(PresetDetailsResponse payload, IPayloadContext context) {
        PresetClientHandler.onDetailsResponse(payload);
    }

    private static void handleSyncDimensionPresets(SyncDimensionPresets payload, IPayloadContext context) {
        PhysicsZonesClient.apply(payload);
        var level = context.player().level();
        StaticRopeChunkRegistry.get()
                .invalidateAll(level, SuperLeadNetwork.connections(level));
    }

    private static void handleSyncPhysicsZones(SyncPhysicsZones payload, IPayloadContext context) {
        PhysicsZonesClient.apply(payload);
    }

    private static void handleServerConfigSnapshot(ServerConfigSnapshot payload, IPayloadContext context) {
        ServerConfigClient.onSnapshot(payload);
    }

    private static void handleZoneSelectionState(ZoneSelectionState payload, IPayloadContext context) {
        ZoneSelectionClient.apply(payload);
    }

    private static void handleOpenZoneCreateScreen(OpenZoneCreateScreen payload, IPayloadContext context) {
        ZoneSelectionClient.openCreate(payload);
    }
}
