package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.lead.cargo.CargoManifestMenu;
import com.zhongbai233.super_lead.lead.cargo.SetCargoManifestGhostSlot;
import com.zhongbai233.super_lead.lead.cargo.UpdateCargoManifestOptions;
import com.zhongbai233.super_lead.lead.cargo.UpdateCargoManifestTag;
import com.zhongbai233.super_lead.preset.PresetApplyOverrides;
import com.zhongbai233.super_lead.preset.PresetBinderCreate;
import com.zhongbai233.super_lead.preset.PresetBinderToggleRope;
import com.zhongbai233.super_lead.preset.PresetDetailsRequest;
import com.zhongbai233.super_lead.preset.PresetDetailsResponse;
import com.zhongbai233.super_lead.preset.PresetEditKey;
import com.zhongbai233.super_lead.preset.PresetListResponse;
import com.zhongbai233.super_lead.preset.PresetPromptOpen;
import com.zhongbai233.super_lead.preset.PresetPromptResponse;
import com.zhongbai233.super_lead.preset.PresetServerManager;
import com.zhongbai233.super_lead.preset.ServerQuery;
import com.zhongbai233.super_lead.preset.PhysicsZoneSelectionManager;
import com.zhongbai233.super_lead.preset.SyncDimensionPresets;
import com.zhongbai233.super_lead.preset.SyncPhysicsZones;
import com.zhongbai233.super_lead.preset.ZoneCreateRequest;
import com.zhongbai233.super_lead.preset.ZoneSelectionClick;
import com.zhongbai233.super_lead.serverconfig.ServerConfigManager;
import com.zhongbai233.super_lead.serverconfig.ServerConfigSet;
import com.zhongbai233.super_lead.serverconfig.ServerConfigSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Central registration point for Super Lead custom payloads.
 *
 * <p>
 * Payload record classes stay small on purpose: each one owns its wire id and
 * codec. This class wires those codecs to NeoForge directions and then
 * delegates
 * to gameplay managers. Keep transport validation here, but move substantial
 * domain logic into the matching lead/cargo/preset/server-config service.
 */
public final class SuperLeadPayloads {
    private SuperLeadPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar
                .playToServer(UseConnectionAction.TYPE, UseConnectionAction.STREAM_CODEC,
                        SuperLeadPayloads::handleUseConnectionAction)
                .playToServer(StartZipline.TYPE, StartZipline.STREAM_CODEC,
                        SuperLeadPayloads::handleStartZipline)
                .playToServer(ClientRopeContactReport.TYPE, ClientRopeContactReport.STREAM_CODEC,
                        SuperLeadPayloads::handleClientRopeContactReport)
                .playToServer(AddRopeAttachment.TYPE, AddRopeAttachment.STREAM_CODEC,
                        SuperLeadPayloads::handleAddRopeAttachment)
                .playToServer(RemoveRopeAttachment.TYPE, RemoveRopeAttachment.STREAM_CODEC,
                        SuperLeadPayloads::handleRemoveRopeAttachment)
                .playToServer(ToggleRopeAttachmentForm.TYPE, ToggleRopeAttachmentForm.STREAM_CODEC,
                        SuperLeadPayloads::handleToggleRopeAttachmentForm)
                .playToServer(OpenRopeAeTerminal.TYPE, OpenRopeAeTerminal.STREAM_CODEC,
                        SuperLeadPayloads::handleOpenRopeAeTerminal)
                .playToServer(UpdateRopeAttachmentSignText.TYPE, UpdateRopeAttachmentSignText.STREAM_CODEC,
                        SuperLeadPayloads::handleUpdateRopeAttachmentSignText)
                .playToServer(UpdateSignAttachmentAppearance.TYPE, UpdateSignAttachmentAppearance.STREAM_CODEC,
                        SuperLeadPayloads::handleUpdateSignAttachmentAppearance)
                .playToServer(UpdateCargoManifestOptions.TYPE, UpdateCargoManifestOptions.STREAM_CODEC,
                        SuperLeadPayloads::handleUpdateCargoManifestOptions)
                .playToServer(UpdateCargoManifestTag.TYPE, UpdateCargoManifestTag.STREAM_CODEC,
                        SuperLeadPayloads::handleUpdateCargoManifestTag)
                .playToServer(SetCargoManifestGhostSlot.TYPE, SetCargoManifestGhostSlot.STREAM_CODEC,
                        SuperLeadPayloads::handleSetCargoManifestGhostSlot)
                .playToServer(PresetPromptResponse.TYPE, PresetPromptResponse.STREAM_CODEC,
                        SuperLeadPayloads::handlePresetPromptResponse)
                .playToServer(PresetEditKey.TYPE, PresetEditKey.STREAM_CODEC, SuperLeadPayloads::handlePresetEditKey)
                .playToServer(PresetDetailsRequest.TYPE, PresetDetailsRequest.STREAM_CODEC,
                        SuperLeadPayloads::handlePresetDetailsRequest)
                .playToServer(PresetBinderCreate.TYPE, PresetBinderCreate.STREAM_CODEC,
                        SuperLeadPayloads::handlePresetBinderCreate)
                .playToServer(PresetBinderToggleRope.TYPE, PresetBinderToggleRope.STREAM_CODEC,
                        SuperLeadPayloads::handlePresetBinderToggleRope)
                .playToServer(ZoneSelectionClick.TYPE, ZoneSelectionClick.STREAM_CODEC,
                        SuperLeadPayloads::handleZoneSelectionClick)
                .playToServer(ZoneCreateRequest.TYPE, ZoneCreateRequest.STREAM_CODEC,
                        SuperLeadPayloads::handleZoneCreateRequest)
                .playToServer(ServerQuery.TYPE, ServerQuery.STREAM_CODEC,
                        SuperLeadPayloads::handleServerQuery)
                .playToServer(ServerConfigSet.TYPE, ServerConfigSet.STREAM_CODEC,
                        SuperLeadPayloads::handleServerConfigSet);

        if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) {
            return;
        }

        registrar
                .playToClient(SyncRopeChunk.TYPE, SyncRopeChunk.STREAM_CODEC, SuperLeadPayloads::ignoreClientPayload)
                .playToClient(UnloadRopeChunk.TYPE, UnloadRopeChunk.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(ClearRopeCache.TYPE, ClearRopeCache.STREAM_CODEC, SuperLeadPayloads::ignoreClientPayload)
                .playToClient(ItemPulse.TYPE, ItemPulse.STREAM_CODEC, SuperLeadPayloads::ignoreClientPayload)
                .playToClient(RopeContactPulse.TYPE, RopeContactPulse.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(SyncZiplines.TYPE, SyncZiplines.STREAM_CODEC, SuperLeadPayloads::ignoreClientPayload)
                .playToClient(PresetPromptOpen.TYPE, PresetPromptOpen.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(PresetApplyOverrides.TYPE, PresetApplyOverrides.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(PresetListResponse.TYPE, PresetListResponse.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(PresetDetailsResponse.TYPE, PresetDetailsResponse.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(SyncDimensionPresets.TYPE, SyncDimensionPresets.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(SyncPhysicsZones.TYPE, SyncPhysicsZones.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(ServerConfigSnapshot.TYPE, ServerConfigSnapshot.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(com.zhongbai233.super_lead.preset.ZoneSelectionState.TYPE,
                        com.zhongbai233.super_lead.preset.ZoneSelectionState.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload)
                .playToClient(com.zhongbai233.super_lead.preset.OpenZoneCreateScreen.TYPE,
                        com.zhongbai233.super_lead.preset.OpenZoneCreateScreen.STREAM_CODEC,
                        SuperLeadPayloads::ignoreClientPayload);
    }

    private static <T extends CustomPacketPayload> void ignoreClientPayload(T payload, IPayloadContext context) {
    }

    public static void sendToPlayer(ServerPlayer player) {
        // Clear the client's previous dimension cache. Actual rope data is sent by
        // ChunkWatchEvent.Sent after each chunk arrives on the client.
        PacketDistributor.sendToPlayer(player, ClearRopeCache.INSTANCE);
    }

    public static void sendChunkToPlayer(ServerLevel level, ServerPlayer player, ChunkPos chunk) {
        PacketDistributor.sendToPlayer(player,
                new SyncRopeChunk(chunk, SuperLeadSavedData.get(level).connectionsForChunk(chunk)));
    }

    public static void unloadChunkForPlayer(ServerPlayer player, ChunkPos chunk) {
        PacketDistributor.sendToPlayer(player, new UnloadRopeChunk(chunk));
    }

    public static void sendToDimension(ServerLevel level) {
        SuperLeadSavedData data = SuperLeadSavedData.get(level);
        java.util.Set<Long> dirty = data.consumeDirtyChunkKeys();
        if (dirty.isEmpty()) {
            dirty = data.allChunkKeys();
        }
        for (long chunkKey : dirty) {
            sendChunkToTracking(level, SuperLeadSavedData.chunkFromKey(chunkKey));
        }
    }

    private static void sendChunkToTracking(ServerLevel level, ChunkPos chunk) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk,
                new SyncRopeChunk(chunk, SuperLeadSavedData.get(level).connectionsForChunk(chunk)));
    }

    private static void runOnServer(IPayloadContext context,
            java.util.function.BiConsumer<ServerPlayer, ServerLevel> action) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player))
                return;
            if (!(player.level() instanceof ServerLevel level))
                return;
            action.accept(player, level);
        });
    }

    private static void runOnServerPlayer(IPayloadContext context,
            java.util.function.Consumer<ServerPlayer> action) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                action.accept(player);
            }
        });
    }

    private static net.minecraft.world.InteractionHand hand(boolean useOffhand) {
        return useOffhand
                ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
    }

    private static boolean canEditCargoMenu(ServerPlayer player, CargoManifestMenu menu, int containerId) {
        return player.containerMenu == menu && menu.containerId == containerId && menu.stillValid(player);
    }

    public static void sendItemPulse(ServerLevel level, ItemPulse pulse) {
        SuperLeadSavedData.get(level).find(pulse.connectionId()).ifPresent(connection -> {
            for (long chunkKey : SuperLeadSavedData.get(level).chunksForConnection(connection.id())) {
                PacketDistributor.sendToPlayersTrackingChunk(level, SuperLeadSavedData.chunkFromKey(chunkKey), pulse);
            }
        });
    }

    private static void handleClientRopeContactReport(ClientRopeContactReport payload, IPayloadContext context) {
        runOnServer(context, (player, level) -> RopeContactTracker.acceptClientContact(level, player, payload));
    }

    private static void handleStartZipline(StartZipline payload, IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            net.minecraft.world.InteractionHand hand = hand(payload.useOffhand());
            if (!ZiplineController.isChain(player.getItemInHand(hand)))
                return;
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            ZiplineController.start(level, player, opt.get(), payload.hitPoint(), payload.hitT());
        });
    }

    private static void handleAddRopeAttachment(AddRopeAttachment payload, IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            if (!SuperLeadNetwork.canModifyRopes(player))
                return;
            net.minecraft.world.InteractionHand hand = hand(payload.useOffhand());
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty())
                return;
            if (!RopeAttachmentItems.isAttachable(stack))
                return;
            // Items must be "tied on" with string in the offhand. Creative still needs the
            // string as an explicit intent signal, but does not consume it.
            net.minecraft.world.item.ItemStack bindStack = player.getOffhandItem();
            boolean creative = player.isCreative();
            if (!bindStack.is(net.minecraft.world.item.Items.STRING))
                return;
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            LeadConnection connection = opt.get();
            if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection))
                return;
            SuperLeadNetwork.addAttachment(level, connection, payload.t(), stack.copyWithCount(1),
                    payload.frontSide());
            if (!creative) {
                stack.shrink(1);
                bindStack.shrink(1);
            }
        });
    }

    private static void handleRemoveRopeAttachment(RemoveRopeAttachment payload, IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            if (!SuperLeadNetwork.canModifyRopes(player))
                return;
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            LeadConnection connection = opt.get();
            if (!SuperLeadNetwork.canTouchConnectionForAttachmentRemoval(level, player, connection))
                return;
            // Snapshot world position before removal so the particle lands where the item
            // was.
            net.minecraft.world.phys.Vec3 particlePos = null;
            for (RopeAttachment a : connection.attachments()) {
                if (a.id().equals(payload.attachmentId())) {
                    LeadEndpointLayout.Endpoints endpoints = LeadEndpointLayout.endpoints(level, connection,
                            SuperLeadNetwork.connections(level));
                    net.minecraft.world.phys.Vec3 from = endpoints.from();
                    net.minecraft.world.phys.Vec3 to = endpoints.to();
                    ServerRopeCurve.Shape shape = ServerRopeCurve.from(level, connection, from, to);
                    particlePos = ServerRopeCurve.point(shape, a.t()).add(0.0D, -0.25D, 0.0D);
                    break;
                }
            }
            SuperLeadNetwork.removeAttachment(level, connection, payload.attachmentId(), player);
            if (particlePos != null) {
                level.sendParticles(
                        net.minecraft.core.particles.ParticleTypes.CRIT,
                        particlePos.x, particlePos.y, particlePos.z,
                        12, 0.25D, 0.25D, 0.25D, 0.10D);
                level.sendParticles(
                        net.minecraft.core.particles.ParticleTypes.POOF,
                        particlePos.x, particlePos.y, particlePos.z,
                        6, 0.20D, 0.20D, 0.20D, 0.08D);
            }
        });
    }

    private static void handleToggleRopeAttachmentForm(ToggleRopeAttachmentForm payload, IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            if (!SuperLeadNetwork.canModifyRopes(player))
                return;
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            LeadConnection connection = opt.get();
            if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection))
                return;
            SuperLeadNetwork.toggleAttachmentForm(level, connection, payload.attachmentId());
        });
    }

    private static void handleOpenRopeAeTerminal(OpenRopeAeTerminal payload, IPayloadContext context) {
        if (!net.neoforged.fml.ModList.get().isLoaded("ae2"))
            return;
        runOnServer(context, (player, level) -> {
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            LeadConnection connection = opt.get();
            if (connection.kind() != LeadKind.AE_NETWORK)
                return;
            if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection))
                return;
            for (RopeAttachment attachment : connection.attachments()) {
                if (!attachment.id().equals(payload.attachmentId()))
                    continue;
                if (com.zhongbai233.super_lead.lead.integration.ae2.AE2NetworkBridge
                        .isTerminalItem(attachment.stack())) {
                    com.zhongbai233.super_lead.lead.integration.ae2.AE2NetworkBridge.openTerminal(level, player,
                            connection, attachment);
                }
                return;
            }
        });
    }

    private static void handleUpdateRopeAttachmentSignText(UpdateRopeAttachmentSignText payload,
            IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            if (!SuperLeadNetwork.canModifyRopes(player))
                return;
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            LeadConnection connection = opt.get();
            if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection))
                return;
            SuperLeadNetwork.updateAttachmentSignText(level, connection, payload.attachmentId(),
                    payload.frontText(), payload.lines());
        });
    }

    private static void handleUpdateSignAttachmentAppearance(UpdateSignAttachmentAppearance payload,
            IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            if (!SuperLeadNetwork.canModifyRopes(player))
                return;
            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            LeadConnection connection = opt.get();
            if (!SuperLeadNetwork.canTouchConnectionForAttachment(level, player, connection))
                return;
            if (payload.operation() == UpdateSignAttachmentAppearance.OP_GLOW) {
                SuperLeadNetwork.applySignGlow(level, connection, payload.attachmentId(), payload.frontText());
            } else if (payload.operation() == UpdateSignAttachmentAppearance.OP_DYE) {
                net.minecraft.world.item.DyeColor color = net.minecraft.world.item.DyeColor.byId(payload.dyeColor());
                if (color != null) {
                    SuperLeadNetwork.applySignDye(level, connection, payload.attachmentId(), color,
                            payload.frontText());
                }
            }
        });
    }

    private static void handleUpdateCargoManifestOptions(UpdateCargoManifestOptions payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> {
            if (player.containerMenu instanceof CargoManifestMenu menu
                    && canEditCargoMenu(player, menu, payload.containerId())) {
                menu.setOptions(payload.whitelist(), payload.matchNbt());
            }
        });
    }

    private static void handleUpdateCargoManifestTag(UpdateCargoManifestTag payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> {
            if (player.containerMenu instanceof CargoManifestMenu menu
                    && canEditCargoMenu(player, menu, payload.containerId())) {
                if (payload.add()) {
                    menu.addTag(payload.tag());
                } else {
                    menu.removeTag(payload.tag());
                }
            }
        });
    }

    private static void handleSetCargoManifestGhostSlot(SetCargoManifestGhostSlot payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> {
            if (player.containerMenu instanceof CargoManifestMenu menu
                    && canEditCargoMenu(player, menu, payload.containerId())) {
                menu.setGhostSlotFromExternal(payload.slotId(), payload.stack());
            }
        });
    }

    private static void handleUseConnectionAction(UseConnectionAction payload, IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            if (!SuperLeadNetwork.canModifyRopes(player))
                return;
            LeadConnectionAction[] actions = LeadConnectionAction.values();
            if (payload.actionOrdinal() < 0 || payload.actionOrdinal() >= actions.length)
                return;
            LeadConnectionAction action = actions[payload.actionOrdinal()];
            net.minecraft.world.InteractionHand hand = hand(payload.useOffhand());
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (!action.matches(stack))
                return;

            java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
            if (opt.isEmpty())
                return;
            LeadConnection connection = opt.get();
            if (!action.canTarget(connection))
                return;

            if (!SuperLeadNetwork.canUseClientPickedConnection(level, player, connection,
                    payload.hitPoint(), payload.hitT())) {
                return;
            }

            if (action.applyTo(level, player, connection)) {
                action.consumeSuccessfulUse(stack, player, hand);
            }
        });
    }

    private static void handlePresetPromptResponse(PresetPromptResponse payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> PresetServerManager.handleResponse(player, payload));
    }

    private static void handlePresetEditKey(PresetEditKey payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> {
            net.minecraft.server.MinecraftServer server = player.level().getServer();
            if (server == null)
                return;
            PresetServerManager.editKey(server, player, payload);
        });
    }

    private static void handlePresetDetailsRequest(PresetDetailsRequest payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> PresetServerManager.handleDetailsRequest(player, payload.name()));
    }

    private static void handlePresetBinderCreate(PresetBinderCreate payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> {
            net.minecraft.world.InteractionHand hand = hand(payload.useOffhand());
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (!SuperLeadItems.isPresetBinder(stack))
                return;
            PresetServerManager.createPlayerPreset(player, stack, payload.displayName());
        });
    }

    private static void handlePresetBinderToggleRope(PresetBinderToggleRope payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> {
            net.minecraft.world.InteractionHand hand = hand(payload.useOffhand());
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (!SuperLeadItems.isPresetBinder(stack))
                return;
            PresetServerManager.toggleBoundPresetInView(player, stack);
        });
    }

    private static void handleZoneSelectionClick(ZoneSelectionClick payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> PhysicsZoneSelectionManager.handleClick(player, payload.pos()));
    }

    private static void handleZoneCreateRequest(ZoneCreateRequest payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> PhysicsZoneSelectionManager.createZone(player, payload));
    }

    private static void handleServerQuery(ServerQuery payload, IPayloadContext context) {
        runOnServer(context, (player, level) -> {
            switch (payload.kind()) {
                case PRESET_LIST -> PresetServerManager.handleListRequest(player);
                case PRESET_EXPORT -> PresetServerManager.exportPresets(player);
                case PRESET_IMPORT -> PresetServerManager.importPresets(player);
                case ZONE_LIST -> {
                    if (PresetServerManager.canManage(player)) {
                        PresetServerManager.sendZones(player, level);
                    }
                }
                case SERVER_CONFIG -> ServerConfigManager.sendSnapshot(player);
            }
        });
    }

    private static void handleServerConfigSet(ServerConfigSet payload, IPayloadContext context) {
        runOnServerPlayer(context, player -> ServerConfigManager.handleSet(player, payload));
    }

}
