package id.homebase.api.client.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import id.homebase.api.common.OdinId

class CredentialsManager {
    private val mutex = Mutex()
    private val storedCredentials = mutableMapOf<String, ApiCredentials>()

    private var activeCredentials: ApiCredentials? = null

    //

    suspend fun hasActiveCredentials(): Boolean = mutex.withLock {
        activeCredentials != null
    }

    //

    suspend fun getActiveDomain(): OdinId? = mutex.withLock {
        activeCredentials?.domain
    }

    //

    suspend fun storeCredentials(credentials: ApiCredentials) = mutex.withLock {
        storedCredentials[credentials.domain.domainName] = credentials
    }

    //

    suspend fun removeCredentials(domain: OdinId) = mutex.withLock {
        if (activeCredentials?.domain == domain) {
            activeCredentials = null
        }
        storedCredentials.remove(domain.domainName)
    }

    //

    suspend fun removeAllCredentials() = mutex.withLock {
        activeCredentials = null
        storedCredentials.clear()
    }

    //

    suspend fun getActiveCredentials(): ApiCredentials? = mutex.withLock {
        activeCredentials
    }

    //

    suspend fun setActiveCredentials(credentials: ApiCredentials) = mutex.withLock {
        storedCredentials[credentials.domain.domainName] = credentials
        activeCredentials = credentials
    }

    //

    suspend fun setActiveCredentials(domain: String) = mutex.withLock {
        activeCredentials = storedCredentials[domain]
            ?: throw IllegalArgumentException("No credentials found for domain: $domain")
    }

    //

    suspend fun removeActiveCredentials() = mutex.withLock {
        activeCredentials = null
    }

    //

    suspend fun requireActiveCredentials(): ApiCredentials = mutex.withLock {
        activeCredentials
            ?: throw IllegalStateException("No active credentials set")
    }

}
