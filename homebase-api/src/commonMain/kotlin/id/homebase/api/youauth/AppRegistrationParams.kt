package id.homebase.api.youauth

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
    fun url(host: String, returnUrl: String): String = buildString {
        append("https://").append(host).append("/owner/appreg")
        append("?appId=").append(appId.encodeURLParam())
        append("&n=").append(name.encodeURLParam())
        append("&as=").append(appSlug.encodeURLParam())
        append("&d=").append(TargetDriveAccessRequest.encodeList(drives).encodeURLParam())
        append("&cd=").append("[]".encodeURLParam())
        append("&ac=").append(encodeCircles().encodeURLParam())
        append("&return=").append(returnUrl.encodeURLParam())
        append("&cancel=").append(withQuery(returnUrl, "status=canceled").encodeURLParam())
    }

    private fun encodeCircles(): String = Json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            circles.forEach { circle ->
                add(buildJsonObject {
                    put("s", JsonPrimitive(circle.slug))
                    put("n", JsonPrimitive(circle.name))
                    put("d", JsonPrimitive(circle.description))
                    put("e", JsonPrimitive(circle.emoji))
                    put("g", buildJsonArray {
                        circle.drives.forEach { drive ->
                            add(buildJsonObject {
                                put("a", JsonPrimitive(drive.alias))
                                put("t", JsonPrimitive(drive.type))
                                put("p", JsonPrimitive(DrivePermission.combine(drive.permissions)))
                            })
                        }
                    })
                })
            }
        },
    )

    private fun withQuery(url: String, query: String) = url + (if ('?' in url) "&" else "?") + query
}
