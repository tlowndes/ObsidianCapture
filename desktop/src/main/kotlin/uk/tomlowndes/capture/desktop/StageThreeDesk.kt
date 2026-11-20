package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.tomlowndes.capture.Card
import uk.tomlowndes.capture.CardDeck
import uk.tomlowndes.capture.FlightNotes
import uk.tomlowndes.capture.Flashcards
import uk.tomlowndes.capture.Grade
import uk.tomlowndes.capture.Habits
import uk.tomlowndes.capture.MonthPatterns
import uk.tomlowndes.capture.Pattern
import uk.tomlowndes.capture.Patterns
import uk.tomlowndes.capture.VaultIo
import uk.tomlowndes.capture.VaultTasks
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

// ---- What helps you (wellbeing-v1) ----

/** Last month's patterns: the saved note, else asks Claude once and saves it. Blocking. */
internal fun loadOrMakePatterns(state: AppState, today: LocalDate): MonthPatterns? {
    val v = state.vault ?: return null
    val m = Patterns.lastMonth(today)
    Patterns.load(v, m)?.let { return it }
    if (!hasClaude(state)) return null
    val habits = runCatching { Habits.load(v) }.getOrNull()
    val focus = runCatching { FlightNotes.loadSince(v, m.atDay(1)) }.getOrDefault(emptyList())
    val done = runCatching { state.writer()!!.listTaskLists().done }.getOrDefault(emptyList())
        .filter { it.done?.let { d -> YearMonth.from(d) == m } == true }
    val p = Patterns.parse(deskAsk(state, Patterns.facts(m, habits, focus, done), Patterns.SYSTEM), m) ?: return null
    if (p.patterns.isNotEmpty()) Patterns.save(v, p, today)
    return p.takeIf { it.patterns.isNotEmpty() }
}

@Composable
internal fun HelpsCardDesk(p: MonthPatterns, onAll: () -> Unit, onClose: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(shape).background(Surface1).border(1.dp, Purple.copy(alpha = .5f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("What helped in ${Patterns.monthName(p.month)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
            TextAction("See all ${p.patterns.size}", onClick = onAll)
            TextAction("✕", Muted, onClick = onClose)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            p.patterns.take(3).forEach { pt -> Box(Modifier.weight(1f)) { PatternLine(pt) } }
        }
    }
}

@Composable
private fun PatternLine(pt: Pattern) {
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(pt.emoji.ifEmpty { if (pt.helps) "↑" else "↓" }, fontSize = 17.sp)
        Column(Modifier.weight(1f)) {
            Text(pt.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (pt.helps) TextMain else Amber)
            Text(pt.detail, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
internal fun HelpsDialog(state: AppState, p: MonthPatterns, onDone: () -> Unit) {
    WideDialog(Patterns.monthName(p.month), p.summary.ifEmpty { null }, onDone) {
        if (p.helps.isNotEmpty()) { MonoHead("Helps"); p.helps.forEach { PatternLine(it) } }
        if (p.drags.isNotEmpty()) { Box(Modifier.height(12.dp)); MonoHead("Drags", Amber); p.drags.forEach { PatternLine(it) } }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Saved in ${Patterns.path(p.month)}", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
            TextAction("Open in Obsidian") { state.openInObsidian(Patterns.path(p.month)) }
        }
    }
}

@Composable
private fun MonoHead(text: String, colour: androidx.compose.ui.graphics.Color = Primary) =
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, color = colour, modifier = Modifier.padding(top = 4.dp))

// ---- Flashcards (growth-v1) ----

/** Today's cards: Space flips, 1 / 2 / 3 grade (Again, Hard, Got it). Decks without cards can ask Claude for 5. */
@Composable
internal fun LearnDialog(state: AppState, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var decks by remember { mutableStateOf<List<CardDeck>?>(null) }
    var queue by remember { mutableStateOf<List<Card>>(emptyList()) }
    var flipped by remember { mutableStateOf(false) }
    var making by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val keys = remember { FocusRequester() }
    LaunchedEffect(refresh) {
        val v = state.vault ?: return@LaunchedEffect
        val ds = withContext(Dispatchers.IO) { runCatching { Flashcards.decks(v, File(state.prefs.cacheDir, "vault-index.json")) }.getOrDefault(emptyList()) }
        decks = ds; queue = Flashcards.dueToday(ds, today); flipped = false
        runCatching { keys.requestFocus() }
    }
    fun grade(c: Card, g: Grade) {
        val v = state.vault ?: return
        queue = queue.drop(1) + if (g == Grade.AGAIN) listOf(c) else emptyList()
        flipped = false
        scope.launch { state.io("Couldn't save the card") { Flashcards.save(v, Flashcards.review(c, g, today)) } }
    }
    fun make(d: CardDeck) {
        val v = state.vault ?: return
        making = d.title
        scope.launch {
            val n = state.io("Couldn't make cards") {
                val cards = Flashcards.cardsFrom(deskAsk(state, VaultIo.read(v, d.path).take(12_000), Flashcards.SYSTEM), d.path)
                require(cards.isNotEmpty()) { "Claude didn't send back any cards" }
                Flashcards.addCards(v, d.path, cards); cards.size
            }
            making = null
            if (n != null) { state.say("Made $n cards from ${d.title}"); refresh++ }
        }
    }
    WideDialog("Learn", "${queue.size} today · ${Flashcards.minutes(queue.size)} min · Space flips, 1 2 3 grade", onDone) {
        val c = queue.firstOrNull()
        Column(
            Modifier.fillMaxWidth().focusRequester(keys).focusable().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || c == null) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Spacebar -> { flipped = !flipped; true }
                    flipped && e.key == Key.One -> { grade(c, Grade.AGAIN); true }
                    flipped && e.key == Key.Two -> { grade(c, Grade.HARD); true }
                    flipped && e.key == Key.Three -> { grade(c, Grade.GOT_IT); true }
                    else -> false
                }
            }
        ) {
            val ds = decks ?: run { Working("Reading your #learn notes…"); return@Column }
            if (c != null) {
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier.fillMaxWidth().height(240.dp).clip(shape).background(Surface1).border(1.dp, Outline, shape)
                        .hand().clickable { flipped = !flipped }.padding(22.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("FROM: ${VaultTasks.noteName(c.path).uppercase()}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    Text(if (flipped) c.a else c.q, fontSize = 20.sp, color = TextMain, textAlign = TextAlign.Center,
                        fontWeight = if (flipped) FontWeight.Normal else FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    if (!flipped) Text("Space to flip", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 14.dp))
                }
                if (flipped) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    Grade.values().forEachIndexed { i, g ->
                        if (g == Grade.GOT_IT) PrimaryButton(g.label, hint = "${i + 1}") { grade(c, g) }
                        else TextAction("${g.label}  ${i + 1}", if (g == Grade.AGAIN) Amber else Muted) { grade(c, g) }
                    }
                }
            } else Text(if (ds.isEmpty()) "Tag any note #learn and come back here." else "All done for today. 🎉", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(vertical = 18.dp))
            if (ds.isNotEmpty()) {
                MonoHead("Decks")
                ds.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontSize = 14.sp, color = TextMain)
                            Text(if (d.cards.isEmpty()) "no cards yet" else "${d.cards.size} cards · ${d.due(today)} due", fontSize = 12.sp, color = Muted)
                        }
                        if (d.cards.isEmpty() && hasClaude(state)) TextAction(if (making == d.title) "Making…" else "Make 5 cards") { if (making == null) make(d) }
                    }
                }
            }
        }
    }
}



/** Cards due today, or null when there are no #learn notes. Blocking. */
internal fun dueCardCount(state: AppState): Int? {
    val v = state.vault ?: return null
    return runCatching { Flashcards.decks(v, File(state.prefs.cacheDir, "vault-index.json")) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { Flashcards.dueToday(it, LocalDate.now()).size }
}

