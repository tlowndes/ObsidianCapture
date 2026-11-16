package uk.tomlowndes.capture

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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

/*
 * Stage 6 widgets (extras-v1, idea 19): Next step (Start / Done / Later), Chores (tap to mark done) and Shopping
 * (tick from the home screen). They refresh when the app changes the vault and every 30 minutes.
 */

data class WidgetNext(val hasVault: Boolean, val task: TaskItem?, val meta: String = "")
data class WidgetChores(val hasVault: Boolean, val chores: List<Chore>)
data class WidgetShop(val hasVault: Boolean, val items: List<String>)

object HomeWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val next = MutableStateFlow<WidgetNext?>(null)
    internal val chores = MutableStateFlow<WidgetChores?>(null)
    internal val shop = MutableStateFlow<WidgetShop?>(null)

    internal fun loadNext(ctx: Context): WidgetNext {
        val prefs = AppPrefs(ctx)
        val uri = prefs.vault ?: return WidgetNext(false, null)
        val tasks = runCatching { VaultWriter(ctx, uri).listTasks() }.getOrDefault(emptyList())
        val t = Deck.nextStep(tasks, prefs.laterToday) ?: return WidgetNext(true, null)
        val meta = listOfNotNull(TaskViews.tags(t).firstOrNull(), Deck.minutes(t)?.let { TaskRules.minutesLabel(it) }).joinToString(" · ")
        return WidgetNext(true, t, meta)
    }

    internal fun loadChores(ctx: Context): WidgetChores {
        val uri = AppPrefs(ctx).vault ?: return WidgetChores(false, emptyList())
        val today = LocalDate.now()
        return WidgetChores(true, runCatching { Chores.sorted(Chores.load(appVault(ctx, uri)), today) }.getOrDefault(emptyList()))
    }

    internal fun loadShop(ctx: Context): WidgetShop {
        val uri = AppPrefs(ctx).vault ?: return WidgetShop(false, emptyList())
        val v = runCatching { appVault(ctx, uri) }.getOrNull() ?: return WidgetShop(true, emptyList())
        return WidgetShop(true, runCatching { v.read(Recipes.SHOPPING)?.let { Recipes.shoppingOpen(it) } }.getOrNull().orEmpty())
    }

    suspend fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        withContext(Dispatchers.IO) { next.value = loadNext(app); chores.value = loadChores(app); shop.value = loadShop(app) }
        NextStepWidget().updateAll(app)
        ChoresWidget().updateAll(app)
        ShoppingWidget().updateAll(app)
    }

    /** Fire-and-forget refresh after the app changes tasks, chores or shopping. */
    fun changed(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { refresh(app) } }
    }
}

private fun cp(c: Color) = ColorProvider(c)
private val RawKey = ActionParameters.Key<String>("raw")
private val PathKey = ActionParameters.Key<String>("path")
private val NameKey = ActionParameters.Key<String>("name")
private val ItemKey = ActionParameters.Key<String>("item")

@Composable
private fun Panel(content: @Composable () -> Unit) =
    Column(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(14.dp)) { content() }

@Composable
private fun Title(text: String, open: androidx.glance.action.Action) =
    Text(text, maxLines = 1, style = TextStyle(color = cp(Muted), fontSize = 12.sp, fontWeight = FontWeight.Bold),
        modifier = GlanceModifier.fillMaxWidth().clickable(open))

@Composable
private fun Quiet(text: String, open: androidx.glance.action.Action) =
    Box(GlanceModifier.fillMaxSize().clickable(open), contentAlignment = Alignment.Center) { Text(text, style = TextStyle(color = cp(Muted), fontSize = 13.sp)) }

// ---- Next step ----

class NextStepWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        HomeWidgets.next.value = withContext(Dispatchers.IO) { HomeWidgets.loadNext(context.applicationContext) }
        provideContent { val d by HomeWidgets.next.collectAsState(); NextContent(context, d) }
    }
}

class NextStepWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NextStepWidget() }

@Composable
private fun NextContent(ctx: Context, d: WidgetNext?) {
    val open = actionStartActivity(Widgets.openTab(ctx, Tab.DECK))
    Panel {
        Title("NEXT STEP", open)
        Spacer(GlanceModifier.height(6.dp))
        val t = d?.task
        when {
            d == null -> Quiet("Loading…", open)
            !d.hasVault -> Quiet("Open Flight Deck to pick your vault", open)
            t == null -> Quiet("Nothing next. Nice.", open)
            else -> {
                Text(TaskViews.plainText(t), maxLines = 2, style = TextStyle(color = cp(TextMain), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.clickable(open))
                if (d.meta.isNotEmpty()) Text(d.meta, style = TextStyle(color = cp(Muted), fontSize = 12.sp))
                Spacer(GlanceModifier.height(10.dp))
                val p = actionParametersOf(RawKey to t.raw, PathKey to t.path)
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Pill("▶ Start", Primary, OnPrimary, actionStartActivity(Widgets.openTab(ctx, Tab.DECK)))
                    Spacer(GlanceModifier.width(8.dp))
                    Pill("✓ Done", Surface2, TextMain, actionRunCallback<NextDoneAction>(p))
                    Spacer(GlanceModifier.width(8.dp))
                    Pill("Later", Surface2, Muted, actionRunCallback<NextLaterAction>(p))
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, action: androidx.glance.action.Action) =
    Box(GlanceModifier.cornerRadius(99.dp).background(bg).padding(horizontal = 12.dp, vertical = 7.dp).clickable(action), contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(color = cp(fg), fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }

class NextDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val raw = parameters[RawKey] ?: return
        val task = TaskNote.parse(raw)?.copy(path = parameters[PathKey] ?: VaultTasks.QUICK_TASK) ?: return
        val app = context.applicationContext
        val prefs = AppPrefs(app)
        HomeWidgets.next.value = HomeWidgets.next.value?.copy(task = null)
        prefs.vault?.let { v -> withContext(Dispatchers.IO) { runCatching { VaultWriter(app, v).completeTask(task, prefs.recurring) } } }
        HomeWidgets.refresh(app)
        Widgets.refreshTasks(app)
    }
}

class NextLaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val raw = parameters[RawKey] ?: return
        val prefs = AppPrefs(context.applicationContext)
        prefs.laterToday = prefs.laterToday + raw
        HomeWidgets.refresh(context)
    }
}

// ---- Chores ----

class ChoresWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        HomeWidgets.chores.value = withContext(Dispatchers.IO) { HomeWidgets.loadChores(context.applicationContext) }
        provideContent { val d by HomeWidgets.chores.collectAsState(); ChoresContent(context, d) }
    }
}

class ChoresWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ChoresWidget() }

@Composable
private fun ChoresContent(ctx: Context, d: WidgetChores?) {
    val open = actionStartActivity(Widgets.openTab(ctx, Tab.DECK))
    val today = LocalDate.now()
    Panel {
        Title("CHORES · tap when done", open)
        Spacer(GlanceModifier.height(6.dp))
        when {
            d == null -> Quiet("Loading…", open)
            !d.hasVault -> Quiet("Open Flight Deck to pick your vault", open)
            d.chores.isEmpty() -> Quiet("No chores yet", open)
            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                items(d.chores, itemId = { it.name.hashCode().toLong() }) { c ->
                    val fill = c.fill(today)
                    val colour = if (fill >= Chores.OVERDUE) Warn else if (fill >= Chores.DUE) Amber else Green
                    Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionRunCallback<ChoreDoneAction>(actionParametersOf(NameKey to c.name)))) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, maxLines = 1, style = TextStyle(color = cp(TextMain), fontSize = 14.sp), modifier = GlanceModifier.defaultWeight())
                            Text(c.ageLabel(today).substringBefore(" ·"), style = TextStyle(color = cp(colour), fontSize = 12.sp))
                        }
                        // The bar: a filled part in a track, widths in whole dp so Glance can lay them out.
                        val w = (fill.coerceIn(0.0, 1.0) * 120).toInt()
                        Row(GlanceModifier.width(120.dp).height(4.dp).cornerRadius(2.dp).background(Surface2)) {
                            if (w > 0) Box(GlanceModifier.width(w.dp).height(4.dp).cornerRadius(2.dp).background(colour)) {}
                        }
                    }
                }
            }
        }
    }
}

class ChoreDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val name = parameters[NameKey] ?: return
        val app = context.applicationContext
        val uri = AppPrefs(app).vault ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val v = appVault(app, uri)
                Chores.load(v).firstOrNull { it.name == name }?.let { Chores.markDone(v, it) }
            }
        }
        HomeWidgets.refresh(app)
    }
}

// ---- Shopping ----

class ShoppingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        HomeWidgets.shop.value = withContext(Dispatchers.IO) { HomeWidgets.loadShop(context.applicationContext) }
        provideContent { val d by HomeWidgets.shop.collectAsState(); ShopContent(context, d) }
    }
}

class ShoppingWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ShoppingWidget() }

@Composable
private fun ShopContent(ctx: Context, d: WidgetShop?) {
    val open = actionStartActivity(Widgets.openTab(ctx, Tab.DECK).putExtra("tab", "ERRANDS"))
    Panel {
        Title("SHOPPING" + (d?.items?.size?.takeIf { it > 0 }?.let { " · $it" } ?: ""), open)
        Spacer(GlanceModifier.height(4.dp))
        when {
            d == null -> Quiet("Loading…", open)
            !d.hasVault -> Quiet("Open Flight Deck to pick your vault", open)
            d.items.isEmpty() -> Quiet("Nothing on the list", open)
            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                items(d.items, itemId = { it.hashCode().toLong() }) { raw ->
                    val i = SharedList.item(raw)
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CheckBox(checked = false, onCheckedChange = actionRunCallback<ShopTickAction>(actionParametersOf(ItemKey to raw)),
                            colors = CheckboxDefaults.colors(checkedColor = cp(Primary), uncheckedColor = cp(Muted)))
                        Text(i.text, maxLines = 1, style = TextStyle(color = cp(TextMain), fontSize = 14.sp), modifier = GlanceModifier.defaultWeight())
                        if (i.partner) Text("partner", style = TextStyle(color = cp(Muted), fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

class ShopTickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val item = parameters[ItemKey] ?: return
        val app = context.applicationContext
        HomeWidgets.shop.value = HomeWidgets.shop.value?.let { it.copy(items = it.items - item) }
        val uri = AppPrefs(app).vault ?: return
        withContext(Dispatchers.IO) { runCatching { Recipes.setHave(appVault(app, uri), Recipes.SHOPPING, item, true) } }
        HomeWidgets.refresh(app)
    }
}
