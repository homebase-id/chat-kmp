package id.homebase.core.localization

import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getSystemResourceEnvironment

// To be used for translation in non-compose context
object TranslationUtil {

    var resourceEnvironment: ResourceEnvironment = getSystemResourceEnvironment()

    // Call on language change
    fun reloadEnvironment() {
        resourceEnvironment = getSystemResourceEnvironment()
    }

    suspend fun getString(resource: StringResource): String =
        org.jetbrains.compose.resources.getString(
            environment = resourceEnvironment,
            resource = resource,
        )

    suspend fun getString(resource: StringResource, vararg formatArgs: Any): String =
        org.jetbrains.compose.resources.getString(
            environment = resourceEnvironment,
            resource = resource,
            *formatArgs,
        )
}
