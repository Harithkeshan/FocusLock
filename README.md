# FocusLock

FocusLock is a personal productivity app that blocks distracting apps after your set daily limit is reached.

Features:
- **Accurate Screen Time Tracking:** Uses real-time `UsageEvents` to accurately calculate foreground usage, completely bypassing MIUI/OEM battery optimization quirks.
- **Modern UI:** Premium design with rounded corners, pill-shaped buttons, and intuitive session selection.
- **Flexible Limits:** Set daily time limits for any installed app.
- **Session Splitting:** Split your limit into equal sessions (e.g. 4 x 15 min).
- **Cooldown Enforcement:** Cooldown period between sessions (40 min – 5 hours).
- **Early Exit Tracking:** Closing an app early still counts the full slot as used.
- **Sleep Mode:** Block apps entirely during specific hours (e.g. 11 PM to 7 AM).
- **Smart Block Screens:** 5 unique, situation-specific block screens (Sleep, Session Timeout, Early Exit, Daily Limit, All Sessions Done).
- **Smart Window Visibility Check:** Utilizes Android `AccessibilityWindowInfo` window scanning to check if the restricted app's window is visible on screen (even under transient overlays like keyboards, dialogs, or custom media viewers like Telegram's photo viewer), preventing false early-exit blocks.
- **Clean Notification Engine:** Overhauls warning notifications with local vector assets (`ic_notification.xml`) and static key warning de-duplication to completely eliminate SharedPreferences database bloat over time.
- **Background Reliable:** Persists across phone reboots and resets counters automatically at midnight.

