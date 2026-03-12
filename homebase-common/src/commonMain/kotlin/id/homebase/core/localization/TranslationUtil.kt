package id.homebase.core.localization

import kotlinx.coroutines.runBlocking
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

    fun getString(resource: StringResource): String {
        return runBlocking {
            org.jetbrains.compose.resources.getString(
                environment = resourceEnvironment,
                resource = resource,
            )
        }
    }

    fun getString(resource: StringResource, vararg formatArgs: Any): String {
        return runBlocking {
            org.jetbrains.compose.resources.getString(
                environment = resourceEnvironment,
                resource = resource,
                *formatArgs
            )
        }
    }
}