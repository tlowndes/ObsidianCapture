package uk.tomlowndes.capture

/**
 * Which colour a thing gets, as a role rather than a colour. Each app maps a [ColourRole] to its own theme colour, so the
 * rules (which tag is amber, when a chore turns red) live here once.
 */
enum class ColourRole { WARN, AMBER, PRIMARY, GREEN, PURPLE }

object Tones {
    /** A chore's bar: green when fresh, blue filling, amber near due, red past due (home-life-v1). */
    fun chore(fill: Double): ColourRole = when {
        fill >= Chores.OVERDUE -> ColourRole.WARN
        fill >= 0.75 -> ColourRole.AMBER
        fill >= 0.4 -> ColourRole.PRIMARY
        else -> ColourRole.GREEN
    }

    /** Work amber, Personal green, #bill amber, the rest purple. */
    fun tag(tag: String): ColourRole = when (Tags.kind(tag)) {
        TagKind.WORK -> ColourRole.AMBER
        TagKind.PERSONAL -> ColourRole.GREEN
        TagKind.OTHER -> if (tag == "#bill") ColourRole.AMBER else ColourRole.PURPLE
    }

    /** Flag colours (priority-flags-v1) as ARGB: text and tint. Null for no priority (each app uses its muted text). */
    fun priority(p: Priority): Pair<Long, Long>? = when (p) {
        Priority.HIGHEST -> 0xFFFF6B6B to 0xFF3A1D22
        Priority.HIGH -> 0xFFFFA36B to 0xFF3A2618
        Priority.MEDIUM -> 0xFFF5C06A to 0xFF2E2616
        Priority.LOW -> 0xFF7AA2FF to 0xFF1A2340
        Priority.LOWEST -> 0xFF5C6478 to 0xFF1C212C
        Priority.NONE -> null
    }
}
