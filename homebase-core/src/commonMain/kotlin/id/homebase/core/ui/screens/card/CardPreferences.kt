package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseManager
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CardPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _design = MutableStateFlow(readDesign())
    val design: StateFlow<String?> = _design.asStateFlow()

    private val _tapShareEnabled = MutableStateFlow(readTapShare())
    val tapShareEnabled: StateFlow<Boolean> = _tapShareEnabled.asStateFlow()

    @Volatile
    var circleCardsUnsupported: Boolean = readCircleCardsUnsupported()
        private set

    suspend fun setDesign(design: String) {
        keyValue.upsertValue(DESIGN_KEY, design.encodeToByteArray())
        _design.value = design
    }

    suspend fun setTapShareEnabled(enabled: Boolean) {
        if (_tapShareEnabled.value == enabled) return
        keyValue.upsertValue(TAP_SHARE_KEY, byteArrayOf(if (enabled) 1 else 0))
        _tapShareEnabled.value = enabled
    }

    suspend fun setCircleCardsUnsupported() {
        if (circleCardsUnsupported) return
        keyValue.upsertValue(CIRCLES_UNSUPPORTED_KEY, byteArrayOf(1))
        circleCardsUnsupported = true
    }

    // A singleton outlives logout; re-read from the next identity's database.
    fun reset() {
        _design.value = readDesign()
        _tapShareEnabled.value = readTapShare()
        circleCardsUnsupported = readCircleCardsUnsupported()
    }

    private fun readDesign(): String? =
        read(DESIGN_KEY, "design")?.decodeToString()?.takeIf { it in CardDesign.all }

    private fun readTapShare(): Boolean = read(TAP_SHARE_KEY, "tap-share")?.firstOrNull()?.let { it.toInt() != 0 } ?: false

    private fun readCircleCardsUnsupported(): Boolean = read(CIRCLES_UNSUPPORTED_KEY, "circle-card support") != null

    // Bootstrap-only sync read, as in DiceRollPreferences: commonMain has no runBlocking on wasmJs.
    private fun read(key: Uuid, what: String): ByteArray? =
        runCatching { keyValue.selectByKeyBootstrapSync(key)?.data_ }
            .onFailure { Logger.w(tag = "CardPreferences", throwable = it) { "reading the saved card $what failed" } }
            .getOrNull()

    companion object {
        val DESIGN_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0b01")
        val TAP_SHARE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0b02")
        val CIRCLES_UNSUPPORTED_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0b03")
    }
}
