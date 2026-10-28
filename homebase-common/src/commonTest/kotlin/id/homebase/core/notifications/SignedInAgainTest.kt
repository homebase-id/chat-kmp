package id.homebase.core.notifications

import id.homebase.api.common.OdinId
import id.homebase.api.youauth.YouAuthState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedInAgainTest {
    private fun signedIn(identity: String, token: String) = YouAuthState.Authenticated(OdinId(identity), token, "secret")

    @Test
    fun aNewTokenForTheSameIdentityIsSigningInAgain() {
        assertTrue(signedInAgain(signedIn("frodo.example.com", "old"), signedIn("frodo.example.com", "new")))
    }

    @Test
    fun aFirstSignInARestoreOrAnotherIdentityIsNot() {
        assertFalse(signedInAgain(null, signedIn("frodo.example.com", "new")))
        assertFalse(signedInAgain(signedIn("frodo.example.com", "same"), signedIn("frodo.example.com", "same")))
        assertFalse(signedInAgain(signedIn("frodo.example.com", "old"), signedIn("sam.example.com", "new")))
    }
}
