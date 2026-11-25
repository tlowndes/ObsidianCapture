package uk.tomlowndes.capture

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One line of a packing or check list (packing-list-v4). [done] is `- [x]`, [skipped] is `- [-]` (this pack only).
 * [perDay] items come ×nights (+[spare]); [link] names another list in Kits that opens from this line.
 */
data class PackItem(
    val emoji: String, val name: String, val done: Boolean = false, val skipped: Boolean = false,
    val perDay: Boolean = false, val spare: Int = 0, val link: String? = null,
) {
    /** How many to pack: nights (+ spare) for per-day items, else none shown. */
    fun qty(nights: Int): Int? = if (perDay) nights + spare else null
    fun label(nights: Int): String = qty(nights)?.let { "$name ×$it" } ?: name
}

data class PackSection(val title: String, val items: List<PackItem>)

/** PACK: repacked every few days (nights, Repack, Print). CHECK: stays put, checked now and then (Car, Go bag). */
enum class PackKind { PACK, CHECK }

/**
 * A list kit in 99 - Meta/Kits: frontmatter `kit: pack|check` marks it, so Launch Pad and Night Pad leave it alone.
 * [checked] is the last Repack (pack) or check (check). [inside] names the bag it lives in (the pouch in EDC bag).
 */
data class PackList(
    val name: String, val emoji: String, val kind: PackKind, val sections: List<PackSection>,
    val nights: Int = 3, val checked: LocalDate? = null, val inside: String? = null,
    val path: String? = null, val intro: String = "", val extraFm: List<String> = emptyList(),
) {
    val items get() = sections.flatMap { it.items }
}

/** Packed, out of how many, with skipped items left out of both. */
data class PackCount(val done: Int, val total: Int) {
    val full get() = total > 0 && done >= total
    override fun toString() = "$done/$total"
}

object PackLists {
    const val PACKING = "Packing"
    const val POUCH = "House-to-house pouch"
    const val CAR = "Car"
    const val GO_BAG = "Go bag"
    const val CHECK_EVERY_DAYS = 30L

    private val ITEM = Regex("^\\s*[-*]\\s+\\[([ xX-])]\\s+(.*)$")
    private val LINK = Regex("\\[\\[([^]|]+)(?:\\|[^]]*)?]]")
    private val PER_DAY = Regex("\\s*·\\s*per day(?:\\s*\\+(\\d+))?\\s*$", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("^#\\s+(.+?)\\s*$")

    /** True when [text] is a list kit (frontmatter `kit: pack` or `kit: check`). */
    fun isList(text: String): Boolean = kindOf(text) != null

    private fun kindOf(text: String): PackKind? =
        when (Md.fmGet(text.replace("\r\n", "\n").split("\n"), "kit")?.lowercase(Locale.UK)) {
            "pack" -> PackKind.PACK
            "check" -> PackKind.CHECK
            else -> null
        }

    fun parse(name: String, text: String, path: String? = null): PackList {
        val lines = text.replace("\r\n", "\n").split("\n")
        val end = Md.fmEnd(lines)
        val fm = if (end > 0) lines.subList(1, end) else emptyList()
        fun get(k: String) = Md.fmGet(lines, k)
        val known = setOf("kit", "nights", "checked", "inside")
        val extra = fm.filter { l -> l.substringBefore(":").trim().lowercase(Locale.UK) !in known }
        val body = Md.fmBody(lines)
        var emoji = "🧳"
        val intro = mutableListOf<String>()
        val sections = mutableListOf<PackSection>()
        var title: String? = null
        var items = mutableListOf<PackItem>()
        fun flush() { title?.let { sections += PackSection(it, items) }; items = mutableListOf() }
        for (l in body) {
            val t = TITLE.matchEntire(l)?.groupValues?.get(1)
            if (t != null && title == null) { if (t.contains(" ") && !t.first().isLetterOrDigit()) emoji = t.substringBefore(" "); continue }
            if (l.startsWith("## ")) { flush(); title = l.removePrefix("## ").trim(); continue }
            if (title == null) { intro += l; continue }
            ITEM.matchEntire(l)?.let { m -> items += item(m.groupValues[1], m.groupValues[2]) }
        }
        flush()
        return PackList(
            name = name, emoji = emoji, kind = kindOf(text) ?: PackKind.PACK, sections = sections,
            nights = get("nights")?.toIntOrNull()?.coerceIn(1, 30) ?: 3,
            checked = get("checked")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
            inside = get("inside")?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() },
            path = path, intro = intro.joinToString("\n").trim(), extraFm = extra,
        )
    }

    private fun item(mark: String, raw: String): PackItem {
        var s = raw.trim()
        var perDay = false; var spare = 0
        PER_DAY.find(s)?.let { m -> perDay = true; spare = m.groupValues[1].toIntOrNull() ?: 0; s = s.removeRange(m.range).trim() }
        val link = LINK.find(s)?.groupValues?.get(1)?.trim()
        s = s.replace(LINK) { it.groupValues[1].trim() }
        val first = s.substringBefore(" ")
        val (emoji, name) = if (s.contains(" ") && first.isNotEmpty() && !first.first().isLetterOrDigit()) first to s.substringAfter(" ").trim() else "•" to s
        return PackItem(emoji, name, done = mark.equals("x", true), skipped = mark == "-", perDay = perDay, spare = spare, link = link)
    }

    private fun line(i: PackItem): String {
        val mark = if (i.skipped) "-" else if (i.done) "x" else " "
        val n = if (i.link != null) i.name.replace(i.link, "[[${i.link}]]").let { if (it == i.name) "[[${i.link}]]" else it } else i.name
        val e = if (i.emoji == "•") "" else "${i.emoji} "
        val day = if (i.perDay) " · per day" + (if (i.spare > 0) " +${i.spare}" else "") else ""
        return "- [$mark] $e$n$day"
    }

    fun render(p: PackList): String {
        val out = mutableListOf("---", "kit: ${p.kind.name.lowercase(Locale.UK)}")
        if (p.kind == PackKind.PACK) out += "nights: ${p.nights}"
        p.checked?.let { out += "checked: $it" }
        p.inside?.let { out += "inside: $it" }
        out += p.extraFm
        out += "---"
        out += "# ${p.emoji} ${p.name}"
        out += ""
        if (p.intro.isNotEmpty()) { out += p.intro; out += "" }
        p.sections.forEach { s -> out += "## ${s.title}"; s.items.forEach { out += line(it) }; out += "" }
        return out.joinToString("\n").trimEnd() + "\n"
    }

    // ---- Counting ----

    /** Progress of [p]. A linked list counts as one item, packed when it's full. */
    fun count(p: PackList, lists: List<PackList> = emptyList()): PackCount {
        val live = p.items.filterNot { it.skipped }
        return PackCount(live.count { isDone(it, lists) }, live.size)
    }

    fun count(s: PackSection, lists: List<PackList>): PackCount {
        val live = s.items.filterNot { it.skipped }
        return PackCount(live.count { isDone(it, lists) }, live.size)
    }

    fun linked(i: PackItem, lists: List<PackList>): PackList? = i.link?.let { l -> lists.firstOrNull { it.name.equals(l, true) } }

    fun isDone(i: PackItem, lists: List<PackList>): Boolean = linked(i, lists)?.let { count(it, lists).full } ?: i.done

    /** True when a check list hasn't been checked in [CHECK_EVERY_DAYS] days (or ever). */
    fun due(p: PackList, today: LocalDate): Boolean =
        p.kind == PackKind.CHECK && (p.checked == null || p.checked.plusDays(CHECK_EVERY_DAYS) <= today)

    // ---- Changes ----

    private fun map(p: PackList, f: (PackItem) -> PackItem) = p.copy(sections = p.sections.map { s -> s.copy(items = s.items.map(f)) })

    private fun at(p: PackList, section: Int, index: Int, f: (PackItem) -> PackItem?): PackList =
        p.copy(sections = p.sections.mapIndexed { si, s ->
            if (si != section) s else s.copy(items = s.items.mapIndexedNotNull { ii, it -> if (ii == index) f(it) else it })
        })

    /** Tap: tick or untick. A skipped item comes back unticked. */
    fun toggle(p: PackList, section: Int, index: Int) = at(p, section, index) { if (it.skipped) it.copy(skipped = false) else it.copy(done = !it.done) }

    fun skip(p: PackList, section: Int, index: Int) = at(p, section, index) { it.copy(skipped = !it.skipped, done = false) }

    fun removeItem(p: PackList, section: Int, index: Int) = at(p, section, index) { null }

    /** Saves [item] into the section called [title] (made if new): in place when it stays put, else at the end. */
    fun putItem(p: PackList, item: PackItem, title: String, from: Pair<Int, Int>? = null): PackList {
        val t = title.trim().ifEmpty { "Other" }
        if (from != null && p.sections.getOrNull(from.first)?.title.equals(t, true)) return at(p, from.first, from.second) { item }
        val base = if (from != null) removeItem(p, from.first, from.second) else p
        val i = base.sections.indexOfFirst { it.title.equals(t, true) }
        return if (i < 0) base.copy(sections = base.sections + PackSection(t, listOf(item)))
        else base.copy(sections = base.sections.mapIndexed { si, s -> if (si == i) s.copy(items = s.items + item) else s })
    }

    /** Repack: every tick and skip cleared, the next stay's nights set, and the date stamped. */
    fun repack(p: PackList, nights: Int, today: LocalDate) =
        map(p) { it.copy(done = false, skipped = false) }.copy(nights = nights.coerceIn(1, 30), checked = today)

    /** Check done: ticks cleared for next time and the date stamped. */
    fun markChecked(p: PackList, today: LocalDate) = map(p) { it.copy(done = false, skipped = false) }.copy(checked = today)

    /** Lists that open from [p]'s lines (the pouch from Packing), so Repack clears them too. */
    fun children(p: PackList, lists: List<PackList>): List<PackList> = p.items.mapNotNull { linked(it, lists) }.filter { it.name != p.name }.distinctBy { it.name }

    // ---- Printing ----

    /** The printed slip: each section with a tick box per item (skipped left off), linked lists as their own group. */
    fun printDoc(p: PackList, lists: List<PackList>, today: LocalDate): ListDoc {
        val groups = mutableListOf<ListGroup>()
        p.sections.forEach { s ->
            val plain = s.items.filter { !it.skipped && linked(it, lists) == null }.map { it.label(p.nights) }
            if (plain.isNotEmpty()) groups += ListGroup(s.title.uppercase(Locale.UK), plain)
            s.items.filter { !it.skipped }.mapNotNull { linked(it, lists) }.forEach { c ->
                val items = c.items.filter { !it.skipped }.map { it.label(p.nights) }
                if (items.isNotEmpty()) groups += ListGroup(c.name.uppercase(Locale.UK), items)
            }
        }
        val n = groups.sumOf { it.items.size }
        val day = today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        val sub = listOfNotNull(day, if (p.kind == PackKind.PACK) "${p.nights} night" + (if (p.nights == 1) "" else "s") else null, "$n item" + if (n == 1) "" else "s")
        return ListDoc(p.name.uppercase(Locale.UK), sub.joinToString(" · "), groups)
    }

    // ---- The vault ----

    /** Every list kit in Kits, Packing first, then by name. Seeds the four lists the first time. */
    fun loadAll(v: Vault): List<PackList> {
        ensure(v)
        return read(v)
    }

    private fun read(v: Vault): List<PackList> {
        if (v.entry(Kits.DIR) == null) return emptyList()
        val all = v.list(Kits.DIR).filter { !it.isDir && it.name.endsWith(".md") }.mapNotNull { e ->
            val p = join(Kits.DIR, e.name)
            val text = VaultIo.read(v, p)
            if (isList(text)) parse(Kits.cleanName(e.name.removeSuffix(".md")), text, p) else null
        }
        return Kits.dedupe(all, { it.name }, { it.path }).sortedWith(
            compareBy<PackList> { order(it.name) }.thenBy { it.name.lowercase(Locale.UK) })
    }

    private fun order(name: String) = listOf(PACKING, CAR, GO_BAG).indexOfFirst { it.equals(name, true) }.let { if (it < 0) 9 else it }

    /** Adds the starter lists once: only when Kits has no list kit at all. */
    fun ensure(v: Vault) {
        if (read(v).isNotEmpty()) return
        v.mkdirs(Kits.DIR)
        STARTERS.forEach { (name, text) -> if (v.entry(Kits.path(name)) == null) VaultIo.write(v, v.create(Kits.DIR, name, "md"), text) }
    }

    fun save(v: Vault, p: PackList) {
        val path = p.path ?: Kits.path(p.name)
        if (v.entry(path) == null) { v.mkdirs(Kits.DIR); VaultIo.write(v, v.create(Kits.DIR, p.name, "md"), render(p)); return }
        VaultIo.write(v, path, render(p))
    }

    // ---- Starters, from Bags & Pouches (05 - Resources/Go bag & EDC) ----

    val PACKING_STARTER = """
        ---
        kit: pack
        nights: 3
        ---
        # 🧳 Packing

        For going between houses. Tap to tick, long-press to edit, swipe left to skip for this pack.
        Per day items come once per night. Repack clears the ticks for next time.

        ## Bags
        - [ ] 🎒 EDC bag
        - [ ] 👝 [[House-to-house pouch]]

        ## Clothes
        - [ ] 🩲 Boxers · per day
        - [ ] 🧦 Socks · per day
        - [ ] 👕 T-shirts · per day
        - [ ] 👔 Work T-shirts · per day
        - [ ] 👖 Trousers
        - [ ] 🧥 Jacket and jumper
        - [ ] 👟 Shoes
        - [ ] 🧢 Cap
        - [ ] 🕶️ Sunglasses

        ## Wash bag
        - [ ] 🪥 Toothbrush and paste
        - [ ] 🧴 Deodorant
        - [ ] 🧴 Small aftershave
        - [ ] 🧼 Wash cloth
        - [ ] 🧴 Hand gel

        ## Meds
        - [ ] 💊 Meds
        - [ ] 🩹 Plasters

        ## Odds
        - [ ] 🔑 Keys for both houses
    """.trimIndent() + "\n"

    val POUCH_STARTER = """
        ---
        kit: pack
        nights: 3
        inside: EDC bag
        ---
        # 👝 House-to-house pouch

        Lives inside the EDC bag. Opens from Packing, and Repack there clears it too.

        ## In the pouch
        - [ ] 🖨️ Receipt printer cable
        - [ ] 🔌 Spare USB-C cable
        - [ ] 🔌 Phone charging plug
        - [ ] 🔀 USB-C to Lightning / micro-USB adapter
        - [ ] 🔌 Small multi-socket plug
        - [ ] 💊 Painkillers
        - [ ] 💋 Lip balm
        - [ ] 🧴 Mini hand cream
        - [ ] 🖊️ Pen
    """.trimIndent() + "\n"

    val CAR_STARTER = """
        ---
        kit: check
        ---
        # 🚗 Car

        Stays in the car. Tick as you check each spot, then Mark checked.

        ## Driver's door
        - [ ] 🧤 Mechanix gloves
        - [ ] 🪙 Loose change bag
        - [ ] 🔦 Head torch
        - [ ] 🖊️ Pen organiser

        ## Passenger door
        - [ ] 🕶️ Nat's sunglasses

        ## Glove box
        - [ ] 💊 Tan medical pouch
        - [ ] 🥄 Sporks
        - [ ] 🖊️ Mesh pen pouch
        - [ ] 🍴 Travel cutlery
        - [ ] 🧻 Tissues
        - [ ] 🧽 Sponge

        ## Rear passenger side
        - [ ] 🧴 Sun cream, body spray, deodorant

        ## Rear driver's door
        - [ ] 🩹 Red first aid and survival kit
        - [ ] 🛞 Tyre pressure gauge
        - [ ] 🧽 Blue cloth

        ## Seat back net
        - [ ] 🧻 Napkins
        - [ ] 🛍️ Carrier bags

        ## Below rear cubby
        - [ ] 🎒 Foldaway backpack
        - [ ] 🔌 USB cable

        ## Boot side net
        - [ ] 🩹 Green first aid kit
        - [ ] 🦴 Dog treats

        ## Boot
        - [ ] 🧰 TSTAK box (jumper and warm coat)
        - [ ] ☂️ Umbrella
        - [ ] 🧢 Cap
        - [ ] 🛍️ Shopping bags

        ## Under boot floor
        - [ ] 🔧 Ratchet set
        - [ ] 🧽 Microfibre cloths
        - [ ] 🪛 Screwdriver roll
        - [ ] 👓 Spare glasses
        - [ ] 🧊 Ice scraper
        - [ ] 🧥 Thin jacket and gloves
        - [ ] 🧴 Kids' sun cream
        - [ ] 🧹 Lint roller
        - [ ] 🛞 Tyre inflator

        ## Car kit
        - [ ] 🔋 Spare lithium batteries
        - [ ] 🥶 Foil blankets ×2
        - [ ] 🌧️ Poncho
        - [ ] 🛠️ Multi-tool
        - [ ] 🔥 Lighter
        - [ ] 📓 Notepad and pencil
        - [ ] 🧦 Socks
    """.trimIndent() + "\n"

    val GO_BAG_STARTER = """
        ---
        kit: check
        ---
        # 🆘 Go bag

        tomtoc sling, kept light. Tick as you check, then Mark checked.

        ## Go bag sling
        - [ ] 🩹 First aid pouch (plasters, painkillers, meds)
        - [ ] 🔋 Power bank and USB-C cable
        - [ ] 👛 Zip wallet (ID copies, emergency contacts, cash)
        - [ ] 🍫 Ration bars
    """.trimIndent() + "\n"

    val STARTERS = listOf(PACKING to PACKING_STARTER, POUCH to POUCH_STARTER, CAR to CAR_STARTER, GO_BAG to GO_BAG_STARTER)
}
