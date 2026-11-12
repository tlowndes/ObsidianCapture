package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.ArticleDoc
import uk.tomlowndes.capture.MdBlock
import uk.tomlowndes.capture.MdBlocks
import uk.tomlowndes.capture.ReadRef
import uk.tomlowndes.capture.ReadStore
import uk.tomlowndes.capture.SavedArticle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Shelf(val label: String) { UNREAD("Unread"), READ("Read"), ARCHIVED("Archived") }

private fun SavedArticle.shelf() = when { ref.archived -> Shelf.ARCHIVED; read -> Shelf.READ; else -> Shelf.UNREAD }

private fun minutes(words: Int) = "${(words / 230).coerceAtLeast(1)} min"

/** Read later: the reading list beside the reader. J/K move through the list, E archives, M marks read. */
@Composable
fun ReadPane(state: AppState) {
    val scope = rememberCoroutineScope()
    var articles by remember { mutableStateOf<List<SavedArticle>?>(null) }
    var shelf by remember { mutableStateOf(Shelf.UNREAD) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<ReadRef?>(null) }
    var asking by remember { mutableStateOf<ReadRef?>(null) }
    val images = remember(state.vault) { mutableStateMapOf<String, Any>() }
    val keys = remember { FocusRequester() }
    // Progress through the open article, and whether you've answered "finished?" for it.
    val progress = remember { floatArrayOf(0f) }

    fun reload() = scope.launch {
        val list = state.io("Couldn't read articles") { state.readStore()!!.list() } ?: return@launch
        articles = list
    }
    LaunchedEffect(state.vault, state.changes) {
        reload()
        state.io { state.ideaStore()!!.imageIndex() }?.let { images.putAll(it) }
    }

    val all = articles.orEmpty()
    val shown = all.filter { it.shelf() == shelf }
        .filter { query.isBlank() || it.title.contains(query.trim(), true) || it.snippet.contains(query.trim(), true) }
    val current = all.firstOrNull { it.ref == open }

    // Opened from the Deck's Daily highlights: jump to its shelf and open it.
    LaunchedEffect(state.readOpen, articles) {
        val want = state.readOpen ?: return@LaunchedEffect
        val a = articles?.firstOrNull { it.ref == want || it.ref.file == want.file } ?: return@LaunchedEffect
        shelf = a.shelf(); query = ""; open = a.ref; state.readOpen = null
    }
    LaunchedEffect(shown.map { it.ref }) { if (open == null || shown.none { it.ref == open } && current == null) open = shown.firstOrNull()?.ref }

    fun change(msg: String? = null, block: ReadStore.() -> Unit) = scope.launch {
        state.io { state.readStore()!!.block() } ?: return@launch
        msg?.let(state::say)
        reload(); state.changed()
    }

    /** Opens [ref]; leaving an unread article you've read to the end asks whether you're done with it. */
    fun select(ref: ReadRef?) {
        val cur = current
        if (cur != null && ref != cur.ref && !cur.read && !cur.ref.archived && progress[0] > 0.9f) { asking = ref; return }
        open = ref
    }

    fun archive(a: SavedArticle) {
        val i = shown.indexOfFirst { it.ref == a.ref }
        val next = shown.getOrNull(i + 1) ?: shown.getOrNull(i - 1)
        state.prefs.setReadPos(a.ref.file, 0f)
        progress[0] = 0f
        open = next?.ref
        scope.launch {
            val moved = state.io { state.readStore()!!.archive(a.ref) } ?: return@launch
            reload(); state.changed()
            state.offerUndo("Archived") { state.io { state.readStore()!!.unarchive(moved) }; reload() }
        }
    }

    fun step(by: Int) {
        val i = shown.indexOfFirst { it.ref == open }
        shown.getOrNull((if (i < 0) 0 else i + by).coerceIn(0, (shown.size - 1).coerceAtLeast(0)))?.let { select(it.ref) }
    }

    Row(
        Modifier.fillMaxSize().focusRequester(keys).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || e.isCtrlPressed) return@onPreviewKeyEvent false
            // Letters only act when no text box has the focus (the search box takes them as typing).
            if (typingFocused) return@onPreviewKeyEvent false
            when (e.key) {
                Key.J -> { step(1); true }
                Key.K -> { step(-1); true }
                Key.E -> { current?.takeIf { !it.ref.archived }?.let(::archive); true }
                Key.M -> { current?.let { a -> change(if (a.read) "Marked unread" else "Marked read") { setRead(a.ref, !a.read) } }; true }
                else -> false
            }
        }
    ) {
        Column(Modifier.width(400.dp).fillMaxHeight().padding(horizontal = 20.dp, vertical = 26.dp)) {
            H1("Read later", 26)
            Pill(Shelf.values().map { s -> if (s == Shelf.UNREAD) "Unread ${all.count { it.shelf() == s }}" else s.label },
                shelf.ordinal, Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) { shelf = Shelf.values()[it]; open = null }
            TypingField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 12.dp), "🔍  Search articles")
            if (articles != null && shown.isEmpty()) Sub(if (query.isBlank()) "Nothing here." else "No articles match.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { "${it.ref.archived}/${it.ref.file}" }) { a -> ArticleCard(a, a.ref == open, state.prefs.readPos(a.ref.file)) { select(a.ref); keys.requestFocus() } }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Kbd("J"); Kbd("K"); Hint("next / previous ·"); Kbd("E"); Hint("archive ·"); Kbd("M"); Hint("mark read")
            }
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(Divider))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val a = current
            if (a == null) Sub("Pick an article to read.", Modifier.padding(36.dp))
            else Reader(state, a, images, progress,
                onToggleRead = { change(if (a.read) "Marked unread" else "Marked read") { setRead(a.ref, !a.read) } },
                onArchive = { archive(a) },
                onUnarchive = { change("Back in the inbox") { unarchive(a.ref) }; open = null })
        }
    }

    asking?.let { next ->
        val a = current
        DialogCard("Finished reading?", onDismiss = { asking = null }) {
            Sub(a?.title.orEmpty(), Modifier.padding(bottom = 16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Keep unread", Muted) { asking = null; progress[0] = 0f; open = next }
                TextAction("Archive") { asking = null; a?.let(::archive); open = next }
                PrimaryButton("Mark read") {
                    asking = null; progress[0] = 0f
                    a?.let { x -> state.prefs.setReadPos(x.ref.file, 0f); change { setRead(x.ref, true) } }
                    open = next
                }
            }
        }
    }
    LaunchedEffect(Unit) { keys.requestFocus() }
}

/** Set while a text box has the keyboard, so the reader's letter shortcuts stay out of the way. */
private var typingFocused by mutableStateOf(false)

@Composable
private fun TypingField(value: String, onChange: (String) -> Unit, modifier: Modifier, placeholder: String) {
    Field(value, onChange, modifier.onFocusChanged { typingFocused = it.hasFocus }, placeholder, shape = RoundedCornerShape(999.dp))
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 11.5.sp, color = Muted)

@Composable
private fun ArticleCard(a: SavedArticle, selected: Boolean, pos: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) Surface2 else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, Primary, shape) else Modifier)
            .hand().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!a.read && !a.ref.archived) Box(Modifier.padding(end = 7.dp).size(7.dp).clip(CircleShape).background(Primary))
            Text(a.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextMain, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
        }
        Text(listOfNotNull(ReadStore.host(a.url), minutes(a.words), pos.takeIf { it > 0.02f }?.let { "${(it * 100).toInt()}% read" }).joinToString(" · "),
            fontSize = 11.5.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Reader(
    state: AppState, a: SavedArticle, images: Map<String, Any>, progressOut: FloatArray,
    onToggleRead: () -> Unit, onArchive: () -> Unit, onUnarchive: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var doc by remember(a.ref) { mutableStateOf<ArticleDoc?>(null) }
    var size by remember { mutableStateOf(state.prefs.readerSize) }
    var serif by remember { mutableStateOf(state.prefs.readerSerif) }
    val highlights = remember(a.ref) { mutableStateListOf<String>() }
    val scroll = rememberScrollState()

    LaunchedEffect(a.ref) {
        val d = state.io("Couldn't open the article") { state.readStore()!!.load(a.ref) } ?: return@LaunchedEffect
        doc = d
        highlights.clear(); highlights.addAll(d.highlights)
        // Back to where you got to last time, once the text has been laid out.
        val at = state.prefs.readPos(a.ref.file)
        if (at > 0f) {
            repeat(30) { if (scroll.maxValue <= 0) withFrameNanos { } }
            scroll.scrollTo((scroll.maxValue * at).toInt())
        }
    }
    val progress = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
    progressOut[0] = progress
    DisposableEffect(a.ref) { onDispose { if (doc != null && !a.ref.archived) state.prefs.setReadPos(a.ref.file, progressOut[0].takeIf { it < 0.98f } ?: 0f) } }

    fun toggleHighlight(k: String) {
        val adding = k !in highlights
        scope.launch {
            state.io { state.readStore()!!.toggleHighlight(a.ref, k) } ?: return@launch
            if (adding) highlights.add(k) else highlights.remove(k)
            state.say(if (adding) "Highlighted" else "Highlight removed")
        }
    }

    val d = doc
    val blocks = remember(d) { d?.let { MdBlocks.parse(it.body) }.orEmpty() }
    val body = TextStyle(fontSize = size.sp, lineHeight = (size * 1.65f).sp, color = Color(0xFFD8DDE8),
        fontFamily = if (serif) FontFamily.Serif else FontFamily.Default)
    val blockKeys = remember(blocks) { blocks.mapNotNull { MdBlocks.highlightKey(it) }.toSet() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Surface2)) { Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Primary)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { a.url?.let { url -> TextAction("Open original ↗", Muted) { state.browse(url) } } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("A−", small = true) { size = (size - 1.5f).coerceAtLeast(13f); state.prefs.readerSize = size }
                Chip("A+", small = true) { size = (size + 1.5f).coerceAtMost(30f); state.prefs.readerSize = size }
                Chip(if (serif) "Serif" else "Sans", small = true) { serif = !serif; state.prefs.readerSerif = serif }
                if (a.ref.archived) Chip("↩ To inbox", small = true, onClick = onUnarchive)
                else {
                    Chip(if (a.read) "Mark unread" else "✓ Mark read", small = true, onClick = onToggleRead)
                    Chip("Archive", small = true, onClick = onArchive)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 680.dp).padding(horizontal = 36.dp).padding(top = 10.dp, bottom = 40.dp)) {
                    if (d != null) {
                        Text(d.title, fontSize = (size * 1.7f).sp, lineHeight = (size * 2.05f).sp, fontWeight = FontWeight.Bold, color = TextMain)
                        Text(listOfNotNull(ReadStore.host(d.url), "saved " + savedLabel(d.saved), minutes(a.words) + " read").joinToString(" · ") +
                            " · hover a paragraph and click 🖍 to highlight it",
                            fontSize = 12.5.sp, color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                blocks.forEach { b ->
                                    val key = MdBlocks.highlightKey(b)
                                    BlockWithHighlight(key, key != null && key in highlights, ::toggleHighlight) {
                                        MdBlockView(b, images, body, key != null && key in highlights) { state.browse(it) }
                                    }
                                }
                                val loose = highlights.filter { it !in blockKeys }
                                if (loose.isNotEmpty()) {
                                    Text("Highlights", fontSize = (size * 1.2f).sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.padding(top = 16.dp))
                                    loose.forEach { MdBlockView(MdBlock.Quote(it), images, body, true) { u -> state.browse(u) } }
                                }
                            }
                        }
                        if (blocks.isEmpty()) Sub("Only the link was saved for this one.")
                    }
                }
            }
        }
    }
}

/** A block with a 🖍 button in the margin while hovered (or always, when it's highlighted). */
@Composable
private fun BlockWithHighlight(key: String?, on: Boolean, toggle: (String) -> Unit, content: @Composable () -> Unit) {
    if (key == null) { content(); return }
    HoverRow(Modifier.fillMaxWidth()) { hovered ->
        Box(Modifier.fillMaxWidth()) {
            content()
            if (hovered || on) Text(
                if (on) "🖍✕" else "🖍", fontSize = 13.sp, color = if (on) Primary else Muted,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 0.dp).offset(x = 44.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Menu).hand().clickable { toggle(key) }.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

private fun savedLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK))

/** Inline markdown as styled text, with links tagged for clicking. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString { appendInline(s) }

private fun AnnotatedString.Builder.appendInline(s: String) {
    var at = 0
    for (m in MdBlocks.INLINE.findAll(s)) {
        append(s.substring(at, m.range.first))
        val g = m.groups
        when {
            m.value.startsWith("!") -> Unit
            g[1] != null -> {
                pushStringAnnotation("url", g[2]!!.value)
                withStyle(SpanStyle(color = Primary, textDecoration = TextDecoration.Underline)) { appendInline(g[1]!!.value) }
                pop()
            }
            g[3] != null || g[4] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline((g[3] ?: g[4])!!.value) }
            g[5] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Surface2)) { append(g[5]!!.value) }
            g[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[6]!!.value) }
            g[7] != null -> withStyle(SpanStyle(color = Primary)) { append((g[8] ?: g[7])!!.value) }
        }
        at = m.range.last + 1
    }
    append(s.substring(at))
}

@Composable
private fun Rich(text: String, style: TextStyle, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = remember(text) { inline(text) }
    ClickableText(s, modifier, style) { off -> s.getStringAnnotations("url", off, off).firstOrNull()?.let { onLink(it.item) } }
}

@Composable
private fun MdBlockView(b: MdBlock, images: Map<String, Any>, body: TextStyle, highlighted: Boolean, onLink: (String) -> Unit) {
    val tint = if (highlighted) Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF263A66)).padding(horizontal = 8.dp, vertical = 4.dp)
    else Modifier
    when (b) {
        is MdBlock.Heading -> Rich(b.text, body.copy(color = TextMain, fontFamily = FontFamily.Default,
            fontSize = body.fontSize * when (b.level) { 1 -> 1.4f; 2 -> 1.25f; 3 -> 1.1f; else -> 1f },
            lineHeight = body.lineHeight * 1.1f, fontWeight = FontWeight.Bold), onLink, Modifier.padding(top = 8.dp))
        is MdBlock.Para -> Rich(b.text, body, onLink, tint)
        is MdBlock.Item -> Row(Modifier.padding(start = (b.indent * 20).dp).then(tint)) {
            Text(b.marker, style = body.copy(color = Muted), modifier = Modifier.width(26.dp))
            Rich(b.text, body, onLink, Modifier.weight(1f))
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min).then(tint)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Primary))
            Rich(b.text, body.copy(color = Muted, fontStyle = FontStyle.Italic), onLink, Modifier.padding(start = 14.dp))
        }
        is MdBlock.Code -> Text(
            b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = TextMain, softWrap = false,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1)
                .horizontalScroll(rememberScrollState()).padding(12.dp)
        )
        is MdBlock.Image -> {
            val model: Any? = if (b.vault) images[b.src.substringAfterLast('/')] else b.src
            if (model != null) AsyncImage(model, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), maxSide = 1400, scale = ContentScale.FillWidth)
        }
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Outline))
    }
}
