package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.media.subsample.ZoomableSubSamplingImage
import id.homebase.core.media.subsample.sharedElementKey
import id.homebase.resources.MR
import id.homebase.resources.avatar_conversation
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen, pinch-zoomable viewer for a conversation's profile photo — the group photo in group
 * settings, the contact's photo in 1:1 settings. Deliberately not [ChatMediaFullScreenHost]: a
 * profile image is not a chat attachment and has no save/share/jump-to-message actions.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AvatarFullScreenViewer(
    source: SubSamplingImageSource,
    title: String,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { onDismiss() }

    val sharedContentStateKey = source.sharedElementKey()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ZoomableSubSamplingImage(
            source = source,
            contentDescription = stringResource(MR.string.avatar_conversation),
            modifier = Modifier.fillMaxSize(),
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            sharedContentStateKey = sharedContentStateKey,
        )

        TopAppBar(
            modifier = Modifier.align(Alignment.TopCenter),
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(MR.string.menu_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.4f),
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
            ),
        )
    }
}
