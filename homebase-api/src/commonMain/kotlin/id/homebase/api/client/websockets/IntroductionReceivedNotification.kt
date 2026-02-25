package id.homebase.api.client.websockets

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

@Serializable
data class IntroductionReceivedNotification(
    val introducerOdinId: OdinId,
    val introduction: Introduction
)