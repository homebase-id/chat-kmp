package id.homebase.api.client.auth

import id.homebase.api.common.OdinId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CredentialsManager {
    private val mutex = Mutex()
    private val storedCredentials = mutableMapOf<String, ApiCredentials>()

    private var activeCredentials: ApiCredentials? = null

    private val _credentialsFlow = MutableStateFlow<ApiCredentials?>(null)
    val credentialsFlow: StateFlow<ApiCredentials?> = _credentialsFlow.asStateFlow()

    suspend fun hasActiveCredentials(): Boolean = mutex.withLock {
        activeCredentials != null
    }

    suspend fun getActiveDomain(): OdinId? = mutex.withLock {
        activeCredentials?.domain
    }

    suspend fun storeCredentials(credentials: ApiCredentials) = mutex.withLock {
        storedCredentials[credentials.domain.domainName] = credentials
    }

    suspend fun removeCredentials(domain: OdinId) = mutex.withLock {
        if (activeCredentials?.domain == domain) {
            activeCredentials = null
        }
        storedCredentials.remove(domain.domainName)
    }

    suspend fun removeAllCredentials() = mutex.withLock {
        _credentialsFlow.update { null }
        activeCredentials = null
        storedCredentials.clear()
    }

    suspend fun getActiveCredentials(): ApiCredentials? = mutex.withLock {
        activeCredentials
    }

    suspend fun setActiveCredentials(credentials: ApiCredentials) = mutex.withLock {
        storedCredentials[credentials.domain.domainName] = credentials
        activeCredentials = credentials
        _credentialsFlow.update { credentials }
    }

    suspend fun setActiveCredentials(domain: String) = mutex.withLock {
        activeCredentials = storedCredentials[domain]
            ?: throw IllegalArgumentException("No credentials found for domain: $domain")
    }

    suspend fun removeActiveCredentials() = mutex.withLock {
        activeCredentials = null
        _credentialsFlow.update { null }
    }

    suspend fun requireActiveCredentials(): ApiCredentials = mutex.withLock {
        activeCredentials
            ?: throw IllegalStateException("No active credentials set")
    }

    suspend fun requireActiveDomain(): OdinId = mutex.withLock {
        activeCredentials?.domain
            ?: throw IllegalStateException("No active credentials set")
    }
}
