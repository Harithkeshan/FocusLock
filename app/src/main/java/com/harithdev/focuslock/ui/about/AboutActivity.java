package com.harithdev.focuslock.ui.about;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.harithdev.focuslock.databinding.ActivityAboutBinding;

/**
 * AboutActivity — Displays app branding, privacy guarantees, feature breakdown,
 * and technical architecture info using FocusLock's native dark design system.
 */
public class AboutActivity extends AppCompatActivity {

    private ActivityAboutBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnBack.setOnClickListener(v -> finish());
    }
}
