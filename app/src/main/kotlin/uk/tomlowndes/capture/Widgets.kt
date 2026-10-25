package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the tasks widget shows: overdue and today's open tasks, oldest first. */
data class WidgetTasks(val hasVault: Boolean, val items: List<TaskItem>, val error: String? = null)

/** What the Read widget shows: the unread count and the newest unread article. */
data class WidgetRead(val hasVault: Boolean, val unread: Int, val next: SavedArticle?, val error: String? = null)

/** Home screen widgets: shared loading and refresh. */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val tasks = MutableStateFlow<WidgetTasks?>(null)

    internal fun load(ctx: Context): WidgetTasks {
        val vault = AppPrefs(ctx).vault ?: return WidgetTasks(false, emptyList())
        val today = LocalDate.now()
        return runCatching { VaultWriter(ctx, vault).listTasks() }.fold(
            { all -> WidgetTasks(true, dueBy(all, today)) },
            { WidgetTasks(true, emptyList(), "Couldn't read tasks") }
        )
    }

    /** Overdue and today's tasks, oldest first; undated and future tasks are left out. */
    internal fun dueBy(all: List<TaskItem>, today: LocalDate) =
        all.filter { d -> d.due?.isAfter(today) == false }.sortedBy { it.due }

    internal fun tasksTitle(count: Int) = "Today" + (count.takeIf { it > 0 }?.let { " · $it" } ?: "")

    internal fun readTitle(unread: Int) = "📖 " + when (unread) { 0 -> "Read"; 1 -> "1 unread"; else -> "$unread unread" }

    suspend fun refreshTasks(ctx: Context) {
        val app = ctx.applicationContext
        tasks.value = withContext(Dispatchers.IO) { load(app) }
        TasksWidget().updateAll(app)
        CombinedWidget().updateAll(app)
    }

    /** Fire-and-forget refresh after the app changes the task note. */
    fun tasksChanged(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { refreshTasks(app) } }
    }

    internal val read = MutableStateFlow<WidgetRead?>(null)

    internal fun loadRead(ctx: Context): WidgetRead {
        val vault = AppPrefs(ctx).vault ?: return WidgetRead(false, 0, null)
        return runCatching { ReadNote.unread(ReadStore(ctx, vault).inbox()) }.fold(
            { (count, next) -> WidgetRead(true, count, next) },
            { WidgetRead(true, 0, null, "Couldn't read the inbox") }
        )
    }

    suspend fun refreshRead(ctx: Context) {
        val app = ctx.applicationContext
        read.value = withContext(Dispatchers.IO) { loadRead(app) }
        ReadWidget().updateAll(app)
        CombinedWidget().updateAll(app)
    }

    /** Fire-and-forget refresh after an article is saved, read or archived. */
    fun readChanged(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { refreshRead(app) } }
    }

    internal fun openTab(ctx: Context, tab: Tab) =
        Intent(ctx, MainActivity::class.java).putExtra("tab", tab.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun quickCapture(ctx: Context, link: Boolean) =
        Intent(ctx, QuickCaptureActivity::class.java).putExtra(QuickCaptureActivity.EXTRA_LINK, link)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

private fun cp(c: Color) = ColorProvider(c)
private val RawKey = ActionParameters.Key<String>("raw")
private val PathKey = ActionParameters.Key<String>("path")

// ---- Today's tasks ----

class TasksWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Widgets.tasks.value = withContext(Dispatchers.IO) { Widgets.load(context.applicationContext) }
        provideContent {
            val data by Widgets.tasks.collectAsState()
            TasksContent(context, data)
        }
    }
}

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}

@Composable
private fun TasksContent(ctx: Context, data: WidgetTasks?) {
    TasksPanel(ctx, data, GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(14.dp))
}

@Composable
private fun TasksPanel(ctx: Context, data: WidgetTasks?, modifier: GlanceModifier) {
    val today = LocalDate.now()
    val openTasks = actionStartActivity(Widgets.openTab(ctx, Tab.TASKS))
    Column(modifier) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(openTasks)) {
                Text(
                    Widgets.tasksTitle(data?.items?.size ?: 0), maxLines = 1,
                    style = TextStyle(color = cp(TextMain), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)),
                    style = TextStyle(color = cp(Muted), fontSize = 12.sp)
                )
            }
            Text("↻", style = TextStyle(color = cp(Muted), fontSize = 20.sp),
                modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(actionRunCallback<RefreshAction>()))
            Box(
                GlanceModifier.width(34.dp).height(34.dp).cornerRadius(17.dp).background(Primary)
                    .clickable(actionStartActivity(Widgets.quickCapture(ctx, false))),
                contentAlignment = Alignment.Center
            ) { Text("+", style = TextStyle(color = cp(OnPrimary), fontSize = 20.sp, fontWeight = FontWeight.Bold)) }
        }
        Spacer(GlanceModifier.height(8.dp))
        when {
            data == null -> Message("Loading…", openTasks)
            !data.hasVault -> Message("Open Flight Deck to pick your vault", openTasks)
            data.error != null -> Message(data.error, openTasks)
            data.items.isEmpty() -> Message("Nothing due today", openTasks)
            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                items(data.items, itemId = { (it.path + it.raw).hashCode().toLong() }) { t -> TaskRow(t, today, openTasks) }
            }
        }
    }
}

@Composable
private fun Message(text: String, onClick: androidx.glance.action.Action) {
    Box(GlanceModifier.fillMaxSize().clickable(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = cp(Muted), fontSize = 13.sp))
    }
}

@Composable
private fun TaskRow(t: TaskItem, today: LocalDate, open: androidx.glance.action.Action) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<TickAction>(actionParametersOf(RawKey to t.raw, PathKey to t.path)),
            colors = CheckboxDefaults.colors(checkedColor = cp(Primary), uncheckedColor = cp(Muted))
        )
        Column(GlanceModifier.defaultWeight().clickable(open)) {
            Text(t.text, maxLines = 2, style = TextStyle(color = cp(TextMain), fontSize = 14.sp))
            val due = t.due
            if (due != null && due.isBefore(today)) {
                Text("Overdue · ${dueLabel(due)}", style = TextStyle(color = cp(Warn), fontSize = 12.sp))
            }
        }
    }
}

/** Ticks a task from the widget: drops it from view straight away, then writes the note. */
class TickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val raw = parameters[RawKey] ?: return
        val path = parameters[PathKey] ?: VaultTasks.QUICK_TASK
        val task = TaskNote.parse(raw)?.copy(path = path) ?: return
        val app = context.applicationContext
        Widgets.tasks.value?.let { cur -> Widgets.tasks.value = cur.copy(items = cur.items.filterNot { it.raw == raw && it.path == path }) }
        val prefs = AppPrefs(app)
        val vault = prefs.vault
        if (vault != null) withContext(Dispatchers.IO) { runCatching { VaultWriter(app, vault).completeTask(task, prefs.recurring) } }
        Widgets.refreshTasks(app)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Widgets.refreshTasks(context)
    }
}

// ---- Read It Later ----

class ReadWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Widgets.read.value = withContext(Dispatchers.IO) { Widgets.loadRead(context.applicationContext) }
        provideContent {
            val data by Widgets.read.collectAsState()
            ReadContent(context, data)
        }
    }
}

class ReadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadWidget()
}

@Composable
private fun ReadContent(ctx: Context, data: WidgetRead?) {
    ReadPanel(ctx, data, GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(14.dp))
}

@Composable
private fun ReadPanel(ctx: Context, data: WidgetRead?, modifier: GlanceModifier) {
    val openList = actionStartActivity(Widgets.openTab(ctx, Tab.READ))
    val next = data?.next
    val openNext = next?.let { actionStartActivity(Widgets.openTab(ctx, Tab.READ).putExtra("slot", it.ref.file)) } ?: openList
    Column(modifier) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Widgets.readTitle(data?.unread ?: 0), maxLines = 1,
                style = TextStyle(color = cp(TextMain), fontSize = 17.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight().clickable(openList)
            )
            Text("↻", style = TextStyle(color = cp(Muted), fontSize = 20.sp),
                modifier = GlanceModifier.padding(start = 8.dp).clickable(actionRunCallback<RefreshReadAction>()))
        }
        Spacer(GlanceModifier.height(6.dp))
        when {
            data == null -> Message("Loading…", openList)
            !data.hasVault -> Message("Open Flight Deck to pick your vault", openList)
            data.error != null -> Message(data.error, openList)
            next == null -> Message("All caught up", openList)
            else -> Column(GlanceModifier.fillMaxSize().clickable(openNext)) {
                Text("Next up", style = TextStyle(color = cp(Muted), fontSize = 12.sp))
                Text(next.title, maxLines = 3, style = TextStyle(color = cp(TextMain), fontSize = 14.sp, fontWeight = FontWeight.Medium))
                ReadStore.host(next.url)?.let { Text(it, maxLines = 1, style = TextStyle(color = cp(Muted), fontSize = 12.sp)) }
            }
        }
    }
}

class RefreshReadAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Widgets.refreshRead(context)
    }
}

// ---- Tasks and Read It Later, side by side ----

class CombinedWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            Widgets.tasks.value = Widgets.load(app)
            Widgets.read.value = Widgets.loadRead(app)
        }
        provideContent {
            val tasks by Widgets.tasks.collectAsState()
            val read by Widgets.read.collectAsState()
            CombinedContent(context, tasks, read)
        }
    }
}

class CombinedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CombinedWidget()
}

@Composable
private fun CombinedContent(ctx: Context, tasks: WidgetTasks?, read: WidgetRead?) {
    Row(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(vertical = 14.dp)) {
        TasksPanel(ctx, tasks, GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 14.dp))
        Box(GlanceModifier.width(1.dp).fillMaxHeight().background(Surface2)) {}
        ReadPanel(ctx, read, GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 14.dp))
    }
}

// ---- One-tap capture ----

class CaptureWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { CaptureContent(context) }
    }

    companion object {
        private val SMALL = DpSize(48.dp, 48.dp)
        private val WIDE = DpSize(120.dp, 48.dp)
    }
}

class CaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaptureWidget()
}

@Composable
private fun CaptureContent(ctx: Context) {
    val wide = LocalSize.current.width >= 110.dp
    Row(
        GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CaptureButton(if (wide) "+ Task" else "+", Primary, OnPrimary, Widgets.quickCapture(ctx, false), GlanceModifier.defaultWeight())
        if (wide) {
            Spacer(GlanceModifier.width(6.dp))
            CaptureButton("🔗 Later", Surface2, TextMain, Widgets.quickCapture(ctx, true), GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun CaptureButton(label: String, bg: Color, fg: Color, intent: Intent, modifier: GlanceModifier) {
    Box(
        modifier.fillMaxSize().cornerRadius(16.dp).background(bg).clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center
    ) { Text(label, maxLines = 1, style = TextStyle(color = cp(fg), fontSize = 15.sp, fontWeight = FontWeight.Bold)) }
}
