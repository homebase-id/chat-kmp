package id.homebase.chat.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Clock
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.location.LocationPreview
import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.theme.Dimens
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.cd_location_pin
import id.homebase.resources.chat_location_attachment
import id.homebase.resources.live_share_15m
import id.homebase.resources.live_share_1h
import id.homebase.resources.live_share_2h
import id.homebase.resources.live_share_30m
import id.homebase.resources.live_share_4h
import id.homebase.resources.live_share_active
import id.homebase.resources.live_share_ended
import id.homebase.resources.share_live_location
import id.homebase.resources.stop_sharing
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

// ─── Shared text content ────────────────────────────────────────────────────

@Composable
private fun LocationPreviewTextContent(
    address: String,
    maxAddressLines: Int = 2,
) {
    // Address only — raw lat/lon coordinates aren't human-readable, so we don't show them. Muted grey
    // (Event-style) because the address is fixed/auto-generated location metadata, not user content.
    if (address.isNotEmpty()) {
        Text(
            text = address,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = maxAddressLines,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Sender-side card (base64 image from LocationPreview) ────────────────────

/**
 * A card-style preview of a location — sender side. Decodes the base64 PNG returned by
 * `LocationPreviewProvider`. Tapping opens the user's map app via `geo:` URI.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalResourceApi::class)
@Composable
fun LocationPreviewCard(
    locationPreview: LocationPreview,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    onCancel: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val geoUri = remember(locationPreview.lat, locationPreview.lon, locationPreview.address) {
        buildGeoUri(locationPreview.lat, locationPreview.lon, locationPreview.address)
    }

    val imageBitmap = remember(locationPreview.imageUrl) {
        locationPreview.imageUrl?.let {
            try {
                Base64.decode(it.substringAfter("base64,")).decodeToImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    if (isCompact) {
        Row(
            modifier = modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)
            ).clip(RoundedCornerShape(12.dp)).clickable { uriHandler.openUri(geoUri) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                LocationPreviewTextContent(
                    address = locationPreview.address,
                    maxAddressLines = 1,
                )
            }

            if (imageBitmap != null) {
                Box(modifier = Modifier.height(80.dp).aspectRatio(1f)) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(MR.string.chat_location_attachment),
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (onCancel != null) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(MR.string.cancel),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            } else if (onCancel != null) {
                IconButton(onClick = onCancel, modifier = Modifier.padding(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.cancel),
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth().clickable { uriHandler.openUri(geoUri) }
        ) {
            Box {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = stringResource(MR.string.chat_location_attachment),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(
                            RoundedCornerShape(
                                topStart = Dimens.Message.cornerRadius,
                                topEnd = Dimens.Message.cornerRadius,
                            )
                        ),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = stringResource(MR.string.cd_location_pin),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                if (onCancel != null) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(MR.string.cancel),
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                LocationPreviewTextContent(
                    address = locationPreview.address,
                )
            }
        }
    }
}

// ─── Receiver-side card (image from drive via HomebaseImage) ─────────────────

/**
 * A card-style preview of a received location — receiver side. The map PNG is fetched from the
 * encrypted drive payload; the `geo:` deep-link tap opens the user's own map app, which means
 * the receiver never contacts a third-party map provider when rendering or interacting.
 */
@Composable
fun LocationPreviewCard(
    descriptor: LocationPreviewDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    payloadKey: String,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb? = null,
    modifier: Modifier = Modifier,
    /** Forwarded to the message actions menu — fixes long-press being swallowed by the old `.clickable`. */
    onLongPress: (() -> Unit)? = null,
    /** Live-share side + actions; null only for the pre-send staging preview. */
    liveControls: LiveLocationBubbleControls? = null,
) {
    val uriHandler = LocalUriHandler.current
    val geoUri = remember(descriptor.lat, descriptor.lon, descriptor.address) {
        buildGeoUri(descriptor.lat, descriptor.lon, descriptor.address)
    }

    // Derive STATIC / LIVE / ENDED from the descriptor's window vs now. While live, a coarse ticker
    // (<=30s) refreshes the "time left" caption; the loop lands exactly on `until` to flip to ENDED.
    val until = descriptor.liveShareUntilMs
    var nowMs by remember(until) { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(until) {
        val u = until ?: return@LaunchedEffect
        while (true) {
            val now = Clock.System.now().toEpochMilliseconds()
            nowMs = now
            if (now >= u) break
            delay(minOf(u - now, 30_000L) + 50)
        }
    }
    val isLive = until != null && nowMs < until
    val isEnded = until != null && nowMs >= until
    val remainingMs = if (until != null) (until - nowMs).coerceAtLeast(0L) else 0L

    val onCardTap = {
        if (isLive && liveControls != null) liveControls.onOpenMap() else uriHandler.openUri(geoUri)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(isLive, onLongPress) {
                detectTapGestures(
                    onTap = { onCardTap() },
                    onLongPress = { onLongPress?.invoke() },
                )
            },
    ) {
        if (descriptor.hasImage) {
            val imageData = remember(driveId, fileId, payloadKey) {
                HomebaseImageData(
                    driveId = driveId,
                    fileId = fileId,
                    payloadKey = payloadKey,
                    previewThumbnail = previewThumbnail,
                    requestedSize = ImageSize.THUMB_MEDIUM,
                    isEncrypted = true,
                    keyHeader = keyHeader,
                    loadFullPayload = true,
                )
            }
            // Fill the bubble width (no letterbox borders); the outer container rounds the corners.
            HomebaseImage(
                imageData = imageData,
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentScale = ContentScale.Crop,
                contentDescription = descriptor.address,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = stringResource(MR.string.cd_location_pin),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Column(modifier = Modifier.padding(12.dp)) {
            // ── Fixed location metadata (muted grey): address, then the share-live affordance ──
            LocationPreviewTextContent(address = descriptor.address)
            if (liveControls != null) {
                if (descriptor.address.isNotEmpty()) Spacer(modifier = Modifier.height(6.dp))
                LiveShareActionArea(
                    controls = liveControls,
                    isLive = isLive,
                    isEnded = isEnded,
                    remainingMs = remainingMs,
                )
            }
            // ── The user's own typed caption, in the regular message text color, below the fixed parts ──
            if (!descriptor.caption.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = descriptor.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The live-share action area shown directly below the map image. Sender (own message) sees a
 * "Share live location" link with a duration menu, or "Stop sharing" while live. Receiver sees a
 * read-only "sharing live / ended" caption. State (LIVE/ENDED) comes from the descriptor; this only
 * renders affordances.
 */
@Composable
private fun LiveShareActionArea(
    controls: LiveLocationBubbleControls,
    isLive: Boolean,
    isEnded: Boolean,
    remainingMs: Long,
) {
    Box {
        when {
            isLive -> {
                // Both sides show the live caption + time left; only the sender gets the Stop link.
                Column {
                    if (controls.sentByYou) {
                        Row(
                            modifier = Modifier.clickable { controls.onStop() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = stringResource(MR.string.stop_sharing),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Text(
                        text = stringResource(MR.string.live_share_active, formatRemaining(remainingMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            isEnded -> {
                // Finished — no re-share option.
                Text(
                    text = stringResource(MR.string.live_share_ended),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            controls.sentByYou -> {
                // STATIC, my own message: offer to share live.
                var menuExpanded by remember { mutableStateOf(false) }
                Column {
                    Row(
                        modifier = Modifier.clickable { menuExpanded = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = stringResource(MR.string.share_live_location),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DURATION_OPTIONS.forEach { (labelRes, durationMs) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
                                onClick = {
                                    menuExpanded = false
                                    controls.onStart(durationMs)
                                },
                            )
                        }
                    }
                }
            }
            // Receiver + STATIC: no action area.
        }
    }
}

private val DURATION_OPTIONS = listOf(
    MR.string.live_share_15m to 15 * 60_000L,
    MR.string.live_share_30m to 30 * 60_000L,
    MR.string.live_share_1h to 60 * 60_000L,
    MR.string.live_share_2h to 2 * 60 * 60_000L,
    MR.string.live_share_4h to 4 * 60 * 60_000L,
)

/** Compact "time left" label: "42m", "1h", "1h 20m". */
private fun formatRemaining(remainingMs: Long): String {
    val totalMin = (remainingMs / 60_000L).coerceAtLeast(0L)
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "${h}h" else "${h}h ${m}m"
}

// ─── Compact list row (the "See all" locations tab) ──────────────────────────

/**
 * A single-line-dense row for the conversation's "See all → Locations" list: a
 * small map thumbnail on the start, then address, coordinates, and a meta line
 * (sender · date). Tapping opens the user's own map app via `geo:` URI — the map
 * PNG is the encrypted drive payload, so nothing reaches a third-party provider.
 */
@Composable
fun LocationListRow(
    descriptor: LocationPreviewDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    payloadKey: String,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb?,
    metaLine: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val geoUri = remember(descriptor.lat, descriptor.lon, descriptor.address) {
        buildGeoUri(descriptor.lat, descriptor.lon, descriptor.address)
    }
    val coordinatesLabel = remember(descriptor.lat, descriptor.lon) {
        formatLatLon(descriptor.lat, descriptor.lon)
    }

    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable { uriHandler.openUri(geoUri) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.padding(end = 12.dp).size(64.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (descriptor.hasImage) {
                val imageData = remember(driveId, fileId, payloadKey) {
                    HomebaseImageData(
                        driveId = driveId,
                        fileId = fileId,
                        payloadKey = payloadKey,
                        previewThumbnail = previewThumbnail,
                        requestedSize = ImageSize.THUMB_MEDIUM,
                        isEncrypted = true,
                        keyHeader = keyHeader,
                        loadFullPayload = true,
                    )
                }
                HomebaseImage(
                    imageData = imageData,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    contentDescription = descriptor.address,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = stringResource(MR.string.cd_location_pin),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            if (descriptor.address.isNotEmpty()) {
                Text(
                    text = descriptor.address,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = coordinatesLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaLine.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─── Utilities ──────────────────────────────────────────────────────────────

private fun formatLatLon(lat: Double, lon: Double): String {
    val latStr = roundTo5Decimals(lat)
    val lonStr = roundTo5Decimals(lon)
    return "$latStr, $lonStr"
}

private fun roundTo5Decimals(value: Double): String {
    val rounded = (value * 1e5).toLong() / 1e5
    return rounded.toString()
}

/**
 * Builds an Android-compatible `geo:` URI that iOS's `Maps.app` and Android's map handlers both
 * accept. Includes the address as a query so the receiving map app can show a labeled pin.
 */
private fun buildGeoUri(lat: Double, lon: Double, address: String): String {
    val q = "$lat,$lon"
    return if (address.isBlank()) "geo:$q" else "geo:$q?q=$q(${urlEncode(address)})"
}

private fun urlEncode(input: String): String {
    val builder = StringBuilder(input.length)
    for (c in input) {
        if (c.isLetterOrDigit() || c in "-_.~") {
            builder.append(c)
        } else if (c == ' ') {
            builder.append("%20")
        } else {
            for (b in c.toString().encodeToByteArray()) {
                builder.append('%')
                builder.append(((b.toInt() shr 4) and 0xF).toString(16).uppercase())
                builder.append((b.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }
    return builder.toString()
}
