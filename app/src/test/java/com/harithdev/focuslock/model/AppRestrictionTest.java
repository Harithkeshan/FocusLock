package com.harithdev.focuslock.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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
}
