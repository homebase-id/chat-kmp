package id.homebase.feed.openfile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.openedfile.OPENED_FILE_STAGING_DIR
import id.homebase.core.ui.screens.openedfile.OpenedFileInbox
import id.homebase.core.ui.screens.openedfile.OpenedFileRequest
import id.homebase.core.ui.screens.openedfile.discardStaged
import id.homebase.core.ui.screens.openedfile.reserveStagedPath
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.feed.MainActivity
import id.homebase.resources.MR
import id.homebase.resources.openedfile_open_failed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

private const val TAG = "OpenFileActivity"

/** The "Open with Homebase" target: copies the document while the sender's read grant is live, then hands it to the app. */
class OpenFileActivity : ComponentActivity(), KoinComponent {

    private val fileOperationsProvider: FileOperationsProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomebaseTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        val uri = intent?.data
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            val request = uri?.takeIf { it.scheme == "content" }?.let { stage(it) }
            if (request == null) {
                Toast.makeText(this@OpenFileActivity, getString(MR.string.openedfile_open_failed), Toast.LENGTH_LONG).show()
            } else {
                OpenedFileInbox.offer(request)
                startActivity(
                    Intent(this@OpenFileActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            finish()
        }
    }

    private suspend fun stage(uri: Uri): OpenedFileRequest? = withContext(Dispatchers.IO) {
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        val root = fileOperationsProvider.getCacheDirectory().trimEnd('/') + "/" + OPENED_FILE_STAGING_DIR
        val staged = reserveStagedPath(root, displayName)
        try {
            val input = contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source -> File(staged).outputStream().use { source.copyTo(it) } }
            val type = (intent?.type ?: contentResolver.getType(uri))?.takeIf { it != "application/octet-stream" }
            OpenedFileRequest(staged, File(staged).name, type, File(staged).length())
        } catch (e: Exception) {
            Logger.w(e, TAG) { "couldn't copy the opened document" }
            discardStaged(staged)
            null
        }
    }
}
