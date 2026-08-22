package com.harithdev.focuslock.ui.onboarding;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.harithdev.focuslock.R;
import com.harithdev.focuslock.databinding.ActivityOnboardingBinding;
import com.harithdev.focuslock.ui.permission.PermissionActivity;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity {

    public static final String PREFS_NAME            = "focuslock_prefs";
    public static final String KEY_ONBOARDING_DONE   = "onboarding_complete";

    private ActivityOnboardingBinding binding;
    private List<OnboardingAdapter.SlideItem> slides;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupSlides();
        setupViewPager();
        setupButtons();
    }

    private void setupSlides() {
        slides = new ArrayList<>();
        slides.add(new OnboardingAdapter.SlideItem(
                "⏱️",
                "Take control of your screen time",
                "Set daily limits for any app. FocusLock enforces them so you don't have to rely on willpower."
        ));
        slides.add(new OnboardingAdapter.SlideItem(
                "🔄",
                "Smart sessions & cooldowns",
                "Split your time into sessions with breaks between them. Step away mid-session? Your timer pauses automatically."
        ));
        slides.add(new OnboardingAdapter.SlideItem(
                "🛡️",
                "Tamper-proof protection",
                "PIN lock, direction-based enforcement, and sleep mode keep your limits locked in — even from yourself."
        ));
    }

    private void setupViewPager() {
        OnboardingAdapter adapter = new OnboardingAdapter(slides);
        binding.viewPager.setAdapter(adapter);

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateIndicators(position);
            }
        });
    }

    private void updateIndicators(int position) {
        int activeBg   = R.drawable.bg_slot_active;
        int inactiveBg = R.drawable.bg_slot_inactive;

        setDotStyle(binding.dotSlide1, position == 0 ? activeBg : inactiveBg, position == 0 ? 24 : 8);
        setDotStyle(binding.dotSlide2, position == 1 ? activeBg : inactiveBg, position == 1 ? 24 : 8);
        setDotStyle(binding.dotSlide3, position == 2 ? activeBg : inactiveBg, position == 2 ? 24 : 8);

        if (position == slides.size() - 1) {
            binding.btnNext.setText("Get Started →");
            binding.btnSkip.setVisibility(View.INVISIBLE);
        } else {
            binding.btnNext.setText("Next →");
            binding.btnSkip.setVisibility(View.VISIBLE);
        }
    }

    private void setDotStyle(View dot, int backgroundRes, int widthDp) {
        dot.setBackgroundResource(backgroundRes);
        ViewGroup.LayoutParams params = dot.getLayoutParams();
        int px = (int) (widthDp * getResources().getDisplayMetrics().density);
        params.width = px;
        dot.setLayoutParams(params);
    }

    private void setupButtons() {
        binding.btnSkip.setOnClickListener(v -> finishOnboarding());
        binding.btnNext.setOnClickListener(v -> {
            int current = binding.viewPager.getCurrentItem();
            if (current < slides.size() - 1) {
                binding.viewPager.setCurrentItem(current + 1);
            } else {
                finishOnboarding();
            }
        });
    }

    private void finishOnboarding() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply();

        Intent intent = new Intent(this, PermissionActivity.class);
        startActivity(intent);
        finish();
    }
}
