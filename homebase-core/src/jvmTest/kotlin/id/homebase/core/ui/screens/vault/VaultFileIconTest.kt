package id.homebase.core.ui.screens.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.ui.screens.vault.components.fileTypeIcon
import id.homebase.core.ui.screens.vault.components.pageTypeIcon
import id.homebase.core.ui.screens.vault.model.VaultEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class VaultFileIconTest {

    @Test
    fun image_mimeTypes() {
        assertEquals(Icons.Outlined.Image, fileTypeIcon("image/jpeg"))
        assertEquals(Icons.Outlined.Image, fileTypeIcon("image/png"))
        assertEquals(Icons.Outlined.Image, fileTypeIcon("image/webp"))
        assertEquals(Icons.Outlined.Image, fileTypeIcon("image/svg+xml"))
    }

    @Test
    fun video_mimeTypes() {
        assertEquals(Icons.Outlined.VideoFile, fileTypeIcon("video/mp4"))
        assertEquals(Icons.Outlined.VideoFile, fileTypeIcon("video/quicktime"))
    }

    @Test
    fun audio_mimeTypes() {
        assertEquals(Icons.Outlined.AudioFile, fileTypeIcon("audio/mpeg"))
        assertEquals(Icons.Outlined.AudioFile, fileTypeIcon("audio/wav"))
    }

    @Test
    fun pdf() {
        assertEquals(Icons.Outlined.PictureAsPdf, fileTypeIcon("application/pdf"))
    }

    @Test
    fun code_mimeTypes() {
        assertEquals(Icons.Outlined.Code, fileTypeIcon("application/json"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("application/xml"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("application/javascript"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("application/x-sh"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("application/x-yaml"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("text/x-python"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("text/x-kotlin"))
    }

    @Test
    fun spreadsheet_mimeTypes() {
        assertEquals(Icons.Outlined.TableChart, fileTypeIcon("text/csv"))
        assertEquals(Icons.Outlined.TableChart, fileTypeIcon("application/vnd.ms-excel"))
        assertEquals(
            Icons.Outlined.TableChart,
            fileTypeIcon("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
    }

    @Test
    fun presentation_mimeTypes() {
        assertEquals(Icons.Outlined.Slideshow, fileTypeIcon("application/vnd.ms-powerpoint"))
        assertEquals(
            Icons.Outlined.Slideshow,
            fileTypeIcon("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        )
    }

    @Test
    fun document_mimeTypes() {
        assertEquals(Icons.AutoMirrored.Outlined.Article, fileTypeIcon("application/msword"))
        assertEquals(
            Icons.AutoMirrored.Outlined.Article,
            fileTypeIcon("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertEquals(Icons.AutoMirrored.Outlined.Article, fileTypeIcon("application/vnd.oasis.opendocument.text"))
        assertEquals(Icons.AutoMirrored.Outlined.Article, fileTypeIcon("application/rtf"))
    }

    @Test
    fun archive_mimeTypes() {
        assertEquals(Icons.Outlined.FolderZip, fileTypeIcon("application/zip"))
        assertEquals(Icons.Outlined.FolderZip, fileTypeIcon("application/x-tar"))
        assertEquals(Icons.Outlined.FolderZip, fileTypeIcon("application/gzip"))
        assertEquals(Icons.Outlined.FolderZip, fileTypeIcon("application/x-rar-compressed"))
        assertEquals(Icons.Outlined.FolderZip, fileTypeIcon("application/x-7z-compressed"))
    }

    @Test
    fun plainText_mimeTypes() {
        assertEquals(Icons.Outlined.Description, fileTypeIcon("text/plain"))
        assertEquals(Icons.AutoMirrored.Outlined.NoteAdd, fileTypeIcon("text/markdown"))
    }

    @Test
    fun markup_isCode() {
        assertEquals(Icons.Outlined.Code, fileTypeIcon("text/html"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("text/css"))
    }

    @Test
    fun openDocumentSheetsAndSlides() {
        assertEquals(Icons.Outlined.TableChart, fileTypeIcon("application/vnd.oasis.opendocument.spreadsheet"))
        assertEquals(Icons.Outlined.Slideshow, fileTypeIcon("application/vnd.oasis.opendocument.presentation"))
    }

    @Test
    fun unresolvedMime_fallsBackToExtension() {
        assertEquals(Icons.Outlined.FolderZip, fileTypeIcon("application/octet-stream", "archive.zip"))
        assertEquals(Icons.Outlined.PictureAsPdf, fileTypeIcon(null, "report.pdf"))
        assertEquals(Icons.Outlined.Description, fileTypeIcon("application/octet-stream", "notes.md"))
    }

    @Test
    fun pageTypeIcon_onlyFirstPageUsesEntryName() {
        val entry = VaultEntry(
            fileId = Uuid.random(),
            uniqueId = Uuid.random(),
            driveId = Uuid.random(),
            fileName = "archive.zip",
            contentType = "application/octet-stream",
            sizeBytes = 0L,
            createdAt = 0L,
            previewThumbnail = null,
            keyHeader = KeyHeader.empty(),
            isEncrypted = true,
            versionTag = null,
            payloadDescriptors = listOf(
                PayloadDescriptor(key = "vlt_pg_00", contentType = "application/octet-stream"),
                PayloadDescriptor(key = "vlt_pg_01", contentType = "application/octet-stream"),
            ),
        )
        assertEquals(Icons.Outlined.FolderZip, entry.pageTypeIcon(entry.payloadDescriptors[0]))
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, entry.pageTypeIcon(entry.payloadDescriptors[1]))
    }

    @Test
    fun fallback_unknownMimeType() {
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, fileTypeIcon("application/octet-stream"))
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, fileTypeIcon(""))
        assertEquals(Icons.AutoMirrored.Outlined.InsertDriveFile, fileTypeIcon("application/x-unknown"))
    }

    @Test
    fun code_takePrecedenceOverPlainText() {
        // text/x-* should match code, not fall through to text/*
        assertEquals(Icons.Outlined.Code, fileTypeIcon("text/x-java"))
        assertEquals(Icons.Outlined.Code, fileTypeIcon("text/x-c"))
    }
}
