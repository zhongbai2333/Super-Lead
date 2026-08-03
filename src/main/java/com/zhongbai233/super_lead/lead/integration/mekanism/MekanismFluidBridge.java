package com.zhongbai233.super_lead.lead.integration.mekanism;

import com.zhongbai233.super_lead.lead.LeadAnchor;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Resolves Mekanism's fluid capability into the common NeoForge transfer API.
 *
 * <p>The transfer algorithm remains in {@code LeadTransferService}; this class
 * only provides the Mekanism-specific capability key. Keeping that boundary
 * prevents Mekanism fluid networks from being traversed twice in one tick.
 */
public final class MekanismFluidBridge {
    private MekanismFluidBridge() {
    }

    public static ResourceHandler<FluidResource> handler(ServerLevel level, LeadAnchor anchor) {
        if (level == null || anchor == null) {
            return null;
        }
        ResourceHandler<FluidResource> handler = level.getCapability(
                mekanism.common.capabilities.Capabilities.FLUID.block(),
                anchor.pos(), anchor.face());
        if (handler == null) {
            handler = level.getCapability(
                    mekanism.common.capabilities.Capabilities.FLUID.block(),
                    anchor.pos(), null);
        }
        return handler;
    }

    public static boolean hasHandler(ServerLevel level, LeadAnchor anchor) {
        return handler(level, anchor) != null;
    }
}
