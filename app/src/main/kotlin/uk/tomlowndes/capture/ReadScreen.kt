package uk.tomlowndes.capture

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private enum class ReadFilter(val label: String) { UNREAD("Unread"), READ("Read"), ARCHIVED("Archived") }

private fun SavedArticle.matches(f: ReadFilter) = when (f) {
    ReadFilter.UNREAD -> !ref.archived && !read
    ReadFilter.READ -> !ref.archived && read
    ReadFilter.ARCHIVED -> ref.archived
}

private fun savedLabel(millis: Long) = dayChip(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())
private fun minutes(words: Int) = "${maxOf(1, (words + 110) / 220)} min"

@Composable
fun ReadScreen(vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, open: ReadRef?, onOpen: (ReadRef?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var articles by remember { mutableStateOf<List<SavedArticle>?>(null) }
    var filter by remember { mutableStateOf(ReadFilter.UNREAD) }
    val images = remember(vault) { mutableStateMapOf<String, Uri>() }

    fun reload() {
        val uri = vault ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).list() } }
            r.onSuccess { articles = it }.onFailure { snack.showSnackbar("Couldn't read articles: ${it.message}") }
        }
    }
    LaunchedEffect(vault, open == null) { if (open == null) reload() }
    LaunchedEffect(vault) {
        val uri = vault ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).imageUris() }.getOrNull() }?.let { images.putAll(it) }
    }

    /** Runs a store change off the main thread; reports failures and refreshes the list. */
    fun change(block: ReadStore.() -> Unit) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).block() } }
                .onFailure { snack.showSnackbar(it.message ?: "Failed") }
            reload()
        }
    }

    fun archive(ref: ReadRef) {
        val uri = vault ?: return
        prefs.setScrollPos(ref.file, 0)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).archive(ref) } }
            reload()
            r.onFailure { snack.showSnackbar(it.message ?: "Failed") }.onSuccess { moved ->
                val res = snack.showSnackbar("Archived", actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (res == SnackbarResult.ActionPerformed) change { unarchive(moved) }
            }
        }
    }

    if (open != null) ArticleView(vault, prefs, snack, open, images, scope,
        onArchive = { onOpen(null); archive(open) },
        onUnarchive = { onOpen(null); change { unarchive(open) } },
        onBack = { onOpen(null) })
    else ReadList(vault, articles, filter, { filter = it }, onOpen,
        onSetRead = { a, read -> change { setRead(a.ref, read) } },
        onArchive = { archive(it.ref) },
        onUnarchive = { change { unarchive(it.ref) } })
}

// ---------------------------------------------------------------- list

@Composable
private fun ReadList(
    vault: Uri?, articles: List<SavedArticle>?, filter: ReadFilter, onFilter: (ReadFilter) -> Unit,
    onOpen: (ReadRef) -> Unit, onSetRead: (SavedArticle, Boolean) -> Unit,
    onArchive: (SavedArticle) -> Unit, onUnarchive: (SavedArticle) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val all = articles.orEmpty()
    val unread = all.count { it.matches(ReadFilter.UNREAD) }
    val shown = all.filter {
        it.matches(filter) && (query.isBlank() || it.title.contains(query, true) || it.snippet.contains(query, true) ||
            ReadStore.host(it.url).orEmpty().contains(query, true))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(top = 8.dp)) {
            ScreenTitle("Read", "${VaultWriter.RIL_DIR} · $unread unread")
        }
        PillSwitch(ReadFilter.values().map { it.label }, filter.ordinal) { onFilter(ReadFilter.values()[it]) }
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("🔍  Search articles") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), shape = CircleShape, colors = fieldColors()
        )
        when {
            vault == null -> Text("Pick your vault in Settings first.", color = Muted)
            articles != null && shown.isEmpty() -> Text(
                when {
                    query.isNotBlank() -> "Nothing matches."
                    filter == ReadFilter.UNREAD -> "All caught up. Share a page to Flight Deck to save it here."
                    filter == ReadFilter.READ -> "Nothing read yet."
                    else -> "Nothing archived yet."
                }, color = Muted
            )
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { "${it.ref.archived}/${it.ref.file}" }) { a ->
                ArticleCard(a, { onOpen(a.ref) }, onSetRead, onArchive, onUnarchive)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ArticleCard(
    a: SavedArticle, onClick: () -> Unit, onSetRead: (SavedArticle, Boolean) -> Unit,
    onArchive: (SavedArticle) -> Unit, onUnarchive: (SavedArticle) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.EndToStart -> { if (a.ref.archived) onUnarchive(a) else onArchive(a); true }
            SwipeToDismissBoxValue.StartToEnd -> { onSetRead(a, !a.read); false }
            SwipeToDismissBoxValue.Settled -> false
        }
    })
    SwipeToDismissBox(
        swipe, enableDismissFromStartToEnd = !a.ref.archived,
        backgroundContent = {
            val left = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val label = when {
                left -> if (a.read) "○  Mark unread" else "✓  Mark read"
                a.ref.archived -> "To inbox  ↩"
                else -> "Archive  🗄"
            }
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(if (left) Surface2 else TaskTint)
                    .padding(horizontal = 22.dp),
                contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd
            ) { Text(label, fontWeight = FontWeight.SemiBold, color = TextMain) }
        }
    ) { CardBody(a, menu, { menu = it }, onClick, onSetRead, onArchive, onUnarchive) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardBody(
    a: SavedArticle, menu: Boolean, setMenu: (Boolean) -> Unit, onClick: () -> Unit, onSetRead: (SavedArticle, Boolean) -> Unit,
    onArchive: (SavedArticle) -> Unit, onUnarchive: (SavedArticle) -> Unit
) {
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1)
                .combinedClickable(onClick = onClick, onLongClick = { setMenu(true) }).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape)
                .background(if (!a.read && !a.ref.archived) Primary else Color.Transparent))
            Column(Modifier.weight(1f)) {
                Text(a.title, fontSize = 15.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (a.read) FontWeight.Medium else FontWeight.Bold, color = if (a.read) Muted else TextMain)
                if (a.snippet.isNotBlank()) Text(a.snippet, fontSize = 12.5.sp, color = Muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Text(
                    listOfNotNull(ReadStore.host(a.url), savedLabel(a.saved), minutes(a.words)).joinToString(" · "),
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
        DropdownMenu(menu, { setMenu(false) }, modifier = Modifier.background(Surface2)) {
            if (a.ref.archived) {
                DropdownMenuItem(text = { Text("Move back to inbox") }, onClick = { setMenu(false); onUnarchive(a) })
            } else {
                DropdownMenuItem(text = { Text(if (a.read) "Mark unread" else "Mark read") },
                    onClick = { setMenu(false); onSetRead(a, !a.read) })
                DropdownMenuItem(text = { Text("Archive") }, onClick = { setMenu(false); onArchive(a) })
            }
        }
    }
}

// ---------------------------------------------------------------- reader

@Composable
private fun ArticleView(
    vault: Uri?, prefs: AppPrefs, snack: SnackbarHostState, ref: ReadRef, images: Map<String, Uri>, scope: CoroutineScope,
    onArchive: () -> Unit, onUnarchive: () -> Unit, onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var doc by remember { mutableStateOf<ArticleDoc?>(null) }
    var read by remember { mutableStateOf(true) }
    var decided by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var textMenu by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(prefs.readerSize) }
    var serif by remember { mutableStateOf(prefs.readerSerif) }
    val highlights = remember(ref) { mutableStateListOf<String>() }
    val scroll = rememberScrollState()
    val keepPlace = remember { booleanArrayOf(true) }

    LaunchedEffect(ref, vault) {
        val uri = vault ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).load(ref) } }
        r.onFailure { snack.showSnackbar("Couldn't open article: ${it.message}"); onBack() }
        val d = r.getOrNull() ?: return@LaunchedEffect
        doc = d
        read = d.read
        highlights.clear(); highlights.addAll(d.highlights)
        // Back to where you got to last time, once the text has been laid out.
        val y = prefs.scrollPos(ref.file)
        if (y > 0) {
            repeat(30) { if (scroll.maxValue < y) withFrameNanos { } }
            scroll.scrollTo(y)
        }
    }
    DisposableEffect(ref) {
        onDispose { if (keepPlace[0] && doc != null) prefs.setScrollPos(ref.file, scroll.value) }
    }

    fun store(block: ReadStore.() -> Unit, after: () -> Unit = {}) {
        val uri = vault ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).block() } }
                .onSuccess { after() }.onFailure { snack.showSnackbar(it.message ?: "Failed") }
        }
    }

    fun toggleRead() {
        val now = !read
        decided = true
        store({ setRead(ref, now) }) { read = now }
    }

    /** Finished with it: forget the reading position and leave. */
    fun finish(then: () -> Unit) { keepPlace[0] = false; prefs.setScrollPos(ref.file, 0); then() }

    val needsAnswer = doc != null && !read && !decided && !ref.archived
    fun leave() { if (needsAnswer) asking = true else onBack() }
    BackHandler(enabled = needsAnswer) { asking = true }

    fun openLink(url: String) { runCatching { uriHandler.openUri(url) } }

    fun toggleHighlight(k: String) {
        val adding = k !in highlights
        store({ toggleHighlight(ref, k) }) {
            if (adding) highlights.add(k) else highlights.remove(k)
            scope.launch { snack.showSnackbar(if (adding) "Highlighted" else "Highlight removed") }
        }
    }

    val d = doc
    val blocks = remember(d) { d?.let { MdBlocks.parse(it.body) }.orEmpty() }
    val words = remember(d) { d?.body?.sumOf { l -> l.split(' ').count { it.isNotBlank() } } ?: 0 }
    val body = TextStyle(
        fontSize = size.sp, lineHeight = (size * 1.6f).sp, color = TextMain,
        fontFamily = if (serif) FontFamily.Serif else FontFamily.Default
    )
    val blockKeys = remember(blocks) { blocks.mapNotNull { MdBlocks.highlightKey(it) }.toSet() }
    val haptics = LocalHapticFeedback.current

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::leave) { Text("←", fontSize = 24.sp, color = TextMain) }
            Text(listOfNotNull(ReadStore.host(d?.url), d?.let { minutes(words) + " read" }).joinToString(" · "),
                fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { textMenu = true }) { Text("Aa", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain) }
                DropdownMenu(textMenu, { textMenu = false }, modifier = Modifier.background(Surface2)) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { size = (size - 1.5f).coerceAtLeast(13f); prefs.readerSize = size }) {
                            Text("A−", fontSize = 14.sp, color = TextMain)
                        }
                        Text("${size.toInt()}", color = Muted, fontSize = 13.sp)
                        TextButton(onClick = { size = (size + 1.5f).coerceAtMost(27f); prefs.readerSize = size }) {
                            Text("A+", fontSize = 19.sp, color = TextMain)
                        }
                    }
                    DropdownMenuItem(text = { Text("Sans", fontFamily = FontFamily.Default, color = if (!serif) Primary else TextMain) },
                        onClick = { serif = false; prefs.readerSerif = false })
                    DropdownMenuItem(text = { Text("Serif", fontFamily = FontFamily.Serif, color = if (serif) Primary else TextMain) },
                        onClick = { serif = true; prefs.readerSerif = true })
                }
            }
        }
        // Reading progress.
        val progress = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(Surface1)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Primary))
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 22.dp)) {
            if (d != null) {
                Text(d.title, fontSize = (size * 1.55f).sp, lineHeight = (size * 1.9f).sp, fontWeight = FontWeight.Bold,
                    fontFamily = body.fontFamily, modifier = Modifier.padding(top = 10.dp))
                Text("Saved ${savedLabel(d.saved)}" + (if (ref.archived) " · archived" else "") + " · long-press a paragraph to highlight",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    blocks.forEach { b ->
                        val key = MdBlocks.highlightKey(b)
                        val onLong: (() -> Unit)? = if (key == null) null else {
                            { haptics.performHapticFeedback(HapticFeedbackType.LongPress); toggleHighlight(key) }
                        }
                        MdBlockView(b, images, body, key != null && key in highlights, ::openLink, onLong)
                    }
                }
                val loose = highlights.filter { it !in blockKeys }
                if (loose.isNotEmpty()) {
                    Text("Highlights", fontSize = (size * 1.2f).sp, fontWeight = FontWeight.Bold, color = TextMain,
                        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        loose.forEach { MdBlockView(MdBlock.Quote(it), images, body, true, ::openLink, null) }
                    }
                }
                if (blocks.isEmpty()) Text("Only the link was saved for this one.", color = Muted)
                Box(Modifier.height(32.dp))
            }
        }
        HorizontalDivider(color = Outline)
        Row(
            Modifier.fillMaxWidth().background(NavBg).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            if (ref.archived) {
                BarButton("↩", "To inbox") { finish(onUnarchive) }
            } else {
                BarButton(if (read) "○" else "●", if (read) "Mark unread" else "Mark read", ::toggleRead)
                BarButton("🗄", "Archive") { finish(onArchive) }
            }
            d?.url?.let { url -> BarButton("↗", "Original") { openLink(url) } }
        }
    }

    if (asking) AlertDialog(
        onDismissRequest = { asking = false }, containerColor = Surface1,
        title = { Text("Finished reading?") },
        text = { Text(d?.title.orEmpty(), color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        confirmButton = {
            Row {
                TextButton(onClick = { asking = false; finish(onArchive) }) { Text("Archive", color = Primary) }
                TextButton(onClick = {
                    asking = false
                    finish { store({ setRead(ref, true) }); onBack() }
                }) { Text("Mark read", color = Primary, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = { TextButton(onClick = { asking = false; onBack() }) { Text("Keep unread", color = Muted) } }
    )
}

@Composable
private fun BarButton(icon: String, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 18.sp, color = Primary)
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        }
    }
}

// ---------------------------------------------------------------- markdown (blocks are parsed by MdBlocks in core)

private object MdInline {
    fun inline(s: String): AnnotatedString = buildAnnotatedString { appendInline(s) }

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
}

private val HighlightTint = Color(0x33F5C451)

/** Inline markdown text: tap a link to open it, long-press anywhere for [onLongPress]. */
@Composable
private fun Rich(
    text: String, style: TextStyle, onLink: (String) -> Unit, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null
) {
    val s = remember(text) { MdInline.inline(text) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        s, style = style, onTextLayout = { layout = it },
        modifier = modifier.pointerInput(s, onLongPress) {
            detectTapGestures(
                onLongPress = if (onLongPress == null) null else { _ -> onLongPress() },
                onTap = { pos ->
                    val off = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                    s.getStringAnnotations("url", off, off).firstOrNull()?.let { onLink(it.item) }
                }
            )
        }
    )
}

@Composable
private fun MdBlockView(
    b: MdBlock, images: Map<String, Uri>, body: TextStyle, highlighted: Boolean,
    onLink: (String) -> Unit, onLongPress: (() -> Unit)?
) {
    val tint = if (highlighted) Modifier.clip(RoundedCornerShape(8.dp)).background(HighlightTint).padding(horizontal = 8.dp, vertical = 4.dp)
    else Modifier
    when (b) {
        is MdBlock.Heading -> Rich(b.text, body.copy(
            fontSize = body.fontSize * when (b.level) { 1 -> 1.4f; 2 -> 1.22f; 3 -> 1.1f; else -> 1f },
            lineHeight = body.lineHeight * 1.1f, fontWeight = FontWeight.Bold), onLink, Modifier.padding(top = 8.dp))
        is MdBlock.Para -> Rich(b.text, body, onLink, tint, onLongPress)
        is MdBlock.Item -> Row(Modifier.padding(start = (b.indent * 18).dp).then(tint)) {
            Text(b.marker, style = body.copy(color = Muted), modifier = Modifier.width(24.dp))
            Rich(b.text, body, onLink, Modifier.weight(1f), onLongPress)
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min).then(tint)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Primary))
            Rich(b.text, body.copy(color = Muted, fontStyle = FontStyle.Italic), onLink, Modifier.padding(start = 14.dp), onLongPress)
        }
        is MdBlock.Code -> Text(
            b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = TextMain, softWrap = false,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1)
                .horizontalScroll(rememberScrollState()).padding(12.dp)
        )
        is MdBlock.Image -> {
            val model: Any? = if (b.vault) images[b.src.substringAfterLast('/')] else b.src
            if (model != null) AsyncImage(
                model, b.alt.ifBlank { null }, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillWidth
            )
        }
        MdBlock.Rule -> HorizontalDivider(color = Outline, modifier = Modifier.padding(vertical = 6.dp))
    }
}
