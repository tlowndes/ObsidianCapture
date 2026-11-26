package uk.tomlowndes.capture

/**
 * What this device ticked lately: the done line, when, and the follow-up line a repeating task left. Un-ticking
 * looks here to take the follow-up away again, and the done list shows the times. Keeps the last [KEEP].
 * Kept in a small file by [VaultWriter], so the phone and Windows both remember the same way.
 */
object TickLog {
    data class Tick(val doneLine: String, val at: Long, val nextLine: String?)

    const val KEEP = 30

    /** [log] with this tick added (a line ticked again keeps only its newest entry). */
    fun add(log: List<Tick>, tick: Tick): List<Tick> = (log.filter { it.doneLine != tick.doneLine } + tick).takeLast(KEEP)

    fun remove(log: List<Tick>, doneLine: String): List<Tick> = log.filter { it.doneLine != doneLine }

    fun nextFor(log: List<Tick>, doneLine: String): String? = log.lastOrNull { it.doneLine == doneLine }?.nextLine

    // One tick per line, fields split by \u0001 (lines never hold control characters).
    fun encode(log: List<Tick>): String = log.joinToString("\n") { "${it.doneLine}\u0001${it.at}\u0001${it.nextLine.orEmpty()}" }

    fun decode(text: String?): List<Tick> = text.orEmpty().split("\n").mapNotNull { row ->
        val f = row.split("\u0001")
        if (f.size == 3 && f[0].isNotBlank()) Tick(f[0], f[1].toLongOrNull() ?: 0L, f[2].ifBlank { null }) else null
    }
}
