package id.homebase.core.updater

interface UpdateAppManager {
    suspend fun checkForUpdate(): UpdateAppModel

    /**
     * Initiates the update download/installation process.
     * Returns a result indicating what happened or what the UI should do.
     */
    suspend fun downloadUpdate(): UpdateResult
}

data class UpdateAppModel(
    val updateAvailable: Boolean,
    val canUpdate: Boolean = false,
    val error: UpdateAppError? = null,
    val versionName: String? = null,
)

enum class UpdateAppError {
    UNSUPPORTED_VERSION,
    UNKNOWN_ERROR,
    CURRENT_VERSION_NOT_AVAILABLE,
    LATEST_VERSION_NOT_AVAILABLE
}

/**
 * Result of attempting to download/start an update.
 */
sealed interface UpdateResult {
    /** Update process started successfully (UI shown or download started) */
    data object Started : UpdateResult
    /** Update process completed successfully (e.g. user accepted update or download completed) */
    data object Completed : UpdateResult

    /** No update available */
    data object NoUpdateAvailable : UpdateResult

    /** Platform doesn't support programmatic updates */
    data object Unsupported : UpdateResult

    /** Error occurred */
    data class Error(val error: UpdateAppError) : UpdateResult
}