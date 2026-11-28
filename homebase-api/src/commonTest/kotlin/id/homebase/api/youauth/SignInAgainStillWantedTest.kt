package id.homebase.api.youauth

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A sign-in again is finished off any screen, so the browser can answer after a logout.
class SignInAgainStillWantedTest {
    private val frodo = OdinId("frodo.dotyou.cloud")
    private fun signedInAs(identity: OdinId) = ApiCredentials.create(identity, "token", SecureByteArray(ByteArray(16)))

    @Test
    fun aSignInAgainForTheSignedInIdentityIsTaken() {
        assertTrue(signInAgainStillWanted(frodo, signedInAs(frodo)))
    }

    @Test
    fun aSignInAgainThatAnswersAfterALogoutIsDropped() {
        assertFalse(signInAgainStillWanted(frodo, null), "it would sign the user back in")
    }

    @Test
    fun aSignInAgainThatAnswersAfterAnotherIdentitySignedInIsDropped() {
        assertFalse(signInAgainStillWanted(frodo, signedInAs(OdinId("sam.dotyou.cloud"))), "it would replace the other identity's token")
    }
}
