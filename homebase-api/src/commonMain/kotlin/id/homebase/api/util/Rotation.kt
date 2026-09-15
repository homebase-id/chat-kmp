package id.homebase.api.util

/** 90°/270° for any sign or turn count — the frame's display dims are its stored dims swapped. */
fun isQuarterTurn(degrees: Int): Boolean = degrees.mod(180) == 90
