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

/** Home screen widgets: shared loading and refresh. */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val tasks = MutableStateFlow<WidgetTasks?>(null)

    internal fun load(ctx: Context): WidgetTasks {
        val vault = AppPrefs(ctx).vault ?: return WidgetTasks(false, emptyList())
        val today = LocalDate.now()
        return runCatching { VaultWriter(ctx, vault).listTasks() }.fold(
            { all -> WidgetTasks(true, all.filter { it.due != null && !it.due.isAfter(today) }.sortedBy { it.due }) },
            { WidgetTasks(true, emptyList(), "Couldn't read tasks") }
        )
    }

    suspend fun refreshTasks(ctx: Context) {
        val app = ctx.applicationContext
        tasks.value = withContext(Dispatchers.IO) { load(app) }
        TasksWidget().updateAll(app)
    }

    /** Fire-and-forget refresh after the app changes the task note. */
    fun tasksChanged(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { refreshTasks(app) } }
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
    val today = LocalDate.now()
    val openTasks = actionStartActivity(Widgets.openTab(ctx, Tab.TASKS))
    Column(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(openTasks)) {
                Text(
                    "Today" + (data?.items?.size?.takeIf { it > 0 }?.let { " · $it" } ?: ""),
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
            !data.hasVault -> Message("Open Vault Capture to pick your vault", openTasks)
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
                Text("Overdue · ${dueLabel(due)}", style = TextStyle(color = cp(Warn), fontSize = 11.sp))
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
