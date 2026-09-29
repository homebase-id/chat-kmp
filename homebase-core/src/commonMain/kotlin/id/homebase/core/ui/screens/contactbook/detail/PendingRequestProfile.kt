package id.homebase.core.ui.screens.contactbook.detail

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.media.subsample.SubSamplingImageSource
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups
import id.homebase.core.ui.screens.contactbook.components.PendingRequestReview
import id.homebase.core.ui.screens.contactbook.components.ContactBookAvatar
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry

/**
 * Self-contained public-profile card shown in place of the tabbed contact detail while an
 * incoming connection request is still pending (#921). Everything it shows is public and
 * fetchable before connecting — avatar (`/pub/image`), display name, Homebase ID, status, and
 * short bio summary — so the Accept/Reject decision has real context instead of the empty
 * "Contact details: None" / "connect to see…" placeholders.
 *
 * This owns the whole pending presentation (rather than reusing the shared header + tabs) so the
 * pre-connection state's logic lives in one place — it is the *only* place the detail screen
 * offers Accept/Reject, since the header renders only once the request is gone. Accepting flips
 * the parent screen to the full connected detail in place — no navigation.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PendingRequestProfile(
    entry: ContactBookEntry,
    review: ReviewSheetState,
    reviewCircleGroups: ReviewCircleGroups,
    onReviewSubmit: (Set<String>) -> Unit,
    onReject: () -> Unit,
    onAvatarClick: (SubSamplingImageSource) -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    PendingRequestReview(
        review = review,
        displayName = entry.displayName,
        odinId = entry.odinId,
        groups = reviewCircleGroups,
        onSubmit = onReviewSubmit,
        onReject = onReject,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 24.dp),
        avatar = {
            ContactBookAvatar(
                entry = entry,
                size = 52.dp,
                onClick = onAvatarClick,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        },
        details = {
            entry.status?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            entry.shortBio?.takeIf { it.isNotBlank() }?.let { bio -> ShortBioCard(bio) }
        },
    )
}

/** The public short-bio summary. */
@Composable
private fun ShortBioCard(bio: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        SelectionContainer {
            Text(
                text = bio,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
