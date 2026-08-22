package com.harithdev.focuslock.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TimeUtilsTest {

    @Test
    public void testTodayStringFormat() {
        String today = TimeUtils.todayString();
        assertNotNull(today);
        assertTrue(today.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void testYesterdayStringFormat() {
        String yesterday = TimeUtils.yesterdayString();
        assertNotNull(yesterday);
        assertTrue(yesterday.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void testParseTimeToMinutes_standard() {
        assertEquals(810, TimeUtils.parseTimeToMinutes("13:30"));
        assertEquals(60, TimeUtils.parseTimeToMinutes("01:00"));
        assertEquals(0, TimeUtils.parseTimeToMinutes("00:00"));
        assertEquals(1439, TimeUtils.parseTimeToMinutes("23:59"));
    }

    @Test
    public void testFormatSleepEndTime() {
        assertEquals("12:00 PM", TimeUtils.formatSleepEndTime("12:00"));
        assertEquals("1:00 AM", TimeUtils.formatSleepEndTime("01:00"));
        assertEquals("11:30 PM", TimeUtils.formatSleepEndTime("23:30"));
        assertEquals("12:15 AM", TimeUtils.formatSleepEndTime("00:15"));
    }

    @Test
    public void testFormatCooldownEnd() {
        // Test with arbitrary timestamp — ensures no crash and returns valid formatted string
        String result = TimeUtils.formatCooldownEnd(System.currentTimeMillis());
        assertNotNull(result);
        assertTrue(result.contains("AM") || result.contains("PM"));
    }
}
