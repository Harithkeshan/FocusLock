package com.harithdev.focuslock.util;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

/**
 * AppCategoryHelper — Categorizes applications using native Android API (API 26+)
 * with intelligent package-name keyword fallback.
 */
public class AppCategoryHelper {

    public static final String CATEGORY_ALL          = "All";
    public static final String CATEGORY_SOCIAL       = "Social";
    public static final String CATEGORY_GAMING       = "Gaming";
    public static final String CATEGORY_VIDEO        = "Video";
    public static final String CATEGORY_MESSAGING    = "Messaging";
    public static final String CATEGORY_PRODUCTIVITY = "Productivity";
    public static final String CATEGORY_OTHER        = "Other";

    /**
     * Categorizes an app using PackageManager native category when available,
     * falling back to keyword heuristics.
     */
    public static String categorize(PackageManager pm, String packageName) {
        if (packageName == null) return CATEGORY_OTHER;

        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                switch (info.category) {
                    case ApplicationInfo.CATEGORY_SOCIAL:
                        return CATEGORY_SOCIAL;
                    case ApplicationInfo.CATEGORY_GAME:
                        return CATEGORY_GAMING;
                    case ApplicationInfo.CATEGORY_VIDEO:
                    case ApplicationInfo.CATEGORY_AUDIO:
                        return CATEGORY_VIDEO;
                    case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                        return CATEGORY_PRODUCTIVITY;
                }
            } catch (Exception ignored) {}
        }

        return categorize(packageName);
    }

    /**
     * Fallback categorization based on package name heuristics.
     */
    public static String categorize(String packageName) {
        if (packageName == null) return CATEGORY_OTHER;
        String pkg = packageName.toLowerCase(Locale.ROOT);

        // ── Social Media ──────────────────────────────────────
        if (pkg.contains("instagram") || pkg.contains("twitter") || pkg.contains("x.android")
                || pkg.contains("facebook") || pkg.contains("snapchat") || pkg.contains("reddit")
                || pkg.contains("tiktok") || pkg.contains("threads") || pkg.contains("pinterest")
                || pkg.contains("tumblr") || pkg.contains("linkedin")) {
            return CATEGORY_SOCIAL;
        }

        // ── Video & Streaming ─────────────────────────────────
        if (pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("disney")
                || pkg.contains("primevideo") || pkg.contains("hulu") || pkg.contains("twitch")
                || pkg.contains("vimeo") || pkg.contains("hotstar") || pkg.contains("mxplayer")) {
            return CATEGORY_VIDEO;
        }

        // ── Messaging & Chat ──────────────────────────────────
        // Note: Check naver.line / line.android specifically to avoid matching shopping apps like "cargills.online"
        if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("discord")
                || pkg.contains("viber") || pkg.contains("signal") || pkg.contains("messenger")
                || pkg.contains("wechat") || pkg.contains("skype")
                || pkg.contains("naver.line") || pkg.contains("line.android") || pkg.endsWith(".line")) {
            return CATEGORY_MESSAGING;
        }

        // ── Gaming ────────────────────────────────────────────
        if (pkg.contains("supercell") || pkg.contains("king.com") || pkg.contains("roblox")
                || pkg.contains("mojang") || pkg.contains("epicgames") || pkg.contains("pubg")
                || pkg.contains("riotgames") || pkg.contains("tencent") || pkg.contains("gameloft")
                || pkg.contains(".game") || pkg.endsWith("games")) {
            return CATEGORY_GAMING;
        }

        // ── Productivity ──────────────────────────────────────
        if (pkg.contains("slack") || pkg.contains("notion") || pkg.contains("trello")
                || pkg.contains("asana") || pkg.contains("evernote") || pkg.contains("microsoft.office")
                || pkg.contains("google.android.apps.docs") || pkg.contains("google.android.apps.sheets")
                || pkg.contains("todoist") || pkg.contains("ticktick")) {
            return CATEGORY_PRODUCTIVITY;
        }

        return CATEGORY_OTHER;
    }
}
