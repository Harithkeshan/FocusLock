package com.harithdev.focuslock.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AppRestrictionTest {

    @Test
    public void testDefaultValues() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");

        assertEquals("com.test.app", restriction.packageName);
        assertEquals("Test App", restriction.appName);
        assertFalse(restriction.isRestricted);
        assertFalse(restriction.sleepModeEnabled);
        assertEquals(60, restriction.dailyLimitMinutes);
        assertFalse(restriction.splitSessions);
        assertEquals(4, restriction.sessionCount);
        assertEquals(40, restriction.cooldownMinutes);
        assertEquals("Other", restriction.category);

        assertEquals(60, restriction.enforcedDailyLimitMinutes);
        assertEquals(4, restriction.enforcedSessionCount);
        assertEquals(40, restriction.enforcedCooldownMinutes);
    }

    @Test
    public void testGetSlotDurationMinutes_noSplit() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.splitSessions = false;
        restriction.dailyLimitMinutes = 90;

        assertEquals(90, restriction.getSlotDurationMinutes());
    }

    @Test
    public void testGetSlotDurationMinutes_split() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.splitSessions = true;
        restriction.dailyLimitMinutes = 60;
        restriction.sessionCount = 4;

        assertEquals(15, restriction.getSlotDurationMinutes());
    }

    @Test
    public void testGetSlotDurationMinutes_zeroSessionCount() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.splitSessions = true;
        restriction.dailyLimitMinutes = 60;
        restriction.sessionCount = 0; // Guard against divide by zero

        assertEquals(60, restriction.getSlotDurationMinutes());
    }

    @Test
    public void testGetEnforcedSlotDurationMinutes() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.splitSessions = true;
        restriction.enforcedDailyLimitMinutes = 120;
        restriction.enforcedSessionCount = 6;

        assertEquals(20, restriction.getEnforcedSlotDurationMinutes());
    }

    @Test
    public void testHasPendingChanges_noSyncDate() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.lastEnforcedSyncDate = null;
        restriction.dailyLimitMinutes = 120;
        restriction.enforcedDailyLimitMinutes = 60;
        // Without initial sync, no pending changes should be flagged
        assertFalse(restriction.hasPendingChanges());
    }

    @Test
    public void testHasPendingChanges_identicalValues() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.lastEnforcedSyncDate = "2026-09-16";
        restriction.dailyLimitMinutes = 60;
        restriction.enforcedDailyLimitMinutes = 60;
        restriction.sessionCount = 4;
        restriction.enforcedSessionCount = 4;
        restriction.cooldownMinutes = 40;
        restriction.enforcedCooldownMinutes = 40;

        assertFalse(restriction.hasPendingChanges());
    }

    @Test
    public void testHasPendingChanges_dailyLimitRelaxed() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.lastEnforcedSyncDate = "2026-09-16";
        restriction.dailyLimitMinutes = 90;
        restriction.enforcedDailyLimitMinutes = 60;

        assertTrue(restriction.hasPendingChanges());
    }

    @Test
    public void testHasPendingChanges_sessionCountRelaxed() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.lastEnforcedSyncDate = "2026-09-16";
        restriction.sessionCount = 2;
        restriction.enforcedSessionCount = 4;

        assertTrue(restriction.hasPendingChanges());
    }

    @Test
    public void testHasPendingChanges_cooldownRelaxed() {
        AppRestriction restriction = new AppRestriction("com.test.app", "Test App");
        restriction.lastEnforcedSyncDate = "2026-09-16";
        restriction.cooldownMinutes = 30;
        restriction.enforcedCooldownMinutes = 50;

        assertTrue(restriction.hasPendingChanges());
    }
}
