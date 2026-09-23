package id.homebase.core.camera

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MirrorPolicyTest {
    @Test
    fun frontLensFollowsPreference() {
        assertTrue(MirrorPolicy.shouldMirror(CameraLens.Front, mirrorFront = true))
        assertFalse(MirrorPolicy.shouldMirror(CameraLens.Front, mirrorFront = false))
    }

    @Test
    fun backLensIsNeverMirrored() {
        assertFalse(MirrorPolicy.shouldMirror(CameraLens.Back, mirrorFront = true))
        assertFalse(MirrorPolicy.shouldMirror(CameraLens.Back, mirrorFront = false))
    }
}
