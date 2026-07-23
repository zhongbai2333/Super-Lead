package com.zhongbai233.super_lead.lead.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttachmentSwingClientTest {
    @Test
    void constantSupportVelocityDoesNotInjectPendulumImpulse() {
        assertEquals(0.0D, AttachmentSwingClient.supportAccelerationImpulse(0.0D, 1.0D), 0.0D);
    }

    @Test
    void acceleratingSupportMakesAttachmentLagBehind() {
        double impulse = AttachmentSwingClient.supportAccelerationImpulse(0.20D, 1.0D);

        assertTrue(impulse < 0.0D);
        assertEquals(-0.048D, impulse, 1.0e-9D);
    }

    @Test
    void reversalDampingReducesSupportImpulse() {
        double full = AttachmentSwingClient.supportAccelerationImpulse(0.20D, 1.0D);
        double damped = AttachmentSwingClient.supportAccelerationImpulse(0.20D, 0.45D);

        assertEquals(full * 0.45D, damped, 1.0e-9D);
    }
}