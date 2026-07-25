package com.zhongbai233.super_lead.lead.client.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RopeTuningEditingTest {
    @AfterEach
    void resetEditingMode() {
        RopeTuning.setTransparentEditingMode(false);
    }

    @Test
    void editingModeMakesOnlyFullyTransparentColorsVisible() {
        RopeTuning.setTransparentEditingMode(true);
        assertEquals(0x60FFFFFF, RopeTuning.editingColor(0x00FFFFFF));
        assertEquals(0x80808080, RopeTuning.editingColor(0x80808080));
        assertEquals(0xFF808080, RopeTuning.editingColor(0xFF808080));
    }

    @Test
    void configuredTransparencyRemainsDetectableWhileEditing() {
        RopeTuning.setTransparentEditingMode(true);

        assertEquals(true, RopeTuning.colorsFullyTransparent(0x00112233, 0x00445566));
        assertEquals(false, RopeTuning.colorsFullyTransparent(
            RopeTuning.editingColor(0x00112233), RopeTuning.editingColor(0x00445566)));
    }

    @Test
    void mixedAlphaPaletteIsNotAnInvisibleEditingTarget() {
        assertEquals(false, RopeTuning.colorsFullyTransparent(0x00112233, 0xFF445566));
    }
}