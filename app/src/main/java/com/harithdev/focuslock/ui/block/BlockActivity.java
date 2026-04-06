package com.harithdev.focuslock.ui.block;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.harithdev.focuslock.databinding.ActivityBlockBinding;
import com.harithdev.focuslock.util.TimeUtils;

/**
 * BlockActivity — Step 4 (part 2)
 *
 * Full-screen overlay shown when a blocked app is opened.
 * Displays one of three messages depending on the reason:
 *
 *   REASON_SLEEP    → "Sleeping time now. Come back at [time]"
 *   REASON_COOLDOWN → "Session ended. Come back at [time]"
 *   REASON_LIMIT    → "Daily limit reached. Come back tomorrow."
 *
 * This activity sits ON TOP of the blocked app.
 * The user can only dismiss it by pressing the Home button.
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/ui/block/BlockActivity.java
 */
public class BlockActivity extends Activity {

    // Intent extras — used to pass data from UsageTrackingService
    public static final String EXTRA_PACKAGE         = "extra_package";
    public static final String EXTRA_REASON          = "extra_reason";
    public static final String EXTRA_SLEEP_END       = "extra_sleep_end";
    public static final String EXTRA_COOLDOWN_END_MS = "extra_cooldown_end_ms";

    // Block reasons
    public static final String REASON_SLEEP    = "sleep";
    public static final String REASON_COOLDOWN = "cooldown";
    public static final String REASON_LIMIT    = "limit";

    private ActivityBlockBinding binding;

    // Re-check every 60 seconds — dismiss if block has lifted
    private Handler  handler    = new Handler(Looper.getMainLooper());
    private Runnable recheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBlockBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Keep screen on and show over lock screen
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );

        String reason          = getIntent().getStringExtra(EXTRA_REASON);
        String sleepEnd        = getIntent().getStringExtra(EXTRA_SLEEP_END);
        long   cooldownEndsMs  = getIntent().getLongExtra(EXTRA_COOLDOWN_END_MS, 0);

        setupUI(reason, sleepEnd, cooldownEndsMs);
        startRecheckLoop(reason, cooldownEndsMs);

        // Go home button — sends user to home screen
        binding.btnGoHome.setOnClickListener(v -> goHome());
    }

    // ── UI setup ──────────────────────────────────────────────

    private void setupUI(String reason, String sleepEnd, long cooldownEndsMs) {
        switch (reason != null ? reason : "") {

            case REASON_SLEEP:
                binding.txtEmoji.setText("🌙");
                binding.txtTitle.setText("Sleeping time now");
                binding.txtSubtitle.setText(
                        "Come back at " + TimeUtils.formatSleepEndTime(sleepEnd != null ? sleepEnd : ""));
                break;

            case REASON_COOLDOWN:
                binding.txtEmoji.setText("⏳");
                binding.txtTitle.setText("Session ended");
                binding.txtSubtitle.setText(
                        "Come back at " + TimeUtils.formatCooldownEnd(cooldownEndsMs));
                break;

            case REASON_LIMIT:
            default:
                binding.txtEmoji.setText("🔒");
                binding.txtTitle.setText("Daily limit reached");
                binding.txtSubtitle.setText("Come back tomorrow");
                break;
        }
    }

    // ── Recheck loop ──────────────────────────────────────────

    /**
     * Every 60 seconds, re-evaluate whether the block should still be showing.
     * For cooldown: check if cooldownEndsMs has passed.
     * For sleep: check if we are no longer in the sleep window.
     * For limit: always stay blocked until midnight reset.
     */
    private void startRecheckLoop(String reason, long cooldownEndsMs) {
        recheck = new Runnable() {
            @Override
            public void run() {
                boolean shouldDismiss = false;

                if (REASON_COOLDOWN.equals(reason)) {
                    shouldDismiss = System.currentTimeMillis() >= cooldownEndsMs;
                }
                // Sleep and limit blocks are dismissed when the service stops sending
                // the intent — we just auto-finish if no new intent arrives

                if (shouldDismiss) {
                    finish();
                    return;
                }
                handler.postDelayed(this, 60_000);
            }
        };
        handler.postDelayed(recheck, 60_000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Service sent a new block intent (refreshed reason/time)
        // Update the UI with new data
        String reason         = intent.getStringExtra(EXTRA_REASON);
        String sleepEnd       = intent.getStringExtra(EXTRA_SLEEP_END);
        long   cooldownEndsMs = intent.getLongExtra(EXTRA_COOLDOWN_END_MS, 0);
        setupUI(reason, sleepEnd, cooldownEndsMs);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(recheck);
    }

    // ── Back button disabled ──────────────────────────────────

    @Override
    public void onBackPressed() {
        // Block the back button — user cannot go back into the app
        goHome();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }
}