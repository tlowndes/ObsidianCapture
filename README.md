# Vault Capture

Tiny Android app with two jobs:

- **Task**: appends `- [ ] #task ... 📅 date` to the end of `## Active` in `01 - Inbox/⚡ Quick Task.md`.
- **Link**: saves a note into `01 - Inbox/ReadItLater Inbox/`. The app also shows in the Android Share sheet, so sharing from Chrome, YouTube or Reddit saves the link in one tap with no UI.

First launch: pick your vault root folder on the phone (the one containing `.obsidian`).
It must be a folder your sync tool keeps in sync with the vault.

## Build

Open this folder in Android Studio, let it sync, then Run on your phone (or Build > Build APK).
