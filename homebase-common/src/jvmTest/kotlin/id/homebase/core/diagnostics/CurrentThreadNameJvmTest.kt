package id.homebase.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertTrue

class CurrentThreadNameJvmTest {
    @Test
    fun unnamedThreadReportsAPlaceholder() {
        var name = ""
        Thread({ name = currentThreadName() }, "").apply { start(); join() }
        assertTrue(name.isNotBlank())
    }
}
