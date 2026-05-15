package id.homebase.api.file

import co.touchlab.kermit.Logger
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Belt-and-suspenders recursive folder delete — THE single entry point for
 * "delete a folder" anywhere in the codebase. Never call
 * [FileSystem.deleteRecursively] directly.
 *
 * The delete target is [relativeSubPath] resolved under [baseDir] (the trusted
 * root the caller operates within — the app cache / data / install directory).
 * The call is refused — logged at ERROR, **nothing deleted**, returns false —
 * unless every one of these holds:
 *  - [baseDir] is non-blank and at least [MIN_BASE_DIR_LENGTH] characters (a
 *    cheap floor against `""`, `"/"`, `"."` being passed as the trusted root);
 *  - [baseDir] is an absolute path with a parent (never a filesystem root);
 *  - [relativeSubPath] is non-blank and NOT absolute;
 *  - [relativeSubPath] contains no `..` segment;
 *  - the resolved target stays strictly *under* [baseDir] — deeper than it and
 *    sharing its full prefix — never equal to [baseDir] itself, never outside.
 *
 * A missing target is not an error: nothing to delete, returns false quietly.
 *
 * @return true only when a real directory existed under [baseDir] and was deleted.
 */
fun safeDeleteRecursively(
    baseDir: String,
    relativeSubPath: String,
    fileSystem: FileSystem = systemFileSystem,
): Boolean {
    // 1. Base dir: non-blank and not trivially short. The length floor is a
    //    cheap extra guard; the real protection is the structural checks below.
    //    Kept low so legitimate short system roots (e.g. "/tmp") still pass.
    if (baseDir.isBlank() || baseDir.length < MIN_BASE_DIR_LENGTH) {
        Logger.e(tag = TAG) {
            "refusing recursive delete: base dir is blank or too short ('$baseDir')"
        }
        return false
    }
    // 2. Relative sub-path: non-blank.
    if (relativeSubPath.isBlank()) {
        Logger.e(tag = TAG) {
            "refusing recursive delete: relative sub-path is blank (base='$baseDir')"
        }
        return false
    }

    val base = runCatching { baseDir.toPath() }.getOrNull()
    // 3. Base dir must be an absolute path with a parent — never a filesystem root.
    if (base == null || !base.isAbsolute || base.parent == null) {
        Logger.e(tag = TAG) {
            "refusing recursive delete: base dir is not an absolute path with a parent ('$baseDir')"
        }
        return false
    }

    val rel = runCatching { relativeSubPath.toPath() }.getOrNull()
    // 4. Relative sub-path must actually be relative.
    if (rel == null || rel.isAbsolute) {
        Logger.e(tag = TAG) {
            "refusing recursive delete: sub-path must be relative, not absolute ('$relativeSubPath', base='$baseDir')"
        }
        return false
    }
    // 5. No '..' segment — reject any traversal outright, even one that would
    //    happen to resolve back under the base.
    if (rel.segments.any { it == ".." }) {
        Logger.e(tag = TAG) {
            "refusing recursive delete: sub-path contains a '..' segment ('$relativeSubPath', base='$baseDir')"
        }
        return false
    }

    // 6. Resolve, and require the target to be STRICTLY under the base: deeper
    //    than it, and sharing its full prefix of segments.
    val normBase = base.normalized()
    val target = (normBase / relativeSubPath).normalized()
    val baseSegs = normBase.segments
    val targetSegs = target.segments
    if (targetSegs.size <= baseSegs.size || targetSegs.subList(0, baseSegs.size) != baseSegs) {
        Logger.e(tag = TAG) {
            "refusing recursive delete: target '$target' is not strictly under base '$normBase'"
        }
        return false
    }

    // All guards passed — delete. A missing target is a quiet no-op.
    return try {
        if (!fileSystem.exists(target)) return false
        fileSystem.deleteRecursively(target)
        true
    } catch (e: Exception) {
        Logger.e(tag = TAG, throwable = e) { "recursive delete failed for '$target'" }
        false
    }
}

/**
 * Floor on [safeDeleteRecursively]'s `baseDir` length. Deliberately low — the
 * real safety comes from the absolute/parent/escape checks — but enough to
 * reject `""`, `"/"`, `"."` outright.
 */
private const val MIN_BASE_DIR_LENGTH = 4

private const val TAG = "SafeDelete"
