package id.homebase.upload

/**
 * Protocol constants shared by the upload pipeline, independent of any feature module.
 *
 * Lives here (not in chat's `ChatProtocol`) so the shared encrypt/upload code doesn't
 * couple back to the chat module. `ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY`
 * delegates to this value so existing chat call sites are unchanged and there is a
 * single source of truth.
 */
object UploadProtocol {
    /** Prefix for the per-payload descriptor-content key (`pld_desc0`, `pld_desc1`, …). */
    const val DEFAULT_PAYLOAD_DESCRIPTOR_KEY = "pld_desc"
}
