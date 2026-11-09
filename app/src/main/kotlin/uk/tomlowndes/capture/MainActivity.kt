package uk.tomlowndes.capture

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
enum class Sub { FOCUS, STUCK, HABITS, WEEKLY, JOBS }

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
        if (intent?.action == Intent.ACTION_SEND) {
            if (!toCapture(intent)) { handleShare(intent); return }
        } else readOpenRequest(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CaptureTheme {
                App(prefs, openRequest) { openRequest = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) toCapture(intent) else readOpenRequest(intent)
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
        if (i?.getStringExtra("tab") == "FOCUS") { openRequest = Tab.DECK to "focus"; return }
        if (i?.getStringExtra("tab") == "WEEKLY") { openRequest = Tab.DECK to "weekly"; return }
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
                else -> withContext(Dispatchers.IO) { runCatching { ReadLater.save(applicationContext, vault, url, title) } }
                    .fold({ if (it) "Saved page to Read It Later" else "Saved link only (couldn't fetch the page)" },
                        { "Failed: ${it.message}" })
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

private fun subFor(slot: String?) = when (slot) {
    "focus" -> Sub.FOCUS
    "weekly" -> Sub.WEEKLY
    else -> null
}

@Composable
fun App(prefs: AppPrefs, request: Pair<Tab, String?>?, onRequestHandled: () -> Unit) {
    val ctx = LocalContext.current
    var vault by remember { mutableStateOf(prefs.vault) }
    var tab by remember { mutableStateOf(request?.first ?: Tab.DECK) }
    var settings by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf(subFor(request?.second)) }
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

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Reminders.scheduleAll(ctx, prefs) }
    LaunchedEffect(Unit) {
        Reminders.scheduleAll(ctx, prefs)
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx) && (prefs.morningOn || prefs.eveningOn || prefs.weeklyOn) && !prefs.askedNotif) {
            prefs.askedNotif = true
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(request) {
        request?.let {
            tab = it.first; settings = false; cards = false; search = false; openIdea = null
            sub = subFor(it.second)
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

    val hideNav = (tab == Tab.PROJECTS && openIdea != null) || (tab == Tab.READ && openArticle != null) || (tab == Tab.VAULT && vaultEditing)
    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!hideNav) NavBar(if (sub == Sub.JOBS) Tab.VAULT else if (settings || search || sub != null) null else if (tab == Tab.READ || tab == Tab.PROJECTS) Tab.VAULT else tab) {
                if (it == Tab.TASKS && tab != Tab.TASKS) tasksNote = null
                tab = it; settings = false; cards = false; search = false; sub = null
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                sub == Sub.FOCUS -> FocusScreen(vault, prefs, snack) { sub = null }
                sub == Sub.HABITS -> HabitsScreen(vault, snack) { sub = null }
                sub == Sub.WEEKLY -> WeeklyScreen(vault, snack) { sub = null }
                sub == Sub.JOBS -> JobsScreen(vault, snack) { sub = null }
                sub == Sub.STUCK -> StuckScreen(vault, snack, onBack = { sub = null }) { i -> startFocus(i.name, null, null, i.minutes) }
                cards -> CardsScreen(vault, snack) { cards = false }
                settings -> SettingsScreen(vault, prefs, onPickVault = { picker.launch(null) }, onOpenCards = { cards = true }, onOpenHabits = { settings = false; sub = Sub.HABITS }, onOpenWeekly = { settings = false; sub = Sub.WEEKLY })
                search -> SearchScreen(vault, snack) { search = false }
                tab == Tab.DECK -> DeckScreen(
                    vault, prefs, snack, onOpenTab = { tab = it }, onOpenSub = { sub = it },
                    onStartFocus = { t, m -> startFocus(t.text, t.raw.ifEmpty { null }, t.path.ifEmpty { null }, m) }, onOpenSearch = { search = true }
                ) { settings = true }
                tab == Tab.CAPTURE -> CaptureScreen(vault, prefs, snack) { settings = true }
                tab == Tab.TASKS -> TasksScreen(
                    vault, prefs, snack, tasksNote, onClearNote = { tasksNote = null },
                    onStartFocus = { t, m -> startFocus(t.text, t.raw, t.path, m) }, onOpenSettings = { settings = true }
                )
                tab == Tab.VAULT -> VaultScreen(
                    vault, prefs, snack, unread, jobsOpen, onOpenRead = { tab = Tab.READ }, onOpenProjects = { openIdea = null; tab = Tab.PROJECTS },
                    onOpenJobs = { sub = Sub.JOBS },
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
    }
}

@Composable
private fun NavBar(selected: Tab?, onSelect: (Tab) -> Unit) {
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
        }
    }
}
