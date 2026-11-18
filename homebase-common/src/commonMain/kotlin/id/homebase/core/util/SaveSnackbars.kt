package id.homebase.core.util

import androidx.compose.material3.SnackbarHostState
import id.homebase.core.localization.TranslationUtil
import id.homebase.resources.MR
import id.homebase.resources.error_unknown
import id.homebase.resources.file_save_failed
import id.homebase.resources.file_saved_to

suspend fun SnackbarHostState.showSavedTo(location: String) =
    showSnackbar(TranslationUtil.getString(MR.string.file_saved_to, location))

suspend fun SnackbarHostState.showSaveFailed(detail: String?) =
    showSnackbar(TranslationUtil.getString(MR.string.file_save_failed, detail ?: TranslationUtil.getString(MR.string.error_unknown)))
