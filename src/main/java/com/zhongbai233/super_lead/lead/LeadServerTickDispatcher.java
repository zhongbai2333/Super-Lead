package com.zhongbai233.super_lead.lead;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class LeadServerTickDispatcher {
    private LeadServerTickDispatcher() {
    }

    static void tick(Level level) {
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        SuperLeadNetwork.tickStuckBreaks(serverLevel);
        SuperLeadNetwork.tickRedstone(serverLevel);
        SuperLeadNetwork.tickEnergy(serverLevel);
        SuperLeadNetwork.tickItem(serverLevel);
        SuperLeadNetwork.tickFluid(serverLevel);
        SuperLeadNetwork.tickPressurized(serverLevel);
        SuperLeadNetwork.tickThermal(serverLevel);
        SuperLeadNetwork.tickAeNetwork(serverLevel);
        RopeContactTracker.tickRopeContacts(serverLevel);
        RopeTripController.tick(serverLevel);
        ParrotRopePerchController.tick(serverLevel);
        ZiplineController.tick(serverLevel);
    }
}
