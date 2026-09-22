package id.homebase.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import id.homebase.resources.MR
import id.homebase.resources.montserrat_alternates_bold
import id.homebase.resources.montserrat_alternates_light
import id.homebase.resources.montserrat_light
import id.homebase.resources.montserrat_regular
import org.jetbrains.compose.resources.Font

/**
 * Design manual pp.12-13: Montserrat Alternates carries headings and the logotype, Montserrat
 * carries body text. Only the login screen draws these today — repointing [HomebaseTypography] is
 * an app-wide restyle that has not been asked for.
 */
object HomebaseFonts {
    val headline: FontFamily
        @Composable get() = FontFamily(
            Font(MR.font.montserrat_alternates_light, FontWeight.Light),
            Font(MR.font.montserrat_alternates_bold, FontWeight.Bold),
        )

    val body: FontFamily
        @Composable get() = FontFamily(
            Font(MR.font.montserrat_light, FontWeight.Light),
            Font(MR.font.montserrat_regular, FontWeight.Normal),
        )
}
