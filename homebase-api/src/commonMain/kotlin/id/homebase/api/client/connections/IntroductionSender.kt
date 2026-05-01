package id.homebase.api.client.connections

interface IntroductionSender {
    suspend fun sendIntroductions(group: IntroductionGroup): IntroductionResult

    /**
     * Best-effort preflight check before [sendIntroductions]. Asks the server to
     * report per-recipient readiness without actually creating any introduction
     * records — useful for showing the user a confirmation dialog when one or
     * more recipients are unreachable / unconfigured / not connected.
     *
     * The server does NOT enforce ordering between preflight and send: callers
     * may skip preflight (e.g. headless paths), and "send anyway" after a
     * non-Ready preflight is still permitted — the outbox retry path on the
     * server handles real failures in the background.
     */
    suspend fun preflightIntroductions(group: IntroductionGroup): IntroductionPreflightResult
}
