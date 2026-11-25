package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGallery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** The galleries the library opens on their own, each with how many photos it holds, counted while shown. */
class PhotoLibraryViewModel(galleries: List<Pair<PhotoGallery, Flow<LibraryDays>>>) : ViewModel() {
    /** Null until a gallery's photos are counted. */
    val counts: StateFlow<Map<PhotoGallery, Int?>> = combine(
        // Null first, or combine waits for the slowest gallery before showing any count.
        galleries.map { (_, days) -> days.map<LibraryDays, Int?> { it.photos }.onStart { emit(null) } },
    ) { counted -> galleries.map { it.first }.zip(counted).toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), galleries.associate { it.first to null })
}
