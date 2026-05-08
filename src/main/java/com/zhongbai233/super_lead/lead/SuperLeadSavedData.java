package com.zhongbai233.super_lead.lead;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.super_lead.Super_lead;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class SuperLeadSavedData extends SavedData {
    public static final Codec<SuperLeadSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    LeadConnection.CODEC.listOf().optionalFieldOf("connections", List.of()).forGetter(SuperLeadSavedData::connections))
            .apply(instance, SuperLeadSavedData::new));

    public static final SavedDataType<SuperLeadSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Super_lead.MODID, "lead_connections"),
            SuperLeadSavedData::new,
            CODEC);

    private final List<LeadConnection> connections = new ArrayList<>();

    public SuperLeadSavedData() {}

    public SuperLeadSavedData(List<LeadConnection> connections) {
        this.connections.addAll(connections);
    }

    public static SuperLeadSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<LeadConnection> connections() {
        return Collections.unmodifiableList(connections);
    }

    public void add(LeadConnection connection) {
        connections.add(connection);
        setDirty();
    }

    public boolean removeIf(Predicate<LeadConnection> predicate) {
        boolean removed = connections.removeIf(predicate);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public boolean update(UUID id, UnaryOperator<LeadConnection> updater, boolean markDirty) {
        for (int i = 0; i < connections.size(); i++) {
            LeadConnection oldConnection = connections.get(i);
            if (!oldConnection.id().equals(id)) {
                continue;
            }

            LeadConnection newConnection = updater.apply(oldConnection);
            if (newConnection.equals(oldConnection)) {
                return false;
            }
            connections.set(i, newConnection);
            if (markDirty) {
                setDirty();
            }
            return true;
        }
        return false;
    }
}
