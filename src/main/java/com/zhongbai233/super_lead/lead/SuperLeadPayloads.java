package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.preset.PresetApplyOverrides;
import com.zhongbai233.super_lead.preset.PresetClearOverrides;
import com.zhongbai233.super_lead.preset.PresetDetailsRequest;
import com.zhongbai233.super_lead.preset.PresetDetailsResponse;
import com.zhongbai233.super_lead.preset.PresetEditKey;
import com.zhongbai233.super_lead.preset.PresetListRequest;
import com.zhongbai233.super_lead.preset.PresetListResponse;
import com.zhongbai233.super_lead.preset.PresetPromptOpen;
import com.zhongbai233.super_lead.preset.PresetPromptResponse;
import com.zhongbai233.super_lead.preset.PresetServerManager;
import com.zhongbai233.super_lead.preset.OpenZoneCreateScreen;
import com.zhongbai233.super_lead.preset.PhysicsZoneSelectionManager;
import com.zhongbai233.super_lead.preset.SyncPhysicsZones;
import com.zhongbai233.super_lead.preset.ZoneCreateRequest;
import com.zhongbai233.super_lead.preset.ZoneListRequest;
import com.zhongbai233.super_lead.preset.ZoneSelectionClick;
import com.zhongbai233.super_lead.preset.ZoneSelectionState;
import com.zhongbai233.super_lead.preset.client.PhysicsZonesClient;
import com.zhongbai233.super_lead.serverconfig.ServerConfigManager;
import com.zhongbai233.super_lead.serverconfig.ServerConfigRequest;
import com.zhongbai233.super_lead.serverconfig.ServerConfigSet;
import com.zhongbai233.super_lead.serverconfig.ServerConfigSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class SuperLeadPayloads {
    private static final Map<NetworkKey, Map<UUID, LeadConnection>> LAST_SENT_BY_DIMENSION = new java.util.HashMap<>();

    private SuperLeadPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(SyncConnections.TYPE, SyncConnections.STREAM_CODEC, SuperLeadPayloads::handleSyncConnections)
                .playToClient(SyncConnectionChanges.TYPE, SyncConnectionChanges.STREAM_CODEC, SuperLeadPayloads::handleSyncConnectionChanges)
                .playToClient(ItemPulse.TYPE, ItemPulse.STREAM_CODEC, SuperLeadPayloads::handleItemPulse)
                .playToClient(RopeContactPulse.TYPE, RopeContactPulse.STREAM_CODEC, SuperLeadPayloads::handleRopeContactPulse)
                .playToClient(RopeNodesPulse.TYPE, RopeNodesPulse.STREAM_CODEC, SuperLeadPayloads::handleRopeNodesPulse)
                .playToClient(PresetPromptOpen.TYPE, PresetPromptOpen.STREAM_CODEC, SuperLeadPayloads::handlePresetPromptOpen)
                .playToClient(PresetApplyOverrides.TYPE, PresetApplyOverrides.STREAM_CODEC, SuperLeadPayloads::handlePresetApply)
                .playToClient(PresetClearOverrides.TYPE, PresetClearOverrides.STREAM_CODEC, SuperLeadPayloads::handlePresetClear)
                .playToClient(PresetListResponse.TYPE, PresetListResponse.STREAM_CODEC, SuperLeadPayloads::handlePresetListResponse)
                .playToClient(PresetDetailsResponse.TYPE, PresetDetailsResponse.STREAM_CODEC, SuperLeadPayloads::handlePresetDetailsResponse)
                .playToClient(SyncPhysicsZones.TYPE, SyncPhysicsZones.STREAM_CODEC, SuperLeadPayloads::handleSyncPhysicsZones)
                .playToClient(ZoneSelectionState.TYPE, ZoneSelectionState.STREAM_CODEC, SuperLeadPayloads::handleZoneSelectionState)
                .playToClient(OpenZoneCreateScreen.TYPE, OpenZoneCreateScreen.STREAM_CODEC, SuperLeadPayloads::handleOpenZoneCreateScreen)
                .playToClient(ServerConfigSnapshot.TYPE, ServerConfigSnapshot.STREAM_CODEC, SuperLeadPayloads::handleServerConfigSnapshot)
                .playToServer(UseConnectionAction.TYPE, UseConnectionAction.STREAM_CODEC, SuperLeadPayloads::handleUseConnectionAction)
                .playToServer(AddRopeAttachment.TYPE, AddRopeAttachment.STREAM_CODEC, SuperLeadPayloads::handleAddRopeAttachment)
                .playToServer(RemoveRopeAttachment.TYPE, RemoveRopeAttachment.STREAM_CODEC, SuperLeadPayloads::handleRemoveRopeAttachment)
                .playToServer(ToggleRopeAttachmentForm.TYPE, ToggleRopeAttachmentForm.STREAM_CODEC, SuperLeadPayloads::handleToggleRopeAttachmentForm)
                .playToServer(PresetPromptResponse.TYPE, PresetPromptResponse.STREAM_CODEC, SuperLeadPayloads::handlePresetPromptResponse)
                .playToServer(PresetEditKey.TYPE, PresetEditKey.STREAM_CODEC, SuperLeadPayloads::handlePresetEditKey)
                .playToServer(PresetListRequest.TYPE, PresetListRequest.STREAM_CODEC, SuperLeadPayloads::handlePresetListRequest)
                .playToServer(PresetDetailsRequest.TYPE, PresetDetailsRequest.STREAM_CODEC, SuperLeadPayloads::handlePresetDetailsRequest)
                .playToServer(ZoneSelectionClick.TYPE, ZoneSelectionClick.STREAM_CODEC, SuperLeadPayloads::handleZoneSelectionClick)
                .playToServer(ZoneCreateRequest.TYPE, ZoneCreateRequest.STREAM_CODEC, SuperLeadPayloads::handleZoneCreateRequest)
                .playToServer(ZoneListRequest.TYPE, ZoneListRequest.STREAM_CODEC, SuperLeadPayloads::handleZoneListRequest)
                .playToServer(ServerConfigRequest.TYPE, ServerConfigRequest.STREAM_CODEC, SuperLeadPayloads::handleServerConfigRequest)
                .playToServer(ServerConfigSet.TYPE, ServerConfigSet.STREAM_CODEC, SuperLeadPayloads::handleServerConfigSet);
    }

    public static void sendToPlayer(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            PacketDistributor.sendToPlayer(player, new SyncConnections(SuperLeadSavedData.get(level).connections()));
        }
    }

    public static void sendToDimension(ServerLevel level) {
        SuperLeadNetwork.invalidateRedstoneIndex(level);
        List<LeadConnection> currentList = SuperLeadSavedData.get(level).connections();
        NetworkKey key = NetworkKey.of(level);
        Map<UUID, LeadConnection> current = snapshotById(currentList);
        Map<UUID, LeadConnection> previous = LAST_SENT_BY_DIMENSION.get(key);
        if (previous == null) {
            LAST_SENT_BY_DIMENSION.put(key, current);
            PacketDistributor.sendToPlayersInDimension(level, new SyncConnections(currentList));
            return;
        }

        ArrayList<UUID> removed = new ArrayList<>();
        for (UUID id : previous.keySet()) {
            if (!current.containsKey(id)) {
                removed.add(id);
            }
        }
        ArrayList<LeadConnection> upserts = new ArrayList<>();
        for (LeadConnection connection : currentList) {
            LeadConnection old = previous.get(connection.id());
            if (!connection.equals(old)) {
                upserts.add(connection);
            }
        }
        LAST_SENT_BY_DIMENSION.put(key, current);
        if (removed.isEmpty() && upserts.isEmpty()) {
            return;
        }

        int changeCount = removed.size() + upserts.size();
        if (changeCount > Math.max(8, currentList.size() / 2)) {
            PacketDistributor.sendToPlayersInDimension(level, new SyncConnections(currentList));
        } else {
            PacketDistributor.sendToPlayersInDimension(level, new SyncConnectionChanges(removed, upserts));
        }
    }

    private static void handleSyncConnections(SyncConnections payload, IPayloadContext context) {
        SuperLeadNetwork.replaceConnections(context.player().level(), payload.connections());
    }

    private static void handleSyncConnectionChanges(SyncConnectionChanges payload, IPayloadContext context) {
        SuperLeadNetwork.applyConnectionChanges(context.player().level(), payload.removed(), payload.upserts());
    }

    private static Map<UUID, LeadConnection> snapshotById(List<LeadConnection> connections) {
        LinkedHashMap<UUID, LeadConnection> out = new LinkedHashMap<>(Math.max(16, connections.size() * 2));
        for (LeadConnection connection : connections) {
            out.put(connection.id(), connection);
        }
        return Map.copyOf(out);
    }

    public static void sendItemPulse(ServerLevel level, ItemPulse pulse) {
        PacketDistributor.sendToPlayersInDimension(level, pulse);
    }

    private static void handleItemPulse(ItemPulse payload, IPayloadContext context) {
        com.zhongbai233.super_lead.lead.client.render.ItemFlowAnimator.queue(payload);
    }

    private static void handleRopeContactPulse(RopeContactPulse payload, IPayloadContext context) {
        com.zhongbai233.super_lead.lead.client.render.RopeContactsClient.apply(payload);
    }

    private static void handleRopeNodesPulse(RopeNodesPulse payload, IPayloadContext context) {
        com.zhongbai233.super_lead.lead.client.render.RopeServerNodesClient.apply(payload);
    }

    private static void handleSyncPhysicsZones(SyncPhysicsZones payload, IPayloadContext context) {
        PhysicsZonesClient.apply(payload);
    }

    private static void handleZoneSelectionState(ZoneSelectionState payload, IPayloadContext context) {
        com.zhongbai233.super_lead.preset.client.ZoneSelectionClient.apply(payload);
    }

    private static void handleOpenZoneCreateScreen(OpenZoneCreateScreen payload, IPayloadContext context) {
        com.zhongbai233.super_lead.preset.client.ZoneSelectionClient.openCreate(payload);
    }

    private static void handleAddRopeAttachment(AddRopeAttachment payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        net.minecraft.world.InteractionHand hand = payload.useOffhand()
                ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
        net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) return;
        if (!RopeAttachmentItems.isAttachable(stack)) return;
        // Items must be "tied on" with string in the opposite hand. Creative bypasses both checks.
        net.minecraft.world.InteractionHand bindHand = payload.useOffhand()
                ? net.minecraft.world.InteractionHand.MAIN_HAND
                : net.minecraft.world.InteractionHand.OFF_HAND;
        net.minecraft.world.item.ItemStack bindStack = player.getItemInHand(bindHand);
        boolean creative = player.getAbilities().instabuild;
        if (!creative && !bindStack.is(net.minecraft.world.item.Items.STRING)) return;
        java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
        if (opt.isEmpty()) return;
        LeadConnection connection = opt.get();
        if (connection.kind() != LeadKind.NORMAL && connection.kind() != LeadKind.REDSTONE) return;
        if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection)) return;
        SuperLeadNetwork.addAttachment(level, connection, payload.t(), stack.copyWithCount(1));
        if (!creative) {
            stack.shrink(1);
            bindStack.shrink(1);
        }
    }

    private static void handleRemoveRopeAttachment(RemoveRopeAttachment payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
        if (opt.isEmpty()) return;
        LeadConnection connection = opt.get();
        if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection)) return;
        SuperLeadNetwork.removeAttachment(level, connection, payload.attachmentId(), player);
    }

    private static void handleToggleRopeAttachmentForm(ToggleRopeAttachmentForm payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
        if (opt.isEmpty()) return;
        LeadConnection connection = opt.get();
        if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection)) return;
        SuperLeadNetwork.toggleAttachmentForm(level, connection, payload.attachmentId());
    }

    private static void handleUseConnectionAction(UseConnectionAction payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        LeadConnectionAction[] actions = LeadConnectionAction.values();
        if (payload.actionOrdinal() < 0 || payload.actionOrdinal() >= actions.length) return;
        LeadConnectionAction action = actions[payload.actionOrdinal()];
        net.minecraft.world.InteractionHand hand = payload.useOffhand()
                ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
        net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
        if (!action.matches(stack)) return;

        java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
        if (opt.isEmpty()) return;
        LeadConnection connection = opt.get();
        if (!action.canTarget(connection)) return;

        if (!SuperLeadNetwork.canUseClientPickedConnection(level, player, connection)) {
            return;
        }

        if (action.applyTo(level, player, connection)) {
            action.consumeSuccessfulUse(stack, player, hand);
        }
    }

    private static void handlePresetPromptOpen(PresetPromptOpen payload, IPayloadContext context) {
        com.zhongbai233.super_lead.preset.client.PresetClientHandler.onPromptOpen(payload);
    }

    private static void handlePresetApply(PresetApplyOverrides payload, IPayloadContext context) {
        com.zhongbai233.super_lead.preset.client.PresetClientHandler.onApply(payload);
    }

    private static void handlePresetClear(PresetClearOverrides payload, IPayloadContext context) {
        com.zhongbai233.super_lead.preset.client.PresetClientHandler.onClear();
    }

    private static void handlePresetListResponse(PresetListResponse payload, IPayloadContext context) {
        com.zhongbai233.super_lead.preset.client.PresetClientHandler.onListResponse(payload);
    }

    private static void handlePresetPromptResponse(PresetPromptResponse payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        PresetServerManager.handleResponse(player, payload);
    }

    private static void handlePresetEditKey(PresetEditKey payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        net.minecraft.server.permissions.Permission.HasCommandLevel op =
                new net.minecraft.server.permissions.Permission.HasCommandLevel(
                        net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS);
        if (!player.permissions().hasPermission(op)) return;
        net.minecraft.server.MinecraftServer server = player.level().getServer();
        if (server == null) return;
        PresetServerManager.editKey(server, payload);
    }

    private static void handlePresetListRequest(PresetListRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        PresetServerManager.handleListRequest(player);
    }

    private static void handlePresetDetailsRequest(PresetDetailsRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        PresetServerManager.handleDetailsRequest(player, payload.name());
    }

    private static void handleZoneSelectionClick(ZoneSelectionClick payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        PhysicsZoneSelectionManager.handleClick(player, payload.pos());
    }

    private static void handleZoneCreateRequest(ZoneCreateRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        PhysicsZoneSelectionManager.createZone(player, payload);
    }

    private static void handleZoneListRequest(ZoneListRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (player.level() instanceof ServerLevel level) {
            PresetServerManager.sendZones(player, level);
        }
    }

    private static void handlePresetDetailsResponse(PresetDetailsResponse payload, IPayloadContext context) {
        com.zhongbai233.super_lead.preset.client.PresetClientHandler.onDetailsResponse(payload);
    }

    private static void handleServerConfigRequest(ServerConfigRequest payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerConfigManager.sendSnapshot(player);
    }

    private static void handleServerConfigSet(ServerConfigSet payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        ServerConfigManager.handleSet(player, payload);
    }

    private static void handleServerConfigSnapshot(ServerConfigSnapshot payload, IPayloadContext context) {
        com.zhongbai233.super_lead.serverconfig.client.ServerConfigClient.onSnapshot(payload);
    }
}
