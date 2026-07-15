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

    // ---- Dot/dash boundary matrix, asserted under BOTH modes ----
    // Interactive (default) preserves a trailing dash only on the label being typed (the last one);
    // submit (both flags false) is the final-validation form. A domain label may not start or end
    // with '-' and may not contain '--', so every boundary dash is cleaned once it's no longer the
    // label being typed. Values verified against the implementation.

    @Test
    fun leadingDashOnAMiddleLabelIsStripped() {
        // "a.-b.c": the '-b' label starts with a dash -> stripped in both modes.
        assertEquals("a.b.c", "a.-b.c".cleanDomain())
        assertEquals("a.b.c", "a.-b.c".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }

    @Test
    fun trailingDashOnAMiddleLabelIsStripped() {
        // "a.b-.c": 'b-' is not the label being typed (a '.' follows) -> dash stripped in both modes.
        assertEquals("a.b.c", "a.b-.c".cleanDomain())
        assertEquals("a.b.c", "a.b-.c".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }

    @Test
    fun internalDoubleDashInAMiddleLabelCollapses() {
        // "a.b--c.d": consecutive dashes collapse to one everywhere.
        assertEquals("a.b-c.d", "a.b--c.d".cleanDomain())
        assertEquals("a.b-c.d", "a.b--c.d".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }

    @Test
    fun loneDashAfterATrailingDotIsDropped() {
        // "a.b.-": the '-' is a leading dash of a fresh (empty) label -> dropped, both modes.
        assertEquals("a.b", "a.b.-".cleanDomain())
        assertEquals("a.b", "a.b.-".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
        // "a.-": same shape with a single leading label.
        assertEquals("a", "a.-".cleanDomain())
        assertEquals("a", "a.-".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }

    @Test
    fun loneDashThenDotKeepsTrailingDotInteractiveButNotSubmit() {
        // "a.b.-.": the '-' label drops; interactive keeps the just-typed trailing dot, submit strips it.
        assertEquals("a.b.", "a.b.-.".cleanDomain())
        assertEquals("a.b", "a.b.-.".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }

    @Test
    fun dashTypedAtEndOfAFullDomainPreservedThenCleanedOnSubmit() {
        // The real-world case: user has typed "a.b.c" and presses '-'. Interactive keeps it so they
        // can continue to "a.b.c-d"; submit cleans the stray trailing dash.
        assertEquals("a.b.c-", "a.b.c-".cleanDomain())
        assertEquals("a.b.c", "a.b.c-".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }

    @Test
    fun trailingDashOnLastLabelPreservedInteractiveStrippedSubmit() {
        // "a.b-": 'b-' IS the label being typed -> preserved interactively, stripped on submit.
        assertEquals("a.b-", "a.b-".cleanDomain())
        assertEquals("a.b", "a.b-".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }

    @Test
    fun allDashesAndDotsCollapseToEmpty() {
        // "-.-": every label is a lone/leading dash -> nothing survives, both modes.
        assertEquals("", "-.-".cleanDomain())
        assertEquals("", "-.-".cleanDomain(preserveTrailingDot = false, preserveTrailingDash = false))
    }
}
