package id.homebase.chat.calling

sealed interface CallTrayItem {
    data class Status(val name: String, val elapsed: String?) : CallTrayItem
    data class Mute(val muted: Boolean) : CallTrayItem
    data object HangUp : CallTrayItem
}

fun callTrayItems(ui: CallUiState, name: String, elapsed: String? = null): List<CallTrayItem> =
    if (!ui.inProgress) emptyList()
    else listOf(CallTrayItem.Status(name, elapsed), CallTrayItem.Mute(ui.muted), CallTrayItem.HangUp)
