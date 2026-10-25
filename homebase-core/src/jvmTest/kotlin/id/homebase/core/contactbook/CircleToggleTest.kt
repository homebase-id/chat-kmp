package id.homebase.core.contactbook

import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.config.CONTACTS_APP_ID
import id.homebase.core.ui.screens.contactbook.ContactBookError
import id.homebase.core.ui.screens.contactbook.toCircleToggleError
import id.homebase.core.ui.screens.contactbook.toggleBlockedReason
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class CircleToggleTest {

    private fun circle(appId: Uuid?, id: String = "aa") = RedactedCircleDefinition(id = id, name = "c", appId = appId)

    @Test
    fun theToggleIsOfferedOnlyOnChatCircles() {
        assertNull(circle(ChatProtocol.ChatAppId).toggleBlockedReason())
    }

    @Test
    fun ownerAndOtherAppCirclesAreBlockedAsForbidden() {
        assertEquals(ContactBookError.CircleToggleForbidden, circle(null).toggleBlockedReason())
        assertEquals(ContactBookError.CircleToggleForbidden, circle(Uuid.parse(CONTACTS_APP_ID)).toggleBlockedReason())
        assertEquals(ContactBookError.CircleToggleForbidden, circle(Uuid.random()).toggleBlockedReason())
    }

    @Test
    fun aMissingCircleIsBlockedAsNotFound() {
        val missing: RedactedCircleDefinition? = null
        assertEquals(ContactBookError.CircleToggleNotFound, missing.toggleBlockedReason())
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
