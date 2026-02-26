package id.homebase.api.youauth

import co.touchlab.kermit.Logger
import id.homebase.api.client.ApiResponse
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.auth.CredentialsManager
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Provider for fetching security context from the API. */
class SecurityContextProvider(httpClient: HttpClient, credentialsManager: CredentialsManager) :
    OdinApiProviderBase(httpClient, credentialsManager) {

    companion object {
        private const val TAG = "SecurityContextProvider"
    }

    //TODO: Move to OdinSystemSerializer
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch the security context for the current session.
     *
     * For owner API type, returns a minimal context with owner security level. For app API type,
     * fetches from /security/context and parses drive permissions.
     */
    suspend fun getSecurityContext(): SecurityContext? {
        return try {

            val creds = requireCreds()
            val url = apiUrl(creds.domain, "/auth/context")

            val credentials = requireCreds()
            val response: ApiResponse = encryptedGet(
                url = url,
                token = credentials.accessToken,
                secret = credentials.secret
            )

            throwForFailure(response)
            parseSecurityContext(response.body)
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Error fetching security context: ${e.message}" }
            null
        }
    }

    /** Parse the JSON response into SecurityContext, converting string permissions to enums. */
    private fun parseSecurityContext(jsonString: String): SecurityContext {
        val root = json.parseToJsonElement(jsonString).jsonObject

        val callerJson = root["caller"]?.jsonObject
        val caller =
            CallerContext(
                odinId = callerJson?.get("odinId")?.jsonPrimitive?.contentOrNull,
                securityLevel =
                    callerJson?.get("securityLevel")?.jsonPrimitive?.contentOrNull
                        ?: "anonymous",
                isGrantedConnectedIdentitiesSystemCircle =
                    callerJson
                        ?.get("isGrantedConnectedIdentitiesSystemCircle")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.toBooleanStrictOrNull()
                        ?: false
            )

        val permissionContextJson = root["permissionContext"]?.jsonObject
        val permissionGroupsJson =
            permissionContextJson?.get("permissionGroups")?.jsonArray ?: JsonArray(emptyList())

        val permissionGroups =
            permissionGroupsJson.map { groupElement ->
                parsePermissionGroup(groupElement.jsonObject)
            }

        return SecurityContext(
            caller = caller,
            permissionContext = PermissionContext(permissionGroups = permissionGroups)
        )
    }

    private fun parsePermissionGroup(groupJson: JsonObject): PermissionGroup {
        val driveGrantsJson = groupJson["driveGrants"]?.jsonArray
        val driveGrants =
            driveGrantsJson?.map { grantElement -> parseDriveGrant(grantElement.jsonObject) }

        val permissionSetJson = groupJson["permissionSet"]?.jsonObject
        val permissionSet =
            if (permissionSetJson != null) {
                val keys =
                    permissionSetJson["keys"]?.jsonArray?.map { it.jsonPrimitive.int }
                        ?: emptyList()
                PermissionSet(keys = keys)
            } else null

        return PermissionGroup(driveGrants = driveGrants, permissionSet = permissionSet)
    }

    private fun parseDriveGrant(grantJson: JsonObject): DriveGrant {
        val permissionedDriveJson =
            grantJson["permissionedDrive"]?.jsonObject
                ?: throw IllegalArgumentException("Missing permissionedDrive")

        val driveJson =
            permissionedDriveJson["drive"]?.jsonObject
                ?: throw IllegalArgumentException("Missing drive")

        val drive =
            DriveReference(
                alias = driveJson["alias"]?.jsonPrimitive?.contentOrNull ?: "",
                type = driveJson["type"]?.jsonPrimitive?.contentOrNull ?: ""
            )

        // Parse permission - can be string or array
        val permissionElement = permissionedDriveJson["permission"]
        val permissions = parsePermission(permissionElement)

        return DriveGrant(
            permissionedDrive = PermissionedDrive(drive = drive, permission = permissions)
        )
    }

    /**
     * Convert permission from API format to DrivePermissionType list. Handles both string format
     * ("ReadWrite") and array format.
     */
    private fun parsePermission(element: JsonElement?): List<DrivePermissionType> {
        if (element == null) return emptyList()

        return when (element) {
            is JsonPrimitive -> {
                // String permission like "ReadWrite", "All", etc.
                getDrivePermissionFromString(element.contentOrNull ?: "")
            }

            is JsonArray -> {
                // Array of permission values
                element.mapNotNull {
                    val value = it.jsonPrimitive.int
                    DrivePermissionType.entries.find { type -> type.value == value }
                }
            }

            else -> emptyList()
        }
    }
}

/**
 * Convert text-based permission levels to DrivePermissionType list. Reflects DrivePermission enum
 * in services/Odin.Core.Services/Drives/DrivePermission.cs
 */
fun getDrivePermissionFromString(permission: String): List<DrivePermissionType> {
    if (permission.isBlank()) return emptyList()

    var lowered = permission.lowercase()

    // Convert multi types to their simpler form
    lowered = lowered.replace("writereactionsandcomments", "react,comment")
    lowered = lowered.replace("readwrite", "read,write,react,comment")
    lowered = lowered.replace("reactandwrite", "write,react")
    lowered = lowered.replace("all", "read,write,react,comment")

    val parts = lowered.split(",")

    return parts.mapNotNull { part ->
        when (part.trim()) {
            "read" -> DrivePermissionType.Read
            "write" -> DrivePermissionType.Write
            "react" -> DrivePermissionType.React
            "comment" -> DrivePermissionType.Comment
            else -> null
        }
    }
}

/** Get unique drives with highest permission by merging permissions for same alias/type. */
fun getUniqueDrivesWithHighestPermission(grants: List<DriveGrant>): List<DriveGrant> {
    return grants.fold(mutableListOf()) { result, grantedDrive ->
        val existingIndex =
            result.indexOfFirst { driveGrant ->
                driveGrant.permissionedDrive.drive.alias ==
                        grantedDrive.permissionedDrive.drive.alias &&
                        driveGrant.permissionedDrive.drive.type ==
                        grantedDrive.permissionedDrive.drive.type
            }

        if (existingIndex != -1) {
            // Merge permissions
            val existing = result[existingIndex]
            val mergedPermissions =
                (existing.permissionedDrive.permission +
                        grantedDrive.permissionedDrive.permission)
                    .distinct()
            result[existingIndex] =
                DriveGrant(
                    permissionedDrive =
                        PermissionedDrive(
                            drive = existing.permissionedDrive.drive,
                            permission = mergedPermissions
                        )
                )
        } else {
            result.add(grantedDrive)
        }
        result
    }
}
