package id.homebase.core.ui.screens.email

import id.homebase.core.ui.screens.email.mode.canConfirmModeSwitch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailModeSwitchGateTest {

    private val address = "mail@frodo.dotyou.cloud"

    @Test
    fun bothTheAcknowledgementAndTheAddressAreNeeded() {
        assertFalse(canConfirmModeSwitch(acknowledged = false, typedAddress = address, address = address))
        assertFalse(canConfirmModeSwitch(acknowledged = true, typedAddress = "", address = address))
        assertTrue(canConfirmModeSwitch(acknowledged = true, typedAddress = address, address = address))
    }

    @Test
    fun aNearMissIsNotEnough() {
        assertFalse(canConfirmModeSwitch(acknowledged = true, typedAddress = "mail@frodo.dotyou", address = address))
    }

    /** Phones capitalise the first letter and keyboards append spaces; neither is a different address. */
    @Test
    fun caseAndSurroundingSpacesAreForgiven() {
        assertTrue(canConfirmModeSwitch(acknowledged = true, typedAddress = " Mail@Frodo.dotyou.cloud ", address = address))
    }

    /** Before the status loads there is no address, and typing nothing must not match it. */
    @Test
    fun anUnknownAddressNeverConfirms() {
        assertFalse(canConfirmModeSwitch(acknowledged = true, typedAddress = "", address = ""))
    }
}
