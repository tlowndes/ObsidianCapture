package uk.tomlowndes.capture

import java.util.Locale

/** A "Use when" line on a kit: the same words as a rule's head, with nothing to add. */
data class KitTrigger(val kind: KitRule.Kind, val arg: String = "")

/**
 * Where one kit item sits (kits-backpack-v1): its group (take, charge, wear) and the rule that adds it.
 * [kind] null is the plain list: Always for the bag, the plain Charge or Wear list otherwise.
 */
data class KitSlot(val group: KitGroup = KitGroup.BAG, val kind: KitRule.Kind? = null, val arg: String = "")

/** One kit on today's pass, with what it adds and why it's on ("work", "rain"; null when always on or tapped on). */
data class KitPack(val kit: Kit, val items: List<KitItem>, val why: String? = null)

/**
 * Kits (kits-backpack-v1): one note per kit in 99 - Meta/Kits, each with the same sections. Pocket has no
 * Use when, so it's always on; Backpack switches itself on when one of its Use when lines fits the trip, or by a
 * tap for today. Every kit is edited in the app; the note stays plain Markdown for Obsidian.
 */
object Kits {
    const val DIR = "${FlightNotes.DIR}/Kits"
    const val POCKET = "Pocket"
    const val BACKPACK = "Backpack"

    fun path(name: String) = "$DIR/$name.md"

    val POCKET_STARTER = """
        # 👖 Pocket

        Always takes these. Under When, each line is a rule, a ·, then what to add.
        Rules: rain, cold, event <word in the title>, long <hours>.
        Night Pad uses Charge and Wear the evening before. Edit it in Flight Deck or here.

        ## Always
        - 🔑 Keys
        - 👛 Wallet
        - 📱 Phone
        - 🎧 Headphones

        ## When
        - rain · ☂️ Umbrella
        - cold · 🧣 Scarf
    """.trimIndent() + "\n" + NightPad.SECTIONS

    val BACKPACK_STARTER = """
        # 🎒 Backpack

        Comes along when a Use when line fits the day, or when you tap it on Launch Pad.
        Night Pad packs it the evening before. Edit it in Flight Deck or here.

        ## Use when
        - event work
        - long 4

        ## Always
        - 💻 Laptop
        - 🔌 Charger
        - 💧 Water bottle

        ## When
        - long 4 · 🍎 Snack, 🔋 Power bank
        - rain · 🧥 Packable jacket

        ## Charge
        - 🔋 Power bank
    """.trimIndent() + "\n"

    private val TITLE = Regex("^#\\s+(.+?)\\s*$")

    /** A kit note's text as a [Kit]. The name comes from the file; the emoji from the # title, if it has one. */
    fun parse(name: String, text: String, path: String? = null): Kit {
        val k = LaunchPad.kit(text)
        val title = text.lines().firstNotNullOfOrNull { TITLE.matchEntire(it)?.groupValues?.get(1) }
        val emoji = title?.substringBefore(" ")?.takeIf { title.contains(" ") && !it.first().isLetterOrDigit() }
            ?: if (name.equals(POCKET, true)) "👖" else "🎒"
        return k.copy(name = name, emoji = emoji, path = path)
    }

    /** A Use when line: `event work`, `long 4`, `rain`, `cold 3`. */
    fun trigger(body: String): KitTrigger? {
        val head = body.trim().lowercase(Locale.UK)
        val word = head.substringBefore(" ")
        val arg = head.substringAfter(" ", "").trim()
        val kind = when (word) {
            "rain" -> KitRule.Kind.RAIN
            "cold" -> KitRule.Kind.COLD
            "event" -> KitRule.Kind.EVENT
            "long" -> KitRule.Kind.LONG
            else -> return null
        }
        if (kind == KitRule.Kind.EVENT && arg.isEmpty()) return null
        return KitTrigger(kind, arg)
    }

    fun head(kind: KitRule.Kind, arg: String): String = when (kind) {
        KitRule.Kind.RAIN -> "rain"
        KitRule.Kind.COLD -> if (arg.isEmpty()) "cold" else "cold $arg"
        KitRule.Kind.EVENT -> "event $arg"
        KitRule.Kind.LONG -> "long ${arg.ifEmpty { "4" }}"
    }

    fun reason(kind: KitRule.Kind, arg: String): String = when (kind) {
        KitRule.Kind.RAIN -> "rain"
        KitRule.Kind.COLD -> "cold"
        KitRule.Kind.EVENT -> arg
        KitRule.Kind.LONG -> "long trip"
    }

    /** Words for a rule or trigger on the Kits page. */
    fun words(kind: KitRule.Kind, arg: String): String = when (kind) {
        KitRule.Kind.RAIN -> "Rain over ${LaunchPad.RAIN_AT}%"
        KitRule.Kind.COLD -> "Low of ${arg.toIntOrNull() ?: 5}° or less"
        KitRule.Kind.EVENT -> "Event says \"$arg\""
        KitRule.Kind.LONG -> "Out ${arg.ifEmpty { "4" }} hours or more"
    }

    private fun itemText(i: KitItem) = if (i.emoji == "•") i.name else "${i.emoji} ${i.name}"

    // ---- Which kits come today ----

    /** Why a kit switches itself on for this trip: the first Use when line that fits. Null if none fits. */
    fun triggeredBy(k: Kit, t: Trip): KitTrigger? =
        k.useWhen?.firstOrNull { LaunchPad.applies(KitRule(it.kind, it.arg, emptyList()), t) }

    /** On by itself: no Use when (always), or a Use when line fits. */
    fun auto(k: Kit, t: Trip): Boolean = k.useWhen == null || triggeredBy(k, t) != null

    /** Today's kits. [on] and [off] are taps for today and beat the rules. */
    fun active(kits: List<Kit>, t: Trip, on: Set<String> = emptySet(), off: Set<String> = emptySet()): List<Kit> =
        kits.filter { it.name in on || (it.name !in off && auto(it, t)) }

    /** Each active kit with its items for the trip. An item already in an earlier kit isn't listed twice. */
    fun packs(active: List<Kit>, t: Trip, on: Set<String> = emptySet()): List<KitPack> {
        val seen = mutableSetOf<String>()
        return active.map { k ->
            val items = LaunchPad.itemsFor(k, t).filter { seen.add(it.name.lowercase(Locale.UK)) }
            val why = if (k.name in on) null else triggeredBy(k, t)?.let { reason(it.kind, it.arg) }
            KitPack(k, items, why)
        }
    }

    // ---- The vault ----

    /** Every kit, Pocket first. The first time, the old Launch Pad kit note moves to Kits/Pocket.md and Backpack is added. */
    fun loadAll(v: Vault): List<Kit> {
        ensure(v)
        return v.list(DIR).filter { !it.isDir && it.name.endsWith(".md") }
            .map { e -> val name = e.name.removeSuffix(".md"); parse(name, VaultIo.read(v, join(DIR, e.name)), join(DIR, e.name)) }
            .sortedWith(compareBy<Kit> { !it.name.equals(POCKET, true) }.thenBy { it.name.lowercase(Locale.UK) })
    }

    /** Makes the Kits folder once, moving the old single kit note into it. Never runs again once a kit exists. */
    fun ensure(v: Vault) {
        if (v.entry(DIR) != null && v.list(DIR).any { !it.isDir && it.name.endsWith(".md") }) return
        v.mkdirs(DIR)
        val old = if (v.entry(LaunchPad.KIT) != null) VaultIo.read(v, LaunchPad.KIT) else null
        val pocket = old?.let { o -> retitle(NightPad.upgrade(o) ?: o, "👖 $POCKET") } ?: POCKET_STARTER
        writeNew(v, POCKET, pocket)
        writeNew(v, BACKPACK, BACKPACK_STARTER)
        if (old != null) runCatching { v.delete(LaunchPad.KIT) }
    }

    private fun writeNew(v: Vault, name: String, text: String) {
        val p = if (v.entry(path(name)) != null) path(name) else v.create(DIR, name, "md")
        VaultIo.write(v, p, text)
    }

    /** Swaps the note's # title (or adds one) so the kit shows its emoji. */
    fun retitle(text: String, title: String): String {
        val lines = text.split("\n").toMutableList()
        val i = lines.indexOfFirst { TITLE.matches(it) }
        if (i >= 0) lines[i] = "# $title" else lines.addAll(0, listOf("# $title", ""))
        return lines.joinToString("\n")
    }

    /** Saves [k] back to its note, keeping the intro and any sections the app doesn't know. */
    fun save(v: Vault, k: Kit) {
        val p = k.path ?: path(k.name)
        if (v.entry(p) == null) { v.mkdirs(DIR); VaultIo.write(v, v.create(DIR, k.name, "md"), render(k, "")); return }
        VaultIo.edit(v, p) { lines -> val out = render(k, lines.joinToString("\n")); lines.clear(); lines.addAll(out.split("\n")) }
    }

    /** A new, empty kit that only comes when tapped on or once it has a Use when line. */
    fun create(v: Vault, name: String, emoji: String): Kit {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "").ifEmpty { "Kit" }
        val k = Kit(emptyList(), emptyList(), name = clean, emoji = emoji.ifBlank { "🎒" }, useWhen = emptyList())
        v.mkdirs(DIR)
        val p = v.create(DIR, clean, "md")
        VaultIo.write(v, p, render(k, ""))
        return k.copy(name = nameOf(p).removeSuffix(".md"), path = p)
    }

    /** Deletes a kit's note. Pocket stays. */
    fun delete(v: Vault, k: Kit) { if (!k.name.equals(POCKET, true)) v.delete(k.path ?: path(k.name)) }

    private val KNOWN = setOf("use when", "always", "when", "charge", "wear")

    /** The kit as Markdown. [original]'s intro lines and unknown sections are kept as they were. */
    fun render(k: Kit, original: String): String {
        val intro = mutableListOf<String>()
        val extra = mutableListOf<String>()
        var section: String? = null
        original.split("\n").forEach { line ->
            if (line.startsWith("## ")) { section = line.removePrefix("## ").trim().lowercase(Locale.UK); if (section !in KNOWN) extra += line; return@forEach }
            when {
                section == null -> if (!TITLE.matches(line)) intro += line
                section !in KNOWN -> extra += line
            }
        }
        val out = mutableListOf("# ${k.emoji} ${k.name}")
        val introText = intro.joinToString("\n").trim()
        out += ""
        if (introText.isNotEmpty()) { out += introText; out += "" }
        fun rules(g: KitGroup) = k.rules.filter { it.group == g }.map { r -> "- ${head(r.kind, r.arg)} · ${r.items.joinToString(", ") { itemText(it) }}" }
        k.useWhen?.let { u -> out += "## Use when"; u.forEach { out += "- ${head(it.kind, it.arg)}" }; out += "" }
        out += "## Always"; k.always.forEach { out += "- ${itemText(it)}" }; out += ""
        out += "## When"; out += rules(KitGroup.BAG); out += ""
        out += "## Charge"; k.charge.forEach { out += "- ${itemText(it)}" }; out += rules(KitGroup.CHARGE); out += ""
        out += "## Wear"; k.wear.forEach { out += "- ${itemText(it)}" }; out += rules(KitGroup.WEAR)
        if (extra.isNotEmpty()) { out += ""; out += extra.joinToString("\n").trimEnd() }
        return out.joinToString("\n").trimEnd() + "\n"
    }

    // ---- Editing ----

    /** Every place an item sits, in page order, for the Kits page. */
    fun entries(k: Kit): List<Pair<KitSlot, KitItem>> =
        k.always.map { KitSlot() to it } +
            k.rules.filter { it.group == KitGroup.BAG }.flatMap { r -> r.items.map { KitSlot(r.group, r.kind, r.arg) to it } } +
            k.charge.map { KitSlot(KitGroup.CHARGE) to it } + k.wear.map { KitSlot(KitGroup.WEAR) to it } +
            k.rules.filter { it.group != KitGroup.BAG }.flatMap { r -> r.items.map { KitSlot(r.group, r.kind, r.arg) to it } }

    private fun same(a: KitItem, name: String) = a.name.equals(name.trim(), true)

    /** Adds [item] to [slot]. A rule with the same head takes it; otherwise a new rule line is made. */
    fun put(k: Kit, item: KitItem, slot: KitSlot): Kit {
        val clean = item.copy(reason = slot.kind?.let { reason(it, slot.arg) })
        if (slot.kind == null) return when (slot.group) {
            KitGroup.BAG -> if (k.always.any { same(it, item.name) }) k else k.copy(always = k.always + clean.copy(reason = null))
            KitGroup.CHARGE -> if (k.charge.any { same(it, item.name) }) k else k.copy(charge = k.charge + clean.copy(reason = null))
            KitGroup.WEAR -> if (k.wear.any { same(it, item.name) }) k else k.copy(wear = k.wear + clean.copy(reason = null))
        }
        val i = k.rules.indexOfFirst { it.group == slot.group && it.kind == slot.kind && it.arg == slot.arg }
        if (i < 0) return k.copy(rules = k.rules + KitRule(slot.kind, slot.arg, listOf(clean), slot.group))
        val r = k.rules[i]
        if (r.items.any { same(it, item.name) }) return k
        return k.copy(rules = k.rules.toMutableList().also { it[i] = r.copy(items = r.items + clean) })
    }

    /** Takes the item called [name] out of [slot]. A rule left empty goes too. */
    fun remove(k: Kit, slot: KitSlot, name: String): Kit {
        if (slot.kind == null) return when (slot.group) {
            KitGroup.BAG -> k.copy(always = k.always.filterNot { same(it, name) })
            KitGroup.CHARGE -> k.copy(charge = k.charge.filterNot { same(it, name) })
            KitGroup.WEAR -> k.copy(wear = k.wear.filterNot { same(it, name) })
        }
        return k.copy(rules = k.rules.mapNotNull { r ->
            if (r.group == slot.group && r.kind == slot.kind && r.arg == slot.arg) r.copy(items = r.items.filterNot { same(it, name) }).takeIf { it.items.isNotEmpty() }
            else r
        })
    }

    /** Edits an item in place when it stays in the same slot; otherwise moves it to the end of the new one. */
    fun replace(k: Kit, slot: KitSlot, name: String, item: KitItem, to: KitSlot): Kit {
        if (slot != to) return put(remove(k, slot, name), item, to)
        fun swap(list: List<KitItem>, reason: String?) = list.map { if (same(it, name)) item.copy(reason = reason) else it }
        if (slot.kind == null) return when (slot.group) {
            KitGroup.BAG -> k.copy(always = swap(k.always, null))
            KitGroup.CHARGE -> k.copy(charge = swap(k.charge, null))
            KitGroup.WEAR -> k.copy(wear = swap(k.wear, null))
        }
        return k.copy(rules = k.rules.map { r ->
            if (r.group == slot.group && r.kind == slot.kind && r.arg == slot.arg) r.copy(items = swap(r.items, reason(r.kind, r.arg))) else r
        })
    }

    /** Moves an Always item from [from] to [to]. */
    fun moveAlways(k: Kit, from: Int, to: Int): Kit {
        if (from !in k.always.indices || to !in k.always.indices || from == to) return k
        return k.copy(always = k.always.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    fun addTrigger(k: Kit, t: KitTrigger): Kit {
        val u = k.useWhen.orEmpty()
        return if (u.contains(t)) k else k.copy(useWhen = u + t)
    }

    /** Removing the last Use when line leaves an empty section, so the kit only comes when tapped on. */
    fun removeTrigger(k: Kit, t: KitTrigger): Kit = k.copy(useWhen = k.useWhen?.filterNot { it == t })
}
