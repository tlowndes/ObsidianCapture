package uk.tomlowndes.capture

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screens opened from Flight Deck that sit over the tabs. */
enum class Sub { FOCUS, STUCK, HABITS, WEEKLY, JOBS, MIGRATE, SCAN, TRIAGE, PLAN, ROUTINE, PRINT, CHORES, RECIPES, SNAP, LEARN, INBOX, PEOPLE, ERRANDS, MEALS, HANGAR, DID_I, LAUNCH_PAD, HEADS_UP, TICKET_RAIL, NIGHT_PAD, POCKET_PASS, LAST_SEEN, RETURN_RADAR, COOL_OFF, REFILL, WAITING, ERRAND_RUN, SENSORY }

enum class Tab(val label: String, val icon: String) {
    DECK("Deck", "◎"), CAPTURE("Capture", "＋"), TASKS("Tasks", "✓"), READ("Read", "📖"), VAULT("Vault", "🗂"), PROJECTS("Projects", "💡");

    companion object {
        /**
         * The bottom bar. Read later and Projects open from chips in Vault, so they have no button of their own,
         * and Jarvis is a mode inside Capture.
         */
        val NAV = listOf(DECK, CAPTURE, TASKS, VAULT)
    }
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { AppPrefs(this) }

    /** Set by notification and widget taps (a tab, plus the article for Read) and read by [App]. */
    private var openRequest by mutableStateOf<Pair<Tab, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND && sharedImage(intent)) {
            // An image goes to Snap anything (snap-anything-v1).
        } else if (intent?.action == Intent.ACTION_SEND && sharedCool(intent)) {
            // A shop page shared to "Cool-off" goes to Cool-off's add sheet (hangar-phase3).
        } else if (intent?.action == Intent.ACTION_SEND && sharedOrder(intent)) {
            // An order or delivery email goes to Return Radar's add sheet (hangar-phase2).
        } else if (intent?.action == Intent.ACTION_SEND && sharedJob(intent)) {
            // A job ad goes to the Job co-pilot (job-copilot-v1).
        } else if (intent?.action == Intent.ACTION_SEND) {
            if (!toCapture(intent)) { handleShare(intent); return }
        } else readOpenRequest(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // Quiet the day (quiet-day-v1): once per phone, then the reminders and Heads Up are planned again.
        if (!prefs.quietDayDone || !prefs.eveningWeekDone) {
            prefs.quietDayOnce()
            prefs.eveningWeekOnce()
            Reminders.scheduleAll(applicationContext, prefs)
            Thread { runCatching { HeadsAlarms.reschedule(applicationContext) } }.start()
        }
        // Fold the extra doors (fold-doors-v1): duplicate widgets leave the picker unless one is placed.
        Thread { WidgetFold.apply(applicationContext, prefs) }.start()
        setContent {
            CaptureTheme {
                App(prefs, openRequest) { openRequest = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND && sharedImage(intent)) return
        if (intent.action == Intent.ACTION_SEND && sharedCool(intent)) return
        if (intent.action == Intent.ACTION_SEND && sharedOrder(intent)) return
        if (intent.action == Intent.ACTION_SEND && sharedJob(intent)) return
        if (intent.action == Intent.ACTION_SEND) toCapture(intent) else readOpenRequest(intent)
    }

    /** A page shared to the "Cool-off" share target opens Cool-off's add sheet with the link, name and any price filled in. */
    private fun sharedCool(i: Intent): Boolean {
        if (i.component?.className?.endsWith(".CoolOffShare") != true) return false
        CoolInbox.pending = CoolOff.fromShare(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty(), i.getStringExtra(Intent.EXTRA_SUBJECT), java.time.LocalDateTime.now())
        openRequest = Tab.DECK to "cool"
        return true
    }

    /** A shared order or delivery email opens Return Radar with it filled in, when Return Radar is in Hangar. */
    private fun sharedOrder(i: Intent): Boolean {
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (Hangar.parse(prefs.hangarTools)?.contains(HangarTool.RETURN_RADAR) != true || !ReturnMail.looksLikeOrder(text)) return false
        ReturnInbox.pending = ReturnMail.guess(text, i.getStringExtra(Intent.EXTRA_SUBJECT))
        openRequest = Tab.DECK to "returns"
        return true
    }

    /** A shared job ad (LinkedIn, Indeed…) opens Jobs with the Save job sheet, when Claude is on. */
    private fun sharedJob(i: Intent): Boolean {
        val url = ShareIntent.urlOf(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        if (url.isBlank() || !JobCopilot.looksLikeJob(url) || claudeKey(this, prefs) == null) return false
        JobInbox.pending = url
        openRequest = Tab.VAULT to "jobs"
        return true
    }

    /** A shared image opens Snap anything with it. Returns false when the share isn't an image. */
    private fun sharedImage(i: Intent): Boolean {
        if (i.type?.startsWith("image/") != true) return false
        @Suppress("DEPRECATION")
        val uri = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            ?: return false
        SnapInbox.pending = uri
        openRequest = Tab.CAPTURE to "snap"
        return true
    }

    /**
     * Shared text that isn't just a link (a Gemini reply, a note) lands in Capture › Jarvis, ready to send.
     * Returns false for a link, which still goes to Read It Later.
     */
    private fun toCapture(i: Intent): Boolean {
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (ShareIntent.isPage(text)) return false
        prefs.setDraft("capture", ShareIntent.captureDraft(prefs.draft("capture"), text))
        prefs.captureMode = 2
        openRequest = Tab.CAPTURE to null
        return true
    }

    private fun readOpenRequest(i: Intent?) {
        if (i?.getStringExtra("tab") == "RAIL") { openRequest = Tab.DECK to "rail"; return }
        if (i?.getStringExtra("tab") == "FOCUS") { openRequest = Tab.DECK to "focus"; return }
        if (i?.getStringExtra("tab") == "WEEKLY") { openRequest = Tab.DECK to "weekly"; return }
        if (i?.getStringExtra("tab") == "PLAN") { openRequest = Tab.DECK to "plan"; return }
        if (i?.getStringExtra("tab") == "ERRANDS") { openRequest = Tab.DECK to "errands"; return }
        // Hangar and Did I? from the app shortcuts, pinned shortcuts and the Did I? widget.
        if (i?.getStringExtra("tab") == "HANGAR") { openRequest = Tab.DECK to "hangar"; return }
        if (i?.getStringExtra("tab") == "DID_I") { openRequest = Tab.DECK to "didi"; return }
        // Launch Pad and Heads Up from their buzzes and pinned shortcuts. HEADS_SWITCH opens the Focus switch view.
        if (i?.getStringExtra("tab") == "LAUNCH_PAD") { openRequest = Tab.DECK to "launch"; return }
        if (i?.getStringExtra("tab") == "HEADS_UP") { openRequest = Tab.DECK to "heads"; return }
        if (i?.getStringExtra("tab") == "NIGHT_PAD") { openRequest = Tab.DECK to "night"; return }
        if (i?.getStringExtra("tab") == "POCKET_PASS") { openRequest = Tab.DECK to "pass"; return }
        if (i?.getStringExtra("tab") == "LAST_SEEN") { openRequest = Tab.DECK to "seen"; return }
        if (i?.getStringExtra("tab") == "RETURN_RADAR") { openRequest = Tab.DECK to "returns"; return }
        // Day to day (hangar-phase3): Cool-off and Refill buzzes, the Waiting Mode tile and pinned shortcuts.
        if (i?.getStringExtra("tab") == "COOL_OFF") { openRequest = Tab.DECK to "cool"; return }
        if (i?.getStringExtra("tab") == "REFILL") { openRequest = Tab.DECK to "refill"; return }
        if (i?.getStringExtra("tab") == "WAITING") { openRequest = Tab.DECK to "waiting"; return }
        if (i?.getStringExtra("tab") == "ERRAND_RUN") { openRequest = Tab.DECK to "run"; return }
        if (i?.getStringExtra("tab") == "SENSORY") { openRequest = Tab.DECK to "sensory"; return }
        if (i?.getStringExtra("tab") == "HEADS_SWITCH") { openRequest = Tab.DECK to "heads_switch"; return }
        // flightdeck://launch, flightdeck://did and flightdeck://hangar, from an NFC tag by the door or a link.
        // flightdeck://clear/<id>: a Ticket Rail ticket read by the phone's own camera (ticket-rail-v1).
        if (i?.data?.scheme == TicketRail.SCHEME) {
            when (i.data?.host) {
                "clear" -> { TicketInbox.pending = TicketRail.parseLink(i.data.toString()); openRequest = Tab.DECK to "rail" }
                "rail" -> openRequest = Tab.DECK to "rail"
                "launch" -> openRequest = Tab.DECK to "launch"
                "night" -> openRequest = Tab.DECK to "night"
                "pass", "seen", "returns", "cool", "refill", "waiting", "run", "sensory" -> openRequest = Tab.DECK to i.data?.host
                "did" -> openRequest = Tab.DECK to "didi"
                "hangar" -> openRequest = Tab.DECK to "hangar"
                "heads" -> openRequest = Tab.DECK to "heads"
            }
            return
        }
        val tab = i?.getStringExtra("tab")?.let { name -> Tab.values().firstOrNull { it.name.equals(name, true) } } ?: return
        openRequest = tab to i.getStringExtra("slot")
    }

    /** Share-sheet entry: fetch the page, save it to Read It Later, then close. Shows a brief "Saving" screen. */
    private fun handleShare(i: Intent) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CaptureTheme {
                Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
                    Text("Saving to Read It Later…", color = Muted, fontSize = 16.sp)
                }
            }
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = ShareIntent.urlOf(text)
        val title = ShareIntent.pageTitle(text, i.getStringExtra(Intent.EXTRA_SUBJECT))
        val vault = prefs.vault
        lifecycleScope.launch {
            val msg = when {
                vault == null -> "Open Flight Deck once and pick your vault first"
                url.isBlank() -> "Nothing to save"
                // A recipe page (schema.org Recipe) is saved to Recipes instead (home-life-v1).
                withContext(Dispatchers.IO) { runCatching { Recipes.fetch(url).first?.let { r -> Recipes.save(appVault(applicationContext, vault), r); r.title } }.getOrNull() } != null ->
                    "Saved recipe to Recipes"
                else -> withContext(Dispatchers.IO) { runCatching { ReadLater.save(applicationContext, vault, url, title) } }
                    .fold({ if (it) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)" },
                        {
                            PhoneOutbox.keepLink(applicationContext, url, emptyList())
                            "Couldn't save that. It's kept on the phone and will be tried again."
                        })
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

private fun subFor(slot: String?) = when (slot) {
    "focus" -> Sub.FOCUS
    "snap" -> Sub.SNAP
    "jobs" -> Sub.JOBS
    "errands" -> Sub.ERRANDS
    "weekly" -> Sub.WEEKLY
    "plan" -> Sub.PLAN
    "hangar" -> Sub.HANGAR
    "didi" -> Sub.DID_I
    "launch" -> Sub.LAUNCH_PAD
    "heads", "heads_switch" -> Sub.HEADS_UP
    "rail" -> Sub.TICKET_RAIL
    "night" -> Sub.NIGHT_PAD
    "pass" -> Sub.POCKET_PASS
    "seen" -> Sub.LAST_SEEN
    "returns" -> Sub.RETURN_RADAR
    "cool" -> Sub.COOL_OFF
    "refill" -> Sub.REFILL
    "waiting" -> Sub.WAITING
    "run" -> Sub.ERRAND_RUN
    "sensory" -> Sub.SENSORY
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun App(prefs: AppPrefs, request: Pair<Tab, String?>?, onRequestHandled: () -> Unit) {
    val ctx = LocalContext.current
    var vault by remember { mutableStateOf(prefs.vault) }
    var tab by remember { mutableStateOf(request?.first ?: Tab.DECK) }
    var settings by remember { mutableStateOf(false) }
    // Settings straight to Quick Task backups (from the Tasks guard sheet); cleared when Settings closes.
    var settingsBackups by remember { mutableStateOf(false) }
    LaunchedEffect(settings) { if (!settings) settingsBackups = false }
    var cards by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf(subFor(request?.second)) }
    /** Heads Up opened from a Focus buzz shows the switch view; from Hangar it shows its settings. */
    var headsSwitch by remember { mutableStateOf(request?.second == "heads_switch") }
    // Hangar opens as a pull-up sheet (hangar-menu-v1) from the dock (hangar-dock-v1). Anything that asks for
    // Sub.HANGAR (a tool's back arrow, the app shortcut) lands on the Deck with the sheet open.
    var hangarOpen by remember { mutableStateOf(false) }
    // Edit Deck sheet from the Deck's More row (bottom-dock-v1 A); deckRev makes the Deck re-read its layout after.
    var editDeck by remember { mutableStateOf(false) }
    var addTiles by remember { mutableStateOf(false) }
    var deckRev by remember { mutableIntStateOf(0) }
    LaunchedEffect(sub) {
        if (sub == Sub.HANGAR) { sub = null; tab = Tab.DECK; settings = false; search = false; cards = false; hangarOpen = true }
    }
    var routineName by remember { mutableStateOf<String?>(null) }
    fun startFocus(task: String, raw: String?, path: String?, minutes: Int) {
        Focus.start(ctx, task, raw, path, minutes)
        sub = Sub.FOCUS
    }
    var openIdea by remember { mutableStateOf<String?>(null) }
    var openArticle by remember { mutableStateOf<ReadRef?>(null) }
    /** Tasks filtered to one note (from a note in Vault), and whether Vault is in quick edit (no bottom bar). */
    var tasksNote by remember { mutableStateOf<String?>(null) }
    var vaultEditing by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(0) }
    var jobsOpen by remember { mutableStateOf(0) }
    val snack = remember { SnackbarHostState() }
    // The Hangar dock (hangar-dock-v1): tools in Hangar's order, re-read when the sheet closes (Edit may have changed them).
    // Inbox, Home, Projects, Errands and Meals moved off the Deck into Hangar (hangar-dock-v2): added once to a Hangar set up before.
    remember {
        if (!prefs.hangarDeckToolsAdded) {
            Hangar.parse(prefs.hangarTools)?.let { prefs.hangarTools = Hangar.format(Hangar.withDeckTools(it)) }
            prefs.hangarDeckToolsAdded = true
        }
    }
    var dockTools by remember { mutableStateOf(Hangar.parse(prefs.hangarTools)) }
    var dockDots by remember { mutableStateOf(emptySet<HangarTool>()) }
    LaunchedEffect(vault, hangarOpen, sub) {
        if (hangarOpen) return@LaunchedEffect
        val t = Hangar.parse(prefs.hangarTools)
        dockTools = t
        val uri = vault ?: return@LaunchedEffect
        dockDots = withContext(Dispatchers.IO) { runCatching { dockNeeds(ctx, uri, prefs, t.orEmpty()) }.getOrDefault(emptySet()) }
    }
    fun openTool(t: HangarTool) {
        hangarOpen = false
        settings = false; search = false; cards = false
        when (t) {
            HangarTool.DID_I -> sub = Sub.DID_I
            HangarTool.LAUNCH_PAD -> sub = Sub.LAUNCH_PAD
            HangarTool.HEADS_UP -> { headsSwitch = false; sub = Sub.HEADS_UP }
            HangarTool.TICKET_RAIL -> sub = Sub.TICKET_RAIL
            HangarTool.NIGHT_PAD -> sub = Sub.NIGHT_PAD
            HangarTool.POCKET_PASS -> sub = Sub.POCKET_PASS
            HangarTool.LAST_SEEN -> sub = Sub.LAST_SEEN
            HangarTool.RETURN_RADAR -> sub = Sub.RETURN_RADAR
            HangarTool.COOL_OFF -> sub = Sub.COOL_OFF
            HangarTool.REFILL -> sub = Sub.REFILL
            HangarTool.WAITING -> sub = Sub.WAITING
            HangarTool.ERRAND_RUN -> sub = Sub.ERRANDS
            HangarTool.SENSORY -> sub = Sub.SENSORY
            HangarTool.INBOX -> sub = Sub.INBOX
            HangarTool.HOME -> sub = Sub.CHORES
            HangarTool.ERRANDS -> sub = Sub.ERRANDS
            HangarTool.MEALS -> sub = Sub.MEALS
            HangarTool.PROJECTS -> { sub = null; openIdea = null; tab = Tab.PROJECTS }
        }
    }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Reminders.scheduleAll(ctx, prefs) }
    LaunchedEffect(Unit) {
        Reminders.scheduleAll(ctx, prefs)
        HeadsAlarms.changed(ctx)
        val report = withContext(Dispatchers.IO) { runCatching { PhoneOutbox.retry(ctx, prefs) }.getOrNull() }
        if (report != null && report.done > 0) snack.showSnackbar(if (report.done == 1) "Saved 1 that was waiting" else "Saved ${report.done} that were waiting")
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx) && (prefs.morningOn || prefs.eveningOn || prefs.weeklyOn) && !prefs.askedNotif) {
            prefs.askedNotif = true
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(request) {
        request?.let {
            tab = it.first; settings = false; cards = false; search = false; openIdea = null
            sub = subFor(it.second)
            headsSwitch = it.second == "heads_switch"
            // From the Read widget the second part is the article's file name in the inbox.
            openArticle = if (it.first == Tab.READ) it.second?.let { f -> ReadRef(f, archived = false) } else null
            onRequestHandled()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.vault = uri
            vault = uri
        }
    }

    BackHandler(enabled = sub != null || cards || settings || search || openIdea != null || openArticle != null || tab == Tab.READ || tab == Tab.PROJECTS) {
        when {
            sub != null -> sub = null
            cards -> cards = false
            settings -> settings = false
            search -> search = false
            openArticle != null -> openArticle = null
            openIdea != null -> openIdea = null
            else -> tab = Tab.VAULT
        }
    }

    LaunchedEffect(vault, tab) {
        val uri = vault ?: return@LaunchedEffect
        if (tab == Tab.VAULT) withContext(Dispatchers.IO) { runCatching { ReadNote.unread(ReadStore(ctx, uri).inbox()).first } }
            .onSuccess { unread = it }
        if (tab == Tab.VAULT) withContext(Dispatchers.IO) { runCatching { Jobs.list(appVault(ctx, uri)).count { !it.closed } } }
            .onSuccess { jobsOpen = it }
    }

    // First run (care-welcome-v1): pick the vault and see what was found before the Deck opens.
    var welcoming by remember { mutableStateOf(vault == null) }
    if (welcoming) { WelcomeScreen(vault, onPick = { picker.launch(null) }, onDone = { welcoming = false }); return }

    val hideNav = (tab == Tab.PROJECTS && openIdea != null) || (tab == Tab.READ && openArticle != null) || (tab == Tab.VAULT && vaultEditing)
    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            // One bar (bottom-dock-v1 A): Deck, Capture, Tasks, Vault and Hangar, with a badge for what needs you.
            if (!hideNav) NavBar(if (hangarOpen) null else if (sub == Sub.JOBS || sub == Sub.RECIPES) Tab.VAULT else if (settings || search || sub != null) null else if (tab == Tab.READ || tab == Tab.PROJECTS) Tab.VAULT else tab,
                hangarOpen = hangarOpen, needs = dockDots.size, onHangar = { hangarOpen = true }) {
                if (it == Tab.TASKS && tab != Tab.TASKS) tasksNote = null
                tab = it; settings = false; cards = false; search = false; sub = null
            }
        }
    ) { pad ->
        // Consumed so a screen's imePadding() only adds what the keyboard covers beyond the bottom bar.
        Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            when {
                sub == Sub.DID_I -> DidIScreen(vault, prefs, snack) { sub = Sub.HANGAR }
                sub == Sub.LAUNCH_PAD -> LaunchPadScreen(vault, prefs, snack) { sub = Sub.HANGAR }
                sub == Sub.NIGHT_PAD -> NightPadScreen(vault, prefs, snack) { sub = Sub.HANGAR }
                sub == Sub.POCKET_PASS -> PocketPassScreen(vault, prefs, snack) { sub = Sub.HANGAR }
                sub == Sub.LAST_SEEN -> LastSeenScreen(vault, snack) { sub = Sub.HANGAR }
                sub == Sub.RETURN_RADAR -> ReturnRadarScreen(vault, snack) { sub = Sub.HANGAR }
                sub == Sub.COOL_OFF -> CoolOffScreen(vault, snack) { sub = Sub.HANGAR }
                sub == Sub.REFILL -> RefillScreen(vault, snack) { sub = Sub.HANGAR }
                sub == Sub.WAITING -> WaitingScreen(vault, snack, onOpenArticle = { openArticle = it; sub = null; tab = Tab.READ }) { sub = Sub.HANGAR }
                sub == Sub.ERRAND_RUN -> ErrandRunScreen(vault, snack, onPlaces = { sub = Sub.ERRANDS }) { sub = Sub.ERRANDS }
                sub == Sub.HEADS_UP -> HeadsUpScreen(vault, prefs, snack, headsSwitch) { headsSwitch = false; sub = Sub.HANGAR }
                sub == Sub.SENSORY -> SensoryScreen(vault, prefs, snack) { sub = Sub.HANGAR }
                sub == Sub.TICKET_RAIL -> TicketRailScreen(vault, prefs, snack, TicketInbox.pending, onClearHandled = { TicketInbox.pending = null }) { sub = Sub.HANGAR }
                sub == Sub.FOCUS -> FocusScreen(vault, prefs, snack) { sub = null }
                sub == Sub.HABITS -> HabitsScreen(vault, snack) { sub = null }
                sub == Sub.WEEKLY -> WeeklyScreen(vault, snack, prefs, onMigrate = { sub = Sub.MIGRATE }) { sub = null }
                sub == Sub.JOBS -> JobsScreen(vault, prefs, snack) { sub = null }
                sub == Sub.PRINT -> PrintScreen(vault, snack) { sub = null }
                sub == Sub.MIGRATE -> MigrateScreen(vault, prefs, snack) { sub = null }
                sub == Sub.SCAN -> ScanScreen(vault, prefs, snack) { sub = null }
                sub == Sub.SNAP -> SnapScreen(vault, prefs, snack, onScanPage = { sub = Sub.SCAN }) { sub = null }
                sub == Sub.CHORES -> ChoresScreen(vault, snack) { sub = null }
                sub == Sub.LEARN -> LearnScreen(vault, prefs, snack) { sub = null }
                sub == Sub.INBOX -> InboxScreen(vault, prefs, snack) { sub = null }
                sub == Sub.PEOPLE -> PeopleScreen(vault, snack) { sub = null }
                sub == Sub.ERRANDS -> ErrandsScreen(vault, snack, onRun = { sub = Sub.ERRAND_RUN }) { sub = null }
                sub == Sub.MEALS -> MealsScreen(vault, snack) { sub = null }
                sub == Sub.RECIPES -> RecipesScreen(vault, snack) { sub = null }
                sub == Sub.TRIAGE -> TriageScreen(vault, prefs, snack) { sub = null }
                sub == Sub.PLAN -> PlanScreen(vault, prefs, snack) { sub = null }
                sub == Sub.ROUTINE -> RoutineScreen(vault, routineName, snack, onPlan = { sub = Sub.PLAN }) { sub = null }
                sub == Sub.STUCK -> StuckScreen(vault, snack, prefs, onFocus = { t, m -> startFocus(t.text, t.raw.ifEmpty { null }, t.path.ifEmpty { null }, m) }, onBack = { sub = null }) { i -> startFocus(i.name, null, null, i.minutes) }
                cards -> CardsScreen(vault, snack) { cards = false }
                settings -> SettingsScreen(vault, prefs, startOnBackups = settingsBackups, onPickVault = { picker.launch(null) }, onOpenCards = { cards = true }, onOpenHabits = { settings = false; sub = Sub.HABITS }, onOpenWeekly = { settings = false; sub = Sub.WEEKLY }, onOpenPrint = { settings = false; sub = Sub.PRINT })
                search -> SearchScreen(vault, snack) { search = false }
                tab == Tab.DECK -> DeckScreen(
                    vault, prefs, snack, deckRev, onEditDeck = { editDeck = true }, onAddTile = { addTiles = true }, onOpenTool = { openTool(it) }, onOpenTab = { tab = it }, onOpenSub = { sub = it },
                    onStartFocus = { t, m -> startFocus(t.text, t.raw.ifEmpty { null }, t.path.ifEmpty { null }, m) }, onOpenSearch = { search = true },
                    onOpenArticle = { openArticle = it; tab = Tab.READ }, onPlayRoutine = { routineName = it; sub = Sub.ROUTINE }
                ) { settings = true }
                tab == Tab.CAPTURE -> CaptureScreen(vault, prefs, snack, onScan = { sub = Sub.SNAP }, onScanPage = { sub = Sub.SCAN }, onPrintToday = { sub = Sub.PRINT }) { settings = true }
                tab == Tab.TASKS -> TasksScreen(
                    vault, prefs, snack, tasksNote, onClearNote = { tasksNote = null },
                    onStartFocus = { t, m -> startFocus(t.text, t.raw, t.path, m) }, onOpenSettings = { settings = true },
                    onOpenTriage = { sub = Sub.TRIAGE },
                    onOpenGuardBackups = { settingsBackups = true; settings = true },
                    onAddOn = { d -> CapturePreset.date = d; prefs.captureMode = 0; tab = Tab.CAPTURE },
                )
                tab == Tab.VAULT -> VaultScreen(
                    vault, prefs, snack, unread, jobsOpen, onOpenRead = { tab = Tab.READ }, onOpenProjects = { openIdea = null; tab = Tab.PROJECTS },
                    onOpenJobs = { sub = Sub.JOBS }, onOpenRecipes = { sub = Sub.RECIPES },
                    onOpenTasks = { tasksNote = it; tab = Tab.TASKS }, onEditing = { vaultEditing = it }
                )
                tab == Tab.PROJECTS -> Column(Modifier.fillMaxSize()) {
                    // Projects opens from Vault (or the Deck card); this takes you back there.
                    if (openIdea == null) Text("‹ Vault", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { tab = Tab.VAULT }.padding(start = 18.dp, top = 10.dp, end = 18.dp))
                    Box(Modifier.weight(1f)) { IdeasScreen(vault, prefs, snack, openIdea) { openIdea = it } }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    // Read later opens from Vault; this takes you back there.
                    if (openArticle == null) Text("‹ Vault", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { tab = Tab.VAULT }.padding(start = 18.dp, top = 10.dp, end = 18.dp))
                    Box(Modifier.weight(1f)) { ReadScreen(vault, prefs, snack, openArticle) { openArticle = it } }
                }
            }
        }
        if (addTiles) {
            ModalBottomSheet(onDismissRequest = { addTiles = false; deckRev++ }, containerColor = Surface2, scrimColor = Color.Black.copy(alpha = 0.6f)) {
                AddTilesSheet(prefs) { addTiles = false; deckRev++ }
            }
        }
        if (editDeck) {
            ModalBottomSheet(onDismissRequest = { editDeck = false; deckRev++ }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface2,
                scrimColor = Color.Black.copy(alpha = 0.6f)) {
                CardsScreen(vault, snack, inSheet = true) { editDeck = false; deckRev++ }
            }
        }
        if (hangarOpen) {
            val hangarSheet = rememberModalBottomSheetState()
            // Opens halfway; drag up for the full list. A darker scrim so the screen behind doesn't show through.
            ModalBottomSheet(onDismissRequest = { hangarOpen = false }, sheetState = hangarSheet, containerColor = Surface2,
                scrimColor = Color.Black.copy(alpha = 0.6f)) {
            HangarScreen(vault, prefs, snack, onOpen = { openTool(it) }, inSheet = true, needs = dockDots) { hangarOpen = false }
            }
        }
    }
}

@Composable
private fun NavBar(selected: Tab?, hangarOpen: Boolean, needs: Int, onHangar: () -> Unit, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(NavBg)) {
        HorizontalDivider(color = Outline)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.NAV.forEach { t ->
                val on = t == selected
                Column(Modifier.clickable { onSelect(t) }.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(CircleShape).background(if (on) PrimaryTint else Color.Transparent).padding(horizontal = 12.dp, vertical = 3.dp)) {
                        Text(t.icon, fontSize = 18.sp, color = if (on) Primary else Muted)
                    }
                    Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Primary else Muted)
                }
            }
            Column(Modifier.clickable(onClick = onHangar).padding(horizontal = 2.dp).semantics { contentDescription = "Hangar" + if (needs > 0) ", $needs need you" else "" },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Box(Modifier.clip(CircleShape).background(if (hangarOpen) PrimaryTint else Color.Transparent).padding(horizontal = 12.dp, vertical = 3.dp)) {
                        Text("⌂", fontSize = 18.sp, color = if (hangarOpen) Primary else Muted)
                    }
                    if (needs > 0) Box(Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(Green), contentAlignment = Alignment.Center) {
                        Text("$needs", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1A12))
                    }
                }
                Text("Hangar", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (hangarOpen) Primary else Muted)
            }
        }
    }
}
