package id.homebase.core.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.effectiveOwnerSessionFlow
import id.homebase.api.common.SecureByteArray
import androidx.compose.runtime.collectAsState
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AppConnectionStatus
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
        onNodeWithTag(RailOwnerAvatarTag)
            .assertContentDescriptionEquals("Settings")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf<Route>(Route.Settings), navigated)
    }

    @Test
    fun railAvatarCarriesTheSelectionAndSaysItsStatusInWords() = runComposeUiTest {
        var selected by mutableStateOf(false)
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    RailOwnerAvatar(
                        session = owner,
                        state = OwnerAvatarState(AppConnectionStatus.Connected, hasDriveError = true),
                        onNavigate = {},
                        selected = selected,
                    )
                }
            }
        }
        onNodeWithTag(RailOwnerAvatarTag)
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sync problem"))
        selected = true
        onNodeWithTag(RailOwnerAvatarTag).assertIsSelected()
    }

    @Test
    fun railShowsTheAvatarFromCredentialsBeforeTheProfileLoads() = runComposeUiTest {
        val creds = ApiCredentials.create(
            domain = OdinId("frodo.baggins.demo.rocks"),
            clientAccessToken = "token",
            sharedSecret = SecureByteArray(byteArrayOf(1, 2, 3)),
        )
        val session = effectiveOwnerSessionFlow(MutableStateFlow(null), MutableStateFlow(creds))
        setContent {
            val owner by session.collectAsState(null)
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    RailOwnerAvatar(session = owner, state = OwnerAvatarState(), onNavigate = {})
                }
            }
        }
        onNodeWithTag(RailOwnerAvatarTag).assertExists()
    }
}
