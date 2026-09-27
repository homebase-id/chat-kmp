package id.homebase.core.contactbook

import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONTACTS_APP_ID
import id.homebase.core.ui.screens.contactbook.ContactBookError
import id.homebase.core.ui.screens.contactbook.offersEnableToggle
import id.homebase.core.ui.screens.contactbook.toCircleToggleError
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class CircleToggleTest {

    private fun circle(appId: Uuid?, id: String = "aa") = RedactedCircleDefinition(id = id, name = "c", appId = appId)

    @Test
    fun theToggleIsOfferedOnChatAndContactsAppCircles() {
        assertTrue(circle(ChatProtocol.ChatAppId).offersEnableToggle())
        assertTrue(circle(Uuid.parse(CONTACTS_APP_ID)).offersEnableToggle())
    }

    @Test
    fun theToggleIsNotOfferedOnOwnerOrOtherAppCircles() {
        assertFalse(circle(null).offersEnableToggle())
        assertFalse(circle(Uuid.random()).offersEnableToggle())
    }

    @Test
    fun theToggleIsNeverOfferedOnASystemCircle() {
        assertFalse(circle(ChatProtocol.ChatAppId, CONFIRMED_CONNECTIONS_CIRCLE_ID).offersEnableToggle())
        assertFalse(circle(ChatProtocol.ChatAppId, AUTO_CONNECTIONS_CIRCLE_ID).offersEnableToggle())
    }

    private fun badRequest(code: String) = ClientException(
        status = 400,
        message = "bad",
        correlationId = null,
        problem = ProblemDetails(status = 400, rawErrorCode = JsonPrimitive(code)),
    )

    @Test
    fun serverRefusalsMapToTheirOwnMessages() {
        assertEquals(ContactBookError.CircleToggleSystemCircle, badRequest("cannotDisableSystemCircle").toCircleToggleError())
        assertEquals(ContactBookError.CircleToggleNotFound, badRequest("circleNotFound").toCircleToggleError())
        assertEquals(ContactBookError.CircleToggleForbidden, ForbiddenException(ProblemDetails(status = 403)).toCircleToggleError())
    }

    @Test
    fun anythingElseIsTheGenericCircleFailure() {
        assertEquals(ContactBookError.CircleActionFailed, badRequest("somethingElse").toCircleToggleError())
        assertEquals(ContactBookError.CircleActionFailed, IllegalStateException().toCircleToggleError())
    }
}
