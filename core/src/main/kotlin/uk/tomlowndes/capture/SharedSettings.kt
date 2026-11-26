package uk.tomlowndes.capture

/** Where a device keeps its settings: Android's SharedPreferences, or the Windows registry. Null removes a key. */
interface KeyStore {
    fun string(key: String): String?
    fun putString(key: String, value: String?)
    fun bool(key: String, def: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
    fun int(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun long(key: String, def: Long): Long
    fun putLong(key: String, value: Long)
    fun float(key: String, def: Float): Float
    fun putFloat(key: String, value: Float)
}

/**
 * The settings the phone and Windows apps both have, with the same keys and rules, written once. Each app's own
 * settings class extends this and adds what only it has. Settings stay on each device; the keys are the ones each
 * app already used, so nothing resets. [readerSizeDefault], [readerSerifDefault] and [jarvisSpeakDefault] differ
 * by screen.
 */
abstract class SharedSettings(
    private val s: KeyStore,
    private val readerSizeDefault: Float,
    private val readerSerifDefault: Boolean,
    private val jarvisSpeakDefault: Boolean,
) : VaultPrefs {
    // ---- tasks

    /** Tasks tab: group by due date (true) or keep the note's own order (false). */
    var sortByDue: Boolean
        get() = s.bool("sort_by_due", true)
        set(v) = s.putBool("sort_by_due", v)

    /** Ticking a 🔁 task leaves its next occurrence, like the Tasks plugin. */
    var recurring: Boolean
        get() = s.bool("recurring", true)
        set(v) = s.putBool("recurring", v)

    /** Sub-tasks shown under their parent (true) or opened on their own. */
    var subtasksInline: Boolean
        get() = s.bool("subtasks_inline", true)
        set(v) = s.putBool("subtasks_inline", v)

    /** Quick Task guard (quick-task-guard-v1): keep copies and warn when it looks off. */
    var quickGuard: Boolean
        get() = s.bool("quick_guard", true)
        set(v) = s.putBool("quick_guard", v)

    /** Energy reshuffle (energy-reshuffle-v1): actions that fit on a 100% day. */
    var reshuffleUsual: Int
        get() = s.int("reshuffle_usual", Reshuffle.USUAL)
        set(v) = s.putInt("reshuffle_usual", v.coerceIn(1, 30))

    /** Stuck? snoozes ([TalkThrough]). */
    var talkSnoozed: String?
        get() = s.string("talk_snoozed")
        set(v) = s.putString("talk_snoozed", v)

    // ---- projects and folders

    var ideasByUpdated: Boolean
        get() = s.bool("ideas_by_updated", true)
        set(v) = s.putBool("ideas_by_updated", v)

    override var ideasDir: String
        get() = s.string("ideas_dir") ?: "03 - Projects"
        set(v) = s.putString("ideas_dir", v.trim().trim('/'))

    override var imagesDir: String
        get() = s.string("images_dir") ?: "99 - Meta/Assets"
        set(v) = s.putString("images_dir", v.trim().trim('/'))

    // ---- reading

    var readerSize: Float
        get() = s.float("reader_size", readerSizeDefault)
        set(v) = s.putFloat("reader_size", v)

    var readerSerif: Boolean
        get() = s.bool("reader_serif", readerSerifDefault)
        set(v) = s.putBool("reader_serif", v)

    var findingsLength: KeyFindings.Length
        get() = KeyFindings.Length.fromStored(s.string("findings_length"))
        set(v) = s.putString("findings_length", v.stored)

    // ---- daily brief, habits, weather

    var briefFull: Boolean
        get() = s.bool("brief_full", true)
        set(v) = s.putBool("brief_full", v)

    var briefSpeed: Float
        get() = s.float("brief_speed", 1f)
        set(v) = s.putFloat("brief_speed", v)

    /** Habit helpers closed today ([Patterns]). */
    var helpsClosed: String?
        get() = s.string("helps_closed")
        set(v) = s.putString("helps_closed", v)

    var stepGoal: Int
        get() = s.int("step_goal", 7000)
        set(v) = s.putInt("step_goal", v)

    var weatherStrip: Boolean
        get() = s.bool("weather_strip", false)
        set(v) = s.putBool("weather_strip", v)

    // ---- Jarvis, Claude and spend

    var assistantName: String
        get() = AssistantName.clean(s.string("assistant_name"))
        set(v) = s.putString("assistant_name", AssistantName.clean(v))

    var jarvisAddress: String
        get() = s.string("jarvis_address").orEmpty()
        set(v) = s.putString("jarvis_address", v.trim().ifEmpty { null })

    var jarvisWorker: String
        get() = s.string("jarvis_worker") ?: "jarvis"
        set(v) = s.putString("jarvis_worker", v)

    var jarvisSpeak: Boolean
        get() = s.bool("jarvis_speak", jarvisSpeakDefault)
        set(v) = s.putBool("jarvis_speak", v)

    var jarvisSpeed: Float
        get() = s.float("jarvis_speed", 1f)
        set(v) = s.putFloat("jarvis_speed", v)

    var piholegotchiAddress: String
        get() = s.string("piholegotchi_address").orEmpty()
        set(v) = s.putString("piholegotchi_address", v.trim().ifEmpty { null })

    /** Claude's better model for harder asks. */
    var brainClever: Boolean
        get() = s.bool("brain_clever", false)
        set(v) = s.putBool("brain_clever", v)

    /** This month's Claude answers from this device. */
    var brainSpend: PhoneBrain.Spend
        get() = PhoneBrain.Spend(s.string("brain_month").orEmpty(), s.long("brain_micro_usd", 0), s.int("brain_answers", 0))
        set(v) { s.putString("brain_month", v.month); s.putLong("brain_micro_usd", v.microUsd); s.putInt("brain_answers", v.answers) }

    /** This month's Claude spend by feature (ClaudeCap ledger). */
    var spendByFeature: String
        get() = s.string("spend_by_feature").orEmpty()
        set(v) = s.putString("spend_by_feature", v)

    /** Records a Claude answer's cost under [feature] and keeps the cap gate fresh. */
    fun spend(feature: String, usd: Double) {
        brainSpend = brainSpend.add(usd)
        spendByFeature = ClaudeCap.added(spendByFeature, feature, usd)
        ClaudeCap.ownUsd = ClaudeCap.ledger(spendByFeature).values.sum() / 1e6
    }

    // ---- updates

    var updateChecks: Boolean
        get() = s.bool("update_checks", true)
        set(v) = s.putBool("update_checks", v)

    var updateSkip: String?
        get() = s.string("update_skip")
        set(v) = s.putString("update_skip", v)

    var updateCheckedAt: Long
        get() = s.long("update_checked_at", 0)
        set(v) = s.putLong("update_checked_at", v)

    var updateLatest: String?
        get() = s.string("update_latest")
        set(v) = s.putString("update_latest", v)
}
