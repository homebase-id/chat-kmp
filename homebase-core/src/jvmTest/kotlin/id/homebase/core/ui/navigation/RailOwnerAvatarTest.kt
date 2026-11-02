package id.homebase.core.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.OwnerAvatarState
import coil3.ImageLoader
import coil3.PlatformContext
import id.homebase.api.client.contacts.AvatarResolver
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RailOwnerAvatarTest {
    private val owner = OwnerSession(
        odinId = OdinId("frodo.baggins.demo.rocks"), displayName = "Frodo Baggins", firstName = "Frodo", surName = "Baggins",
        profileImageFileId = null, profileImageFileKey = null, profileImagePreviewThumbnail = null, status = null,
    )

    private val koin = koinApplication {
        modules(module {
            single { AvatarResolver(MutableStateFlow(emptyList())) }
            single<ImageLoader> { ImageLoader.Builder(PlatformContext.INSTANCE).build() }
        })
    }

    @Test
    fun railShowsTheOwnerAvatarAndItOpensSettings() = runComposeUiTest {
        val navigated = mutableListOf<Route>()
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    RailOwnerAvatar(session = owner, state = OwnerAvatarState(), onNavigate = { navigated += it })
                }
            }
        }
        onNodeWithTag(RailOwnerAvatarTag).assertExists().performClick()
        assertEquals(listOf<Route>(Route.Settings), navigated)
    }
}
