package id.homebase.chat.viewonce

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import id.homebase.core.settings.UserPreferences
import id.homebase.resources.MR
import id.homebase.resources.chat_view_once_toast_off
import id.homebase.resources.chat_view_once_toast_photo
import id.homebase.resources.chat_view_once_toast_video
import org.koin.compose.koinInject
import id.homebase.resources.chat_view_once_close
import id.homebase.resources.chat_view_once_intro_disappears
import id.homebase.resources.chat_view_once_intro_protected
import id.homebase.resources.chat_view_once_intro_title_photo
import id.homebase.resources.chat_view_once_intro_title_video
import id.homebase.resources.cd_view_once_toggle
import id.homebase.resources.ok
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

const val VIEW_ONCE_INTRO_OK_TAG = "viewOnceIntroOk"
const val VIEW_ONCE_INTRO_CLOSE_TAG = "viewOnceIntroClose"
const val VIEW_ONCE_INTRO_TITLE_TAG = "viewOnceIntroTitle"
const val VIEW_ONCE_TOAST_TAG = "viewOnceToast"
internal const val VIEW_ONCE_TOAST_MS = 2_000L

/** Shown once, the first time a sender turns the toggle on; the caller persists that it was shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewOnceIntroSheet(isVideo: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ViewOnceIntroContent(isVideo = isVideo, onOk = ::close, onClose = ::close)
    }
}

@Composable
internal fun ViewOnceIntroContent(isVideo: Boolean, onOk: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp).testTag(VIEW_ONCE_INTRO_CLOSE_TAG)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.chat_view_once_close))
        }
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(88.dp).background(colors.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ViewOnceIcon,
                    contentDescription = stringResource(MR.string.cd_view_once_toggle),
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(56.dp),
                )
            }
            Text(
                text = stringResource(
                    if (isVideo) MR.string.chat_view_once_intro_title_video else MR.string.chat_view_once_intro_title_photo,
                ),
                style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp, bottom = 24.dp).testTag(VIEW_ONCE_INTRO_TITLE_TAG),
            )
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                IntroRow(ViewOnceRingIcon, stringResource(MR.string.chat_view_once_intro_disappears))
                IntroRow(Icons.Outlined.Lock, stringResource(MR.string.chat_view_once_intro_protected))
            }
            Button(onClick = onOk, modifier = Modifier.fillMaxWidth().padding(top = 28.dp).testTag(VIEW_ONCE_INTRO_OK_TAG)) {
                Text(stringResource(MR.string.ok))
            }
        }
    }
}

@Composable
private fun IntroRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

enum class ViewOnceToastKind { Photo, Video, Off }

/** A new instance restarts the toast even when the kind is the same as the last one. */
@Immutable
class ViewOnceToastMessage(val kind: ViewOnceToastKind)

/** What the media send screen keeps for the view-once toggle: the request, its toast, and the one-time intro. */
@Stable
class ViewOnceComposerState(private val preferences: UserPreferences) {
    var requested by mutableStateOf(false)
        private set
    var toast by mutableStateOf<ViewOnceToastMessage?>(null)
        private set
    var showIntro by mutableStateOf(false)
        private set

    fun toggle(isVideo: Boolean) {
        requested = !requested
        toast = ViewOnceToastMessage(
            when {
                !requested -> ViewOnceToastKind.Off
                isVideo -> ViewOnceToastKind.Video
                else -> ViewOnceToastKind.Photo
            },
        )
        if (requested && !preferences.viewOnceIntroSeen) {
            preferences.viewOnceIntroSeen = true
            showIntro = true
        }
    }

    fun dismissIntro() {
        showIntro = false
    }
}

@Composable
fun rememberViewOnceComposerState(preferences: UserPreferences = koinInject()): ViewOnceComposerState =
    remember(preferences) { ViewOnceComposerState(preferences) }

@Composable
fun ViewOnceToast(message: ViewOnceToastMessage?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<ViewOnceToastMessage?>(null) }
    LaunchedEffect(message) {
        shown = message
        if (message != null) {
            delay(VIEW_ONCE_TOAST_MS)
            shown = null
        }
    }
    val toastPhoto = stringResource(MR.string.chat_view_once_toast_photo)
    val toastVideo = stringResource(MR.string.chat_view_once_toast_video)
    val toastOff = stringResource(MR.string.chat_view_once_toast_off)
    // Keeps the last text through the exit animation instead of collapsing to nothing mid-fade.
    var last by remember { mutableStateOf("") }
    shown?.let {
        last = when (it.kind) {
            ViewOnceToastKind.Photo -> toastPhoto
            ViewOnceToastKind.Video -> toastVideo
            ViewOnceToastKind.Off -> toastOff
        }
    }
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 2 },
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.testTag(VIEW_ONCE_TOAST_TAG),
        ) {
            Text(
                text = last,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
