package id.homebase.chat.event

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_event_tz_picker_search
import id.homebase.resources.chat_event_tz_picker_title
import id.homebase.resources.chat_event_tz_region_africa
import id.homebase.resources.chat_event_tz_region_americas
import id.homebase.resources.chat_event_tz_region_antarctica
import id.homebase.resources.chat_event_tz_region_arctic
import id.homebase.resources.chat_event_tz_region_asia
import id.homebase.resources.chat_event_tz_region_atlantic
import id.homebase.resources.chat_event_tz_region_australia
import id.homebase.resources.chat_event_tz_region_europe
import id.homebase.resources.chat_event_tz_region_indian
import id.homebase.resources.chat_event_tz_region_pacific
import id.homebase.resources.chat_event_tz_suggested
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Friendly label for the form field and picker rows:
 * "Europe/Copenhagen" → "Copenhagen (GMT+2)".
 */
internal fun friendlyZoneLabel(zoneId: String, now: Instant = Clock.System.now()): String {
    val city = cityName(zoneId)
    return "$city (${currentOffsetLabel(zoneId, now)})"
}

/**
 * Offset of `zoneId` at `now`, formatted as `GMT±H` or `GMT±H:MM`
 * (half-hour zones — India, Nepal, Newfoundland, Chatham — preserved).
 */
internal fun currentOffsetLabel(zoneId: String, now: Instant = Clock.System.now()): String {
    val tz = runCatching { TimeZone.of(zoneId) }.getOrNull() ?: return "GMT"
    val totalMinutes = now.offsetIn(tz).totalSeconds / 60
    val sign = if (totalMinutes < 0) "-" else "+"
    val absMin = kotlin.math.abs(totalMinutes)
    val hours = absMin / 60
    val mins = absMin % 60
    return if (mins == 0) "GMT$sign$hours"
    else "GMT$sign$hours:${mins.toString().padStart(2, '0')}"
}

/** Last IANA segment with underscores → spaces. UTC and other no-slash zones pass through. */
internal fun cityName(zoneId: String): String =
    if ('/' in zoneId) zoneId.substringAfterLast('/').replace('_', ' ') else zoneId

/** Continent display name (English; the UI maps these to localized strings). */
internal fun continentDisplayName(zoneId: String): String = when (zoneId.substringBefore('/', missingDelimiterValue = "")) {
    "Africa" -> "Africa"
    "America" -> "Americas"
    "Antarctica" -> "Antarctica"
    "Arctic" -> "Arctic"
    "Asia" -> "Asia"
    "Atlantic" -> "Atlantic"
    "Australia" -> "Australia"
    "Europe" -> "Europe"
    "Indian" -> "Indian Ocean"
    "Pacific" -> "Pacific"
    else -> "Other"
}

/**
 * Substring match (case-insensitive) against the IANA id, the friendly
 * label (with offset), and the continent name. Lets users type either
 * "kol", "kolkata", "asia", or "+5:30" to find Asia/Kolkata.
 */
internal fun matchesQuery(zoneId: String, query: String, now: Instant = Clock.System.now()): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    if (zoneId.lowercase().contains(q)) return true
    if (friendlyZoneLabel(zoneId, now).lowercase().contains(q)) return true
    if (continentDisplayName(zoneId).lowercase().contains(q)) return true
    return false
}

/** Filter to the user-facing IANA prefixes; drops Etc, SystemV and legacy aliases. */
private fun isMainZone(zoneId: String): Boolean {
    if ('/' !in zoneId) return false
    val prefix = zoneId.substringBefore('/')
    return prefix in mainPrefixes
}

private val mainPrefixes = setOf(
    "Africa", "America", "Antarctica", "Arctic", "Asia",
    "Atlantic", "Australia", "Europe", "Indian", "Pacific",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimezonePickerSheet(
    currentZoneId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val deviceZoneId = remember { TimeZone.currentSystemDefault().id }
    val now = remember { Clock.System.now() }

    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // All "main" zones sorted by city name within each continent. UTC is
    // surfaced separately in Suggested, not in the main grouped list.
    val allMainZones = remember {
        TimeZone.availableZoneIds
            .filter { isMainZone(it) }
            .sortedWith(compareBy({ continentDisplayName(it) }, { cityName(it) }))
    }

    val filtered by remember(query) {
        derivedStateOf {
            if (query.isBlank()) allMainZones
            else allMainZones.filter { matchesQuery(it, query, now) }
        }
    }

    val grouped by remember(filtered) {
        derivedStateOf { filtered.groupBy(::continentDisplayName) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 640.dp)) {
            Text(
                text = stringResource(MR.string.chat_event_tz_picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(MR.string.chat_event_tz_picker_search)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .focusRequester(focusRequester),
            )

            val lazyState = rememberLazyListState()
            LazyColumn(modifier = Modifier.fillMaxWidth(), state = lazyState) {
                if (query.isBlank()) {
                    stickyHeader {
                        SectionHeader(stringResource(MR.string.chat_event_tz_suggested))
                    }
                    item {
                        ZoneRow(
                            zoneId = deviceZoneId,
                            selected = deviceZoneId == currentZoneId,
                            now = now,
                            onClick = { onPick(deviceZoneId) },
                        )
                    }
                    item {
                        ZoneRow(
                            zoneId = "UTC",
                            selected = currentZoneId == "UTC",
                            now = now,
                            onClick = { onPick("UTC") },
                        )
                    }
                }

                grouped.forEach { (continent, zones) ->
                    stickyHeader(key = "h-$continent") {
                        SectionHeader(localizedContinentName(continent))
                    }
                    items(zones, key = { it }) { zoneId ->
                        ZoneRow(
                            zoneId = zoneId,
                            selected = zoneId == currentZoneId,
                            now = now,
                            onClick = { onPick(zoneId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ZoneRow(zoneId: String, selected: Boolean, now: Instant, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val onBg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        val cityWithOffset = "${cityName(zoneId)} · ${currentOffsetLabel(zoneId, now)}"
        Column {
            Text(
                text = cityWithOffset,
                style = MaterialTheme.typography.bodyLarge,
                color = onBg,
            )
            Text(
                text = zoneId,
                style = MaterialTheme.typography.labelSmall,
                color = onBg.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun localizedContinentName(english: String): String {
    val resource: StringResource = when (english) {
        "Africa" -> MR.string.chat_event_tz_region_africa
        "Americas" -> MR.string.chat_event_tz_region_americas
        "Antarctica" -> MR.string.chat_event_tz_region_antarctica
        "Arctic" -> MR.string.chat_event_tz_region_arctic
        "Asia" -> MR.string.chat_event_tz_region_asia
        "Atlantic" -> MR.string.chat_event_tz_region_atlantic
        "Australia" -> MR.string.chat_event_tz_region_australia
        "Europe" -> MR.string.chat_event_tz_region_europe
        "Indian Ocean" -> MR.string.chat_event_tz_region_indian
        "Pacific" -> MR.string.chat_event_tz_region_pacific
        else -> return english
    }
    return stringResource(resource)
}
