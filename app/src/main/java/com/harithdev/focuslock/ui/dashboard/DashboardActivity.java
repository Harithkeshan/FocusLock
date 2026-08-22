package com.harithdev.focuslock.ui.dashboard;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.harithdev.focuslock.R;
import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.databinding.ActivityDashboardBinding;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.model.DailySummary;
import com.harithdev.focuslock.util.TimeUtils;
import com.harithdev.focuslock.util.UsageCalculator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DashboardActivity extends AppCompatActivity {

    private ActivityDashboardBinding binding;
    private int selectedDays = 7; // 7, 14, or 30

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnBack.setOnClickListener(v -> finish());

        setupChips();
        setupRecyclerView();
        loadDashboardData();
    }

    private void setupChips() {
        binding.chip7d.setOnClickListener(v -> selectRange(7));
        binding.chip14d.setOnClickListener(v -> selectRange(14));
        binding.chip30d.setOnClickListener(v -> selectRange(30));
    }

    private void selectRange(int days) {
        selectedDays = days;

        int activeBg   = R.drawable.bg_chip_active;
        int inactiveBg = R.drawable.bg_chip_inactive;
        int whiteColor = androidx.core.content.ContextCompat.getColor(this, R.color.white);
        int grayColor  = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary);

        binding.chip7d.setBackgroundResource(days == 7 ? activeBg : inactiveBg);
        binding.chip7d.setTextColor(days == 7 ? whiteColor : grayColor);

        binding.chip14d.setBackgroundResource(days == 14 ? activeBg : inactiveBg);
        binding.chip14d.setTextColor(days == 14 ? whiteColor : grayColor);

        binding.chip30d.setBackgroundResource(days == 30 ? activeBg : inactiveBg);
        binding.chip30d.setTextColor(days == 30 ? whiteColor : grayColor);

        loadChartData();
    }

    private void setupRecyclerView() {
        binding.rvAppUsage.setLayoutManager(new LinearLayoutManager(this));
    }

    private void loadDashboardData() {
        new Thread(() -> {
            FocusLockDatabase db = FocusLockDatabase.getInstance(this);
            List<AppRestriction> restrictions = db.appRestrictionDao().getActiveRestrictions();

            long totalTodayMs = 0;
            List<AppUsageAdapter.AppUsageItem> usageItems = new ArrayList<>();

            for (AppRestriction restriction : restrictions) {
                long usedMs = UsageCalculator.getScreenTimeToday(this, restriction.packageName);
                totalTodayMs += usedMs;
                usageItems.add(new AppUsageAdapter.AppUsageItem(restriction, usedMs));
            }

            // Sort most used apps first
            Collections.sort(usageItems, (a, b) -> Long.compare(b.usedMs, a.usedMs));

            final long finalTotalMs = totalTodayMs;
            runOnUiThread(() -> {
                long totalMins = finalTotalMs / 60_000L;
                binding.txtTodayTotal.setText(formatTimeLarge(totalMins));
                binding.txtTodaySub.setText(restrictions.size() + " restricted apps tracked today");

                if (usageItems.isEmpty()) {
                    binding.rvAppUsage.setVisibility(View.GONE);
                    binding.txtEmptyApps.setVisibility(View.VISIBLE);
                } else {
                    binding.rvAppUsage.setVisibility(View.VISIBLE);
                    binding.txtEmptyApps.setVisibility(View.GONE);
                    binding.rvAppUsage.setAdapter(new AppUsageAdapter(usageItems));
                }
            });

            loadChartDataInternal(db);
        }).start();
    }

    private void loadChartData() {
        new Thread(() -> {
            FocusLockDatabase db = FocusLockDatabase.getInstance(this);
            loadChartDataInternal(db);
        }).start();
    }

    private void loadChartDataInternal(FocusLockDatabase db) {
        String startDate = getStartDate(selectedDays);
        List<DailySummary> historySummaries = db.usageHistoryDao().getDailySummaries(startDate);

        // Fill in full date sequence (including days with zero usage) up to today
        List<DailySummary> fullSequence = buildFullDateSequence(selectedDays, historySummaries);

        // Calculate today's live total screen time for restricted apps
        List<AppRestriction> restrictions = db.appRestrictionDao().getActiveRestrictions();
        long todayLiveMs = 0;
        for (AppRestriction app : restrictions) {
            todayLiveMs += UsageCalculator.getScreenTimeToday(this, app.packageName);
        }

        // Replace/update today's value in the sequence with live calculation
        String todayString = TimeUtils.todayString();
        for (DailySummary item : fullSequence) {
            if (item.date.equals(todayString)) {
                item.totalUsedMs = todayLiveMs;
            }
        }

        long totalSumMs = 0;
        for (DailySummary item : fullSequence) {
            totalSumMs += item.totalUsedMs;
        }
        long averageMins = fullSequence.size() > 0 ? (totalSumMs / fullSequence.size()) / 60_000L : 0;

        boolean isHistoryEmpty = historySummaries == null || historySummaries.isEmpty();

        runOnUiThread(() -> {
            binding.chartView.setData(fullSequence);
            binding.txtDailyAverage.setText("Daily average: " + formatTimeShort(averageMins));
            binding.txtEmptyHistoryPlaceholder.setVisibility(isHistoryEmpty ? View.VISIBLE : View.GONE);
        });
    }

    private List<DailySummary> buildFullDateSequence(int days, List<DailySummary> history) {
        List<DailySummary> result = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Calendar cal = Calendar.getInstance();

        // Build list from (days - 1) days ago to today
        cal.add(Calendar.DATE, -(days - 1));

        for (int i = 0; i < days; i++) {
            String dateStr = sdf.format(cal.getTime());
            long usedMs = 0;

            if (history != null) {
                for (DailySummary h : history) {
                    if (h.date.equals(dateStr)) {
                        usedMs = h.totalUsedMs;
                        break;
                    }
                }
            }

            result.add(new DailySummary(dateStr, usedMs));
            cal.add(Calendar.DATE, 1);
        }

        return result;
    }

    private String getStartDate(int daysAgo) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -daysAgo);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.getTime());
    }

    private String formatTimeLarge(long minutes) {
        if (minutes < 60) return minutes + "m";
        long h = minutes / 60;
        long m = minutes % 60;
        return h + "h " + m + "m";
    }

    private String formatTimeShort(long minutes) {
        if (minutes < 60) return minutes + "m";
        long h = minutes / 60;
        long m = minutes % 60;
        return m > 0 ? h + "h " + m + "m" : h + "h";
    }
}
