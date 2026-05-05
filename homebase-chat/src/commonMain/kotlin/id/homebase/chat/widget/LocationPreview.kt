package id.homebase.chat.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import id.homebase.resources.chat_location_attachment
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
    coordinatesLabel: String,
    maxAddressLines: Int = 2,
) {
    if (address.isNotEmpty()) {
        Text(
            text = address,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = maxAddressLines,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
    }

    Text(
        text = coordinatesLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
    val coordinatesLabel = remember(locationPreview.lat, locationPreview.lon) {
        formatLatLon(locationPreview.lat, locationPreview.lon)
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
                    coordinatesLabel = coordinatesLabel,
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
                            contentDescription = null,
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
                    coordinatesLabel = coordinatesLabel,
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
) {
    val uriHandler = LocalUriHandler.current
    val geoUri = remember(descriptor.lat, descriptor.lon, descriptor.address) {
        buildGeoUri(descriptor.lat, descriptor.lon, descriptor.address)
    }
    val coordinatesLabel = remember(descriptor.lat, descriptor.lon) {
        formatLatLon(descriptor.lat, descriptor.lon)
    }

    Column(modifier = modifier.fillMaxWidth().clickable { uriHandler.openUri(geoUri) }) {
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
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(
                    RoundedCornerShape(
                        topStart = Dimens.Message.cornerRadius,
                        topEnd = Dimens.Message.cornerRadius,
                    )
                ),
                contentScale = ContentScale.Fit,
                contentDescription = descriptor.address,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Column(modifier = Modifier.padding(12.dp)) {
            LocationPreviewTextContent(
                address = descriptor.address,
                coordinatesLabel = coordinatesLabel,
            )
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
