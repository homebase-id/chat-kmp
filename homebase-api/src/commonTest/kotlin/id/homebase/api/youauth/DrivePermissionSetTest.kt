package id.homebase.api.youauth

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class DrivePermissionSetTest {

    private fun parse(wire: String) = OdinSystemSerializer.deserialize<DrivePermissionSet>("\"$wire\"").values.toSet()

    @Test
    fun theServersCombinedNamesReadAsTheirBits() {
        val all = DrivePermission.entries.toSet()
        assertEquals(all, parse("readWrite"))
        assertEquals(all, parse("all"))
        assertEquals(setOf(DrivePermission.React, DrivePermission.Comment), parse("writeReactionsAndComments"))
        assertEquals(setOf(DrivePermission.Read, DrivePermission.React, DrivePermission.Comment), parse("writeReactionsAndComments, read"))
    }

    @Test
    fun singleNamesStillRead() {
        assertEquals(setOf(DrivePermission.Read, DrivePermission.Write), parse("read, write"))
        assertEquals(setOf(DrivePermission.Comment), parse("comment"))
    }
}
