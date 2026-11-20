package uk.tomlowndes.capture

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
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
import java.time.format.DateTimeFormatter

/** What the Did I? widget shows: today's checks with their stamp times. */
data class WidgetDid(val hasVault: Boolean, val status: List<DidStatus>)

/** The Did I? widget and Quick Settings tile: shared loading and refresh. */
object DidWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val did = MutableStateFlow<WidgetDid?>(null)

    internal fun load(ctx: Context): WidgetDid {
        val uri = AppPrefs(ctx).vault ?: return WidgetDid(false, emptyList())
        return WidgetDid(true, runCatching { DidI.today(appVault(ctx, uri)) }.getOrDefault(emptyList()))
    }

    suspend fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        withContext(Dispatchers.IO) { did.value = load(app) }
        DidWidget().updateAll(app)
        runCatching { TileService.requestListeningState(app, android.content.ComponentName(app, DidTileService::class.java)) }
    }

    /** Fire-and-forget refresh after a stamp, an undo or a new check. */
    fun changed(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runCatching { refresh(app) } }
    }

    internal fun open(ctx: Context) =
        Intent(ctx, MainActivity::class.java).putExtra("tab", "DID_I").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun cp(c: Color) = ColorProvider(c)
private val CheckKey = ActionParameters.Key<String>("check")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

class DidWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        DidWidgets.did.value = withContext(Dispatchers.IO) { DidWidgets.load(context.applicationContext) }
        provideContent { val d by DidWidgets.did.collectAsState(); DidContent(context, d) }
    }
}

class DidWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DidWidget() }

@Composable
private fun DidContent(ctx: Context, d: WidgetDid?) {
    val open = actionStartActivity(DidWidgets.open(ctx))
    Column(GlanceModifier.fillMaxSize().cornerRadius(20.dp).background(Bg).padding(12.dp)) {
        Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
            Text("DID I?", style = TextStyle(color = cp(Green), fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
            if (d != null && d.status.isNotEmpty()) Text(DidI.progress(d.status), style = TextStyle(color = cp(Muted), fontSize = 12.sp))
        }
        Spacer(GlanceModifier.height(8.dp))
        when {
            d == null -> Text("Loading…", style = TextStyle(color = cp(Muted), fontSize = 13.sp))
            !d.hasVault -> Text("Open Flight Deck to pick your vault", style = TextStyle(color = cp(Muted), fontSize = 13.sp), modifier = GlanceModifier.clickable(open))
            d.status.isEmpty() -> Text("No checks yet", style = TextStyle(color = cp(Muted), fontSize = 13.sp), modifier = GlanceModifier.clickable(open))
            else -> Row(GlanceModifier.fillMaxWidth()) {
                d.status.take(4).forEachIndexed { i, s ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    val done = s.at != null
                    // A done check opens the app (no double stamps); an open one stamps straight from the home screen.
                    val action = if (done) open else actionRunCallback<DidStampAction>(actionParametersOf(CheckKey to s.check.name))
                    Column(
                        GlanceModifier.defaultWeight().cornerRadius(12.dp).background(if (done) GreenTint else Surface2).padding(vertical = 8.dp).clickable(action),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(if (done) "✓ ${s.check.name}" else s.check.name, maxLines = 1,
                            style = TextStyle(color = cp(if (done) Green else TextMain), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        Text(s.at?.format(HM) ?: "tap", style = TextStyle(color = cp(Muted), fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

class DidStampAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val name = parameters[CheckKey] ?: return
        val app = context.applicationContext
        val uri = AppPrefs(app).vault ?: return
        withContext(Dispatchers.IO) { runCatching { DidI.stamp(appVault(app, uri), name) } }
        DidWidgets.refresh(app)
    }
}

/**
 * Quick Settings tile: swipe down, tap, and the chosen check (Door by default, set in Did I?) is stamped
 * without unlocking the phone. The tile shows the time it was last done today.
 */
class DidTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun target(status: List<DidStatus>): DidStatus? {
        val want = AppPrefs(this).didTile
        return status.firstOrNull { it.check.name.equals(want, true) } ?: status.firstOrNull()
    }

    private fun show(s: DidStatus?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notify)
        tile.label = s?.check?.name ?: "Did I?"
        tile.subtitle = s?.at?.let { "Done ${it.format(HM)}" } ?: "Tap when done"
        tile.state = if (s?.at != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { show(target(withContext(Dispatchers.IO) { DidWidgets.load(applicationContext).status })) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val app = applicationContext
            val uri = AppPrefs(app).vault ?: return@launch
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val v = appVault(app, uri)
                    target(DidI.today(v))?.let { t -> DidI.stamp(v, t.check.name); target(DidI.today(v)) }
                }.getOrNull()
            }
            show(s)
            DidWidgets.changed(app)
        }
    }

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }
}
