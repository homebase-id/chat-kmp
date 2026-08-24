package id.homebase.api.client.diagnostics

/** v1 implements the probe on Android only; iOS/native (Darwin) returns unsupported. */
actual suspend fun runNetworkDiagnostics(hostname: String, lastKnownIp: String?): NetworkDiagnostics =
    unsupportedDiagnostics(hostname)
