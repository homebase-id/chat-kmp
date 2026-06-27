package id.homebase.auth.login

/**
 * Best-effort diagnostic, run only after a TLS login failure: connect to [host]:443, read the
 * leaf certificate the server (or an interceptor) actually presents, and return a short human
 * description of its issuer — AND whether the root the chain needs is in this device's trust
 * store. So the error can both name the culprit ("issued by AdGuard Personal CA" → interception)
 * and detect a missing root ("chain anchors to [ISRG Root X2] — MISSING" → the device lacks a
 * legit public root, e.g. an old/OEM Android; fix is on our chain, not the user). Returns null
 * when unsupported (iOS/web) or on any error; never throws.
 *
 * Safety (see the JVM/Android actual): it does NOT weaken validation — the platform default
 * trust manager still runs and still rejects an untrusted cert; we only *record* the presented
 * chain in the callback (which fires before the rejection). It sends no application data — the
 * throwaway socket is closed right after the handshake attempt. The production HTTP client's
 * TLS is untouched.
 */
internal expect suspend fun probeCertificateInfo(host: String): String?
