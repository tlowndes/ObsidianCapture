# Vault Capture

Android app that writes straight into an Obsidian vault (no plugin needed; it uses the vault folder
your sync tool keeps up to date). Five tabs:

- **Capture**: add a task to `01 - Inbox/⚡ Quick Task.md` (`- [ ] #task ... 📅 date`), or save a link as a
  full article note in `01 - Inbox/ReadItLater Inbox/`. The app is also in the Android Share sheet, so
  sharing from Chrome, YouTube or Reddit saves the page in one tap.
- **Tasks**: the Quick Task note grouped by due date (Overdue, Today, Tomorrow, This week, Later, No date),
  or in note order. Tick, reschedule or remove with Undo. Ticking a recurring task (`🔁 every week`, etc.)
  creates the next occurrence like the Tasks plugin. Done tasks move to `## Archive` with a `✅` date.
- **Log**: fill in today's daily note in a form: Morning, Evening or All. Built from the `##` sections of your
  Daily Note template; Energy and Overwhelmed are written to frontmatter. **Add section** adds a new `##`
  heading to the template and today's note (short text, bullets, checklist, choice or yes/no; choice and
  yes/no use Meta Bind inputs bound to frontmatter). Reminders at 08:00 and 21:00 (editable) are skipped if
  that part of the log is already filled in. The app only remembers custom section types in
  `99 - Meta/capture-config.json`.
- **Ideas**: each idea is `03 - Projects/<Title>/<Title>.md` with a Summary and a newest-first Log. Add text and
  photos (camera or gallery, shrunk to 1600 px and saved to `99 - Meta/Assets`, embedded as `![[...]]`),
  edit or delete old entries, change status. Unsent text is kept as a draft.

- **Read**: the Read It Later inbox as a reading list (Unread, Read, Archived, with search). Articles open in a
  clean reader view; opening one marks it read. Read state is `status: unread|read|archived` in the note's
  frontmatter (no status counts as unread). Archive moves the note to `ReadItLater Inbox/Archive/`, with Undo.
  Long-press an article in the list to mark it read/unread or archive it without opening it.

Settings (gear on Capture) holds the vault folder, reminder times, recurring-task switch and folder names.

First launch: pick your vault root folder on the phone (the one containing `.obsidian`).

Every write is a read-modify-write of just the part it owns, so edits made in Obsidian are kept.
If your sync tool and the app change the same file at the same moment you can still get a sync conflict.

## Build

Open this folder in Android Studio, let it sync, then Run on your phone (or Build > Build APK).
Use a JDK between 17 and 22 for Gradle (Android Studio's bundled JBR 21 is fine; Java 25 is not).

`design/` has the HTML mockups (`render.sh`, `render2.sh` re-render the PNGs).
