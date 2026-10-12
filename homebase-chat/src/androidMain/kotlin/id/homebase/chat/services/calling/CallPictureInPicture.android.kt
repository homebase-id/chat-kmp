package id.homebase.chat.services.calling

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PendingIntentCompat
import androidx.core.util.Consumer
import id.homebase.resources.MR
import id.homebase.resources.call_hang_up
import id.homebase.resources.call_mute
import id.homebase.resources.call_unmute
import org.jetbrains.compose.resources.stringResource

private const val ACTION_MUTE = "id.homebase.chat.calling.PIP_MUTE"
private const val ACTION_HANGUP = "id.homebase.chat.calling.PIP_HANGUP"

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
actual fun rememberInPictureInPicture(): Boolean {
    val activity = LocalContext.current.findActivity() ?: return false
    var inPip by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

@Composable
actual fun CallPictureInPictureEffect(eligible: Boolean, remote: VideoHandle?, muted: Boolean, onToggleMute: () -> Unit, onHangup: () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val size by RemoteVideoSize.current.collectAsState()
    val muteLabel = stringResource(if (muted) MR.string.call_unmute else MR.string.call_mute)
    val hangupLabel = stringResource(MR.string.call_hang_up)
    val toggleMute by rememberUpdatedState(onToggleMute)
    val hangup by rememberUpdatedState(onHangup)

    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_MUTE -> toggleMute()
                    ACTION_HANGUP -> hangup()
                }
            }
        }
        val filter = IntentFilter().apply { addAction(ACTION_MUTE); addAction(ACTION_HANGUP) }
        androidx.core.content.ContextCompat.registerReceiver(activity, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { activity.unregisterReceiver(receiver) }
    }

    val params = remember(eligible, muted, size, muteLabel, hangupLabel) {
        buildPipParams(activity, eligible, muted, size, muteLabel, hangupLabel)
    }
    DisposableEffect(activity, params) {
        // Pre-31 there is no auto-enter, so the user-leave-hint hook is the only way in.
        val leave = Runnable { if (eligible) activity.enterPictureInPictureMode(params) }
        activity.setPictureInPictureParams(params)
        activity.addOnUserLeaveHintListener(leave)
        onDispose {
            activity.removeOnUserLeaveHintListener(leave)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }
}

private fun buildPipParams(
    activity: Activity,
    eligible: Boolean,
    muted: Boolean,
    size: Pair<Int, Int>?,
    muteLabel: String,
    hangupLabel: String,
): PictureInPictureParams {
    val (w, h) = pipAspectRatio(size)
    val actions = listOf(
        remoteAction(
            activity, ACTION_MUTE, 1,
            if (muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off,
            muteLabel,
        ),
        remoteAction(activity, ACTION_HANGUP, 2, android.R.drawable.ic_menu_close_clear_cancel, hangupLabel),
    )
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational(w, h))
        .setActions(actions)
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(eligible) }
        .build()
}

private fun remoteAction(context: Context, action: String, requestCode: Int, iconRes: Int, label: String): RemoteAction {
    val intent = Intent(action).setPackage(context.packageName)
    val pending = PendingIntentCompat.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)!!
    return RemoteAction(Icon.createWithResource(context, iconRes), label, label, pending)
}
