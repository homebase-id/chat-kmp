package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsTopBar
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.resources.MR
import id.homebase.resources.photobase_library_count
import id.homebase.resources.photobase_library_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhotoLibraryScreen(viewModel: PhotoLibraryViewModel, onOpen: (PhotoGallery) -> Unit, onBack: () -> Unit) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    Scaffold(topBar = { SettingsTopBar(stringResource(MR.string.photobase_library_title), onBack) }) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            for ((gallery, count) in counts) {
                SettingsRow(
                    icon = gallery.look.icon,
                    title = stringResource(gallery.look.title),
                    supportingText = count?.let { pluralStringResource(MR.plurals.photobase_library_count, it, it) },
                    action = SettingsRowAction.Navigate { onOpen(gallery) },
                )
            }
        }
    }
}
