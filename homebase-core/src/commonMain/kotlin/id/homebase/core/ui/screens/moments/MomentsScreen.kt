package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.resources.MR
import id.homebase.resources.moments_create_action
import id.homebase.resources.moments_label
import id.homebase.resources.moments_post_open
import id.homebase.resources.moments_reaction_like
import org.jetbrains.compose.resources.stringResource

/**
 * Page 1 — Main Feed / Timeline.
 *
 * Skeleton only: hard-coded sample posts so the layout can be evaluated. The
 * 2×2 grid cells, indicator badges, FAB, and post tap are all wired as empty
 * placeholders for the real components to slot into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(
    onCreateMoment: () -> Unit = {},
    onOpenMoment: (momentId: String) -> Unit = {},
) {
    val posts = remember { samplePosts() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(MR.string.moments_label)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateMoment) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.moments_create_action),
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(posts, key = { it.id }) { post ->
                MomentPostCard(
                    post = post,
                    onClick = { onOpenMoment(post.id) },
                )
            }
        }
    }
}

private data class MomentPost(
    val id: String,
    val assetCount: Int,
    val hasDescription: Boolean,
    val commentCount: Int,
    val isLiked: Boolean,
)

private fun samplePosts(): List<MomentPost> = listOf(
    MomentPost(id = "1", assetCount = 4, hasDescription = true, commentCount = 3, isLiked = false),
    MomentPost(id = "2", assetCount = 1, hasDescription = false, commentCount = 0, isLiked = true),
    MomentPost(id = "3", assetCount = 8, hasDescription = true, commentCount = 7, isLiked = false),
    MomentPost(id = "4", assetCount = 2, hasDescription = false, commentCount = 1, isLiked = false),
)

@Composable
private fun MomentPostCard(
    post: MomentPost,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(
                onClick = onClick,
                onClickLabel = stringResource(MR.string.moments_post_open),
            ),
    ) {
        AssetGridTeaser(seedPrefix = post.id)

        // Overlay indicators in the bottom-right corner of the thumbnail.
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (post.hasDescription) IndicatorBadge(Icons.Outlined.Info)
            if (post.commentCount > 0) IndicatorBadge(Icons.Outlined.ChatBubbleOutline)
            IndicatorBadge(
                imageVector = if (post.isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(MR.string.moments_reaction_like),
            )
        }
    }
}

/**
 * 2×2 grid of placeholder asset cells. Real implementation will fan out the
 * post's assets into the four slots (collapsing single-asset posts onto the
 * full square, etc.). Skeleton uses seeded picsum.photos URLs so each post's
 * grid is stable across recompositions.
 */
@Composable
private fun AssetGridTeaser(seedPrefix: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(2) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(2) { col ->
                    val cellIndex = row * 2 + col
                    AsyncImage(
                        model = "https://picsum.photos/seed/$seedPrefix-$cellIndex/400/400",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
            }
        }
    }
}

@Composable
private fun IndicatorBadge(
    imageVector: ImageVector,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}
