package id.homebase.upload

/**
 * The video-encoding policy the upload pipeline needs, decoupled from any settings
 * implementation. Lets `PayloadBundleEncryptionService` live in homebase-upload without
 * depending on `homebase-common`'s `UserPreferences` (which implements this).
 */
interface VideoEncodePolicy {
    /**
     * When true, uploaded 10-bit videos keep their 10-bit depth instead of being
     * downconverted to 8-bit. Developer/test escape hatch; default off.
     */
    val allowTenBitVideo: Boolean
}
