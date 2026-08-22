package com.harithdev.focuslock.model;

/**
 * DailySummary — Room Query POJO
 *
 * Represents total aggregated screen time across all restricted apps for a single date.
 */
public class DailySummary {
    public String date;
    public long totalUsedMs;

    public DailySummary(String date, long totalUsedMs) {
        this.date = date;
        this.totalUsedMs = totalUsedMs;
    }
}
