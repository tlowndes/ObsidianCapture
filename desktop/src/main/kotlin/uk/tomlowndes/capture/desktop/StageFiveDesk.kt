package uk.tomlowndes.capture.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.tomlowndes.capture.Errand
import uk.tomlowndes.capture.Errands
import uk.tomlowndes.capture.Place
import uk.tomlowndes.capture.PlaceHit
import uk.tomlowndes.capture.PlaceSearch
import uk.tomlowndes.capture.MealDay
import uk.tomlowndes.capture.MealPlan
import uk.tomlowndes.capture.People
import uk.tomlowndes.capture.Person
import uk.tomlowndes.capture.RecipeNote
import uk.tomlowndes.capture.Recipes
import uk.tomlowndes.capture.TaskRules
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

@Composable
private fun Box16(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(Surface1).border(1.dp, Outline, shape).padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
}

// ---- Meal plan the week: a 7-column board (meals-spend-v1) ----

/** Home › This week: one column a day. Click a day to pick a recipe; right-click to move or clear. */
@Composable
internal fun MealBoard(state: AppState) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var week by remember { mutableStateOf(MealPlan.monday(today)) }
    var days by remember { mutableStateOf<List<MealDay>?>(null) }
    var recipes by remember { mutableStateOf<List<RecipeNote>>(emptyList()) }
    var picking by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(state.vault, state.changes, week) {
        val v = state.vault ?: return@LaunchedEffect
        state.io("Couldn't read the meal plan") { recipes = Recipes.list(v); days = MealPlan.load(v, week) }
    }
    fun save(n: List<MealDay>) {
        days = n
        val v = state.vault ?: return
        scope.launch { state.io("Couldn't save the plan") { MealPlan.save(v, n) } }
    }
    val ds = days ?: return
    Row(Modifier.padding(top = 28.dp).widthIn(max = 980.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            H1("This week", 22)
            Sub("${MealPlan.planned(ds)} of 7 planned · Recipes/Week ${MealPlan.week(week)}.md")
        }
        TextAction("‹", Muted) { week = week.minusWeeks(1) }
        TextAction("›", Muted) { week = week.plusWeeks(1) }
    }
    Row(Modifier.padding(top = 10.dp).widthIn(max = 980.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ds.forEachIndexed { i, d ->
            Column(Modifier.weight(1f)) { ContextMenuArea(items = {
                listOfNotNull(
                    ContextMenuItem("Pick a recipe…") { picking = d.date },
                    if (i > 0) ContextMenuItem("Move to the day before") { save(MealPlan.swap(ds, d.date, d.date.minusDays(1))) } else null,
                    if (i < 6) ContextMenuItem("Move to the day after") { save(MealPlan.swap(ds, d.date, d.date.plusDays(1))) } else null,
                    ContextMenuItem("Clear") { save(MealPlan.set(ds, d.date, null, null)) },
                )
            }) {
                Box16(Modifier.fillMaxWidth().heightIn(min = 96.dp).hand().clickable { picking = d.date }) {
                    Text(d.date.format(DateTimeFormatter.ofPattern("EEE d", Locale.UK)), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = if (d.date == today) Primary else Muted)
                    Text(if (d.empty) "＋" else d.label, fontSize = 13.sp, color = if (d.empty) Faint else TextMain, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                    d.recipe?.let { r -> recipes.firstOrNull { it.title.equals(r, true) }?.minutes?.let { Text(TaskRules.minutesLabel(it), fontSize = 11.sp, color = Muted) } }
                }
            } }
        }
    }
    val shop = MealPlan.shopping(ds, recipes)
    if (shop.isNotEmpty()) Row(Modifier.padding(top = 10.dp).widthIn(max = 980.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🛒 ${shop.size} items for the week" + (MealPlan.mergedNote(shop)?.let { " · $it" } ?: ""), fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
        PrimaryButton("Add to shopping list") {
            val v = state.vault ?: return@PrimaryButton
            scope.launch { state.io("Couldn't add them") { Recipes.addToShopping(v, shop.map { it.text }) }?.let { state.say("Added $it to the shopping list") } }
        }
    }
    picking?.let { date ->
        WideDialog(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)), "Pick a recipe, or one of the quick ones", { picking = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MealPlan.QUICK.forEach { q -> Chip(q) { save(MealPlan.set(ds, date, null, q)); picking = null } }
            }
            Spacer(Modifier.height(10.dp))
            if (recipes.isEmpty()) Text("No recipes saved yet. Copy a recipe link and use Home › Recipes.", fontSize = 13.sp, color = Muted)
            recipes.forEach { r ->
                Row(Modifier.fillMaxWidth().hand().clickable { save(MealPlan.set(ds, date, r.title)); picking = null }.padding(vertical = 7.dp)) {
                    Text(r.title, fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f))
                    r.minutes?.let { Text(TaskRules.minutesLabel(it), fontSize = 12.sp, color = Muted) }
                }
            }
        }
    }
}

// ---- Errands by place: the list only, no nudges on Windows ----

@Composable
internal fun ErrandsList(state: AppState) {
    var errands by remember { mutableStateOf<List<Errand>?>(null) }
    LaunchedEffect(state.vault, state.changes) {
        val v = state.vault ?: return@LaunchedEffect
        errands = state.io("Couldn't read errands") {
            val shopping = Recipes.shopping(v)?.let { Recipes.shoppingOpen(it) }.orEmpty()
            Errands.errands(state.writer()!!.listTasks(), shopping, Errands.places(v))
        }
    }
    val es = errands ?: return
    H1("Errands", 22)
    Sub("Add @tesco or @b&q to any task. Nudges are set up on the phone.", Modifier.padding(bottom = 10.dp))
    if (es.isEmpty()) Text("Nothing tagged with a place yet.", fontSize = 13.sp, color = Muted)
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        es.forEach { e ->
            Box16(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.place?.let { "${e.tag} · ${it.label}" } ?: e.tag, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (e.place != null) TextMain else Muted, modifier = Modifier.weight(1f))
                    Text("${e.items.size}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Muted)
                }
                Text(Errands.summary(e, 8), fontSize = 12.5.sp, color = Muted)
            }
        }
    }
    PlacesDesk(state, es)
}

private fun placeGet(url: String): String {
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 8_000; c.readTimeout = 10_000
    c.setRequestProperty("User-Agent", "FlightDeck/2.0 (personal Obsidian app)") // OpenStreetMap asks every app to say who it is
    try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
}

/** Places on Windows (errand-places-v1): search only, no map. Results sort from your weather town. */
@Composable
private fun PlacesDesk(state: AppState, es: List<Errand>) {
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PlaceHit>?>(emptyList()) }
    var pick by remember { mutableStateOf<PlaceHit?>(null) }
    var tag by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(1) }
    val near = state.prefs.weatherLatLon
    LaunchedEffect(state.vault, state.changes) { val v = state.vault ?: return@LaunchedEffect; places = state.io("Couldn't read places") { Errands.places(v) }.orEmpty() }
    LaunchedEffect(q) {
        if (q.trim().length < PlaceSearch.MIN_CHARS) { hits = emptyList(); return@LaunchedEffect }
        delay(400)
        hits = state.io("Couldn't search") {
            val raw = runCatching { PlaceSearch.parsePhoton(placeGet(PlaceSearch.photonUrl(q, near))) }.getOrNull()
                ?: PlaceSearch.parseNominatim(placeGet(PlaceSearch.nominatimUrl(q)))
            PlaceSearch.sorted(raw, near)
        }
    }
    H1("Places", 17)
    Column(Modifier.widthIn(max = 720.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        places.forEach { p ->
            Box16(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍 ${p.tag} · ${p.label}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("${p.radius} m", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    TextAction("Remove", Warn) {
                        val v = state.vault ?: return@TextAction
                        scope.launch { state.io("Couldn't remove") { Errands.remove(v, p.tag) }; state.changed() }
                    }
                }
            }
        }
        Field(q, { q = it; pick = null }, Modifier.fillMaxWidth(), placeholder = "🔍 Add a place: shop name, street or postcode")
        val p = pick
        if (p != null) {
            Box16(Modifier.fillMaxWidth()) {
                Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (p.address.isNotEmpty()) Text(p.address, fontSize = 12.sp, color = Muted)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(tag, { tag = it }, Modifier.width(160.dp), placeholder = "@tag")
                    Pill(PlaceSearch.RADII.map { "$it m" }, radius, Modifier.width(300.dp)) { radius = it }
                    val clean = Errands.cleanTag(tag)
                    PrimaryButton("Save place", enabled = clean.length > 1 && clean[1].isLetter()) {
                        val v = state.vault ?: return@PrimaryButton
                        scope.launch {
                            state.io("Couldn't save") { Errands.upsert(v, Place(clean, p.name, p.lat, p.lon, PlaceSearch.RADII[radius])) } ?: return@launch
                            state.say("Saved $clean · ${p.name}"); pick = null; q = ""; state.changed()
                        }
                    }
                }
                val n = es.firstOrNull { it.tag == Errands.cleanTag(tag) }?.tasks?.size ?: 0
                if (n > 0) Text("$n task${if (n == 1) "" else "s"} use this", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        } else hits.let { list ->
            if (list == null) Text("Couldn't search just now.", fontSize = 13.sp, color = Muted)
            else list.forEach { h ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                    pick = h; radius = 1; tag = PlaceSearch.tagFor(h.name, (places.map { it.tag } + es.map { it.tag }).distinct())
                }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (h.address.isNotEmpty()) Text(h.address, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    h.metres?.let { Text(PlaceSearch.distance(it), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Muted) }
                }
            }
        }
        Text(PlaceSearch.ATTRIBUTION + ". Pick on a map on the phone.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
    }
}

// ---- People and birthdays ----

/** Sidebar › People: coming up, everyone, and the picked person's gift ideas and notes. */
@Composable
fun PeoplePane(state: AppState) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var picking by remember { mutableStateOf<Person?>(null) }
    fun reload() = scope.launch { state.vault?.let { v -> people = state.io("Couldn't read People") { People.list(v) } } }
    LaunchedEffect(state.vault, state.changes) { reload() }
    fun edit(msg: String?, work: (uk.tomlowndes.capture.Vault) -> Unit) = scope.launch {
        val v = state.vault ?: return@launch
        if (state.io("Couldn't save it") { work(v) } != null) msg?.let { state.say(it) }
        reload()
    }
    val ps = people.orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                H1("People", 28)
                Sub("${ps.size} · notes in ${state.vault?.let { People.folder(it) } ?: "People"}/", Modifier.padding(top = 4.dp, bottom = 14.dp))
            }
            TextAction("＋ Add someone") { ask = "Name" to { s -> edit("Added $s") { v -> People.create(v, s) } } }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val up = People.comingUp(ps, today)
                if (up.isNotEmpty()) {
                    SmallLabel("COMING UP")
                    up.forEach { n ->
                        Box16(Modifier.fillMaxWidth().hand().clickable { picked = n.person.path }) {
                            Text("${if (n.birthday != null) "🎂" else "💬"} ${n.person.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                            Text(n.text, fontSize = 12.sp, color = if (n.birthday != null && n.days <= People.GIFT_DAYS) Amber else Muted)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                SmallLabel("EVERYONE")
                ps.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (picked == p.path) PrimaryTint else Surface1)
                        .hand().clickable { picked = p.path }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(p.name, fontSize = 14.sp, color = TextMain, modifier = Modifier.weight(1f))
                        p.nextBirthday(today)?.let { Text("🎂 ${it.format(SHORT)}", fontSize = 12.sp, color = Muted) }
                    }
                }
            }
            val p = ps.firstOrNull { it.path == picked }
            Box16(Modifier.weight(1f).fillMaxSize()) {
                if (p == null) { Text("Pick someone to see gift ideas and notes.", fontSize = 13.sp, color = Muted); return@Box16 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextMain, modifier = Modifier.weight(1f))
                    TextAction(p.birthday?.let { "🎂 " + p.nextBirthday(today)!!.format(SHORT) } ?: "＋ Birthday", if (p.birthday == null) Primary else Muted) {
                        picking = p
                    }
                    TextAction("Open note", Muted) { state.openInObsidian(p.path) }
                }
                p.lastSpoke?.let { Text("Last spoke ${it.format(SHORT)}", fontSize = 12.sp, color = Muted) }
                SmallLabel("GIFT IDEAS", Modifier.padding(top = 12.dp))
                if (p.gifts.isEmpty()) Text("None yet", fontSize = 13.sp, color = Muted)
                p.gifts.forEach { Text("• $it", fontSize = 14.sp, color = TextMain, modifier = Modifier.padding(top = 3.dp)) }
                SmallLabel("NOTES", Modifier.padding(top = 12.dp))
                if (p.notes.isEmpty()) Text("None yet", fontSize = 13.sp, color = Muted)
                p.notes.forEach { Text(it, fontSize = 14.sp, color = TextMain, modifier = Modifier.padding(top = 3.dp)) }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("＋ Gift idea") { ask = "Gift idea for ${p.name}" to { s -> edit("Gift idea saved") { v -> People.addGift(v, p, s) } } }
                    TextAction("＋ Note") { ask = "Note about ${p.name}" to { s -> edit("Note saved") { v -> People.addNote(v, p, s) } } }
                    TextAction("Spoke today") { edit("Marked as spoken today") { v -> People.spoke(v, p) } }
                }
                SmallLabel("KEEP IN TOUCH", Modifier.padding(top = 12.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Off", 7 to "1 wk", 21 to "3 wks", 42 to "6 wks", 90 to "3 mo").forEach { (d, label) ->
                        Chip(label, selected = p.every == d) { edit(null) { v -> People.setEvery(v, p, d) } }
                    }
                }
            }
        }
    }
    picking?.let { p ->
        BirthdayDialog(p) { value ->
            picking = null
            if (value != null) edit(if (value.isEmpty()) "Birthday removed" else "Birthday saved") { v -> People.setBirthday(v, p, value) }
        }
    }
    ask?.let { (title, then) ->
        var text by remember(title) { mutableStateOf("") }
        DialogCard(title, { ask = null }) {
            Column(Modifier.fillMaxWidth()) {
                Field(text, { text = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                DialogButtons({ ask = null }) { PrimaryButton("Save", enabled = text.isNotBlank()) { val s = text.trim(); ask = null; then(s) } }
            }
        }
    }
}

// ---- Birthday picker (birthday-picker-v1, frame 3) ----

private val PICK_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Month grid, day grid, optional year. onDone gets the frontmatter value, "" to remove, or null to cancel. */
@Composable
private fun BirthdayDialog(p: Person, onDone: (String?) -> Unit) {
    val today = LocalDate.now()
    var month by remember { mutableStateOf(p.birthday?.monthValue ?: today.monthValue) }
    var day by remember { mutableStateOf(p.birthday?.dayOfMonth ?: today.dayOfMonth) }
    var knowYear by remember { mutableStateOf(p.born != null) }
    var yearText by remember { mutableStateOf((p.born ?: (today.year - 30)).toString()) }
    val typed = yearText.trim().toIntOrNull()?.takeIf { it in 1900..today.year }
    val y = typed.takeIf { knowYear }
    val maxDay = People.pickerDays(month, y)
    val dayShown = day.coerceAtMost(maxDay)
    DialogCard("${p.name}'s birthday", { onDone(null) }) {
        Column(Modifier.fillMaxWidth()) {
            PickLabel("Month")
            PICK_MONTHS.chunked(6).forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { c, m -> val n = r * 6 + c + 1; PickCell(m, n == month, true, Modifier.weight(1f)) { month = n } }
                }
            }
            PickLabel("Day")
            (1..31).chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { d -> PickCell("$d", d == dayShown, d <= maxDay, Modifier.weight(1f)) { day = d } }
                    repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("I know the year", fontSize = 14.sp, color = TextMain)
                    Text("Optional. Shows their age.", fontSize = 12.sp, color = Muted)
                }
                Toggle(knowYear) { knowYear = it }
            }
            if (knowYear) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun bump(by: Int) { yearText = ((typed ?: (today.year - 30)) + by).coerceIn(1900, today.year).toString() }
                Chip("−10") { bump(-10) }; Chip("−1") { bump(-1) }
                Field(yearText, { yearText = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), placeholder = "Year")
                Chip("+1") { bump(1) }; Chip("+10") { bump(10) }
            }
            Text(People.pickerPreview(month, dayShown, y, today), fontSize = 14.sp, color = TextMain,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(PrimaryTint).padding(horizontal = 13.dp, vertical = 10.dp))
            Spacer(Modifier.height(14.dp))
            DialogButtons({ onDone(null) }, onDelete = if (p.birthday != null) ({ onDone("") }) else null) {
                PrimaryButton("Save", enabled = !knowYear || typed != null) { onDone(People.birthdayValue(month, dayShown, y)) }
            }
        }
    }
}

@Composable
private fun PickLabel(text: String) =
    Text(text.uppercase(Locale.UK), fontSize = 11.sp, letterSpacing = 1.2.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))

@Composable
private fun PickCell(text: String, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(text, fontSize = 13.sp, textAlign = TextAlign.Center, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) Primary else if (enabled) TextMain else Faint,
        modifier = modifier.clip(shape).background(if (on) PrimaryTint else Color.Transparent)
            .border(1.dp, if (on) Primary else if (enabled) Outline else Surface2, shape)
            .then(if (enabled) Modifier.hand().clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp))
}
