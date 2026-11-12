# Usage Guard

An Android app that limits how long you stay in the apps you choose. When you hit the limit, the app locks behind a cooldown screen until the timer runs out.

Everything stays on the phone. There's no account and no network access.

## Features

- **Opt-in limits.** Only the apps you switch on (or put in a group) are timed and locked. Everything else is just counted.
- **Cooldown lock.** After continuous use past the limit (default 2 min), a full-screen lock covers the app for the cooldown (default 15 min). The app's audio is paused. The only way out is the home screen.
- **Per-app limits.** Each app can have its own session limit and cooldown.
- **Groups.** Apps in a group share one timer and lock together, for example a Social group with Instagram, X and Snapchat. A Social group is pre-filled from the social apps you have installed.
- **Motivational videos.** Optionally plays videos you pick from your gallery on the lock screen, shuffled or in order.
- **Today screen.** Shows screen time, an hourly chart, per-app time and sessions, cooldowns, and each limited app's use in the last 5 minutes.
- **NFC tags.** Write and name NFC stickers, then scan them in the app or with the app closed. Scan history is kept. Home actions per tag are planned.

## Permissions

| Permission | Why |
|---|---|
| Usage access | See which app is in front and for how long |
| Display over other apps | Show the cooldown lock over the limited app |
| Notifications (optional) | The "Guard on" notice that keeps tracking running |
| Ignore battery optimization (optional) | Stops aggressive phones from killing the guard |
| NFC | Read and write tags |

If you install the APK outside the Play Store, Android blocks usage access until you unlock it once: open App info → ⋮ → **Allow restricted settings**.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are signed with the debug key so they install directly.

## Project layout

```
app/src/main/java/com/nishith/usageguard/
  GuardService.kt     foreground service: tracks the app in front, enforces limits
  LockShield.kt       instant overlay shown when a limit is hit
  LockActivity.kt     full-screen cooldown lock (plus optional video)
  GuardPrefs.kt       settings, limits, groups, lock state
  UsageRepo.kt        usage stats, today's totals, last-5-minutes usage
  NfcTags.kt          tag storage and NFC read/write
  TagScanActivity.kt  opens when a tag is tapped with the app closed
  ui/                 Compose screens: Setup, Today, Apps, Group editor, Tags, Settings
```

## Versions

- **0.1** Core tracking, cooldown lock, Today, Settings, setup
- **0.2** Real app blocking (lock screen instead of an overlay), groups, App info helper
- **0.3** Opt-in limits: only the apps you add are locked
- **0.4** Motivational videos on the lock screen, NFC tags
- **0.5** Fixes for missed locks (screen switches inside apps, Android 15 launch rules, killed-guard banner), last-5-minutes usage
