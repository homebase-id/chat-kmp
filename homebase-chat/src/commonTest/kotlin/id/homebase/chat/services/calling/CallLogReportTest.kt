package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallLogReportTest {
    private val id = "11111111-aaaa-bbbb-cccc-222222222222"
    private val other = "99999999-aaaa-bbbb-cccc-000000000000"

    private fun line(sec: Int, tag: String, msg: String) = "2026-10-26T10:00:${sec.toString().padStart(2, '0')}.123456Z Info: ($tag) $msg"

    @Test
    fun keepsLinesNamingTheCallAndCallTaggedEngineLinesInsideItsWindow() {
        val file = listOf(
            line(0, "HttpIO", "→ GET unrelated"),
            line(1, "CallStateMachine", "call=$id Idle -> Outgoing on UserCall"),
            line(2, "PeerLink", "link state Disconnected everConnected=true"),
            line(3, "HttpIO", "→ GET unrelated"),
            line(4, "CallStateMachine", "call=$id Connected -> Reconnecting on media Disconnected attempt=1"),
            line(20, "PeerLink", "link state Connected everConnected=true"),
        )
        val kept = CallLogReport.select(listOf(file.asSequence()), id)
        assertEquals(listOf(1, 2, 4), kept.map { it.substring(17, 19).toInt() })
    }

    @Test
    fun dropsEngineLinesThatBelongToAnotherCall() {
        val file = listOf(
            line(1, "CallStateMachine", "call=$id Idle -> Outgoing on UserCall"),
            line(2, "CallController", "call=$other timer Ring started"),
            line(3, "CallStateMachine", "call=$id Outgoing -> Connecting on signal Answer"),
        )
        val kept = CallLogReport.select(listOf(file.asSequence()), id)
        assertEquals(2, kept.size)
        assertTrue(kept.none { other in it })
    }

    @Test
    fun keepsStackTraceLinesOfAKeptEntry() {
        val file = listOf(
            line(1, "CallController", "call=$id call effect failed"),
            "java.lang.IllegalStateException: boom",
            "\tat id.homebase.Foo.bar(Foo.kt:1)",
            line(2, "HttpIO", "→ GET unrelated"),
        )
        val kept = CallLogReport.select(listOf(file.asSequence()), id)
        assertEquals(3, kept.size)
        assertTrue(kept.last().startsWith("\tat "))
    }

    @Test
    fun mergesRolledFilesInTimeOrder() {
        val older = listOf(line(1, "CallStateMachine", "call=$id Idle -> Outgoing on UserCall"))
        val newer = listOf(line(9, "CallStateMachine", "call=$id Reconnecting -> Ended on timer Reconnect"))
        assertEquals(listOf(1, 9), CallLogReport.select(listOf(newer.asSequence(), older.asSequence()), id).map { it.substring(17, 19).toInt() })
    }

    @Test
    fun redactsKeyMaterial() {
        val file = listOf(
            line(1, "CallController", "call=$id applying a=ice-pwd:SECRETPASSWORD123 key=abcdef token: xyz"),
        )
        val kept = CallLogReport.select(listOf(file.asSequence()), id).single()
        assertFalse("SECRETPASSWORD123" in kept)
        assertFalse("abcdef" in kept)
        assertFalse("xyz" in kept)
        assertTrue("[redacted]" in kept)
    }

    @Test
    fun anUnknownCallYieldsNothing() {
        assertTrue(CallLogReport.select(listOf(sequenceOf(line(1, "CallController", "call=$other x"))), id).isEmpty())
        assertTrue(CallLogReport.select(listOf(sequenceOf(line(1, "CallController", "call=$other x"))), "").isEmpty())
    }

    @Test
    fun anUntaggedLineStartsItsOwnEntryInsteadOfJoiningTheCallLineAbove() {
        val file = listOf(
            line(1, "CallStateMachine", "call=$id Idle -> Outgoing on UserCall"),
            "2026-10-26T10:00:02.123456Z Warn: unrelated untagged line",
        )
        assertEquals(1, CallLogReport.select(listOf(file.asSequence()), id).size)
    }

    @Test
    fun exportNameCarriesOnlySafeCharacters() {
        assertEquals("call-report-ab-12.txt", CallLogReport.exportName("ab/../-12"))
    }

    @Test
    fun renderPutsTheHeaderFirst() {
        val text = CallLogReport.render(listOf("Homebase call report", "app=1.0"), listOf("a", "b"))
        assertEquals("Homebase call report\napp=1.0\n\na\nb\n", text)
    }
}
