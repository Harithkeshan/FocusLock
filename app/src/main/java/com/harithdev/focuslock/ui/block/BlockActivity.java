package com.harithdev.focuslock.ui.block;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

import com.harithdev.focuslock.R;
import com.harithdev.focuslock.databinding.ActivityBlockBinding;
import com.harithdev.focuslock.util.TimeUtils;

/**
 * BlockActivity — full-screen block overlay.
 *
 * Shown whenever a restricted app is opened and a rule is triggered.
 * Displays one of FIVE unique messages depending on exactly why the
 * app was blocked:
 *
 *   REASON_SLEEP            → 🌙  Sleep hours — "Sleep hours, phone down!"
 *   REASON_SESSION_TIMEOUT  → ⏰  Slot ran out — "Time's up for this session!"
 *   REASON_EARLY_EXIT       → 🧘  Left mid-session — "Good call stepping away!"
 *   REASON_LIMIT            → 🔒  Daily cap hit — "That's your daily dose!"
 *   REASON_ALL_SESSIONS     → 🏁  All slots done — "All [N] sessions done!"
 *
 * File location:
 *   app/src/main/java/com/harithdev/focuslock/ui/block/BlockActivity.java
 */
public class BlockActivity extends Activity {

    // ── Intent extras ─────────────────────────────────────────
    public static final String EXTRA_PACKAGE         = "extra_package";
    public static final String EXTRA_REASON          = "extra_reason";
    public static final String EXTRA_SLEEP_END       = "extra_sleep_end";
    public static final String EXTRA_COOLDOWN_END_MS = "extra_cooldown_end_ms";
    public static final String EXTRA_SESSION_COUNT   = "extra_session_count";   // for "All N sessions done"

    // ── Block reasons ─────────────────────────────────────────
    /** Blocked because we're inside the user's sleep window */
    public static final String REASON_SLEEP           = "sleep";

    /** Blocked because the session slot ran out from continuous use (Behaviour 1) */
    public static final String REASON_SESSION_TIMEOUT = "session_timeout";

    /** Blocked because the user exited the app mid-session (Behaviour 2) */
    public static final String REASON_EARLY_EXIT      = "early_exit";

    /** Blocked because total daily screen time hit the daily limit (no sessions mode) */
    public static final String REASON_LIMIT           = "limit";

    /** Blocked because all session slots for the day have been used up */
    public static final String REASON_ALL_SESSIONS    = "all_sessions";

    private ActivityBlockBinding binding;
    private Handler  handler = new Handler(Looper.getMainLooper());
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

        String reason         = getIntent().getStringExtra(EXTRA_REASON);
        String sleepEnd       = getIntent().getStringExtra(EXTRA_SLEEP_END);
        long   cooldownEndsMs = getIntent().getLongExtra(EXTRA_COOLDOWN_END_MS, 0);
        int    sessionCount   = getIntent().getIntExtra(EXTRA_SESSION_COUNT, 0);

        setupUI(reason, sleepEnd, cooldownEndsMs, sessionCount);
        startRecheckLoop(reason, cooldownEndsMs);

        binding.btnGoHome.setOnClickListener(v -> goHome());
    }

    // ── UI setup ──────────────────────────────────────────────

    private void setupUI(String reason, String sleepEnd,
                         long cooldownEndsMs, int sessionCount) {
        switch (reason != null ? reason : "") {

            // ─── 1. SLEEP MODE ────────────────────────────────
            case REASON_SLEEP:
                binding.imgBlockIcon.setImageResource(R.drawable.ic_moon_sleep);
                binding.txtTitle.setText(getString(R.string.block_title_sleep));
                binding.txtSubtitle.setText(getString(R.string.block_subtitle_sleep,
                        TimeUtils.formatSleepEndTime(sleepEnd != null ? sleepEnd : "")));
                break;

            // ─── 2. SESSION TIMED OUT (Behaviour 1) ──────────
            // User continuously used the app until their slot ran out
            case REASON_SESSION_TIMEOUT:
                binding.imgBlockIcon.setImageResource(R.drawable.ic_timer);
                binding.txtTitle.setText(getString(R.string.block_title_session_timeout));
                binding.txtSubtitle.setText(getString(R.string.block_subtitle_session_timeout,
                        TimeUtils.formatCooldownEnd(cooldownEndsMs)));
                break;

            // ─── 3. EARLY EXIT COOLDOWN (Behaviour 2) ────────
            // User left the app mid-session; that still counts as a session
            case REASON_EARLY_EXIT:
                binding.imgBlockIcon.setImageResource(R.drawable.ic_cooldown_pause);
                binding.txtTitle.setText(getString(R.string.block_title_early_exit));
                binding.txtSubtitle.setText(getString(R.string.block_subtitle_early_exit,
                        TimeUtils.formatCooldownEnd(cooldownEndsMs)));
                break;

            // ─── 4. DAILY LIMIT REACHED (no sessions mode) ───
            // Cumulative screen time for the day has hit the cap
            case REASON_LIMIT:
                binding.imgBlockIcon.setImageResource(R.drawable.ic_hourglass);
                binding.txtTitle.setText(getString(R.string.block_title_limit));
                binding.txtSubtitle.setText(getString(R.string.block_subtitle_limit));
                break;

            // ─── 5. ALL SESSIONS EXHAUSTED (sessions mode) ───
            // Every one of the N session slots has been used up
            case REASON_ALL_SESSIONS:
                String sessionLabel = sessionCount > 0
                        ? getString(R.string.block_title_all_sessions_count, sessionCount)
                        : getString(R.string.block_title_all_sessions_no_count);
                binding.imgBlockIcon.setImageResource(R.drawable.ic_sessions_split);
                binding.txtTitle.setText(sessionLabel);
                binding.txtSubtitle.setText(getString(R.string.block_subtitle_all_sessions));
                break;

            // ─── Fallback (should never happen) ──────────────
            default:
                binding.imgBlockIcon.setImageResource(R.drawable.ic_lock_closed);
                binding.txtTitle.setText(getString(R.string.block_title_fallback));
                binding.txtSubtitle.setText(getString(R.string.block_subtitle_fallback));
                break;
        }
    }

    // ── Recheck loop ──────────────────────────────────────────

    private void startRecheckLoop(String reason, long cooldownEndsMs) {
        if (recheck != null) {
            handler.removeCallbacks(recheck);
            recheck = null;
        }

        if (REASON_SESSION_TIMEOUT.equals(reason) || REASON_EARLY_EXIT.equals(reason)) {
            long delayMs = cooldownEndsMs - System.currentTimeMillis();
            if (delayMs > 0) {
                recheck = () -> {
                    if (!isFinishing()) {
                        finish();
                    }
                };
                handler.postDelayed(recheck, delayMs);
            } else {
                finish();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Service sent a refreshed block intent — update the UI and recheck timer
        String reason         = intent.getStringExtra(EXTRA_REASON);
        String sleepEnd       = intent.getStringExtra(EXTRA_SLEEP_END);
        long   cooldownEndsMs = intent.getLongExtra(EXTRA_COOLDOWN_END_MS, 0);
        int    sessionCount   = intent.getIntExtra(EXTRA_SESSION_COUNT, 0);
        setupUI(reason, sleepEnd, cooldownEndsMs, sessionCount);
        startRecheckLoop(reason, cooldownEndsMs);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(recheck);
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }
}