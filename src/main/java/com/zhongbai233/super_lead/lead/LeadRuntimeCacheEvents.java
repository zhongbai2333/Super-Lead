package com.zhongbai233.super_lead.lead;

import com.zhongbai233.super_lead.Super_lead;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = Super_lead.MODID)
final class LeadRuntimeCacheEvents {
    private LeadRuntimeCacheEvents() {
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LeadTransferService.discardLevelState(level);
            LeadSignalService.clearEnergySafetyState(level);
            RopeContactTracker.clear(level);
            RopeTripController.clear(level);
        }
    }
}
