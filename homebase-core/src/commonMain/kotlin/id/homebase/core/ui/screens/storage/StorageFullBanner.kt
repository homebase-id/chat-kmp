package id.homebase.core.ui.screens.storage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import id.homebase.api.client.storage.StorageQuota
import id.homebase.resources.MR
import id.homebase.resources.cd_navigate_forward
import id.homebase.resources.storage_full_banner_body
import id.homebase.resources.storage_full_banner_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.minutes

/** How often to ask the server again while full: its usage figure may be up to a minute old. */
private val FULL_RECHECK_INTERVAL = 1.minutes

/**
 * Puts a banner over [content] while the identity's storage is full: a 507 refused an upload, or the
 * server says it is over its plan's quota. Photos and files wait in the outbox; text still goes.
 * Tapping it calls [onOpenStorage]. While shown it asks the server again every minute, so it goes on
 * its own once enough was deleted.
 */
@Composable
fun StorageFullHost(onOpenStorage: () -> Unit, content: @Composable () -> Unit) {
    val storageQuota = koinInject<StorageQuota>()
    val quotaState by storageQuota.state.collectAsStateWithLifecycle()
    val isFull = quotaState.isFull
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(isFull, lifecycleOwner) {
        if (!isFull) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Waits first: a refresh right after the 507 would only repeat it
                delay(FULL_RECHECK_INTERVAL)
                storageQuota.refresh()
            }
        }
    }

    val motion = MaterialTheme.motionScheme
    Column {
        // As the tab root's banners: padded for the status bar here, which the content then does not repeat
        AnimatedVisibility(
            visible = isFull,
            enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
        ) {
            StorageFullBanner(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                onClick = onOpenStorage,
            )
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxSize().then(
                if (isFull) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
            ),
        ) {
            content()
        }
    }
}

@Composable
private fun StorageFullBanner(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(MR.string.storage_full_banner_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(MR.string.storage_full_banner_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(MR.string.cd_navigate_forward),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
