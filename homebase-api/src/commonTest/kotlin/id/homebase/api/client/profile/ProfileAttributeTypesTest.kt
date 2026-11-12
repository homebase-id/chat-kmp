package id.homebase.api.client.profile

import id.homebase.api.crypto.Md5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ProfileAttributeTypesTest {

    // The names js-lib's BuiltInAttributes hashes with toGuidId.
    @Test
    fun typeIdsAreTheWebsToGuidIdValues() {
        mapOf(
            "name" to ProfileAttributeTypes.NAME,
            "status" to ProfileAttributeTypes.STATUS,
            "twitter_username" to ProfileAttributeTypes.TWITTER,
            "linkedin_username" to ProfileAttributeTypes.LINKEDIN,
            "youtube_username" to ProfileAttributeTypes.YOUTUBE,
            "snapchat_username" to ProfileAttributeTypes.SNAPCHAT,
            "github_username" to ProfileAttributeTypes.GITHUB,
            "discord_username" to ProfileAttributeTypes.DISCORD,
            "stackoverflow_username" to ProfileAttributeTypes.STACKOVERFLOW,
            "steam_username" to ProfileAttributeTypes.STEAM,
            "minecraft_username" to ProfileAttributeTypes.MINECRAFT,
            "epic_username" to ProfileAttributeTypes.EPIC,
            "riot_username" to ProfileAttributeTypes.RIOT,
            "dot_you_identity" to ProfileAttributeTypes.HOMEBASE_ID,
        ).forEach { (name, id) -> assertEquals(Md5.toGuidId(name).toHexString(), id, name) }
    }
}
