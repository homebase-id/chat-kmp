package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/** Initials scale with the circle as PeerAvatar's do, so shots match the app at every avatar size. */
internal val testPeerAvatar: @Composable (Dp) -> Unit = { size ->
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text("SG", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = (size.value / 3).sp, fontWeight = FontWeight.Medium)
    }
}
