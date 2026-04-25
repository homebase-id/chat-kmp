package id.homebase.api.client.connections

interface IntroductionSender {
    suspend fun sendIntroductions(group: IntroductionGroup): IntroductionResult
}
