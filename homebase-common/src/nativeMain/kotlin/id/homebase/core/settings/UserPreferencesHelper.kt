package id.homebase.core.settings

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object UserPreferencesHelper : KoinComponent {
    private val userPreferences: UserPreferences by inject()
    
    val errorCollectionEnabled: Boolean
        get() = userPreferences.errorCollectionEnabled
    
    fun setErrorCollectionEnabled(enabled: Boolean) {
        userPreferences.errorCollectionEnabled = enabled
    }
}