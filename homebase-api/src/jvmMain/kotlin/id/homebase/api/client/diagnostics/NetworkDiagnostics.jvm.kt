package id.homebase.api.client.diagnostics

/** v1 implements the probe on Android only; Desktop (CIO engine) returns unsupported. */
actual suspend fun runNetworkDiagnostics(hostname: String, lastKnownIp: String?): NetworkDiagnostics =
    unsupportedDiagnostics(hostname)
