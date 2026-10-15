package id.homebase.core.ui.screens.invite

import id.homebase.api.client.ServerException
import id.homebase.api.client.invitations.InvitationCode
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InviteFriendsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun code(usesLeft: Int, maxUses: Int = 5, code: String = "K7QX-M2PD-9FHW") =
        InvitationCode(code = code, maxUses = maxUses, usesLeft = usesLeft, link = "https://p.example/?invitation-code=$code")

    @Test
    fun `loading until the server answers`() = runTest(dispatcher) {
        val answer = CompletableDeferred<List<InvitationCode>>()
        val vm = InviteFriendsViewModel { answer.await() }
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.hasNoCode)

        answer.complete(listOf(code(usesLeft = 3)))
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `codes load`() = runTest(dispatcher) {
        val codes = listOf(code(usesLeft = 3), code(usesLeft = 1, code = "AAAA-BBBB-CCCC"))
        val state = InviteFriendsViewModel { codes }.uiState.value
        assertEquals(codes, state.codes)
        assertFalse(state.loadFailed)
        assertFalse(state.hasNoCode)
        assertFalse(state.codes.any { it.isUsedUp })
    }

    @Test
    fun `no codes is the empty state, not an error`() = runTest(dispatcher) {
        val state = InviteFriendsViewModel { emptyList() }.uiState.value
        assertTrue(state.hasNoCode)
        assertFalse(state.loadFailed)
    }

    @Test
    fun `a code with no uses left is used up`() = runTest(dispatcher) {
        val state = InviteFriendsViewModel { listOf(code(usesLeft = 0)) }.uiState.value
        assertTrue(state.codes.single().isUsedUp)
        assertFalse(state.hasNoCode)
    }

    @Test
    fun `a failed load shows the error and retry recovers`() = runTest(dispatcher) {
        var fail = true
        val vm = InviteFriendsViewModel {
            if (fail) throw ServerException(status = 503, correlationId = null, problem = null)
            listOf(code(usesLeft = 2))
        }
        assertTrue(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.hasNoCode)

        fail = false
        vm.onAction(InviteFriendsUiAction.Retry)
        assertFalse(vm.uiState.value.loadFailed)
        assertEquals(1, vm.uiState.value.codes.size)
    }
}
