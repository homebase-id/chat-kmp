package id.homebase.core.photobase

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** All push handling knows about backup, so backup can later be lifted out of chat-kmp. */
fun interface PhotoBackupTrigger {
    /** [remaining]: what's left of the OS's wake after chat has synced. */
    suspend fun onWake(remaining: Duration)
}

/** iOS grants about 30 s for a silent push, and killing an overrun is worse than stopping short. */
val PUSH_WAKE_BUDGET: Duration = 25.seconds
