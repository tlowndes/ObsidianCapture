# Flight Deck

Flight Deck (v1.7) is a personal capture and daily-planning app that writes straight into an Obsidian vault. The notes stay ordinary markdown, so Obsidian, and whatever syncs the vault, remain the source of truth. It needs no Obsidian plugin.

The phone app is labelled **Flight Deck**. It is the daily home screen: what to do next, what is due, what to read, and the small loops (focus, habits, weather, calendar, jobs) that otherwise live in separate apps. A Windows desktop app in `desktop/` (window title **Flight Deck**) shares the same vault, with its own Deck, tasks, projects, reading and quick capture.

## Writing into the vault

On Android, pick the vault root (the folder that contains `.obsidian`) with the system folder picker. The app keeps a persistent read/write grant. On Windows, pick the same folder in Settings; the app reads and writes it as a normal directory.

Defaults below match a vault laid out as `01 - Inbox`, `03 - Projects` and `99 - Meta`. The projects folder and the images folder can be changed in Settings. Every other path is fixed.

| What | Where | Format |
| --- | --- | --- |
| Quick tasks | `01 - Inbox/⚡ Quick Task.md` | Open tasks under `## Active`, done ones under `## Archive`. A task is `- [ ] #task …`, with optional `📅 YYYY-MM-DD`, `⏱ 15m` and `🔁 every week` (Tasks-plugin markers). |
| Tasks in other notes | Any other note in the vault | The same `- [ ] … #task` line. Ticked in place. A task added from search goes under the note's `## Tasks`, or at the end if that heading is missing. A plain line goes at the end. |
| Read It Later | `01 - Inbox/ReadItLater Inbox/` | One note per page, in the same shape as the Obsidian read-it-later plugin: frontmatter `status` (`unread`, `read` or `archived`) and `saved`, then `[[ReadItLater]]` and the article (or just the link if the page could not be fetched). YouTube links are saved as video notes. Archive moves the file to `ReadItLater Inbox/Archive/`. |
| Jarvis notes | `01 - Inbox/<Title>.md`, or `01 - Inbox/Capture <date>.md` | A long ramble tidied into one note (frontmatter `created`, `source: jarvis`, then the note, then your own words folded in a `> [!quote]- Transcript` callout). A short leftover line goes to `Capture <date>.md`. |
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

Four tabs: **Deck**, **Capture**, **Tasks**, **Vault**. Read later and Projects open from chips at the top of Vault. The gear and the search icon are on Deck. Minimum Android 10.

### Deck

The home screen. A Low / Med / High energy chip (it resets to Med each morning) changes the day: Low moves **Stuck? Pick one.** to the top and prefers tasks of 15 minutes or less; High shows three top tasks instead of two. Pills along the top open Tasks (how many are due today or overdue), Focus (minutes logged today) and Habits.

Cards, in an order you can rearrange, and any of which you can hide, under Settings › Cards on home:

- **Next step**: the earliest dated task that is not a bill and was not put off with Later today. Start opens Focus. Later hides it until tomorrow.
- **Weather & travel**: today's forecast from Open-Meteo, using the phone's coarse location (no API key, cached for an hour). Tap for a Morning / Afternoon / Evening strip. If today's next timed Proton event has a place, a drive time from OpenStreetMap and OSRM is shown, with a leave-by time.
- **Today**: today's events from the Proton calendars linked in Settings. The one happening now is highlighted. Hidden when there is nothing on.
- **Bills**: `#bill` tasks due in the next seven days, with a `£` amount when the line has one. Marking one paid ticks the task.
- **Projects**: the active project, when there is one. Opens Projects.
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
- **Jarvis**: hold to talk or type to the Oracle server (below). Ask anything, or ramble: when the server sorts a ramble it sends back tasks, ideas and a tidied note, and a **Check and save** line appears. Nothing is written until you save. You can drop items or edit the note there. Tasks go to Quick Task, ideas become projects with `type: idea`, and the note goes to the inbox. Save writes them together: a failure removes what that attempt wrote, and if some of it is already in the vault a second Save does not add it again.

### Jarvis

Hold the big button to talk, let go to send (slide off to cancel). The app records OGG/Opus and posts it, with the
worker picked top right (Jarvis, Coding, Obsidian, Research, Odd jobs) and a bearer token, to `POST /voice` on the
Oracle box over Tailscale. That server hands it to the Claude CLI and answers with what it heard, the reply and an
`audio_url`, which the app fetches with the same token and plays. A Done line shows when `GET /done` reports a
finished background job. Address and token are in Settings › Jarvis; the token is kept in Android secure storage,
never in the vault or the repo. Type `demo` as the address to try it with no server (a recording over 15 seconds,
or typed text of 25 words or more, comes back with a demo note to check). Typed messages go to `POST /text` as JSON
`{"text", "worker", "voice"}` and get the same reply. Either reply may carry `"sorted": {"tasks", "ideas", "note"}`,
where `note` is a line or `{"title", "markdown"}`.

To test against something real before the Oracle side is ready, run the fake server on a PC that's on the tailnet:
`python tools/fake-jarvis/fake_jarvis.py --token test-token`, then `tailscale serve --bg 8765`, and put that PC's
`*.ts.net` name and `test-token` into Settings › Jarvis. It plays your own recording back.

The real server for the Oracle box is in `tools/jarvis-server` (setup steps in its README).

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
- **Daily Slip** (the receipt on Deck): today's printout for a Bluetooth pocket thermal printer (built for the Phomemo M02 Pro, 48 mm at 300 dpi; any BLE printer that takes ESC/POS raster should work). It shows the day, the weather, today's Proton events with a month grid, overdue, today's and tomorrow's to-dos with blank lines, and a daily sudoku. Each part has a switch, the preview updates as you go, and the printer is picked once from a scan. Nothing is written to the vault.

### Share sheet

The app is in the Android share sheet. A shared link, or a link with only a short caption, is fetched and saved to Read It Later, then the app closes. Any other shared text (a note, a reply, a paragraph) is put in Capture › Jarvis's type box, ready to send.

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

Compose Desktop, installed per user from an MSI (still named Vault Capture, so it upgrades the installed copy). The window is titled Flight Deck and uses the phone's colours. Closing it leaves the app in the system tray. A second launch brings the running window forward. Start with Windows is a Settings switch.

The sidebar is Deck, Tasks, Projects, Read later, Find a note (Ctrl+K) and Settings, plus a Capture button (Ctrl+N). Ctrl+R re-reads the vault.

- **Tasks**: the same vault-wide `#task` list, grouped Overdue, Today, Tomorrow, This week, Later and No date, or in note order. Add a task in the box; a typed date such as `fri` or `25 oct` is taken as the due date. Tick, reschedule or remove, with Undo.
- **Deck**: the phone's home cards in two columns, in the order and with the cards switched off in `capture-config.json`. Left: Next step (Start runs a focus timer in the card, Finish writes the focus log and asks if it's done; Later hides it until tomorrow), Bills, Top tasks and Habits. Right: Weather (the town from Settings; click for the day strip), Projects and Read it later. Today, Stuck? and Jobs stay on the phone.
- **Projects**: the phone's quiet list (Active, Paused, Ideas, Done folded) beside the open project. The status icon or ⚙ opens a menu for status, Work or Personal, and type. The detail has the tickable next step, the summary, the GitHub line (with the token from Settings), and Log, Changelog, Decisions and Links tabs. Photos come from a file, a drop or the clipboard.
- **Read later**: the inbox beside the reader. J and K move through the list, E archives, M marks read. Hover a paragraph and click to highlight it. Reading position is remembered.
- **Find a note**: search, then add a task or a line.
- **Quick capture**: a small always-on-top window from the global shortcut (Ctrl+Shift+Space by default; the other choices are in Settings). A link on the clipboard opens it in link mode, with a preview of the page. A task can have Today, Tomorrow or a picked date, a time estimate, and a repeat (Daily, Weekly, Monthly, or Custom… in a panel beside it). Escape or a click away dismisses it. Windows keeps Win+Space for switching keyboard language, so the shortcuts leave the Win key alone.

Jarvis, the calendar, jobs and reminders are on the phone. Both apps write the same notes, so a task captured on Windows shows up on Deck.

## Setup

1. Install the Android app or the Windows MSI (or run either from a debug build).
2. Open Settings and pick the vault root, the folder that contains `.obsidian`. On Android the gear is on Deck. Until a vault is picked, Deck shows a banner and capture tells you to pick one.
3. Optional, Android only, all on the Settings screen:
   - **Jarvis** address and token (see Jarvis above). The token is encrypted with the Android Keystore.
   - **GitHub token** (`github_pat_…`) for the activity line on a project, stored the same way. Read access to the linked repos is enough.
   - **Proton Calendar.** In Proton Calendar on the web: Settings › Share › Share with anyone, Full view, then paste the `.ics` link. Name it and pick a colour. Links are saved in `99 - Meta/capture-config.json` and fetched read-only. Deck keeps the next seven days and refreshes every 30 minutes while it is open.
   - **Reminders**, the times above.
   - **Cards on home**, **Weekly review** and **Habits** open those screens.
   - **Notes used** shows the projects folder, the images folder, and the fixed paths.
4. Allow notifications if you want reminders, coarse location if you want weather, and the microphone if you want dictation.
5. On Windows, Settings also holds the quick-capture shortcut, start with Windows, the recurring-task switch, the weather town, a GitHub token (encrypted for your Windows account), and the projects and images folders (the same defaults as the phone).

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
