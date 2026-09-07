@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.connections

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The four ownership fields (odin-core #1688) are served by a migrated server and simply absent on
 * one that predates them, so "absent" has to land on the same values that server would have sent:
 * every pre-migration circle is None/Personal, owner-owned, no emoji.
 */
class CircleDefinitionSerializationTest {

    @Test
    fun preMigrationServerShapeTakesTheServerSideDefaults() {
        val json = """
            {"id":"9e22b42952f74d2580e11250b651d343","name":"Auto Connections","disabled":false}
        """.trimIndent()

        val circle = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(json)

        assertNull(circle.appId)
        assertNull(circle.emoji)
        assertEquals(CircleGrantOn.None, circle.grantOn)
        assertEquals(CircleDesignation.Personal, circle.designation)
    }

    @Test
    fun migratedServerShapeParsesAllFourFields() {
        val json = """
            {"id":"55900e0ab05347dca85c5ac2514e7fd3","name":"Chat",
             "appId":"2d781401-3804-4b4b-b03f-4b4d1e4c1a06","grantOn":"connect",
             "designation":"personal","emoji":"💬"}
        """.trimIndent()

        val circle = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(json)

        assertEquals(Uuid.parse("2d781401-3804-4b4b-b03f-4b4d1e4c1a06"), circle.appId)
        assertEquals(CircleGrantOn.Connect, circle.grantOn)
        assertEquals(CircleDesignation.Personal, circle.designation)
        assertEquals("💬", circle.emoji)
    }

    @Test
    fun enumsDecodeFromTheCasingCSharpActuallySends() {
        val json = """
            {"id":"aa","name":"Subscribers","grantOn":"OwnFlowConnect","designation":"Audience"}
        """.trimIndent()

        val circle = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(json)

        assertEquals(CircleGrantOn.OwnFlowConnect, circle.grantOn)
        assertEquals(CircleDesignation.Audience, circle.designation)
    }

    /**
     * coerceInputValues turns a designation this build has never heard of into the property
     * default rather than throwing — the circle still renders, as the most conservative kind.
     */
    @Test
    fun unknownDesignationCoercesToTheDefault() {
        val json = """{"id":"aa","name":"Whatever","designation":"someFutureKind"}"""

        val circle = OdinSystemSerializer.deserialize<RedactedCircleDefinition>(json)

        assertEquals(CircleDesignation.Personal, circle.designation)
    }
}
