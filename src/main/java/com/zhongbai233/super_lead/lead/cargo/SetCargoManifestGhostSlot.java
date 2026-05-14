package com.zhongbai233.super_lead.lead.cargo;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public record SetCargoManifestGhostSlot(int containerId, int slotId, ItemStack stack)
        implements CustomPacketPayload {
    public static final Type<SetCargoManifestGhostSlot> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "set_cargo_manifest_ghost_slot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCargoManifestGhostSlot> STREAM_CODEC = CustomPacketPayload
            .codec(SetCargoManifestGhostSlot::write, SetCargoManifestGhostSlot::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(slotId);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
    }

    private static SetCargoManifestGhostSlot read(RegistryFriendlyByteBuf buffer) {
        return new SetCargoManifestGhostSlot(buffer.readVarInt(), buffer.readVarInt(),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
    }
}