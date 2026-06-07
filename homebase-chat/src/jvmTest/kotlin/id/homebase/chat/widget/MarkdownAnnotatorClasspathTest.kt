package id.homebase.chat.widget

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Class-level guard for the Desktop runtime crash on PR #665:
 *
 *   java.lang.NoClassDefFoundError: com/mikepenz/markdown/annotator/AnnotatorSettingsKt
 *   Caused by: java.lang.ClassNotFoundException
 *
 * The annotator lives in the mikepenz CORE module
 * (`com.mikepenz:multiplatform-markdown-renderer`). [ChatMarkdown] uses it on
 * both the inline and block paths. This test asserts the core class is loadable
 * AND that the annotator code actually executes (returns a non-null
 * AnnotatedString) on the test runtime classpath.
 *
 * Honest scope: homebase-chat's OWN runtime classpath has always resolved the
 * core transitively (via the m3 artifact) because tests resolve homebase-chat's
 * dependencies normally. So this test passes both before and after the build
 * fix — it does NOT reproduce the Desktop-distributable failure, which is caused
 * by desktopApp consuming homebase-chat via a repackaged per-platform JAR that
 * strips homebase-chat's external transitive deps. That packaging crash is fixed
 * by declaring the renderer deps on homebase-core (a module desktopApp consumes
 * normally) and is validated by building/running the Desktop app. This test is a
 * cheap, fast tripwire that the annotator class never silently leaves the
 * compile/test classpath of the renderer's own module.
 */
class MarkdownAnnotatorClasspathTest {

    @Test
    fun annotatorSettingsClassIsLoadable() {
        // Exact class named in the Desktop crash's `Caused by ClassNotFoundException`.
        val clazz = Class.forName("com.mikepenz.markdown.annotator.AnnotatorSettingsKt")
        assertNotNull(clazz, "AnnotatorSettingsKt must be on the runtime classpath")
    }

    @Test
    fun buildMarkdownAnnotatedStringExecutesAndReturnsContent() {
        // Non-composable overload (no Composer param) — exercises the annotator
        // code path directly from a plain JVM test. If the core artifact were
        // missing, this call site would throw NoClassDefFoundError at link time.
        val annotated = "**x** `c` [l](https://e.test)".buildMarkdownAnnotatedString(
            style = TextStyle.Default,
            linkTextSpanStyle = SpanStyle(),
            codeSpanStyle = SpanStyle(),
        )
        assertNotNull(annotated, "annotated string must be produced")
        // The visible text drops markdown syntax: "x c l" (link label kept, URL gone).
        assertTrue(
            annotated.text.contains("x") && annotated.text.contains("c") && annotated.text.contains("l"),
            "annotated text should contain the rendered inline content, was: '${annotated.text}'",
        )
    }
}
