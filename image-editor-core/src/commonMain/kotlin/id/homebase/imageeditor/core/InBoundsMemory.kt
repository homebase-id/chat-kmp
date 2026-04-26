package id.homebase.imageeditor.core

/**
 * Holds the most recent "in-bounds" snapshots of the main-image and crop
 * matrices, so we can fall back to them when a gesture leaves the crop in an
 * unacceptable state.
 *
 * Translated from `InBoundsMemory.java` in Signal-Android (AGPL-3.0).
 */
internal class InBoundsMemory {
    val lastGoodUserCrop: Matrix2D = Matrix2D()
    val lastGoodMainImage: Matrix2D = Matrix2D()

    fun push(mainImage: EditorElement?, userCrop: EditorElement) {
        if (mainImage == null) {
            lastGoodMainImage.reset()
        } else {
            lastGoodMainImage.set(mainImage.localMatrix)
            lastGoodMainImage.preConcat(mainImage.editorMatrix)
        }
        lastGoodUserCrop.set(userCrop.localMatrix)
        lastGoodUserCrop.preConcat(userCrop.editorMatrix)
    }

    fun restore(mainImage: EditorElement?, cropEditorElement: EditorElement) {
        if (mainImage != null) {
            mainImage.localMatrix.set(lastGoodMainImage)
            mainImage.editorMatrix.reset()
        }
        cropEditorElement.localMatrix.set(lastGoodUserCrop)
        cropEditorElement.editorMatrix.reset()
    }
}
