package com.harithdev.focuslock.security;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * PinManager — Utility for secure PIN storage and verification.
 *
 * Stores the SHA-256 hash of the user's PIN in SharedPreferences.
 * The PIN is never saved in plain text.
 */
public class PinManager {

    private static final String PREFS_NAME   = "focuslock_pin";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SET  = "pin_set";

    private static final String KEY_SECURITY_QUESTION    = "security_question";
    private static final String KEY_SECURITY_ANSWER_HASH = "security_answer_hash";

    /** In-memory session authentication state */
    private static volatile boolean sessionAuthenticated = false;

    public static boolean isSessionAuthenticated() {
        return sessionAuthenticated;
    }

    public static void setSessionAuthenticated(boolean authenticated) {
        sessionAuthenticated = authenticated;
    }

    public static void lockSession() {
        sessionAuthenticated = false;
    }

    /** Returns true if the user has created a PIN */
    public static boolean isPinSet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PIN_SET, false);
    }

    /** Hashes and saves the new PIN */
    public static void setPin(Context context, String pin) {
        String hash = hashPin(context, pin);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_PIN_HASH, hash)
                .putBoolean(KEY_PIN_SET, true)
                .apply();
    }

    /** Saves Security Question and SHA-256 hashed answer */
    public static void saveSecurityQuestion(Context context, String question, String answer) {
        if (question == null || answer == null) return;
        String answerHash = hashPin(context, answer.trim().toLowerCase(java.util.Locale.ROOT));
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SECURITY_QUESTION, question)
                .putString(KEY_SECURITY_ANSWER_HASH, answerHash)
                .apply();
    }

    /** Retrieves saved security question */
    public static String getSecurityQuestion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SECURITY_QUESTION, "What is your favorite book or movie?");
    }

    /** Returns true if security question & answer exist */
    public static boolean isSecurityQuestionSet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.contains(KEY_SECURITY_ANSWER_HASH);
    }

    /** Verifies input answer against stored hash */
    public static boolean verifySecurityAnswer(Context context, String inputAnswer) {
        if (inputAnswer == null || inputAnswer.trim().isEmpty()) return false;
        String inputHash = hashPin(context, inputAnswer.trim().toLowerCase(java.util.Locale.ROOT));
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedHash = prefs.getString(KEY_SECURITY_ANSWER_HASH, "");
        return storedHash.equals(inputHash);
    }

    /** Verifies input PIN against stored hash */
    public static boolean verifyPin(Context context, String pin) {
        if (!isPinSet(context)) return true;
        String inputHash = hashPin(context, pin);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedHash = prefs.getString(KEY_PIN_HASH, "");
        return storedHash.equals(inputHash);
    }

    /** Clears stored PIN and security question */
    public static void clearPin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    /** Generates SHA-256 hash salted with package name */
    private static String hashPin(Context context, String pin) {
        try {
            String salted = context.getPackageName() + ":" + pin;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(salted.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(pin.hashCode());
        }
    }
}
