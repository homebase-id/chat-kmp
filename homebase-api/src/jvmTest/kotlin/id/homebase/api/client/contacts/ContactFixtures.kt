package id.homebase.api.client.contacts

/** Test fixtures for the V2 Contacts wire shapes. */
internal object ContactFixtures {

    /** A 200 OK write body. */
    fun okBody(uniqueId: String, versionTag: String): String =
        """{"uniqueId":"$uniqueId","versionTag":"$versionTag"}"""

    /**
     * A 409 ContactWriteConflict body whose `current` is a minimal but valid [ServerFile]-shaped
     * file header. `fileMetadata.versionTag` mirrors the top-level [versionTag] (the server keeps
     * them equal) and `fileMetadata.appData.uniqueId` carries the contact id.
     */
    fun conflictBody(uniqueId: String, versionTag: String): String =
        """
        {
          "versionTag": "$versionTag",
          "current": {
            "fileId": "$uniqueId",
            "driveId": "00000000-0000-0000-0000-000000000001",
            "fileState": "active",
            "fileSystemType": "standard",
            "sharedSecretEncryptedKeyHeader": { "encryptionVersion": 1, "iv": "AAAA", "encryptedAesKey": "AAAA" },
            "fileMetadata": {
              "versionTag": "$versionTag",
              "appData": { "uniqueId": "$uniqueId", "fileType": 100 }
            },
            "serverMetadata": {}
          }
        }
        """.trimIndent()
}
