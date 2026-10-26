package id.homebase.api.share

/** The one source of the codes stored in settings and published to the iOS extension. */
enum class NotificationContentLevel(val code: String) {
    NAME_CONTENT_ACTIONS("name_content_actions"),
    NAME_ONLY("name_only"),
    NO_NAME_OR_CONTENT("no_name_or_content");

    companion object {
        fun fromCode(code: String): NotificationContentLevel =
            entries.find { it.code == code } ?: NAME_CONTENT_ACTIONS
    }
}
