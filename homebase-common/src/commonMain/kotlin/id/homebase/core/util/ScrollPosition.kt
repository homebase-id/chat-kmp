package id.homebase.core.util

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

@Immutable
data class ScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val anchorMessageId: Uuid? = null,
    val triggerScroll: Boolean = false,
)