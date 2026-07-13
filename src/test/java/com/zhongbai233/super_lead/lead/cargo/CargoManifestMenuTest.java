package com.zhongbai233.super_lead.lead.cargo;

import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

class CargoManifestMenuTest {
    @Test
    void completedGhostQuickMoveSignalsNoRemainingStack() {
        ItemStack result = CargoManifestMenu.completedGhostQuickMove();

        assertSame(ItemStack.EMPTY, result);
    }
}