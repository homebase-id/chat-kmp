package id.homebase.core.util

/** Human-readable OS identity for diagnostics. Both fields are never blank; do not parse them. */
expect object Platform {
    /** Android "Android", iOS the UIDevice system name, desktop the `os.name` property, web "Web". */
    val osName: String

    /** Android the SDK level, iOS the system version, desktop `os.version`, web "Unknown". */
    val osVersion: String
}
