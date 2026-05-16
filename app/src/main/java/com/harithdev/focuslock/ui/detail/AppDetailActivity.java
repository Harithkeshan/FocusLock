package com.harithdev.focuslock.ui.detail;

import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.harithdev.focuslock.database.FocusLockDatabase;
import com.harithdev.focuslock.databinding.ActivityAppDetailBinding;
import com.harithdev.focuslock.model.AppRestriction;
import com.harithdev.focuslock.util.TimeUtils;

import java.util.Locale;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import java.util.Calendar;
import java.util.List;
import com.harithdev.focuslock.model.DailyUsage;

/**
 * AppDetailActivity — Step 3
 *
 * The full settings screen for a single app.
 * Sections:
 *   1. Restrict on/off
 *   2. Sleep mode (optional) — pick start/end time
 *   3. Daily time limit — pick hours + minutes
 *   4. Usage sessions — split into slots + cooldown
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/ui/detail/AppDetailActivity.java
 */
public class AppDetailActivity extends AppCompatActivity {

    private ActivityAppDetailBinding binding;
    private FocusLockDatabase db;

    // The app we are configuring
    private String packageName;
    private String appName;

    // Current state (loaded from DB or defaults)
    private AppRestriction restriction;

    // Currently selected number of sessions (2–6)
    private int selectedSlots = 4;

    // Cooldown in minutes (40–300)
    private int cooldownMinutes = 40;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAppDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Get the app info passed from AppListActivity
        packageName = getIntent().getStringExtra("packageName");
        appName     = getIntent().getStringExtra("appName");

        db = FocusLockDatabase.getInstance(this);

        setupHeader();
        setupClickListeners();
        loadExistingSettings();
        loadTodayUsage();
    }

    // ── Header ────────────────────────────────────────────────

    private void setupHeader() {
        binding.txtAppName.setText(appName);
        binding.txtPackageName.setText(packageName);
        binding.btnBack.setOnClickListener(v -> finish());

        // Load app icon on background thread
        AsyncTask.execute(() -> {
            try {
                android.graphics.drawable.Drawable icon =
                        getPackageManager().getApplicationIcon(packageName);
                runOnUiThread(() -> binding.imgAppIcon.setImageDrawable(icon));
            } catch (Exception ignored) {}
        });
    }

    // ── Load existing settings from DB ────────────────────────

    private void loadExistingSettings() {
        AsyncTask.execute(() -> {
            AppRestriction existing = db.appRestrictionDao().getByPackageName(packageName);

            runOnUiThread(() -> {
                if (existing != null) {
                    restriction = existing;
                } else {
                    // First time opening this app — create defaults
                    restriction = new AppRestriction(packageName, appName);
                }
                populateUI();
            });
        });
    }

    // ── Populate UI from restriction object ───────────────────

    private void populateUI() {

        // Section 1: Restrict toggle
        binding.switchRestrict.setChecked(restriction.isRestricted);
        updateSectionsLocked(!restriction.isRestricted);

        // Section 2: Sleep mode
        binding.switchSleep.setChecked(restriction.sleepModeEnabled);
        updateSleepTimeVisibility(restriction.sleepModeEnabled);
        setSleepTimeFields(restriction.sleepStartTime, restriction.sleepEndTime);
        updateSleepSummary();

        // Section 3: Daily limit
        int hours   = restriction.dailyLimitMinutes / 60;
        int minutes = restriction.dailyLimitMinutes % 60;
        binding.inputHours.setText(String.valueOf(hours));
        binding.inputMinutes.setText(String.format(Locale.getDefault(), "%02d", minutes));

        // Section 4: Sessions
        binding.switchSplit.setChecked(restriction.splitSessions);
        updateSplitVisibility(restriction.splitSessions);
        selectedSlots    = restriction.sessionCount;
        cooldownMinutes  = restriction.cooldownMinutes;
        highlightSlot(selectedSlots);
        binding.seekbarCooldown.setProgress(cooldownMinutes - 40); // offset: min=40
        updateCooldownLabel(cooldownMinutes);
        updateSlotPreview();
    }

    // ── Click listeners ───────────────────────────────────────

    private void setupClickListeners() {

        // ── Section 1: Restrict master toggle ─────────────────
        binding.switchRestrict.setOnCheckedChangeListener((btn, isChecked) -> {
            updateSectionsLocked(!isChecked);
        });

        // ── Section 2: Sleep toggle ────────────────────────────
        binding.switchSleep.setOnCheckedChangeListener((btn, isChecked) -> {
            updateSleepTimeVisibility(isChecked);
            updateSleepSummary();
        });

        // AM/PM buttons — From time
        binding.btnFromAm.setOnClickListener(v -> setFromAmPm("AM"));
        binding.btnFromPm.setOnClickListener(v -> setFromAmPm("PM"));

        // AM/PM buttons — To time
        binding.btnToAm.setOnClickListener(v -> setToAmPm("AM"));
        binding.btnToPm.setOnClickListener(v -> setToAmPm("PM"));

        // Update sleep summary when time fields change
        binding.inputFromHour.setOnFocusChangeListener((v, f) -> updateSleepSummary());
        binding.inputFromMin.setOnFocusChangeListener((v, f)  -> updateSleepSummary());
        binding.inputToHour.setOnFocusChangeListener((v, f)   -> updateSleepSummary());
        binding.inputToMin.setOnFocusChangeListener((v, f)    -> updateSleepSummary());

        // ── Section 3: Daily limit fields ─────────────────────
        binding.inputHours.setOnFocusChangeListener((v, f)   -> updateSlotPreview());
        binding.inputMinutes.setOnFocusChangeListener((v, f) -> updateSlotPreview());

        // ── Section 4: Split toggle ────────────────────────────
        binding.switchSplit.setOnCheckedChangeListener((btn, isChecked) -> {
            updateSplitVisibility(isChecked);
            updateSlotPreview();
        });

        // Slot selection buttons (2–6)
        binding.btnSlot2.setOnClickListener(v -> selectSlot(2));
        binding.btnSlot3.setOnClickListener(v -> selectSlot(3));
        binding.btnSlot4.setOnClickListener(v -> selectSlot(4));
        binding.btnSlot5.setOnClickListener(v -> selectSlot(5));
        binding.btnSlot6.setOnClickListener(v -> selectSlot(6));

        // Cooldown seekbar
        binding.seekbarCooldown.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Progress 0–260 maps to 40–300 minutes
                cooldownMinutes = progress + 40;
                updateCooldownLabel(cooldownMinutes);
                updateSlotPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s)  {}
        });

        // ── Save button ────────────────────────────────────────
        binding.btnSave.setOnClickListener(v -> saveSettings());
    }

    // ── Section lock/unlock ───────────────────────────────────

    /**
     * When restrict toggle is OFF → lock sections 2,3,4 (dim + no touch).
     * When restrict toggle is ON  → unlock all sections.
     */
    private void updateSectionsLocked(boolean locked) {
        float alpha = locked ? 0.4f : 1.0f;
        binding.sectionSleep.setAlpha(alpha);
        binding.sectionLimit.setAlpha(alpha);
        binding.sectionSlots.setAlpha(alpha);
        binding.sectionSleep.setEnabled(!locked);
        binding.sectionLimit.setEnabled(!locked);
        binding.sectionSlots.setEnabled(!locked);
    }

    // ── Sleep mode helpers ────────────────────────────────────

    private void updateSleepTimeVisibility(boolean visible) {
        binding.layoutSleepTimes.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateSleepSummary() {
        if (!binding.switchSleep.isChecked()) {
            binding.txtSleepSummary.setText("Off — no sleep block");
            return;
        }
        String fromH  = getFieldText(binding.inputFromHour, "1");
        String fromM  = getFieldText(binding.inputFromMin,  "00");
        String toH    = getFieldText(binding.inputToHour,   "12");
        String toM    = getFieldText(binding.inputToMin,    "00");
        String fromAP = binding.btnFromAm.isSelected() ? "AM" : "PM";
        String toAP   = binding.btnToAm.isSelected()   ? "AM" : "PM";

        binding.txtSleepSummary.setText(
                String.format("Blocked %s:%s %s → %s:%s %s",
                        fromH, fromM, fromAP, toH, toM, toAP)
        );
    }

    private void setFromAmPm(String val) {
        binding.btnFromAm.setSelected(val.equals("AM"));
        binding.btnFromPm.setSelected(val.equals("PM"));
        updateAmPmStyle();
        updateSleepSummary();
    }

    private void setToAmPm(String val) {
        binding.btnToAm.setSelected(val.equals("AM"));
        binding.btnToPm.setSelected(val.equals("PM"));
        updateAmPmStyle();
        updateSleepSummary();
    }

    private void updateAmPmStyle() {
        // Active button = purple tint, inactive = dim
        int activeColor   = 0xFFC084FC;
        int inactiveColor = 0xFF5A5475;

        binding.btnFromAm.setTextColor(binding.btnFromAm.isSelected() ? activeColor : inactiveColor);
        binding.btnFromPm.setTextColor(binding.btnFromPm.isSelected() ? activeColor : inactiveColor);
        binding.btnToAm.setTextColor(binding.btnToAm.isSelected()     ? activeColor : inactiveColor);
        binding.btnToPm.setTextColor(binding.btnToPm.isSelected()     ? activeColor : inactiveColor);
    }

    /**
     * Converts stored "HH:mm" 24-hour string into the UI fields.
     * e.g. "01:00" → hour=1, min=00, AM
     *      "13:30" → hour=1, min=30, PM
     */
    private void setSleepTimeFields(String start, String end) {
        int startMins = TimeUtils.parseTimeToMinutes(start);
        int endMins   = TimeUtils.parseTimeToMinutes(end);

        int startH24 = startMins / 60, startM = startMins % 60;
        int endH24   = endMins   / 60, endM   = endMins   % 60;

        // Convert to 12-hour
        boolean startPm = startH24 >= 12;
        boolean endPm   = endH24   >= 12;
        int startH12 = startH24 > 12 ? startH24 - 12 : (startH24 == 0 ? 12 : startH24);
        int endH12   = endH24   > 12 ? endH24   - 12 : (endH24   == 0 ? 12 : endH24);

        binding.inputFromHour.setText(String.valueOf(startH12));
        binding.inputFromMin.setText(String.format(Locale.getDefault(), "%02d", startM));
        binding.inputToHour.setText(String.valueOf(endH12));
        binding.inputToMin.setText(String.format(Locale.getDefault(), "%02d", endM));

        setFromAmPm(startPm ? "PM" : "AM");
        setToAmPm(endPm ? "PM" : "AM");
    }

    // ── Slot selection ────────────────────────────────────────

    private void selectSlot(int count) {
        selectedSlots = count;
        highlightSlot(count);
        updateSlotPreview();
    }

    private void highlightSlot(int selected) {
        int activeColor   = 0xFFC084FC;
        int inactiveColor = 0xFF9990BB;

        binding.btnSlot2.setTextColor(selected == 2 ? activeColor : inactiveColor);
        binding.btnSlot3.setTextColor(selected == 3 ? activeColor : inactiveColor);
        binding.btnSlot4.setTextColor(selected == 4 ? activeColor : inactiveColor);
        binding.btnSlot5.setTextColor(selected == 5 ? activeColor : inactiveColor);
        binding.btnSlot6.setTextColor(selected == 6 ? activeColor : inactiveColor);

        binding.btnSlot2.setSelected(selected == 2);
        binding.btnSlot3.setSelected(selected == 3);
        binding.btnSlot4.setSelected(selected == 4);
        binding.btnSlot5.setSelected(selected == 5);
        binding.btnSlot6.setSelected(selected == 6);
    }

    // ── Split session visibility ──────────────────────────────

    private void updateSplitVisibility(boolean splitEnabled) {
        binding.layoutSplitConfig.setVisibility(splitEnabled ? View.VISIBLE : View.GONE);
        binding.txtNoSplitNote.setVisibility(splitEnabled ? View.GONE : View.VISIBLE);
    }

    // ── Slot preview ──────────────────────────────────────────

    /**
     * Updates the preview text showing e.g.
     * "4 sessions × 15 min each · Cooldown: 40 min"
     */
    private void updateSlotPreview() {
        if (!binding.switchSplit.isChecked()) return;

        int totalMins = getTotalLimitMinutes();
        if (totalMins <= 0) return;

        int slotMins = totalMins / selectedSlots;

        String slotStr = slotMins < 60
                ? slotMins + " min"
                : (slotMins / 60) + "h " + (slotMins % 60 > 0 ? slotMins % 60 + "m" : "");

        String coolStr = cooldownMinutes < 60
                ? cooldownMinutes + " min"
                : (cooldownMinutes / 60) + "h " + (cooldownMinutes % 60 > 0 ? cooldownMinutes % 60 + "m" : "");

        binding.txtSlotPreview.setText(
                String.format(Locale.getDefault(),
                        "%d sessions × %s each\nCooldown: %s · Closing early still uses full slot",
                        selectedSlots, slotStr, coolStr)
        );
    }

    // ── Cooldown label ────────────────────────────────────────

    private void updateCooldownLabel(int minutes) {
        if (minutes < 60) {
            binding.txtCooldownValue.setText(minutes + " min");
        } else {
            int h = minutes / 60;
            int m = minutes % 60;
            binding.txtCooldownValue.setText(m > 0
                    ? h + "h " + m + "m"
                    : h + " hr");
        }
    }

    // ── Save ──────────────────────────────────────────────────

    private void saveSettings() {
        if (restriction == null) return;

        // Section 1
        restriction.isRestricted = binding.switchRestrict.isChecked();

        // Section 2 — sleep mode
        restriction.sleepModeEnabled = binding.switchSleep.isChecked();
        restriction.sleepStartTime   = buildSleepTime(
                binding.inputFromHour, binding.inputFromMin, binding.btnFromAm.isSelected());
        restriction.sleepEndTime     = buildSleepTime(
                binding.inputToHour, binding.inputToMin, binding.btnToAm.isSelected());

        // Section 3 — daily limit
        restriction.dailyLimitMinutes = getTotalLimitMinutes();
        if (restriction.dailyLimitMinutes < 1) restriction.dailyLimitMinutes = 1;

        // Section 4 — sessions
        restriction.splitSessions  = binding.switchSplit.isChecked();
        restriction.sessionCount   = selectedSlots;
        restriction.cooldownMinutes = cooldownMinutes;

        // Save to DB on background thread
        AsyncTask.execute(() -> {
            // Store today's already-used time so enforcement is accurate
            if (restriction.isRestricted) {
                String today = TimeUtils.todayString();
                DailyUsage usage = db.dailyUsageDao().getUsage(packageName, today);
                if (usage == null) {
                    usage = new DailyUsage(packageName, today);
                    db.dailyUsageDao().insert(usage);
                }
                // Convert system usage to sessions already consumed
                long usedMs      = getSystemUsageToday(packageName);
                long slotMs      = restriction.getSlotDurationMinutes() * 60_000L;
                int slotsUsed    = (int)(usedMs / slotMs);
                // Cap at max sessions
                int maxSessions  = restriction.splitSessions ? restriction.sessionCount : 1;
                usage.sessionsUsedToday = Math.min(slotsUsed, maxSessions);
                db.dailyUsageDao().update(usage);
            }
            db.appRestrictionDao().insert(restriction); // REPLACE if exists
            runOnUiThread(() -> {
                Toast.makeText(this, "Settings saved ✓", Toast.LENGTH_SHORT).show();
                finish(); // go back to app list
            });
        });
    }

    // ── Helpers ───────────────────────────────────────────────

    /** Reads hours + minutes fields and returns total minutes */
    private int getTotalLimitMinutes() {
        int h = parseFieldInt(binding.inputHours,   0);
        int m = parseFieldInt(binding.inputMinutes, 0);
        return h * 60 + m;
    }

    /**
     * Converts 12-hour UI fields into "HH:mm" 24-hour string for storage.
     * e.g. hour=1, min=00, isAm=true  → "01:00"
     *      hour=1, min=30, isAm=false → "13:30"
     */
    private String buildSleepTime(android.widget.EditText hourField,
                                  android.widget.EditText minField,
                                  boolean isAm) {
        int h12 = parseFieldInt(hourField, 12);
        int m   = parseFieldInt(minField,  0);
        int h24;
        if (isAm) {
            h24 = (h12 == 12) ? 0 : h12;
        } else {
            h24 = (h12 == 12) ? 12 : h12 + 12;
        }
        return String.format(Locale.getDefault(), "%02d:%02d", h24, m);
    }

    private int parseFieldInt(android.widget.EditText field, int defaultVal) {
        try {
            String text = field.getText().toString().trim();
            return text.isEmpty() ? defaultVal : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private String getFieldText(android.widget.EditText field, String defaultVal) {
        String text = field.getText().toString().trim();
        return text.isEmpty() ? defaultVal : text;
    }

    private void loadTodayUsage() {
        AsyncTask.execute(() -> {
            long usedMs = getSystemUsageToday(packageName);
            runOnUiThread(() -> {
                if (usedMs <= 0) {
                    binding.txtUsageToday.setText("📊 Used today: none");
                    return;
                }
                long totalMins = usedMs / 60_000;
                long hours     = totalMins / 60;
                long mins      = totalMins % 60;

                String display;
                if (hours > 0) {
                    display = hours + "h " + mins + "m";
                } else {
                    display = mins + " min";
                }
                binding.txtUsageToday.setText("📊 Used today: " + display);
            });
        });
    }

    private long getSystemUsageToday(String packageName) {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return 0;

            Calendar midnight = Calendar.getInstance();
            midnight.set(Calendar.HOUR_OF_DAY, 0);
            midnight.set(Calendar.MINUTE, 0);
            midnight.set(Calendar.SECOND, 0);
            midnight.set(Calendar.MILLISECOND, 0);

            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    midnight.getTimeInMillis(),
                    System.currentTimeMillis());

            if (stats == null) return 0;

            for (UsageStats s : stats) {
                if (s.getPackageName().equals(packageName)) {
                    return s.getTotalTimeInForeground();
                }
            }
        } catch (Exception e) { return 0; }
        return 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTodayUsage();
    }
}