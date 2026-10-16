package id.homebase.core.ui.screens.onboarding

import id.homebase.api.client.ServerException
import id.homebase.api.client.onboarding.OnboardingInfo
import id.homebase.api.common.OdinId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InvitedByPromptViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `offers the inviter once and marks it handled`() = runTest(dispatcher) {
        var marked = 0
        val vm = InvitedByPromptViewModel({ OnboardingInfo(invitedBy = "frodo.demo.rocks") }) { marked++ }

        assertEquals(OdinId("frodo.demo.rocks"), vm.invitedBy.value)
        assertEquals(1, marked)

        vm.onPromptShown()
        assertNull(vm.invitedBy.value)
    }

    @Test
    fun `nothing when already handled`() = runTest(dispatcher) {
        var marked = 0
        val vm = InvitedByPromptViewModel({ OnboardingInfo(invitedBy = "frodo.demo.rocks", handledAt = 1) }) { marked++ }

        assertNull(vm.invitedBy.value)
        assertEquals(0, marked)
    }

    @Test
    fun `nothing when no one invited the identity`() = runTest(dispatcher) {
        var marked = 0
        val vm = InvitedByPromptViewModel({ OnboardingInfo() }) { marked++ }

        assertNull(vm.invitedBy.value)
        assertEquals(0, marked)
    }

    @Test
    fun `no prompt when marking handled fails`() = runTest(dispatcher) {
        val vm = InvitedByPromptViewModel({ OnboardingInfo(invitedBy = "frodo.demo.rocks") }) {
            throw ServerException(status = 500, correlationId = null, problem = null)
        }

        assertNull(vm.invitedBy.value)
    }
}
