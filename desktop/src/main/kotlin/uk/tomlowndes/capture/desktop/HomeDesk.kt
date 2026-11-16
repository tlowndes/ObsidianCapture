package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.Chore
import uk.tomlowndes.capture.Chores
import uk.tomlowndes.capture.PhoneBrain
import uk.tomlowndes.capture.RecipeNote
import uk.tomlowndes.capture.Recipes
import uk.tomlowndes.capture.Snap
import uk.tomlowndes.capture.SnapMode
import uk.tomlowndes.capture.SnapResult
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Base64
import javax.imageio.ImageIO

// ---- Home: chores that decay (home-life-v1) ----

/** The bar colour for a chore: green when fresh, blue filling, amber near due, red past due. */
internal fun choreColour(fill: Double): Color = when {
    fill >= Chores.OVERDUE -> Warn
    fill >= 0.75 -> Amber
    fill >= 0.4 -> Primary
    else -> TaskGreen
}

@Composable
internal fun ChoreBar(fill: Double, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(Outline)) {
        Box(Modifier.fillMaxWidth(fill.coerceIn(0.03, 1.0).toFloat()).height(8.dp).clip(RoundedCornerShape(8.dp)).background(choreColour(fill)))
    }
}

/** Marks [c] done today, with Undo. Shared by the Home page and the Deck card. */
internal fun choreDone(state: AppState, c: Chore, after: () -> Unit) = state.scope.launch {
    val v = state.vault ?: return@launch
    val today = LocalDate.now()
    val before = state.io("Couldn't save it") { Chores.markDone(v, c, today) } ?: return@launch
    after(); state.changed()
    state.offerUndo("${c.label} done") { state.io { Chores.put(v, c.name, before) }; after(); state.changed() }
}

/** The Home page (home-life-v1): the same bars as the phone. Click = done today, right-click = edit. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomePane(state: AppState) {
    val scope = rememberCoroutineScope()
    var chores by remember { mutableStateOf<List<Chore>?>(null) }
    var editing by remember { mutableStateOf<Chore?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    fun reload() = scope.launch { state.vault?.let { v -> chores = state.io("Couldn't read the chores note") { Chores.load(v, today) } } }
    LaunchedEffect(state.vault, state.changes) { reload() }
    fun edit(work: (uk.tomlowndes.capture.Vault) -> Unit) = scope.launch {
        val v = state.vault ?: return@launch
        state.io("Couldn't save it") { work(v) }; reload()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 26.dp)) {
        val all = chores.orEmpty()
        val due = Chores.due(all, today).size
        Row(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                H1("Home")
                Sub("Bars fill up as chores get older · ${Chores.PATH.removeSuffix(".md")}")
            }
            if (due > 0) Count(due, text = "$due need doing")
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chores.sorted(all, today).forEach { c ->
                val fill = c.fill(today)
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, Outline, shape)
                        .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) editing = c }
                        .hand().clickable { choreDone(state, c) { reload() } }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.label, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = TextMain, modifier = Modifier.weight(1f))
                        Text(c.ageLabel(today), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (fill >= Chores.DUE) choreColour(fill) else Muted)
                    }
                    ChoreBar(fill, Modifier.padding(top = 8.dp))
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextAction("＋ Add chore") { adding = true }
            Text("Click = done today · right-click = edit", fontSize = 12.sp, color = Muted)
        }
    }

    if (adding || editing != null) {
        val c = editing
        var name by remember(c) { mutableStateOf(c?.label ?: "") }
        var every by remember(c) { mutableStateOf((c?.every ?: 7).toString()) }
        fun close() { adding = false; editing = null }
        DialogCard(if (c == null) "Add chore" else "Edit chore", ::close) {
            Column(Modifier.fillMaxWidth()) {
                SmallLabel("CHORE")
                Field(name, { name = it }, Modifier.fillMaxWidth().padding(top = 6.dp), "🧹 Hoover downstairs")
                SmallLabel("EVERY HOW MANY DAYS", Modifier.padding(top = 12.dp))
                Field(every, { every = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth().padding(top = 6.dp), "7")
                Spacer(Modifier.height(16.dp))
                DialogButtons(::close, onDelete = c?.let { { close(); edit { v -> Chores.remove(v, it) } } }) {
                    PrimaryButton("Save", enabled = name.isNotBlank()) {
                        val (emoji, n) = Chores.splitEmoji(name)
                        val new = Chore(n, emoji, every.toIntOrNull()?.coerceIn(1, 365) ?: 7, c?.done ?: today)
                        close(); edit { v -> Chores.put(v, c?.name, new) }
                    }
                }
            }
        }
    }
}

// ---- Recipes (home-life-v1) ----

/**
 * Saves the recipe at [url] when it is one: schema.org first, then Claude Fast from the page text when [claude] and
 * the PC has a key. Returns the recipe's title, or null when the page isn't a recipe. Blocking.
 */
internal fun saveRecipe(state: AppState, url: String, claude: Boolean): String? {
    val v = state.vault ?: return null
    val (found, text) = Recipes.fetch(url)
    val recipe = found ?: run {
        val key = state.prefs.claudeKey?.takeIf { claude && it.isNotBlank() } ?: return null
        val r = PhoneBrain.complete(key, "Page: $url\n\n$text", Recipes.CLAUDE_SYSTEM)
        state.prefs.brainSpend = state.prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, r.inTok, r.outTok))
        Recipes.fromClaude(r.reply, url)
    } ?: return null
    Recipes.save(v, recipe)
    return recipe.title
}

/** On a recipe note in the Vault reader: Add the unticked ingredients to shopping, and Cook mode. */
@Composable
internal fun RecipeBar(state: AppState, path: String, text: String) {
    val scope = rememberCoroutineScope()
    var cooking by remember(path) { mutableStateOf(false) }
    val r = remember(text) { Recipes.parse(path, text) }
    val need = r.ingredients.filter { !it.have }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("🛒 Add ${need.size} to shopping", small = true, selected = need.isNotEmpty()) {
            if (need.isEmpty()) { state.say("You've ticked everything"); return@Chip }
            scope.launch {
                val n = state.io("Couldn't add to shopping") { Recipes.addToShopping(state.vault!!, need.map { it.text }) } ?: return@launch
                state.say(if (n == 0) "Already on the shopping list" else "Added $n to the Shopping list")
                state.changed()
            }
        }
        if (r.steps.isNotEmpty()) Chip("🍳 Cook mode", small = true) { cooking = true }
        Text(listOfNotNull(r.minutes?.let { "$it min" }, r.serves?.let { "serves $it" }, "tick what you have").joinToString(" · "),
            fontSize = 12.sp, color = Muted)
    }
    if (cooking) CookDialog(r) { cooking = false }
}

/** Cook mode: one big step at a time, ← → or click to move, Esc to leave. */
@Composable
private fun CookDialog(r: RecipeNote, onDone: () -> Unit) {
    var i by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    val last = r.steps.lastIndex
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CaptureTheme {
            Column(
                Modifier.fillMaxSize().background(Bg).focusRequester(focus).focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionRight, Key.Spacebar -> { i = (i + 1).coerceAtMost(last); true }
                            Key.DirectionLeft -> { i = (i - 1).coerceAtLeast(0); true }
                            Key.Escape -> { onDone(); true }
                            else -> false
                        }
                    }
                    .padding(48.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, fontSize = 16.sp, color = Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Step ${i + 1} of ${r.steps.size}", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Primary)
                    Spacer(Modifier.size(16.dp))
                    TextAction("Done", Muted, onClick = onDone)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(r.steps.getOrElse(i) { "" }, fontSize = 34.sp, lineHeight = 46.sp, color = TextMain, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 1000.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextAction("‹ Back", if (i > 0) Primary else Faint) { i = (i - 1).coerceAtLeast(0) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.steps.indices.forEach { n -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (n == i) Primary else Outline)) }
                    }
                    if (i < last) PrimaryButton("Next ›") { i++ } else PrimaryButton("Finished") { onDone() }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

// ---- Snap anything: a pasted image in Quick capture (snap-anything-v1) ----

/** An image on the Windows clipboard (a screenshot, or a copied image file), or null. */
internal fun clipboardImage(): BufferedImage? = runCatching {
    val clip = Toolkit.getDefaultToolkit().systemClipboard
    when {
        clip.isDataFlavorAvailable(DataFlavor.imageFlavor) -> (clip.getData(DataFlavor.imageFlavor) as? Image)?.let(::buffered)
        clip.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> (clip.getData(DataFlavor.javaFileListFlavor) as? List<*>)
            ?.filterIsInstance<File>()?.firstOrNull { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif") }
            ?.let { ImageIO.read(it) }
        else -> null
    }
}.getOrNull()

private fun buffered(img: Image): BufferedImage? {
    if (img is BufferedImage) return img
    val w = img.getWidth(null); val h = img.getHeight(null)
    if (w <= 0 || h <= 0) return null
    return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { b -> b.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }
}

/** The image as a JPEG, shrunk to [Snap.MAX_SIDE] on the long side, in base64. */
internal fun snapJpeg(src: BufferedImage): String {
    val scale = minOf(1.0, Snap.MAX_SIDE.toDouble() / maxOf(src.width, src.height))
    val w = (src.width * scale).toInt().coerceAtLeast(1); val h = (src.height * scale).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    out.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        color = java.awt.Color.WHITE; fillRect(0, 0, w, h)
        drawImage(src, 0, 0, w, h, null); dispose()
    }
    val bytes = ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
    return Base64.getEncoder().encodeToString(bytes)
}

/** Reads the image with Claude Fast and returns what to save. Blocking. */
internal fun readSnap(state: AppState, img: BufferedImage, mode: SnapMode = SnapMode.AUTO): SnapResult {
    val key = state.prefs.claudeKey?.takeIf { it.isNotBlank() } ?: error("Add the Claude key in Settings to read images")
    val r = PhoneBrain.vision(key, Snap.system(LocalDate.now(), mode, listOf("#work", "#personal")), snapJpeg(img), "image/jpeg", Snap.PROMPT)
    state.prefs.brainSpend = state.prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, r.inTok, r.outTok))
    return Snap.parse(r.reply) ?: error("Claude's answer didn't make sense, try again")
}

/** The Snap result inside Quick capture: tick what to keep, Enter saves, Esc closes. */
@Composable
internal fun SnapPanel(state: AppState, result: SnapResult?, failed: String?, onChange: (SnapResult) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("📷 Image", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primary,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(PrimaryTint).padding(horizontal = 9.dp, vertical = 4.dp))
            Text(when {
                failed != null -> failed
                result == null -> "Reading it with Claude…"
                else -> result.title + (result.what.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
            }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (failed != null) Warn else TextMain)
        }
        if (result != null) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            result.items.forEachIndexed { n, item ->
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(Surface1).border(1.dp, if (item.keep) Primary else Outline, shape)
                        .hand().clickable { onChange(result.copy(items = result.items.toMutableList().also { it[n] = item.copy(keep = !item.keep) })) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (item.keep) Primary else Color.Transparent)
                        .border(2.dp, if (item.keep) Primary else Muted, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        if (item.keep) Text("✓", fontSize = 11.sp, color = OnPrimary, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.text, fontSize = 14.sp, color = if (item.keep) TextMain else Muted)
                        Snap.detail(item).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = Muted) }
                    }
                    Text(item.kind.label, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
                }
            }
            if (result.items.isEmpty()) Sub("Nothing worth saving in that one.")
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Paste a screenshot (Win+Shift+S then Ctrl+V) or a copied image file", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Kbd("↵"); Text("save ${result?.kept?.size ?: 0}", fontSize = 12.sp, color = Muted, modifier = Modifier.hand().clickable(onClick = onSave))
                Box(Modifier.size(6.dp)); Kbd("Esc"); Text("close", fontSize = 12.sp, color = Muted, modifier = Modifier.hand().clickable(onClick = onCancel))
            }
        }
    }
}

/** Saves the ticked Snap items to the vault. Returns how many. Blocking. */
internal fun saveSnap(state: AppState, r: SnapResult): Int = Snap.save(state.writer()!!, r, steps = r.steps, parent = r.what)

