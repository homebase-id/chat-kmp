package id.homebase.core.ui.screens.profile

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.imageeditor.ui.CropResultBus
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun testAvatarViewModel(
    repository: ProfileRepository,
    circles: List<CardCircle>,
    circlePhotosSupported: Boolean = SERVER_STORES_CIRCLE_PHOTOS,
    bus: CropResultBus = CropResultBus(),
) = ProfileAvatarEditViewModel(
    ownerSession = object : AvatarOwnerSession {
        override val user: Flow<OwnerSession?> = MutableStateFlow(null)
        override suspend fun reloadAvatar(odinId: OdinId) = Unit
    },
    profileRepository = repository,
    cropResultBus = bus,
    fileOperationsProvider = object : FileOperationsProvider {
        override fun getCacheDirectory() = System.getProperty("java.io.tmpdir")
        override fun openFileInput(path: String) = error("not used")
        override suspend fun readFileBytes(path: String) = File(path).readBytes()
        override fun deleteTempFile(path: String) = false
        override fun getFileSize(path: String) = File(path).length()
        override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
            File.createTempFile(prefix, suffix).also { it.writeBytes(bytes); it.deleteOnExit() }.path
        override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String) = error("not used")
        override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
    },
    circlePhotosSupported = circlePhotosSupported,
    loadCircles = { circles },
)
