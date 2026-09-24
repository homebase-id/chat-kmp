package id.homebase.core.ui.screens.location.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TiledMapViewMarkerDirectionTest {

    @Test
    fun `a marker lands on its projected point under an RTL layout`() = runComposeUiTest {
        var projected = Offset.Zero
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(300.dp)) {
                    TiledMapView(
                        bbox = doubleArrayOf(0.2, 0.2, 0.4, 0.4),
                        showMapTiles = false,
                        fetchTile = { _, _, _ -> null },
                        markerContent = { project, ready ->
                            if (ready) {
                                Box(
                                    Modifier
                                        .offset {
                                            projected = project(0.22, 0.3)
                                            IntOffset(projected.x.roundToInt(), projected.y.roundToInt())
                                        }
                                        .size(10.dp)
                                        .testTag("marker"),
                                )
                            }
                        },
                    )
                }
            }
        }
        waitForIdle()

        val bounds = onNodeWithTag("marker").fetchSemanticsNode().boundsInRoot
        assertEquals(projected.x, bounds.left, absoluteTolerance = 1f)
        assertEquals(projected.y, bounds.top, absoluteTolerance = 1f)
    }
}
