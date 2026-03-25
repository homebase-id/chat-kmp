package id.homebase.core.clipboard

import io.github.vinceglb.filekit.PlatformFile

/**
 * Creates a PlatformFile from a file path string.
 * Used for clipboard image paste where we write bytes to a temp file first.
 */
expect fun platformFileFromPath(path: String): PlatformFile
