package com.zhongbai233.super_lead.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ColorTuningTypeTest {
    private final ColorTuningType type = new ColorTuningType();

    @Test
    void legacyRgbInputsBecomeOpaqueArgb() {
        assertEquals(0xFF563B22, type.parse("#563B22"));
        assertEquals(0xFF563B22, type.parse("0x563B22"));
        assertEquals(0xFF563B22, type.parse(Integer.toString(0x563B22)));
    }

    @Test
    void fullArgbInputsPreserveEveryChannel() {
        assertEquals(0x80563B22, type.parse("#80563B22"));
        assertEquals(0x00563B22, type.parse("0x00563B22"));
        assertEquals(0xFFFFFFFF, type.parse("#FFFFFFFF"));
        assertEquals("#80563B22", type.format(0x80563B22));
    }

    @Test
    void malformedHexLengthsAreRejected() {
        assertThrows(NumberFormatException.class, () -> type.parse("#12345"));
        assertThrows(NumberFormatException.class, () -> type.parse("#1234567"));
        assertThrows(NumberFormatException.class, () -> type.parse("#123456789"));
    }
}