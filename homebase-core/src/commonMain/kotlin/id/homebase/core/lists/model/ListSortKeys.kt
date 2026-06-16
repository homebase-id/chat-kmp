package id.homebase.core.lists.model

/**
 * Fractional order keys for manual drag-reorder. A key is a short string compared
 * lexicographically; [between] returns a new key strictly between two existing keys (either
 * bound may be null = open). New items append via [after]; a drag computes between(prev, next).
 * No renumbering is ever required.
 *
 * Each character position is a base-fraction digit. A position past the end of `a` reads as
 * [FLOOR] (below every real digit); a position past the end of `b` — or `b == null` — reads as
 * [CEIL] (above every real digit). That makes "n" sit strictly between "" and "na", so the
 * recursion always finds a midpoint and never collides with a bound.
 */
object ListSortKeys {
    private const val FLOOR = 0           // implicit digit at/after the end of `a`
    private const val CEIL = 'z'.code + 1 // implicit digit at/after the end of `b` (or b == null)
    private const val MID = 'n'.code      // a pleasant, middle-of-the-alphabet first key

    /** Key for the first item in a fresh list. */
    fun first(): String = MID.toChar().toString()

    /** A key that sorts strictly after [last]. */
    fun after(last: String): String = between(last, null)

    /**
     * Shortest key strictly greater than [a] (null = nothing below) and strictly less than [b]
     * (null = nothing above). If the bounds are equal or inverted (`a >= b`), gracefully appends
     * after [a] rather than throwing.
     */
    fun between(a: String?, b: String?): String {
        if (a != null && b != null && a >= b) return between(a, null)
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val lo = if (a != null && i < a.length) a[i].code else FLOOR
            val hi = if (b != null && i < b.length) b[i].code else CEIL
            if (hi - lo >= 2) {
                // Room for a digit strictly between lo and hi at this position.
                sb.append((lo + (hi - lo) / 2).toChar())
                return sb.toString()
            }
            // Gap of 0 or 1: copy a's digit (or FLOOR past a's end) and descend one position.
            // Since a < b, lo <= hi here, so this never picks a digit above the upper bound.
            sb.append((if (a != null && i < a.length) a[i].code else FLOOR).toChar())
            i++
        }
    }
}
