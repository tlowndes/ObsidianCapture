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

- **Read**: the Read It Later inbox as a reading list (Unread, Read, Archived, with search). Swipe right to mark
  read/unread, left to archive (long-press for the same menu). Articles open in a clean reader view with a progress
  bar, Aa for text size and serif/sans, and the place you got to is remembered. Long-press a paragraph to highlight
  it; highlights are kept as `> ` quotes under `## Highlights` at the end of the note. Leaving an unread article asks
  whether you've finished it (Mark read, Archive or Keep unread). Read state is `status: unread|read|archived` in
  frontmatter (no status counts as unread). Archive moves the note to `ReadItLater Inbox/Archive/`, with Undo.

Settings (gear on Capture) holds the vault folder, reminder times, recurring-task switch and folder names.

First launch: pick your vault root folder on the phone (the one containing `.obsidian`).

Every write is a read-modify-write of just the part it owns, so edits made in Obsidian are kept. It is written to
sit alongside a sync tool such as Resilio Sync, which keeps whichever copy was written last:

- Just before saving, the note is read again. If it changed in the meantime (a sync landed), the app redoes its
  change on the new copy instead of writing over it.
- Each save is written in one go and read back, so a half-written note is never left for the sync tool to pick up.
- **Log** only saves the sections you changed in the form. If one of them was also changed on another device since
  the form loaded, it is left alone and you're told; Save again replaces it.
- **Ideas** finds an entry by its heading and text, not its position, and won't edit an entry or summary that was
  changed elsewhere.
- Tasks are matched by their exact line, so a task changed in Obsidian is never overwritten.

## Build

Open this folder in Android Studio, let it sync, then Run on your phone (or Build > Build APK).
Use a JDK between 17 and 22 for Gradle (Android Studio's bundled JBR 21 is fine; Java 25 is not).

`design/` has the HTML mockups (`render.sh`, `render2.sh` re-render the PNGs).
