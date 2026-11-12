# Play Console answers

Ready-to-paste answers for the **App content** section of Play Console. They describe Usage Guard as built in this repo; if the app changes (for example it starts using the internet), update them.

Privacy policy URL: `https://github.com/nishithdev/usage-guard-android/blob/main/PRIVACY.md`

---

## Foreground service declaration (issue #4)

**Foreground service type:** Special use (`specialUse`)

**Use case:** *Other / custom:* Digital wellbeing: enforcing app time limits the user set

**Describe the app functionality that uses this foreground service type:**
> Usage Guard lets people limit how long they spend in apps they choose (for example Instagram or YouTube). While the user has the guard switched on, a foreground service checks once a second which app is in front, using the Usage Access permission the user granted. When the user has been in a limited app continuously for longer than their own limit, the service shows a full-screen cooldown lock over that app until the cooldown ends. The service only times apps the user explicitly added. It does not collect or send any data, and the app has no internet permission. A persistent "Guard on" notification is shown the whole time, and the user can switch the guard off in Settings.

**What is the impact on the user if the task is deferred or interrupted by the system?**
> The limits stop working: the user can keep using an app past the limit they set for themselves, and no lock appears. The whole point of the app is to step in at the exact moment the limit is reached, so a deferred or batched job (WorkManager, JobScheduler) would let the user pass their limit unnoticed. The user would have to reopen the app to restart the guard, and the Today screen warns them when this happened.

**Why other APIs don't work:**
> The check has to run continuously while the phone is in use and act within about a second of the limit being reached. WorkManager and JobScheduler can be delayed for minutes, and there's no system callback for "the user has been in app X for N minutes".

**Video:** see issue #5 for the shot list. Upload it as unlisted and paste the link here.

---

## Data safety (issue #7)

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (no data is collected or transmitted) |
| Do you provide a way for users to request that their data be deleted? | Not applicable; all data stays on the device and is removed by clearing app storage or uninstalling |

Notes for the reviewer, if asked:
- App usage information (which app is open, for how long) is read with Usage Access and **processed only on the device**. Under Play's definition, data processed only on-device that is never sent off the device is not "collected".
- The app has **no `INTERNET` permission**.
- Gallery videos are chosen with the system photo picker; the app keeps only a reference to the chosen files.

---

## Content rating (issue #7)

Category: **Utility, Productivity, Communication, or Other**

Answer **No** to every question: violence, sexuality, language, controlled substances, gambling, user interaction and sharing, sharing location, and digital purchases. Expected rating: **Everyone / PEGI 3**.

---

## Target audience and content (issue #7)

- Target age groups: **18 and over**
- Appeals to children: **No**

## Ads

- Contains ads: **No**

## App access

- **All functionality is available without special access.** No login. Reviewers need to grant Usage access and Display over other apps when the setup screen asks. If the switch is greyed out, open App info → ⋮ → Allow restricted settings (only needed when the app is installed outside the Play Store).

## News app / COVID / government / financial features

- **No** to all.

## Permissions to explain if Play asks

| Permission | Why |
|---|---|
| `PACKAGE_USAGE_STATS` (Usage access) | Core function: knowing which app is open and for how long, to enforce the user's own limits |
| `SYSTEM_ALERT_WINDOW` (Display over other apps) | Core function: showing the cooldown lock over a limited app |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Continuous limit enforcement (see the declaration above) |
| `RECEIVE_BOOT_COMPLETED` | Restart the guard after a reboot so limits keep working |
| `NFC` | Optional NFC tags feature |
