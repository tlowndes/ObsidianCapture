package uk.tomlowndes.capture

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROW = 52.dp

/** Cards the phone Deck no longer shows (Read later and Jobs open from Vault chips instead). Desktop still has them. */
val PHONE_GONE = listOf(HomeCard.READ_LATER, HomeCard.JOBS)

/** Settings › Cards on home: drag ≡ to reorder, switch cards on and off. Saved to the vault config. */
@Composable
fun CardsScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf<DeckLayout?>(null) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW.toPx() }

    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        layout = withContext(Dispatchers.IO) { runCatching { DeckConfig.load(appVault(ctx, uri)) }.getOrDefault(DeckLayout()) }
            .let { l -> l.copy(order = l.order.filter { it !in PHONE_GONE } + l.order.filter { it in PHONE_GONE }) }
    }

    fun save(l: DeckLayout) {
        layout = l
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { DeckConfig.save(appVault(ctx, uri), l) } }
                .onFailure { snack.showSnackbar("Couldn't save: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        ScreenTitle("Cards on home") { TextButton(onClick = onBack) { Text("Close", color = Muted) } }
        val l = layout ?: return@Column
        // Phone-only cards sit at the end of the order and aren't listed, so drag indices still match.
        val lastShown = l.order.count { it !in PHONE_GONE } - 1
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1)) {
            l.order.take(lastShown + 1).forEachIndexed { i, card -> key(card) {
                if (i > 0) HorizontalDivider(color = Outline)
                val lifted = dragging == i
                Row(
                    Modifier.fillMaxWidth().height(ROW)
                        .graphicsLayer { if (lifted) { translationY = offset; shadowElevation = 12f } }
                        .background(if (lifted) Surface2 else Surface1)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "≡", fontSize = 20.sp, color = Muted,
                        modifier = Modifier.pointerInput(card) {
                            detectDragGestures(
                                onDragStart = { dragging = layout?.order?.indexOf(card); offset = 0f },
                                onDragEnd = { dragging = null; offset = 0f; layout?.let { save(it) } },
                                onDragCancel = { dragging = null; offset = 0f }
                            ) { change, drag ->
                                change.consume()
                                val cur = layout ?: return@detectDragGestures
                                val from = dragging ?: return@detectDragGestures
                                offset += drag.y
                                val to = when {
                                    offset > rowPx / 2 && from < lastShown -> from + 1
                                    offset < -rowPx / 2 && from > 0 -> from - 1
                                    else -> from
                                }
                                if (to != from) {
                                    layout = cur.moved(from, to)
                                    dragging = to
                                    offset -= (to - from) * rowPx
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    Text(card.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    AppSwitch(card !in l.hidden) { save(l.toggled(card)) }
                }
            } }
        }
        TextButton(onClick = { save(DeckLayout(order = HomeCard.values().filter { it !in PHONE_GONE } + PHONE_GONE, hidden = l.hidden)) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Reset order", color = Primary)
        }
    }
}
