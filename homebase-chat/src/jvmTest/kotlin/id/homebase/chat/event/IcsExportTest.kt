package id.homebase.chat.event

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Pins the wire format of the .ics export. These assertions describe
 * what RFC-5545 consumers (Apple Calendar, Outlook, Google Calendar,
 * Thunderbird, GNOME Calendar) actually read, so a change here
 * means the imported invite would land at the wrong time or with
 * the wrong zone label.
 */
@OptIn(ExperimentalUuidApi::class)
class IcsExportTest {

    private val fixedUid = Uuid.fromLongs(1L, 2L)
    private val fixedStamp = Date(0L)

    private fun descriptor(
        tz: String,
        local: LocalDateTime,
        durationMin: Long = 60,
    ): EventDescriptor {
        val zone = runCatching { TimeZone.of(tz) }.getOrDefault(TimeZone.UTC)
        val startMs = local.toInstant(zone).toEpochMilliseconds()
        return EventDescriptor(
            title = "Standup",
            description = "",
            startUtcMs = startMs,
            endUtcMs = startMs + durationMin * 60_000L,
            timezone = tz,
        )
    }

    @Test
    fun tokyo_event_emits_tzid_local_times_and_vtimezone_with_plus_0900() {
        val ics = buildIcs(
            event = descriptor("Asia/Tokyo", LocalDateTime(2026, 5, 12, 9, 0)),
            messageId = fixedUid,
            dtstampOverride = fixedStamp,
        )

        assertTrue("BEGIN:VTIMEZONE" in ics, "VTIMEZONE block missing:\n$ics")
        assertTrue("TZID:Asia/Tokyo" in ics, "TZID line missing")
        assertTrue("TZOFFSETFROM:+0900" in ics, "JST offset wrong")
        assertTrue("TZOFFSETTO:+0900" in ics)
        assertTrue("DTSTART;TZID=Asia/Tokyo:20260512T090000" in ics, "DTSTART wrong:\n$ics")
        assertTrue("DTEND;TZID=Asia/Tokyo:20260512T100000" in ics)
        // DTSTART must NOT be UTC when a zone is supplied.
        assertFalse("DTSTART:20260512" in ics, "should not emit UTC DTSTART alongside TZID:\n$ics")
    }

    @Test
    fun los_angeles_event_emits_negative_offset() {
        // May is PDT (UTC−7).
        val ics = buildIcs(
            event = descriptor("America/Los_Angeles", LocalDateTime(2026, 5, 12, 9, 0)),
            messageId = fixedUid,
            dtstampOverride = fixedStamp,
        )
        assertTrue("TZID:America/Los_Angeles" in ics)
        assertTrue("TZOFFSETFROM:-0700" in ics, "PDT offset wrong:\n$ics")
        assertTrue("DTSTART;TZID=America/Los_Angeles:20260512T090000" in ics)
    }

    @Test
    fun unparseable_zone_falls_back_to_utc_dtstart_with_no_vtimezone() {
        // Bogus zone string — should not throw, should not emit VTIMEZONE,
        // should still emit a valid UTC DTSTART so the moment in time is preserved.
        val tokyoMs = LocalDateTime(2026, 5, 12, 9, 0)
            .toInstant(TimeZone.of("Asia/Tokyo"))
            .toEpochMilliseconds()
        val ics = buildIcs(
            event = EventDescriptor(
                title = "Standup",
                description = "",
                startUtcMs = tokyoMs,
                endUtcMs = tokyoMs + 60 * 60_000L,
                timezone = "Definitely/Not/A/Zone",
            ),
            messageId = fixedUid,
            dtstampOverride = fixedStamp,
        )
        assertFalse("BEGIN:VTIMEZONE" in ics, "should skip VTIMEZONE on unparseable zone:\n$ics")
        assertFalse("TZID=" in ics, "should not emit TZID on unparseable zone:\n$ics")
        // 2026-05-12 09:00 JST == 2026-05-12 00:00 UTC.
        assertTrue("DTSTART:20260512T000000Z" in ics, "UTC fallback wrong:\n$ics")
        assertTrue("DTEND:20260512T010000Z" in ics)
    }
}
