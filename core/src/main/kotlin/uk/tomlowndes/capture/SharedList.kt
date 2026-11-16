package uk.tomlowndes.capture

import java.time.LocalDateTime

/** One open shopping item, and whether the partner added it on the shared page. */
data class SharedItem(val text: String, val partner: Boolean)

/**
 * Shared household list (extras-v1, idea 17). The Oracle box serves one private page (box/shared) that can only read
 * and write the Shopping list and Chores notes. Items the partner adds end in " 👥"; chores they tick get "· by partner".
 * The box writes `99 - Meta/Shared list.md` (frontmatter `updated:`) so the app knows the page is on.
 */
object SharedList {
    const val MARK = "👥"
    const val STATE = "${FlightNotes.DIR}/Shared list.md"
    const val PARTNER = "partner"

    fun fromPartner(item: String) = item.trimEnd().endsWith(MARK)
    fun clean(item: String) = item.trimEnd().removeSuffix(MARK).trimEnd()
    fun item(raw: String) = SharedItem(clean(raw), fromPartner(raw))

    /** Open items with who added them. */
    fun open(shoppingText: String): List<SharedItem> = Recipes.shoppingOpen(shoppingText).map(::item)

    /** When the box last wrote, or null when the shared page isn't set up. */
    fun updated(stateText: String?): LocalDateTime? {
        val lines = stateText?.replace("\r", "")?.split("\n") ?: return null
        val u = Md.fmGet(lines, "updated")?.trim()?.trim('"') ?: return LocalDateTime.MIN
        return runCatching { LocalDateTime.parse(u.take(19)) }.getOrDefault(LocalDateTime.MIN)
    }

    fun on(v: Vault): Boolean = v.entry(STATE) != null
}
