package id.homebase.photobase.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGallery
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.photobase_library_count
import id.homebase.resources.photobase_library_title
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The galleries the library opens on their own, each with how many photos it holds, counted while shown. */
class PhotoLibraryViewModel(days: List<Pair<PhotoGallery, Flow<LibraryDays>>>) : ViewModel() {
    /** Null until a gallery's photos are counted. */
    val counts: StateFlow<Map<PhotoGallery, Int?>> = combine(
        days.map { (gallery, days) -> days.map<LibraryDays, Pair<PhotoGallery, Int?>> { gallery to it.photos }.onStart { emit(gallery to null) } },
    ) { counted -> counted.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), days.associate { it.first to null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoLibraryScreen(viewModel: PhotoLibraryViewModel, onOpen: (PhotoGallery) -> Unit, onBack: () -> Unit) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
                title = { Text(stringResource(MR.string.photobase_library_title)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding)) {
            items(counts.keys.toList(), key = { it.key }) { gallery ->
                val count = counts[gallery]
                ListItem(
                    headlineContent = { Text(stringResource(gallery.title)) },
                    supportingContent = count?.let { { Text(pluralStringResource(MR.plurals.photobase_library_count, it, it)) } },
                    leadingContent = { Icon(gallery.icon, contentDescription = null) },
                    modifier = Modifier.clickable { onOpen(gallery) },
                )
            }
        }
    }
}
