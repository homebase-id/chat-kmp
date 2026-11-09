package id.homebase.core.config

import id.homebase.api.youauth.TargetDriveAccessRequest
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotobaseAppRegistrationTest {

    @Test
    fun theInstallDeclaresTheAppItsDriveAndItsCircle() {
        val url = Url(photobaseAppRegistration.url("frodo.example.com", "https://frodo.example.com/apps/chat/permission-callback"))

        assertEquals("/owner/appreg", url.encodedPath)
        assertEquals(
            mapOf(
                "appId" to "704334ede383476bb82e2b0d5bab36fd",
                "n" to "Homebase Photobase",
                "as" to "photobase",
                "d" to """[{"a":"e3e5d4ed-462a-4fc0-835c-2d3722820be8","t":"764a8e3a-13b9-46e1-b53f-092679dc9953","n":"Photos","d":"Photos and videos backed up by Homebase Photobase","p":3,"ds":"photos","ts":"photos","sm":"selectiveShare"}]""",
                "cd" to "[]",
                "ac" to """[{"s":"photobase","n":"Photobase","d":"People who can see the photos you share through Homebase Photobase","e":"📷","g":[{"a":"e3e5d4ed-462a-4fc0-835c-2d3722820be8","t":"764a8e3a-13b9-46e1-b53f-092679dc9953","p":1}]}]""",
                "return" to "https://frodo.example.com/apps/chat/permission-callback",
                "cancel" to "https://frodo.example.com/apps/chat/permission-callback?status=canceled",
            ),
            url.parameters.entries().associate { (key, values) -> key to values.single() },
        )
    }

    @Test
    fun chatOnlyAsksForAccessToTheDrive() {
        assertEquals(
            """[{"a":"e3e5d4ed-462a-4fc0-835c-2d3722820be8","t":"764a8e3a-13b9-46e1-b53f-092679dc9953","p":3,"ts":"photos"}]""",
            TargetDriveAccessRequest.encodeList(photosTargetDriveAccessRequest),
        )
    }
}
