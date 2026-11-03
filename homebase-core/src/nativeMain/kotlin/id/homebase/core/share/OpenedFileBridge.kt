package id.homebase.core.share

import co.touchlab.kermit.Logger
import id.homebase.core.ui.screens.openedfile.OpenedFileInbox
import id.homebase.core.ui.screens.openedfile.OpenedFileRequest
import id.homebase.core.ui.screens.openedfile.stageFileCopy
import okio.FileSystem
import okio.Path.Companion.toPath

/** Swift (iOSApp.swift onOpenURL) hands a document another app opened in Homebase to the OpenedFile screen. */
object OpenedFileBridge {
    /** Copies [sourcePath] into [stagingRoot]; the caller holds the security scope and is off the main thread. */
    fun handleIncomingFile(sourcePath: String, displayName: String, mimeType: String?, stagingRoot: String): Boolean =
        try {
            val staged = stageFileCopy(sourcePath, stagingRoot, displayName)
            val size = FileSystem.SYSTEM.metadata(staged.toPath()).size ?: 0L
            OpenedFileInbox.offer(OpenedFileRequest(staged, staged.toPath().name, mimeType, size))
            true
        } catch (e: Exception) {
            Logger.w(e, tag = "OpenedFileBridge") { "couldn't copy the opened document" }
            OpenedFileInbox.reportFailure()
            false
        }
}
