package id.homebase.core.updater

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's one answer to "is an update available?"; every check publishes it to every surface. */
class AppUpdates(private val updateAppManager: UpdateAppManager) {
    private val _latest = MutableStateFlow<UpdateAppModel?>(null)
    val latest: StateFlow<UpdateAppModel?> = _latest.asStateFlow()

    suspend fun check(): UpdateAppModel =
        updateAppManager.checkForUpdate().also { _latest.value = it }

    suspend fun download(): UpdateResult = updateAppManager.downloadUpdate()
}

val UpdateAppModel?.canOfferUpdate: Boolean
    get() = this != null && updateAvailable && canUpdate
