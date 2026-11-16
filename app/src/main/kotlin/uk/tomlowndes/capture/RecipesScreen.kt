package uk.tomlowndes.capture

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Saves a recipe from [url]: the page's schema.org recipe, or Claude Fast when the page has none and a key is set.
 * Returns the saved recipe's title, or null when no recipe was found. Blocking.
 */
internal fun saveRecipe(ctx: Context, vault: Uri, url: String): String? {
    val (found, text) = Recipes.fetch(url)
    val recipe = found ?: run {
        val key = SecretStore(ctx).claudeKey?.takeIf { it.isNotBlank() } ?: return null
        val prefs = AppPrefs(ctx)
        val r = PhoneBrain.complete(key, "Page: $url\n\n$text", Recipes.CLAUDE_SYSTEM)
        prefs.brainSpend = prefs.brainSpend.add(PhoneBrain.costUsd(PhoneBrain.FAST, r.inTok, r.outTok))
        Recipes.fromClaude(r.reply, url)
    } ?: return null
    Recipes.save(appVault(ctx, vault), recipe, LocalDate.now())
    return recipe.title
}

/** Recipes (home-life-v1): the saved recipes, one recipe with ingredient ticks and Add to shopping, and Cook mode. */
@Composable
fun RecipesScreen(vault: Uri?, snack: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<RecipeNote>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var cooking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(vault, refresh) {
        val uri = vault ?: return@LaunchedEffect
        list = withContext(Dispatchers.IO) { runCatching { Recipes.list(appVault(ctx, uri)) }.getOrDefault(emptyList()) }
    }
    BackHandler(enabled = open != null) { if (cooking) cooking = false else open = null }

    val recipe = list?.firstOrNull { it.path == open }
    if (recipe != null && cooking) { CookMode(recipe) { cooking = false }; return }
    if (recipe != null) {
        RecipeView(recipe, onBack = { open = null }, onCook = { cooking = true },
            onTick = { item, have ->
                val uri = vault ?: return@RecipeView
                list = list?.map { r -> if (r.path == recipe.path) r.copy(ingredients = r.ingredients.map { if (it.text == item) it.copy(have = have) else it }) else r }
                scope.launch { withContext(Dispatchers.IO) { runCatching { Recipes.setHave(appVault(ctx, uri), recipe.path, item, have) } } }
            },
            onShop = {
                val uri = vault ?: return@RecipeView
                val items = recipe.ingredients.filter { !it.have }.map { it.text }
                scope.launch {
                    val n = withContext(Dispatchers.IO) { runCatching { Recipes.addToShopping(appVault(ctx, uri), items) } }
                    snack.showSnackbar(n.fold({ if (it == 0) "Already on the shopping list" else "Added $it to the shopping list" }, { "Failed: ${it.message}" }))
                }
            })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("‹ Vault", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
        Text("Recipes", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Text("Share a recipe page to Flight Deck to save it here.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
        val all = list ?: return@Column
        if (all.isNotEmpty()) ListCard {
            all.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = Outline)
                Column(Modifier.fillMaxWidth().clickable { open = r.path }.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Text(r.title, fontSize = 15.sp)
                    Text(listOfNotNull(r.minutes?.let { TaskRules.minutesLabel(it) }, r.serves?.let { "serves $it" }, "${r.ingredients.size} ingredients").joinToString(" · "),
                        fontSize = 12.sp, color = Muted)
                }
            }
        }
        Text(if (busy) "Saving…" else "＋ Paste a recipe link", color = Primary, fontSize = 14.sp,
            modifier = Modifier.clickable(enabled = !busy) { adding = true }.padding(vertical = 10.dp))
    }

    if (adding) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false }, containerColor = Surface1,
            title = { Text("Save a recipe") },
            text = { OutlinedTextField(link, { link = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("https://…") }) },
            confirmButton = {
                TextButton(enabled = link.isNotBlank(), onClick = {
                    val uri = vault ?: return@TextButton
                    val url = ShareIntent.urlOf(link)
                    adding = false; busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { saveRecipe(ctx, uri, url) } }
                        busy = false; refresh++
                        snack.showSnackbar(r.fold({ it?.let { t -> "Saved $t" } ?: "No recipe found on that page" }, { "Couldn't fetch that page" }))
                    }
                }) { Text("Save", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel", color = Muted) } }
        )
    }
}

@Composable
private fun RecipeView(r: RecipeNote, onBack: () -> Unit, onCook: () -> Unit, onTick: (String, Boolean) -> Unit, onShop: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("‹ Recipes", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onBack).padding(top = 10.dp))
        Text(r.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Text(listOfNotNull(r.minutes?.let { TaskRules.minutesLabel(it) }, r.serves?.let { "serves $it" }, ReadStore.host(r.url)?.let { "from $it" }).joinToString(" · "),
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        SectionLabel("Ingredients")
        ListCard {
            r.ingredients.forEachIndexed { i, ing ->
                if (i > 0) HorizontalDivider(color = Outline)
                Row(Modifier.fillMaxWidth().clickable { onTick(ing.text, !ing.have) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TickBox(ing.have, onClick = { onTick(ing.text, !ing.have) })
                    Text(ing.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (ing.have) Text("have", fontSize = 11.sp, color = Muted)
                }
            }
        }
        val missing = r.ingredients.count { !it.have }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(if (missing == 0) "🛒 Got everything" else "🛒 Add $missing to shopping", primary = false, modifier = Modifier.weight(1f)) { if (missing > 0) onShop() }
            DeckButton("Cook this", primary = true, modifier = Modifier.weight(1f), onClick = onCook)
        }
        SectionLabel("Method · ${r.steps.size} steps")
        r.steps.forEachIndexed { i, s ->
            Text("${i + 1}. $s", fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Cook mode: one step at a time, big, with the screen kept on. */
@Composable
private fun CookMode(r: RecipeNote, onClose: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var step by remember { mutableIntStateOf(0) }
    val steps = r.steps.ifEmpty { listOf("No method saved for this one.") }
    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✕", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable(onClick = onClose).padding(end = 14.dp))
            Text(r.title, fontSize = 15.sp, color = Muted, modifier = Modifier.weight(1f), maxLines = 1)
            Text("${step + 1} / ${steps.size}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Primary)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(steps[step], fontSize = 26.sp, lineHeight = 36.sp, textAlign = TextAlign.Start)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("‹ Back", textAlign = TextAlign.Center, fontSize = 16.sp, color = if (step > 0) TextMain else Muted,
                modifier = Modifier.weight(1f).clip(CircleShape).border(1.dp, Outline, CircleShape)
                    .clickable(enabled = step > 0) { step-- }.padding(vertical = 16.dp))
            val last = step == steps.lastIndex
            Text(if (last) "Done" else "Next ›", textAlign = TextAlign.Center, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OnPrimary,
                modifier = Modifier.weight(1f).clip(CircleShape).background(Primary)
                    .clickable { if (last) onClose() else step++ }.padding(vertical = 16.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}
