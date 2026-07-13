package com.zhongbai233.super_lead.lead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zhongbai233.super_lead.Config;
import org.junit.jupiter.api.Test;

class LeadSignalServiceTest {
    @Test
    void energyRequestNeverExceedsConfiguredPerCallLimit() {
        assertEquals(Config.energyMaxRequestPerCall(), LeadSignalService.boundedEnergyRequest(Long.MAX_VALUE));
        assertEquals(1234, LeadSignalService.boundedEnergyRequest(1234));
        assertEquals(0, LeadSignalService.boundedEnergyRequest(0));
    }

    @Test
    void handlerCallBudgetRejectsReservationsBeyondLimit() {
        LeadSignalService.EnergyTickBudget budget = new LeadSignalService.EnergyTickBudget(3, Integer.MAX_VALUE);

        assertTrue(budget.reserveCalls(2));
        assertTrue(budget.reserveTransferCalls());
        assertEquals(3, budget.calls());
        assertFalse(budget.canStartCall());
        assertFalse(budget.reserveCalls(1));
        assertEquals(3, budget.calls());
    }

    @Test
    void transferRequiresOneRemainingHandlerCall() {
        LeadSignalService.EnergyTickBudget budget = new LeadSignalService.EnergyTickBudget(2, Integer.MAX_VALUE);

        assertTrue(budget.reserveCalls(1));
        assertTrue(budget.canStartTransfer());
        assertTrue(budget.reserveTransferCalls());
        assertEquals(2, budget.calls());
        assertFalse(budget.canStartTransfer());
    }
}