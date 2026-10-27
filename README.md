# Flight Deck

Flight Deck (v1.7) is a personal capture and daily-planning app that writes straight into an Obsidian vault. The notes stay ordinary markdown, so Obsidian, and whatever syncs the vault, remain the source of truth. It needs no Obsidian plugin.

The phone app is labelled **Flight Deck**. It is the daily home screen: what to do next, what is due, what to read, and the small loops (focus, habits, weather, calendar, jobs) that otherwise live in separate apps. A Windows desktop app in `desktop/` (window title **Vault Capture**) shares the same vault for tasks, projects, reading and quick capture.

## Writing into the vault

On Android, pick the vault root (the folder that contains `.obsidian`) with the system folder picker. The app keeps a persistent read/write grant. On Windows, pick the same folder in Settings; the app reads and writes it as a normal directory.

Defaults below match a vault laid out as `01 - Inbox`, `03 - Projects` and `99 - Meta`. The projects folder and the images folder can be changed in Settings. Every other path is fixed.

| What | Where | Format |
| --- | --- | --- |
| Quick tasks | `01 - Inbox/⚡ Quick Task.md` | Open tasks under `## Active`, done ones under `## Archive`. A task is `- [ ] #task …`, with optional `📅 YYYY-MM-DD`, `⏱ 15m` and `🔁 every week` (Tasks-plugin markers). |
| Tasks in other notes | Any other note in the vault | The same `- [ ] … #task` line. Ticked in place. A task added from search goes under the note's `## Tasks`, or at the end if that heading is missing. A plain line goes at the end. |
| Read It Later | `01 - Inbox/ReadItLater Inbox/` | One note per page, in the same shape as the Obsidian read-it-later plugin: frontmatter `status` (`unread`, `read` or `archived`) and `saved`, then `[[ReadItLater]]` and the article (or just the link if the page could not be fetched). YouTube links are saved as video notes. Archive moves the file to `ReadItLater Inbox/Archive/`. |
| Brain-dump note | `01 - Inbox/Capture <date>.md` | The leftover sentence from Sort it, when there is one. |
| Projects | `03 - Projects/<Title>/<Title>.md` | Frontmatter (`type`, `status`, `created`, `area`, `tags`), a title, `## Summary`, and `## Log` linking to `<Title> - Log.md`. Log entries are newest-first `### ` headings. Photos are embedded as `![[…]]`. |
| Photos | `99 - Meta/Assets` | JPEG, long side at most 1600 px. |
| Habits | `99 - Meta/Habits.md` | A habit list, a water goal, and a dated log table. Edit the list in Obsidian. Created with starter habits if it is missing. |
| Stuck menu | `99 - Meta/Stuck menu.md` | `## Quick`, `## Medium`, `## Long`, each line `- ☕ Make a proper coffee · 5 min`. Created with starters if missing. |
| Focus log | `99 - Meta/Focus log.md` | `- YYYY-MM-DD HH:mm · task · N min`. |
| Job applications | `02 - Area/Work/Job Hunt/Applications/` | One note per application, `type: application`, from `99 - Meta/Templates/(TEMPLATE) Application.md` when that template is present. |
| Home layout and calendars | `99 - Meta/capture-config.json` | Card order, hidden cards, and Proton ICS links. Other keys in the file are left alone. Synced with the vault, so the phone and any other device that reads this file share them. |

Task scans skip `99 - Meta`, the Read It Later inbox, dot-folders, and the vault's Templates and Templater folders. Code blocks are skipped, so a `tasks` query is not treated as a task. Only notes changed since the last look are read again.

Ticking a recurring task creates the next occurrence the way the Tasks plugin does (`every day`, `every 2 weeks`, `every monday`, `every weekday`, and `when done` to count from the tick). On the phone this stays switched on. The desktop Settings screen can switch it off. Done Quick Tasks move to `## Archive` with a `✅` date. Tasks in other notes are ticked where they are. Cancelling a task (weekly review) rewrites the line as `- [-] … ❌ date` and keeps it.

### Obsidian Sync

Every write is a read-modify-write of the part the app owns, so edits made in Obsidian are kept. It is written to sit alongside Obsidian Sync, which syncs the phone's vault while the Obsidian app runs and merges edits made in two places:

- Just before saving, the note is read again. If it changed in the meantime (a sync landed), the app redoes its change on the new copy instead of writing over it.
- Each save is written in one go and read back, so a half-written note is never left for the sync tool to pick up. On the desktop, the file is swapped in from a temporary file beside it.
- A project log entry is found by its heading and text, and a summary is only replaced when it still matches what was on screen. A changed entry or summary is left alone.
- Tasks are matched by their exact line, so a task changed in Obsidian is never overwritten.

## Android

Five tabs: **Deck**, **Capture**, **Tasks**, **Read**, **Projects**. The gear and the search icon are on Deck. Minimum Android 10.

### Deck

The home screen. A Low / Med / High energy chip (it resets to Med each morning) changes the day: Low moves **Stuck? Pick one.** to the top and prefers tasks of 15 minutes or less; High shows three top tasks instead of two. Pills along the top open Tasks (how many are due today or overdue), Focus (minutes logged today) and Habits.

Cards, in an order you can rearrange, and any of which you can hide, under Settings › Cards on home:

- **Next step**: the earliest dated task that is not a bill and was not put off with Later today. Start opens Focus. Later hides it until tomorrow.
- **Weather & travel**: today's forecast from Open-Meteo, using the phone's coarse location (no API key, cached for an hour). Tap for a Morning / Afternoon / Evening strip. If today's next timed Proton event has a place, a drive time from OpenStreetMap and OSRM is shown, with a leave-by time.
- **Today**: today's events from the Proton calendars linked in Settings. The one happening now is highlighted. Hidden when there is nothing on.
- **Bills**: `#bill` tasks due in the next seven days, with a `£` amount when the line has one. Marking one paid ticks the task.
- **Projects**: the active project, when there is one. Opens the Projects tab.
- **Top tasks**: the next dated tasks after the next step. Tick them here.
- **Habits**: today's ticks, streaks, and a water bar (+250 ml).
- **Stuck? Pick one.**: on a low-energy day, a few quick items from the stuck menu. Otherwise a row that opens the full screen.
- **Read it later**: the unread count.
- **Jobs**: open applications and the next follow-up date.

The header shows the date and a time-of-day greeting (the name in the greeting is Tom).

### Capture

Three modes, and it reopens on the last one used:

- **Task**: adds to Quick Task, with Today / Tomorrow / a picked date, a time estimate (5, 15, 30, 60 or 120 minutes) and a repeat (daily, weekly, monthly, or a custom rule).
- **Read later**: fetches the page and saves a full article, or the link alone if the fetch fails.
- **Brain dump**: type or dictate (UK speech recognition). **Sort it** sends the text to Claude with the tags already in use, and queues it on the phone until you save. Nothing is written until **Check and save**. You can drop items there. Tasks go to Quick Task, ideas become projects with `type: idea`, and any leftover note goes to the inbox. The model is `claude-haiku-5-5` unless Settings has another id. If Claude rejects that model, Sort it says so and you can paste a current id. Save writes the tasks, the ideas and the note together: a failure removes what that attempt wrote, and if some of it is already in the vault a second Save does not add it again. If the phone is offline the dump stays queued and sorts on the next try. This needs the Claude API key from Settings.

### Tasks

Every open `#task` in the vault: Quick Task first, then the rest. Groups are Overdue, Today, This week (tomorrow included) and Later (undated included). Filter by the tags you use most, by Work (`#work`, `#jobs`) or Personal, by 15 minutes or under, or by search. Tick, reschedule or remove, each with Undo. Today's rows can start a Focus timer. The due-date sheet also sets or clears the `⏱` estimate.

### Read

The Read It Later inbox as Unread, Read and Archived, with search. Swipe right to mark read or unread, left to archive (long-press for the same menu). Articles open in a reader with a progress bar, Aa for text size and serif or sans, and the place you got to is remembered. Long-press a paragraph to highlight it; highlights are kept as `> ` quotes under `## Highlights`. Leaving an unread article asks whether you have finished it (Mark read, Archive or Keep unread). No `status` counts as unread. Archive moves the note, with Undo.

### Projects

Folder notes under the projects folder, grouped Active, Paused, Ideas, and Done (folded). Sort by last updated or A–Z, and search. Status (`active`, `paused`, `idea`, `done`; `shelved` counts as done), Work or Personal, and type (`claude`, `concept`, `idea`) are the note's frontmatter. A project opens onto its summary, its next checkbox, a newest-first log (text and photos, camera or gallery; unsent text is kept as a draft), and, when the note has them, Changelog, Decisions and Links. A `github.com/owner/repo` link in the note, together with the GitHub token from Settings, shows the last push, open pull requests and the latest release.

### Screens opened from Deck or Settings

- **Focus**: a countdown in a notification. Pause, then finish. Finishing writes the session to the focus log and can tick the task it was started from.
- **Stuck? Pick one.**: Quick, Medium and Long from the stuck menu. Start runs that item as a focus timer. Add your own lines; they are written into the note.
- **Habits**: the full water counter, streaks and this week's dots. The habit names live in the note.
- **Weekly review**: the last seven days: tasks done, overdue tasks to move to next Monday, one long-overdue task to drop, and next week's tasks and bills. Also opened from the Sunday reminder.
- **Jobs**: open applications by follow-up date, closed ones (Rejected, Withdrawn) underneath. Add a job, or change status and the next date. Statuses are Shortlisted, Applied, Interview, Offer, Rejected and Withdrawn.
- **Search** (the magnifying glass on Deck): find any note by name or folder, then add a task or a plain line to it.

### Share sheet

The app is in the Android share sheet. A shared link, or a link with only a short caption, is fetched and saved to Read It Later, then the app closes. Any other shared text (a note, a reply, a paragraph) is appended to the Capture box and opened on Brain dump, ready for Sort it.

### Widgets

Five home-screen widgets:

- **Today's tasks**: overdue and today, ticked from the widget.
- **Read It Later**: unread count and the newest unread article; tap to open it in the reader.
- **Tasks & Read It Later**: those two side by side.
- **Quick capture**: add a task or save a link in a small sheet, without opening the app.
- **Capture**: opens the Capture tab.

### Reminders

Scheduled with WorkManager (they survive a reboot, and may fire a few minutes late). Each has Snooze 1h. Defaults: morning briefing 08:00 (opens Deck), evening wrap-up 21:00 (opens Tasks), task due alerts off until switched on (09:00, and only when something is due), weekly review Sunday 19:00 (opens the review).

## Windows

Compose Desktop, installed per user from an MSI. The window is titled Vault Capture. Closing it leaves the app in the system tray. A second launch brings the running window forward. Start with Windows is a Settings switch.

The sidebar is Tasks, Ideas, Read later, Find a note (Ctrl+K) and Settings, plus a Capture button (Ctrl+N). Ctrl+R re-reads the vault.

- **Tasks**: the same vault-wide `#task` list, grouped Overdue, Today, Tomorrow, This week, Later and No date, or in note order. Add a task in the box; a typed date such as `fri` or `25 oct` is taken as the due date. Tick, reschedule or remove, with Undo.
- **Ideas**: the same project notes: summary, status, newest-first log, and photos from a file, a drop or the clipboard.
- **Read later**: the inbox beside the reader. J and K move through the list, E archives, M marks read. Hover a paragraph and click to highlight it. Reading position is remembered.
- **Find a note**: search, then add a task or a line.
- **Quick capture**: a small always-on-top window from the global shortcut (Ctrl+Shift+Space by default; the other choices are in Settings). A link on the clipboard opens it in link mode, with a preview of the page. Escape or a click away dismisses it. Windows keeps Win+Space for switching keyboard language, so the shortcuts leave the Win key alone.

Deck, Sort it, the calendar, weather, habits, jobs and the focus timer are on the phone. Both apps write the same notes, so a task captured on Windows shows up on Deck.

## Setup

1. Install the Android app or the Windows MSI (or run either from a debug build).
2. Open Settings and pick the vault root, the folder that contains `.obsidian`. On Android the gear is on Deck. Until a vault is picked, Deck shows a banner and capture tells you to pick one.
3. Optional, Android only, all on the Settings screen:
   - **Claude API key** (`sk-ant-…`) for Sort it. Stored on the phone, encrypted with the Android Keystore.
   - **Sort it model**, the Claude model id. Leave it on `claude-haiku-5-5` unless that model is retired.
   - **GitHub token** (`github_pat_…`) for the activity line on a project, stored the same way. Read access to the linked repos is enough.
   - **Proton Calendar.** In Proton Calendar on the web: Settings › Share › Share with anyone, Full view, then paste the `.ics` link. Name it and pick a colour. Links are saved in `99 - Meta/capture-config.json` and fetched read-only. Deck keeps the next seven days and refreshes every 30 minutes while it is open.
   - **Reminders**, the times above.
   - **Cards on home**, **Weekly review** and **Habits** open those screens.
   - **Notes used** shows the projects folder, the images folder, and the fixed paths.
4. Allow notifications if you want reminders, coarse location if you want weather, and the microphone if you want dictation.
5. On Windows, Settings also holds the quick-capture shortcut, start with Windows, the recurring-task switch, and the projects and images folders (the same defaults as the phone).

## Building and installing

Open this folder in Android Studio, let it sync, then Run on a phone, or build the APK. Use a JDK from 17 to 26 to run Gradle (CI uses 21). Java 25 is fine. The Android app is only part of the Gradle build when `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties` is set, so a machine that only builds the desktop app can do so without the Android SDK.

```
./gradlew :core:test testDebugUnitTest   # unit tests
./gradlew assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
./gradlew :desktop:packageMsi             # Windows MSI (run on Windows)
```

Debug builds are signed with the committed `app/debug.keystore` (store and key password `android`, alias `androiddebugkey`), so a build from any machine or from CI installs over any other. The version both apps show is `captureVersion` in `gradle.properties` (currently 1.7). The MSI version is that plus `.0`, because an MSI version needs three numbers. Bump `versionCode` in `app/build.gradle.kts` for an Android release as well.

The unit tests cover the note formats the app reads and writes (tasks and recurrence, project notes, Read It Later, habits, jobs, sorting, the calendar, weather and the weekly review), so a change that would mangle a note fails here first.

GitHub Actions (`.github/workflows/build.yml`) runs the tests and builds a debug APK on Ubuntu, then the MSI on Windows, for pull requests, pushes to main, and version tags. Both files are uploaded as workflow artefacts and kept for 7 days (a failed test report is kept for 3 days). The APK is named `VaultCapture-<ref>-<sha>.apk`. Pushing a tag such as `v1.7` also creates a GitHub Release titled `Vault Capture v1.7`, with the APK and the MSI attached (`VaultCapture-v1.7.apk` and `VaultCapture-v1.7.msi`). If that release already exists, the files are replaced on it. Run the workflow by hand with a release tag to rebuild and re-attach. Clean up artifacts (`.github/workflows/cleanup-artifacts.yml`) deletes older artefacts every week, and can be run by hand.

## Repo layout

- `core/`: the vault, the note formats and the edits, shared by both apps. Plain Kotlin, and where the unit tests live.
- `app/`: the Android app (Jetpack Compose). Package `uk.tomlowndes.capture`.
- `desktop/`: the Windows app (Compose Desktop): window, tray, global hotkey, MSI packaging.
- `design/`: HTML mockups of the phone and desktop UI. `render.sh`, `render2.sh` and `render-desktop.sh` re-render the PNGs.
