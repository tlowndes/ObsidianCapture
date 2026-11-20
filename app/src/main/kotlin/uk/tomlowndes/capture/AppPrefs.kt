package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri

/** Typed wrapper over the app's SharedPreferences. Cheap to construct anywhere. */
class AppPrefs(ctx: Context) : VaultPrefs {
    private val p = ctx.applicationContext.getSharedPreferences("capture", Context.MODE_PRIVATE)

    var vault: Uri?
        get() = p.getString("vault", null)?.let(Uri::parse)
        set(v) { p.edit().putString("vault", v?.toString()).apply() }

    /** Tasks tab: group by due date (true) or keep the note's own order (false). */
    var sortByDue: Boolean
        get() = p.getBoolean("sort_by_due", true)
        set(v) { p.edit().putBoolean("sort_by_due", v).apply() }

    /** Ideas tab: last updated first (true) or by title A–Z (false). */
    var ideasByUpdated: Boolean
        get() = p.getBoolean("ideas_by_updated", true)
        set(v) { p.edit().putBoolean("ideas_by_updated", v).apply() }

    /** Tasks tab: the tag and side filters last used, so they stay on between visits. */
    var tasksTag: String?
        get() = p.getString("tasks_tag", null)
        set(v) { p.edit().putString("tasks_tag", v).apply() }
    var tasksFilter: String?
        get() = p.getString("tasks_filter", null)
        set(v) { p.edit().putString("tasks_filter", v).apply() }
    /** Quiet projects the user chose to Keep, so the Vault's "Archive?" doesn't ask again. */
    var paraKept: Set<String>
        get() = p.getString("para_kept", "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()
        set(v) { p.edit().putString("para_kept", v.joinToString("\n")).apply() }
    /** The month ("2026-11") the last Bullet Journal migration was done for. */
    var migratedMonth: String?
        get() = p.getString("migrated_month", null)
        set(v) { p.edit().putString("migrated_month", v).apply() }
    /** Tasks tab shows Upcoming (tasks + calendar, 7 days) instead of the List. */
    /** Tasks list order (priority-flags-v1): DUE (day groups, flagged first) or PRIORITY (priority groups). */
    var tasksSort: String?
        get() = p.getString("tasks_sort", null)
        set(v) { p.edit().putString("tasks_sort", v).apply() }
    var tasksUpcoming: Boolean
        get() = p.getBoolean("tasks_upcoming", false)
        set(v) { p.edit().putBoolean("tasks_upcoming", v).apply() }
    /** Upcoming shows today as a timeline (Visual day) instead of the 7-day list. */
    var upcomingDay: Boolean
        get() = p.getBoolean("upcoming_day", false)
        set(v) { p.edit().putBoolean("upcoming_day", v).apply() }
    /** New events also go to Proton Calendar as an emailed invite (events-to-proton-v1): the switch's starting position. */
    var eventsToProton: Boolean
        get() = p.getBoolean("events_to_proton", true)
        set(v) { p.edit().putBoolean("events_to_proton", v).apply() }
    /** Tasks shows Chores by room (chores-rooms-v1). */
    var tasksChores: Boolean
        get() = p.getBoolean("tasks_chores", false)
        set(v) { p.edit().putBoolean("tasks_chores", v).apply() }
    /** Upcoming's mode (calendar-view-v1): LIST, DAY, WEEK or MONTH. Null falls back to [upcomingDay]. */
    var upcomingMode: String?
        get() = p.getString("upcoming_mode", null)
        set(v) { p.edit().putString("upcoming_mode", v).apply() }
    /** The day Plan today was done ("2026-11-10"), and the day Shut down was. */
    var plannedOn: String?
        get() = p.getString("planned_on", null)
        set(v) { p.edit().putString("planned_on", v).apply() }
    var shutDownOn: String?
        get() = p.getString("shut_down_on", null)
        set(v) { p.edit().putString("shut_down_on", v).apply() }
    /** The day the evening check-in was swiped off the Deck; it asks again tomorrow. */
    var journalSkippedOn: String?
        get() = p.getString("journal_skipped_on", null)
        set(v) { p.edit().putString("journal_skipped_on", v).apply() }
    /** The day the Today card's "✓ N earlier" row was left open; it closes again tomorrow. */
    var earlierOpenOn: String?
        get() = p.getString("earlier_open_on", null)
        set(v) { p.edit().putString("earlier_open_on", v).apply() }
    /** Daily highlights: passages already kept or skipped (so they don't come back), and today's picks. */
    var highlightsSeen: Set<String>
        get() = p.getString("highlights_seen", "").orEmpty().split("\u0001").filter { it.isNotEmpty() }.toSet()
        set(v) { p.edit().putString("highlights_seen", v.toList().takeLast(500).joinToString("\u0001")).apply() }
    /** Subtasks pop out under their task in place (true) or in a sheet (false). */
    /** Quick Task guard (quick-task-guard-v1): keep copies of Quick Task and flag odd changes. */
    var quickGuard: Boolean
        get() = p.getBoolean("quick_guard", true)
        set(v) { p.edit().putBoolean("quick_guard", v).apply() }
    var subtasksInline: Boolean
        get() = p.getBoolean("subtasks_inline", true)
        set(v) { p.edit().putBoolean("subtasks_inline", v).apply() }
    /** Swipe a task right to tick, left to reschedule or remove. */
    var taskSwipe: Boolean
        get() = p.getBoolean("task_swipe", true)
        set(v) { p.edit().putBoolean("task_swipe", v).apply() }
    /** What each swipe does (multiselect-swipes-v1); defaults are the old fixed ones. */
    var swipeRight: SwipeChoice
        get() = SwipeChoice.of(p.getString("swipe_right", null), SwipeChoice.DONE)
        set(v) { p.edit().putString("swipe_right", v.name).apply() }
    var swipeLeft: SwipeChoice
        get() = SwipeChoice.of(p.getString("swipe_left", null), SwipeChoice.RESCHEDULE)
        set(v) { p.edit().putString("swipe_left", v.name).apply() }
    var swipeLongLeft: SwipeChoice
        get() = SwipeChoice.of(p.getString("swipe_long_left", null), SwipeChoice.REMOVE)
        set(v) { p.edit().putString("swipe_long_left", v.name).apply() }

    /** Ticking a 🔁 task also creates its next occurrence. */
    var recurring: Boolean
        get() = p.getBoolean("recurring", true)
        set(v) { p.edit().putBoolean("recurring", v).apply() }

    override var ideasDir: String
        get() = p.getString("ideas_dir", "03 - Projects")!!
        set(v) { p.edit().putString("ideas_dir", v.trim().trim('/')).apply() }

    override var imagesDir: String
        get() = p.getString("images_dir", "99 - Meta/Assets")!!
        set(v) { p.edit().putString("images_dir", v.trim().trim('/')).apply() }

    /** The morning and evening reminders on the Tasks tab. */
    var morningOn: Boolean
        get() = p.getBoolean("morning_on", true)
        set(v) { p.edit().putBoolean("morning_on", v).apply() }
    var morningMinute: Int
        get() = p.getInt("morning_min", 8 * 60)
        set(v) { p.edit().putInt("morning_min", v).apply() }
    var eveningOn: Boolean
        get() = p.getBoolean("evening_on", true)
        set(v) { p.edit().putBoolean("evening_on", v).apply() }
    var eveningMinute: Int
        get() = p.getInt("evening_min", 21 * 60)
        set(v) { p.edit().putInt("evening_min", v).apply() }

    /** In-app updater: check GitHub for a new release when the app opens. */
    var updateChecks: Boolean
        get() = p.getBoolean("update_checks", true)
        set(v) { p.edit().putBoolean("update_checks", v).apply() }

    /** The release tag Later was pressed on; its banner stays hidden until a newer one comes out. */
    var updateSkip: String?
        get() = p.getString("update_skip", null)
        set(v) { p.edit().putString("update_skip", v).apply() }

    /** When GitHub was last asked, and the latest tag it gave. */
    var updateCheckedAt: Long
        get() = p.getLong("update_checked_at", 0)
        set(v) { p.edit().putLong("update_checked_at", v).apply() }
    var updateLatest: String?
        get() = p.getString("update_latest", null)
        set(v) { p.edit().putString("update_latest", v).apply() }

    /** Hangar's tools in order ("didi,launch"); null until Hangar is first set up. */
    var hangarTools: String?
        get() = p.getString("hangar_tools", null)
        set(v) { p.edit().putString("hangar_tools", v).apply() }

    /** The Did I? check the Quick Settings tile stamps; blank means the first check. */
    var didTile: String
        get() = p.getString("did_tile", "Door") ?: "Door"
        set(v) { p.edit().putString("did_tile", v).apply() }

    /** Flight Deck energy chip. Medium unless set today, so it resets each morning. */
    var energy: Energy
        get() = if (p.getString("energy_day", null) == java.time.LocalDate.now().toString())
            Energy.values().firstOrNull { it.name == p.getString("energy", null) } ?: Energy.MED
        else Energy.MED
        set(v) { p.edit().putString("energy", v.name).putString("energy_day", java.time.LocalDate.now().toString()).apply() }

    /** Energy reshuffle (energy-reshuffle-v1): actions on a 100% day, set in Settings › Tasks. */
    var reshuffleUsual: Int
        get() = p.getInt("reshuffle_usual", Reshuffle.USUAL)
        set(v) { p.edit().putInt("reshuffle_usual", v.coerceIn(1, 30)).apply() }
    /** Today's reshuffle level, once one is saved. Null on other days, so it resets each morning like [energy]. */
    var reshuffleLevel: Reshuffle.Level?
        get() = if (p.getString("reshuffle_day", null) == java.time.LocalDate.now().toString())
            Reshuffle.Level.values().firstOrNull { it.name == p.getString("reshuffle_level", null) } else null
        set(v) { p.edit().putString("reshuffle_level", v?.name).putString("reshuffle_day", java.time.LocalDate.now().toString()).apply() }

    /** Weather card: Morning / Afternoon / Evening strip, shown after a tap on the card. */
    var weatherStrip: Boolean
        get() = p.getBoolean("weather_strip", false)
        set(v) { p.edit().putBoolean("weather_strip", v).apply() }

    /** Tasks put off with "Later" on Next step (raw lines). Cleared the next day; due dates stay as they are. */
    /** Talk me through it: tasks hidden with "Not today", as TalkThrough keys with their until dates. */
    var talkSnoozed: String?
        get() = p.getString("talk_snoozed", null)
        set(v) { p.edit().putString("talk_snoozed", v).apply() }

    var laterToday: Set<String>
        get() = if (p.getString("later_day", null) == java.time.LocalDate.now().toString())
            p.getStringSet("later", null).orEmpty() else emptySet()
        set(v) { p.edit().putStringSet("later", v).putString("later_day", java.time.LocalDate.now().toString()).apply() }

    /** What Jarvis last sorted, waiting for Check and save (null once saved or discarded). */
    var jarvisFound: SortResult?
        get() = Sorting.fromJson(p.getString("jarvis_found", null))
        set(v) { p.edit().apply { if (v == null) remove("jarvis_found") else putString("jarvis_found", Sorting.toJson(v)) }.remove("capture_queue").remove("sort_model").apply() }

    /** Task due alerts (off until switched on) and the Sunday weekly review. */
    var dueOn: Boolean
        get() = p.getBoolean("due_on", false)
        set(v) { p.edit().putBoolean("due_on", v).apply() }
    var dueMinute: Int
        get() = p.getInt("due_min", 9 * 60)
        set(v) { p.edit().putInt("due_min", v).apply() }
    var weeklyOn: Boolean
        get() = p.getBoolean("weekly_on", true)
        set(v) { p.edit().putBoolean("weekly_on", v).apply() }
    var weeklyMinute: Int
        get() = p.getInt("weekly_min", 19 * 60)
        set(v) { p.edit().putInt("weekly_min", v).apply() }

    /** Capture's switch: 0 Task, 1 Read later, 2 Jarvis. Opens on the last one used. */
    var captureMode: Int
        get() = p.getInt("capture_mode", 0)
        set(v) { p.edit().putInt("capture_mode", v).apply() }

    var askedNotif: Boolean
        get() = p.getBoolean("asked_notif", false)
        set(v) { p.edit().putBoolean("asked_notif", v).apply() }

    /** Vault tab: show 99 folders, sort folders A–Z instead of newest first, and the last few searches. */
    var vaultShowHidden: Boolean
        get() = p.getBoolean("vault_show_hidden", false)
        set(v) { p.edit().putBoolean("vault_show_hidden", v).apply() }
    var vaultByName: Boolean
        get() = p.getBoolean("vault_by_name", false)
        set(v) { p.edit().putBoolean("vault_by_name", v).apply() }
    var vaultSearches: List<String>
        get() = p.getString("vault_searches", "").orEmpty().split("\u0001").filter { it.isNotBlank() }
        set(v) { p.edit().putString("vault_searches", v.joinToString("\u0001")).apply() }

    /** Reader text size in sp, and serif (true) or sans body text. */
    var readerSize: Float
        get() = p.getFloat("reader_size", 16.5f)
        set(v) { p.edit().putFloat("reader_size", v).apply() }
    var readerSerif: Boolean
        get() = p.getBoolean("reader_serif", false)
        set(v) { p.edit().putBoolean("reader_serif", v).apply() }

    /** Where you got to in an article (pixels), keyed by file name; 0 clears it. */
    fun scrollPos(file: String): Int = p.getInt("scroll_$file", 0)
    fun setScrollPos(file: String, y: Int) {
        p.edit().apply { if (y <= 0) remove("scroll_$file") else putInt("scroll_$file", y) }.apply()
    }

    /** Settings › Vault › Server fallback: read and save through the Jarvis server while this phone's sync is behind. */
    var serverFallback: Boolean
        get() = p.getBoolean("server_fallback", false)
        set(v) { p.edit().putBoolean("server_fallback", v).apply() }
    /** How far behind (minutes) the phone must be before the server copy is used. */
    var fallbackStaleMin: Int
        get() = p.getInt("fallback_stale_min", 5)
        set(v) { p.edit().putInt("fallback_stale_min", v).apply() }

    /** Piholegotchi (the e-ink screen at home): its address on the home Wi-Fi, blank for off. */
    var piholegotchiAddress: String
        get() = p.getString("piholegotchi_address", "").orEmpty()
        set(v) { p.edit().putString("piholegotchi_address", v.trim()).apply() }

    /**
     * The assistant's name, shown and spoken in the app. Blank becomes Tower.
     * Kept separate from the jarvis_* keys so an update does not drop the address, token or chats.
     */
    var assistantName: String
        get() = AssistantName.clean(p.getString("assistant_name", null))
        set(v) { p.edit().putString("assistant_name", AssistantName.clean(v)).apply() }

    /** Jarvis voice tab. The token is in [SecretStore], never here. "demo" as the address needs no server. */
    var jarvisAddress: String
        get() = p.getString("jarvis_address", "").orEmpty()
        set(v) { p.edit().putString("jarvis_address", v.trim()).apply() }
    var jarvisWorker: String
        get() = p.getString("jarvis_worker", "jarvis") ?: "jarvis"
        set(v) { p.edit().putString("jarvis_worker", v).apply() }
    var jarvisSpeak: Boolean
        get() = p.getBoolean("jarvis_speak", true)
        set(v) { p.edit().putBoolean("jarvis_speak", v).apply() }
    /** Voice id sent with each recording ("" = the server's default) and its label for Settings. */
    var jarvisVoice: String
        get() = p.getString("jarvis_voice", "").orEmpty()
        set(v) { p.edit().putString("jarvis_voice", v).apply() }
    var jarvisVoiceLabel: String
        get() = p.getString("jarvis_voice_label", "").orEmpty()
        set(v) { p.edit().putString("jarvis_voice_label", v).apply() }
    /** Reply playback speed, done on the phone. */
    var jarvisSpeed: Float
        get() = p.getFloat("jarvis_speed", 1f)
        set(v) { p.edit().putFloat("jarvis_speed", v).apply() }
    /** Jarvis thinks on the phone (Claude direct) when a Claude key is set; off sends everything to the box. */
    /** Sleep and steps (body-people-errands-v1): read from Health Connect, step goal, and easy day after a short night. */
    var healthOn: Boolean
        get() = p.getBoolean("health_on", false)
        set(v) { p.edit().putBoolean("health_on", v).apply() }
    var stepGoal: Int
        get() = p.getInt("step_goal", 7000)
        set(v) { p.edit().putInt("step_goal", v).apply() }
    var easyAfterShort: Boolean
        get() = p.getBoolean("easy_after_short", true)
        set(v) { p.edit().putBoolean("easy_after_short", v).apply() }
    /** What helps you: the month whose Deck card was closed with ✕. */
    var helpsClosed: String?
        get() = p.getString("helps_closed", null)
        set(v) { p.edit().putString("helps_closed", v).apply() }

    /** People: the day the birthday gift tasks were last checked. */
    var giftsCheckedOn: String?
        get() = p.getString("gifts_checked_on", null)
        set(v) { p.edit().putString("gifts_checked_on", v).apply() }

    var phoneBrain: Boolean
        get() = p.getBoolean("phone_brain", false)
        set(v) { p.edit().putBoolean("phone_brain", v).apply() }
    /** Settings › Jarvis › Model: false = Fast (Haiku), true = Clever (Sonnet). */
    /** Gemini Nano answers quick asks on the phone (mockup nano-brain-v1). */
    var nanoBrain: Boolean
        get() = p.getBoolean("nano_brain", true)
        set(v) { p.edit().putBoolean("nano_brain", v).apply() }
    /** Vault, research, coding and long rambles go to Claude on the box rather than Nano. */
    var nanoToBox: Boolean
        get() = p.getBoolean("nano_to_box", true)
        set(v) { p.edit().putBoolean("nano_to_box", v).apply() }
    var brainClever: Boolean
        get() = p.getBoolean("brain_clever", false)
        set(v) { p.edit().putBoolean("brain_clever", v).apply() }
    /** Android's own speech-to-text instead of recording for Whisper on the box. */
    var phoneSpeech: Boolean
        get() = p.getBoolean("phone_speech", true)
        set(v) { p.edit().putBoolean("phone_speech", v).apply() }
    /** Android's own voice for replies instead of Piper on the box. */
    var phoneVoice: Boolean
        get() = p.getBoolean("phone_voice", true)
        set(v) { p.edit().putBoolean("phone_voice", v).apply() }
    /** What the phone brain has cost this month (kept on the phone only). */
    var brainSpend: PhoneBrain.Spend
        get() = PhoneBrain.Spend(p.getString("brain_month", "").orEmpty(), p.getLong("brain_micro_usd", 0), p.getInt("brain_answers", 0))
        set(v) { p.edit().putString("brain_month", v.month).putLong("brain_micro_usd", v.microUsd).putInt("brain_answers", v.answers).apply() }
    /** This month's Claude spend by feature (ClaudeCap ledger). */
    var spendByFeature: String
        get() = p.getString("spend_by_feature", "").orEmpty()
        set(v) { p.edit().putString("spend_by_feature", v).apply() }
    /** Records a Claude answer's cost under [feature] and keeps the cap gate fresh. */
    fun spend(feature: String, usd: Double) {
        brainSpend = brainSpend.add(usd)
        spendByFeature = ClaudeCap.added(spendByFeature, feature, usd)
        ClaudeCap.ownUsd = ClaudeCap.ledger(spendByFeature).values.sum() / 1e6
    }
    /** Finished-job ids already shown or dismissed, so the Done line doesn't come back. Keeps the last 50. */
    var jarvisSeenDone: List<String>
        get() = p.getString("jarvis_seen_done", "").orEmpty().split("\u0001").filter { it.isNotBlank() }
        set(v) { p.edit().putString("jarvis_seen_done", v.takeLast(50).joinToString("\u0001")).apply() }
    /** The chat with [worker]: its id (sent as "chat") and the messages kept on the phone. */
    fun jarvisChat(worker: String): Jarvis.Chat = Jarvis.chatFromJson(p.getString("jarvis_chat_$worker", null))

    fun setJarvisChat(worker: String, chat: Jarvis.Chat) {
        p.edit().putString("jarvis_chat_$worker", Jarvis.chatToJson(chat))
            .remove("jarvis_last_heard").remove("jarvis_last_reply").remove("jarvis_last_at").apply()
    }

    fun draft(key: String): String = p.getString("draft_$key", "") ?: ""
    fun setDraft(key: String, text: String) {
        p.edit().apply { if (text.isEmpty()) remove("draft_$key") else putString("draft_$key", text) }.apply()
    }

    /**
     * Tasks the app ticked: done line -> (when, the repeat's follow-up line or ""). Orders Completed and lets
     * un-tick remove the follow-up. Keeps the last 30.
     */
    var tickLog: List<Triple<String, Long, String>>
        get() = p.getString("tick_log", "").orEmpty().split("\u0002").filter { it.isNotBlank() }.mapNotNull {
            val f = it.split("\u0001")
            if (f.size == 3) Triple(f[0], f[1].toLongOrNull() ?: 0L, f[2]) else null
        }
        set(v) {
            p.edit().putString("tick_log", v.takeLast(30).joinToString("\u0002") { "${it.first}\u0001${it.second}\u0001${it.third}" }).apply()
        }

    fun logTick(doneLine: String, nextLine: String?) {
        tickLog = tickLog.filter { it.first != doneLine } + Triple(doneLine, System.currentTimeMillis(), nextLine.orEmpty())
    }
}
