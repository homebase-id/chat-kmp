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
import id.homebase.resources.d10_00
import id.homebase.resources.d10_01
import id.homebase.resources.d10_02
import id.homebase.resources.d10_03
import id.homebase.resources.d10_04
import id.homebase.resources.d10_05
import id.homebase.resources.d10_06
import id.homebase.resources.d10_07
import id.homebase.resources.d10_08
import id.homebase.resources.d10_09
import id.homebase.resources.d12_01
import id.homebase.resources.d12_02
import id.homebase.resources.d12_03
import id.homebase.resources.d12_04
import id.homebase.resources.d12_05
import id.homebase.resources.d12_06
import id.homebase.resources.d12_07
import id.homebase.resources.d12_08
import id.homebase.resources.d12_09
import id.homebase.resources.d12_10
import id.homebase.resources.d12_11
import id.homebase.resources.d12_12
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
import id.homebase.resources.d4_01
import id.homebase.resources.d4_02
import id.homebase.resources.d4_03
import id.homebase.resources.d4_04
import id.homebase.resources.d6_01
import id.homebase.resources.d6_02
import id.homebase.resources.d6_03
import id.homebase.resources.d6_04
import id.homebase.resources.d6_05
import id.homebase.resources.d6_06
import id.homebase.resources.d8_01
import id.homebase.resources.d8_02
import id.homebase.resources.d8_03
import id.homebase.resources.d8_04
import id.homebase.resources.d8_05
import id.homebase.resources.d8_06
import id.homebase.resources.d8_07
import id.homebase.resources.d8_08
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.stringResource

/**
 * A single die face. Picks the matching PNG for [faces]+[value]. When [value]
 * is null we show a neutral placeholder (face 1) — used by the composer's
 * preview row before the user has rolled.
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
    val faceList = when (faces) {
        4 -> D4_FACES
        6 -> D6_FACES
        8 -> D8_FACES
        10 -> D10_FACES
        12 -> D12_FACES
        20 -> D20_FACES
        // Unrecognised face count (older sender, future variant) — clamp to d20
        // art so the bubble still has something to draw.
        else -> D20_FACES
    }
    val safeValue = when {
        value == null -> 1
        value < 1 -> 1
        value > faceList.size -> faceList.size
        else -> value
    }
    return faceList[safeValue - 1]
}

private val D4_FACES: List<DrawableResource> = listOf(
    MR.drawable.d4_01, MR.drawable.d4_02, MR.drawable.d4_03, MR.drawable.d4_04,
)

private val D6_FACES: List<DrawableResource> = listOf(
    MR.drawable.d6_01, MR.drawable.d6_02, MR.drawable.d6_03, MR.drawable.d6_04,
    MR.drawable.d6_05, MR.drawable.d6_06,
)

private val D8_FACES: List<DrawableResource> = listOf(
    MR.drawable.d8_01, MR.drawable.d8_02, MR.drawable.d8_03, MR.drawable.d8_04,
    MR.drawable.d8_05, MR.drawable.d8_06, MR.drawable.d8_07, MR.drawable.d8_08,
)

// Tabletop convention: a d10 has faces 1–9 plus a "0" face that represents the
// value 10. Roll values are still 1..10 in `DiceRollDescriptor.results`; we map
// the 10 to the d10_00 PNG so it renders the way physical dice do.
private val D10_FACES: List<DrawableResource> = listOf(
    MR.drawable.d10_01, MR.drawable.d10_02, MR.drawable.d10_03, MR.drawable.d10_04,
    MR.drawable.d10_05, MR.drawable.d10_06, MR.drawable.d10_07, MR.drawable.d10_08,
    MR.drawable.d10_09, MR.drawable.d10_00,
)

private val D12_FACES: List<DrawableResource> = listOf(
    MR.drawable.d12_01, MR.drawable.d12_02, MR.drawable.d12_03, MR.drawable.d12_04,
    MR.drawable.d12_05, MR.drawable.d12_06, MR.drawable.d12_07, MR.drawable.d12_08,
    MR.drawable.d12_09, MR.drawable.d12_10, MR.drawable.d12_11, MR.drawable.d12_12,
)

private val D20_FACES: List<DrawableResource> = listOf(
    MR.drawable.d20_01, MR.drawable.d20_02, MR.drawable.d20_03, MR.drawable.d20_04,
    MR.drawable.d20_05, MR.drawable.d20_06, MR.drawable.d20_07, MR.drawable.d20_08,
    MR.drawable.d20_09, MR.drawable.d20_10, MR.drawable.d20_11, MR.drawable.d20_12,
    MR.drawable.d20_13, MR.drawable.d20_14, MR.drawable.d20_15, MR.drawable.d20_16,
    MR.drawable.d20_17, MR.drawable.d20_18, MR.drawable.d20_19, MR.drawable.d20_20,
)
