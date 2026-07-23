package com.zhongbai233.super_lead.lead.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RopeAttachmentRendererTest {
    @Test
    void attachmentFrameSamplesSymmetricallyAroundInteriorPoint() {
        assertEquals(4.4D, RopeAttachmentRenderer.attachmentFrameSampleStart(5.0D, 10.0D, 0.6D), 1.0e-9D);
        assertEquals(5.6D, RopeAttachmentRenderer.attachmentFrameSampleEnd(5.0D, 10.0D, 0.6D), 1.0e-9D);
    }

    @Test
    void attachmentFrameKeepsWideWindowNearRopeEnds() {
        assertEquals(0.0D, RopeAttachmentRenderer.attachmentFrameSampleStart(0.1D, 10.0D, 0.6D), 1.0e-9D);
        assertEquals(1.2D, RopeAttachmentRenderer.attachmentFrameSampleEnd(0.1D, 10.0D, 0.6D), 1.0e-9D);
        assertEquals(8.8D, RopeAttachmentRenderer.attachmentFrameSampleStart(9.9D, 10.0D, 0.6D), 1.0e-9D);
        assertEquals(10.0D, RopeAttachmentRenderer.attachmentFrameSampleEnd(9.9D, 10.0D, 0.6D), 1.0e-9D);
    }

    @Test
    void attachmentFrameClampsDegenerateInputs() {
        assertEquals(0.0D, RopeAttachmentRenderer.attachmentFrameSampleStart(5.0D, 0.0D, 0.6D), 0.0D);
        assertEquals(0.0D, RopeAttachmentRenderer.attachmentFrameSampleEnd(5.0D, 0.0D, 0.6D), 0.0D);
    }
}