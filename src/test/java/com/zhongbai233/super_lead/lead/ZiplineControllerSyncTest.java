package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZiplineControllerSyncTest {
    @Test
    void stableRidersBuildSnapshotOnlyAtHeartbeat() {
        assertFalse(ZiplineController.shouldBuildSnapshot(false, false,
                true, true, 100L, 101L));
        assertTrue(ZiplineController.shouldBuildSnapshot(false, false,
                true, true, 100L, 102L));
    }

    @Test
    void membershipAndConnectionChangesBuildImmediately() {
        assertTrue(ZiplineController.shouldBuildSnapshot(false, true,
                true, true, 100L, 101L));
        assertTrue(ZiplineController.shouldBuildSnapshot(false, false,
                false, true, 100L, 101L));
        assertTrue(ZiplineController.shouldBuildSnapshot(false, false,
                true, false, 100L, 101L));
    }

    @Test
    void stableEmptyStateDoesNotBuildHeartbeatSnapshots() {
        assertFalse(ZiplineController.shouldBuildSnapshot(false, false,
                false, false, 100L, 200L));
    }
}