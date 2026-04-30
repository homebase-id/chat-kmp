package id.homebase.core.architecture

import androidx.compose.runtime.Composable
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.functions
import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Ignore
import kotlin.test.Test

class ArchitectureTest {
    @Test
    fun `UiState classes should be data classes`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("UiState")
            .assertTrue { it.hasDataModifier }
    }

    @Test
    @Ignore("This test is currently ignored because we need to fix existing hardcoded strings in Composables before enforcing this rule")
    fun `No hardcoded strings in Composables`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .functions()
            .withAnnotationOf(Composable::class)
            .assertFalse {
                it.text.contains(Regex("""Text\s*\(\s*"[^"]*"""")) ||
                        it.text.contains(Regex("""Text\s*\(\s*text\s*=\s*"[^"]*""""))
            }
    }

    @Test
    fun `Do not allow calling close on httpClient`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .assertFalse(
                additionalMessage = "HttpClient is managed by Koin DI and should not be manually closed"
            ) { file ->
                file.text.contains(Regex("""httpClient\s*\.\s*close\s*\("""))
            }
    }
}
