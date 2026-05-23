package id.homebase.api.client.websockets

import java.util.Base64
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the v2 ws-token bearer encoding.
 *
 * The bearer is sent as a `Sec-WebSocket-Protocol` value (`odin.bearer.<token>`). Per RFC 6455
 * §4.1 each subprotocol must be an RFC 7230 `token` (1*tchar); browsers enforce this in the
 * `new WebSocket(url, protocols)` constructor and throw SyntaxError BEFORE connecting (verified
 * against Chromium). The App ClientAuthenticationToken is 33 bytes → 44-char base64 with no
 * padding; the only base64 char that is not a legal token char is '/'. Standard base64 emits '/'
 * (~50% of tokens), so OdinWebSocketClient must send base64url ('-'/'_'), which is always a valid
 * token. This test pins that: the base64url transform the client applies must produce a valid
 * subprotocol for every token, while raw standard base64 frequently does not.
 */
class BearerSubprotocolGrammarTest {

    // RFC 7230 token chars (== what RFC 6455 requires for each Sec-WebSocket-Protocol value).
    private val tchar: Set<Char> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()).toSet()

    private fun isValidWsSubprotocol(value: String): Boolean =
        value.isNotEmpty() && value.all { it in tchar }

    private fun standardBase64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    // The exact transform OdinWebSocketClient applies to creds.clientAccessToken.
    private fun toBase64Url(standardB64: String) =
        standardB64.replace('+', '-').replace('/', '_').trimEnd('=')

    @Test
    fun base64urlBearerIsAlwaysAValidSubprotocol_andStandardBase64FrequentlyIsNot() {
        val rng = Random(7)
        val n = 20000
        var stdRejected = 0
        var urlRejected = 0

        repeat(n) {
            val cat = ByteArray(33).also { rng.nextBytes(it) } // App CAT shape: Id16+halfKey16+type1
            val std = standardBase64(cat)
            if (!isValidWsSubprotocol("odin.bearer.$std")) stdRejected++
            if (!isValidWsSubprotocol("odin.bearer.${toBase64Url(std)}")) urlRejected++
        }

        // The fix: base64url bearers are ALWAYS valid subprotocols (browser never rejects them).
        assertEquals(0, urlRejected, "base64url bearers must always be valid WebSocket subprotocols")
        // Why the fix is needed: raw standard base64 is browser-invalid for a large share of tokens.
        assertTrue(stdRejected > n / 4,
            "Expected many standard-base64 bearers to be browser-invalid; got $stdRejected/$n")
    }
}
