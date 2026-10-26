@file:OptIn(ExperimentalUuidApi::class, ExperimentalTestApi::class)

package id.homebase.core.ui.screens.card

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

class CardRowKeyingTest {

    private val circles = listOf(CardCircle(FRIENDS_CIRCLE_ID, "Friends"))

    private fun phone(number: String) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PHONE,
        versionTag = Uuid.random(),
        visibility = ProfileVisibility.OWNER,
        data = JsonObject(mapOf(ProfileAttributeTypes.KEY_PHONE to JsonPrimitive(number))),
        acl = AccessControlList(requiredSecurityGroup = ProfileVisibility.OWNER.wireValue),
    )

    @Test
    fun anOpenCardsSheetDoesNotMoveToTheItemThatTakesItsRow() = runComposeUiTest {
        val first = phone("+14155550101")
        val second = phone("+14155550102")
        var attributes by mutableStateOf(listOf(first, second))
        setContent {
            MaterialTheme {
                CardContentEditor(
                    card = CardAudience.Public,
                    items = cardContentItems(attributes, circles, CardAudience.Public),
                    circles = circles,
                    photo = CardPhotoUi(photo = null, own = false, canChange = true, busy = false),
                    enabled = true,
                    importSources = emptyList(),
                    showCardsButton = true,
                    onAudience = { _, _ -> },
                    onEdit = { _, _, _ -> },
                    onDelete = {},
                    onAdd = { _, _ -> },
                    onChangePhoto = {},
                    onRemovePhoto = {},
                    onImport = {},
                )
            }
        }

        onAllNodesWithTag("cards_button")[0].performClick()
        onNodeWithTag("cards_menu_done").assertExists()

        attributes = listOf(second)
        waitForIdle()

        onNodeWithTag("cards_menu_done").assertDoesNotExist()
    }
}
