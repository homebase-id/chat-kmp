package id.homebase.homebasekmppoc.prototype.lib.database

import app.cash.sqldelight.db.SqlDriver
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.AppNotifications
import id.homebase.api.sync.database.ChatReadCount
import id.homebase.api.sync.database.DriveLocalTagIndex
import id.homebase.api.sync.database.DriveMainIndex
import id.homebase.api.sync.database.DriveTagIndex
import id.homebase.api.sync.database.KeyValue
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.UuidAdapter

/**
 * Factory for creating test databases with all necessary adapters pre-configured.
 * Centralizes adapter definitions to avoid duplication across test files.
 */
object TestDatabaseFactory {
    
    // Shared adapters - defined once here and reused across all tests
    private val driveTagIndexAdapter = DriveTagIndex.Adapter(
        identityIdAdapter = UuidAdapter,
        driveIdAdapter = UuidAdapter,
        fileIdAdapter = UuidAdapter,
        tagIdAdapter = UuidAdapter
    )

    private val driveLocalTagIndexAdapter = DriveLocalTagIndex.Adapter(
        identityIdAdapter = UuidAdapter,
        driveIdAdapter = UuidAdapter,
        fileIdAdapter = UuidAdapter,
        tagIdAdapter = UuidAdapter
    )

    private val driveMainIndexAdapter = DriveMainIndex.Adapter(
        identityIdAdapter = UuidAdapter,
        driveIdAdapter = UuidAdapter,
        fileIdAdapter = UuidAdapter,
        globalTransitIdAdapter = UuidAdapter,
        groupIdAdapter = UuidAdapter,
        uniqueIdAdapter = UuidAdapter
    )

    private val keyValueAdapter = KeyValue.Adapter(
        keyAdapter = UuidAdapter
    )

    private val outboxAdapter = Outbox.Adapter(
        driveIdAdapter = UuidAdapter,
        fileIdAdapter = UuidAdapter,
        dependencyFileIdAdapter = UuidAdapter
    )

    private val appNotificationsAdapter = AppNotifications.Adapter(
        identityIdAdapter = UuidAdapter,
        notificationIdAdapter = UuidAdapter
    )

    private val chatReadCountAdapter = ChatReadCount.Adapter(
        groupIdAdapter = UuidAdapter
    )

    /**
     * Creates a test database with all adapters pre-configured.
     * Uses the platform-specific in-memory driver.
     * 
     * @param driver Optional custom SQL driver. If null, uses in-memory database.
     * @return Configured OdinDatabase instance ready for testing
     */
    fun createTestDatabase(driver: SqlDriver? = null): OdinDatabase {
        val sqlDriver = driver ?: createInMemoryDatabase()
        
        return OdinDatabase.Companion(
            sqlDriver,
            appNotificationsAdapter,
            chatReadCountAdapter,
            driveLocalTagIndexAdapter,
            driveMainIndexAdapter,
            driveTagIndexAdapter,
            keyValueAdapter,
            outboxAdapter
        )
    }
}