package id.homebase.core.location.emergency

/**
 * How-far-back presets for the emergency locate request panel, in hours. Max 4 days.
 * Pure logic (no Compose) so the floor rule is jvmTest-able.
 */
val LOCATE_WINDOW_PRESETS_HOURS: List<Int> = listOf(6, 12, 24, 48, 72, 96)

const val LOCATE_WINDOW_MAX_HOURS = 96

/**
 * The selectable window options given the peer's last data point age.
 *
 * Rule: the user cannot select less than (last-point age + 1h) — a smaller window is
 * guaranteed to fetch nothing, so the floor keeps the newest data point inside every
 * offered option. Capped at [LOCATE_WINDOW_MAX_HOURS]; when even 96h is below the floor
 * (their newest point is older than ~4 days) the max option alone is offered — the
 * fetch may legitimately return nothing. `null` age ("no data yet" / unknown) offers
 * the full preset list.
 */
fun locateWindowOptionsHours(lastPointAgeMs: Long?): List<Int> {
    if (lastPointAgeMs == null) return LOCATE_WINDOW_PRESETS_HOURS
    val floorHours = (lastPointAgeMs / 3_600_000L) + 1
    val allowed = LOCATE_WINDOW_PRESETS_HOURS.filter { it >= floorHours }
    return allowed.ifEmpty { listOf(LOCATE_WINDOW_MAX_HOURS) }
}
