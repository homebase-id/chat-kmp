package id.homebase.auth.login

// Web: the browser owns TLS; the app can't read the presented certificate. Return null.
internal actual suspend fun probeCertificateInfo(host: String): String? = null
