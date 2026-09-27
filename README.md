# FixTok Rework

FixTok is an unofficial TikTok client for Android TV / Google TV, built around a
full-screen WebView. Reworked edition: pure TV navigation in the main screen,
separate secure Login WebView.

## Features

- 🖥️ Desktop version of TikTok in a full-screen WebView
- 🎮 Main screen: pure D-pad navigation (mouse removed)
- 🔐 Separate Login WebView with strict domain allowlist
- 🖱️ Virtual mouse exists ONLY inside the Login WebView
- 🍪 Cookie and session persistence (shared WebView cookie store)
- 🔄 Safe session migration: old sessions keep working automatically
- 🚪 Logout from Settings
- 🎬 HTML5 fullscreen video support
- 🚀 Splash screen
- 👤 by Manas

## Remote Control — Main screen

| Remote Button | Action |
|---|---|
| D-pad | Focus / scroll inside TikTok |
| OK / Enter | Click focused element |
| Back | Go back / exit (double-press) |
| Menu | Settings (bass boost, account: login / logout) |

## Remote Control — Login WebView

| Remote Button | Action |
|---|---|
| D-pad | Move virtual mouse cursor |
| OK / Enter | Click |
| Back | Close popup / close login, return to main screen |

The mouse cursor disappears completely after login: it does not exist in the
main UI.

## Security

- Login WebView navigation is restricted to an allowlist of hosts
  (tiktok.com, accounts.google.com, facebook.com, appleid.apple.com,
  idmsa.apple.com), checked against the real URI host — never `url.contains()`.
- Camera/microphone web permission requests are always denied.
- No JavaScript bridge, no secrets in URLs or logs.
- Safe Browsing stays enabled.

## Technology

- Kotlin · Android TV / Google TV · WebView · Gradle

## Architecture

```text
FixTok
  ├── MainActivity   — TikTok WebView, D-pad only, mouse OFF
  ├── LoginActivity  — Login WebView, allowlist, mouse ON (login only)
  └── SettingsActivity — TV settings: bass, account, login/logout
```

## Support

Donations (USDT): `0xf3582caCE9450d8A9d9291D359Af75c89FC86C75`

Thank you for supporting FixTok! ❤️
