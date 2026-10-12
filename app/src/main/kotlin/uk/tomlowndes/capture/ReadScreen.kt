package uk.tomlowndes.capture

import android.net.Uri
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
import androidx.compose.foundation.text.ClickableText
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
        withContext(Dispatchers.IO) { runCatching { IdeaStore(ctx, uri, prefs).imageIndex() }.getOrNull() }?.let { images.putAll(it) }
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
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).archive(ref) } }
            reload()
            r.onFailure { snack.showSnackbar(it.message ?: "Failed") }.onSuccess { moved ->
                val res = snack.showSnackbar("Archived", actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (res == SnackbarResult.ActionPerformed) change { unarchive(moved) }
            }
        }
    }

    if (open != null) ArticleView(vault, snack, open, images, scope,
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
                    filter == ReadFilter.UNREAD -> "All caught up. Share a page to Vault Capture to save it here."
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleCard(
    a: SavedArticle, onClick: () -> Unit, onSetRead: (SavedArticle, Boolean) -> Unit,
    onArchive: (SavedArticle) -> Unit, onUnarchive: (SavedArticle) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1)
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(14.dp),
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
                    fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
        DropdownMenu(menu, { menu = false }, modifier = Modifier.background(Surface2)) {
            if (a.ref.archived) {
                DropdownMenuItem(text = { Text("Move back to inbox") }, onClick = { menu = false; onUnarchive(a) })
            } else {
                DropdownMenuItem(text = { Text(if (a.read) "Mark unread" else "Mark read") },
                    onClick = { menu = false; onSetRead(a, !a.read) })
                DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; onArchive(a) })
            }
        }
    }
}

// ---------------------------------------------------------------- reader

@Composable
private fun ArticleView(
    vault: Uri?, snack: SnackbarHostState, ref: ReadRef, images: Map<String, Uri>, scope: CoroutineScope,
    onArchive: () -> Unit, onUnarchive: () -> Unit, onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var doc by remember { mutableStateOf<ArticleDoc?>(null) }
    var read by remember { mutableStateOf(true) }

    LaunchedEffect(ref, vault) {
        val uri = vault ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).load(ref) } }
        r.onFailure { snack.showSnackbar("Couldn't open article: ${it.message}"); onBack() }
        val d = r.getOrNull() ?: return@LaunchedEffect
        doc = d
        read = true
        // Opening an article counts as reading it.
        if (!d.read) withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).setRead(ref, true) } }
    }

    fun toggleRead() {
        val uri = vault ?: return
        val now = !read
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ReadStore(ctx, uri).setRead(ref, now) } }
                .onSuccess { read = now; if (!now) onBack() }.onFailure { snack.showSnackbar(it.message ?: "Failed") }
        }
    }

    fun openLink(url: String) { runCatching { uriHandler.openUri(url) } }

    val d = doc
    val blocks = remember(d) { d?.let { MdBlocks.parse(it.body) }.orEmpty() }
    val words = remember(d) { d?.body?.sumOf { l -> l.split(' ').count { it.isNotBlank() } } ?: 0 }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 24.sp, color = TextMain) }
            Text(listOfNotNull(ReadStore.host(d?.url), d?.let { minutes(words) + " read" }).joinToString(" · "),
                fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            if (d != null) {
                Text(d.title, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp))
                Text("Saved ${savedLabel(d.saved)}" + if (ref.archived) " · archived" else "",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    blocks.forEach { MdBlockView(it, images, ::openLink) }
                }
                if (blocks.isEmpty()) Text("Only the link was saved for this one.", color = Muted)
                Box(Modifier.height(32.dp))
            }
        }
        HorizontalDivider(color = Outline)
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF191623)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            if (ref.archived) {
                BarButton("↩", "To inbox", onUnarchive)
            } else {
                BarButton(if (read) "○" else "●", if (read) "Mark unread" else "Mark read", ::toggleRead)
                BarButton("🗄", "Archive", onArchive)
            }
            d?.url?.let { url -> BarButton("↗", "Original") { openLink(url) } }
        }
    }
}

@Composable
private fun BarButton(icon: String, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 18.sp, color = Primary)
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        }
    }
}

// ---------------------------------------------------------------- markdown

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Item(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Image(val alt: String, val src: String, val vault: Boolean) : MdBlock
    data object Rule : MdBlock
}

/** Just enough markdown for saved articles: headings, paragraphs, lists, quotes, code, images and rules. */
private object MdBlocks {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val IMAGE = Regex("^!\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)$")
    private val LINKED_IMAGE = Regex("^\\[!\\[([^\\]]*)]\\((\\S+?)\\)]\\(\\S+\\)$")
    private val EMBED = Regex("^!\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?]]$")
    private val RULE = Regex("^([-*_])(\\s*\\1){2,}$")

    fun parse(lines: List<String>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        val quote = mutableListOf<String>()
        fun flush() {
            if (para.isNotBlank()) out.add(MdBlock.Para(para.toString().trim()))
            para.clear()
            if (quote.isNotEmpty()) out.add(MdBlock.Quote(quote.joinToString("\n").trim()))
            quote.clear()
        }
        var i = 0
        while (i < lines.size) {
            val raw = lines[i].trimEnd()
            val line = raw.trim()
            i++
            if (line.startsWith("```")) {
                flush()
                val code = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("```")) code.add(lines[i++].trimEnd())
                i++
                out.add(MdBlock.Code(code.joinToString("\n")))
                continue
            }
            if (line.isEmpty()) { flush(); continue }
            if (line.startsWith(">")) {
                if (para.isNotEmpty()) flush()
                quote.add(line.removePrefix(">").trim())
                continue
            }
            if (quote.isNotEmpty()) flush()
            val heading = HEADING.matchEntire(line)
            val image = IMAGE.matchEntire(line) ?: LINKED_IMAGE.matchEntire(line)
            val embed = EMBED.matchEntire(line)
            val item = ITEM.matchEntire(raw)
            val block: MdBlock? = when {
                heading != null -> MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
                RULE.matches(line) -> MdBlock.Rule
                image != null -> MdBlock.Image(image.groupValues[1], image.groupValues[2], vault = false)
                embed != null -> MdBlock.Image("", embed.groupValues[1].trim(), vault = true)
                item != null -> {
                    val m = item.groupValues[2]
                    val marker = if (m[0].isDigit()) m.trimEnd(')').trimEnd('.') + "." else "•"
                    MdBlock.Item(item.groupValues[1].replace("\t", "  ").length / 2, marker, item.groupValues[3])
                }
                else -> null
            }
            if (block != null) { flush(); out.add(block); continue }
            // A line ending in two spaces is a hard break; otherwise lines of a paragraph join with a space.
            if (para.isNotEmpty() && !para.endsWith("\n")) para.append(' ')
            para.append(line)
            if (lines[i - 1].endsWith("  ")) para.append('\n')
        }
        flush()
        return out
    }

    private val INLINE = Regex(
        "!\\[[^\\]]*]\\([^)]*\\)" +                       // inline image (dropped)
            "|\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)" +         // 1,2 link
            "|\\*\\*(.+?)\\*\\*|__(.+?)__" +              // 3,4 bold
            "|`([^`]+)`" +                                 // 5 code
            "|\\*([^*\\s](?:[^*]*[^*\\s])?)\\*" +          // 6 italic
            "|\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]"       // 7,8 wikilink
    )

    fun inline(s: String): AnnotatedString = buildAnnotatedString { appendInline(s) }

    private fun AnnotatedString.Builder.appendInline(s: String) {
        var at = 0
        for (m in INLINE.findAll(s)) {
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

private val BodyStyle = TextStyle(fontSize = 16.5.sp, lineHeight = 26.sp, color = TextMain)

@Composable
private fun Rich(text: String, style: TextStyle, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = remember(text) { MdBlocks.inline(text) }
    @Suppress("DEPRECATION")
    ClickableText(s, modifier, style = style) { off ->
        s.getStringAnnotations("url", off, off).firstOrNull()?.let { onLink(it.item) }
    }
}

@Composable
private fun MdBlockView(b: MdBlock, images: Map<String, Uri>, onLink: (String) -> Unit) {
    when (b) {
        is MdBlock.Heading -> Rich(b.text, BodyStyle.copy(
            fontSize = when (b.level) { 1 -> 23.sp; 2 -> 20.sp; 3 -> 18.sp; else -> 16.5.sp },
            lineHeight = 28.sp, fontWeight = FontWeight.Bold), onLink, Modifier.padding(top = 8.dp))
        is MdBlock.Para -> Rich(b.text, BodyStyle, onLink)
        is MdBlock.Item -> Row(Modifier.padding(start = (b.indent * 18).dp)) {
            Text(b.marker, style = BodyStyle.copy(color = Muted), modifier = Modifier.width(24.dp))
            Rich(b.text, BodyStyle, onLink, Modifier.weight(1f))
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Primary))
            Rich(b.text, BodyStyle.copy(color = Muted, fontStyle = FontStyle.Italic), onLink, Modifier.padding(start = 14.dp))
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
