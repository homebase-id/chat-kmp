package id.homebase.chat.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileExistenceTest {
    @Test
    fun schemeUris_countAsPresent() {
        assertTrue(fileExists("file:///x"))
        assertTrue(fileExists("content://x"))
        assertTrue(fileExists("blob:https://x/y"))
    }

    @Test
    fun missingPath_isFalse() {
        assertFalse(fileExists("/definitely/missing"))
    }
}
