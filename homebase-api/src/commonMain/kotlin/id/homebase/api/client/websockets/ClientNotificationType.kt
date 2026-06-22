package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable

@Serializable
enum class ClientNotificationType {
    deviceHandshakeSuccess,
    pong,
    fileAdded,
    fileDeleted,
    fileModified,
    connectionRequestReceived,
    connectionFinalized,
    connectionRequestAccepted,
    introductionsReceived,
    introductionAccepted,
    deviceConnected,
    deviceDisconnected,
    inboxItemReceived,
    newFollower,
    connectionChanged,
    circleDefinitionChanged,
    statisticsChanged,
    reactionContentAdded,
    reactionContentDeleted,
    allReactionsByFileDeleted,
    appNotificationAdded,
    liveRelay,
    unused,
    error,
    authenticationError
}
