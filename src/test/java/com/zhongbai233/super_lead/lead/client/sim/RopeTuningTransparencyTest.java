package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.zhongbai233.super_lead.lead.LeadKind;
import org.junit.jupiter.api.Test;

class RopeTuningTransparencyTest {
    @Test
    void defaultPaletteIsNotFullyTransparent() {
        assertFalse(RopeTuning.localDefaults().isFullyTransparent(LeadKind.NORMAL));
    }
}