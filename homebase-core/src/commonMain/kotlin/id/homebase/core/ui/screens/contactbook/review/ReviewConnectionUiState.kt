package id.homebase.core.ui.screens.contactbook.review

import androidx.compose.runtime.Immutable

/** One of the owner's own circles, offered as a checkbox in the review modal. */
@Immutable
data class ReviewCircleOption(
    val id: String,
    val name: String,
    val emoji: String?,
    /**
     * False when this app holds no grant on a drive the circle grants — the server would reject
     * the enrolment with CannotSourceDriveStorageKeyForGrant. Shown, but not selectable.
     */
    val grantable: Boolean = true,
)

/**
 * One app's default-circle toggle. [pending] means a prior review queued this app's enrolment
 * because the app's own key wasn't in scope — checked, but not yet in effect.
 */
@Immutable
data class ReviewAppToggle(
    val appId: String,
    val label: String,
    val circleIds: List<String>,
    val pending: Boolean = false,
)

@Immutable
data class ReviewConnectionUiState(
    val odinId: String = "",
    val displayName: String = "",
    val introducerOdinId: String? = null,
    val circles: List<ReviewCircleOption> = emptyList(),
    val appToggles: List<ReviewAppToggle> = emptyList(),
    val selectedCircleIds: Set<String> = emptySet(),
    val checkedAppIds: Set<String> = emptySet(),
    val followFeed: Boolean = false,
    val submitting: Boolean = false,
    val error: ReviewError? = null,
) {
    /**
     * The destination the button will produce. Only the owner's own circles move a contact to
     * Circle, so the label always predicts the state the row will then show.
     */
    val addsToCircles: Boolean get() = selectedCircleIds.isNotEmpty()
}

enum class ReviewError { Generic, NotConnected, CircleNotAllowed }

sealed interface ReviewConnectionUiAction {
    data class CircleToggled(val circleId: String) : ReviewConnectionUiAction
    data class AppToggled(val appId: String) : ReviewConnectionUiAction
    data class FollowFeedToggled(val follow: Boolean) : ReviewConnectionUiAction
    data object SubmitClicked : ReviewConnectionUiAction
    data object KeepAsNewClicked : ReviewConnectionUiAction
    data object DisconnectClicked : ReviewConnectionUiAction
    data object BlockClicked : ReviewConnectionUiAction
}

sealed interface ReviewConnectionUiEvent {
    /**
     * Review completed. [toCircles] is false for the chat-only outcome; [notYetActiveCount] is
     * how many circles were enrolled but aren't usable yet (deposited or queued for another app).
     */
    data class Completed(
        val toCircles: Boolean,
        val notYetActiveCount: Int = 0,
    ) : ReviewConnectionUiEvent
    data object Dismissed : ReviewConnectionUiEvent
    data object Disconnected : ReviewConnectionUiEvent
    data object Blocked : ReviewConnectionUiEvent
}
