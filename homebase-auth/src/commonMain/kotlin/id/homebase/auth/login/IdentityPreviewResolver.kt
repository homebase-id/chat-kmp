package id.homebase.auth.login

import co.touchlab.kermit.Logger
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "LoginPreview"

/**
 * Debounced public-profile lookup for whatever identity is currently typed. Takes a plain suspend
 * lambda rather than the gateway so it can be driven from a test — [LoginViewModel] is not
 * constructible in one.
 */
class IdentityPreviewResolver(
    private val scope: CoroutineScope,
    private val profileCardOf: suspend (OdinId) -> ProfileCard?,
    private val debounceMs: Long = 450,
) {
    private val _preview = MutableStateFlow<IdentityPreview?>(null)
    val preview: StateFlow<IdentityPreview?> = _preview

    private var job: Job? = null

    fun onInput(domain: String) {
        job?.cancel()
        if (_preview.value?.odinId?.domainName == domain) return
        // Cleared before dispatch so a slow answer for an abandoned domain can't surface late.
        _preview.value = null
        // isValid only demands >=2 labels and >=3 chars, so `frodo.d` still fires; the debounce is
        // the real limit on request volume.
        if (!OdinId.isValid(domain)) return
        val odinId = OdinId(domain)
        job = scope.launch {
            delay(debounceMs)
            // A missing profile is an expected, non-actionable outcome, but getPublicProfile throws
            // on any network failure — and an unhandled throw here takes the process down on Native.
            runCatching { profileCardOf(odinId) }
                .onFailure {
                    Logger.d(tag = TAG, messageString = "No public profile for $domain: $it")
                }
                .getOrNull()
                ?.let { _preview.value = it.toPreview(odinId) }
        }
    }
}
