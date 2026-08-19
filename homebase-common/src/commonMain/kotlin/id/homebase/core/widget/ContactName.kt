package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import id.homebase.api.client.profile.PublicProfileProvider
import id.homebase.api.common.OdinId
import id.homebase.core.ui.theme.withEmojiFont
import org.koin.compose.koinInject

@Composable
fun ContactName(
    odinId: OdinId,
    knownName: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val needsFetch = knownName == null || knownName == odinId.domainName

    var displayName by remember(odinId, knownName) {
        mutableStateOf(knownName ?: odinId.domainName)
    }

    if (needsFetch) {
        val profileProvider = koinInject<PublicProfileProvider>()
        LaunchedEffect(odinId) {
            try {

                val profile = profileProvider.getPublicProfile(odinId)
                displayName = profile.name;
            } catch (_: Exception) {
                // Keep fallback domain name
            }
        }
    }

    Text(
        text = displayName.withEmojiFont(),
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}
