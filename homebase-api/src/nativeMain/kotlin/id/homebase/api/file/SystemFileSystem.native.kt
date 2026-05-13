package id.homebase.api.file

import okio.FileSystem
import okio.SYSTEM

actual val systemFileSystem: FileSystem = FileSystem.SYSTEM
