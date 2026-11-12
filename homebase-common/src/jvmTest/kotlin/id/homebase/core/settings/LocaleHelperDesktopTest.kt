package id.homebase.core.settings

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleHelperDesktopTest {
    private val original = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
        resetStartupLocaleForTest()
    }

    @Test
    fun choosingSystemAfterExplicitLanguageRestoresStartupLocale() {
        Locale.setDefault(Locale.forLanguageTag("en-US"))
        resetStartupLocaleForTest()

        setPlatformSystemLocale("da")
        assertEquals("da", Locale.getDefault().language)

        setPlatformSystemLocale("system")
        assertEquals(Locale.forLanguageTag("en-US"), Locale.getDefault())
    }
}
