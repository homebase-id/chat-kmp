package id.homebase.api.client.diagnostics

/** v1 implements the probe on Android only; Web (wasmJs) returns unsupported. */
actual suspend fun runNetworkDiagnostics(hostname: String, lastKnownIp: String?): NetworkDiagnostics =
    unsupportedDiagnostics(hostname)
