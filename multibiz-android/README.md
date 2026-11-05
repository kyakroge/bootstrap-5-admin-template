# MultiBiz – 5 WhatsApp Business accounts, one app

Android app that keeps **up to 5 WhatsApp Business accounts logged in at the same time**.
Each account has a custom name and color, and **every notification says which account it came from**.

> Ek hi app mein 5 WhatsApp Business accounts. Har account ka apna naam aur color hota hai.
> Notification aata hai toh title mein account ka naam hota hai, jaise **"Sharma Electronics • Ravi Kumar"**.

## Features

| Feature | Details |
|---|---|
| 5 accounts max | Hard limit of 5 slots. The add button is blocked after that. |
| Full isolation | Every account runs in its **own Android process** (`:wa1` … `:wa5`) with its own WebView data folder, so cookies and logins can never mix. A crash in one account doesn't affect the others. |
| Named notifications | Title `Account name • Sender`, account name in the header, account color, and an initials badge as the large icon. |
| Per-account ringtone | Each account has its own notification channel. You can pick a different sound per business (⋮ → *Notification sound & settings*). |
| Tap → opens the chat | Tapping a notification opens that account and the exact chat. |
| Background delivery | One silent foreground service keeps all account processes alive (`BIND_IMPORTANT`). It auto-starts after a reboot or app update. |
| Self-healing | Auto-retry with backoff when offline, reload on network return, WebView renderer-crash recovery, and a watchdog for stuck or frozen pages. |
| Logged-out alert | If a business unlinks this device, you get a "*Account X • Logged out*" alert. |
| Group Grabber | Export the members of any group this account is in to Excel (one file per group, named after the group: number, saved name, WhatsApp name, role), and export group chats to Excel (`<Group name> - Chat.xlsx`: date, time, sender, number, message). Bulk select, search, progress and Stop, then Share/Open. Files go to `Downloads/MultiBiz/<Account>/Groups/`. |
| Account description | Optional note per account (up to 80 chars, e.g. "Sales enquiries"), shown under the name on the home screen and in launcher shortcuts. |
| Profile photos | Each account shows its own WhatsApp profile photo (DP) on the home screen, in notifications, in launcher shortcuts and in the account screen header, framed in the account color. |
| Dashboard | Unread count and status (Connected / Not linked / Offline) for every account. |
| Security | Optional fingerprint/PIN app lock, optional screenshot/recents blocking, no cloud backup of sessions, cleartext HTTP disabled, and a JS bridge restricted to `https://web.whatsapp.com` only. |
| Media | File/photo upload, voice notes (mic permission), downloads saved to `Downloads/MultiBiz/<Account>/`. |
| Launcher shortcuts | Long-press the app icon to open any account directly. |

## How it works (important)

WhatsApp doesn't allow two copies of the WhatsApp Business app to run inside another app.
MultiBiz uses **WhatsApp's own Linked Devices feature**, the same thing WhatsApp Web uses.
Each account inside MultiBiz is a *linked device* of the real WhatsApp Business number.

So:
1. Each business number must still be active in the **WhatsApp Business app on some phone** (the primary device).
2. Open that phone's WhatsApp at least **once every 14 days**, otherwise WhatsApp expires the link.
3. Calls are not supported (WhatsApp Web doesn't support calls on this platform). Chats, media, documents, voice notes, labels and quick replies work as they do on WhatsApp Web.

## Add an account

1. Open MultiBiz → **Add account** → enter a name (e.g. *Sharma Electronics*) → pick a color.
2. A QR code appears.
3. On the phone with that business number: **WhatsApp Business → ⋮ → Linked devices → Link a device** → scan the QR.
   - Is the number on this same phone? Tap **"Log in with phone number"** in MultiBiz and enter the 8-character code in WhatsApp Business → Linked devices → *Link with phone number instead*.

## For reliable notifications (zaroori settings)

- Tap **Allow** on the "Allow background running" card (turns battery optimisation off for the app).
- **Xiaomi/Redmi/POCO, Oppo, Vivo, Realme, OnePlus:** also turn on *Autostart* and set Battery to *No restrictions* for MultiBiz.
- Don't *force stop* the app. Swiping it from recents is fine.
- RAM: each account is a live WhatsApp Web session (~150–300 MB). A phone with **6 GB+ RAM** is recommended for 5 accounts.

## Build

Requirements: JDK 17+, Android SDK (platform 35).

```bash
cd multibiz-android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # needs your own signing config
```

You can also open the `multibiz-android` folder in Android Studio and press Run.

- `minSdk 28` (Android 9+), `targetSdk 35` (Android 15).
- Package: `com.multibiz.hub`.
- Debug builds are signed with the committed `app/debug.keystore`, so a new build installs over the old one without logging accounts out.

## Code map

```
app/src/main/
├── AndroidManifest.xml          5 isolated processes (:wa1..:wa5), FGS, boot receiver
├── assets/inject.js             Notification API → native bridge, unread/state, blob downloads
└── java/com/multibiz/hub/
    ├── App.kt                   per-process WebView data dir (isolation)
    ├── core/
    │   ├── AccountStore.kt      accounts + settings (atomic JSON, max 5)
    │   ├── Notifier.kt          per-account channels & labelled notifications
    │   ├── KeepAliveService.kt  foreground service, binds all account processes
    │   ├── StatusBus.kt         unread/state from account processes → dashboard
    │   ├── Housekeeping.kt      add/rename/remove, data wipe, launcher shortcuts
    │   ├── LockGate.kt          biometric / PIN lock
    │   ├── Badge.kt, Slots.kt, BootReceiver.kt
    ├── session/
    │   ├── SessionManager.kt    the WebView session: bridge, retries, crash recovery
    │   ├── SessionService.kt    keeps the process alive; wipe on remove
    │   ├── AccountActivity.kt   account screen (file upload, mic, menu)
    │   └── Downloads.kt
    └── ui/                      dashboard, add/edit dialog, list adapter
```

## Disclaimer

MultiBiz is an independent app and is not affiliated with WhatsApp or Meta. It only loads the official
`web.whatsapp.com` client. It doesn't automate messages or read or store your chats anywhere outside your phone.
Use it in line with WhatsApp's Terms of Service.
