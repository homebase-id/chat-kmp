package id.homebase.core.ui.screens.card

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatformTools

internal object CardNfcTagHolder {
    @Volatile
    var resumed = false

    // Resolved only while resumed, so a reader tapping a backgrounded phone never touches Koin.
    val url: String?
        get() = if (resumed) KoinPlatformTools.defaultContext().get().get<CardTapShare>().servedUrl.value else null

    private val debounce = TapShareDebounce()
    private val _reads = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val reads: SharedFlow<Unit> = _reads.asSharedFlow()

    // HostApduService callbacks run on the main thread, so the debounce needs no lock.
    fun onMessageRead() {
        if (debounce.accept(SystemClock.elapsedRealtime())) _reads.tryEmit(Unit)
    }
}

class CardNfcApduService : HostApduService() {
    private val tag = Type4TagEmulator(currentUrl = { CardNfcTagHolder.url }, onMessageRead = CardNfcTagHolder::onMessageRead)

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray = tag.process(commandApdu)

    override fun onDeactivated(reason: Int) = tag.reset()
}

@Composable
actual fun rememberCardNfc(): CardNfc? {
    val activity = LocalActivity.current ?: return null
    val adapter = remember(activity) { hceAdapter(activity) } ?: return null
    val nfc = remember(activity, adapter) { AndroidCardNfc(activity, adapter) }
    DisposableEffect(nfc) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = nfc.refresh()
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        nfc.refresh()
        onDispose { activity.unregisterReceiver(receiver) }
    }
    return nfc
}

@Composable
actual fun CardTapShareDriver(onShared: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle ?: return
    val adapter = remember(activity) { hceAdapter(activity) } ?: return
    val tapShare = koinInject<CardTapShare>()
    val server = remember(activity, adapter) { CardTapShareServer(activity, lifecycle, adapter, tapShare) }
    val currentOnShared by rememberUpdatedState(onShared)
    // Tracked from the activity lifecycle itself: a recomposition may not run once the app is stopped.
    DisposableEffect(server) {
        val observer = LifecycleEventObserver { _, _ -> server.apply() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            server.stop()
        }
    }
    LaunchedEffect(server) { tapShare.servedUrl.collect { server.apply() } }
    LaunchedEffect(server) { CardNfcTagHolder.reads.collect { currentOnShared() } }
}

private fun hceAdapter(context: Context): NfcAdapter? {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return null
    return NfcAdapter.getDefaultAdapter(context)
}

private class AndroidCardNfc(private val activity: Activity, private val adapter: NfcAdapter) : CardNfc {
    override var isEnabled by mutableStateOf(adapter.isEnabled)
        private set

    fun refresh() {
        isEnabled = adapter.isEnabled
    }

    override fun openSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
}

private class CardTapShareServer(
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    adapter: NfcAdapter,
    private val tapShare: CardTapShare,
) {
    private val emulation = CardEmulation.getInstance(adapter)
    private val service = ComponentName(activity, CardNfcApduService::class.java)
    private var preferred = false

    fun apply() {
        val resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        CardNfcTagHolder.resumed = resumed
        val serving = resumed && tapShare.servedUrl.value != null
        // CardEmulation throws unless the activity is resumed; the system drops the preference itself once the app leaves the foreground.
        if (serving && !preferred) {
            preferred = emulation.setPreferredService(activity, service)
        } else if (!serving && preferred) {
            if (resumed) emulation.unsetPreferredService(activity)
            preferred = false
        }
    }

    fun stop() {
        CardNfcTagHolder.resumed = false
        if (preferred && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) emulation.unsetPreferredService(activity)
        preferred = false
    }
}
