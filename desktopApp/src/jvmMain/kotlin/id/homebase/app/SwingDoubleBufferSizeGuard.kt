package id.homebase.app

import java.awt.Component
import java.awt.Dimension
import javax.swing.RepaintManager

// AWT clears RepaintManager's double-buffer ceiling on every display change and recomputes it
// from GraphicsEnvironment.getScreenDevices(); on X11 a lock/DPMS/monitor change can enumerate
// zero screens mid-recompute, pinning the ceiling to 0x0 -- after which every double-buffered
// paint clamps its buffer request to 0x0 and throws. A ceiling set here is kept, not recomputed.
internal fun installSwingDoubleBufferSizeGuard() {
    RepaintManager.currentManager(null as Component?).doubleBufferMaximumSize =
        Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
}
