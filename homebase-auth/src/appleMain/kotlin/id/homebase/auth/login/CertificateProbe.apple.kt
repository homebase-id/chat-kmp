package id.homebase.auth.login

// iOS: capturing the presented cert on a handshake failure goes through the NSURLSession
// server-trust delegate / Security framework — not wired yet. Return null so the TLS error
// still shows the host + exception, just without the issuer line.
internal actual suspend fun probeCertificateInfo(host: String): String? = null
