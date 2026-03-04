package id.homebase.core.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.ExtendedColors
import id.homebase.resources.MR
import id.homebase.resources.connection_requests_banner
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectionRequestHeaderBanner(
    modifier: Modifier = Modifier,
    requestCount: Int,
    onBannerClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ExtendedColors.RequestBanner)
            .clickable { onBannerClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(MR.string.connection_requests_banner, requestCount.toString()),
            color = ExtendedColors.OnRequestBanner
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = ExtendedColors.OnRequestBanner)
    }

}