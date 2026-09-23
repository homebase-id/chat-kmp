package id.homebase.core.vault

import java.io.File

internal fun linuxAvailability(): Boolean =
    findOnPath("pkcheck", System.getenv("PATH")) != null &&
        File(POLKIT_ACTIONS_DIR, "$ACTION_ID.policy").isFile

internal fun findOnPath(executable: String, path: String?): File? =
    path.orEmpty().split(File.pathSeparator)
        .filter { it.isNotEmpty() }
        .map { File(it, executable) }
        .firstOrNull { it.isFile && it.canExecute() }
