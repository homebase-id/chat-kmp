package id.homebase.api.sync.database

import id.homebase.api.sync.database.DatabaseFileState.Absent
import id.homebase.api.sync.database.DatabaseFileState.Encrypted
import id.homebase.api.sync.database.DatabaseFileState.Plaintext
import id.homebase.api.sync.database.DatabaseFileState.Unreadable
import id.homebase.api.sync.database.RawKeyDecision.Create
import id.homebase.api.sync.database.RawKeyDecision.FailKeyLost
import id.homebase.api.sync.database.RawKeyDecision.FailUnavailable
import id.homebase.api.sync.database.RawKeyDecision.OpenUnencrypted
import id.homebase.api.sync.database.RawKeyDecision.Use
import kotlin.test.Test
import kotlin.test.assertEquals

class RawKeyDecisionTest {

    private val hex = "ab".repeat(SqlCipherRawKey.BYTES)
    private val found = RawKeyRead.Found(hex)
    private val corrupt = RawKeyRead.Found("not-a-key")
    private val missing = RawKeyRead.Missing
    private val locked = RawKeyRead.Unavailable("OSStatus -25308")

    private val table: List<Triple<RawKeyRead, DatabaseFileState, RawKeyDecision>> = listOf(
        Triple(found, Absent, Use(hex)),
        Triple(found, Plaintext, Use(hex)),
        Triple(found, Encrypted, Use(hex)),
        Triple(found, Unreadable, FailUnavailable),

        Triple(corrupt, Absent, Create),
        Triple(corrupt, Plaintext, Create),
        Triple(corrupt, Encrypted, FailKeyLost),
        Triple(corrupt, Unreadable, FailUnavailable),

        Triple(missing, Absent, Create),
        Triple(missing, Plaintext, Create),
        Triple(missing, Encrypted, FailKeyLost),
        Triple(missing, Unreadable, FailUnavailable),

        Triple(locked, Absent, FailUnavailable),
        Triple(locked, Plaintext, OpenUnencrypted),
        Triple(locked, Encrypted, FailUnavailable),
        Triple(locked, Unreadable, FailUnavailable),
    )

    @Test
    fun everyKeyAndFileStateHasTheExpectedDecision() {
        val wrong = table.mapNotNull { (key, file, expected) ->
            val actual = decideRawKey(key, file)
            if (actual == expected) null else "$key × $file: expected $expected, got $actual"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun tableCoversEveryCombination() {
        val reads = listOf(found, corrupt, missing, locked)
        assertEquals(reads.size * DatabaseFileState.entries.size, table.map { it.first to it.second }.toSet().size)
    }
}
