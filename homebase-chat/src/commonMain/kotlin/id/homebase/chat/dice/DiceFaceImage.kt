package id.homebase.chat.dice

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.chat_dice_face_content_description
import id.homebase.resources.chat_dice_face_placeholder_content_description
import id.homebase.resources.d20_01
import id.homebase.resources.d20_02
import id.homebase.resources.d20_03
import id.homebase.resources.d20_04
import id.homebase.resources.d20_05
import id.homebase.resources.d20_06
import id.homebase.resources.d20_07
import id.homebase.resources.d20_08
import id.homebase.resources.d20_09
import id.homebase.resources.d20_10
import id.homebase.resources.d20_11
import id.homebase.resources.d20_12
import id.homebase.resources.d20_13
import id.homebase.resources.d20_14
import id.homebase.resources.d20_15
import id.homebase.resources.d20_16
import id.homebase.resources.d20_17
import id.homebase.resources.d20_18
import id.homebase.resources.d20_19
import id.homebase.resources.d20_20
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.stringResource

/**
 * A single die face. Until per-face art lands for d4/d6/d8/d10/d12 we render the
 * d20 PNG corresponding to [value] for every [faces] choice — by design (the
 * user chose this fallback so dice are usable today). When [value] is null we
 * show a neutral placeholder (face 1) — used by the composer's preview row
 * before the user has rolled.
 */
@Composable
fun DiceFaceImage(
    faces: Int,
    value: Int?,
    modifier: Modifier = Modifier.size(48.dp),
) {
    val resource = facePainter(faces, value)
    val description = if (value != null) {
        stringResource(MR.string.chat_dice_face_content_description, faces, value)
    } else {
        stringResource(MR.string.chat_dice_face_placeholder_content_description, faces)
    }
    // Bitmap overload (rather than painterResource) so we can opt into
    // FilterQuality.High — the painter overload of `Image` doesn't expose it.
    // Source PNGs are 256×256; on-screen sizes are 56dp–96dp, a real downscale
    // where the default bilinear filter leaves text-on-die patterns soft.
    Image(
        bitmap = imageResource(resource),
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
    )
}

private fun facePainter(faces: Int, value: Int?): DrawableResource {
    // All faces fall through to the d20 art set for now — when per-face PNGs
    // arrive (d4_01..d4_04 etc.), branch on `faces` here.
    val safeValue = when {
        value == null -> 1
        value < 1 -> 1
        value > 20 -> 20
        else -> value
    }
    return D20_FACES[safeValue - 1]
}

private val D20_FACES: List<DrawableResource> = listOf(
    MR.drawable.d20_01, MR.drawable.d20_02, MR.drawable.d20_03, MR.drawable.d20_04,
    MR.drawable.d20_05, MR.drawable.d20_06, MR.drawable.d20_07, MR.drawable.d20_08,
    MR.drawable.d20_09, MR.drawable.d20_10, MR.drawable.d20_11, MR.drawable.d20_12,
    MR.drawable.d20_13, MR.drawable.d20_14, MR.drawable.d20_15, MR.drawable.d20_16,
    MR.drawable.d20_17, MR.drawable.d20_18, MR.drawable.d20_19, MR.drawable.d20_20,
)
