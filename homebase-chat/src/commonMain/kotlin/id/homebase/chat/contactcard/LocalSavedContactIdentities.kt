package id.homebase.chat.contactcard

import androidx.compose.runtime.compositionLocalOf
import id.homebase.api.common.OdinId

// Empty denies: a host that renders a card without providing this gets initials, not a fetch.
val LocalSavedContactIdentities = compositionLocalOf<Set<OdinId>> { emptySet() }
