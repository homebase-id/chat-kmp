package id.homebase.core.lists.model

/**
 * Fractional order keys for manual drag-reorder. Keys are short strings compared
 * lexicographically. The first character is always in 'a'..'z' (visible); characters at
 * deeper positions may be any char code ≥ 1, allowing fine-grained insertion between a
 * short key and its 'a'-extended neighbour without limit. No renumbering is ever needed.
 */
object ListSortKeys {
    private const val MIN = 'a'
    private const val MAX = 'z'
    private const val MID = 'n'

    /** Key for the first item in a fresh list. */
    fun first(): String = MID.toString()

    /** A key that sorts strictly after [last]. */
    fun after(last: String): String = between(last, null)

    /**
     * A key strictly greater than [a] (or after nothing, when a is null) and strictly
     * less than [b] (or before nothing, when b is null).
     *
     * The algorithm treats each string as a base-∞ fraction: missing trailing characters
     * have the implicit value 0 (below any printable character). This means "n" and "na"
     * are not adjacent — "n" sits between them — so the recursion always terminates
     * with a valid midpoint no matter how many halvings have already occurred.
     *
     * Position 0 of every key is always a visible ASCII letter ('a'..'z'). Deeper positions
     * may contain any char whose code is ≥ 1 (they sort correctly via standard Char ordering).
     */
    fun between(a: String?, b: String?): String {
        val lo = a ?: ""
        val sb = StringBuilder()
        var i = 0
        while (true) {
            // Treat missing lo characters as code 0 (implicit floor below every printable char).
            val lc: Int = if (i < lo.length) lo[i].code else 0
            // Treat missing b characters as MAX+1 (open upper bound) when b is exhausted;
            // null b means "no upper bound".
            val hc: Int = when {
                b == null -> MAX.code + 1
                i < b.length -> b[i].code
                else -> MAX.code + 1   // b exhausted: any extension is strictly < b (prefix rule)
            }

            val gap = hc - lc
            if (gap >= 2) {
                // Room for a midpoint strictly between lc and hc.
                val mid = lc + gap / 2
                // Position 0: clamp to visible alphabet ('a'..'z').
                // Deeper positions: allow any char code ≥ 0. char(0) sorts below everything
                // and is a valid Kotlin String character; char(≥1) is used when the midpoint
                // falls in the visible range naturally.
                val out = if (i == 0) mid.coerceIn(MIN.code, MAX.code) else mid
                sb.append(out.toChar())
                return sb.toString()
            }

            // gap == 0 or gap == 1 — no room for a midpoint here; go one level deeper.
            //
            // When gap == 1 and we are past lo: we append lc (the floor value at this
            // position — may be 0) so that this digit is strictly below b[i] = lc+1.
            // The next iteration then has an open upper bound (b is exhausted one level
            // deeper) giving gap ≥ 2 and a valid midpoint.  We do NOT stop here because
            // returning the key as-is would make it adjacent to lo (e.g. "n" and "n\0"
            // would be prefix-adjacent with nothing between them).
            //
            // When gap == 0: copy the common digit and continue.
            val appendCode: Int = when {
                gap == 1 && i >= lo.length -> lc          // past lo, gap=1: pin to floor
                i < lo.length              -> lo[i].code   // within lo: copy lo's digit
                else                       -> b!![i].code  // past lo, gap=0: copy b's digit
            }
            // Position 0 must stay in the visible alphabet.
            val out = if (i == 0) appendCode.coerceIn(MIN.code, MAX.code) else appendCode
            sb.append(out.toChar())
            i++
        }
    }
}
