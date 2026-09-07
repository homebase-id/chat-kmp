package id.homebase.api.client.websockets

// Ktor's WebSocketException carries no status code — a rejected upgrade survives only as the
// message "Handshake exception, expected status code 101 but was 401" (WebSockets.kt:233).
// OkHttp and Darwin both special-case 401 so it reaches us as that exception rather than a raw
// engine error; CIO produces it for any non-101. WebSocketUpgradeAuthTest pins the wording, so a
// Ktor upgrade that reworded it fails the build instead of silently disabling the logout.
private const val UPGRADE_401_MARKER = "but was 401"

fun Throwable.isWebSocketUpgradeUnauthorized(): Boolean {
    val seen = HashSet<Throwable>()
    var cur: Throwable? = this
    while (cur != null && seen.add(cur)) {
        if (cur.message?.contains(UPGRADE_401_MARKER) == true) return true
        cur = cur.cause
    }
    return false
}
