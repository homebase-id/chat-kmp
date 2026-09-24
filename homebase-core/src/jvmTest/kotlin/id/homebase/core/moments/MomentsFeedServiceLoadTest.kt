@file:OptIn(ExperimentalCoroutinesApi::class)

package id.homebase.core.moments

import id.homebase.core.feed.FeedTestEnv
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.moments.services.MomentsUserStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MomentsFeedServiceLoadTest {

    @Test
    fun `an empty feed reads as loaded only after the cold load`() = runTest {
        val env = FeedTestEnv(this)
        try {
            env.login()
            val service = MomentsFeedService(
                databaseManager = env.databaseManager,
                credentialsManager = env.credentialsManager,
                eventBus = env.eventBus,
                userStateStore = MomentsUserStateStore(
                    credentialsManager = env.credentialsManager,
                    databaseManager = env.databaseManager,
                    getFileHeaderByUid = { _, _ -> null },
                    uploadFile = {},
                    updateFileByUniqueId = {},
                    stampLocalAppData = { _, _ -> null },
                    enqueueOutbox = { false },
                    eventBus = env.eventBus,
                    scope = env.scope,
                ),
                scope = env.scope,
            )

            assertFalse(service.isLoaded.value)

            service.start()
            advanceUntilIdle()
            // The cold load's DB read finishes off the test clock.
            withContext(Dispatchers.Default) { withTimeout(10_000) { service.isLoaded.first { it } } }

            assertTrue(service.feed.value.isEmpty())
        } finally {
            env.close()
        }
    }
}
