package id.homebase.core.ui.screens.card

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.feed.newInMemoryJdbcDriver
import id.homebase.core.settings.DeveloperPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class CardTapShareTest {
    private val owner = OwnerSession(
        odinId = OdinId("frodo.dotyou.cloud"), displayName = null, firstName = null, surName = null,
        profileImageFileId = null, profileImageFileKey = null, profileImagePreviewThumbnail = null, status = null,
    )

    private fun fixture(tapShare: Boolean): Pair<CardTapShare, CardPreferences> = runBlocking {
        val driver = newInMemoryJdbcDriver()
        val db = DatabaseManager({ driver }, dispatcher = Dispatchers.Unconfined, readDispatcher = Dispatchers.Unconfined)
        val developer = DeveloperPreferences(db).also { it.setProfileCardEnabled(true) }
        val cards = CardPreferences(db).also { it.setTapShareEnabled(tapShare) }
        CardTapShare(developer, cards, MutableStateFlow<OwnerSession?>(owner)) to cards
    }

    private suspend fun CardTapShare.awaitUrl(expected: String?) {
        withTimeout(5_000) { servedUrl.first { it == expected } }
        assertEquals(expected, servedUrl.value)
    }

    @Test
    fun servesOnlyWhileTheSheetIsOpenWhenTheSettingIsOff() = runBlocking {
        val (share, _) = fixture(tapShare = false)
        share.awaitUrl(null)
        share.setSheetOpen(true)
        share.awaitUrl("https://frodo.dotyou.cloud/card")
        share.setSheetOpen(false)
        share.awaitUrl(null)
    }

    @Test
    fun settingKeepsServingAfterTheSheetCloses() = runBlocking {
        val (share, _) = fixture(tapShare = true)
        share.awaitUrl("https://frodo.dotyou.cloud/card")
        share.setSheetOpen(true)
        share.setSheetOpen(false)
        delay(300)
        assertEquals("https://frodo.dotyou.cloud/card", share.servedUrl.value)
    }
}
