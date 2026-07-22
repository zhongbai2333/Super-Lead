package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParrotRopePerchControllerTest {
    @Test
    void scansOnlyAtTwentyTickBoundaries() {
        assertTrue(ParrotRopePerchController.shouldScanForNewPerches(0L));
        assertFalse(ParrotRopePerchController.shouldScanForNewPerches(1L));
        assertFalse(ParrotRopePerchController.shouldScanForNewPerches(19L));
        assertTrue(ParrotRopePerchController.shouldScanForNewPerches(20L));
        assertTrue(ParrotRopePerchController.shouldScanForNewPerches(40L));
    }

    @Test
    void scanCadenceRemainsStableAcrossNegativeTimeValues() {
        assertTrue(ParrotRopePerchController.shouldScanForNewPerches(-20L));
        assertFalse(ParrotRopePerchController.shouldScanForNewPerches(-1L));
    }
}