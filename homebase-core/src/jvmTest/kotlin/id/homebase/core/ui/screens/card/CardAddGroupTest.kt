@file:OptIn(ExperimentalUuidApi::class, ExperimentalTestApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CardAddGroupTest {

    private fun attribute(type: String, key: String, value: String) = ProfileAttribute(
        id = Uuid.random(),
        type = type,
        versionTag = Uuid.random(),
        visibility = ProfileVisibility.ANONYMOUS,
        data = JsonObject(mapOf(key to JsonPrimitive(value))),
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.ANONYMOUS.wireValue),
    )

    private fun ComposeUiTest.editor(vararg attributes: ProfileAttribute, card: CardAudience = CardAudience.Public) {
        val items = cardContentItems(attributes.toList(), emptyList(), card)
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = card,
                    items = items,
                    circles = emptyList(),
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    onToggle = { _, _ -> },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
                    onImport = {},
                    onChangeDesign = {},
                )
            }
        }
    }

    private fun ComposeUiTest.chip(label: String) = onNodeWithContentDescription("Add new: $label")

    @Test
    fun socialStaysAvailableWhileANetworkIsUnused() = runComposeUiTest {
        editor(attribute(ProfileAttributeTypes.TWITTER, ProfileAttributeTypes.KEY_TWITTER, "frodo"))
        chip("Social").assertIsEnabled()
    }

    @Test
    fun socialIsLeftOutOnceEveryNetworkIsUsed() = runComposeUiTest {
        editor(
            attribute(ProfileAttributeTypes.TWITTER, ProfileAttributeTypes.KEY_TWITTER, "samgamgee"),
            attribute(ProfileAttributeTypes.FACEBOOK, ProfileAttributeTypes.KEY_FACEBOOK, "sam.gamgee"),
            attribute(ProfileAttributeTypes.INSTAGRAM, ProfileAttributeTypes.KEY_INSTAGRAM, "@samgamgee"),
            attribute(ProfileAttributeTypes.TIKTOK, ProfileAttributeTypes.KEY_TIKTOK, "samgamgee"),
            attribute(ProfileAttributeTypes.LINKEDIN, ProfileAttributeTypes.KEY_LINKEDIN, "samgamgee"),
        )
        chip("Social").assertDoesNotExist()
        chip("Link").assertIsEnabled()
    }

    @Test
    fun repeatableKindsStayAddableWhileATypeTheProfileHasIsLeftOut() = runComposeUiTest {
        editor(
            attribute(ProfileAttributeTypes.EMAIL, ProfileAttributeTypes.KEY_EMAIL, "frodo@shire.me"),
            attribute(ProfileAttributeTypes.PHONE, ProfileAttributeTypes.KEY_PHONE, "+14155550123"),
            attribute(ProfileAttributeTypes.BIO_SUMMARY, ProfileAttributeTypes.KEY_SHORT_BIO, "Hobbit"),
        )
        chip("Email").assertIsEnabled()
        chip("Phone").assertIsEnabled()
        chip("Link").assertIsEnabled()
        chip("Bio").assertDoesNotExist()
    }

    @Test
    fun anEmptyCardSaysSoInOneLineOfText() = runComposeUiTest {
        editor(card = CardAudience.Circle(FRIENDS_CIRCLE_ID, "Friends"))
        onNodeWithText("Nothing else on this card yet. Add something below.").assertExists()
    }
}
