package id.homebase.api.youauth

import io.ktor.http.formUrlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** A circle an app creates and owns when it is installed: the owner app's `ac` entry. */
data class AppCircleDeclaration(
    val slug: String,
    val name: String,
    val description: String,
    val emoji: String,
    val drives: List<TargetDriveAccessRequest>,
)

/**
 * An app as `/owner/appreg` installs it: registered with its drives and circles, which it then owns.
 * That page hands out no token; it sends the owner back to the return URL.
 */
data class AppRegistrationParams(
    val appId: String,
    val name: String,
    val appSlug: String,
    val drives: List<TargetDriveAccessRequest>,
    val circles: List<AppCircleDeclaration>,
) {
    fun url(host: String, returnUrl: String): String = "https://$host/owner/appreg?" + listOf(
        "appId" to appId,
        "n" to name,
        "as" to appSlug,
        "d" to TargetDriveAccessRequest.encodeList(drives),
        "cd" to "[]",
        "ac" to encodeCircles(),
        "return" to returnUrl,
        "cancel" to returnUrl + (if ('?' in returnUrl) "&" else "?") + "status=canceled",
    ).formUrlEncode()

    private fun encodeCircles(): String = Json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            circles.forEach { circle ->
                add(buildJsonObject {
                    put("s", JsonPrimitive(circle.slug))
                    put("n", JsonPrimitive(circle.name))
                    put("d", JsonPrimitive(circle.description))
                    put("e", JsonPrimitive(circle.emoji))
                    put("g", JsonArray(circle.drives.map { it.toJsonObject() }))
                })
            }
        },
    )
}
