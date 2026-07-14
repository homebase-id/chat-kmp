package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the dash-handling contract of [cleanDomain] (#1088). The function runs on every keystroke,
 * so a dash the user just pressed lands as a *trailing* dash on the label being typed and must
 * survive while `preserveTrailingDash` is true (the interactive default) — mirroring the existing
 * `preserveTrailingDot` behavior. Submit paths pass `false` to strip a stray trailing dash.
 */
class CleanDomainTest {

    @Test
    fun internalDashPreserved() {
        assertEquals("my-domain.com", "my-domain.com".cleanDomain())
    }

    @Test
    fun justTypedTrailingDashPreservedByDefault() {
        // The bug: pressing '-' after "example" used to yield "example" (dash eaten). Now kept.
        assertEquals("example-", "example-".cleanDomain())
    }

    @Test
    fun submitStripsTrailingDash() {
        assertEquals(
            "example",
            "example-".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false),
        )
    }

    @Test
    fun consecutiveDashesStillCollapse() {
        assertEquals("a-b.com", "a--b.com".cleanDomain())
    }

    @Test
    fun leadingDashStillStripped() {
        assertEquals("a.com", "-a.com".cleanDomain())
    }

    @Test
    fun typingProgressionKeepsDash() {
        assertEquals("my-", "my-".cleanDomain())
        assertEquals("my-domain", "my-domain".cleanDomain())
    }

    @Test
    fun trailingDashOnlyPreservedOnLastLabel() {
        // A dash left trailing on a non-last label (before a dot) is still cleaned interactively.
        assertEquals("a.b", "a-.b".cleanDomain())
    }

    @Test
    fun collapsedTrailingDashesLeaveSinglePreservedDash() {
        // "example--" collapses to a single dash, which as the typed label stays put.
        assertEquals("example-", "example--".cleanDomain())
    }
}
