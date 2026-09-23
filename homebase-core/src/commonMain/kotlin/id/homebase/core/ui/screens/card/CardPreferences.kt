package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseManager
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CardPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _design = MutableStateFlow(readDesign())
    val design: StateFlow<String?> = _design.asStateFlow()

    suspend fun setDesign(design: String) {
        keyValue.upsertValue(DESIGN_KEY, design.encodeToByteArray())
        _design.value = design
    }

    // A singleton outlives logout; re-read from the next identity's database.
    fun reset() {
        _design.value = readDesign()
    }

    private fun readDesign(): String? {
        // Bootstrap-only sync read, as in DiceRollPreferences: commonMain has no runBlocking on wasmJs.
        val bytes = runCatching { keyValue.selectByKeyBootstrapSync(DESIGN_KEY)?.data_ }
            .onFailure { Logger.w(tag = "CardPreferences", throwable = it) { "reading the saved card design failed" } }
            .getOrNull() ?: return null
        return bytes.decodeToString().takeIf { it in CardDesign.all }
    }

    companion object {
        val DESIGN_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0b01")
    }
}
