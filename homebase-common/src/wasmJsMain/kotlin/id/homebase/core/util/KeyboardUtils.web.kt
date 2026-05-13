package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
actual fun keyboardHeightAsState(): State<Int> = remember { mutableStateOf(0) }