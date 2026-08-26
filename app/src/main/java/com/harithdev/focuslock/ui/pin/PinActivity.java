package com.harithdev.focuslock.ui.pin;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.harithdev.focuslock.R;
import com.harithdev.focuslock.databinding.ActivityPinBinding;
import com.harithdev.focuslock.security.PinManager;

/**
 * PinActivity — Setup and verification screen for PIN protection.
 *
 * Modes:
 *   MODE_SETUP  → 2-step setup: Enter 4 digits → Confirm 4 digits
 *   MODE_VERIFY → Enter 4 digits → Check against stored SHA-256 hash
 */
public class PinActivity extends AppCompatActivity {

    public static final String EXTRA_MODE  = "pin_mode";
    public static final String MODE_SETUP  = "setup";
    public static final String MODE_VERIFY = "verify";
    public static final String MODE_CHANGE = "change";

    private ActivityPinBinding binding;
    private String mode = MODE_VERIFY;

    private String enteredPin = "";
    private String firstStepPin = null; // Used during setup mode
    private int changeStage = 0;        // 0: Current PIN, 1: New PIN, 2: Confirm New PIN

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPinBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (getIntent() != null && getIntent().hasExtra(EXTRA_MODE)) {
            mode = getIntent().getStringExtra(EXTRA_MODE);
        }

        setupUIForMode();
        setupKeypad();
    }

    private void setupUIForMode() {
        if (MODE_SETUP.equals(mode)) {
            binding.txtPinTitle.setText("Set a PIN");
            binding.txtPinSubtitle.setText("Create a 4-digit PIN to protect your settings");
            binding.btnForgotPin.setVisibility(View.GONE);
        } else if (MODE_CHANGE.equals(mode)) {
            changeStage = 0;
            binding.txtPinTitle.setText("Enter Current PIN");
            binding.txtPinSubtitle.setText("Enter your current PIN to change it");
            binding.btnForgotPin.setVisibility(View.VISIBLE);
            binding.btnForgotPin.setOnClickListener(v -> showForgotPinDialog());
        } else {
            binding.txtPinTitle.setText("Enter PIN");
            binding.txtPinSubtitle.setText("Enter your PIN to access FocusLock");
            binding.btnForgotPin.setVisibility(View.VISIBLE);
            binding.btnForgotPin.setOnClickListener(v -> showForgotPinDialog());
        }
    }

    private void setupKeypad() {
        View.OnClickListener listener = v -> {
            if (enteredPin.length() < 4) {
                String digit = ((android.widget.TextView) v).getText().toString();
                enteredPin += digit;
                updateDots();
                if (enteredPin.length() == 4) {
                    new Handler(Looper.getMainLooper()).postDelayed(this::onPinComplete, 150);
                }
            }
        };

        binding.btnKey0.setOnClickListener(listener);
        binding.btnKey1.setOnClickListener(listener);
        binding.btnKey2.setOnClickListener(listener);
        binding.btnKey3.setOnClickListener(listener);
        binding.btnKey4.setOnClickListener(listener);
        binding.btnKey5.setOnClickListener(listener);
        binding.btnKey6.setOnClickListener(listener);
        binding.btnKey7.setOnClickListener(listener);
        binding.btnKey8.setOnClickListener(listener);
        binding.btnKey9.setOnClickListener(listener);

        binding.btnKeyDelete.setOnClickListener(v -> {
            if (enteredPin.length() > 0) {
                enteredPin = enteredPin.substring(0, enteredPin.length() - 1);
                updateDots();
            }
        });
    }

    private void updateDots() {
        int activeBg   = R.drawable.bg_slot_active;
        int inactiveBg = R.drawable.bg_slot_inactive;

        binding.dot1.setBackgroundResource(enteredPin.length() >= 1 ? activeBg : inactiveBg);
        binding.dot2.setBackgroundResource(enteredPin.length() >= 2 ? activeBg : inactiveBg);
        binding.dot3.setBackgroundResource(enteredPin.length() >= 3 ? activeBg : inactiveBg);
        binding.dot4.setBackgroundResource(enteredPin.length() >= 4 ? activeBg : inactiveBg);
    }

    private void onPinComplete() {
        if (MODE_CHANGE.equals(mode)) {
            handleChangePinFlow();
        } else if (MODE_SETUP.equals(mode)) {
            handleSetupPinFlow();
        } else {
            handleVerifyPinFlow();
        }
    }

    private void handleChangePinFlow() {
        if (changeStage == 0) {
            // Stage 0: verify current PIN
            if (PinManager.verifyPin(this, enteredPin)) {
                changeStage = 1;
                enteredPin = "";
                firstStepPin = null;
                updateDots();
                binding.txtPinTitle.setText("Enter New PIN");
                binding.txtPinSubtitle.setText("Choose a new 4-digit PIN");
                binding.btnForgotPin.setVisibility(View.GONE);
            } else {
                shakeDots();
                binding.txtPinSubtitle.setText("Incorrect current PIN. Try again");
                enteredPin = "";
                updateDots();
            }
        } else if (changeStage == 1) {
            // Stage 1: entered new PIN
            firstStepPin = enteredPin;
            changeStage = 2;
            enteredPin = "";
            updateDots();
            binding.txtPinTitle.setText("Confirm New PIN");
            binding.txtPinSubtitle.setText("Re-enter your new 4-digit PIN");
        } else if (changeStage == 2) {
            // Stage 2: confirm new PIN
            if (enteredPin.equals(firstStepPin)) {
                showSecurityQuestionSetupDialog(enteredPin);
            } else {
                shakeDots();
                binding.txtPinSubtitle.setText("PINs didn't match. Try again");
                changeStage = 1;
                firstStepPin = null;
                enteredPin = "";
                updateDots();
                binding.txtPinTitle.setText("Enter New PIN");
            }
        }
    }

    private void handleSetupPinFlow() {
        if (firstStepPin == null) {
            // First step of setup complete
            firstStepPin = enteredPin;
            enteredPin = "";
            updateDots();
            binding.txtPinTitle.setText("Confirm PIN");
            binding.txtPinSubtitle.setText("Re-enter your 4-digit PIN");
        } else {
            // Second step of setup
            if (enteredPin.equals(firstStepPin)) {
                showSecurityQuestionSetupDialog(enteredPin);
            } else {
                shakeDots();
                binding.txtPinSubtitle.setText("PINs didn't match. Try again");
                firstStepPin = null;
                enteredPin = "";
                updateDots();
                binding.txtPinTitle.setText("Set a PIN");
            }
        }
    }

    private void handleVerifyPinFlow() {
        if (PinManager.verifyPin(this, enteredPin)) {
            PinManager.setSessionAuthenticated(true);
            setResult(RESULT_OK);
            finish();
        } else {
            shakeDots();
            binding.txtPinSubtitle.setText("Incorrect PIN. Try again");
            enteredPin = "";
            updateDots();
        }
    }

    private void shakeDots() {
        Animation shake = new TranslateAnimation(0, 25, 0, 0);
        shake.setDuration(50);
        shake.setRepeatCount(4);
        shake.setRepeatMode(Animation.REVERSE);
        binding.layoutPinDots.startAnimation(shake);
    }

    private void showSecurityQuestionSetupDialog(String pendingPin) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        com.harithdev.focuslock.databinding.DialogSecurityQuestionSetupBinding dialogBinding =
                com.harithdev.focuslock.databinding.DialogSecurityQuestionSetupBinding.inflate(getLayoutInflater());
        dialog.setContentView(dialogBinding.getRoot());
        dialog.setCancelable(false);

        String[] questions = new String[]{
                "What is your first pet's name?",
                "What city were you born in?",
                "What is your favorite book or movie?",
                "What was your childhood nickname?"
        };

        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, questions) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v).setTextColor(0xFFFFFFFF);
                    ((android.widget.TextView) v).setTextSize(14);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v).setTextColor(0xFFFFFFFF);
                    ((android.widget.TextView) v).setBackgroundColor(0xFF14171E);
                    ((android.widget.TextView) v).setPadding(32, 24, 32, 24);
                }
                return v;
            }
        };
        dialogBinding.spinnerQuestions.setAdapter(adapter);

        dialogBinding.btnSaveSecurity.setOnClickListener(v -> {
            String answer = dialogBinding.editAnswer.getText().toString().trim();
            if (answer.isEmpty()) {
                dialogBinding.editAnswer.setError("Please enter an answer");
                return;
            }

            String selectedQuestion = questions[dialogBinding.spinnerQuestions.getSelectedItemPosition()];
            PinManager.setPin(this, pendingPin);
            PinManager.saveSecurityQuestion(this, selectedQuestion, answer);
            PinManager.setSessionAuthenticated(true);

            android.widget.Toast.makeText(this, "PIN & Security Question saved", android.widget.Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            setResult(RESULT_OK);
            finish();
        });

        dialog.show();
    }

    private void showForgotPinDialog() {
        if (PinManager.isSecurityQuestionSet(this)) {
            showSecurityQuestionRecoveryDialog();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Forgot PIN?")
                    .setMessage("To reset your PIN, go to Android Settings → Apps → FocusLock → Storage & Cache → Clear Storage.\n\nThis will reset your PIN so you can set a new one.")
                    .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                    .show();
        }
    }

    private void showSecurityQuestionRecoveryDialog() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        com.harithdev.focuslock.databinding.DialogSecurityQuestionRecoveryBinding dialogBinding =
                com.harithdev.focuslock.databinding.DialogSecurityQuestionRecoveryBinding.inflate(getLayoutInflater());
        dialog.setContentView(dialogBinding.getRoot());

        dialogBinding.txtQuestion.setText(PinManager.getSecurityQuestion(this));

        dialogBinding.btnVerifyAnswer.setOnClickListener(v -> {
            String inputAnswer = dialogBinding.editAnswer.getText().toString().trim();
            if (PinManager.verifySecurityAnswer(this, inputAnswer)) {
                dialog.dismiss();
                PinManager.clearPin(this);
                android.widget.Toast.makeText(this, "Identity verified. Please set a new PIN", android.widget.Toast.LENGTH_LONG).show();

                mode = MODE_SETUP;
                firstStepPin = null;
                enteredPin = "";
                updateDots();
                binding.txtPinTitle.setText("Set a PIN");
                binding.txtPinSubtitle.setText("Create a 4-digit PIN to protect your settings");
                binding.btnForgotPin.setVisibility(View.GONE);
            } else {
                dialogBinding.txtError.setVisibility(View.VISIBLE);
                dialogBinding.txtError.setText("Incorrect answer. Please try again.");
                Animation shake = new TranslateAnimation(0, 20, 0, 0);
                shake.setDuration(50);
                shake.setRepeatCount(3);
                shake.setRepeatMode(Animation.REVERSE);
                dialogBinding.editAnswer.startAnimation(shake);
            }
        });

        dialog.show();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}
