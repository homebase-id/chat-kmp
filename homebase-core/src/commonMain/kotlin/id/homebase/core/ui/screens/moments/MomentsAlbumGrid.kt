package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.moments.MomentsAlbumZoom
import coil3.compose.AsyncImage
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.util.formatMomentDate
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlin.uuid.Uuid
import id.homebase.resources.MR
import id.homebase.resources.moments_album_extra_count
import id.homebase.resources.moments_album_text_only_label
import id.homebase.resources.moments_album_zoom_day
import id.homebase.resources.moments_album_zoom_label
import id.homebase.resources.moments_album_zoom_month
import id.homebase.resources.moments_album_zoom_year
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * Photo-album view of the moments feed. Renders a thumbnail grid grouped by
 * date, with three zoom levels (Day → Month → Year). Cell density scales
 * with zoom: more columns + smaller cells as you zoom out, so an entire year
 * of moments can fit on a single screen at the Year level.
 *
 * The zoom chip row at the top is the primary affordance for switching levels;
 * tapping a group header is a secondary affordance that zooms *out* one step
 * (Year wraps back to Day).
 */
@Composable
fun MomentsAlbumGrid(
    moments: List<MomentFeedItem>,
    zoom: MomentsAlbumZoom,
    onZoomChange: (MomentsAlbumZoom) -> Unit,
    onOpenMoment: (momentId: String, payloadKey: String?) -> Unit,
    pendingLocalPreviews: ImmutableMap<Uuid, Any> = persistentMapOf(),
    modifier: Modifier = Modifier,
) {
    // Group + sort once per (moments, zoom) pair. Sorting newest-first inside
    // each group mirrors the feed's overall ordering so the per-day strip
    // reads top-left → bottom-right newest → oldest.
    val grouped = remember(moments, zoom) { groupMoments(moments, zoom) }

    Column(modifier = modifier) {
        AlbumZoomChips(
            current = zoom,
            onChange = onZoomChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(zoom.columnCount),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            grouped.forEach { group ->
                item(
                    key = "header-${group.key}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "album-header",
                ) {
                    AlbumDateHeader(
                        label = group.label(),
                        onClick = { onZoomChange(zoom.cycleOut()) },
                    )
                }
                items(
                    items = group.moments,
                    key = { it.id },
                    contentType = { "album-cell" },
                ) { moment ->
                    AlbumMomentCell(
                        moment = moment,
                        pendingLocalPreviewModel = pendingLocalPreviews[moment.id],
                        onClick = { onOpenMoment(moment.id.toString(), null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumZoomChips(
    current: MomentsAlbumZoom,
    onChange: (MomentsAlbumZoom) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = mapOf(
        MomentsAlbumZoom.Day to stringResource(MR.string.moments_album_zoom_day),
        MomentsAlbumZoom.Month to stringResource(MR.string.moments_album_zoom_month),
        MomentsAlbumZoom.Year to stringResource(MR.string.moments_album_zoom_year),
    )
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MomentsAlbumZoom.entries.forEach { z ->
            FilterChip(
                selected = z == current,
                onClick = { if (z != current) onChange(z) },
                label = { Text(text = labels.getValue(z)) },
            )
        }
    }
}

@Composable
private fun AlbumDateHeader(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun AlbumMomentCell(
    moment: MomentFeedItem,
    pendingLocalPreviewModel: Any?,
    onClick: () -> Unit,
) {
    val cellShape: Shape = RectangleShape
    val firstImagePayload = remember(moment.payloads) {
        moment.payloads.firstOrNull { it.isRenderableImage() }
            ?: moment.payloads.firstOrNull()
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(cellShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        if (firstImagePayload == null && pendingLocalPreviewModel != null) {
            // Placeholder window: row exists locally but thumbnails haven't
            // been generated yet. Render the local preview model (photo file
            // path, or a video's poster-frame JPEG bytes) so the album cell
            // shows the picked media immediately.
            AsyncImage(
                model = pendingLocalPreviewModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (firstImagePayload != null) {
            // Coil + HomebaseImage handle the encrypted-thumbnail fetch. We
            // pass `previewThumbnail` so the embedded tiny thumb shows
            // immediately while the higher-res server thumb loads.
            val payloadIv = remember(firstImagePayload.iv) {
                firstImagePayload.iv?.let {
                    runCatching { Base64.decode(it) }.getOrNull()
                }
            }
            if (payloadIv != null) {
                HomebaseImage(
                    imageData = HomebaseImageData(
                        driveId = moment.driveId,
                        fileId = moment.fileId,
                        payloadKey = firstImagePayload.key,
                        previewThumbnail = moment.previewThumbnail,
                        requestedSize = ImageSize.THUMB_MEDIUM,
                        isEncrypted = true,
                        keyHeader = id.homebase.api.client.KeyHeader(
                            iv = payloadIv,
                            aesKey = moment.keyHeader.aesKey,
                        ),
                        lastModified = firstImagePayload.lastModified,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    contentDescription = null,
                )
            } else {
                // No IV — encrypted payload we can't decrypt yet. Show
                // background only; user can still tap to open the detail
                // view which has its own progressive-load path.
            }
        } else {
            TextOnlyCell(description = moment.description)
        }

        // "+N" badge for multi-payload moments — Photos shows a small stack
        // icon; here we keep it text-only to avoid pulling another icon.
        val extra = moment.payloads.size - 1
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = stringResource(MR.string.moments_album_extra_count, extra),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

@Composable
private fun TextOnlyCell(description: String) {
    val fallback = stringResource(MR.string.moments_album_text_only_label)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = description.ifBlank { fallback },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val MomentsAlbumZoom.columnCount: Int
    get() = when (this) {
        MomentsAlbumZoom.Day -> 3
        MomentsAlbumZoom.Month -> 5
        MomentsAlbumZoom.Year -> 8
    }

private fun MomentsAlbumZoom.cycleOut(): MomentsAlbumZoom = when (this) {
    MomentsAlbumZoom.Day -> MomentsAlbumZoom.Month
    MomentsAlbumZoom.Month -> MomentsAlbumZoom.Year
    MomentsAlbumZoom.Year -> MomentsAlbumZoom.Day
}

private fun PayloadDescriptor.isRenderableImage(): Boolean {
    val ct = contentType?.lowercase() ?: return false
    return ct.startsWith("image/") || ct.startsWith("video/")
}

// Album-grouping primitives. Group key + display label live together so the
// grouping decision and the header rendering can't drift.
private sealed interface AlbumGroupKey {
    data class Day(val date: LocalDate) : AlbumGroupKey
    data class MonthOf(val year: Int, val month: Month) : AlbumGroupKey
    data class Year(val year: Int) : AlbumGroupKey
}

private data class AlbumGroup(
    val key: AlbumGroupKey,
    val moments: List<MomentFeedItem>,
) {
    @Composable
    fun label(): String = when (val k = key) {
        is AlbumGroupKey.Day -> formatMomentDate(
            k.date.atStartOfDayIn(TimeZone.currentSystemDefault())
        )
        // English-only month label ("May 2026") — kmp doesn't expose a
        // skeleton-pattern "yMMMM" formatter across targets, and the rest
        // of the codebase wires localized day-of-week names via string
        // resources (see TimeFormatter.getDayName). When this feature
        // graduates beyond an MVP, add `month_january` … `month_december`
        // resources and switch the lookup here.
        is AlbumGroupKey.MonthOf ->
            "${k.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${k.year}"
        is AlbumGroupKey.Year -> k.year.toString()
    }
}

private fun groupMoments(
    moments: List<MomentFeedItem>,
    zoom: MomentsAlbumZoom,
): List<AlbumGroup> {
    if (moments.isEmpty()) return emptyList()
    val zone = TimeZone.currentSystemDefault()
    // Album sort is by userDate (the moment's "when"). The Album view's whole
    // point is "ordered by when the moment happened," so we re-sort defensively
    // even though the VM already sorts by userDate for the Album mode.
    val sorted = moments.sortedByDescending { it.userDateMs }
    val groups = linkedMapOf<AlbumGroupKey, MutableList<MomentFeedItem>>()
    for (m in sorted) {
        val instant = Instant.fromEpochMilliseconds(m.userDateMs)
        val ldt = instant.toLocalDateTime(zone)
        val key: AlbumGroupKey = when (zoom) {
            MomentsAlbumZoom.Day -> AlbumGroupKey.Day(ldt.date)
            MomentsAlbumZoom.Month -> AlbumGroupKey.MonthOf(ldt.year, ldt.month)
            MomentsAlbumZoom.Year -> AlbumGroupKey.Year(ldt.year)
        }
        groups.getOrPut(key) { mutableListOf() }.add(m)
    }
    return groups.map { (k, v) -> AlbumGroup(key = k, moments = v) }
}
