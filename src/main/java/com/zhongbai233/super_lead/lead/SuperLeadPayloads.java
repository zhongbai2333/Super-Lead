package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class SuperLeadPayloads {
    private SuperLeadPayloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(SyncConnections.TYPE, SyncConnections.STREAM_CODEC, SuperLeadPayloads::handleSyncConnections)
                .playToClient(ItemPulse.TYPE, ItemPulse.STREAM_CODEC, SuperLeadPayloads::handleItemPulse)
                .playToServer(UseConnectionAction.TYPE, UseConnectionAction.STREAM_CODEC, SuperLeadPayloads::handleUseConnectionAction);
    }

    public static void sendToPlayer(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            PacketDistributor.sendToPlayer(player, new SyncConnections(SuperLeadSavedData.get(level).connections()));
        }
    }

    public static void sendToDimension(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, new SyncConnections(SuperLeadSavedData.get(level).connections()));
    }

    private static void handleSyncConnections(SyncConnections payload, IPayloadContext context) {
        SuperLeadNetwork.replaceConnections(context.player().level(), payload.connections());
    }

    public static void sendItemPulse(ServerLevel level, ItemPulse pulse) {
        PacketDistributor.sendToPlayersInDimension(level, pulse);
    }

    private static void handleItemPulse(ItemPulse payload, IPayloadContext context) {
        com.zhongbai233.super_lead.lead.client.ItemFlowAnimator.queue(payload);
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
        if (!action.matches(stack)) return; // held item changed during latency

        java.util.Optional<LeadConnection> opt = SuperLeadNetwork.findConnectionById(level, payload.connectionId());
        if (opt.isEmpty()) return;
        LeadConnection connection = opt.get();
        if (!action.canTarget(connection)) return;

        // Anti-cheat: ensure player is roughly within reach of one of the rope endpoints.
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition(1.0F);
        double maxDistSqr = SuperLeadNetwork.MAX_LEASH_DISTANCE * SuperLeadNetwork.MAX_LEASH_DISTANCE;
        if (eye.distanceToSqr(connection.from().attachmentPoint(level)) > maxDistSqr
                && eye.distanceToSqr(connection.to().attachmentPoint(level)) > maxDistSqr) {
            return;
        }

        if (action.applyTo(level, player, connection)) {
            action.consumeSuccessfulUse(stack, player, hand);
        }
    }

    public record UseConnectionAction(UUID connectionId, int actionOrdinal, boolean useOffhand) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UseConnectionAction> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(Super_lead.MODID, "use_connection_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UseConnectionAction> STREAM_CODEC = CustomPacketPayload.codec(
                UseConnectionAction::write,
                UseConnectionAction::read);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(connectionId);
            buffer.writeVarInt(actionOrdinal);
            buffer.writeBoolean(useOffhand);
        }

        private static UseConnectionAction read(RegistryFriendlyByteBuf buffer) {
            return new UseConnectionAction(buffer.readUUID(), buffer.readVarInt(), buffer.readBoolean());
        }
    }

    public record ItemPulse(UUID connectionId, boolean reverse, long startTick, int durationTicks) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ItemPulse> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(Super_lead.MODID, "item_pulse"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemPulse> STREAM_CODEC = CustomPacketPayload.codec(
                ItemPulse::write,
                ItemPulse::read);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(connectionId);
            buffer.writeBoolean(reverse);
            buffer.writeLong(startTick);
            buffer.writeVarInt(durationTicks);
        }

        private static ItemPulse read(RegistryFriendlyByteBuf buffer) {
            return new ItemPulse(buffer.readUUID(), buffer.readBoolean(), buffer.readLong(), buffer.readVarInt());
        }
    }

    public record SyncConnections(List<LeadConnection> connections) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SyncConnections> TYPE = new CustomPacketPayload.Type<>(
                Identifier.fromNamespaceAndPath(Super_lead.MODID, "sync_connections"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncConnections> STREAM_CODEC = CustomPacketPayload.codec(
                SyncConnections::write,
                SyncConnections::read);

        public SyncConnections {
            connections = List.copyOf(connections);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(connections.size());
            for (LeadConnection connection : connections) {
                buffer.writeUUID(connection.id());
                writeAnchor(buffer, connection.from());
                writeAnchor(buffer, connection.to());
                buffer.writeEnum(connection.kind());
                buffer.writeVarInt(connection.power());
                buffer.writeVarInt(connection.tier());
                buffer.writeVarInt(connection.extractAnchor());
            }
        }

        private static SyncConnections read(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            java.util.ArrayList<LeadConnection> connections = new java.util.ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                connections.add(new LeadConnection(
                        buffer.readUUID(),
                        readAnchor(buffer),
                        readAnchor(buffer),
                        buffer.readEnum(LeadKind.class),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt()));
            }
            return new SyncConnections(connections);
        }

        private static void writeAnchor(RegistryFriendlyByteBuf buffer, LeadAnchor anchor) {
            buffer.writeBlockPos(anchor.pos());
            buffer.writeEnum(anchor.face());
        }

        private static LeadAnchor readAnchor(RegistryFriendlyByteBuf buffer) {
            return new LeadAnchor(buffer.readBlockPos(), buffer.readEnum(Direction.class));
        }
    }
}
