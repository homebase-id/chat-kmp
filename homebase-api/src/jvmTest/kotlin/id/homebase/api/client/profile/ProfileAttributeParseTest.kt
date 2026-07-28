@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.profile

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.common.SecureByteArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Pins [HomebaseFile.toProfileAttribute] — the single parser behind [ProfileRepository]. */
class ProfileAttributeParseTest {

    private val uniqueId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val versionTag = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun fileFor(
        content: String?,
        uniqueId: Uuid? = this.uniqueId,
        securityGroup: String? = null,
        fileState: FileState = FileState.Active,
    ): HomebaseFile = HomebaseFile(
        fileId = Uuid.parse("99999999-9999-9999-9999-999999999999"),
        driveId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        fileState = fileState,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        fileMetadata = FileMetadata(
            versionTag = versionTag,
            appData = AppFileMetaData(
                uniqueId = uniqueId,
                fileType = ProfileProvider.PROFILE_ATTRIBUTE_FILE_TYPE,
                content = content,
            ),
        ),
        serverMetadata = ServerMetadata(
            accessControlList = securityGroup?.let { AccessControlList(requiredSecurityGroup = it) },
        ),
    )

    private fun nameContent(type: String = ProfileAttributeTypes.NAME): String =
        """{"id":"$uniqueId","type":"$type","data":{"givenName":"Sam","surname":"Public"}}"""

    @Test
    fun fullAttribute_preservesIdTagAndData() {
        val attr = fileFor(nameContent())!!.toProfileAttribute()!!

        assertEquals(uniqueId, attr.id)
        assertEquals(versionTag, attr.versionTag)
        assertEquals(ProfileAttributeTypes.NAME, attr.type)
        assertEquals("Sam", attr.string(ProfileAttributeTypes.KEY_GIVEN_NAME))
        assertEquals("Public", attr.string(ProfileAttributeTypes.KEY_SURNAME))
    }

    @Test
    fun dashedUppercaseType_isNormalizedToNoDashLowercase() {
        // The drive may store the type as a dashed (and/or upper-cased) GUID; it must still match
        // the no-dash constant. b068931c-c450-442b-63f5-b3d276ea4297 → ProfileAttributeTypes.NAME
        val attr = fileFor(nameContent(type = "B068931C-C450-442B-63F5-B3D276EA4297"))
            .toProfileAttribute()!!
        assertEquals(ProfileAttributeTypes.NAME, attr.type)
    }

    @Test
    fun visibility_readFromSecurityGroup_caseInsensitive() {
        assertEquals(
            ProfileVisibility.CONNECTED,
            fileFor(nameContent(), securityGroup = "Connected").toProfileAttribute()!!.visibility,
        )
        assertEquals(
            ProfileVisibility.ANONYMOUS,
            fileFor(nameContent(), securityGroup = "anonymous").toProfileAttribute()!!.visibility,
        )
    }

    @Test
    fun visibility_absentSecurityGroup_defaultsToOwner() {
        assertEquals(
            ProfileVisibility.OWNER,
            fileFor(nameContent(), securityGroup = null).toProfileAttribute()!!.visibility,
        )
    }

    @Test
    fun missingData_yieldsEmptyDataObject() {
        val attr = fileFor("""{"id":"$uniqueId","type":"${ProfileAttributeTypes.STATUS}"}""")
            .toProfileAttribute()!!
        assertEquals(0, attr.data.size)
        assertNull(attr.string(ProfileAttributeTypes.KEY_STATUS))
    }

    @Test
    fun socialHandle_readsThroughStringAccessor() {
        val attr = fileFor(
            """{"id":"$uniqueId","type":"${ProfileAttributeTypes.TWITTER}","data":{"twitter":"@sam"}}""",
        ).toProfileAttribute()!!
        assertEquals(JsonPrimitive("@sam"), attr.data[ProfileAttributeTypes.KEY_TWITTER])
        assertEquals("@sam", attr.string(ProfileAttributeTypes.KEY_TWITTER))
    }

    @Test
    fun returnsNull_whenUniqueIdMissing() {
        assertNull(fileFor(nameContent(), uniqueId = null).toProfileAttribute())
    }

    @Test
    fun returnsNull_whenContentMissing() {
        assertNull(fileFor(content = null).toProfileAttribute())
    }

    @Test
    fun returnsNull_whenContentInvalidJson() {
        assertNull(fileFor("not valid json {{{").toProfileAttribute())
    }

    @Test
    fun returnsNull_whenTypeMissing() {
        assertNull(fileFor("""{"id":"$uniqueId","data":{"x":"y"}}""").toProfileAttribute())
    }

    @Test
    fun returnsNull_whenSoftDeleted() {
        assertNull(fileFor(nameContent(), fileState = FileState.Deleted).toProfileAttribute())
    }
}
