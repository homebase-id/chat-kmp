package id.homebase.core.ui.screens.contactbook.detail

import id.homebase.chat.services.convo.ConversationService.OneOnOneRebuildOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class RepairConversationEventTest {
    @Test
    fun everyOutcomeHasItsOwnEvent() {
        assertEquals(ContactDetailEvent.ConversationRebuilt, repairEventFor(OneOnOneRebuildOutcome.Rebuilt))
        assertEquals(ContactDetailEvent.ConversationRebuildNotNeeded, repairEventFor(OneOnOneRebuildOutcome.AlreadyOnServer))
        assertEquals(ContactDetailEvent.ConversationRebuildQueued, repairEventFor(OneOnOneRebuildOutcome.AlreadyQueued))
    }
}
