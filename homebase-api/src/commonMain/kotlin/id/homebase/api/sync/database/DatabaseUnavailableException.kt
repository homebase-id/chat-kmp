package id.homebase.api.sync.database

/**
 * The database's key can't be read right now. [DatabaseManager.initializeWithRecovery] rethrows
 * it without wiping, so a later launch retries with the key intact.
 */
class DatabaseUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
