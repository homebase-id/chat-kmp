package id.homebase.core.ui.screens.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.core.ui.screens.card.FAMILY_CIRCLE_ID
import id.homebase.core.ui.screens.card.FRIENDS_CIRCLE_ID
import id.homebase.core.ui.screens.card.WORK_CIRCLE_ID
import id.homebase.core.ui.screens.card.CardExpressiveTheme
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders the profile editor in every state, light and dark, to PNGs for design review.
 * Set CARD_SHOTS_DIR to write the images; without it the states are only composed.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalUuidApi::class)
class ProfileEditShotsTest {

    private val outDir: File? = System.getenv("CARD_SHOTS_DIR")?.let(::File)?.also { it.mkdirs() }

    /** Comma-separated shot-name prefixes; renders only those, for quick iteration on one state. */
    private val only: List<String> = System.getenv("CARD_SHOTS_ONLY")?.split(',')?.map { it.trim() }.orEmpty()

    private val family = CardCircle(FAMILY_CIRCLE_ID, "Family")
    private val friends = CardCircle(FRIENDS_CIRCLE_ID, "Friends")
    private val work = CardCircle(WORK_CIRCLE_ID, "Work")
    private val climbing = CardCircle("c", "Climbing partners from the Tuesday bouldering gym night")
    private val circles = listOf(family, friends, work)

    private val filled = ProfileEditUiState(
        isLoading = false,
        circles = circles,
        values = mapOf(
            ProfileField.GIVEN_NAME to "Samwise",
            ProfileField.SURNAME to "Gamgee",
            ProfileField.NICKNAME to "Sam",
            ProfileField.BIO to "Gardener, cook, and the most loyal friend in the Shire.",
            ProfileField.BIRTHDAY to "1980-04-06",
            ProfileField.EMAIL to "sam@bagend.me",
            ProfileField.PHONE to "+14155550123",
            ProfileField.PHONE_LABEL to "Mobile",
            ProfileField.CITY to "Hobbiton",
            ProfileField.COUNTRY to "The Shire",
            ProfileField.INSTAGRAM to "samwise",
        ),
        audiences = mapOf(
            ProfileAttributeTypes.NAME to ProfileAudience.Public,
            ProfileAttributeTypes.NICKNAME to ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID)),
            ProfileAttributeTypes.BIO_SUMMARY to ProfileAudience.Public,
            ProfileAttributeTypes.BIRTHDAY to ProfileAudience.OnlyMe,
            ProfileAttributeTypes.EMAIL to ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID, WORK_CIRCLE_ID)),
            ProfileAttributeTypes.PHONE to ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID)),
            ProfileAttributeTypes.ADDRESS to ProfileAudience.Circles(setOf(FRIENDS_CIRCLE_ID, WORK_CIRCLE_ID)),
            ProfileAttributeTypes.INSTAGRAM to ProfileAudience.Public,
        ),
        links = listOf(
            LinkDraft("l1", "Garden journal", "https://sam.garden", ProfileAudience.Public),
            LinkDraft("l2", "Recipes", "https://sam.kitchen", ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID))),
        ),
    )

    private fun with(type: String, audience: ProfileAudience, state: ProfileEditUiState = filled) =
        state.copy(audiences = state.audiences + (type to audience))

    private val avatar = ProfileAvatarEditUiState(isLoading = false)

    private fun photo(visibility: ProfileVisibility, acl: AccessControlList) = ProfileAttribute(
        id = Uuid.random(),
        type = ProfileAttributeTypes.PHOTO,
        versionTag = Uuid.random(),
        visibility = visibility,
        data = buildJsonObject { put(ProfileAttributeTypes.KEY_PROFILE_IMAGE, "key") },
        acl = acl,
    )

    private val publicPhoto = photo(ProfileVisibility.ANONYMOUS, AccessControlList(requiredSecurityGroup = "anonymous"))
    private val familyPhoto = photo(ProfileVisibility.CONNECTED, AccessControlList(requiredSecurityGroup = "connected", circleIdList = listOf(FAMILY_CIRCLE_ID)))

    /** A seeded Public photo and a set Family photo; Friends and Work inherit the public one. */
    private fun circleAvatar(supported: Boolean) = ProfileAvatarEditUiState(
        isLoading = false,
        anonymous = PhotoTierUiState(ProfileVisibility.ANONYMOUS, existing = publicPhoto),
        circleSlots = mapOf(FAMILY_CIRCLE_ID to PhotoTierUiState(ProfileVisibility.CONNECTED, circleId = FAMILY_CIRCLE_ID, existing = familyPhoto)),
        photos = listOf(publicPhoto, familyPhoto),
        circlePhotosSupported = supported,
    )

    private class Shot(
        val name: String,
        val state: ProfileEditUiState,
        val clicks: List<String> = emptyList(),
        val fontScale: Float = 1f,
        val rtl: Boolean = false,
        val widthDp: Int = PHONE_W,
        val heightDp: Int = PHONE_H,
        val avatar: ProfileAvatarEditUiState? = null,
        /** Friends and Work show the inherited photo; null skips the circle-slot assertions. */
        val inheritedSlots: Int? = null,
        val useDifferentButtons: Int = 0,
    )

    private val empty = ProfileEditUiState(isLoading = false, circles = circles)

    private val shots = listOf(
        Shot("k8-01-overview", filled),
        Shot("k8-01b-overview-full", filled, heightDp = FULL_H),
        Shot("k8-02-phone-one-circle", filled, listOf("Phone")),
        Shot("k8-03-email-every-circle", filled, listOf("Email")),
        Shot("k8-04-bio-public", filled, listOf("Bio")),
        Shot("k8-05-birthday-only-me", filled, listOf("Birthday")),
        Shot("k8-06-no-circle-picked", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(emptySet())), listOf("Phone")),
        Shot(
            "k8-07-other-circles",
            with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID), setOf("x1", "x2")))
                .copy(otherCircleNames = mapOf("x1" to "Book club", "x2" to "Hiking crew")),
            listOf("Phone"),
        ),
        Shot("k8-08-no-contacts-circles", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(emptySet()), filled.copy(circles = emptyList())), listOf("Phone")),
        Shot("k8-09-long-circle", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf("c", FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID)), filled.copy(circles = circles + climbing)), heightDp = FULL_H),
        Shot("k8-09b-long-circle-open", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf("c", FAMILY_CIRCLE_ID)), filled.copy(circles = circles + climbing)), listOf("Phone")),
        Shot("k8-10-link-open", filled, listOf("Recipes")),
        Shot("k8-11-empty-profile", empty),
        Shot("k8-12-loading", ProfileEditUiState()),
        Shot("k8-13-load-failed", ProfileEditUiState(isLoading = false, loadFailed = true)),
        Shot("k8-14-font-scale", filled, fontScale = 1.6f),
        Shot("k8-15-font-scale-open", filled, listOf("Phone"), fontScale = 1.6f),
        Shot("k8-16-rtl-open", filled, listOf("Phone"), rtl = true),
        Shot("k8-17-small-open", filled, listOf("Email"), widthDp = 360, heightDp = 640),
        Shot("k11-01-card-photos", filled, heightDp = FULL_H),
        Shot("k11-02-card-photos-rtl", filled, heightDp = FULL_H, rtl = true),
        Shot("k8-19-single-card", filled.copy(circles = emptyList(), audiences = filled.audiences.filterValues { it !is ProfileAudience.Circles })),
        Shot("k8-20-add-sheet", filled, listOf("Add detail")),
        Shot("k8-21-add-from-empty", empty, listOf("Phone")),
        Shot("k8-22-add-from-empty-no-circles", empty.copy(circles = emptyList()), listOf("Phone")),
        Shot("k8-23-add-status", filled, listOf("Add detail", "Status")),
        Shot("k8-24-audience-popover", filled, listOf("cd:Visible to Family. Change")),
        Shot("k8-25-audience-popover-rtl", filled, listOf("cd:Visible to Family. Change"), rtl = true),
        Shot("k8-26-custom-label", filled.copy(values = filled.values + (ProfileField.EMAIL_LABEL to "Hobbit post")), listOf("Email")),
        // A legacy Connected record with no circles loads as every Contacts circle picked.
        Shot("k8-27-legacy-all-circles-open", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID, FRIENDS_CIRCLE_ID, WORK_CIRCLE_ID))), listOf("Phone")),
        Shot("k8-28-birthday-picker", filled, listOf("Birthday", "cd:Birthday")),
        Shot("k8-29-popover-other-circles", with(ProfileAttributeTypes.PHONE, ProfileAudience.Circles(setOf(FAMILY_CIRCLE_ID), setOf("x1")))
            .copy(otherCircleNames = mapOf("x1" to "Book club")), listOf("cd:Visible to Family, Book club. Change")),
        Shot("k11-03-card-photos-font-scale", filled, fontScale = 1.6f, heightDp = FULL_H),
        Shot("k11-04-circle-photos", filled, heightDp = FULL_H, avatar = circleAvatar(true), inheritedSlots = 2, useDifferentButtons = 2),
        Shot("k11-05-circle-photos-rtl", filled, heightDp = FULL_H, rtl = true, avatar = circleAvatar(true), inheritedSlots = 2, useDifferentButtons = 2),
        Shot("k11-06-circle-photos-gate-off", filled, heightDp = FULL_H, avatar = circleAvatar(false), inheritedSlots = 2, useDifferentButtons = 0),
        Shot("k11-07-circle-photos-gate-off-rtl", filled, heightDp = FULL_H, rtl = true, avatar = circleAvatar(false), inheritedSlots = 2, useDifferentButtons = 0),
    )

    @Test
    fun profileEditorRendersEveryState() {
        for (dark in listOf(false, true)) {
            for (shot in shots) if (only.isEmpty() || only.any { shot.name.startsWith(it) }) render(shot, dark)
        }
    }

    @Composable
    private fun themed(dark: Boolean, fontScale: Float = 1f, rtl: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(SCALE, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            HomebaseTheme(darkTheme = dark, updatesSystemChrome = false) {
                CardExpressiveTheme(content)
            }
        }
    }

    private fun render(shot: Shot, dark: Boolean) = runDesktopComposeUiTest(
        width = (shot.widthDp * SCALE).toInt(),
        height = (shot.heightDp * SCALE).toInt(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            themed(dark, shot.fontScale, shot.rtl) {
                var state by remember { mutableStateOf(shot.state) }
                ProfileEditContent(
                    uiState = state,
                    avatarUiState = shot.avatar ?: avatar,
                    previewMode = false,
                    onTogglePreview = {},
                    onOpenCard = {},
                    onAction = { action ->
                        when (action) {
                            is ProfileEditAction.AudienceChanged ->
                                state = state.copy(audiences = state.audiences + (action.type to action.audience))
                            is ProfileEditAction.FieldChanged ->
                                state = state.copy(values = state.values + (action.field to action.value))
                            else -> Unit
                        }
                    },
                    onAvatarAction = {},
                    onPickPhoto = {},
                    onPickOnlyMePhoto = {},
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        for (label in shot.clicks) {
            // A scroll waits on its own animation frames, which a paused clock never delivers.
            mainClock.autoAdvance = true
            val node = if (label.startsWith(BY_DESCRIPTION)) {
                onAllNodesWithContentDescription(label.removePrefix(BY_DESCRIPTION), useUnmergedTree = true).onFirst()
            } else {
                onAllNodesWithText(label, useUnmergedTree = true).onFirst()
            }
            // Floating chrome (the extended FAB, a sheet) has no scrolling parent to bring it into view.
            runCatching { node.performScrollTo() }
            node.performClick()
            // The injected tap leaves a pointer behind that hovers whichever row scrolls under it.
            onAllNodes(isRoot()).onFirst().performMouseInput {
                moveTo(Offset(width / 2f, 1f))
                exit()
            }
            waitForIdle()
            mainClock.autoAdvance = false
            mainClock.advanceTimeBy(SETTLE_MS)
        }
        if (shot.clicks.isNotEmpty()) {
            // A sheet's pick lands only after its hide animation, so the row it opens needs frames of its own.
            mainClock.autoAdvance = true
            waitForIdle()
            mainClock.autoAdvance = false
            mainClock.advanceTimeBy(SETTLE_MS)
        }
        assertPhotoSection(shot)
        save(shot.name, dark)
    }

    private fun ComposeUiTest.assertPhotoSection(shot: Shot) {
        onAllNodesWithText("Your cards", useUnmergedTree = true).assertCountEquals(0)
        if (shot.avatar == null) return
        for (caption in listOf("Friends", "Family", "Work")) {
            assertTrue(onAllNodesWithText(caption, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "caption $caption missing in ${shot.name}")
        }
        onAllNodesWithText("Shown on every card", useUnmergedTree = true).assertCountEquals(1)
        shot.inheritedSlots?.let { onAllNodesWithText("Uses your Public photo", useUnmergedTree = true).assertCountEquals(it) }
        onAllNodesWithText("Use a different photo", useUnmergedTree = true).assertCountEquals(shot.useDifferentButtons)
        val intro = if (shot.useDifferentButtons > 0) "Each card can have its own photo" else "Friends, Family and Work show the photo they inherit for now"
        assertTrue(onAllNodesWithText(intro, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "intro for ${shot.name}")
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        val image = onAllNodes(isRoot()).onFirst().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        outDir?.let { dir ->
            ImageIO.write(image.toAwtImage(), "png", File(dir, "$name-${if (dark) "dark" else "light"}.png"))
        }
    }

    private companion object {
        const val SCALE = 2f
        const val PHONE_W = 412
        const val PHONE_H = 892
        const val SETTLE_MS = 1_500L
        const val FULL_H = 1_900
        const val BY_DESCRIPTION = "cd:"
    }
}
