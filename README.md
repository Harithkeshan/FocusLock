<div align="center">

# 🔒 FocusLock

**Take back your time. One session at a time.**

A powerful Android app that doesn't just block distracting apps — it splits your screen time into disciplined sessions with enforced cooldowns, so you stay in control without going cold turkey.

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![API](https://img.shields.io/badge/Min%20SDK-26%20(Oreo)-brightgreen)](https://developer.android.com/about/versions/oreo)
[![Java](https://img.shields.io/badge/Language-Java-ED8B00?logo=openjdk&logoColor=white)](https://www.java.com)
[![License](https://img.shields.io/badge/License-Private-red)]()

</div>

---

## 💡 Why FocusLock?

Most app blockers give you a hard daily limit — **use it up, and you're done for the day.**

FocusLock takes a completely different approach. Instead of a single wall, it **splits your allowed time into multiple sessions** with mandatory cooldown periods between them. This means you get controlled access throughout the day, not a single binge followed by total withdrawal.

> **Example:** Instead of 1 hour straight on Instagram, FocusLock gives you **4 × 15-minute sessions** with a 1-hour cooldown between each. You can still check in — but you can't get lost in the scroll.

---

## ✨ Key Features

### 🧩 Session Splitting — *The Core Differentiator*
Split any daily limit into equal sessions with enforced cooldowns between them. This is the feature that makes FocusLock fundamentally different from every other app blocker on the market.

| Setting | What it does |
|---|---|
| **Daily Limit** | Total screen time allowed per day (e.g., 1 hour) |
| **Number of Sessions** | How many chunks to split it into (e.g., 4) |
| **Cooldown Duration** | Mandatory break between sessions (40 min – 5 hours) |

### 🎯 5 Context-Aware Block Screens
Not a generic "App Blocked" wall. FocusLock shows a **different, tailored message** depending on exactly *why* the app was blocked:

| Reason | Message | Icon |
|---|---|---|
| Sleep hours active | *"Sleep hours, phone down!"* | 🌙 Moon |
| Session slot used up | *"Time's up for this session!"* | ⏱️ Timer |
| Exited during cooldown | *"Good call stepping away!"* | ⏸️ Pause |
| Daily limit reached | *"That's your daily dose!"* | ⏳ Hourglass |
| All sessions exhausted | *"All sessions done for today!"* | 📊 Sessions |

### 🌙 Sleep Mode
Block apps entirely during your sleep hours (e.g., 11 PM – 7 AM). Per-app configurable.

### 🛡️ Anti-Bypass Protection
- **PIN Lock** with security question recovery
- **Device Admin Protection** to prevent uninstallation
- **Direction-Aware Enforcement** — making restrictions stricter takes effect immediately; relaxing them only applies the next day (so you can't cheat in the moment)
- **Early Exit Cooldown** — leaving an app mid-session still triggers a cooldown period before you can return, preventing rapid on-off abuse

### 🔒 100% Offline & Private
FocusLock has **zero network permissions**. No data collection, no analytics, no cloud sync. Your usage data never leaves your device — ever.

---

## 🏗️ Architecture & Tech Stack

FocusLock is built with a clean, modern Android architecture:

```
┌─────────────────────────────────────────────┐
│                    UI Layer                  │
│  Activities → ViewModels → LiveData → Views │
├─────────────────────────────────────────────┤
│               Repository Layer               │
│         FocusLockRepository (Single          │
│          source of truth for DAOs)           │
├─────────────────────────────────────────────┤
│                Data Layer                    │
│    Room Database → DAOs → Entity Models     │
├─────────────────────────────────────────────┤
│              Service Layer                   │
│   UsageTrackingService (Foreground)          │
│   AccessibilityService (Window Detection)   │
│   MidnightResetWorker (WorkManager)         │
│   BootReceiver (Survives Reboots)           │
└─────────────────────────────────────────────┘
```

| Technology | Purpose |
|---|---|
| **Java** | Primary language |
| **Room** | Local SQLite database for restrictions & usage data |
| **ViewBinding** | Type-safe view references |
| **ViewModel + LiveData** | Reactive UI with lifecycle awareness |
| **WorkManager** | Reliable midnight counter resets |
| **AccessibilityService** | Real-time window visibility detection |
| **UsageStatsManager** | Accurate foreground usage tracking |
| **Material Design 3** | Premium Emerald & Obsidian dark theme |
| **Timber** | Structured logging for diagnostics |

---

## 🧠 Technical Highlights

<details>
<summary><b>Smart Window Visibility Detection</b></summary>

FocusLock doesn't rely on simple foreground detection. It uses Android's `AccessibilityWindowInfo` to scan whether the restricted app's window is actually visible on screen — even under transient overlays like keyboards, dialogs, or media viewers (e.g., Telegram's photo viewer). This prevents false early-exit triggers that plague other blockers.

</details>

<details>
<summary><b>OEM Battery Optimization Bypass</b></summary>

Many Chinese OEM ROMs (MIUI, ColorOS, OneUI) aggressively kill background services. FocusLock uses real-time `UsageEvents` polling instead of relying on `UsageStatsManager.queryUsageStats()`, which is often inaccurate on these devices. This ensures accurate tracking regardless of the device manufacturer.

</details>

<details>
<summary><b>Delayed vs. Immediate Enforcement</b></summary>

When users update their settings, FocusLock applies a directional enforcement policy:
- **More restrictive** changes (lower limits, fewer sessions) → take effect **immediately**
- **Less restrictive** changes (higher limits, more sessions) → take effect **tomorrow**

This prevents the common bypass pattern of temporarily raising limits to get more screen time.

</details>

<details>
<summary><b>Notification De-duplication Engine</b></summary>

Warning notifications use static channel keys and intelligent de-duplication to prevent SharedPreferences bloat. Each warning type (75% limit, 90% limit) fires exactly once per app per day, with no leftover state accumulation over weeks of usage.

</details>

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK 34

### Build & Run
```bash
# Clone the repository
git clone https://github.com/Harithkeshan/FocusLock.git

# Open in Android Studio and sync Gradle

# Build the debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

### Permissions Required
| Permission | Why |
|---|---|
| **Usage Access** | Track which app is currently in the foreground |
| **Draw Over Other Apps** | Display the block screen overlay |
| **Accessibility Service** | Detect app window visibility for accurate tracking |
| **Notifications** | Warn when approaching daily limits |

---

## 🎨 Design Language

FocusLock uses a custom **"Neutral Obsidian + Punchy Emerald"** design system:

| Token | Value | Usage |
|---|---|---|
| `surface_bg` | `#0C0E12` | Primary background |
| `card_bg` | `#14171E` | Card surfaces |
| `emerald_primary` | `#34D399` | Accent, CTAs, active states |
| `text_primary` | `#F1F5F9` | Headings & body text |
| `text_secondary` | `#94A3B8` | Captions & metadata |

All icons are custom-designed Android Vector Drawables — no emoji, no third-party icon packs.

---

## 📁 Project Structure

```
app/src/main/java/com/harithdev/focuslock/
├── database/          # Room database, DAOs, entities
├── receiver/          # Boot receiver, Device Admin receiver
├── repository/        # Single source of truth (Repository pattern)
├── security/          # PIN manager, hashing utilities
├── service/           # Foreground tracking service
├── ui/
│   ├── applist/       # App selection & settings bottom sheet
│   ├── block/         # 5 context-aware block screens
│   ├── dashboard/     # Usage analytics & bar charts
│   ├── detail/        # Per-app restriction configuration
│   ├── onboarding/    # 3-slide intro flow
│   ├── permission/    # Permission setup wizard
│   └── pin/           # PIN setup & verification
├── util/              # Time formatting, category helpers
└── worker/            # Midnight reset via WorkManager
```

---

## 👤 Author

**Harith Keshan**

---

<div align="center">
<i>Built with discipline, for discipline.</i>
</div>
